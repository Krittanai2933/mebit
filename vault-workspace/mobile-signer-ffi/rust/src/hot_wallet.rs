//! Single-sig hot wallet (free BTC). BIP-84 P2WPKH on the account key.
//!
//! Shape matches the BDK hot-wallet half described in `mobile-signer-ffi/README.md`.
//! Address/UTXO/sign/broadcast are real against testnet Esplora; full `bdk_wallet`
//! persistence and coin selection land when Android binary size is measured.
//!
//! Every call rescans the account from Esplora: both BIP-84 chains, external `0/i` and internal
//! `1/i`, until `GAP_LIMIT` consecutive unused indices follow the last used one. Nothing is kept
//! between calls, so the same words and passphrase always give the same answer.

use std::collections::HashSet;
use std::str::FromStr;
use std::sync::LazyLock;
use std::time::Duration;

use bip39::Mnemonic;
use bitcoin::absolute::LockTime;
use bitcoin::bip32::{ChildNumber, DerivationPath, Xpriv};
use bitcoin::consensus::encode::serialize_hex;
use bitcoin::hashes::Hash;
use bitcoin::secp256k1::{All, Message, Secp256k1};
use bitcoin::{
    Address, Amount, CompressedPublicKey, Network, OutPoint, PrivateKey, ScriptBuf, Sequence,
    Transaction, TxIn, TxOut, Txid, Witness, sighash::EcdsaSighashType, sighash::SighashCache,
};
use serde_json::Value;

use crate::{KeyNetwork, MobileSignerError};

const ESPLORA_TESTNET4: &str = "https://mempool.space/testnet4/api";
const ACCOUNT_PATH: &str = "m/84'/1'/0'";

/// BIP-44 gap limit. A chain is scanned until this many consecutive unused indices follow the last
/// used one, so funds paid to an address further out than that are not seen or spent.
const GAP_LIMIT: u32 = 20;
/// Most indices scanned per chain. The Esplora host is a trusted dependency: a host that lies about
/// which addresses are used can still place change at any internal index below this cap, where an
/// honest rescan would not find it. The cap bounds that damage and the call count (a lying host got
/// 5,010 calls before). shortcut: 200 per chain, raise it when a real wallet's chain nears it.
const MAX_SCAN_PER_CHAIN: usize = 200;
// Lookups in flight at once. Ten fills a fresh chain's gap in two windows and is a small burst for a
// public explorer.
const WINDOW: usize = 10;

// Esplora connects in well under a second. Past 10 s on a weak phone network the host is not answering.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
// Per read and per write. ureq 2 has neither by default, so a server that went silent held the call for good.
const IO_TIMEOUT: Duration = Duration::from_secs(20);

static AGENT: LazyLock<ureq::Agent> = LazyLock::new(|| {
    ureq::AgentBuilder::new()
        .timeout_connect(CONNECT_TIMEOUT)
        .timeout_read(IO_TIMEOUT)
        .timeout_write(IO_TIMEOUT)
        .build()
});

// After an unclear broadcast: about 6 s of asking, for the explorer to index what its node took.
const LOOKUPS: u32 = 4;
const LOOKUP_PAUSE: Duration = Duration::from_secs(2);

// What a node says about a transaction it already holds, so the payment is out. "outputs already in
// utxo set" is what blockstream.info testnet said on 2026-11-02; older Bitcoin Core says "already in
// block chain".
const ALREADY_HELD: [&str; 5] = [
    "txn-already-in-mempool",
    "txn-already-known",
    "already in block chain",
    "already in utxo set",
    "already in the utxo set",
];

/// One GET of an Esplora URL, the whole body as text. Injected so the wallet logic runs without a
/// network in tests.
type Esplora<'a> = &'a (dyn Fn(&str) -> Result<String, MobileSignerError> + Sync);

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct HotWalletSnapshot {
    pub receive_address: String,
    pub balance_sats: u64,
    pub network: KeyNetwork,
}

fn network_of(key_network: KeyNetwork) -> Network {
    match key_network {
        KeyNetwork::Main => Network::Bitcoin,
        KeyNetwork::Test => Network::Testnet4,
    }
}

fn esplora_base(network: KeyNetwork) -> Result<&'static str, MobileSignerError> {
    match network {
        KeyNetwork::Test => Ok(ESPLORA_TESTNET4),
        KeyNetwork::Main => Err(MobileSignerError::InvalidPsbtJson(
            "mainnet Esplora is not enabled in this build".into(),
        )),
    }
}

fn mnemonic_from(words: &[String]) -> Result<Mnemonic, MobileSignerError> {
    let phrase = words.join(" ");
    Mnemonic::parse_normalized(&phrase)
        .map_err(|e| MobileSignerError::InvalidPsbtJson(e.to_string()))
}

#[derive(Clone, Copy)]
enum Chain {
    External = 0,
    Internal = 1,
}

struct OwnKey {
    privkey: PrivateKey,
    pubkey: CompressedPublicKey,
    address: Address,
}

/// The BIP-84 account's two chain keys, derived once per call. `key` derives one index offline.
struct Account {
    secp: Secp256k1<All>,
    network: Network,
    chains: [Xpriv; 2],
}

impl Account {
    fn new(
        words: &[String],
        passphrase: &str,
        network: Network,
    ) -> Result<Self, MobileSignerError> {
        let bad = |e: bitcoin::bip32::Error| MobileSignerError::InvalidPsbtJson(e.to_string());
        let mnemonic = mnemonic_from(words)?;
        let secp = Secp256k1::new();
        let master = Xpriv::new_master(network, &mnemonic.to_seed(passphrase)).map_err(bad)?;
        let path = DerivationPath::from_str(ACCOUNT_PATH).map_err(bad)?;
        let account = master.derive_priv(&secp, &path).map_err(bad)?;
        let chain = |index| account.derive_priv(&secp, &[ChildNumber::Normal { index }]);
        let chains = [chain(0).map_err(bad)?, chain(1).map_err(bad)?];
        Ok(Self {
            secp,
            network,
            chains,
        })
    }

    fn key(&self, chain: Chain, index: u32) -> Result<OwnKey, MobileSignerError> {
        let bad = |e: String| MobileSignerError::InvalidPsbtJson(e);
        let step = ChildNumber::from_normal_idx(index).map_err(|e| bad(e.to_string()))?;
        let child = self.chains[chain as usize]
            .derive_priv(&self.secp, &[step])
            .map_err(|e| bad(e.to_string()))?;
        let privkey = PrivateKey::new(child.private_key, self.network);
        let pubkey = CompressedPublicKey::from_private_key(&self.secp, &privkey)
            .map_err(|e| bad(e.to_string()))?;
        let address = Address::p2wpkh(&pubkey, self.network);
        Ok(OwnKey {
            privkey,
            pubkey,
            address,
        })
    }
}

// Retries are for a GET only. A retried broadcast could pay twice, so `broadcast_signed` has none.
const RETRIES: usize = 2;
const BACKOFF: [Duration; RETRIES] = [Duration::from_secs(1), Duration::from_secs(2)];
const MAX_RETRY_AFTER: Duration = Duration::from_secs(5);
const MAX_EXTRA_WAIT: Duration = Duration::from_secs(8);

enum GetError {
    /// Throttled or a brief server error, answered quickly: the same GET may be asked again.
    Retryable {
        retry_after: Option<Duration>,
        what: String,
    },
    Fatal(MobileSignerError),
}

/// Asks up to `RETRIES + 1` times and returns only a body some attempt got as a 200. The waits add up
/// to at most `MAX_EXTRA_WAIT`. shortcut: Retry-After in HTTP-date form is ignored, upgrade if the
/// host is seen sending it.
fn get_with_retry(
    attempt: &dyn Fn() -> Result<String, GetError>,
    sleep: &dyn Fn(Duration),
) -> Result<String, MobileSignerError> {
    let mut slept = Duration::ZERO;
    for n in 0.. {
        match attempt() {
            Ok(body) => return Ok(body),
            Err(GetError::Fatal(e)) => return Err(e),
            Err(GetError::Retryable { retry_after, what }) => {
                if n == RETRIES {
                    return Err(MobileSignerError::InvalidPsbtJson(format!(
                        "esplora get: {what}, gave up after {} attempts",
                        n + 1
                    )));
                }
                let wait = retry_after
                    .map_or(BACKOFF.get(n).copied().unwrap_or_default(), |d| {
                        d.min(MAX_RETRY_AFTER)
                    })
                    .min(MAX_EXTRA_WAIT - slept);
                sleep(wait);
                slept += wait;
            }
        }
    }
    unreachable!()
}

// A timeout is not retried: a host that stayed silent for the connect or read limit will not answer
// after a one second pause, and a retry would multiply the stall of a scan that fails anyway.
fn classify(answer: Result<ureq::Response, ureq::Error>) -> Result<String, GetError> {
    let fatal = |what: &str, e: &dyn std::fmt::Display| {
        GetError::Fatal(MobileSignerError::InvalidPsbtJson(format!("{what}: {e}")))
    };
    match answer {
        Ok(response) => response
            .into_string()
            .map_err(|e| fatal("esplora body", &e)),
        Err(ureq::Error::Status(code @ (429 | 502 | 503 | 504), response)) => {
            let retry_after = response
                .header("Retry-After")
                .and_then(|v| v.trim().parse::<u64>().ok())
                .map(Duration::from_secs);
            Err(GetError::Retryable {
                retry_after,
                what: format!("HTTP status {code}"),
            })
        }
        Err(e) => Err(fatal("esplora get", &e)),
    }
}

fn http_get(url: &str) -> Result<String, MobileSignerError> {
    get_with_retry(&|| classify(AGENT.get(url).call()), &std::thread::sleep)
}

/// Every URL's body, in the order given, `WINDOW` requests in flight at a time. One failure fails
/// the whole batch, so a caller never works from a partial picture of the wallet.
fn get_all(esplora: Esplora, urls: &[String]) -> Result<Vec<String>, MobileSignerError> {
    let mut bodies = Vec::with_capacity(urls.len());
    for window in urls.chunks(WINDOW) {
        let answers = std::thread::scope(|scope| {
            let handles: Vec<_> = window
                .iter()
                .map(|url| scope.spawn(move || esplora(url.as_str())))
                .collect();
            handles.into_iter().map(|h| h.join()).collect::<Vec<_>>()
        });
        for answer in answers {
            let body = answer.map_err(|_| {
                MobileSignerError::InvalidPsbtJson("esplora get: lookup thread panicked".into())
            })??;
            bodies.push(body);
        }
    }
    Ok(bodies)
}

struct Scanned {
    key: OwnKey,
    used: bool,
    balance: u64,
}

/// Both chains, every scanned index in order. Each chain holds `GAP_LIMIT` unused indices past its
/// last used one, so the next unused key of either chain is always present.
struct Wallet {
    chains: [Vec<Scanned>; 2],
}

impl Wallet {
    fn next_unused(&self, chain: Chain) -> &OwnKey {
        let scanned = &self.chains[chain as usize];
        let next = scanned
            .iter()
            .rposition(|s| s.used)
            .map_or(0, |last| last + 1);
        &scanned[next].key
    }

    fn used(&self) -> impl Iterator<Item = (Chain, &OwnKey)> {
        [Chain::External, Chain::Internal]
            .into_iter()
            .zip(&self.chains)
            .flat_map(|(chain, scanned)| {
                scanned
                    .iter()
                    .filter(|s| s.used)
                    .map(move |s| (chain, &s.key))
            })
    }

    fn addresses(&self) -> Vec<String> {
        self.chains
            .iter()
            .flatten()
            .map(|s| s.key.address.to_string())
            .collect()
    }

    fn balance(&self) -> Result<u64, MobileSignerError> {
        self.chains
            .iter()
            .flatten()
            .try_fold(0u64, |sum, s| sum.checked_add(s.balance))
            .ok_or_else(|| {
                MobileSignerError::InvalidPsbtJson(
                    "esplora balance: the sum over the wallet's addresses does not fit u64".into(),
                )
            })
    }
}

fn scan_wallet(
    esplora: Esplora,
    words: &[String],
    passphrase: &str,
    network: KeyNetwork,
) -> Result<Wallet, MobileSignerError> {
    let account = Account::new(words, passphrase, network_of(network))?;
    let base = esplora_base(network)?;
    Ok(Wallet {
        chains: [
            scan_chain(esplora, base, &account, Chain::External)?,
            scan_chain(esplora, base, &account, Chain::Internal)?,
        ],
    })
}

fn scan_chain(
    esplora: Esplora,
    base: &str,
    account: &Account,
    chain: Chain,
) -> Result<Vec<Scanned>, MobileSignerError> {
    let mut scanned: Vec<Scanned> = Vec::new();
    loop {
        let after_last_used = scanned
            .iter()
            .rposition(|s| s.used)
            .map_or(0, |last| last + 1);
        let wanted = after_last_used + GAP_LIMIT as usize;
        if wanted > MAX_SCAN_PER_CHAIN {
            return Err(MobileSignerError::InvalidPsbtJson(format!(
                "esplora scan: more than {MAX_SCAN_PER_CHAIN} addresses on one chain, the history is abnormal"
            )));
        }
        if scanned.len() >= wanted {
            return Ok(scanned);
        }
        let from = scanned.len();
        let keys = (from..wanted.min(from + WINDOW))
            .map(|i| account.key(chain, i as u32))
            .collect::<Result<Vec<_>, _>>()?;
        let urls: Vec<String> = keys
            .iter()
            .map(|k| format!("{base}/address/{}", k.address))
            .collect();
        let answers = get_all(esplora, &urls)?;
        for (key, raw) in keys.into_iter().zip(answers) {
            let stats = parse_address_stats(&raw)?;
            scanned.push(Scanned {
                key,
                used: stats.used,
                balance: stats.balance,
            });
        }
    }
}

// The `///` text on each export stays as committed: UniFFI hashes it into the checksum the committed
// Kotlin bindings pin. What each call does now is on the inner function it hands over to.
/// First receive address at BIP-84 index `index` (usually 0). `passphrase` is the
/// BIP-39 passphrase the key was restored with, empty for none: a different
/// passphrase is a different wallet.
#[uniffi::export]
pub fn hot_receive_address(
    mnemonic_words: Vec<String>,
    passphrase: String,
    network: KeyNetwork,
    index: u32,
) -> Result<String, MobileSignerError> {
    let account = Account::new(&mnemonic_words, &passphrase, network_of(network))?;
    Ok(account.key(Chain::External, index)?.address.to_string())
}

/// Confirmed + mempool balance for receive index 0 via Esplora.
#[uniffi::export]
pub fn hot_wallet_snapshot(
    mnemonic_words: Vec<String>,
    passphrase: String,
    network: KeyNetwork,
) -> Result<HotWalletSnapshot, MobileSignerError> {
    snapshot_from(&http_get, &mnemonic_words, &passphrase, network)
}

/// The first unused receive address and the confirmed + mempool balance over every address of both
/// chains. About 40 Esplora calls for a fresh wallet, so call it off the main thread.
fn snapshot_from(
    esplora: Esplora,
    words: &[String],
    passphrase: &str,
    network: KeyNetwork,
) -> Result<HotWalletSnapshot, MobileSignerError> {
    let wallet = scan_wallet(esplora, words, passphrase, network)?;
    Ok(HotWalletSnapshot {
        receive_address: wallet.next_unused(Chain::External).address.to_string(),
        balance_sats: wallet.balance()?,
        network,
    })
}

struct AddressStats {
    used: bool,
    balance: u64,
}

// Defaulting a missing field to 0 would show a Wi-Fi sign-in page as an empty wallet.
fn parse_address_stats(json: &str) -> Result<AddressStats, MobileSignerError> {
    let bad = |what: String| MobileSignerError::InvalidPsbtJson(format!("esplora balance: {what}"));
    let body: Value = serde_json::from_str(json).map_err(|e| bad(format!("not JSON: {e}")))?;
    if !body.is_object() {
        return Err(bad("not an object".into()));
    }
    let sum = |section: &str, field: &str| {
        body[section][field]
            .as_u64()
            .map(u128::from)
            .ok_or_else(|| bad(format!("no unsigned integer {section}.{field}")))
    };
    // Subtract totals. A coin can be funded in chain_stats and spent in mempool_stats.
    let funded = sum("chain_stats", "funded_txo_sum")? + sum("mempool_stats", "funded_txo_sum")?;
    let spent = sum("chain_stats", "spent_txo_sum")? + sum("mempool_stats", "spent_txo_sum")?;
    let balance = funded
        .checked_sub(spent)
        .ok_or_else(|| bad(format!("spent {spent} is more than funded {funded}")))?;
    let balance =
        u64::try_from(balance).map_err(|_| bad(format!("{balance} sats does not fit u64")))?;
    let txs = sum("chain_stats", "tx_count")? + sum("mempool_stats", "tx_count")?;
    if txs == 0 && (funded > 0 || spent > 0) {
        return Err(bad(format!(
            "tx_count is 0 but funded {funded} and spent {spent}, the answer contradicts itself"
        )));
    }
    Ok(AddressStats {
        used: txs > 0,
        balance,
    })
}

/// One transaction of the hot wallet, seen from the wallet's side.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct HotTx {
    pub txid: String,
    /// Sats the wallet gained (positive) or lost (negative), fee included: the outputs paying any
    /// of the wallet's addresses minus the inputs that spent from them. A payment to yourself, and
    /// change, read as minus the fee.
    pub net_sats: i64,
    /// 0 while in the mempool, 1 in the newest block, and so on. Never 0 once confirmed.
    pub confirmations: u32,
    /// Unix seconds of the block that holds it. Null while unconfirmed, and when the server gives
    /// 0 or a time after the start of 2100, which the app could not print.
    pub block_time: Option<u64>,
}

// 2100-01-01T00:00:00Z. The app prints a block time with a date formatter that wraps on absurd
// values, so a server must not be able to hand it one.
const LATEST_BLOCK_TIME: u64 = 4_102_444_800;

/// The latest transactions of the wallet's address, each as the wallet saw it. The order is
/// Esplora's: the mempool first, then confirmed transactions newest first. Only the first page
/// comes back (about 25 confirmed transactions plus the mempool), so older history is not here.
///
/// Makes two network calls, so call it off the main thread. An answer it cannot read fails the
/// whole call, never one transaction.
#[uniffi::export]
pub fn hot_wallet_history(
    mnemonic_words: Vec<String>,
    passphrase: String,
    network: KeyNetwork,
) -> Result<Vec<HotTx>, MobileSignerError> {
    history_of(&http_get, &mnemonic_words, &passphrase, network)
}

/// The latest transactions of every used address of the wallet, each once, as the wallet saw it:
/// the mempool first, then confirmed transactions newest first. Only each address's first page
/// comes back (about 25 confirmed transactions plus the mempool), so older history is not here.
///
/// Makes a network call per scanned address and per used address, so call it off the main thread.
/// An answer it cannot read fails the whole call, never one transaction.
fn history_of(
    esplora: Esplora,
    words: &[String],
    passphrase: &str,
    network: KeyNetwork,
) -> Result<Vec<HotTx>, MobileSignerError> {
    let base = esplora_base(network)?;
    let wallet = scan_wallet(esplora, words, passphrase, network)?;
    // shortcut: first page only per address, upgrade when a full History screen exists
    let urls: Vec<String> = wallet
        .used()
        .map(|(_, k)| format!("{base}/address/{}/txs", k.address))
        .collect();
    if urls.is_empty() {
        return Ok(Vec::new());
    }
    let pages = get_all(esplora, &urls)?;
    // Second, so a block landing between the calls only raises the tip above every listed tx.
    let tip = esplora(&format!("{base}/blocks/tip/height"))?;
    let own = wallet.addresses();
    let own: Vec<&str> = own.iter().map(String::as_str).collect();
    merge_history(&pages, &tip, &own)
}

fn merge_history(
    pages: &[String],
    tip_text: &str,
    own: &[&str],
) -> Result<Vec<HotTx>, MobileSignerError> {
    let mut seen = HashSet::new();
    let mut all: Vec<HotTx> = Vec::new();
    for page in pages {
        let page = history_from(page, tip_text, own)?;
        all.extend(page.into_iter().filter(|tx| seen.insert(tx.txid.clone())));
    }
    // Esplora lists the mempool first, then the newest block first. A stable sort on
    // confirmations keeps that order once the pages are merged.
    all.sort_by_key(|tx| tx.confirmations);
    Ok(all)
}

fn history_from(
    txs_json: &str,
    tip_text: &str,
    own: &[&str],
) -> Result<Vec<HotTx>, MobileSignerError> {
    let bad = |what: String| MobileSignerError::InvalidPsbtJson(format!("esplora history: {what}"));
    let tip: u64 = tip_text
        .trim()
        .parse()
        .map_err(|e| bad(format!("tip height is not an integer: {e}")))?;
    let body: Value = serde_json::from_str(txs_json).map_err(|e| bad(format!("not JSON: {e}")))?;
    let txs = body.as_array().ok_or_else(|| bad("not an array".into()))?;
    // A bad transaction fails the list. Skipping it would show a history that is quietly wrong.
    txs.iter()
        .enumerate()
        .map(|(i, tx)| hot_tx(tx, tip, own).map_err(|e| bad(format!("tx {i}: {e}"))))
        .collect()
}

fn hot_tx(tx: &Value, tip: u64, own: &[&str]) -> Result<HotTx, String> {
    let txid = tx["txid"].as_str().ok_or("no string txid")?;
    let vin = tx["vin"].as_array().ok_or("vin is not an array")?;
    let vout = tx["vout"].as_array().ok_or("vout is not an array")?;
    let is_own = |txo: &Value| {
        txo["scriptpubkey_address"]
            .as_str()
            .is_some_and(|a| own.contains(&a))
    };

    let mut net = 0i64;
    for (j, output) in vout.iter().enumerate() {
        let value = sats(&output["value"])
            .ok_or_else(|| format!("output {j} has no unsigned integer value"))?;
        if is_own(output) {
            net = net.checked_add(value).ok_or("net sats overflow")?;
        }
    }
    for (j, input) in vin.iter().enumerate() {
        let prevout = &input["prevout"];
        if prevout.is_null() && input["is_coinbase"] == true {
            continue;
        }
        let value = sats(&prevout["value"])
            .ok_or_else(|| format!("input {j} has no prevout with an unsigned integer value"))?;
        if is_own(prevout) {
            net = net.checked_sub(value).ok_or("net sats overflow")?;
        }
    }

    let status = &tx["status"];
    let (confirmations, block_time) = if status["confirmed"]
        .as_bool()
        .ok_or("status.confirmed is not a boolean")?
    {
        let height = status["block_height"]
            .as_u64()
            .ok_or("confirmed without an integer block_height")?;
        // A lagging backend or a reorg can leave the tip below the height. That still reads 1.
        let depth = tip.saturating_sub(height).saturating_add(1);
        let time = status["block_time"]
            .as_u64()
            .filter(|t| (1..=LATEST_BLOCK_TIME).contains(t));
        (u32::try_from(depth).unwrap_or(u32::MAX), time)
    } else {
        (0, None)
    };
    Ok(HotTx {
        txid: txid.to_string(),
        net_sats: net,
        confirmations,
        block_time,
    })
}

// i64 because a net is signed, and a value above i64::MAX is no real amount.
fn sats(value: &Value) -> Option<i64> {
    value.as_u64().and_then(|n| i64::try_from(n).ok())
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct HotSendResult {
    pub txid: String,
}

/// True when `address` parses and belongs to `network`: the same test `hot_send` applies to its destination.
#[uniffi::export]
pub fn is_valid_address(address: String, network: KeyNetwork) -> bool {
    Address::from_str(&address).is_ok_and(|a| a.require_network(network_of(network)).is_ok())
}

/// Build, sign (BIP-84 index 0 key), and broadcast a payment. Fee is absolute sats.
///
/// The txid it returns is computed from the signed transaction, never read from the server. When the
/// explorer's answer to the broadcast leaves it unclear whether the payment went out, it looks that
/// txid up a few times and fails with `BroadcastUnknown` if it is still not found. The payment may be
/// out then, so the app must not offer it as a failure to retry. A silent explorer can hold this call
/// for about two minutes, so call it off the main thread.
#[uniffi::export]
pub fn hot_send(
    mnemonic_words: Vec<String>,
    passphrase: String,
    network: KeyNetwork,
    destination: String,
    amount_sats: u64,
    fee_sats: u64,
) -> Result<HotSendResult, MobileSignerError> {
    let prepared = hot_prepare_send(
        mnemonic_words,
        passphrase,
        network,
        destination,
        amount_sats,
        fee_sats,
    )?;
    hot_broadcast(network, prepared.hex, prepared.txid)
}

/// A signed payment that has not been posted anywhere.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct PreparedSend {
    /// Computed from the signed transaction here, never read from a server.
    pub txid: String,
    /// The signed transaction, consensus-encoded, for `hot_broadcast`.
    pub hex: String,
    /// The fee the transaction pays, in sats. Equal to `fee_sats` as asked, because a payment whose
    /// change would be dust is refused instead of being folded into the fee.
    pub fee_sats: u64,
}

/// Scan, select coins, check and sign a payment, and post nothing. The only network use is the
/// Esplora lookups of the scan. The app saves the `txid` as its payment guard before it calls
/// `hot_broadcast`, so a kill between the two can never lead to a second payment.
///
/// Refuses, before signing, an amount below the 546 sat dust limit, a fee above the amount, change
/// that would be dust (above 0 and at most 546 sats), and a fee rate below 1 or above 2,000 sat/vB
/// for the transaction as built. Only confirmed coins of receive addresses are spent; the wallet's
/// own unconfirmed change is spent too. About 40 Esplora calls for a fresh wallet, so call it off
/// the main thread.
#[uniffi::export]
pub fn hot_prepare_send(
    mnemonic_words: Vec<String>,
    passphrase: String,
    network: KeyNetwork,
    destination: String,
    amount_sats: u64,
    fee_sats: u64,
) -> Result<PreparedSend, MobileSignerError> {
    let signed = signed_payment(
        &http_get,
        &mnemonic_words,
        &passphrase,
        network,
        &destination,
        amount_sats,
        fee_sats,
    )?;
    Ok(prepared(&signed, fee_sats))
}

fn prepared(signed: &Transaction, fee_sats: u64) -> PreparedSend {
    PreparedSend {
        txid: signed.compute_txid().to_string(),
        hex: serialize_hex(signed),
        fee_sats,
    }
}

/// Post a transaction from `hot_prepare_send` once. `txid` must be the id of the transaction in
/// `hex`, else it refuses before posting, so nothing else can go out under a saved guard.
///
/// When the explorer's answer leaves it unclear whether the payment went out, it looks `txid` up a
/// few times and fails with `BroadcastUnknown` if it is still not found. The payment may be out
/// then, so the app must not offer it as a failure to retry. A silent explorer can hold this call
/// for about two minutes, so call it off the main thread.
#[uniffi::export]
pub fn hot_broadcast(
    network: KeyNetwork,
    hex: String,
    txid: String,
) -> Result<HotSendResult, MobileSignerError> {
    let base = esplora_base(network)?;
    let post = |hex: &str| {
        AGENT
            .post(&format!("{base}/tx"))
            .set("Content-Type", "text/plain")
            .send_string(hex)
    };
    // Not /tx/{txid}/status: Esplora answers that one 200 for a txid it never saw (blockstream.info, 2026-11-02).
    let lookup = |txid: &str| AGENT.get(&format!("{base}/tx/{txid}")).call();
    let pause = || std::thread::sleep(LOOKUP_PAUSE);
    let txid = broadcast_matching(&post, &lookup, &pause, &hex, &txid)?;
    Ok(HotSendResult { txid })
}

/// `broadcast_signed`, after checking that `hex` is a transaction whose id is `txid`.
fn broadcast_matching(
    post: &dyn Fn(&str) -> Answer,
    lookup: &dyn Fn(&str) -> Answer,
    pause: &dyn Fn(),
    hex: &str,
    txid: &str,
) -> Result<String, MobileSignerError> {
    let tx: Transaction = bitcoin::consensus::encode::deserialize_hex(hex).map_err(|e| {
        MobileSignerError::InvalidPsbtJson(format!("broadcast: not a transaction: {e}"))
    })?;
    let computed = tx.compute_txid().to_string();
    if computed != txid {
        return Err(MobileSignerError::InvalidPsbtJson(format!(
            "broadcast: txid {txid} does not match the transaction {computed}"
        )));
    }
    broadcast_signed(post, lookup, pause, hex, txid)
}

/// Smallest output the network relays. The same floor on the amount and on change.
const DUST: u64 = 546;
const MIN_FEE_RATE: u64 = 1;
const MAX_FEE_RATE: u64 = 2_000;

/// Virtual size of a P2WPKH transaction with `inputs` and `outputs`, rounded up: 10.5 vB overhead
/// (version, locktime, counts, segwit marker and flag) plus 68 vB per input and 31 vB per output.
fn p2wpkh_vsize(inputs: usize, outputs: usize) -> u64 {
    let twice = 21 + 136 * inputs as u64 + 62 * outputs as u64;
    twice.div_ceil(2)
}

/// A payment from the coins of every used address, each input signed with its own key, any change
/// paid to the first unused internal (change) address, not yet broadcast. The amount, the fee and
/// the destination are checked before any network call; the change and the fee rate after the coins
/// are chosen. Every refusal is an error, never a changed payment.
fn signed_payment(
    esplora: Esplora,
    words: &[String],
    passphrase: &str,
    network: KeyNetwork,
    destination: &str,
    amount_sats: u64,
    fee_sats: u64,
) -> Result<Transaction, MobileSignerError> {
    let refuse = |what: String| Err(MobileSignerError::InvalidPsbtJson(what));
    if amount_sats < DUST {
        return refuse(format!(
            "amount {amount_sats} is below the {DUST} sat dust limit"
        ));
    }
    if fee_sats > amount_sats {
        return refuse(format!(
            "fee {fee_sats} is more than the amount {amount_sats}"
        ));
    }
    let need = amount_sats.checked_add(fee_sats).ok_or_else(|| {
        MobileSignerError::InvalidPsbtJson("amount plus fee does not fit u64".into())
    })?;
    let net = network_of(network);
    let dest = Address::from_str(destination)
        .map_err(|e| MobileSignerError::InvalidPsbtJson(e.to_string()))?
        .require_network(net)
        .map_err(|e| MobileSignerError::InvalidPsbtJson(e.to_string()))?;

    let base = esplora_base(network)?;
    let wallet = scan_wallet(esplora, words, passphrase, network)?;
    let owners: Vec<(Chain, &OwnKey)> = wallet.used().collect();
    let urls: Vec<String> = owners
        .iter()
        .map(|(_, k)| format!("{base}/address/{}/utxo", k.address))
        .collect();
    let lists = get_all(esplora, &urls)?;
    let mut seen = HashSet::new();
    // Own change is spent unconfirmed: it is this wallet's own transaction, and waiting for it would
    // lock the balance after every payment.
    let mut spendable: Vec<(Utxo, &OwnKey)> = Vec::new();
    for ((chain, owner), list) in owners.iter().zip(&lists) {
        for utxo in parse_utxos(list)? {
            if !seen.insert((utxo.txid, utxo.vout)) {
                return refuse(format!(
                    "esplora utxo: {}:{} is listed twice",
                    utxo.txid, utxo.vout
                ));
            }
            if utxo.confirmed || matches!(chain, Chain::Internal) {
                spendable.push((utxo, owner));
            }
        }
    }
    let overflow = || {
        MobileSignerError::InvalidPsbtJson("esplora utxo: the coins' values do not fit u64".into())
    };
    let have = spendable
        .iter()
        .try_fold(0u64, |sum, (u, _)| sum.checked_add(u.value))
        .ok_or_else(overflow)?;
    if have < need {
        return refuse(format!(
            "insufficient funds: have {have} confirmed, need {need}"
        ));
    }
    let mut selected = Vec::new();
    let mut total = 0u64;
    for (utxo, owner) in spendable {
        total = total.checked_add(utxo.value).ok_or_else(overflow)?;
        selected.push((utxo, owner));
        if total >= need {
            break;
        }
    }
    let change = total - need;
    if change > 0 && change <= DUST {
        return refuse("change below dust, send the whole balance or change the amount".into());
    }
    let outputs = if change > 0 { 2 } else { 1 };
    let vsize = p2wpkh_vsize(selected.len(), outputs);
    if fee_sats < MIN_FEE_RATE * vsize {
        return refuse(format!(
            "fee {fee_sats} is below {MIN_FEE_RATE} sat/vB for {vsize} vB"
        ));
    }
    if fee_sats > MAX_FEE_RATE * vsize {
        return refuse(format!(
            "fee {fee_sats} is above {MAX_FEE_RATE} sat/vB for {vsize} vB"
        ));
    }

    let secp = Secp256k1::new();
    let mut tx = Transaction {
        version: bitcoin::transaction::Version::TWO,
        lock_time: LockTime::ZERO,
        input: selected
            .iter()
            .map(|(u, _)| TxIn {
                previous_output: OutPoint {
                    txid: u.txid,
                    vout: u.vout,
                },
                script_sig: ScriptBuf::new(),
                sequence: Sequence::ENABLE_RBF_NO_LOCKTIME,
                witness: Witness::new(),
            })
            .collect(),
        output: vec![TxOut {
            value: Amount::from_sat(amount_sats),
            script_pubkey: dest.script_pubkey(),
        }],
    };
    if change > 0 {
        tx.output.push(TxOut {
            value: Amount::from_sat(change),
            script_pubkey: wallet.next_unused(Chain::Internal).address.script_pubkey(),
        });
    }

    let mut cache = SighashCache::new(tx);
    for (i, (utxo, owner)) in selected.iter().enumerate() {
        let sighash = cache
            .p2wpkh_signature_hash(
                i,
                &owner.address.script_pubkey(),
                Amount::from_sat(utxo.value),
                EcdsaSighashType::All,
            )
            .map_err(|e| MobileSignerError::InvalidPsbtJson(e.to_string()))?;
        let msg = Message::from_digest(sighash.to_byte_array());
        let sig = secp.sign_ecdsa(&msg, &owner.privkey.inner);
        let mut der = sig.serialize_der().to_vec();
        der.push(EcdsaSighashType::All as u8);
        let witness = cache
            .witness_mut(i)
            .ok_or_else(|| MobileSignerError::InvalidPsbtJson("missing witness slot".into()))?;
        *witness = Witness::from_slice(&[der.as_slice(), &owner.pubkey.to_bytes()]);
    }
    Ok(cache.into_transaction())
}

type Answer = Result<ureq::Response, ureq::Error>;

/// Posts the signed transaction once and returns `txid`, the id computed from it here, when the
/// explorer shows it took it. A txid the server sends back is never trusted.
///
/// A 200 naming `txid`, or a 4xx whose reason says the node already holds it, means the payment is
/// out. Any other 4xx is a refusal, and a retry after a refusal is safe. Anything else (no answer, a
/// 5xx, a 200 naming something else, a body that cannot be read) leaves it unclear: it looks `txid`
/// up to `LOOKUPS` times with `pause` between asks and never posts again. Still not found is
/// `BroadcastUnknown`, because a retry could pay twice.
fn broadcast_signed(
    post: &dyn Fn(&str) -> Answer,
    lookup: &dyn Fn(&str) -> Answer,
    pause: &dyn Fn(),
    hex: &str,
    txid: &str,
) -> Result<String, MobileSignerError> {
    match post(hex) {
        Ok(answer) if answer.status() == 200 => {
            if answer.into_string().is_ok_and(|body| body.trim() == txid) {
                return Ok(txid.to_string());
            }
        }
        Err(refusal @ ureq::Error::Status(400..=499, _)) => {
            let error = format!("esplora post: {refusal}");
            // A reason that cannot be read may be the node saying it already holds the transaction.
            if let Some(Ok(reason)) = refusal.into_response().map(ureq::Response::into_string) {
                let reason = reason.to_lowercase();
                if ALREADY_HELD.iter().any(|held| reason.contains(held)) {
                    return Ok(txid.to_string());
                }
                return Err(MobileSignerError::InvalidPsbtJson(error));
            }
        }
        _ => {}
    }
    for ask in 0..LOOKUPS {
        if ask > 0 {
            pause();
        }
        if lookup(txid).is_ok_and(|answer| shows(answer, txid)) {
            return Ok(txid.to_string());
        }
    }
    Err(MobileSignerError::BroadcastUnknown(txid.to_string()))
}

// Only the transaction itself counts as found: a Wi-Fi sign-in page answers 200 to any URL.
fn shows(answer: ureq::Response, txid: &str) -> bool {
    answer.status() == 200
        && answer
            .into_string()
            .ok()
            .and_then(|body| serde_json::from_str::<Value>(&body).ok())
            .is_some_and(|tx| tx["txid"] == txid)
}

struct Utxo {
    txid: Txid,
    vout: u32,
    value: u64,
    confirmed: bool,
}

// A bad element fails the whole list. Skipping it would hide funds behind "insufficient funds".
// `status.confirmed` is read strictly: a missing status would otherwise spend an incoming coin that
// may never confirm.
fn parse_utxos(json: &str) -> Result<Vec<Utxo>, MobileSignerError> {
    let bad = |what: String| MobileSignerError::InvalidPsbtJson(format!("esplora utxo: {what}"));
    let body: serde_json::Value =
        serde_json::from_str(json).map_err(|e| bad(format!("not JSON: {e}")))?;
    let items = body.as_array().ok_or_else(|| bad("not an array".into()))?;
    items
        .iter()
        .enumerate()
        .map(|(i, item)| {
            let txid = item["txid"]
                .as_str()
                .ok_or_else(|| bad(format!("item {i} has no string txid")))?;
            let txid = Txid::from_str(txid)
                .map_err(|e| bad(format!("item {i} txid is not 64 hex characters: {e}")))?;
            let vout = item["vout"]
                .as_u64()
                .and_then(|v| u32::try_from(v).ok())
                .ok_or_else(|| bad(format!("item {i} has no u32 vout")))?;
            let value = item["value"]
                .as_u64()
                .ok_or_else(|| bad(format!("item {i} has no unsigned integer value")))?;
            let confirmed = item["status"]["confirmed"]
                .as_bool()
                .ok_or_else(|| bad(format!("item {i} has no boolean status.confirmed")))?;
            Ok(Utxo {
                txid,
                vout,
                value,
                confirmed,
            })
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use std::cell::{Cell, RefCell};
    use std::collections::{HashMap, HashSet, VecDeque};
    use std::sync::Mutex;

    use super::*;

    fn abandon_about() -> Vec<String> {
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
            .split_whitespace()
            .map(str::to_string)
            .collect()
    }

    // Every address here was derived outside this crate (stdlib PBKDF2 + BIP-32 + bech32) at
    // BIP-84 paths m/84'/1'/0'/{0,1}/{0,1}. The empty-passphrase 0/0 and 0/1 are the published
    // BIP-84 testnet vectors, so they also pin the behaviour from before passphrases existed.
    #[test]
    fn receive_and_change_addresses_match_an_independent_derivation() {
        let vectors = [
            ("", 0, 0, "tb1q6rz28mcfaxtmd6v789l9rrlrusdprr9pqcpvkl"),
            ("", 0, 1, "tb1qd7spv5q28348xl4myc8zmh983w5jx32cjhkn97"),
            ("", 1, 0, "tb1q9u62588spffmq4dzjxsr5l297znf3z6j5p2688"),
            ("", 1, 1, "tb1qkwgskuzmmwwvqajnyr7yp9hgvh5y45kg8wvdmd"),
            ("TREZOR", 0, 0, "tb1qfwxzlmn42rh6wu6gt4f0yrfpe4gf0jtwgxagrr"),
            ("TREZOR", 0, 1, "tb1qvn0rdysj4pzc6mq6pswv3q3dwlppl3zqc9e654"),
            ("TREZOR", 1, 0, "tb1qqdza7pkyjfvqx5tzy4kw7x5rcvs4lfg8ce2nq8"),
            ("TREZOR", 1, 1, "tb1q0vy7ch5mc63e3kz5ns8k2s7xxrfygjhesv9l0e"),
        ];
        for (passphrase, chain, index, expected) in vectors {
            let account = Account::new(&abandon_about(), passphrase, Network::Testnet4).unwrap();
            let chain = if chain == 0 {
                Chain::External
            } else {
                Chain::Internal
            };
            let found = account.key(chain, index).unwrap().address.to_string();
            assert_eq!(found, expected, "{passphrase:?} {}/{index}", chain as u32);
            if chain as u32 == 0 {
                let public = hot_receive_address(
                    abandon_about(),
                    passphrase.into(),
                    KeyNetwork::Test,
                    index,
                );
                assert_eq!(public.unwrap(), expected, "{passphrase:?} 0/{index}");
            }
        }
    }

    #[test]
    fn address_check_matches_what_hot_send_accepts() {
        let own = hot_receive_address(abandon_about(), String::new(), KeyNetwork::Test, 0).unwrap();
        assert!(is_valid_address(own.clone(), KeyNetwork::Test));
        assert!(
            !is_valid_address(own, KeyNetwork::Main),
            "a testnet address is not a mainnet one"
        );
        assert!(is_valid_address(
            "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq".into(),
            KeyNetwork::Main
        ));
        assert!(!is_valid_address(
            "tb1qnotanaddress".into(),
            KeyNetwork::Test
        ));
        assert!(!is_valid_address(" ".into(), KeyNetwork::Test));
        assert!(!is_valid_address(String::new(), KeyNetwork::Test));
    }

    #[test]
    fn a_different_passphrase_is_a_different_wallet() {
        let a = hot_receive_address(abandon_about(), "a".into(), KeyNetwork::Test, 0).unwrap();
        let b = hot_receive_address(abandon_about(), "b".into(), KeyNetwork::Test, 0).unwrap();
        let none =
            hot_receive_address(abandon_about(), String::new(), KeyNetwork::Test, 0).unwrap();
        assert!(a != b && a != none && b != none);
    }

    fn balance_and_used(json: &str) -> (u64, bool) {
        let stats = parse_address_stats(json).unwrap();
        (stats.balance, stats.used)
    }

    #[test]
    fn parse_balance_reads_chain_stats() {
        let json = r#"{"chain_stats":{"funded_txo_sum":1000,"spent_txo_sum":200,"tx_count":2},"mempool_stats":{"funded_txo_sum":50,"spent_txo_sum":0,"tx_count":1}}"#;
        assert_eq!(balance_and_used(json), (850, true));
    }

    #[test]
    fn real_address_summary_gives_its_balance() {
        let real = include_str!("../fixtures/esplora/blockstream_testnet_address_real.json");
        assert_eq!(balance_and_used(real), (79_177_760, true));
    }

    // 10000 confirmed, then spent in the mempool with 4000 back as change. 10000 + 4000 - 10000.
    #[test]
    fn a_pending_spend_leaves_only_the_change() {
        let json = r#"{"chain_stats":{"funded_txo_sum":10000,"spent_txo_sum":0,"tx_count":1},"mempool_stats":{"funded_txo_sum":4000,"spent_txo_sum":10000,"tx_count":1}}"#;
        assert_eq!(balance_and_used(json), (4_000, true));
    }

    // Nothing confirmed, 2500 arriving in the mempool. 0 + 2500 - 0. A mempool tx counts as used.
    #[test]
    fn a_receive_still_in_the_mempool_counts() {
        let json = r#"{"chain_stats":{"funded_txo_sum":0,"spent_txo_sum":0,"tx_count":0},"mempool_stats":{"funded_txo_sum":2500,"spent_txo_sum":0,"tx_count":1}}"#;
        assert_eq!(balance_and_used(json), (2_500, true));
    }

    // 5000 received and all of it spent, both confirmed. An empty wallet is a real 0, not an error,
    // and the address stays used.
    #[test]
    fn a_fully_spent_address_is_a_real_zero() {
        let json = r#"{"chain_stats":{"funded_txo_sum":5000,"spent_txo_sum":5000,"tx_count":2},"mempool_stats":{"funded_txo_sum":0,"spent_txo_sum":0,"tx_count":0}}"#;
        assert_eq!(balance_and_used(json), (0, true));
    }

    #[test]
    fn an_address_nobody_paid_is_unused() {
        assert_eq!(balance_and_used(UNUSED), (0, false));
    }

    #[test]
    fn an_answer_it_cannot_read_is_an_error_not_a_balance() {
        let cases = [
            (
                r#"{"chain_stats":{"funded_txo_sum":0,"spent_txo_sum":0},"mempool_stats":{"funded_txo_sum":0,"spent_txo_sum":0,"tx_count":0}}"#,
                "chain_stats.tx_count",
            ),
            (
                r#"{"chain_stats":{"funded_txo_sum":0,"spent_txo_sum":0,"tx_count":0},"mempool_stats":{"funded_txo_sum":0,"spent_txo_sum":0,"tx_count":"0"}}"#,
                "mempool_stats.tx_count",
            ),
            ("<html>Sign in to Wi-Fi</html>", "not JSON"),
            ("[]", "not an object"),
            (
                r#"{"chain_stats":{"funded_txo_sum":5000,"spent_txo_sum":0}}"#,
                "mempool_stats",
            ),
            (
                r#"{"mempool_stats":{"funded_txo_sum":5000,"spent_txo_sum":0}}"#,
                "chain_stats",
            ),
            (
                r#"{"chain_stats":{"funded_txo_sum":"5000","spent_txo_sum":0},"mempool_stats":{"funded_txo_sum":0,"spent_txo_sum":0}}"#,
                "chain_stats.funded_txo_sum",
            ),
            (
                r#"{"chain_stats":{"funded_txo_sum":5000,"spent_txo_sum":1.5},"mempool_stats":{"funded_txo_sum":0,"spent_txo_sum":0}}"#,
                "chain_stats.spent_txo_sum",
            ),
            (
                r#"{"chain_stats":{"funded_txo_sum":5000,"spent_txo_sum":0},"mempool_stats":{"funded_txo_sum":0,"spent_txo_sum":-1}}"#,
                "mempool_stats.spent_txo_sum",
            ),
            (
                r#"{"chain_stats":{"funded_txo_sum":5000,"spent_txo_sum":6000},"mempool_stats":{"funded_txo_sum":0,"spent_txo_sum":0}}"#,
                "spent 6000 is more than funded 5000",
            ),
            (
                r#"{"chain_stats":{"funded_txo_sum":10000,"spent_txo_sum":0},"mempool_stats":{"funded_txo_sum":1000,"spent_txo_sum":12000}}"#,
                "spent 12000 is more than funded 11000",
            ),
            (
                r#"{"chain_stats":{"funded_txo_sum":18446744073709551615,"spent_txo_sum":0},"mempool_stats":{"funded_txo_sum":1,"spent_txo_sum":0}}"#,
                "18446744073709551616 sats does not fit u64",
            ),
        ];
        for (body, rule) in cases {
            match parse_address_stats(body) {
                Err(MobileSignerError::InvalidPsbtJson(m)) => {
                    assert!(m.starts_with("esplora balance:"), "{body}: {m}");
                    assert!(m.contains(rule), "{body}: {m}");
                }
                Err(e) => panic!("{body}: wrong error {e:?}"),
                Ok(stats) => panic!("{body}: accepted as {} sats", stats.balance),
            }
        }
    }

    fn outpoints(json: &str) -> Vec<String> {
        parse_utxos(json)
            .unwrap()
            .iter()
            .map(|u| format!("{}:{} {}", u.txid, u.vout, u.value))
            .collect()
    }

    // Real Esplora puts a nested "status" object between "vout" and "value".
    #[test]
    fn real_esplora_utxos_parse_in_order() {
        let real = include_str!("../fixtures/esplora/testnet4_utxo_real_extract.json");
        assert_eq!(
            outpoints(real),
            [
                "d81587ef11ffab4230dbba9bce41faed7c18dfe561111f18323aeb26da5be194:1 10000",
                "14d8cee6cf445d0bc12e8959435e7e97a5e1833d54391bdbe2a29d72917f4b5a:1 5000",
            ]
        );
    }

    #[test]
    fn an_empty_list_is_an_empty_wallet() {
        assert!(outpoints("[]").is_empty());
    }

    #[test]
    fn an_unconfirmed_utxo_is_kept_and_marked() {
        let unconfirmed = include_str!("../fixtures/esplora/synthetic_utxo_unconfirmed.json");
        assert_eq!(
            outpoints(unconfirmed),
            ["0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef:0 1234"]
        );
        assert!(!parse_utxos(unconfirmed).unwrap()[0].confirmed);
        let real = include_str!("../fixtures/esplora/testnet4_utxo_real_extract.json");
        assert!(parse_utxos(real).unwrap().iter().all(|u| u.confirmed));
    }

    #[test]
    fn a_utxo_without_a_boolean_status_fails_the_list() {
        let (ok, bad) = (coin('1', 0, 1_000, true), id('2'));
        for status in ["", r#","status":{}"#, r#","status":{"confirmed":"true"}"#] {
            let body = format!(r#"[{ok},{{"txid":"{bad}","vout":0,"value":1000{status}}}]"#);
            match parse_utxos(&body) {
                Err(MobileSignerError::InvalidPsbtJson(m)) => {
                    assert_eq!(
                        m, "esplora utxo: item 1 has no boolean status.confirmed",
                        "{body}"
                    )
                }
                other => panic!(
                    "{body}: expected the status error, got {:?}",
                    other.map(|v| v.len())
                ),
            }
        }
    }

    #[test]
    fn unknown_fields_are_ignored() {
        let extra = include_str!("../fixtures/esplora/synthetic_utxo_extra_fields.json");
        assert_eq!(
            outpoints(extra),
            ["0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef:2 5000"]
        );
    }

    #[test]
    fn a_malformed_utxo_list_is_an_error_not_an_empty_wallet() {
        for body in [
            "<html>Sign in to Wi-Fi</html>",
            include_str!("../fixtures/esplora/synthetic_utxo_not_an_array.json"),
            include_str!("../fixtures/esplora/synthetic_utxo_missing_value.json"),
            include_str!("../fixtures/esplora/synthetic_utxo_vout_not_a_number.json"),
            include_str!("../fixtures/esplora/synthetic_utxo_vout_above_u32.json"),
            include_str!("../fixtures/esplora/synthetic_utxo_txid_not_64_hex.json"),
        ] {
            match parse_utxos(body) {
                Err(MobileSignerError::InvalidPsbtJson(m)) => {
                    assert!(m.starts_with("esplora utxo:"), "{body}: {m}")
                }
                Err(e) => panic!("{body}: wrong error {e:?}"),
                Ok(found) => panic!("{body}: accepted as {} utxos", found.len()),
            }
        }
    }

    macro_rules! history_fixture {
        ($name:literal) => {
            include_str!(concat!("../fixtures/history/", $name))
        };
    }

    // abandon-about at BIP-84 testnet index 0: the address the hand-made fixtures call the wallet.
    const WALLET: &str = "tb1q6rz28mcfaxtmd6v789l9rrlrusdprr9pqcpvkl";
    // The tip height the hand-made fixtures were written against.
    const TIP: &str = "105";

    fn id(digit: char) -> String {
        digit.to_string().repeat(64)
    }

    /// One /utxo item as Esplora lists it, in a transaction whose id repeats `digit`.
    fn coin(digit: char, vout: u32, value: u64, confirmed: bool) -> String {
        format!(
            r#"{{"txid":"{}","vout":{vout},"status":{{"confirmed":{confirmed}}},"value":{value}}}"#,
            id(digit)
        )
    }

    fn hot(txid: &str, net_sats: i64, confirmations: u32, block_time: Option<u64>) -> HotTx {
        HotTx {
            txid: txid.to_string(),
            net_sats,
            confirmations,
            block_time,
        }
    }

    fn history(fixture: &str) -> Vec<HotTx> {
        history_from(fixture, TIP, &[WALLET]).unwrap()
    }

    const REAL_TXS: &str = history_fixture!("blockstream_testnet_txs_real.json");
    const REAL_TIP: &str = history_fixture!("blockstream_testnet_tip_height.txt");
    // Four transactions of one testnet block, the first a coinbase, in the order Esplora gave them.
    const REAL_TXIDS: [&str; 4] = [
        "aa0dc317065d2c7ddc9ec03b9f1eb50eff40d2f9bee2bc8334d2cf8a7e164b71",
        "10af14dd16432fe677db864e718a015ec5d4da62ee21f5a596e5fdb8a55be2a7",
        "91b1f75e2f85a1856c1fcb879a69fc404581c93481eba62610d2f2b5556779ba",
        "64eaaf3bdfa169ab2a7077fbd4078eed1db86b7ce96177cd76ba35464d4f0ee4",
    ];
    const REAL_COINBASE_PAYEE: &str = "tb1qnjvttgnl0gkxn99gmsd8g8zzpe7nckssmy56hu";

    // These nets were tallied outside this crate, by a script that read the same four transactions
    // and added every output and every prevout per address. The script also checked the recording
    // against its own fee fields. The block is 5259564 and the tip 5259565: two confirmations.
    #[test]
    fn the_real_recording_gives_each_address_its_net_in_the_order_given() {
        let cases: [(&str, [i64; 4]); 4] = [
            // The coinbase pays it 132. The coinbase input has no prevout and counts as nothing.
            (REAL_COINBASE_PAYEE, [132, 0, 0, 0]),
            // Four inputs of 100000 in the third transaction and no output back.
            (
                "tb1q0l93tn8dyewj602wpe7vj24hfm7jdyv68q2vnj",
                [0, 0, -400_000, 0],
            ),
            // Spends 30000 and is paid 39998, then spends 30000 and is paid 39999.
            (
                "tb1qx54dlmfp0qnqjsqhy8585f0sa0udwdzhm2wwng",
                [0, 0, 9_998, 9_999],
            ),
            // Paid 389796 by the third transaction and spends all of it in the fourth.
            (
                "tb1qwczmuu3h6ch0jxyrp2fdvuprvrw4dcnyde0nj9",
                [0, 0, 389_796, -389_796],
            ),
        ];
        for (address, nets) in cases {
            let expected: Vec<HotTx> = REAL_TXIDS
                .iter()
                .zip(nets)
                .map(|(txid, net)| hot(txid, net, 2, Some(1_793_560_954)))
                .collect();
            assert_eq!(
                history_from(REAL_TXS, REAL_TIP, &[address]).unwrap(),
                expected,
                "{address}"
            );
        }
    }

    #[test]
    fn whitespace_around_the_tip_is_ignored() {
        for tip in ["5259565\n", " 5259565 "] {
            let found = history_from(REAL_TXS, tip, &[REAL_COINBASE_PAYEE]).unwrap();
            assert!(found.iter().all(|t| t.confirmations == 2), "{tip:?}");
        }
    }

    #[test]
    fn a_tip_beyond_u32_saturates_the_confirmations() {
        let found = history_from(REAL_TXS, "99999999999", &[REAL_COINBASE_PAYEE]).unwrap();
        assert_eq!(found.len(), 4);
        assert!(found.iter().all(|t| t.confirmations == u32::MAX));
    }

    #[test]
    fn an_address_with_no_transactions_has_an_empty_history() {
        assert!(history("[]").is_empty());
    }

    // 100000 in, 30000 to someone else, 69000 back, 1000 fee: the wallet is 31000 lighter.
    #[test]
    fn a_spend_with_change_back_is_minus_the_amount_and_the_fee() {
        assert_eq!(
            history(history_fixture!("synthetic_spend_with_change.json")),
            [hot(&id('1'), -31_000, 6, Some(1_790_000_000))]
        );
    }

    // 50000 in and 49000 back to itself: only the fee leaves.
    #[test]
    fn a_self_send_is_minus_the_fee() {
        assert_eq!(
            history(history_fixture!("synthetic_self_send.json")),
            [hot(&id('2'), -1_000, 3, Some(1_790_000_100))]
        );
    }

    // 20000 in, 15000 to someone else, 4000 back, 1000 fee.
    #[test]
    fn an_unconfirmed_transaction_has_no_confirmations_and_no_block_time() {
        assert_eq!(
            history(history_fixture!("synthetic_unconfirmed.json")),
            [hot(&id('3'), -16_000, 0, None)]
        );
    }

    #[test]
    fn block_fields_on_an_unconfirmed_transaction_are_not_believed() {
        assert_eq!(
            history(history_fixture!(
                "synthetic_unconfirmed_with_stray_block_fields.json"
            )),
            [hot(&id('4'), 7_000, 0, None)]
        );
    }

    // 40000 + 30000 + 20000 in, 89000 to someone else, nothing back.
    #[test]
    fn three_wallet_inputs_and_one_outside_output_are_all_spent() {
        assert_eq!(
            history(history_fixture!(
                "synthetic_three_wallet_inputs_one_outside_output.json"
            )),
            [hot(&id('5'), -90_000, 5, Some(1_790_000_200))]
        );
    }

    // Out 14000, in 10000. The 5000 input and the 700 output have no address and are nobody's.
    #[test]
    fn an_input_or_output_without_an_address_counts_as_nothing() {
        assert_eq!(
            history(history_fixture!(
                "synthetic_input_without_prevout_address.json"
            )),
            [hot(&id('6'), 4_000, 2, Some(1_790_000_300))]
        );
    }

    #[test]
    fn a_block_time_the_app_could_not_print_is_dropped() {
        let at_6 = |digit, time| hot(&id(digit), 1_000, 6, time);
        assert_eq!(
            history(history_fixture!("synthetic_block_times.json")),
            [
                at_6('1', None),                // 99999999999
                at_6('2', None),                // 4102444801, one second past the limit
                at_6('3', Some(4_102_444_800)), // the limit itself
                at_6('4', Some(1)),
                at_6('5', None), // 0
                at_6('6', None), // no block_time
                at_6('7', None), // a string
                at_6('8', None), // negative
            ]
        );
    }

    // Tip 105. A block at 105 or above it (a lagging backend, a reorg) still reads 1.
    #[test]
    fn a_transaction_at_or_above_the_tip_reads_one_confirmation() {
        let at = |digit, confirmations| hot(&id(digit), 1_000, confirmations, Some(1_790_000_400));
        assert_eq!(
            history(history_fixture!("synthetic_blocks_around_the_tip.json")),
            [at('1', 2), at('2', 1), at('3', 1), at('4', 1)]
        );
    }

    fn assert_rejected(txs: &str, tip: &str, rule: &str) {
        match history_from(txs, tip, &[WALLET]) {
            Err(MobileSignerError::InvalidPsbtJson(m)) => {
                assert!(m.starts_with("esplora history:"), "{rule}: {m}");
                assert!(m.contains(rule), "{rule}: {m}");
            }
            Err(e) => panic!("{rule}: wrong error {e:?}"),
            Ok(found) => panic!("{rule}: accepted as {} transactions", found.len()),
        }
    }

    // These files go wrong in a second transaction. The good first one must not survive alone.
    #[test]
    fn an_answer_it_cannot_read_is_an_error_not_a_shorter_list() {
        let good = history_fixture!("synthetic_spend_with_change.json");
        assert_rejected(
            history_fixture!("synthetic_malformed_html_page.html"),
            TIP,
            "not JSON",
        );
        assert_rejected(
            history_fixture!("synthetic_malformed_object_not_array.json"),
            TIP,
            "not an array",
        );
        assert_rejected(
            history_fixture!("synthetic_malformed_tx_without_vout.json"),
            TIP,
            "tx 1: vout",
        );
        assert_rejected(
            history_fixture!("synthetic_malformed_output_value_string.json"),
            TIP,
            "tx 1: output 1",
        );
        let tips = [
            history_fixture!("synthetic_malformed_tip_abc.txt"),
            "",
            "12.5",
            "-1",
            "18446744073709551616",
        ];
        for tip in tips {
            assert_rejected(good, tip, "tip height");
        }
        assert_rejected("[]", "abc", "tip height");
    }

    fn one_tx(vin: &str, vout: &str, status: &str) -> String {
        format!(
            r#"[{{"txid":"{}","vin":{vin},"vout":{vout},"status":{status}}}]"#,
            id('9')
        )
    }

    const MEMPOOL: &str = r#"{"confirmed":false}"#;

    fn wallet_outputs(values: &[&str]) -> String {
        let items: Vec<String> = values
            .iter()
            .map(|v| format!(r#"{{"scriptpubkey_address":"{WALLET}","value":{v}}}"#))
            .collect();
        format!("[{}]", items.join(","))
    }

    fn wallet_inputs(values: &[&str]) -> String {
        let items: Vec<String> = values
            .iter()
            .map(|v| {
                let prevout = format!(r#"{{"scriptpubkey_address":"{WALLET}","value":{v}}}"#);
                format!(r#"{{"is_coinbase":false,"prevout":{prevout}}}"#)
            })
            .collect();
        format!("[{}]", items.join(","))
    }

    #[test]
    fn a_transaction_missing_a_part_fails_the_whole_call() {
        let confirmed = |height: &str| format!(r#"{{"confirmed":true,"block_height":{height}}}"#);
        let other_value = |value: &str| {
            format!(r#"[{{"scriptpubkey_address":"tb1qsomeoneelse","value":{value}}}]"#)
        };
        let no_value = format!(r#"[{{"scriptpubkey_address":"{WALLET}"}}]"#);
        let huge = ["5000000000000000000", "5000000000000000000"];
        let cases = [
            (
                r#"[{"vin":[],"vout":[],"status":{"confirmed":false}}]"#.to_string(),
                "no string txid",
            ),
            (one_tx("{}", "[]", MEMPOOL), "vin is not an array"),
            (one_tx("[]", "null", MEMPOOL), "vout is not an array"),
            (one_tx("[]", &wallet_outputs(&["-1"]), MEMPOOL), "output 0"),
            (one_tx("[]", &wallet_outputs(&["1.5"]), MEMPOOL), "output 0"),
            (
                one_tx("[]", &wallet_outputs(&["9223372036854775808"]), MEMPOOL),
                "output 0",
            ),
            (one_tx("[]", &no_value, MEMPOOL), "output 0"),
            // A bad value fails even when the output is not the wallet's.
            (one_tx("[]", &other_value(r#""5""#), MEMPOOL), "output 0"),
            (
                one_tx(r#"[{"is_coinbase":false}]"#, "[]", MEMPOOL),
                "input 0",
            ),
            (
                one_tx(r#"[{"is_coinbase":false,"prevout":null}]"#, "[]", MEMPOOL),
                "input 0",
            ),
            (one_tx("[{}]", "[]", MEMPOOL), "input 0"),
            (
                one_tx(&wallet_inputs(&[r#""5""#]), "[]", MEMPOOL),
                "input 0",
            ),
            (one_tx("[]", "[]", "null"), "status.confirmed"),
            (
                one_tx("[]", "[]", r#"{"confirmed":"true"}"#),
                "status.confirmed",
            ),
            (one_tx("[]", "[]", r#"{"confirmed":true}"#), "block_height"),
            (one_tx("[]", "[]", &confirmed(r#""100""#)), "block_height"),
            (one_tx("[]", &wallet_outputs(&huge), MEMPOOL), "overflow"),
            (one_tx(&wallet_inputs(&huge), "[]", MEMPOOL), "overflow"),
        ];
        for (body, rule) in cases {
            assert_rejected(&body, TIP, rule);
        }
    }

    #[test]
    fn a_coinbase_input_counts_as_nothing_with_or_without_a_prevout_key() {
        for vin in [
            r#"[{"is_coinbase":true,"prevout":null}]"#,
            r#"[{"is_coinbase":true}]"#,
        ] {
            let body = one_tx(vin, &wallet_outputs(&["5000"]), MEMPOOL);
            assert_eq!(history(&body), [hot(&id('9'), 5_000, 0, None)], "{vin}");
        }
    }

    // The txid computed from the signed transaction and its hex. The core parses neither.
    const TXID: &str = "3e4a1db3652a715a6bb202bcaec79818edae3dff4db24a9c928af5279f2b8f20";
    const SIGNED_HEX: &str = "02000000000101";
    // Recorded from blockstream.info testnet on 2026-11-02, posting a confirmed transaction again.
    const NODE_HOLDS_IT: &str =
        "sendrawtransaction RPC error -27: Transaction outputs already in utxo set";
    // The same format with the reason for coins already spent. Not recorded.
    const NODE_REFUSES: &str = "sendrawtransaction RPC error -25: bad-txns-inputs-missingorspent";

    // An answer as ureq hands it over: a status of 400 or more arrives as an error.
    fn answer(status: u16, body: &str) -> Answer {
        let response = ureq::Response::new(status, "-", body).unwrap();
        if status >= 400 {
            Err(response.into())
        } else {
            Ok(response)
        }
    }

    // What ureq returns when a read times out: a transport error with no status.
    fn no_answer() -> Answer {
        Err(std::io::Error::new(std::io::ErrorKind::TimedOut, "timed out").into())
    }

    // A body announced as 100 bytes that stops after 5, so reading it fails.
    fn cut_short(status: u16) -> Answer {
        let response: ureq::Response =
            format!("HTTP/1.1 {status} -\r\nContent-Length: 100\r\n\r\nshort")
                .parse()
                .unwrap();
        if status >= 400 {
            Err(response.into())
        } else {
            Ok(response)
        }
    }

    // What /tx/{txid} answers for a transaction the explorer holds, cut to the field that is read.
    fn found() -> Answer {
        answer(
            200,
            &format!(r#"{{"txid":"{TXID}","status":{{"confirmed":false}}}}"#),
        )
    }

    // What /tx/{txid} answers for a txid it never saw (blockstream.info testnet, 2026-11-02).
    fn not_found() -> Answer {
        answer(404, "Transaction not found")
    }

    struct Broadcast {
        result: Result<String, MobileSignerError>,
        posts: usize,
        asks: usize,
        pauses: usize,
    }

    // The first POST gets `post` and any later one no answer. The lookups get `lookups` in order,
    // then not found.
    fn broadcast(post: Answer, lookups: Vec<Answer>) -> Broadcast {
        let post = RefCell::new(Some(post));
        let lookups = RefCell::new(VecDeque::from(lookups));
        let (posts, asks, pauses) = (Cell::new(0), Cell::new(0), Cell::new(0));
        let result = broadcast_signed(
            &|hex| {
                assert_eq!(hex, SIGNED_HEX);
                posts.set(posts.get() + 1);
                post.borrow_mut().take().unwrap_or_else(no_answer)
            },
            &|txid| {
                assert_eq!(txid, TXID);
                asks.set(asks.get() + 1);
                lookups.borrow_mut().pop_front().unwrap_or_else(not_found)
            },
            &|| pauses.set(pauses.get() + 1),
            SIGNED_HEX,
            TXID,
        );
        Broadcast {
            result,
            posts: posts.get(),
            asks: asks.get(),
            pauses: pauses.get(),
        }
    }

    #[track_caller]
    fn assert_sent(run: &Broadcast, case: &str) {
        match &run.result {
            Ok(txid) => assert_eq!(txid, TXID, "{case}: the txid must be the one computed here"),
            Err(e) => panic!("{case}: expected the payment to count as sent, got {e:?}"),
        }
    }

    #[track_caller]
    fn assert_unknown(run: &Broadcast, case: &str) {
        match &run.result {
            Err(MobileSignerError::BroadcastUnknown(txid)) => assert_eq!(txid, TXID, "{case}"),
            other => panic!("{case}: expected BroadcastUnknown, got {other:?}"),
        }
    }

    #[test]
    fn a_200_naming_the_txid_is_sent_after_one_post_and_no_lookup() {
        for body in [TXID.to_string(), format!("{TXID}\n")] {
            let run = broadcast(answer(200, &body), vec![]);
            assert_sent(&run, &body);
            assert_eq!((run.posts, run.asks, run.pauses), (1, 0, 0), "{body:?}");
        }
    }

    // A 200 says the node took something. Only the lookup can say it was this transaction.
    #[test]
    fn a_200_naming_anything_else_is_settled_by_the_lookup() {
        let other_txid = "1".repeat(64);
        for body in [other_txid.as_str(), "", "<html>Sign in to Wi-Fi</html>"] {
            let run = broadcast(answer(200, body), vec![found()]);
            assert_sent(&run, body);
            assert_eq!((run.posts, run.asks), (1, 1), "{body:?}");
            let run = broadcast(answer(200, body), vec![]);
            assert_unknown(&run, body);
            assert_eq!((run.posts, run.asks), (1, 4), "{body:?}");
        }
    }

    #[test]
    fn a_refusal_fails_as_before_with_no_lookup() {
        let refusals = [
            (400, NODE_REFUSES),
            (
                400,
                "sendrawtransaction RPC error -26: min relay fee not met, 110 < 141",
            ),
            (404, "Not Found"),
            (429, "Too Many Requests"),
        ];
        for (status, body) in refusals {
            // The text the old http_post gave for the same answer.
            let before = format!("esplora post: {}", answer(status, body).unwrap_err());
            let run = broadcast(answer(status, body), vec![found()]);
            match &run.result {
                Err(MobileSignerError::InvalidPsbtJson(text)) => assert_eq!(text, &before),
                other => panic!("{status} {body}: expected the refusal, got {other:?}"),
            }
            assert_eq!((run.posts, run.asks), (1, 0), "{status} {body}");
        }
    }

    #[test]
    fn a_4xx_saying_the_node_holds_it_is_sent_with_no_lookup() {
        let reasons = [
            NODE_HOLDS_IT,
            "sendrawtransaction RPC error -26: txn-already-in-mempool",
            "sendrawtransaction RPC error -26: txn-already-known",
            "sendrawtransaction RPC error -27: Transaction already in block chain",
            "Transaction outputs already in the UTXO set",
        ];
        for reason in reasons {
            let run = broadcast(answer(400, reason), vec![]);
            assert_sent(&run, reason);
            assert_eq!((run.posts, run.asks), (1, 0), "{reason}");
        }
    }

    #[test]
    fn the_holds_it_match_ignores_case() {
        for reason in [
            "TXN-ALREADY-IN-MEMPOOL",
            "Txn-Already-Known",
            "TRANSACTION OUTPUTS ALREADY IN UTXO SET",
            "Transaction Already In Block Chain",
        ] {
            assert_sent(&broadcast(answer(400, reason), vec![]), reason);
        }
    }

    #[test]
    fn no_answer_then_a_find_is_sent_after_one_post() {
        let run = broadcast(no_answer(), vec![found()]);
        assert_sent(&run, "timed out, then found");
        assert_eq!((run.posts, run.asks, run.pauses), (1, 1, 0));
    }

    #[test]
    fn no_answer_then_two_misses_then_a_find_pauses_twice() {
        let run = broadcast(no_answer(), vec![not_found(), not_found(), found()]);
        assert_sent(&run, "timed out, then found on the third ask");
        assert_eq!((run.posts, run.asks, run.pauses), (1, 3, 2));
    }

    #[test]
    fn no_answer_then_four_misses_is_unknown_never_a_plain_failure() {
        let misses = vec![not_found(), not_found(), not_found(), not_found()];
        let run = broadcast(no_answer(), misses);
        assert_unknown(&run, "timed out, then four misses");
        assert_eq!((run.posts, run.asks, run.pauses), (1, 4, 3));
    }

    #[test]
    fn lookups_that_get_no_answer_also_end_unknown() {
        let silent = vec![no_answer(), no_answer(), no_answer(), no_answer()];
        let run = broadcast(no_answer(), silent);
        assert_unknown(&run, "timed out, then every lookup timed out");
        assert_eq!((run.posts, run.asks, run.pauses), (1, 4, 3));
    }

    #[test]
    fn a_5xx_is_unclear_like_no_answer() {
        for status in [500, 502, 503, 504] {
            let run = broadcast(answer(status, "Bad Gateway"), vec![found()]);
            assert_sent(&run, &format!("{status}, then found"));
            assert_eq!((run.posts, run.asks), (1, 1), "{status}");
            let run = broadcast(answer(status, "Bad Gateway"), vec![]);
            assert_unknown(&run, &format!("{status}, then never found"));
            assert_eq!((run.posts, run.asks), (1, 4), "{status}");
        }
    }

    // Esplora's /tx/{txid}/status answers {"confirmed":false} with 200 for a txid it never saw
    // (blockstream.info testnet, 2026-11-02), and a Wi-Fi sign-in page answers 200 to any URL.
    #[test]
    fn a_lookup_200_that_is_not_the_transaction_is_not_a_find() {
        let other_txid = format!(r#"{{"txid":"{}"}}"#, "1".repeat(64));
        let bodies = [
            r#"{"confirmed":false}"#,
            "<html>Sign in to Wi-Fi</html>",
            other_txid.as_str(),
            "",
        ];
        for body in bodies {
            let run = broadcast(no_answer(), (0..4).map(|_| answer(200, body)).collect());
            assert_unknown(&run, body);
            assert_eq!(run.asks, 4, "{body}");
        }
    }

    #[test]
    fn a_body_that_cannot_be_read_leaves_the_payment_unclear() {
        assert!(
            cut_short(200).unwrap().into_string().is_err(),
            "the fixture must not be readable"
        );
        // A 4xx reason that breaks off may have said the node holds the transaction.
        for status in [200, 400] {
            let run = broadcast(cut_short(status), vec![found()]);
            assert_sent(&run, &format!("{status} cut short, then found"));
            assert_eq!((run.posts, run.asks), (1, 1), "{status}");
            let run = broadcast(cut_short(status), vec![]);
            assert_unknown(&run, &format!("{status} cut short, then never found"));
        }
        let run = broadcast(no_answer(), (0..4).map(|_| cut_short(200)).collect());
        assert_unknown(&run, "every lookup answer cut short");
    }

    #[test]
    fn the_transaction_is_never_posted_twice() {
        let first_answers: [fn() -> Answer; 7] = [
            || answer(200, TXID),
            || answer(200, "<html>Sign in to Wi-Fi</html>"),
            || answer(400, NODE_REFUSES),
            || answer(400, NODE_HOLDS_IT),
            || cut_short(400),
            || answer(502, "Bad Gateway"),
            no_answer,
        ];
        for (i, first) in first_answers.iter().enumerate() {
            assert_eq!(broadcast(first(), vec![]).posts, 1, "first answer {i}");
        }
    }

    const UNUSED: &str = r#"{"chain_stats":{"funded_txo_sum":0,"spent_txo_sum":0,"tx_count":0},"mempool_stats":{"funded_txo_sum":0,"spent_txo_sum":0,"tx_count":0}}"#;

    // A real testnet address that is not the wallet's: the coinbase payee of the recording.
    const SOMEONE: &str = REAL_COINBASE_PAYEE;

    fn used_stats(balance: u64) -> String {
        format!(
            r#"{{"chain_stats":{{"funded_txo_sum":{balance},"spent_txo_sum":0,"tx_count":1}},"mempool_stats":{{"funded_txo_sum":0,"spent_txo_sum":0,"tx_count":0}}}}"#
        )
    }

    fn own(chain: Chain, index: u32) -> OwnKey {
        Account::new(&abandon_about(), "", Network::Testnet4)
            .unwrap()
            .key(chain, index)
            .unwrap()
    }

    fn addr(chain: Chain, index: u32) -> String {
        own(chain, index).address.to_string()
    }

    /// Esplora keyed by address. An address not set up answers as one nobody ever paid.
    #[derive(Default)]
    struct Fake {
        stats: HashMap<String, String>,
        txs: HashMap<String, String>,
        utxos: HashMap<String, String>,
        failing: HashSet<String>,
        tip: String,
        calls: Mutex<Vec<String>>,
    }

    impl Fake {
        fn new() -> Self {
            Self {
                tip: TIP.into(),
                ..Default::default()
            }
        }

        /// `address` holds one coin of `value` sats, in a transaction whose id repeats `digit`.
        fn fund(&mut self, address: &str, value: u64, digit: char) {
            self.stats.insert(address.into(), used_stats(value));
            self.utxos
                .insert(address.into(), format!("[{}]", coin(digit, 0, value, true)));
        }

        /// `address` holds one coin of `value` sats that is still in the mempool.
        fn fund_unconfirmed(&mut self, address: &str, value: u64, digit: char) {
            self.stats.insert(
                address.into(),
                format!(
                    r#"{{"chain_stats":{{"funded_txo_sum":0,"spent_txo_sum":0,"tx_count":0}},"mempool_stats":{{"funded_txo_sum":{value},"spent_txo_sum":0,"tx_count":1}}}}"#
                ),
            );
            self.utxos.insert(
                address.into(),
                format!("[{}]", coin(digit, 0, value, false)),
            );
        }

        /// `address` was paid once and emptied again: used, with nothing to spend.
        fn touch(&mut self, address: &str) {
            self.stats.insert(address.into(), used_stats(0));
        }

        fn get(&self, url: &str) -> Result<String, MobileSignerError> {
            self.calls.lock().unwrap().push(url.to_string());
            let path = url
                .strip_prefix(ESPLORA_TESTNET4)
                .expect("a testnet4 Esplora URL");
            if path == "/blocks/tip/height" {
                return Ok(self.tip.clone());
            }
            let rest = path.strip_prefix("/address/").expect("an address URL");
            let (address, what) = rest.split_once('/').unwrap_or((rest, ""));
            if self.failing.contains(address) {
                return Err(MobileSignerError::InvalidPsbtJson(format!(
                    "esplora get: {address}: timed out"
                )));
            }
            let (table, fresh) = match what {
                "" => (&self.stats, UNUSED),
                "txs" => (&self.txs, "[]"),
                "utxo" => (&self.utxos, "[]"),
                other => panic!("unexpected Esplora path /address/{address}/{other}"),
            };
            Ok(table
                .get(address)
                .cloned()
                .unwrap_or_else(|| fresh.to_string()))
        }

        fn asked_about(&self, address: &str) -> bool {
            self.calls
                .lock()
                .unwrap()
                .iter()
                .any(|url| url.contains(address))
        }

        fn snapshot(&self, passphrase: &str) -> Result<HotWalletSnapshot, MobileSignerError> {
            snapshot_from(
                &|url| self.get(url),
                &abandon_about(),
                passphrase,
                KeyNetwork::Test,
            )
        }

        fn history(&self) -> Result<Vec<HotTx>, MobileSignerError> {
            history_of(&|url| self.get(url), &abandon_about(), "", KeyNetwork::Test)
        }

        fn send(&self, to: &str, amount: u64, fee: u64) -> Result<Transaction, MobileSignerError> {
            let words = abandon_about();
            signed_payment(
                &|url| self.get(url),
                &words,
                "",
                KeyNetwork::Test,
                to,
                amount,
                fee,
            )
        }
    }

    fn receive_and_balance(snapshot: HotWalletSnapshot) -> (String, u64) {
        assert_eq!(snapshot.network, KeyNetwork::Test);
        (snapshot.receive_address, snapshot.balance_sats)
    }

    fn pays(output: &TxOut) -> String {
        Address::from_script(&output.script_pubkey, Network::Testnet4)
            .unwrap()
            .to_string()
    }

    #[track_caller]
    fn assert_outputs(tx: &Transaction, expected: &[(&str, u64)]) {
        let found: Vec<(String, u64)> = tx
            .output
            .iter()
            .map(|o| (pays(o), o.value.to_sat()))
            .collect();
        let expected: Vec<(String, u64)> =
            expected.iter().map(|(a, v)| (a.to_string(), *v)).collect();
        assert_eq!(found, expected);
    }

    // True when input `i`'s witness is a SIGHASH_ALL signature by `key` over the P2WPKH sighash of
    // `key`'s own script, with `key`'s public key beside it.
    fn signed_by(tx: &Transaction, i: usize, value: u64, key: &OwnKey) -> bool {
        let sighash = SighashCache::new(tx)
            .p2wpkh_signature_hash(
                i,
                &key.address.script_pubkey(),
                Amount::from_sat(value),
                EcdsaSighashType::All,
            )
            .unwrap();
        let witness = &tx.input[i].witness;
        let der = witness.nth(0).unwrap();
        let (sig, hash_type) = der.split_at(der.len() - 1);
        let sig = bitcoin::secp256k1::ecdsa::Signature::from_der(sig).unwrap();
        let msg = Message::from_digest(sighash.to_byte_array());
        hash_type == [EcdsaSighashType::All as u8]
            && witness.nth(1) == Some(key.pubkey.to_bytes().as_slice())
            && Secp256k1::verification_only()
                .verify_ecdsa(&msg, &sig, &key.pubkey.0)
                .is_ok()
    }

    #[test]
    fn a_fresh_wallet_receives_at_index_0_and_holds_nothing() {
        let fake = Fake::new();
        assert_eq!(
            receive_and_balance(fake.snapshot("").unwrap()),
            (addr(Chain::External, 0), 0)
        );
        // The documented cost of a wallet nobody paid: GAP_LIMIT lookups per chain and no more.
        assert_eq!(fake.calls.lock().unwrap().len(), 40);
        assert!(fake.history().unwrap().is_empty());
        match fake.send(SOMEONE, 1_000, 100) {
            Err(MobileSignerError::InvalidPsbtJson(m)) => {
                assert_eq!(m, "insufficient funds: have 0 confirmed, need 1100")
            }
            other => panic!("expected insufficient funds, got {other:?}"),
        }
    }

    #[test]
    fn a_used_index_0_moves_the_receive_address_to_index_1() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 1_000, '1');
        assert_eq!(
            receive_and_balance(fake.snapshot("").unwrap()),
            (addr(Chain::External, 1), 1_000)
        );
    }

    #[test]
    fn a_coin_at_index_3_behind_three_unused_indices_is_found_counted_and_spent() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 3), 7_000, '3');
        assert_eq!(
            receive_and_balance(fake.snapshot("").unwrap()),
            (addr(Chain::External, 4), 7_000)
        );
        let tx = fake.send(SOMEONE, 5_000, 500).unwrap();
        assert_eq!(tx.input.len(), 1);
        assert_eq!(tx.input[0].previous_output.txid.to_string(), id('3'));
        assert!(signed_by(&tx, 0, 7_000, &own(Chain::External, 3)));
        assert_outputs(&tx, &[(SOMEONE, 5_000), (&addr(Chain::Internal, 0), 1_500)]);
    }

    #[test]
    fn a_coin_on_a_change_address_is_counted_and_spent() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::Internal, 0), 3_000, '4');
        assert_eq!(
            receive_and_balance(fake.snapshot("").unwrap()),
            (addr(Chain::External, 0), 3_000)
        );
        let tx = fake.send(SOMEONE, 2_000, 200).unwrap();
        assert!(signed_by(&tx, 0, 3_000, &own(Chain::Internal, 0)));
        assert_outputs(&tx, &[(SOMEONE, 2_000), (&addr(Chain::Internal, 1), 800)]);
    }

    // The documented limit. Index 20 is the first past twenty unused ones and is never asked about.
    #[test]
    fn a_coin_past_the_gap_limit_is_not_seen() {
        for index in [20, 21] {
            let mut fake = Fake::new();
            let far = addr(Chain::External, index);
            fake.fund(&far, 9_000, '5');
            assert_eq!(
                receive_and_balance(fake.snapshot("").unwrap()),
                (addr(Chain::External, 0), 0),
                "{index}"
            );
            assert!(!fake.asked_about(&far), "{index}");
            assert!(fake.history().unwrap().is_empty(), "{index}");
            assert!(fake.send(SOMEONE, 1_000, 100).is_err(), "{index}");
        }
    }

    #[test]
    fn a_coin_after_exactly_nineteen_unused_indices_is_found() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 19), 1_000, '6');
        assert_eq!(
            receive_and_balance(fake.snapshot("").unwrap()),
            (addr(Chain::External, 20), 1_000)
        );
        // The scan went on past it: twenty more external lookups, so 39 of them were asked about.
        assert!(fake.asked_about(&addr(Chain::External, 39)));
        assert!(!fake.asked_about(&addr(Chain::External, 40)));
    }

    #[test]
    fn the_same_words_give_the_same_answer_twice() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 4_000, '1');
        fake.fund(&addr(Chain::External, 2), 4_000, '2');
        fake.fund(&addr(Chain::Internal, 1), 500, '7');
        assert_eq!(fake.snapshot("").unwrap(), fake.snapshot("").unwrap());
        assert_eq!(fake.history().unwrap(), fake.history().unwrap());
        let (a, b) = (
            fake.send(SOMEONE, 6_000, 500).unwrap(),
            fake.send(SOMEONE, 6_000, 500).unwrap(),
        );
        assert_eq!(serialize_hex(&a), serialize_hex(&b));
    }

    #[test]
    fn a_different_passphrase_sees_none_of_these_coins() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 1_000, '1');
        assert_eq!(fake.snapshot("").unwrap().balance_sats, 1_000);
        assert_eq!(
            receive_and_balance(fake.snapshot("TREZOR").unwrap()),
            ("tb1qfwxzlmn42rh6wu6gt4f0yrfpe4gf0jtwgxagrr".into(), 0)
        );
    }

    #[test]
    fn two_coins_on_two_addresses_are_each_signed_with_their_own_key() {
        let mut fake = Fake::new();
        let (first, third) = (own(Chain::External, 0), own(Chain::External, 2));
        fake.fund(&first.address.to_string(), 4_000, '1');
        fake.fund(&third.address.to_string(), 4_000, '2');
        let tx = fake.send(SOMEONE, 6_000, 500).unwrap();
        assert_eq!(tx.input.len(), 2);
        assert!(signed_by(&tx, 0, 4_000, &first));
        assert!(signed_by(&tx, 1, 4_000, &third));
        // The check can tell the keys apart.
        assert!(!signed_by(&tx, 0, 4_000, &third));
        assert!(!signed_by(&tx, 1, 4_000, &first));
        assert_outputs(&tx, &[(SOMEONE, 6_000), (&addr(Chain::Internal, 0), 1_500)]);
    }

    #[test]
    fn change_goes_to_the_first_unused_internal_address_and_never_an_external_one() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 10_000, '1');
        fake.touch(&addr(Chain::Internal, 0));
        fake.touch(&addr(Chain::Internal, 1));
        let tx = fake.send(SOMEONE, 1_000, 200).unwrap();
        assert_outputs(&tx, &[(SOMEONE, 1_000), (&addr(Chain::Internal, 2), 8_800)]);
        let externals: Vec<String> = (0..40).map(|i| addr(Chain::External, i)).collect();
        assert!(!externals.contains(&pays(&tx.output[1])));
    }

    #[track_caller]
    fn assert_refused(result: Result<Transaction, MobileSignerError>, message: &str) {
        match result {
            Err(MobileSignerError::InvalidPsbtJson(m)) => assert_eq!(m, message),
            other => panic!("expected {message:?}, got {other:?}"),
        }
    }

    // Finding 6. Before, change of 1 to 546 sats was quietly added to the fee.
    #[test]
    fn dust_change_is_refused_and_change_of_547_or_0_is_fine() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 10_000, '1');
        let dust = "change below dust, send the whole balance or change the amount";
        assert_refused(fake.send(SOMEONE, 9_000, 999), dust);
        assert_refused(fake.send(SOMEONE, 9_000, 454), dust);
        assert_outputs(
            &fake.send(SOMEONE, 9_000, 453).unwrap(),
            &[(SOMEONE, 9_000), (&addr(Chain::Internal, 0), 547)],
        );
        // The whole balance: one output and the fee exactly as asked.
        let all = fake.send(SOMEONE, 9_000, 1_000).unwrap();
        assert_outputs(&all, &[(SOMEONE, 9_000)]);
        assert_eq!(prepared(&all, 1_000).fee_sats, 1_000);
    }

    // Finding 5. Each guard is a distinct error before any signing.
    #[test]
    fn a_dust_amount_or_a_fee_above_the_amount_is_refused_before_any_lookup() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 10_000, '1');
        assert_refused(
            fake.send(SOMEONE, 0, 0),
            "amount 0 is below the 546 sat dust limit",
        );
        assert_refused(
            fake.send(SOMEONE, 545, 100),
            "amount 545 is below the 546 sat dust limit",
        );
        assert_refused(
            fake.send(SOMEONE, 1_000, 1_001),
            "fee 1001 is more than the amount 1000",
        );
        assert!(fake.calls.lock().unwrap().is_empty());
        assert_outputs(
            &fake.send(SOMEONE, 546, 546).unwrap(),
            &[(SOMEONE, 546), (&addr(Chain::Internal, 0), 8_908)],
        );
    }

    // Findings 5 and 7. One P2WPKH input and two outputs is 141 vB; one output is 110 vB.
    #[test]
    fn the_fee_rate_of_the_transaction_as_built_stays_between_1_and_2000_sat_per_vb() {
        assert_eq!(p2wpkh_vsize(1, 2), 141);
        assert_eq!(p2wpkh_vsize(1, 1), 110);
        assert_eq!(p2wpkh_vsize(11, 1), 790);
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 1_000_000, '1');
        assert_refused(
            fake.send(SOMEONE, 5_000, 140),
            "fee 140 is below 1 sat/vB for 141 vB",
        );
        assert_eq!(fake.send(SOMEONE, 5_000, 141).unwrap().output.len(), 2);
        assert_eq!(
            fake.send(SOMEONE, 600_000, 282_000).unwrap().output.len(),
            2
        );
        assert_refused(
            fake.send(SOMEONE, 600_000, 282_001),
            "fee 282001 is above 2000 sat/vB for 141 vB",
        );
        // The Slow tier's 564 sats over eleven 1,000-sat coins: 0.71 sat/vB.
        let mut many = Fake::new();
        for i in 0..12u32 {
            many.stats
                .insert(addr(Chain::External, i), used_stats(1_000));
            many.utxos.insert(
                addr(Chain::External, i),
                format!("[{}]", coin('a', i, 1_000, true)),
            );
        }
        assert_refused(
            many.send(SOMEONE, 10_436, 564),
            "fee 564 is below 1 sat/vB for 790 vB",
        );
        let tx = many.send(SOMEONE, 10_210, 790).unwrap();
        assert_eq!((tx.input.len(), tx.output.len()), (11, 1));
    }

    // Finding 8. Before, the total wrapped in release and panicked in debug.
    #[test]
    fn a_coin_value_that_overflows_the_sum_is_an_error_not_a_panic() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 1_000, '1');
        fake.fund(&addr(Chain::External, 1), u64::MAX, '2');
        fake.fund(&addr(Chain::External, 2), 5_000, '3');
        assert_refused(
            fake.send(SOMEONE, 5_000, 600),
            "esplora utxo: the coins' values do not fit u64",
        );
        assert!(fake.snapshot("").is_err(), "the balance overflows too");
    }

    // Finding 9. The same outpoint in one list, and across two addresses' lists.
    #[test]
    fn a_duplicate_outpoint_fails_the_whole_call() {
        let twice = format!("[{0},{0}]", coin('1', 0, 5_000, true));
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 5_000, '1');
        fake.utxos.insert(addr(Chain::External, 0), twice);
        let listed_twice = format!("esplora utxo: {}:0 is listed twice", id('1'));
        assert_refused(fake.send(SOMEONE, 8_000, 200), &listed_twice);
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 5_000, '1');
        fake.fund(&addr(Chain::External, 1), 5_000, '1');
        assert_refused(fake.send(SOMEONE, 8_000, 200), &listed_twice);
    }

    // Finding 10. A used index 179 closes its gap at exactly 200 indices; index 180 needs 201.
    #[test]
    fn the_scan_stops_at_200_indices_per_chain() {
        let abnormal =
            "esplora scan: more than 200 addresses on one chain, the history is abnormal";
        let mut fake = Fake::new();
        for i in 0..180 {
            fake.touch(&addr(Chain::External, i));
        }
        assert_eq!(
            fake.snapshot("").unwrap().receive_address,
            addr(Chain::External, 180)
        );
        assert_eq!(fake.calls.lock().unwrap().len(), 220);
        fake.touch(&addr(Chain::External, 180));
        match fake.snapshot("") {
            Err(MobileSignerError::InvalidPsbtJson(m)) => assert_eq!(m, abnormal),
            other => panic!("expected the abnormal-history error, got {other:?}"),
        }
        // A host that calls every address used: at most one chain's worth of lookups.
        let calls = std::sync::atomic::AtomicUsize::new(0);
        let liar = |_: &str| {
            calls.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
            Ok(used_stats(0))
        };
        let result = snapshot_from(&liar, &abandon_about(), "", KeyNetwork::Test);
        assert!(result.is_err());
        assert!(calls.load(std::sync::atomic::Ordering::SeqCst) <= 200);
    }

    // Finding 11. Funded 5,000 with tx_count 0 showed a balance that could not be spent.
    #[test]
    fn stats_that_contradict_themselves_fail_the_whole_call() {
        let cases = [
            r#"{"chain_stats":{"funded_txo_sum":5000,"spent_txo_sum":0,"tx_count":0},"mempool_stats":{"funded_txo_sum":0,"spent_txo_sum":0,"tx_count":0}}"#,
            r#"{"chain_stats":{"funded_txo_sum":0,"spent_txo_sum":0,"tx_count":0},"mempool_stats":{"funded_txo_sum":10,"spent_txo_sum":10,"tx_count":0}}"#,
        ];
        for body in cases {
            match parse_address_stats(body) {
                Err(MobileSignerError::InvalidPsbtJson(m)) => {
                    assert!(m.contains("contradicts itself"), "{body}: {m}")
                }
                other => panic!(
                    "{body}: expected the contradiction error, got {:?}",
                    other.map(|s| s.balance)
                ),
            }
            let mut fake = Fake::new();
            fake.stats.insert(addr(Chain::External, 0), body.into());
            fake.utxos.insert(
                addr(Chain::External, 0),
                format!("[{}]", coin('1', 0, 5_000, true)),
            );
            assert!(fake.snapshot("").is_err(), "{body}");
            assert!(fake.send(SOMEONE, 1_000, 200).is_err(), "{body}");
        }
    }

    // Finding 14. Balance counts both coins; only the wallet's own unconfirmed change is spent.
    #[test]
    fn an_unconfirmed_receive_is_counted_but_not_spent_while_own_unconfirmed_change_is() {
        let mut fake = Fake::new();
        fake.fund_unconfirmed(&addr(Chain::External, 0), 5_000, '1');
        fake.fund_unconfirmed(&addr(Chain::Internal, 0), 3_000, '2');
        assert_eq!(fake.snapshot("").unwrap().balance_sats, 8_000);
        let tx = fake.send(SOMEONE, 2_000, 200).unwrap();
        assert_eq!(tx.input.len(), 1);
        assert_eq!(tx.input[0].previous_output.txid.to_string(), id('2'));
        assert!(signed_by(&tx, 0, 3_000, &own(Chain::Internal, 0)));
        assert_refused(
            fake.send(SOMEONE, 4_000, 200),
            "insufficient funds: have 3000 confirmed, need 4200",
        );
    }

    // Finding 1. What the app saves as its guard is the id of the transaction it then posts.
    #[test]
    fn a_prepared_send_posts_nothing_and_its_txid_is_the_transaction_in_its_hex() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 10_000, '1');
        let signed = fake.send(SOMEONE, 5_000, 500).unwrap();
        let ready = prepared(&signed, 500);
        // The fake panics on any path that is not a lookup, so a POST could not have gone through it.
        assert!(
            fake.calls
                .lock()
                .unwrap()
                .iter()
                .all(|u| !u.ends_with("/tx"))
        );
        let parsed: Transaction = bitcoin::consensus::encode::deserialize_hex(&ready.hex).unwrap();
        assert_eq!(parsed, signed);
        assert_eq!(ready.txid, parsed.compute_txid().to_string());
        assert_eq!(ready.fee_sats, 500);
        // The same words give the same transaction, so hot_send would broadcast this txid.
        assert_eq!(
            ready,
            prepared(&fake.send(SOMEONE, 5_000, 500).unwrap(), 500)
        );
    }

    #[test]
    fn a_broadcast_whose_txid_is_not_the_transaction_is_refused_before_any_post() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 10_000, '1');
        let good = prepared(&fake.send(SOMEONE, 5_000, 500).unwrap(), 500);
        let posts = Cell::new(0);
        let run = |hex: &str, txid: &str| {
            broadcast_matching(
                &|_| {
                    posts.set(posts.get() + 1);
                    answer(200, txid)
                },
                &|_| not_found(),
                &|| {},
                hex,
                txid,
            )
        };
        let other = id('9');
        match run(&good.hex, &other) {
            Err(MobileSignerError::InvalidPsbtJson(m)) => assert_eq!(
                m,
                format!(
                    "broadcast: txid {other} does not match the transaction {}",
                    good.txid
                )
            ),
            other => panic!("expected the mismatch error, got {other:?}"),
        }
        match run("02000000000101", &good.txid) {
            Err(MobileSignerError::InvalidPsbtJson(m)) => {
                assert!(m.starts_with("broadcast: not a transaction:"), "{m}")
            }
            other => panic!("expected the parse error, got {other:?}"),
        }
        assert_eq!(posts.get(), 0);
        assert_eq!(run(&good.hex, &good.txid).unwrap(), good.txid);
        assert_eq!(posts.get(), 1);
    }

    #[test]
    fn a_malformed_answer_for_any_scanned_address_fails_the_whole_call() {
        for (chain, index) in [(Chain::External, 17), (Chain::Internal, 5)] {
            let mut fake = Fake::new();
            fake.fund(&addr(Chain::External, 0), 1_000, '1');
            fake.stats
                .insert(addr(chain, index), "<html>Sign in to Wi-Fi</html>".into());
            for result in [
                fake.snapshot("").map(|_| ()),
                fake.history().map(|_| ()),
                fake.send(SOMEONE, 1_000, 200).map(|_| ()),
            ] {
                match result {
                    Err(MobileSignerError::InvalidPsbtJson(m)) => {
                        assert!(m.starts_with("esplora balance: not JSON"), "{index}: {m}")
                    }
                    other => panic!("{index}: expected the call to fail, got {other:?}"),
                }
            }
        }
    }

    #[test]
    fn an_esplora_error_on_one_address_of_a_window_fails_the_whole_call() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 1_000, '1');
        fake.failing.insert(addr(Chain::External, 4));
        match fake.snapshot("") {
            Err(MobileSignerError::InvalidPsbtJson(m)) => {
                assert!(m.starts_with("esplora get:"), "{m}")
            }
            other => panic!("expected the call to fail, got {other:?}"),
        }
    }

    fn txo(address: &str, value: u64) -> String {
        format!(r#"{{"scriptpubkey_address":"{address}","value":{value}}}"#)
    }

    fn spent(address: &str, value: u64) -> String {
        format!(
            r#"{{"is_coinbase":false,"prevout":{}}}"#,
            txo(address, value)
        )
    }

    fn tx_json(digit: char, vin: &[String], vout: &[String], status: &str) -> String {
        format!(
            r#"{{"txid":"{}","vin":[{}],"vout":[{}],"status":{status}}}"#,
            id(digit),
            vin.join(","),
            vout.join(",")
        )
    }

    fn at(height: u32) -> String {
        format!(r#"{{"confirmed":true,"block_height":{height},"block_time":1790000000}}"#)
    }

    // 10000 at 0/0 is spent: 6000 to someone, 3000 back as change to 1/0, 1000 fee. Both pages
    // list that transaction. The wallet is 7000 lighter, once. A mempool receive on 1/0 comes
    // first and the older funding of 0/0 last.
    #[test]
    fn a_send_with_change_is_one_outgoing_transaction_in_the_history() {
        let (ext0, int0) = (addr(Chain::External, 0), addr(Chain::Internal, 0));
        let funding = tx_json(
            '0',
            &[spent(SOMEONE, 20_000)],
            &[txo(&ext0, 10_000)],
            &at(90),
        );
        let payment = tx_json(
            '1',
            &[spent(&ext0, 10_000)],
            &[txo(SOMEONE, 6_000), txo(&int0, 3_000)],
            &at(100),
        );
        let receive = tx_json('2', &[spent(SOMEONE, 900)], &[txo(&int0, 500)], MEMPOOL);
        let mut fake = Fake::new();
        fake.touch(&ext0);
        fake.touch(&int0);
        fake.txs.insert(ext0, format!("[{payment},{funding}]"));
        fake.txs.insert(int0, format!("[{receive},{payment}]"));
        assert_eq!(
            fake.history().unwrap(),
            [
                hot(&id('2'), 500, 0, None),
                hot(&id('1'), -7_000, 6, Some(1_790_000_000)),
                hot(&id('0'), 10_000, 16, Some(1_790_000_000)),
            ]
        );
    }

    #[test]
    fn a_destination_on_the_wrong_network_is_refused_before_any_lookup() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 10_000, '1');
        let mainnet = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq";
        assert!(fake.send(mainnet, 1_000, 100).is_err());
        assert!(fake.send("tb1qnotanaddress", 1_000, 100).is_err());
        assert!(fake.calls.lock().unwrap().is_empty());
    }

    fn throttled() -> GetError {
        GetError::Retryable {
            retry_after: None,
            what: "HTTP status 429".into(),
        }
    }

    type Retried = (Result<String, MobileSignerError>, usize, Vec<Duration>);

    // Runs `get_with_retry` over scripted attempts. Returns the result, the attempts made and the sleeps.
    fn retried(script: Vec<Result<String, GetError>>) -> Retried {
        let script = RefCell::new(VecDeque::from(script));
        let attempts = Cell::new(0);
        let sleeps = RefCell::new(Vec::new());
        let result = get_with_retry(
            &|| {
                attempts.set(attempts.get() + 1);
                script
                    .borrow_mut()
                    .pop_front()
                    .expect("one attempt too many")
            },
            &|d| sleeps.borrow_mut().push(d),
        );
        (result, attempts.get(), sleeps.into_inner())
    }

    fn secs(n: &[u64]) -> Vec<Duration> {
        n.iter().map(|s| Duration::from_secs(*s)).collect()
    }

    #[test]
    fn a_throttled_answer_then_a_200_returns_that_body() {
        let (result, attempts, sleeps) = retried(vec![Err(throttled()), Ok("body".into())]);
        assert_eq!(result.unwrap(), "body");
        assert_eq!((attempts, sleeps), (2, secs(&[1])));
    }

    #[test]
    fn a_200_is_asked_once() {
        let (result, attempts, sleeps) = retried(vec![Ok("body".into())]);
        assert_eq!(result.unwrap(), "body");
        assert_eq!((attempts, sleeps.len()), (1, 0));
    }

    #[test]
    fn three_throttled_answers_fail_naming_the_status_and_the_attempts() {
        let (result, attempts, sleeps) =
            retried(vec![Err(throttled()), Err(throttled()), Err(throttled())]);
        let MobileSignerError::InvalidPsbtJson(message) = result.unwrap_err() else {
            panic!("wrong error kind");
        };
        assert!(
            message.contains("429") && message.contains("3 attempts"),
            "{message}"
        );
        assert_eq!((attempts, sleeps), (3, secs(&[1, 2])));
    }

    #[test]
    fn a_fatal_answer_is_asked_once() {
        let fatal = GetError::Fatal(MobileSignerError::InvalidPsbtJson("404".into()));
        let (result, attempts, sleeps) = retried(vec![Err(fatal)]);
        assert!(result.is_err());
        assert_eq!((attempts, sleeps.len()), (1, 0));
    }

    fn after(seconds: Option<u64>) -> GetError {
        GetError::Retryable {
            retry_after: seconds.map(Duration::from_secs),
            what: "HTTP status 503".into(),
        }
    }

    #[test]
    fn retry_after_sets_the_wait_and_is_capped() {
        let (_, _, sleeps) = retried(vec![Err(after(Some(2))), Ok("b".into())]);
        assert_eq!(sleeps, secs(&[2]));
        let (_, _, sleeps) = retried(vec![Err(after(Some(99))), Ok("b".into())]);
        assert_eq!(sleeps, secs(&[5]));
    }

    #[test]
    fn the_waits_never_pass_eight_seconds() {
        let (_, attempts, sleeps) = retried(vec![
            Err(after(Some(99))),
            Err(after(Some(99))),
            Err(after(Some(99))),
        ]);
        assert_eq!(attempts, 3);
        assert_eq!(sleeps, secs(&[5, 3]));
        assert!(sleeps.iter().sum::<Duration>() <= Duration::from_secs(8));
    }

    fn classified(response: &str) -> Result<String, GetError> {
        let response: ureq::Response = response.parse().unwrap();
        let status = response.status();
        classify(if status >= 400 {
            Err(response.into())
        } else {
            Ok(response)
        })
    }

    #[test]
    fn classify_retries_only_429_502_503_504() {
        for status in [429, 502, 503, 504] {
            let got = classified(&format!("HTTP/1.1 {status} -\r\nContent-Length: 0\r\n\r\n"));
            assert!(matches!(got, Err(GetError::Retryable { .. })), "{status}");
        }
        for status in [400, 404, 500, 501, 505] {
            let got = classified(&format!("HTTP/1.1 {status} -\r\nContent-Length: 0\r\n\r\n"));
            assert!(matches!(got, Err(GetError::Fatal(_))), "{status}");
        }
        let ok = classified("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}");
        assert!(matches!(ok, Ok(b) if b == "{}"));
        // A timeout stays fatal: waiting a second does not make a silent host answer.
        assert!(matches!(classify(no_answer()), Err(GetError::Fatal(_))));
        let refused = std::io::Error::new(std::io::ErrorKind::ConnectionRefused, "no");
        assert!(matches!(
            classify(Err(refused.into())),
            Err(GetError::Fatal(_))
        ));
    }

    #[test]
    fn classify_reads_retry_after_seconds_and_ignores_a_date() {
        let wait = |value: &str| match classified(&format!(
            "HTTP/1.1 429 -\r\nRetry-After: {value}\r\nContent-Length: 0\r\n\r\n"
        )) {
            Err(GetError::Retryable { retry_after, .. }) => retry_after,
            _ => panic!("not retryable"),
        };
        assert_eq!(wait("2"), Some(Duration::from_secs(2)));
        assert_eq!(wait("Wed, 21 Oct 2026 07:28:00 GMT"), None);
        let (_, _, sleeps) = retried(vec![Err(after(None)), Err(after(None)), Ok("b".into())]);
        assert_eq!(sleeps, secs(&[1, 2]));
    }

    #[test]
    fn a_retried_200_still_goes_through_the_strict_parser() {
        let (result, _, _) = retried(vec![Err(throttled()), Ok(r#"{"chain_stats":{}}"#.into())]);
        assert!(parse_address_stats(&result.unwrap()).is_err());
    }

    #[test]
    #[ignore = "needs the network and mempool.space/testnet4"]
    fn real_scan_of_the_public_test_phrase() {
        let requests = std::sync::atomic::AtomicUsize::new(0);
        let counted = |url: &str| {
            requests.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
            http_get(url)
        };
        let start = std::time::Instant::now();
        let wallet = scan_wallet(&counted, &abandon_about(), "", KeyNetwork::Test).unwrap();
        println!(
            "scan took {:?} for {} requests",
            start.elapsed(),
            requests.load(std::sync::atomic::Ordering::SeqCst)
        );
        if wallet.used().next().is_none() {
            assert_eq!(
                wallet.next_unused(Chain::External).address.to_string(),
                WALLET
            );
        }
    }
}
