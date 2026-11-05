//! Single-sig hot wallet (free BTC). BIP-84 P2WPKH on the account key.
//!
//! Shape matches the BDK hot-wallet half described in `mobile-signer-ffi/README.md`.
//! Address/UTXO/sign/broadcast are real against testnet Esplora; full `bdk_wallet`
//! persistence and coin selection land when Android binary size is measured.
//!
//! Every call scans the account from Esplora: both BIP-84 chains, external `0/i` and internal
//! `1/i`, until `GAP_LIMIT` consecutive unused indices follow the last used one. What a scan
//! learned (which indices are used, their balances, no key) is kept in `SCAN_CACHE` for the
//! process, so the next call asks only what can have changed; see `scan_wallet`.

use std::collections::{HashMap, HashSet};
use std::str::FromStr;
use std::sync::{LazyLock, Mutex};
use std::time::{Duration, Instant};

use bip39::Mnemonic;
use bitcoin::absolute::LockTime;
use bitcoin::bip32::{ChildNumber, DerivationPath, Xpriv, Xpub};
use bitcoin::consensus::encode::serialize_hex;
use bitcoin::hashes::{Hash, sha256};
use bitcoin::secp256k1::{All, Message, Secp256k1};
use bitcoin::{
    Address, Amount, CompressedPublicKey, Network, OutPoint, PrivateKey, ScriptBuf, Sequence,
    Transaction, TxIn, TxOut, Txid, Witness, sighash::EcdsaSighashType, sighash::SighashCache,
};
use serde_json::Value;

use crate::{KeyNetwork, MobileSignerError};

/// One testnet4 Esplora host and how long its genesis probe may take, DNS to the last body byte.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct HostSpec {
    base: &'static str,
    probe_timeout: Duration,
}

/// A private Esplora or mempool instance tried before the public hosts, read at build time from
/// `MEBIT_ESPLORA_HOST`. `scripts/ffi.sh` passes the one line of `esplora-host.local`, which git
/// ignores: a tailnet address is the developer's own network, not this repository's. A build without
/// it tries only the public hosts.
const OWN_NODE: Option<&str> = option_env!("MEBIT_ESPLORA_HOST");
/// The public testnet4 Esplora hosts in the order they are tried. blockstream.info allows 700
/// requests an hour per IP. mempool.space is last because its TLS handshake hung from a phone on 5G
/// and from the user's networks on 2026-11-03.
const PUBLIC_TESTNET4_HOSTS: [HostSpec; 2] = [
    HostSpec {
        base: ESPLORA_TESTNET4,
        probe_timeout: PROBE_TIMEOUT,
    },
    HostSpec {
        base: "https://mempool.space/testnet4/api",
        probe_timeout: PROBE_TIMEOUT,
    },
];
/// Testnet4 hosts in the order they are tried: this build's private node first, because it has no
/// request limit, then the public hosts. Passing the genesis check does not make a host honest about
/// balances; that residual trust is the same for every host here, see `MAX_SCAN_PER_CHAIN`.
static ESPLORA_TESTNET4_HOSTS: LazyLock<Vec<HostSpec>> = LazyLock::new(|| hosts_with(OWN_NODE));

/// The hosts of a build whose private node is `own`. Cleartext `http://` is acceptable for a private
/// host, which Tailscale already encrypts, and for no other.
fn hosts_with(own: Option<&'static str>) -> Vec<HostSpec> {
    own.map(|base| HostSpec {
        base,
        probe_timeout: OWN_NODE_PROBE_TIMEOUT,
    })
    .into_iter()
    .chain(PUBLIC_TESTNET4_HOSTS)
    .collect()
}
/// The base every URL is built on, and the first public host. `http_get` rewrites it to the host in
/// use.
const ESPLORA_TESTNET4: &str = "https://blockstream.info/testnet4/api";
/// Hash of testnet4 block 0. A host whose `/block-height/0` says anything else serves another
/// chain, and a balance or a broadcast there would be wrong, so it is never used.
const TESTNET4_GENESIS: &str = "00000000da84f2bafbbc53dee25a72ae507ff4914b867c565be350b0da8bf043";
/// How long a host that passed the genesis check is used before it is probed again.
const HOST_TRUST: Duration = Duration::from_secs(10 * 60);
/// The whole probe of a public host, DNS to the last body byte. The per-read limit alone let a host
/// that sent one byte every 3 s hold the host lock for 30 s.
const PROBE_TIMEOUT: Duration = Duration::from_secs(8);
/// The own node answers in under 100 ms when Tailscale is up and not at all when it is down, so a
/// phone off the tailnet loses at most this once per `HOST_TRUST` before the public hosts.
const OWN_NODE_PROBE_TIMEOUT: Duration = Duration::from_secs(3);
/// How long a round in which no host passed is answered from memory, so callers that queued on the
/// host lock during that round do not each probe every host again.
const PROBE_FAILURE_TTL: Duration = Duration::from_secs(5);
const ACCOUNT_PATH: &str = "m/84'/1'/0'";

/// BIP-44 gap limit. A chain is scanned until this many consecutive unused indices follow the last
/// used one, so funds paid to an address further out than that are not seen or spent.
const GAP_LIMIT: u32 = 20;
/// Most indices scanned per chain. The 24-word public test phrase the phone imported (`abandon` 23
/// times, then `art`) uses receive index 250 and none of the indices sampled from 300 to 10,000
/// (2026-11-05), so its scan needs about 271 to 320 indices, and a cap of 200 refused it. The
/// Esplora host is a trusted dependency. A scan succeeds only when the last used index plus
/// `GAP_LIMIT` is below the cap, so a host that lies about which addresses are used can make the
/// wallet hand out a receive or change address at any index up to 980, where an honest gap-20
/// rescan would not find it, and a recovery that must find every coin scans the first 981
/// addresses of each chain. The cap also holds a scan to 1,000 lookups per chain, and a host that
/// calls every address used is refused after 990. A snapshot then asks one coin list per funded
/// address, at most 980 per chain. shortcut: 1,000 per chain, raise it when a real wallet's chain
/// nears it.
const MAX_SCAN_PER_CHAIN: usize = 1_000;
/// How long a finished scan answers the next call of the same wallet with no request, so the app's
/// snapshot followed by history costs one scan. A used address's balance is never older than this.
const SHARE_TTL: Duration = Duration::from_secs(10);
/// How long the cached used-address set is trusted before the whole gap limit is scanned again.
const FULL_RESCAN: Duration = Duration::from_secs(30 * 60);
/// Unused indices after the last used one that an incremental scan asks about. shortcut: a coin paid
/// to an index further out, or to an unused index below the last used one, shows at the next full
/// scan (`FULL_RESCAN` or an app restart); raise it when a wallet is seen handing out that many
/// addresses inside half an hour.
const LOOKAHEAD: usize = 5;
// Lookups in flight at once. Ten fills a fresh chain's gap in two windows and is a small burst for a
// public explorer.
const WINDOW: usize = 10;

// Esplora connects in well under a second. Past 10 s on a weak phone network the host is not answering.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
// Per read and per write. ureq 2 has neither by default, so a server that went silent held the call for good.
const IO_TIMEOUT: Duration = Duration::from_secs(20);
// The whole request, DNS to the last body byte. The per-read limit alone lets a body that trickles
// hold a call for as long as the host likes.
const REQUEST_TIMEOUT: Duration = Duration::from_secs(45);

/// `whole` is ureq's deadline over the request. A body read that runs past it fails with an io
/// `TimedOut`, which `classify` reports as the host being unreachable.
fn agent(connect: Duration, io: Duration, whole: Duration) -> ureq::Agent {
    ureq::AgentBuilder::new()
        .timeout_connect(connect)
        .timeout_read(io)
        .timeout_write(io)
        .timeout(whole)
        .build()
}

static AGENT: LazyLock<ureq::Agent> =
    LazyLock::new(|| agent(CONNECT_TIMEOUT, IO_TIMEOUT, REQUEST_TIMEOUT));

/// What the last probe round decided: the index into the host list of the host in use and when it
/// passed the genesis check, or that no host passed, when, and the error that says so.
#[derive(Clone, Debug, PartialEq, Eq)]
enum HostVerdict {
    Chosen(usize, Instant),
    NoneReachable(Instant, String),
}
type HostChoice = Mutex<Option<HostVerdict>>;
static CHOSEN_HOST: HostChoice = Mutex::new(None);

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
    /// Confirmed plus mempool, over every address of both chains: what the wallet holds.
    pub balance_sats: u64,
    /// What a payment could spend now: the confirmed coins of receive addresses and the wallet's
    /// own change, confirmed or not. The same coins `hot_prepare_send_all` spends, so a receive
    /// still in the mempool counts in `balance_sats` and not here.
    pub spendable_sats: u64,
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

    /// SHA-256 of the two chain xpubs. Public data, so the cache can name a wallet without holding
    /// a key, and different words, passphrase or network give a different id.
    fn id(&self) -> WalletId {
        let mut bytes = Vec::with_capacity(2 * 78);
        for chain in &self.chains {
            bytes.extend_from_slice(&Xpub::from_priv(&self.secp, chain).encode());
        }
        sha256::Hash::hash(&bytes).to_byte_array()
    }
}

type WalletId = [u8; 32];

/// What a scan learned about one index. No key: the account rederives it offline.
#[derive(Clone, Copy)]
struct Seen {
    used: bool,
    balance: u64,
}

#[derive(Clone)]
struct CachedScan {
    chains: [Vec<Seen>; 2],
    /// When the last scan over the whole gap limit finished.
    full_at: Instant,
    /// When this picture was taken.
    scanned_at: Instant,
}

/// Every cached scan, and how many times they were all forgotten. A scan that started before a
/// forget (a broadcast went out while it ran) has a picture older than the chain, so `scan_wallet`
/// compares the count it read first before it writes.
#[derive(Default)]
struct Scans {
    epoch: u64,
    entries: HashMap<WalletId, CachedScan>,
}

type ScanCache = Mutex<Scans>;
static SCAN_CACHE: LazyLock<ScanCache> = LazyLock::new(ScanCache::default);

fn forget_scans(scans: &ScanCache) {
    let mut scans = scans
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    scans.entries.clear();
    scans.epoch += 1;
}

// Retries are for a GET only. A retried broadcast could pay twice, so `broadcast_signed` has none.
const RETRIES: usize = 2;
const BACKOFF: [Duration; RETRIES] = [Duration::from_secs(1), Duration::from_secs(2)];
const MAX_RETRY_AFTER: Duration = Duration::from_secs(5);
const MAX_EXTRA_WAIT: Duration = Duration::from_secs(8);

/// Why one attempt at a GET failed, which decides whether it is asked again, with the text the caller
/// gets when it is the last attempt.
enum GetError {
    /// Throttled or a brief server error, answered quickly.
    Retryable {
        retry_after: Option<Duration>,
        what: String,
    },
    /// No whole answer arrived and no limit ran out: no connection, TLS, a reset, a body cut short.
    Unreachable(String),
    /// The connect, a read or the whole request ran past its limit.
    TimedOut(String),
    /// The host answered and the answer is no use: another status, or a body that cannot be read.
    Fatal(String),
}

impl GetError {
    fn text(&mut self) -> &mut String {
        match self {
            GetError::Retryable { what, .. } => what,
            GetError::Unreachable(text) | GetError::TimedOut(text) | GetError::Fatal(text) => text,
        }
    }
}

impl From<GetError> for MobileSignerError {
    fn from(mut failure: GetError) -> Self {
        MobileSignerError::InvalidPsbtJson(std::mem::take(failure.text()))
    }
}

/// Asks until an attempt gets a 200 or a `Fatal` answer, or the attempts run out: `RETRIES + 1`, or
/// two once one timed out, because a timeout has already held the call up to `REQUEST_TIMEOUT`. A GET
/// is a read, so asking again after a lost connection is safe. The waits add up to at most
/// `MAX_EXTRA_WAIT`. The error is the last attempt's, so the caller sees whether the host answered.
/// shortcut: Retry-After in HTTP-date form is ignored, upgrade if the host is seen sending it.
fn get_with_retry(
    attempt: &dyn Fn() -> Result<String, GetError>,
    sleep: &dyn Fn(Duration),
) -> Result<String, GetError> {
    let mut slept = Duration::ZERO;
    let mut timed_out = false;
    for n in 0.. {
        let mut failure = match attempt() {
            Ok(body) => return Ok(body),
            Err(failure) => failure,
        };
        let retry_after = match &failure {
            GetError::Fatal(_) => return Err(failure),
            GetError::Retryable { retry_after, .. } => *retry_after,
            GetError::Unreachable(_) => None,
            GetError::TimedOut(_) => {
                timed_out = true;
                None
            }
        };
        if n == RETRIES || (timed_out && n > 0) {
            let attempts = format!(", gave up after {} attempts", n + 1);
            failure.text().push_str(&attempts);
            return Err(failure);
        }
        let wait = retry_after
            .map_or(BACKOFF.get(n).copied().unwrap_or_default(), |d| {
                d.min(MAX_RETRY_AFTER)
            })
            .min(MAX_EXTRA_WAIT - slept);
        sleep(wait);
        slept += wait;
    }
    unreachable!()
}

// A timeout is asked again once: one stalled connection in a scan of hundreds says little about the
// host, and a second timeout ends it. A limit on a blocking socket can surface as `WouldBlock`, which
// ureq turns into `TimedOut` while reading the answer but not while writing the request, where a TLS
// handshake that stalls can run out.
fn classify(answer: Result<ureq::Response, ureq::Error>) -> Result<String, GetError> {
    use std::io::ErrorKind;
    let limit = |kind| matches!(kind, ErrorKind::TimedOut | ErrorKind::WouldBlock);
    match answer {
        Ok(response) => {
            let what = format!("esplora body of {}", response.get_url());
            response.into_string().map_err(|e| {
                let text = format!("{what}: {e}");
                match e.kind() {
                    kind if limit(kind) => GetError::TimedOut(text),
                    ErrorKind::ConnectionReset
                    | ErrorKind::ConnectionAborted
                    | ErrorKind::UnexpectedEof => GetError::Unreachable(text),
                    _ => GetError::Fatal(text),
                }
            })
        }
        Err(ureq::Error::Status(code @ (429 | 502 | 503 | 504), response)) => {
            let retry_after = response
                .header("Retry-After")
                .and_then(|v| v.trim().parse::<u64>().ok())
                .map(Duration::from_secs);
            Err(GetError::Retryable {
                retry_after,
                what: format!("esplora get: HTTP status {code}"),
            })
        }
        Err(e @ ureq::Error::Transport(_)) => {
            let timed_out = std::error::Error::source(&e)
                .and_then(|source| source.downcast_ref::<std::io::Error>())
                .is_some_and(|io| limit(io.kind()));
            let text = format!("esplora get: {e}");
            Err(if timed_out {
                GetError::TimedOut(text)
            } else {
                GetError::Unreachable(text)
            })
        }
        Err(e) => Err(GetError::Fatal(format!("esplora get: {e}"))),
    }
}

/// The index of the host to use: the cached one while it is under `HOST_TRUST` old, else the first
/// in `hosts` whose `probe` returns the testnet4 genesis hash, which is then cached as of `now`. A
/// host that answers another hash is skipped like one that does not answer. No host passing fails
/// with `esplora get: no testnet4 host is reachable`, naming each host's failure, and that round is
/// remembered for `PROBE_FAILURE_TTL`: a caller inside it gets the same error with no probe.
fn pick_host(
    hosts: &[HostSpec],
    probe: &dyn Fn(&HostSpec) -> Result<String, MobileSignerError>,
    now: Instant,
    cache: &HostChoice,
) -> Result<usize, MobileSignerError> {
    let mut verdict = cache
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let age = |at: &Instant| now.saturating_duration_since(*at);
    match &*verdict {
        Some(HostVerdict::Chosen(index, verified)) if age(verified) < HOST_TRUST => {
            return Ok(*index);
        }
        Some(HostVerdict::NoneReachable(at, error)) if age(at) < PROBE_FAILURE_TTL => {
            return Err(MobileSignerError::InvalidPsbtJson(error.clone()));
        }
        _ => {}
    }
    let mut failures = Vec::with_capacity(hosts.len());
    for (index, host) in hosts.iter().enumerate() {
        match probe(host) {
            Ok(body) if body.trim() == TESTNET4_GENESIS => {
                *verdict = Some(HostVerdict::Chosen(index, now));
                return Ok(index);
            }
            Ok(body) => {
                let shown: String = body.trim().chars().take(24).collect();
                failures.push(format!("{}: block 0 is {shown:?}, not testnet4", host.base));
            }
            Err(e) => failures.push(format!("{}: {e}", host.base)),
        }
    }
    let error = format!(
        "esplora get: no testnet4 host is reachable ({})",
        failures.join("; ")
    );
    *verdict = Some(HostVerdict::NoneReachable(now, error.clone()));
    Err(MobileSignerError::InvalidPsbtJson(error))
}

/// Forgets the host in use after a GET that got no whole answer (no connection, TLS, a reset, a body
/// cut short, a timeout), so the next call probes again, and every cached scan with it, so the next
/// call scans the whole gap limit. A status or an unreadable body is the host answering and changes
/// nothing.
fn forget_host_if_unreachable(
    got: &Result<String, GetError>,
    cache: &HostChoice,
    scans: &ScanCache,
) {
    if matches!(got, Err(GetError::Unreachable(_) | GetError::TimedOut(_))) {
        *cache
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner()) = None;
        forget_scans(scans);
    }
}

/// `url` with the canonical base swapped for `base`. Any other URL is returned as it is.
fn on_host(url: &str, base: &str) -> String {
    match url.strip_prefix(ESPLORA_TESTNET4) {
        Some(rest) => format!("{base}{rest}"),
        None => url.to_string(),
    }
}

// The connect and each read get the host's whole-probe limit too: a host that cannot say its block 0
// hash in time is skipped for the next one instead of holding the scan.
fn probe_genesis(host: &HostSpec) -> Result<String, MobileSignerError> {
    let limit = host.probe_timeout;
    agent(limit, limit, limit)
        .get(&format!("{}/block-height/0", host.base))
        .call()
        .and_then(|response| response.into_string().map_err(ureq::Error::from))
        .map_err(|e| MobileSignerError::InvalidPsbtJson(e.to_string()))
}

/// The base of the testnet4 host in use, probing the list when none is cached.
fn selected_base() -> Result<&'static str, MobileSignerError> {
    let hosts = &*ESPLORA_TESTNET4_HOSTS;
    let index = pick_host(hosts, &probe_genesis, Instant::now(), &CHOSEN_HOST)?;
    Ok(hosts[index].base)
}

fn http_get(url: &str) -> Result<String, MobileSignerError> {
    let url = on_host(url, selected_base()?);
    get_forgetting(
        &|| classify(AGENT.get(&url).call()),
        &std::thread::sleep,
        &CHOSEN_HOST,
        &SCAN_CACHE,
    )
}

/// `call` with the retry, forgetting the host in use and every cached scan when the last attempt got
/// no whole answer: a loss the retry recovered says the host answers. Every GET of the wallet goes
/// through here, the fee tiers included.
fn get_forgetting(
    call: &dyn Fn() -> Result<String, GetError>,
    sleep: &dyn Fn(Duration),
    hosts: &HostChoice,
    scans: &ScanCache,
) -> Result<String, MobileSignerError> {
    let got = get_with_retry(call, sleep);
    forget_host_if_unreachable(&got, hosts, scans);
    Ok(got?)
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

    /// The used addresses whose balance is above zero: the only ones whose coin lists are worth a
    /// request. A host whose list for an emptied address still holds a coin contradicts its own
    /// stats, and the coin is left alone.
    fn funded(&self) -> impl Iterator<Item = (Chain, &OwnKey)> {
        [Chain::External, Chain::Internal]
            .into_iter()
            .zip(&self.chains)
            .flat_map(|(chain, scanned)| {
                scanned
                    .iter()
                    .filter(|s| s.used && s.balance > 0)
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

    fn seen(&self) -> [Vec<Seen>; 2] {
        let seen = |scanned: &Vec<Scanned>| {
            scanned
                .iter()
                .map(|s| Seen {
                    used: s.used,
                    balance: s.balance,
                })
                .collect()
        };
        [seen(&self.chains[0]), seen(&self.chains[1])]
    }
}

/// `seen` at `index` with its key derived again. No request.
fn recalled(
    account: &Account,
    chain: Chain,
    index: usize,
    seen: Seen,
) -> Result<Scanned, MobileSignerError> {
    Ok(Scanned {
        key: account.key(chain, index as u32)?,
        used: seen.used,
        balance: seen.balance,
    })
}

/// The wallet as of `now`, from `cache` when it can be and from Esplora otherwise. Three scans:
///
/// - Shared: a scan of this wallet finished under `SHARE_TTL` ago. Its picture is returned with no
///   request, so snapshot then history is one scan.
/// - Incremental: the last full scan is under `FULL_RESCAN` old. Every used index is asked again,
///   so balances are fresh, plus `LOOKAHEAD` unused indices after the last used one of each chain;
///   a lookahead index that turned out used continues like a full scan from there.
/// - Full: nothing cached, or the last full scan is `FULL_RESCAN` old. The whole gap limit.
///
/// A scan that fails forgets the wallet's entry, so the cache never answers from a picture a call
/// refused. The entry holds no key: see `Seen`.
fn scan_wallet(
    esplora: Esplora,
    words: &[String],
    passphrase: &str,
    network: KeyNetwork,
    now: Instant,
    cache: &ScanCache,
) -> Result<Wallet, MobileSignerError> {
    let account = Account::new(words, passphrase, network_of(network))?;
    let base = esplora_base(network)?;
    let id = account.id();
    let (epoch, known) = {
        let scans = cache
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        (scans.epoch, scans.entries.get(&id).cloned())
    };
    let age = |at: Instant| now.saturating_duration_since(at);
    let seed = match &known {
        Some(c) if age(c.scanned_at) < SHARE_TTL => {
            return Ok(Wallet {
                chains: [
                    recall_chain(&account, Chain::External, &c.chains[0])?,
                    recall_chain(&account, Chain::Internal, &c.chains[1])?,
                ],
            });
        }
        Some(c) if age(c.full_at) < FULL_RESCAN => Some(c),
        _ => None,
    };
    let scan = |chain: Chain| {
        let known = seed.map(|c| c.chains[chain as usize].as_slice());
        scan_chain(esplora, base, &account, chain, known)
    };
    let scanned = scan(Chain::External).and_then(|external| {
        Ok(Wallet {
            chains: [external, scan(Chain::Internal)?],
        })
    });
    let mut scans = cache
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    // A forget while this scan ran means a payment went out that its picture does not show.
    if scans.epoch == epoch {
        match &scanned {
            Ok(wallet) => {
                scans.entries.insert(
                    id,
                    CachedScan {
                        chains: wallet.seen(),
                        full_at: seed.map_or(now, |c| c.full_at),
                        scanned_at: now,
                    },
                );
            }
            Err(_) => {
                scans.entries.remove(&id);
            }
        }
    }
    scanned
}

fn recall_chain(
    account: &Account,
    chain: Chain,
    seen: &[Seen],
) -> Result<Vec<Scanned>, MobileSignerError> {
    seen.iter()
        .enumerate()
        .map(|(i, s)| recalled(account, chain, i, *s))
        .collect()
}

/// `indices` of `chain` asked about, in order. Keys are derived here and never leave.
fn lookup(
    esplora: Esplora,
    base: &str,
    account: &Account,
    chain: Chain,
    indices: impl Iterator<Item = usize>,
) -> Result<Vec<Scanned>, MobileSignerError> {
    let keys = indices
        .map(|i| account.key(chain, i as u32))
        .collect::<Result<Vec<_>, _>>()?;
    let urls: Vec<String> = keys
        .iter()
        .map(|k| format!("{base}/address/{}", k.address))
        .collect();
    let answers = get_all(esplora, &urls)?;
    keys.into_iter()
        .zip(answers)
        .map(|(key, raw)| {
            let stats = parse_address_stats(&raw)?;
            Ok(Scanned {
                key,
                used: stats.used,
                balance: stats.balance,
            })
        })
        .collect()
}

/// A chain scanned until `GAP_LIMIT` unused indices follow the last used one, `WINDOW` lookups at
/// a time, starting from `refreshed(known)` when there is a cached chain and from nothing otherwise.
fn scan_chain(
    esplora: Esplora,
    base: &str,
    account: &Account,
    chain: Chain,
    known: Option<&[Seen]>,
) -> Result<Vec<Scanned>, MobileSignerError> {
    let mut scanned = match known {
        Some(known) => refreshed(esplora, base, account, chain, known)?,
        None => Vec::new(),
    };
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
        scanned.extend(lookup(
            esplora,
            base,
            account,
            chain,
            from..wanted.min(from + WINDOW),
        )?);
    }
}

/// The cached chain with every used index and the `LOOKAHEAD` indices after the last used one asked
/// about again, in one batch. Unused indices below the last used one keep their cached zero. When no
/// lookahead index turned out used the cached tail is kept too, so the chain is complete as it is;
/// when one did, the chain ends at the lookahead so `scan_chain` scans on from there.
fn refreshed(
    esplora: Esplora,
    base: &str,
    account: &Account,
    chain: Chain,
    known: &[Seen],
) -> Result<Vec<Scanned>, MobileSignerError> {
    let after_last_used = known
        .iter()
        .rposition(|s| s.used)
        .map_or(0, |last| last + 1);
    let edge = after_last_used + LOOKAHEAD;
    let asked = (0..edge).filter(|&i| i >= after_last_used || known[i].used);
    let mut fresh = lookup(esplora, base, account, chain, asked)?.into_iter();
    let mut scanned = Vec::with_capacity(known.len());
    for i in 0..edge {
        scanned.push(match known.get(i) {
            Some(seen) if i < after_last_used && !seen.used => recalled(account, chain, i, *seen)?,
            _ => fresh.next().ok_or_else(|| {
                MobileSignerError::InvalidPsbtJson("esplora scan: fewer answers than asked".into())
            })?,
        });
    }
    if scanned[after_last_used..].iter().all(|s| !s.used) {
        for (i, seen) in known.iter().enumerate().skip(edge) {
            scanned.push(recalled(account, chain, i, *seen)?);
        }
    }
    Ok(scanned)
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
    snapshot_from(
        &http_get,
        &mnemonic_words,
        &passphrase,
        network,
        Instant::now(),
        &SCAN_CACHE,
    )
}

/// The first unused receive address, the confirmed + mempool balance over every address of both
/// chains, and the spendable balance from the same coin lists a payment would spend. About 40
/// Esplora calls for a fresh wallet, fewer after `scan_wallet` cached one, plus one coin list per
/// funded address, so call it off the main thread.
fn snapshot_from(
    esplora: Esplora,
    words: &[String],
    passphrase: &str,
    network: KeyNetwork,
    now: Instant,
    cache: &ScanCache,
) -> Result<HotWalletSnapshot, MobileSignerError> {
    let wallet = scan_wallet(esplora, words, passphrase, network, now, cache)?;
    let (_, spendable_sats) = spendable_coins(esplora, esplora_base(network)?, &wallet)?;
    Ok(HotWalletSnapshot {
        receive_address: wallet.next_unused(Chain::External).address.to_string(),
        balance_sats: wallet.balance()?,
        spendable_sats,
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
    history_of(
        &http_get,
        &mnemonic_words,
        &passphrase,
        network,
        Instant::now(),
        &SCAN_CACHE,
    )
}

/// The latest transactions of every used address of the wallet, each once, as the wallet saw it:
/// the mempool first, then confirmed transactions newest first. Only each address's first page
/// comes back (about 25 confirmed transactions plus the mempool), so older history is not here.
///
/// Makes a network call per used address plus one for the tip, on top of whatever `scan_wallet`
/// needs, so call it off the main thread. An answer it cannot read fails the whole call, never one
/// transaction.
fn history_of(
    esplora: Esplora,
    words: &[String],
    passphrase: &str,
    network: KeyNetwork,
    now: Instant,
    cache: &ScanCache,
) -> Result<Vec<HotTx>, MobileSignerError> {
    let base = esplora_base(network)?;
    let wallet = scan_wallet(esplora, words, passphrase, network, now, cache)?;
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
    let tip = tip_height(tip_text).map_err(bad)?;
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

/// Who owns an output, whether the transaction creates it or an input spends it.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum TxoOwner {
    /// The wallet's receive chain, `0/i`.
    Receive,
    /// The wallet's change chain, `1/i`.
    Change,
    /// Anyone else's, or a script with no address.
    Other,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct HotTxo {
    /// None for a script with no address (OP_RETURN) and for a coinbase input.
    pub address: Option<String>,
    pub value_sats: u64,
    pub owner: TxoOwner,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct HotTxDetail {
    /// The row read again from this answer by `hot_wallet_history`'s rule, so the confirmations are as of now.
    pub tx: HotTx,
    /// None while unconfirmed.
    pub block_height: Option<u64>,
    /// In the transaction's order.
    pub inputs: Vec<HotTxo>,
    /// In the transaction's order.
    pub outputs: Vec<HotTxo>,
    /// Inputs minus outputs, from the values above. 0 for a coinbase, which pays none.
    pub fee_sats: u64,
    pub size_bytes: u64,
    /// Weight / 4, rounded up.
    pub vsize: u64,
    /// True when an input's sequence is below 0xfffffffe (BIP-125). Says nothing about full-RBF nodes.
    pub signals_rbf: bool,
}

// UniFFI hashes the doc below into the checksum, so it stays short and final and the behaviour is on `detail_of`.
/// One transaction of the wallet in full, or None when the explorer does not know `txid`.
#[uniffi::export]
pub fn hot_tx_detail(
    mnemonic_words: Vec<String>,
    passphrase: String,
    network: KeyNetwork,
    txid: String,
) -> Result<Option<HotTxDetail>, MobileSignerError> {
    detail_of(&http_get, &mnemonic_words, &passphrase, network, &txid)
}

/// Refuses a txid that is not 64 hex before any request. Then `/tx/{txid}` and, second so a block landing between the
/// two only adds a confirmation, the tip. A 404 for that transaction is None and asks nothing more. There is no scan, because
/// the wallet's own addresses are derived offline.
fn detail_of(
    esplora: Esplora,
    words: &[String],
    passphrase: &str,
    network: KeyNetwork,
    txid: &str,
) -> Result<Option<HotTxDetail>, MobileSignerError> {
    let txid = Txid::from_str(txid)
        .map_err(|e| tx_error(format!("txid is not 64 hex characters: {e}")))?;
    let base = esplora_base(network)?;
    let account = Account::new(words, passphrase, network_of(network))?;
    let body = match esplora(&format!("{base}/tx/{txid}")) {
        Err(e) if is_not_found(&e, &txid) => return Ok(None),
        answer => answer?,
    };
    let tip = esplora(&format!("{base}/blocks/tip/height"))?;
    detail_from(&body, &tip, &txid, &own_addresses(&account)?).map(Some)
}

/// Every address a scan of this wallet can reach, with its chain, which is every index below `MAX_SCAN_PER_CHAIN` on
/// both chains.
/// shortcut: 2 x MAX_SCAN_PER_CHAIN `Account::key` calls per open, 73 ms in a release build on an M3 Mac at the cap of
/// 1,000 (2026-11-05); bound them by the cached scan when that is felt.
fn own_addresses(account: &Account) -> Result<HashMap<String, Chain>, MobileSignerError> {
    let mut own = HashMap::with_capacity(2 * MAX_SCAN_PER_CHAIN);
    for chain in [Chain::External, Chain::Internal] {
        for index in 0..MAX_SCAN_PER_CHAIN as u32 {
            own.insert(account.key(chain, index)?.address.to_string(), chain);
        }
    }
    Ok(own)
}

/// `tx_json` is the `/tx/{txid}` answer. The row comes from `hot_tx`, so its net and confirmations follow the history's
/// rule exactly. Strict like every parser here, so another transaction, a part it cannot read, no input or no output, a
/// coinbase beside other inputs, a side above 21 million BTC, outputs above the inputs, or a weight outside the size to 4 x
/// the size fails the whole detail.
fn detail_from(
    tx_json: &str,
    tip_text: &str,
    txid: &Txid,
    own: &HashMap<String, Chain>,
) -> Result<HotTxDetail, MobileSignerError> {
    let bad = |what: String| tx_error(format!("transaction {txid}: {what}"));
    let tip = tip_height(tip_text).map_err(bad)?;
    let tx: Value = serde_json::from_str(tx_json).map_err(|e| bad(format!("not JSON: {e}")))?;
    if tx["txid"] != txid.to_string().as_str() {
        return Err(bad("the answer is another transaction".into()));
    }
    let own_keys: Vec<&str> = own.keys().map(String::as_str).collect();
    let row = hot_tx(&tx, tip, &own_keys).map_err(bad)?;
    let part = |txo: &Value, value_sats: u64| {
        let address = txo["scriptpubkey_address"].as_str();
        HotTxo {
            address: address.map(str::to_string),
            value_sats,
            owner: match address.and_then(|a| own.get(a)) {
                Some(Chain::External) => TxoOwner::Receive,
                Some(Chain::Internal) => TxoOwner::Change,
                None => TxoOwner::Other,
            },
        }
    };
    let vin = tx["vin"]
        .as_array()
        .ok_or_else(|| bad("vin is not an array".into()))?;
    let vout = tx["vout"]
        .as_array()
        .ok_or_else(|| bad("vout is not an array".into()))?;
    if vin.is_empty() || vout.is_empty() {
        return Err(bad("no input or no output".into()));
    }

    let mut inputs = Vec::with_capacity(vin.len());
    let mut coinbase = false;
    let mut signals_rbf = false;
    for (j, input) in vin.iter().enumerate() {
        let sequence = input["sequence"]
            .as_u64()
            .and_then(|s| u32::try_from(s).ok())
            .ok_or_else(|| bad(format!("input {j} has no u32 sequence")))?;
        signals_rbf |= sequence < 0xffff_fffe;
        let prevout = &input["prevout"];
        if prevout.is_null() && input["is_coinbase"] == true {
            // A coinbase makes new coins, so it is its transaction's only input.
            if vin.len() > 1 {
                return Err(bad(format!("input {j} is a coinbase beside other inputs")));
            }
            coinbase = true;
            inputs.push(part(prevout, 0));
            continue;
        }
        let value = prevout["value"]
            .as_u64()
            .ok_or_else(|| bad(format!("input {j} has no prevout value")))?;
        inputs.push(part(prevout, value));
    }
    let outputs = vout
        .iter()
        .enumerate()
        .map(|(j, output)| {
            let value = output["value"]
                .as_u64()
                .ok_or_else(|| bad(format!("output {j} has no value")))?;
            Ok(part(output, value))
        })
        .collect::<Result<Vec<_>, MobileSignerError>>()?;

    // No amount above 21 million BTC is real, and a side that adds up to at most that holds no value above it.
    let sum = |txos: &[HotTxo], side: &str| {
        txos.iter()
            .try_fold(0u64, |total, t| total.checked_add(t.value_sats))
            .filter(|total| *total <= Amount::MAX_MONEY.to_sat())
            .ok_or_else(|| bad(format!("the {side} add up to more than 21 million BTC")))
    };
    let (paid_in, paid_out) = (sum(&inputs, "inputs")?, sum(&outputs, "outputs")?);
    let fee_sats = if coinbase {
        0
    } else {
        paid_in
            .checked_sub(paid_out)
            .ok_or_else(|| bad(format!("outputs {paid_out} are more than inputs {paid_in}")))?
    };
    let positive = |field: &str| {
        tx[field]
            .as_u64()
            .filter(|n| *n > 0)
            .ok_or_else(|| bad(format!("no positive integer {field}")))
    };
    let (size_bytes, weight) = (positive("size")?, positive("weight")?);
    // Weight is three times the size without witnesses plus the whole size.
    if !(size_bytes..=size_bytes.saturating_mul(4)).contains(&weight) {
        return Err(bad(format!(
            "weight {weight} is outside size {size_bytes} to 4 x size"
        )));
    }
    Ok(HotTxDetail {
        block_height: tx["status"]["block_height"]
            .as_u64()
            .filter(|_| row.confirmations > 0),
        tx: row,
        inputs,
        outputs,
        fee_sats,
        size_bytes,
        vsize: weight.div_ceil(4),
        signals_rbf,
    })
}

/// `classify`'s text for an HTTP 404 of `/tx/{txid}` on any host (ureq 2.12.1 prints "{url}: status code 404", error.rs:214,
/// then maybe " (redirected from ...)"). The status is gone past the `Esplora` boundary, so a test pins this to `classify`.
/// A probe's 404 that `pick_host` quotes in its own error names `/block-height/0`, so it is a failure.
fn is_not_found(error: &MobileSignerError, txid: &Txid) -> bool {
    matches!(error, MobileSignerError::InvalidPsbtJson(t) if t.contains(&format!("/tx/{txid}: status code 404")))
}

fn tip_height(text: &str) -> Result<u64, String> {
    text.trim()
        .parse()
        .map_err(|e| format!("tip height is not an integer: {e}"))
}

fn tx_error(what: String) -> MobileSignerError {
    MobileSignerError::InvalidPsbtJson(format!("esplora tx: {what}"))
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct HotSendResult {
    pub txid: String,
}

/// True when `address` parses and belongs to `network`: the same test `hot_prepare_send_at_rate` applies to its destination.
#[uniffi::export]
pub fn is_valid_address(address: String, network: KeyNetwork) -> bool {
    Address::from_str(&address).is_ok_and(|a| a.require_network(network_of(network)).is_ok())
}

/// A signed payment that has not been posted anywhere.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct PreparedSend {
    /// Computed from the signed transaction here, never read from a server.
    pub txid: String,
    /// The signed transaction, consensus-encoded, for `hot_broadcast`.
    pub hex: String,
    /// The fee the transaction pays, in sats: its virtual size times the rate asked for, rounded up.
    /// A payment whose change would be dust is refused instead of folding the change into the fee.
    pub fee_sats: u64,
    /// What `destination` is paid, in sats: the amount asked for, or for `hot_prepare_send_all`
    /// the whole balance less `fee_sats`, which is only known once the coins are.
    pub amount_sats: u64,
}

/// Scan, select coins, check and sign a payment at `fee_rate_sat_vb`, and post nothing. The only
/// network use is the Esplora lookups of the scan. The app saves the `txid` as its payment guard
/// before it calls `hot_broadcast`, so a kill between the two can never lead to a second payment.
///
/// The fee is the size of the transaction as built times the rate, so a payment that needs more
/// coins pays more, never a fixed sum that falls under the rate. The answer's `fee_sats` is that
/// fee, and the app compares it with the estimate its screens showed and asks the user when it is
/// much higher. Refuses, before signing, a rate outside 1 to 2,000 sat/vB, an amount below the 546
/// sat dust limit, a fee above the amount, and change that would be dust (above 0 and at most 546
/// sats). Only confirmed coins of receive addresses are spent; the wallet's own unconfirmed change
/// is spent too. About 40 Esplora calls for a fresh wallet, so call it off the main thread.
#[uniffi::export]
pub fn hot_prepare_send_at_rate(
    mnemonic_words: Vec<String>,
    passphrase: String,
    network: KeyNetwork,
    destination: String,
    amount_sats: u64,
    fee_rate_sat_vb: u64,
) -> Result<PreparedSend, MobileSignerError> {
    let (signed, fee_sats) = signed_payment(
        &http_get,
        &mnemonic_words,
        &passphrase,
        network,
        &destination,
        amount_sats,
        fee_rate_sat_vb,
        Instant::now(),
        &SCAN_CACHE,
    )?;
    Ok(prepared(&signed, fee_sats))
}

fn prepared(signed: &Transaction, fee_sats: u64) -> PreparedSend {
    PreparedSend {
        txid: signed.compute_txid().to_string(),
        hex: serialize_hex(signed),
        fee_sats,
        // Both builders put the destination first and change, if any, second.
        amount_sats: signed.output[0].value.to_sat(),
    }
}

/// Scan, spend every coin `hot_prepare_send_at_rate` would consider, and sign one output paying
/// `destination` the whole balance less the fee, with no change, and post nothing. The fee is the
/// size of that transaction times `fee_rate_sat_vb`, so it and the amount are only known once the
/// coins are: the answer's `amount_sats` is what leaves. Refuses, before signing, a rate outside
/// 1 to 2,000 sat/vB, a wallet with nothing confirmed to spend, and a
/// balance that would leave less than the 546 sat dust limit after the fee. The same scan as
/// `hot_prepare_send_at_rate`, so call it off the main thread.
#[uniffi::export]
pub fn hot_prepare_send_all(
    mnemonic_words: Vec<String>,
    passphrase: String,
    network: KeyNetwork,
    destination: String,
    fee_rate_sat_vb: u64,
) -> Result<PreparedSend, MobileSignerError> {
    let (signed, fee_sats) = signed_send_all(
        &http_get,
        &mnemonic_words,
        &passphrase,
        network,
        &destination,
        fee_rate_sat_vb,
        Instant::now(),
        &SCAN_CACHE,
    )?;
    Ok(prepared(&signed, fee_sats))
}

/// Post a transaction from `hot_prepare_send_at_rate` once. `txid` must be the id of the transaction in
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
    esplora_base(network)?;
    let post = |base: &str, hex: &str| {
        AGENT
            .post(&format!("{base}/tx"))
            .set("Content-Type", "text/plain")
            .send_string(hex)
    };
    // Not /tx/{txid}/status: Esplora answers that one 200 for a txid it never saw (blockstream.info, 2026-11-02).
    let lookup = |base: &str, txid: &str| AGENT.get(&format!("{base}/tx/{txid}")).call();
    let pause = || std::thread::sleep(LOOKUP_PAUSE);
    broadcast_via(
        selected_base(),
        &post,
        &lookup,
        &pause,
        &hex,
        &txid,
        &SCAN_CACHE,
    )
}

/// `broadcast_matching` on `host`. When `host` is the error from `pick_host`, nothing is posted.
/// Once the payment is out, or may be, every cached scan is forgotten (the call does not know
/// which wallet paid), so the next refresh scans the whole gap limit. A refusal posted nothing.
fn broadcast_via(
    host: Result<&str, MobileSignerError>,
    post: &dyn Fn(&str, &str) -> Answer,
    lookup: &dyn Fn(&str, &str) -> Answer,
    pause: &dyn Fn(),
    hex: &str,
    txid: &str,
    scans: &ScanCache,
) -> Result<HotSendResult, MobileSignerError> {
    let base = host?;
    let result = broadcast_matching(
        &|hex| post(base, hex),
        &|txid| lookup(base, txid),
        pause,
        hex,
        txid,
    );
    if matches!(result, Ok(_) | Err(MobileSignerError::BroadcastUnknown(_))) {
        forget_scans(scans);
    }
    Ok(HotSendResult { txid: result? })
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

/// Virtual size, rounded up, of a transaction spending `inputs` P2WPKH coins to outputs whose
/// scripts are `output_scripts` bytes long: 10.5 vB overhead (version, locktime, counts, segwit
/// marker and flag), 68 vB per input, and 8 + 1 + the script per output (value, length, script).
/// A taproot or P2WSH output is 43 vB and a P2WPKH one 31, so sizing every output as P2WPKH paid
/// under the rate.
fn vsize(inputs: usize, output_scripts: &[usize]) -> u64 {
    let outputs: u64 = output_scripts.iter().map(|len| 9 + *len as u64).sum();
    let twice = 21 + 136 * inputs as u64 + 2 * outputs;
    twice.div_ceil(2)
}

/// The change output's script: P2WPKH, 22 bytes.
const P2WPKH_SCRIPT: usize = 22;

/// How many times the coin selection is redone for a fee that grew with the inputs it chose.
/// Each pass adds at least one input, so a payment over many small coins can run out of passes.
const FEE_PASSES: usize = 4;

fn check_rate(rate_sat_vb: u64) -> Result<(), MobileSignerError> {
    if rate_sat_vb < MIN_FEE_RATE {
        return Err(MobileSignerError::InvalidPsbtJson(format!(
            "fee rate {rate_sat_vb} is below {MIN_FEE_RATE} sat/vB"
        )));
    }
    if rate_sat_vb > MAX_FEE_RATE {
        return Err(MobileSignerError::InvalidPsbtJson(format!(
            "fee rate {rate_sat_vb} is above {MAX_FEE_RATE} sat/vB"
        )));
    }
    Ok(())
}

fn destination_on(destination: &str, network: KeyNetwork) -> Result<Address, MobileSignerError> {
    Address::from_str(destination)
        .map_err(|e| MobileSignerError::InvalidPsbtJson(e.to_string()))?
        .require_network(network_of(network))
        .map_err(|e| MobileSignerError::InvalidPsbtJson(e.to_string()))
}

fn coins_overflow() -> MobileSignerError {
    MobileSignerError::InvalidPsbtJson("esplora utxo: the coins' values do not fit u64".into())
}

/// The coins a payment may spend, each with its key, and their sum: every confirmed coin of a
/// funded address, and the wallet's own change unconfirmed too, because it is this wallet's own
/// transaction and waiting for it would lock the balance after every payment. Own change is an
/// unconfirmed coin on an internal address whose transaction spends one of the wallet's own coins,
/// which nobody else can: a change address is public once its first payment confirms, and a
/// stranger's coin there could be replaced from under the payment. The coin lists are asked for on
/// every call, never cached, plus one `/tx` lookup per transaction with unconfirmed change. An
/// outpoint listed twice, a sum past u64 or a lookup that is not that transaction fails the whole
/// call.
type Coins<'w> = Vec<(Utxo, &'w OwnKey)>;

fn spendable_coins<'w>(
    esplora: Esplora,
    base: &str,
    wallet: &'w Wallet,
) -> Result<(Coins<'w>, u64), MobileSignerError> {
    let owners: Vec<(Chain, &OwnKey)> = wallet.funded().collect();
    let urls: Vec<String> = owners
        .iter()
        .map(|(_, k)| format!("{base}/address/{}/utxo", k.address))
        .collect();
    let lists = get_all(esplora, &urls)?;
    let mut seen = HashSet::new();
    let mut listed: Vec<(Utxo, &OwnKey, Chain)> = Vec::new();
    for ((chain, owner), list) in owners.iter().zip(&lists) {
        for utxo in parse_utxos(list)? {
            if !seen.insert((utxo.txid, utxo.vout)) {
                return Err(MobileSignerError::InvalidPsbtJson(format!(
                    "esplora utxo: {}:{} is listed twice",
                    utxo.txid, utxo.vout
                )));
            }
            listed.push((utxo, owner, *chain));
        }
    }
    let own_change = own_change_transactions(esplora, base, wallet, &listed)?;
    let spendable: Coins = listed
        .into_iter()
        .filter(|(utxo, _, chain)| {
            utxo.confirmed || (matches!(chain, Chain::Internal) && own_change.contains(&utxo.txid))
        })
        .map(|(utxo, owner, _)| (utxo, owner))
        .collect();
    let have = spendable
        .iter()
        .try_fold(0u64, |sum, (u, _)| sum.checked_add(u.value))
        .ok_or_else(coins_overflow)?;
    Ok((spendable, have))
}

/// Among the transactions of the unconfirmed coins on internal addresses in `listed`, the ones this
/// wallet signed: each spends a coin of one of the wallet's own addresses. One lookup per
/// transaction, none when there is no such coin.
fn own_change_transactions(
    esplora: Esplora,
    base: &str,
    wallet: &Wallet,
    listed: &[(Utxo, &OwnKey, Chain)],
) -> Result<HashSet<Txid>, MobileSignerError> {
    let mut pending: Vec<Txid> = Vec::new();
    for (utxo, _, chain) in listed {
        if !utxo.confirmed && matches!(chain, Chain::Internal) && !pending.contains(&utxo.txid) {
            pending.push(utxo.txid);
        }
    }
    if pending.is_empty() {
        return Ok(HashSet::new());
    }
    let urls: Vec<String> = pending.iter().map(|t| format!("{base}/tx/{t}")).collect();
    let answers = get_all(esplora, &urls)?;
    let own = wallet.addresses();
    let own: Vec<&str> = own.iter().map(String::as_str).collect();
    let mut signed = HashSet::new();
    for (txid, body) in pending.iter().zip(&answers) {
        if spends_own(body, txid, &own)? {
            signed.insert(*txid);
        }
    }
    Ok(signed)
}

/// Whether the `/tx/{txid}` answer `json` spends an output of one of `own`. An answer that is not
/// that transaction, or whose inputs cannot be read, is an error: a guess either way would spend
/// a stranger's coin or hide the wallet's own.
fn spends_own(json: &str, txid: &Txid, own: &[&str]) -> Result<bool, MobileSignerError> {
    let bad = |what: &str| {
        MobileSignerError::InvalidPsbtJson(format!("esplora utxo: transaction {txid}: {what}"))
    };
    let tx: Value = serde_json::from_str(json).map_err(|e| bad(&format!("not JSON: {e}")))?;
    if tx["txid"] != txid.to_string().as_str() {
        return Err(bad("the answer is another transaction"));
    }
    let vin = tx["vin"]
        .as_array()
        .ok_or_else(|| bad("vin is not an array"))?;
    Ok(vin.iter().any(|input| {
        input["prevout"]["scriptpubkey_address"]
            .as_str()
            .is_some_and(|a| own.contains(&a))
    }))
}

/// `selected` spent to `outputs`, each input signed with its coin's own key.
fn signed(
    selected: &[(&Utxo, &OwnKey)],
    outputs: Vec<TxOut>,
) -> Result<Transaction, MobileSignerError> {
    let secp = Secp256k1::new();
    let tx = Transaction {
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
        output: outputs,
    };
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

/// A payment from the coins of every used address, each input signed with its own key, any change
/// paid to the first unused internal (change) address, not yet broadcast, with the fee it pays.
/// The amount, the rate, the destination and the fee of the smallest payment are checked before
/// any network call; the change and the real fee after the coins are chosen. Every refusal is an
/// error, never a changed payment. The used-address set may come from `scan_wallet`'s cache.
#[allow(clippy::too_many_arguments)]
fn signed_payment(
    esplora: Esplora,
    words: &[String],
    passphrase: &str,
    network: KeyNetwork,
    destination: &str,
    amount_sats: u64,
    rate_sat_vb: u64,
    now: Instant,
    cache: &ScanCache,
) -> Result<(Transaction, u64), MobileSignerError> {
    let refuse = |what: String| Err(MobileSignerError::InvalidPsbtJson(what));
    if amount_sats < DUST {
        return refuse(format!(
            "amount {amount_sats} is below the {DUST} sat dust limit"
        ));
    }
    check_rate(rate_sat_vb)?;
    let dest = destination_on(destination, network)?;
    // The destination first, then change; the slice to one output drops the change.
    let outputs = [dest.script_pubkey().len(), P2WPKH_SCRIPT];
    // The rate is at most 2,000 and the size a few thousand vB, so the product fits u64.
    let fee_for = |inputs: usize, change: bool| {
        rate_sat_vb * vsize(inputs, &outputs[..if change { 2 } else { 1 }])
    };
    let fee_checked = |fee_sats: u64| -> Result<u64, MobileSignerError> {
        if fee_sats > amount_sats {
            return Err(MobileSignerError::InvalidPsbtJson(format!(
                "fee {fee_sats} is more than the amount {amount_sats}"
            )));
        }
        amount_sats.checked_add(fee_sats).ok_or_else(|| {
            MobileSignerError::InvalidPsbtJson("amount plus fee does not fit u64".into())
        })
    };
    // The smallest payment is one input and change. A fee the rules refuse there is refused before any lookup.
    let mut fee_sats = fee_for(1, true);
    fee_checked(fee_sats)?;

    let base = esplora_base(network)?;
    let wallet = scan_wallet(esplora, words, passphrase, network, now, cache)?;
    let (mut spendable, have) = spendable_coins(esplora, base, &wallet)?;
    // Largest first, ties by outpoint: which coins pay never depends on the host's listing order,
    // and dust is touched only once the big coins fall short.
    spendable.sort_by(|(a, _), (b, _)| {
        b.value
            .cmp(&a.value)
            .then(a.txid.cmp(&b.txid))
            .then(a.vout.cmp(&b.vout))
    });
    // The fee depends on how many coins pay it, and the coins on the fee, so the selection is
    // redone until the fee covers the coins it chose. A fee the coins over-cover (the change
    // vanished and the transaction shrank) is kept: it is at least the rate and under the amount.
    let mut passes = 0;
    let (selected, change) = loop {
        let need = fee_checked(fee_sats)?;
        if have < need {
            return refuse(format!(
                "insufficient funds: have {have} confirmed, need {need}"
            ));
        }
        let mut selected = Vec::new();
        let mut total = 0u64;
        for (utxo, owner) in &spendable {
            total = total.checked_add(utxo.value).ok_or_else(coins_overflow)?;
            selected.push((utxo, *owner));
            if total >= need {
                break;
            }
        }
        let change = total - need;
        let real = fee_for(selected.len(), change > 0);
        if real <= fee_sats {
            break (selected, change);
        }
        passes += 1;
        if passes == FEE_PASSES {
            return refuse(format!(
                "fee did not settle in {FEE_PASSES} passes, send a smaller amount"
            ));
        }
        fee_sats = real;
    };
    if change > 0 && change <= DUST {
        return refuse("change below dust, send the whole balance or change the amount".into());
    }
    let mut outputs = vec![TxOut {
        value: Amount::from_sat(amount_sats),
        script_pubkey: dest.script_pubkey(),
    }];
    if change > 0 {
        outputs.push(TxOut {
            value: Amount::from_sat(change),
            script_pubkey: wallet.next_unused(Chain::Internal).address.script_pubkey(),
        });
    }
    Ok((signed(&selected, outputs)?, fee_sats))
}

/// The whole balance to `destination` in one output and no change: every coin `signed_payment`
/// would consider, the fee the size of that transaction times the rate, and the amount what is
/// left, not yet broadcast, with the fee it pays. The rate and the destination are checked before
/// any network call; the fee and the amount once the coins are known. Every refusal is an error,
/// never a changed payment.
#[allow(clippy::too_many_arguments)]
fn signed_send_all(
    esplora: Esplora,
    words: &[String],
    passphrase: &str,
    network: KeyNetwork,
    destination: &str,
    rate_sat_vb: u64,
    now: Instant,
    cache: &ScanCache,
) -> Result<(Transaction, u64), MobileSignerError> {
    let refuse = |what: String| Err(MobileSignerError::InvalidPsbtJson(what));
    check_rate(rate_sat_vb)?;
    let dest = destination_on(destination, network)?;
    let base = esplora_base(network)?;
    let wallet = scan_wallet(esplora, words, passphrase, network, now, cache)?;
    let (spendable, have) = spendable_coins(esplora, base, &wallet)?;
    // One input and one output is the smallest this transaction can be, so a wallet with nothing
    // to spend is short by that fee and the dust limit.
    let fee_sats = rate_sat_vb * vsize(spendable.len().max(1), &[dest.script_pubkey().len()]);
    if spendable.is_empty() {
        return refuse(format!(
            "insufficient funds: have {have} confirmed, need {}",
            fee_sats + DUST
        ));
    }
    let amount_sats = match have.checked_sub(fee_sats) {
        Some(amount) if amount >= DUST => amount,
        _ => {
            return refuse(format!(
                "the whole balance {have} less the fee {fee_sats} is below the {DUST} sat dust limit"
            ));
        }
    };
    let selected: Vec<(&Utxo, &OwnKey)> = spendable.iter().map(|(u, k)| (u, *k)).collect();
    let output = TxOut {
        value: Amount::from_sat(amount_sats),
        script_pubkey: dest.script_pubkey(),
    };
    Ok((signed(&selected, vec![output])?, fee_sats))
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

/// The fee tiers the Send screen offers, in sat/vB. `slow_sat_vb` aims at confirmation in about an
/// hour, `normal_sat_vb` in about half an hour and `fast_sat_vb` in the next block or two. `live` is
/// true when they come from the explorer's view of the mempool and false when they are the fixed
/// fallback of 4, 8 and 15.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct FeeTiers {
    pub slow_sat_vb: u64,
    pub normal_sat_vb: u64,
    pub fast_sat_vb: u64,
    pub live: bool,
}

const DEFAULT_FEE_TIERS: FeeTiers = FeeTiers {
    slow_sat_vb: 4,
    normal_sat_vb: 8,
    fast_sat_vb: 15,
    live: false,
};
/// How long live tiers answer from memory. Fees move over blocks, not seconds.
const FEE_TIERS_TTL: Duration = Duration::from_secs(5 * 60);
/// How long the fallback answers from memory, so a host that comes back is noticed soon.
const FEE_FALLBACK_TTL: Duration = Duration::from_secs(30);
/// Confirmation targets in blocks for slow, normal and fast. mempool's `hourFee`, `halfHourFee` and
/// `fastestFee` are read as these same targets, so both answer formats go through one rule.
const FEE_TARGETS: [u32; 3] = [6, 3, 1];

type FeeCache = Mutex<Option<(FeeTiers, Instant)>>;
static FEE_CACHE: FeeCache = Mutex::new(None);

/// The fee tiers for the Send screen, from the testnet4 host in use. One network call in the usual
/// case, answered from memory for five minutes after. Never fails: when no host answers or the
/// answer is unusable the fixed tiers 4, 8 and 15 come back with `live` false.
#[uniffi::export]
pub fn hot_fee_tiers(network: KeyNetwork) -> FeeTiers {
    fee_tiers_from(&http_get, network, Instant::now(), &FEE_CACHE)
}

fn fee_tiers_from(
    esplora: Esplora,
    network: KeyNetwork,
    now: Instant,
    cache: &FeeCache,
) -> FeeTiers {
    let Ok(base) = esplora_base(network) else {
        return DEFAULT_FEE_TIERS;
    };
    // The lock is held through the fetch so callers that queue on it get the same answer, not a
    // fetch each.
    let mut cached = cache
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    if let Some((tiers, at)) = &*cached {
        let ttl = if tiers.live {
            FEE_TIERS_TTL
        } else {
            FEE_FALLBACK_TTL
        };
        if now.saturating_duration_since(*at) < ttl {
            return tiers.clone();
        }
    }
    let tiers = fetch_fee_tiers(esplora, base).unwrap_or(DEFAULT_FEE_TIERS);
    *cached = Some((tiers.clone(), now));
    tiers
}

/// mempool's `/v1/fees/recommended` first, since the own node is one; a host without it
/// (blockstream.info answers 404) is asked Esplora's `/fee-estimates` instead. `None` when neither
/// gives a usable rate.
fn fetch_fee_tiers(esplora: Esplora, base: &str) -> Option<FeeTiers> {
    let get = |path: &str, estimates: fn(&Value) -> Vec<(u32, &Value)>| {
        let body = esplora(&format!("{base}{path}")).ok()?;
        let json: Value = serde_json::from_str(&body).ok()?;
        tiers_from(&estimates(&json))
    };
    get("/v1/fees/recommended", recommended_estimates)
        .or_else(|| get("/fee-estimates", esplora_estimates))
}

/// `hourFee`, `halfHourFee` and `fastestFee` as the targets 6, 3 and 1.
fn recommended_estimates(json: &Value) -> Vec<(u32, &Value)> {
    ["hourFee", "halfHourFee", "fastestFee"]
        .into_iter()
        .zip(FEE_TARGETS)
        .map(|(field, target)| (target, &json[field]))
        .collect()
}

/// Esplora's map from confirmation target in blocks to sat/vB, as `{"1": 12.3, "2": 9.0}`.
fn esplora_estimates(json: &Value) -> Vec<(u32, &Value)> {
    json.as_object()
        .into_iter()
        .flatten()
        .filter_map(|(target, rate)| Some((target.parse().ok()?, rate)))
        .collect()
}

/// A rate as the Send screen can use it: a finite positive number rounded up to whole sat/vB and
/// capped at `MAX_FEE_RATE`, above which `hot_prepare_send_at_rate` refuses the payment. Anything else
/// (zero, negative, NaN, a string, a missing field) is no estimate.
fn whole_sat_vb(rate: &Value) -> Option<u64> {
    let rate = rate.as_f64().filter(|r| r.is_finite() && *r > 0.0)?;
    Some((rate.ceil() as u64).min(MAX_FEE_RATE))
}

/// Slow, normal and fast from whatever targets a host gave. Each tier takes the rate at the nearest
/// present target, the faster one on a tie, so a sparse map still gives a fee that confirms in the
/// tier's time. The lower tiers are then raised to keep slow <= normal <= fast: a host's estimates
/// are not always in that order and the screen promises it.
fn tiers_from(estimates: &[(u32, &Value)]) -> Option<FeeTiers> {
    let usable: Vec<(u32, u64)> = estimates
        .iter()
        .filter_map(|(target, rate)| Some((*target, whole_sat_vb(rate)?)))
        .collect();
    let nearest = |wanted: u32| {
        usable
            .iter()
            .min_by_key(|(target, _)| (target.abs_diff(wanted), *target))
            .map(|(_, rate)| *rate)
    };
    let [slow, normal, fast] = FEE_TARGETS.map(nearest);
    let (slow, normal, fast) = (slow?, normal?, fast?);
    let normal = normal.max(slow);
    Some(FeeTiers {
        slow_sat_vb: slow,
        normal_sat_vb: normal,
        fast_sat_vb: fast.max(normal),
        live: true,
    })
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
    fn address_check_matches_what_hot_prepare_send_accepts() {
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
    /// Stands in for the private node of a build, so the tests read the same with or without one.
    const OWN_NODE_FIXTURE: &str = "http://node.internal:8889/api";

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

    /// Esplora keyed by address. An address not set up answers as one nobody ever paid. Each Fake
    /// has its own scan cache and a clock that moves only by `tick`, so no test sleeps and none
    /// sees another's cache.
    struct Fake {
        stats: HashMap<String, String>,
        txs: HashMap<String, String>,
        utxos: HashMap<String, String>,
        /// What `/tx/{txid}` answers. A transaction not set up is a stranger's: it spends none of the wallet's coins.
        parents: HashMap<String, String>,
        failing: HashSet<String>,
        tip: String,
        calls: Mutex<Vec<String>>,
        now: Mutex<Instant>,
        cache: ScanCache,
    }

    impl Fake {
        fn new() -> Self {
            Self {
                stats: HashMap::new(),
                txs: HashMap::new(),
                utxos: HashMap::new(),
                parents: HashMap::new(),
                failing: HashSet::new(),
                tip: TIP.into(),
                calls: Mutex::new(Vec::new()),
                now: Mutex::new(Instant::now()),
                cache: ScanCache::default(),
            }
        }

        fn now(&self) -> Instant {
            *self.now.lock().unwrap()
        }

        fn tick(&self, by: Duration) {
            *self.now.lock().unwrap() += by;
        }

        fn requests(&self) -> usize {
            self.calls.lock().unwrap().len()
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

        /// `address` holds one coin of `value` sats still in the mempool that is this wallet's own change:
        /// the transaction that paid it spends a coin of the wallet's first receive address.
        fn fund_change(&mut self, address: &str, value: u64, digit: char) {
            self.fund_unconfirmed(address, value, digit);
            self.parents.insert(
                id(digit),
                tx_json(
                    digit,
                    &[spent(&addr(Chain::External, 0), value + 1_000)],
                    &[txo(address, value)],
                    MEMPOOL,
                ),
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
            if let Some(txid) = path.strip_prefix("/tx/") {
                return Ok(self.parents.get(txid).cloned().unwrap_or_else(|| {
                    format!(
                        r#"{{"txid":"{txid}","vin":[{}],"vout":[],"status":{MEMPOOL}}}"#,
                        spent(SOMEONE, 1_000_000)
                    )
                }));
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
            self.snapshot_of(&abandon_about(), passphrase)
        }

        fn snapshot_of(
            &self,
            words: &[String],
            passphrase: &str,
        ) -> Result<HotWalletSnapshot, MobileSignerError> {
            snapshot_from(
                &|url| self.get(url),
                words,
                passphrase,
                KeyNetwork::Test,
                self.now(),
                &self.cache,
            )
        }

        fn history(&self) -> Result<Vec<HotTx>, MobileSignerError> {
            history_of(
                &|url| self.get(url),
                &abandon_about(),
                "",
                KeyNetwork::Test,
                self.now(),
                &self.cache,
            )
        }

        /// A payment at `rate` sat/vB, and the fee it pays.
        fn send_at(
            &self,
            to: &str,
            amount: u64,
            rate: u64,
        ) -> Result<(Transaction, u64), MobileSignerError> {
            let words = abandon_about();
            signed_payment(
                &|url| self.get(url),
                &words,
                "",
                KeyNetwork::Test,
                to,
                amount,
                rate,
                self.now(),
                &self.cache,
            )
        }

        fn send(&self, to: &str, amount: u64, rate: u64) -> Result<Transaction, MobileSignerError> {
            self.send_at(to, amount, rate).map(|(tx, _)| tx)
        }

        /// The whole balance at `rate` sat/vB, as `hot_prepare_send_all` reaches it, and the fee it pays.
        fn send_all_at(
            &self,
            to: &str,
            rate: u64,
        ) -> Result<(Transaction, u64), MobileSignerError> {
            let words = abandon_about();
            signed_send_all(
                &|url| self.get(url),
                &words,
                "",
                KeyNetwork::Test,
                to,
                rate,
                self.now(),
                &self.cache,
            )
        }

        /// `hot_prepare_send_at_rate` as the app would reach it, against this Fake.
        fn ready(&self, to: &str, amount: u64, rate: u64) -> PreparedSend {
            let (tx, fee) = self.send_at(to, amount, rate).unwrap();
            prepared(&tx, fee)
        }

        /// `hot_broadcast` as the app would reach it, against this Fake's scan cache.
        fn broadcast(
            &self,
            ready: &PreparedSend,
            post: Answer,
        ) -> Result<HotSendResult, MobileSignerError> {
            let post = Mutex::new(Some(post));
            broadcast_via(
                Ok(ESPLORA_TESTNET4),
                &|_, _| post.lock().unwrap().take().expect("one post"),
                &|_, _| not_found(),
                &|| {},
                &ready.hex,
                &ready.txid,
                &self.cache,
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
        match fake.send(SOMEONE, 1_000, 1) {
            Err(MobileSignerError::InvalidPsbtJson(m)) => {
                assert_eq!(m, "insufficient funds: have 0 confirmed, need 1141")
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
        let tx = fake.send(SOMEONE, 5_000, 2).unwrap();
        assert_eq!(tx.input.len(), 1);
        assert_eq!(tx.input[0].previous_output.txid.to_string(), id('3'));
        assert!(signed_by(&tx, 0, 7_000, &own(Chain::External, 3)));
        assert_outputs(&tx, &[(SOMEONE, 5_000), (&addr(Chain::Internal, 0), 1_718)]);
    }

    #[test]
    fn a_coin_on_a_change_address_is_counted_and_spent() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::Internal, 0), 3_000, '4');
        assert_eq!(
            receive_and_balance(fake.snapshot("").unwrap()),
            (addr(Chain::External, 0), 3_000)
        );
        let tx = fake.send(SOMEONE, 2_000, 2).unwrap();
        assert!(signed_by(&tx, 0, 3_000, &own(Chain::Internal, 0)));
        assert_outputs(&tx, &[(SOMEONE, 2_000), (&addr(Chain::Internal, 1), 718)]);
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
            assert!(fake.send(SOMEONE, 1_000, 1).is_err(), "{index}");
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
            fake.send(SOMEONE, 6_000, 2).unwrap(),
            fake.send(SOMEONE, 6_000, 2).unwrap(),
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
        let tx = fake.send(SOMEONE, 6_000, 2).unwrap();
        assert_eq!(tx.input.len(), 2);
        assert!(signed_by(&tx, 0, 4_000, &first));
        assert!(signed_by(&tx, 1, 4_000, &third));
        // The check can tell the keys apart.
        assert!(!signed_by(&tx, 0, 4_000, &third));
        assert!(!signed_by(&tx, 1, 4_000, &first));
        assert_outputs(&tx, &[(SOMEONE, 6_000), (&addr(Chain::Internal, 0), 1_582)]);
    }

    #[test]
    fn change_goes_to_the_first_unused_internal_address_and_never_an_external_one() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 10_000, '1');
        fake.touch(&addr(Chain::Internal, 0));
        fake.touch(&addr(Chain::Internal, 1));
        let tx = fake.send(SOMEONE, 1_000, 2).unwrap();
        assert_outputs(&tx, &[(SOMEONE, 1_000), (&addr(Chain::Internal, 2), 8_718)]);
        let externals: Vec<String> = (0..40).map(|i| addr(Chain::External, i)).collect();
        assert!(!externals.contains(&pays(&tx.output[1])));
    }

    #[track_caller]
    fn assert_refused<T: std::fmt::Debug>(result: Result<T, MobileSignerError>, message: &str) {
        match result {
            Err(MobileSignerError::InvalidPsbtJson(m)) => assert_eq!(m, message),
            other => panic!("expected {message:?}, got {other:?}"),
        }
    }

    /// What the transaction pays: its inputs' values (the Fake's coins) minus its outputs.
    fn fee_paid(fake: &Fake, tx: &Transaction) -> u64 {
        let coins: HashMap<(String, u32), u64> = fake
            .utxos
            .values()
            .flat_map(|list| parse_utxos(list).unwrap())
            .map(|u| ((u.txid.to_string(), u.vout), u.value))
            .collect();
        let spent: u64 = tx
            .input
            .iter()
            .map(|i| coins[&(i.previous_output.txid.to_string(), i.previous_output.vout)])
            .sum();
        spent - tx.output.iter().map(|o| o.value.to_sat()).sum::<u64>()
    }

    // Finding 6. Before, change of 1 to 546 sats was quietly added to the fee.
    #[test]
    fn dust_change_is_refused_and_change_of_547_or_0_is_fine() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 10_000, '1');
        let dust = "change below dust, send the whole balance or change the amount";
        // 10,000 less 9,000 less 141 x 4 (436) or 141 x 7 (13) is dust; less 141 x 3 (577) is not.
        assert_refused(fake.send(SOMEONE, 9_000, 4), dust);
        assert_refused(fake.send(SOMEONE, 9_000, 7), dust);
        assert_outputs(
            &fake.send(SOMEONE, 9_000, 3).unwrap(),
            &[(SOMEONE, 9_000), (&addr(Chain::Internal, 0), 577)],
        );
        // At 1 sat/vB the fee is 141: 9,313 leaves exactly 546 and 9,312 leaves 547.
        assert_refused(fake.send(SOMEONE, 9_313, 1), dust);
        assert_outputs(
            &fake.send(SOMEONE, 9_312, 1).unwrap(),
            &[(SOMEONE, 9_312), (&addr(Chain::Internal, 0), 547)],
        );
        // Send all, as the app computes it: the balance less the 141 vB estimate. One output, and
        // the estimate is kept as the fee rather than shrunk into dust change.
        let (all, fee) = fake.send_at(SOMEONE, 10_000 - 141, 1).unwrap();
        assert_outputs(&all, &[(SOMEONE, 9_859)]);
        assert_eq!(fee, 141);
        assert_eq!(fee_paid(&fake, &all), 141);
    }

    // Finding 5. Each guard is a distinct error before any signing.
    #[test]
    fn a_dust_amount_a_rate_out_of_range_or_a_fee_above_the_amount_is_refused_before_any_lookup() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 10_000, '1');
        assert_refused(
            fake.send(SOMEONE, 0, 0),
            "amount 0 is below the 546 sat dust limit",
        );
        assert_refused(
            fake.send(SOMEONE, 545, 1),
            "amount 545 is below the 546 sat dust limit",
        );
        assert_refused(fake.send(SOMEONE, 1_000, 0), "fee rate 0 is below 1 sat/vB");
        assert_refused(
            fake.send(SOMEONE, 1_000, 2_001),
            "fee rate 2001 is above 2000 sat/vB",
        );
        assert_refused(
            fake.send(SOMEONE, 1_000, 8),
            "fee 1128 is more than the amount 1000",
        );
        assert!(fake.calls.lock().unwrap().is_empty());
        assert_outputs(
            &fake.send(SOMEONE, 546, 3).unwrap(),
            &[(SOMEONE, 546), (&addr(Chain::Internal, 0), 9_031)],
        );
    }

    // Findings 5 and 7. One P2WPKH input and two outputs is 141 vB; one output is 110 vB.
    #[test]
    fn the_fee_is_the_size_of_the_transaction_as_built_times_the_rate() {
        assert_eq!(vsize(1, &[P2WPKH_SCRIPT; 2]), 141);
        assert_eq!(vsize(1, &[P2WPKH_SCRIPT]), 110);
        assert_eq!(vsize(11, &[P2WPKH_SCRIPT]), 790);
        let mut fake = Fake::new();
        for i in 0..3u32 {
            fake.fund(&addr(Chain::External, i), 4_000, char::from(b'1' + i as u8));
        }
        for (amount, inputs) in [(3_000, 1), (6_000, 2), (10_000, 3)] {
            let (tx, fee) = fake.send_at(SOMEONE, amount, 2).unwrap();
            assert_eq!((tx.input.len(), tx.output.len()), (inputs, 2));
            assert_eq!(
                fee,
                2 * vsize(inputs, &[P2WPKH_SCRIPT; 2]),
                "{inputs} inputs"
            );
            assert_eq!(fee_paid(&fake, &tx), fee, "{inputs} inputs");
        }
        // Up to eight coins, each payment short of its coins by 2,000 sats so the change stays above dust.
        for coins in 4..=8usize {
            let mut fake = Fake::new();
            for i in 0..coins {
                fake.fund(
                    &addr(Chain::External, i as u32),
                    4_000,
                    char::from(b'1' + i as u8),
                );
            }
            let (tx, fee) = fake
                .send_at(SOMEONE, 4_000 * coins as u64 - 2_000, 2)
                .unwrap();
            assert_eq!(
                (tx.input.len(), tx.output.len()),
                (coins, 2),
                "{coins} coins"
            );
            assert_eq!(fee, 2 * vsize(coins, &[P2WPKH_SCRIPT; 2]), "{coins} coins");
            assert_eq!(fee_paid(&fake, &tx), fee, "{coins} coins");
        }
        let mut big = Fake::new();
        big.fund(&addr(Chain::External, 0), 1_000_000, '1');
        assert_eq!(big.send_at(SOMEONE, 600_000, 2_000).unwrap().1, 282_000);
    }

    // The stuck-payment bug: the node reports 1 sat/vB, the app asked for 1 x 141 sats, and any
    // payment that needs two coins (209 vB) was refused at every tier.
    #[test]
    fn a_payment_that_needs_two_coins_at_the_live_rate_of_1_goes_through() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 5_000, '1');
        fake.fund(&addr(Chain::External, 1), 5_000, '2');
        let (tx, fee) = fake.send_at(SOMEONE, 6_000, 1).unwrap();
        assert_eq!(tx.input.len(), 2);
        assert_eq!(fee, 209);
        assert_eq!(fee_paid(&fake, &tx), 209);
    }

    // Open item 73, seen on an iPhone on 2026-11-04. The app's send all was the balance less the
    // one-input estimate of 141 vB at the rate, through the ordinary path. Two coins and one output
    // are 178 vB, so that payment needs more than the wallet holds, and reserving more would leave
    // dust change. The send-all path spends every coin and takes the real fee off the amount.
    #[test]
    fn send_all_over_two_coins_goes_through_the_send_all_path_and_not_the_apps_old_sum() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 5_000, '1');
        fake.fund(&addr(Chain::External, 1), 5_000, '2');
        assert_refused(
            fake.send_at(SOMEONE, 10_000 - 141, 1),
            "insufficient funds: have 10000 confirmed, need 10037",
        );
        let (tx, fee) = fake.send_all_at(SOMEONE, 1).unwrap();
        assert_eq!(tx.input.len(), 2);
        assert_eq!(fee, 178);
        assert_outputs(&tx, &[(SOMEONE, 10_000 - 178)]);
        assert_eq!(fee_paid(&fake, &tx), 178);
        let ready = prepared(&tx, fee);
        assert_eq!((ready.amount_sats, ready.fee_sats), (9_822, 178));
        assert_eq!(fake.ready(SOMEONE, 6_000, 1).amount_sats, 6_000);
    }

    // Open item 77, seen on an iPhone on 2026-11-04. The app's ceiling was twice the one-coin estimate
    // and at least 300 sats. At 1 sat/vB four coins and change are 345 vB, four coins and one output
    // 314 vB, so both paths were refused with a message no retry could clear. The fee is the real
    // size times the rate, whatever the screen estimated; the app asks the user when it is high.
    #[test]
    fn a_payment_that_needs_four_coins_pays_the_real_fee_on_both_paths() {
        let mut fake = Fake::new();
        for i in 0..4u32 {
            fake.fund(&addr(Chain::External, i), 2_500, char::from(b'1' + i as u8));
        }
        let (tx, fee) = fake.send_at(SOMEONE, 9_000, 1).unwrap();
        assert_eq!((tx.input.len(), fee), (4, 345));
        assert_eq!(fee_paid(&fake, &tx), 345);
        let (all, fee) = fake.send_all_at(SOMEONE, 1).unwrap();
        assert_eq!((all.input.len(), fee), (4, 314));
        assert_outputs(&all, &[(SOMEONE, 10_000 - 314)]);
        assert_eq!(fee_paid(&fake, &all), 314);
    }

    #[test]
    fn send_all_pays_the_real_size_times_the_rate_over_one_to_eight_coins_with_no_change() {
        for coins in 1..=8usize {
            let mut fake = Fake::new();
            for i in 0..coins {
                fake.fund(
                    &addr(Chain::External, i as u32),
                    4_000,
                    char::from(b'1' + i as u8),
                );
            }
            let (tx, fee) = fake.send_all_at(SOMEONE, 2).unwrap();
            assert_eq!(
                (tx.input.len(), tx.output.len()),
                (coins, 1),
                "{coins} coins"
            );
            assert_eq!(fee, 2 * vsize(coins, &[P2WPKH_SCRIPT]), "{coins} coins");
            assert_eq!(fee_paid(&fake, &tx), fee, "{coins} coins");
            assert_outputs(&tx, &[(SOMEONE, 4_000 * coins as u64 - fee)]);
            for i in 0..coins {
                assert!(
                    signed_by(&tx, i, 4_000, &own(Chain::External, i as u32)),
                    "{coins} coins, input {i}"
                );
            }
        }
    }

    // The same coins as the ordinary path: a receive still in the mempool waits, own change does not.
    #[test]
    fn send_all_spends_confirmed_receives_and_own_unconfirmed_change_and_leaves_an_unconfirmed_receive()
     {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 5_000, '1');
        fake.fund_unconfirmed(&addr(Chain::External, 1), 3_000, '2');
        fake.fund_change(&addr(Chain::Internal, 0), 2_000, '3');
        let (tx, fee) = fake.send_all_at(SOMEONE, 1).unwrap();
        assert_eq!(tx.input.len(), 2);
        assert_eq!(fee, 178);
        assert_outputs(&tx, &[(SOMEONE, 7_000 - 178)]);
    }

    // At 1 sat/vB one coin and one output cost 110, so 656 sats leave exactly the dust limit and go.
    #[test]
    fn send_all_that_would_leave_less_than_dust_after_the_fee_is_refused() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 656, '1');
        assert_outputs(&fake.send_all_at(SOMEONE, 1).unwrap().0, &[(SOMEONE, 546)]);
        let mut short = Fake::new();
        short.fund(&addr(Chain::External, 0), 655, '1');
        assert_refused(
            short.send_all_at(SOMEONE, 1),
            "the whole balance 655 less the fee 110 is below the 546 sat dust limit",
        );
        let mut tiny = Fake::new();
        tiny.fund(&addr(Chain::External, 0), 100, '1');
        assert_refused(
            tiny.send_all_at(SOMEONE, 1),
            "the whole balance 100 less the fee 110 is below the 546 sat dust limit",
        );
    }

    #[test]
    fn send_all_of_an_empty_wallet_or_one_whose_only_coin_is_unconfirmed_is_refused() {
        let fake = Fake::new();
        assert_refused(
            fake.send_all_at(SOMEONE, 1),
            "insufficient funds: have 0 confirmed, need 656",
        );
        let mut pending = Fake::new();
        pending.fund_unconfirmed(&addr(Chain::External, 0), 5_000, '1');
        assert_refused(
            pending.send_all_at(SOMEONE, 2),
            "insufficient funds: have 0 confirmed, need 766",
        );
    }

    #[test]
    fn send_all_at_a_rate_out_of_range_or_to_the_wrong_network_is_refused_before_any_lookup() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 10_000, '1');
        assert_refused(fake.send_all_at(SOMEONE, 0), "fee rate 0 is below 1 sat/vB");
        assert_refused(
            fake.send_all_at(SOMEONE, 2_001),
            "fee rate 2001 is above 2000 sat/vB",
        );
        assert!(
            fake.send_all_at("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", 1)
                .is_err()
        );
        assert_eq!(fake.requests(), 0);
    }

    // Each pass adds at least one input, so coins that barely cover their own cost run it out.
    #[test]
    fn a_fee_that_does_not_settle_in_four_passes_is_refused() {
        let mut fake = Fake::new();
        let coins: Vec<String> = (0..1_400u32).map(|i| coin('a', i, 137, true)).collect();
        fake.stats
            .insert(addr(Chain::External, 0), used_stats(137 * 1_400));
        fake.utxos
            .insert(addr(Chain::External, 0), format!("[{}]", coins.join(",")));
        assert_refused(
            fake.send(SOMEONE, 100_000, 1),
            "fee did not settle in 4 passes, send a smaller amount",
        );
        // Over 1,000-sat coins the third pass settles: 12 inputs and change, 889 sats at 1 sat/vB.
        let mut many = Fake::new();
        for i in 0..12u32 {
            many.stats
                .insert(addr(Chain::External, i), used_stats(1_000));
            many.utxos.insert(
                addr(Chain::External, i),
                format!("[{}]", coin('a', i, 1_000, true)),
            );
        }
        let (tx, fee) = many.send_at(SOMEONE, 10_210, 1).unwrap();
        assert_eq!((tx.input.len(), tx.output.len()), (12, 2));
        assert_eq!(fee, vsize(12, &[P2WPKH_SCRIPT; 2]));
        assert_eq!(fee_paid(&many, &tx), 889);
    }

    // Finding 8. Before, the total wrapped in release and panicked in debug.
    #[test]
    fn a_coin_value_that_overflows_the_sum_is_an_error_not_a_panic() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 1_000, '1');
        fake.fund(&addr(Chain::External, 1), u64::MAX, '2');
        fake.fund(&addr(Chain::External, 2), 5_000, '3');
        assert_refused(
            fake.send(SOMEONE, 5_000, 1),
            "esplora utxo: the coins' values do not fit u64",
        );
        assert!(fake.snapshot("").is_err(), "the balance overflows too");
        assert_refused(
            fake.send_all_at(SOMEONE, 1),
            "esplora utxo: the coins' values do not fit u64",
        );
    }

    // Finding 9. The same outpoint in one list, and across two addresses' lists.
    #[test]
    fn a_duplicate_outpoint_fails_the_whole_call() {
        let twice = format!("[{0},{0}]", coin('1', 0, 5_000, true));
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 5_000, '1');
        fake.utxos.insert(addr(Chain::External, 0), twice);
        let listed_twice = format!("esplora utxo: {}:0 is listed twice", id('1'));
        assert_refused(fake.send(SOMEONE, 8_000, 1), &listed_twice);
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 5_000, '1');
        fake.fund(&addr(Chain::External, 1), 5_000, '1');
        assert_refused(fake.send(SOMEONE, 8_000, 1), &listed_twice);
    }

    // Finding 10. Every index below `first_refused` used closes the gap at exactly the cap, and
    // `first_refused` used too needs one index past it.
    #[test]
    fn the_scan_stops_at_the_cap_per_chain() {
        let abnormal = format!(
            "esplora scan: more than {MAX_SCAN_PER_CHAIN} addresses on one chain, the history is abnormal"
        );
        let account = Account::new(&abandon_about(), "", Network::Testnet4).unwrap();
        let at = |index: usize| {
            account
                .key(Chain::External, index as u32)
                .unwrap()
                .address
                .to_string()
        };
        let first_refused = MAX_SCAN_PER_CHAIN - GAP_LIMIT as usize;
        let mut fake = Fake::new();
        for i in 0..first_refused {
            fake.touch(&at(i));
        }
        assert_eq!(
            fake.snapshot("").unwrap().receive_address,
            at(first_refused)
        );
        assert_eq!(fake.requests(), MAX_SCAN_PER_CHAIN + GAP_LIMIT as usize);
        fake.touch(&at(first_refused));
        // Past the share window: the incremental scan finds that index used and hits the cap too.
        fake.tick(SHARE_TTL);
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
        let result = snapshot_from(
            &liar,
            &abandon_about(),
            "",
            KeyNetwork::Test,
            Instant::now(),
            &ScanCache::default(),
        );
        assert!(result.is_err());
        assert!(calls.load(std::sync::atomic::Ordering::SeqCst) <= MAX_SCAN_PER_CHAIN);
    }

    // Open item 75. The 24-word public test phrase the phone imported uses receive index 250, and a
    // cap of 200 refused it on every call.
    #[test]
    fn a_wallet_used_through_receive_index_300_loads_with_its_balance_and_next_receive_address() {
        let account = Account::new(&abandon_about(), "", Network::Testnet4).unwrap();
        let at = |chain, index| account.key(chain, index).unwrap().address.to_string();
        let mut fake = Fake::new();
        for i in 0..=300 {
            fake.touch(&at(Chain::External, i));
        }
        for i in 0..10 {
            fake.touch(&at(Chain::Internal, i));
        }
        fake.fund(&at(Chain::External, 250), 60_000, 'a');
        fake.fund(&at(Chain::External, 300), 25_000, 'b');
        fake.fund(&at(Chain::Internal, 9), 15_000, 'c');
        let snapshot = fake.snapshot("").unwrap();
        assert_eq!(
            (
                snapshot.receive_address,
                snapshot.balance_sats,
                snapshot.spendable_sats
            ),
            (at(Chain::External, 301), 100_000, 100_000)
        );
        // 301 used receive indices and their gap, 10 used change indices and theirs, 3 coin lists.
        assert_eq!(fake.requests(), 321 + 30 + 3);
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
            assert!(fake.send(SOMEONE, 1_000, 1).is_err(), "{body}");
        }
    }

    // Finding 14. Balance counts both coins; only the wallet's own unconfirmed change is spent.
    #[test]
    fn an_unconfirmed_receive_is_counted_but_not_spent_while_own_unconfirmed_change_is() {
        let mut fake = Fake::new();
        fake.fund_unconfirmed(&addr(Chain::External, 0), 5_000, '1');
        fake.fund_change(&addr(Chain::Internal, 0), 3_000, '2');
        assert_eq!(fake.snapshot("").unwrap().balance_sats, 8_000);
        let tx = fake.send(SOMEONE, 2_000, 2).unwrap();
        assert_eq!(tx.input.len(), 1);
        assert_eq!(tx.input[0].previous_output.txid.to_string(), id('2'));
        assert!(signed_by(&tx, 0, 3_000, &own(Chain::Internal, 0)));
        assert_refused(
            fake.send(SOMEONE, 4_000, 2),
            "insufficient funds: have 3000 confirmed, need 4282",
        );
    }

    // Finding 1. What the app saves as its guard is the id of the transaction it then posts.
    #[test]
    fn a_prepared_send_posts_nothing_and_its_txid_is_the_transaction_in_its_hex() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 10_000, '1');
        let (signed, fee) = fake.send_at(SOMEONE, 5_000, 2).unwrap();
        let ready = prepared(&signed, fee);
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
        assert_eq!(ready.fee_sats, 282);
        assert_eq!(fee_paid(&fake, &parsed), 282);
        // The same words give the same transaction, so hot_broadcast would post this txid.
        assert_eq!(ready, fake.ready(SOMEONE, 5_000, 2));
    }

    #[test]
    fn a_broadcast_whose_txid_is_not_the_transaction_is_refused_before_any_post() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 10_000, '1');
        let good = fake.ready(SOMEONE, 5_000, 2);
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
                fake.send(SOMEONE, 1_000, 1).map(|_| ()),
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

    /// Three used receive addresses and one used change address: a full scan is 23 + 21 = 44
    /// lookups, an incremental one 3 + 5 and 1 + 5 = 14.
    fn three_used() -> Fake {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 1_000, '1');
        fake.fund(&addr(Chain::External, 1), 1_000, '2');
        fake.fund(&addr(Chain::External, 2), 1_000, '3');
        fake.fund(&addr(Chain::Internal, 0), 1_000, '4');
        fake
    }

    fn just_under(window: Duration) -> Duration {
        window - Duration::from_millis(1)
    }

    #[test]
    fn a_cold_wallet_costs_forty_once_then_nothing_inside_the_share_window_then_the_lookahead() {
        let fake = Fake::new();
        assert_eq!(
            receive_and_balance(fake.snapshot("").unwrap()),
            (addr(Chain::External, 0), 0)
        );
        assert_eq!(fake.requests(), 40);
        fake.tick(just_under(SHARE_TTL));
        fake.snapshot("").unwrap();
        assert!(fake.history().unwrap().is_empty());
        assert_eq!(
            fake.requests(),
            40,
            "shared: no used address, no page, no tip"
        );
        fake.tick(Duration::from_millis(1));
        assert_eq!(
            receive_and_balance(fake.snapshot("").unwrap()),
            (addr(Chain::External, 0), 0)
        );
        assert_eq!(
            fake.requests(),
            50,
            "incremental: the lookahead of each chain"
        );
    }

    // Every snapshot also asks the coin list of each funded address (four here), shared scan or not,
    // because the spendable figure comes from the lists a payment would spend.
    #[test]
    fn a_warm_wallet_asks_its_used_addresses_and_the_lookahead_and_history_adds_the_pages_and_the_tip()
     {
        let fake = three_used();
        let coins = 4;
        assert_eq!(fake.snapshot("").unwrap().balance_sats, 4_000);
        assert_eq!(fake.requests(), 44 + coins);
        fake.tick(just_under(SHARE_TTL));
        assert_eq!(fake.snapshot("").unwrap().balance_sats, 4_000);
        fake.history().unwrap();
        assert_eq!(
            fake.requests(),
            48 + coins + 5,
            "shared: the coin lists, four pages and the tip"
        );
        fake.tick(Duration::from_millis(1));
        assert_eq!(
            receive_and_balance(fake.snapshot("").unwrap()),
            (addr(Chain::External, 3), 4_000)
        );
        assert_eq!(fake.requests(), 57 + 14 + coins, "incremental");
        fake.history().unwrap();
        assert_eq!(fake.requests(), 75 + 5, "shared with the incremental scan");
        fake.tick(FULL_RESCAN);
        assert_eq!(fake.snapshot("").unwrap().balance_sats, 4_000);
        assert_eq!(
            fake.requests(),
            80 + 44 + coins,
            "full again after FULL_RESCAN"
        );
    }

    #[test]
    fn a_used_address_balance_is_asked_again_once_the_share_window_has_passed() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 1_000, '1');
        assert_eq!(fake.snapshot("").unwrap().balance_sats, 1_000);
        fake.fund(&addr(Chain::External, 0), 5_000, '1');
        fake.tick(just_under(SHARE_TTL));
        assert_eq!(fake.snapshot("").unwrap().balance_sats, 1_000, "shared");
        fake.tick(Duration::from_millis(1));
        assert_eq!(fake.snapshot("").unwrap().balance_sats, 5_000);
    }

    #[test]
    fn a_new_coin_inside_the_lookahead_is_found_and_the_scan_goes_on_past_it() {
        let mut fake = three_used();
        fake.snapshot("").unwrap();
        fake.tick(SHARE_TTL);
        fake.fund(&addr(Chain::External, 3), 2_000, '5');
        assert_eq!(
            receive_and_balance(fake.snapshot("").unwrap()),
            (addr(Chain::External, 4), 6_000)
        );
        // Twenty unused past index 3, as a full scan would: through 23 and not 24.
        assert!(fake.asked_about(&addr(Chain::External, 23)));
        assert!(!fake.asked_about(&addr(Chain::External, 24)));
        // Plus the coin lists of the four, then five, funded addresses.
        assert_eq!(fake.requests(), 44 + 4 + 30 + 5);
    }

    // The documented trade-off of the incremental scan.
    #[test]
    fn a_new_coin_beyond_the_lookahead_waits_for_the_full_rescan() {
        let mut fake = three_used();
        fake.snapshot("").unwrap();
        fake.tick(SHARE_TTL);
        let far = addr(Chain::External, 2 + LOOKAHEAD as u32 + 1);
        fake.fund(&far, 2_000, '5');
        let before = fake.requests();
        assert_eq!(
            receive_and_balance(fake.snapshot("").unwrap()),
            (addr(Chain::External, 3), 4_000)
        );
        assert!(
            !fake.calls.lock().unwrap()[before..]
                .iter()
                .any(|url| url.contains(&far))
        );
        fake.tick(FULL_RESCAN);
        assert_eq!(
            receive_and_balance(fake.snapshot("").unwrap()),
            (addr(Chain::External, 9), 6_000)
        );
    }

    #[test]
    fn a_send_after_a_refresh_asks_only_for_the_coin_lists_and_asks_every_time() {
        let mut fake = three_used();
        fake.snapshot("").unwrap();
        let before = fake.requests();
        fake.send(SOMEONE, 2_000, 1).unwrap();
        let asked = fake.calls.lock().unwrap()[before..].to_vec();
        assert_eq!(asked.len(), 4);
        assert!(asked.iter().all(|url| url.ends_with("/utxo")), "{asked:?}");
        // Coins spent elsewhere since the refresh are not spent again from a cached list.
        fake.utxos.clear();
        assert_refused(
            fake.send(SOMEONE, 2_000, 1),
            "insufficient funds: have 0 confirmed, need 2141",
        );
        assert_eq!(fake.requests(), before + 8);
    }

    #[test]
    fn a_broadcast_that_went_out_or_is_unclear_forgets_the_scan_and_a_refusal_keeps_it() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 10_000, '1');
        let ready = fake.ready(SOMEONE, 5_000, 2);
        // A full scan, and the one funded address's coin list that every snapshot and payment asks.
        let (full, coins) = (41, 1);
        assert_eq!(fake.requests(), full + coins);

        assert!(fake.broadcast(&ready, answer(400, NODE_REFUSES)).is_err());
        fake.snapshot("").unwrap();
        assert_eq!(
            fake.requests(),
            full + 2 * coins,
            "a refusal posted nothing: the scan is shared, only the coin list is asked"
        );

        assert_eq!(
            fake.broadcast(&ready, answer(200, &ready.txid))
                .unwrap()
                .txid,
            ready.txid
        );
        fake.snapshot("").unwrap();
        assert_eq!(
            fake.requests(),
            2 * full + 3 * coins,
            "sent: the next refresh is a full scan"
        );

        match fake.broadcast(&ready, no_answer()) {
            Err(MobileSignerError::BroadcastUnknown(t)) => assert_eq!(t, ready.txid),
            other => panic!("expected BroadcastUnknown, got {other:?}"),
        }
        fake.snapshot("").unwrap();
        assert_eq!(
            fake.requests(),
            3 * full + 4 * coins,
            "unclear: the next refresh is a full scan"
        );
    }

    #[test]
    fn a_scan_that_fails_forgets_the_wallet_so_the_next_call_is_a_full_scan() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 1_000, '1');
        fake.snapshot("").unwrap();
        fake.tick(SHARE_TTL);
        fake.failing.insert(addr(Chain::External, 1));
        assert!(fake.snapshot("").is_err());
        fake.failing.clear();
        let before = fake.requests();
        assert_eq!(fake.snapshot("").unwrap().balance_sats, 1_000);
        assert_eq!(
            fake.requests(),
            before + 41 + 1,
            "a full scan and the one coin list"
        );
    }

    #[test]
    fn two_wallets_never_share_a_scan() {
        let fake = Fake::new();
        let other: Vec<String> =
            "legal winner thank year wave sausage worth useful legal winner thank yellow"
                .split(' ')
                .map(String::from)
                .collect();
        let first = fake.snapshot("").unwrap().receive_address;
        assert_eq!(fake.requests(), 40);
        let second = fake.snapshot("TREZOR").unwrap().receive_address;
        assert_eq!(fake.requests(), 80, "the same words under a passphrase");
        let third = fake.snapshot_of(&other, "").unwrap().receive_address;
        assert_eq!(fake.requests(), 120, "other words");
        assert_ne!(first, second);
        assert_ne!(first, third);
        assert_ne!(second, third);
        fake.snapshot("").unwrap();
        assert_eq!(fake.requests(), 120, "each wallet keeps its own entry");
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
        assert!(fake.send(mainnet, 1_000, 1).is_err());
        assert!(fake.send("tb1qnotanaddress", 1_000, 1).is_err());
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
        )
        .map_err(MobileSignerError::from);
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
        let fatal = GetError::Fatal("404".into());
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
        assert!(matches!(classify(no_answer()), Err(GetError::TimedOut(_))));
        // A read limit on a blocking socket, a stalled TLS handshake, reaches ureq's caller as `WouldBlock`.
        let blocked = std::io::Error::new(
            std::io::ErrorKind::WouldBlock,
            "Resource temporarily unavailable",
        );
        assert!(matches!(
            classify(Err(blocked.into())),
            Err(GetError::TimedOut(_))
        ));
        assert!(matches!(
            classify(Err(refused())),
            Err(GetError::Unreachable(_))
        ));
        assert!(matches!(
            classify(Err(reset())),
            Err(GetError::Unreachable(_))
        ));
        // A body cut short is the connection lost mid-answer, not an answer. A body that times out is
        // tested against a socket in `a_body_that_trickles_past_the_request_deadline_is_a_failure_that_forgets_the_host`.
        assert!(matches!(
            classify(cut_short(200)),
            Err(GetError::Unreachable(_))
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

    // What ureq hands over when the connection drops before the answer: no status, no body.
    fn reset() -> ureq::Error {
        std::io::Error::new(
            std::io::ErrorKind::ConnectionReset,
            "connection reset by peer",
        )
        .into()
    }

    fn refused() -> ureq::Error {
        std::io::Error::new(std::io::ErrorKind::ConnectionRefused, "connection refused").into()
    }

    // Item 75. The public test phrase showed no balance on an iPhone over Tailscale while the same
    // scan passed on a Mac. 150 used receive and 100 used change addresses make a 290 lookup scan
    // and two coin lists, about the 299 the Mac measured, and one of them loses its connection.
    #[test]
    fn one_lost_connection_among_a_large_wallets_requests_still_loads_it_on_the_same_host() {
        use std::sync::atomic::{AtomicUsize, Ordering::SeqCst};
        let losses = [
            ("a reset", lost as fn() -> Result<String, GetError>),
            ("a refused connection", || classify(Err(refused()))),
            ("a timeout", stalled),
            ("a body cut short", || classify(cut_short(200))),
        ];
        let account = Account::new(&abandon_about(), "", Network::Testnet4).unwrap();
        let at = |chain, index| account.key(chain, index).unwrap().address.to_string();
        let used: Vec<String> = (0..150)
            .map(|i| at(Chain::External, i))
            .chain((0..100).map(|i| at(Chain::Internal, i)))
            .collect();
        let mut failed = Vec::new();
        for (loss, lose) in losses {
            let mut fake = Fake::new();
            for address in &used {
                fake.touch(address);
            }
            fake.fund(&at(Chain::External, 149), 60_000, 'a');
            fake.fund(&at(Chain::Internal, 99), 40_000, 'b');
            let hosts = Mutex::new(Some(HostVerdict::Chosen(0, fake.now())));
            let attempts = AtomicUsize::new(0);
            let esplora = |url: &str| {
                get_forgetting(
                    &|| match attempts.fetch_add(1, SeqCst) {
                        200 => lose(),
                        _ => classify(answer(200, &fake.get(url).unwrap())),
                    },
                    &|_| {},
                    &hosts,
                    &fake.cache,
                )
            };
            let snapshot = match snapshot_from(
                &esplora,
                &abandon_about(),
                "",
                KeyNetwork::Test,
                fake.now(),
                &fake.cache,
            ) {
                Ok(snapshot) => snapshot,
                Err(e) => {
                    failed.push(format!(
                        "{loss} at request 200 failed the load after {} answers: {e}",
                        fake.requests()
                    ));
                    continue;
                }
            };
            assert_eq!(
                (
                    snapshot.balance_sats,
                    snapshot.spendable_sats,
                    snapshot.receive_address
                ),
                (100_000, 100_000, at(Chain::External, 150)),
                "{loss}"
            );
            assert_eq!(fake.requests(), 292, "{loss}");
            assert_eq!(
                attempts.load(SeqCst),
                293,
                "{loss}: the lost one is asked once more"
            );
            assert_eq!(
                chosen(&hosts),
                Some(0),
                "{loss}: the host that answered stays"
            );
            assert_eq!(
                fake.cache.lock().unwrap().entries.len(),
                1,
                "{loss}: the scan is kept for the next call"
            );
        }
        assert!(failed.is_empty(), "\n{}", failed.join("\n"));
    }

    fn lost() -> Result<String, GetError> {
        classify(Err(reset()))
    }

    fn stalled() -> Result<String, GetError> {
        classify(no_answer())
    }

    #[test]
    fn a_lost_connection_is_asked_again_after_one_then_two_seconds_and_a_third_loss_ends_it() {
        let (result, attempts, sleeps) = retried(vec![lost(), Ok("body".into())]);
        assert_eq!(result.unwrap(), "body");
        assert_eq!((attempts, sleeps), (2, secs(&[1])));
        let (result, attempts, sleeps) = retried(vec![lost(), lost(), lost()]);
        let MobileSignerError::InvalidPsbtJson(message) = result.unwrap_err() else {
            panic!("wrong error kind");
        };
        assert_eq!(
            message,
            "esplora get: Network Error: connection reset by peer, gave up after 3 attempts"
        );
        assert_eq!((attempts, sleeps), (3, secs(&[1, 2])));
    }

    #[test]
    fn a_get_that_timed_out_is_asked_at_most_twice() {
        let (result, attempts, sleeps) = retried(vec![stalled(), Ok("body".into())]);
        assert_eq!(result.unwrap(), "body");
        assert_eq!((attempts, sleeps), (2, secs(&[1])));
        for (case, script) in [
            ("two timeouts", vec![stalled(), stalled()]),
            ("a loss, then a timeout", vec![lost(), stalled()]),
            ("a timeout, then a loss", vec![stalled(), lost()]),
        ] {
            let (result, attempts, _) = retried(script);
            let MobileSignerError::InvalidPsbtJson(message) = result.unwrap_err() else {
                panic!("{case}: wrong error kind");
            };
            assert!(
                message.ends_with("gave up after 2 attempts"),
                "{case}: {message}"
            );
            assert_eq!(attempts, 2, "{case}");
        }
    }

    #[test]
    fn the_host_and_the_scans_are_forgotten_only_when_the_last_attempt_got_no_whole_answer() {
        let cases = [
            ("a loss, then a 200", vec![lost(), Ok("{}".into())], false),
            (
                "a timeout, then a 200",
                vec![stalled(), Ok("{}".into())],
                false,
            ),
            (
                "a loss, then a 404",
                vec![lost(), classify(answer(404, ""))],
                false,
            ),
            (
                "three throttles",
                vec![Err(throttled()), Err(throttled()), Err(throttled())],
                false,
            ),
            ("three losses", vec![lost(), lost(), lost()], true),
            ("two timeouts", vec![stalled(), stalled()], true),
        ];
        for (case, script, forgotten) in cases {
            let hosts = Mutex::new(Some(HostVerdict::Chosen(0, Instant::now())));
            let fake = Fake::new();
            fake.snapshot("").unwrap();
            let script = Mutex::new(VecDeque::from(script));
            let next = || {
                script
                    .lock()
                    .unwrap()
                    .pop_front()
                    .expect("one attempt too many")
            };
            let _ = get_forgetting(&next, &|_| {}, &hosts, &fake.cache);
            assert!(
                script.lock().unwrap().is_empty(),
                "{case}: every attempt was asked"
            );
            assert_eq!(chosen(&hosts).is_none(), forgotten, "{case}");
            assert_eq!(
                fake.cache.lock().unwrap().entries.is_empty(),
                forgotten,
                "{case}"
            );
        }
    }

    const HOSTS: [&str; 2] = ["https://first.example/api", "https://second.example/api"];
    const SPECS: [HostSpec; 2] = [
        HostSpec {
            base: HOSTS[0],
            probe_timeout: PROBE_TIMEOUT,
        },
        HostSpec {
            base: HOSTS[1],
            probe_timeout: PROBE_TIMEOUT,
        },
    ];
    const TESTNET3_GENESIS: &str =
        "000000000933ea01ad0ee984209779baaec3ced90fa3f408719526f8d77f4943";
    const MAINNET_GENESIS: &str =
        "000000000019d6689c085ae165831e9934ff763ae46a2a6c172b3f1b60a8ce26";

    /// A probe scripted per host, counting the hosts it was asked about.
    struct Probe {
        answers: HashMap<&'static str, Result<&'static str, &'static str>>,
        asked: Mutex<Vec<String>>,
    }

    impl Probe {
        fn new(answers: &[(&'static str, Result<&'static str, &'static str>)]) -> Self {
            Self {
                answers: answers.iter().copied().collect(),
                asked: Mutex::new(Vec::new()),
            }
        }

        fn run(&self, host: &str) -> Result<String, MobileSignerError> {
            self.asked.lock().unwrap().push(host.to_string());
            match self.answers.get(host).copied() {
                Some(Ok(body)) => Ok(body.to_string()),
                Some(Err(why)) => Err(MobileSignerError::InvalidPsbtJson(why.to_string())),
                None => panic!("unexpected probe of {host}"),
            }
        }

        fn asked(&self) -> Vec<String> {
            self.asked.lock().unwrap().clone()
        }

        fn pick(&self, now: Instant, cache: &HostChoice) -> Result<usize, MobileSignerError> {
            pick_host(&SPECS, &|host| self.run(host.base), now, cache)
        }
    }

    fn no_host_message(result: Result<usize, MobileSignerError>) -> String {
        match result {
            Err(MobileSignerError::InvalidPsbtJson(m)) => m,
            other => panic!("expected no host, got {other:?}"),
        }
    }

    fn chosen(cache: &HostChoice) -> Option<usize> {
        match &*cache.lock().unwrap() {
            Some(HostVerdict::Chosen(index, _)) => Some(*index),
            _ => None,
        }
    }

    /// `http://127.0.0.1:port` of a server that answers its first connection with a 200 whose body
    /// is the testnet4 genesis hash, sent one byte every `gap` for the first `slow` bytes.
    fn dripping_host(gap: Duration, slow: usize) -> String {
        use std::io::{Read, Write};
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        std::thread::spawn(move || {
            let (mut socket, _) = listener.accept().unwrap();
            let _ = socket.read(&mut [0u8; 1024]);
            let head = format!(
                "HTTP/1.1 200 OK\r\nContent-Length: {}\r\n\r\n",
                TESTNET4_GENESIS.len()
            );
            if socket.write_all(head.as_bytes()).is_err() {
                return;
            }
            for (i, byte) in TESTNET4_GENESIS.bytes().enumerate() {
                if i < slow {
                    std::thread::sleep(gap);
                }
                if socket
                    .write_all(&[byte])
                    .and_then(|()| socket.flush())
                    .is_err()
                {
                    return;
                }
            }
        });
        format!("http://127.0.0.1:{port}")
    }

    #[test]
    fn a_probe_of_a_host_that_trickles_its_genesis_hash_fails_inside_that_hosts_probe_deadline() {
        for probe_timeout in [OWN_NODE_PROBE_TIMEOUT, PROBE_TIMEOUT] {
            let host = HostSpec {
                base: Box::leak(dripping_host(Duration::from_secs(3), 10).into_boxed_str()),
                probe_timeout,
            };
            let cache = Mutex::new(None);
            let start = Instant::now();
            let result = pick_host(&[host], &probe_genesis, start, &cache);
            let took = start.elapsed();
            let message = no_host_message(result);
            assert!(message.contains("timed out"), "{message}");
            assert!(
                took < probe_timeout + Duration::from_secs(4),
                "the probe held the host lock for {took:?} against a limit of {probe_timeout:?}"
            );
        }
    }

    #[test]
    fn the_own_node_is_probed_first_with_three_seconds_and_a_timeout_there_moves_on_to_blockstream()
    {
        let asked = Mutex::new(Vec::new());
        let probe = |host: &HostSpec| {
            asked.lock().unwrap().push((host.base, host.probe_timeout));
            if host.base.starts_with("http://") {
                Err(MobileSignerError::InvalidPsbtJson(format!(
                    "timed out after {:?}",
                    host.probe_timeout
                )))
            } else {
                Ok(TESTNET4_GENESIS.to_string())
            }
        };
        let cache = Mutex::new(None);
        let hosts = hosts_with(Some(OWN_NODE_FIXTURE));
        let picked = pick_host(&hosts, &probe, Instant::now(), &cache).unwrap();
        assert_eq!(hosts[picked].base, "https://blockstream.info/testnet4/api");
        assert_eq!(
            *asked.lock().unwrap(),
            vec![
                (OWN_NODE_FIXTURE, Duration::from_secs(3)),
                (
                    "https://blockstream.info/testnet4/api",
                    Duration::from_secs(8)
                ),
            ],
            "a phone off the tailnet waits 3 s for the own node, then 8 s at most for blockstream"
        );
    }

    #[test]
    fn a_body_that_trickles_past_the_request_deadline_is_a_failure_that_forgets_the_host() {
        let whole = Duration::from_secs(2);
        let agent = agent(CONNECT_TIMEOUT, IO_TIMEOUT, whole);
        let host = dripping_host(Duration::from_secs(1), 10);
        let cache = Mutex::new(Some(HostVerdict::Chosen(0, Instant::now())));
        let fake = Fake::new();
        fake.snapshot("").unwrap();
        let start = Instant::now();
        let got = classify(agent.get(&format!("{host}/block-height/0")).call());
        let took = start.elapsed();
        forget_host_if_unreachable(&got, &cache, &fake.cache);
        assert!(matches!(got, Err(GetError::TimedOut(_))));
        assert!(took < whole + Duration::from_secs(3), "{took:?}");
        assert_eq!(chosen(&cache), None);
        assert!(fake.cache.lock().unwrap().entries.is_empty());
    }

    #[test]
    fn after_a_failed_round_ten_callers_inside_five_seconds_probe_nothing_and_the_next_round_probes_again()
     {
        let probe = Probe::new(&[(HOSTS[0], Err("timed out")), (HOSTS[1], Err("down"))]);
        let cache = Mutex::new(None);
        let t0 = Instant::now();
        let first = no_host_message(probe.pick(t0, &cache));
        assert_eq!(probe.asked().len(), 2);
        for caller in 1..=10 {
            let later = t0 + Duration::from_millis(400 * caller);
            assert_eq!(no_host_message(probe.pick(later, &cache)), first);
        }
        assert_eq!(probe.asked().len(), 2, "no probe inside the failure window");
        no_host_message(probe.pick(t0 + PROBE_FAILURE_TTL, &cache));
        assert_eq!(probe.asked().len(), 4, "one round after the window");
    }

    #[test]
    fn a_host_that_comes_back_after_the_failure_window_is_chosen_and_the_failure_is_forgotten() {
        let down = Probe::new(&[(HOSTS[0], Err("down")), (HOSTS[1], Err("down"))]);
        let cache = Mutex::new(None);
        let t0 = Instant::now();
        no_host_message(down.pick(t0, &cache));
        let up = Probe::new(&[(HOSTS[0], Ok(TESTNET4_GENESIS))]);
        let later = t0 + PROBE_FAILURE_TTL;
        assert_eq!(up.pick(later, &cache).unwrap(), 0);
        assert_eq!(*cache.lock().unwrap(), Some(HostVerdict::Chosen(0, later)));
        assert_eq!(up.pick(later + Duration::from_secs(1), &cache).unwrap(), 0);
        assert_eq!(up.asked().len(), 1, "the old failure plays no part");
    }

    #[test]
    fn a_scan_overtaken_by_a_broadcast_leaves_the_cache_empty_so_the_next_call_sees_the_payment() {
        use std::sync::atomic::{AtomicBool, Ordering::SeqCst};
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 50_000, 'a');
        assert_eq!(fake.snapshot("").unwrap().balance_sats, 50_000);
        assert_eq!(
            fake.cache.lock().unwrap().entries.len(),
            1,
            "an undisturbed scan is cached"
        );
        fake.tick(SHARE_TTL);
        let forgot = AtomicBool::new(false);
        let get = |url: &str| {
            if !forgot.swap(true, SeqCst) {
                forget_scans(&fake.cache); // what `broadcast_via` does once the payment is out
            }
            fake.get(url)
        };
        let stale = snapshot_from(
            &get,
            &abandon_about(),
            "",
            KeyNetwork::Test,
            fake.now(),
            &fake.cache,
        )
        .unwrap();
        assert_eq!(
            stale.balance_sats, 50_000,
            "the overtaken scan still answers its own caller"
        );
        assert!(fake.cache.lock().unwrap().entries.is_empty());
        // The chain after the payment: the coin spent, 40,000 change at internal 0.
        fake.touch(&addr(Chain::External, 0));
        fake.utxos.insert(addr(Chain::External, 0), "[]".into());
        fake.fund(&addr(Chain::Internal, 0), 40_000, 'b');
        fake.tick(Duration::from_secs(3));
        assert_eq!(fake.snapshot("").unwrap().balance_sats, 40_000);
        fake.send(&addr(Chain::External, 7), 10_000, 1).unwrap();
    }

    #[test]
    fn the_first_host_with_the_testnet4_genesis_is_chosen_and_the_second_is_not_probed() {
        let probe = Probe::new(&[
            (HOSTS[0], Ok(TESTNET4_GENESIS)),
            (HOSTS[1], Ok(TESTNET4_GENESIS)),
        ]);
        let cache = Mutex::new(None);
        let now = Instant::now();
        assert_eq!(probe.pick(now, &cache).unwrap(), 0);
        assert_eq!(probe.asked(), vec![HOSTS[0]]);
        assert_eq!(*cache.lock().unwrap(), Some(HostVerdict::Chosen(0, now)));
    }

    #[test]
    fn an_unreachable_first_host_gives_the_second() {
        let probe = Probe::new(&[
            (HOSTS[0], Err("timed out")),
            (HOSTS[1], Ok(TESTNET4_GENESIS)),
        ]);
        let cache = Mutex::new(None);
        assert_eq!(probe.pick(Instant::now(), &cache).unwrap(), 1);
        assert_eq!(probe.asked(), HOSTS);
    }

    #[test]
    fn a_first_host_on_another_chain_is_skipped_for_the_second() {
        let wrong = [
            TESTNET3_GENESIS,
            MAINNET_GENESIS,
            "",
            "<html><body>Sign in to Wi-Fi</body></html>",
            // Right hash, wrong framing: a JSON string is not the bare hash Esplora sends.
            r#""00000000da84f2bafbbc53dee25a72ae507ff4914b867c565be350b0da8bf043""#,
        ];
        for body in wrong {
            let probe = Probe::new(&[(HOSTS[0], Ok(body)), (HOSTS[1], Ok(TESTNET4_GENESIS))]);
            let cache = Mutex::new(None);
            assert_eq!(probe.pick(Instant::now(), &cache).unwrap(), 1, "{body:?}");
            assert_eq!(chosen(&cache), Some(1), "{body:?}");
        }
    }

    #[test]
    fn a_host_on_another_chain_is_never_used_even_when_it_is_the_only_one() {
        let probe = Probe::new(&[(HOSTS[0], Ok(TESTNET3_GENESIS))]);
        let cache = Mutex::new(None);
        let result = pick_host(&SPECS[..1], &|h| probe.run(h.base), Instant::now(), &cache);
        let message = no_host_message(result);
        assert!(
            message.starts_with("esplora get: no testnet4 host is reachable"),
            "{message}"
        );
        assert!(
            message.contains(HOSTS[0]) && message.contains("not testnet4"),
            "{message}"
        );
        assert_eq!(chosen(&cache), None);
    }

    #[test]
    fn no_host_passing_names_every_failure_and_chooses_no_host() {
        let probe = Probe::new(&[
            (HOSTS[0], Err("tls handshake hung")),
            (HOSTS[1], Ok(MAINNET_GENESIS)),
        ]);
        let cache = Mutex::new(None);
        let message = no_host_message(probe.pick(Instant::now(), &cache));
        assert!(
            message.starts_with("esplora get: no testnet4 host is reachable"),
            "{message}"
        );
        assert!(message.contains("tls handshake hung"), "{message}");
        assert!(message.contains(HOSTS[1]), "{message}");
        assert_eq!(chosen(&cache), None);
    }

    #[test]
    fn a_verified_host_is_reused_for_ten_minutes_then_probed_again() {
        let probe = Probe::new(&[(HOSTS[0], Ok(TESTNET4_GENESIS))]);
        let cache = Mutex::new(None);
        let t0 = Instant::now();
        assert_eq!(probe.pick(t0, &cache).unwrap(), 0);
        let under = t0 + HOST_TRUST - Duration::from_secs(1);
        assert_eq!(probe.pick(under, &cache).unwrap(), 0);
        assert_eq!(probe.asked().len(), 1, "no probe inside the trust window");
        let over = t0 + HOST_TRUST;
        assert_eq!(probe.pick(over, &cache).unwrap(), 0);
        assert_eq!(probe.asked().len(), 2, "one probe after the window");
        assert_eq!(*cache.lock().unwrap(), Some(HostVerdict::Chosen(0, over)));
    }

    #[test]
    fn a_cached_host_is_reused_without_probing_even_when_it_would_now_fail() {
        let probe = Probe::new(&[(HOSTS[1], Err("down"))]);
        let cache = Mutex::new(Some(HostVerdict::Chosen(1, Instant::now())));
        assert_eq!(probe.pick(Instant::now(), &cache).unwrap(), 1);
        assert!(probe.asked().is_empty());
    }

    #[test]
    fn a_transport_failure_forgets_the_host_and_the_scans_but_a_status_or_a_bad_body_does_not() {
        let cached = || Mutex::new(Some(HostVerdict::Chosen(0, Instant::now())));
        let scanned = || {
            let fake = Fake::new();
            fake.snapshot("").unwrap();
            fake
        };
        let scans_kept = |fake: &Fake| fake.cache.lock().unwrap().entries.len() == 1;

        let (cache, fake) = (cached(), scanned());
        forget_host_if_unreachable(&classify(no_answer()), &cache, &fake.cache);
        assert_eq!(chosen(&cache), None, "a timeout");
        assert!(!scans_kept(&fake), "a timeout");

        let (cache, fake) = (cached(), scanned());
        let refused = std::io::Error::new(std::io::ErrorKind::ConnectionRefused, "no");
        forget_host_if_unreachable(&classify(Err(refused.into())), &cache, &fake.cache);
        assert_eq!(chosen(&cache), None, "a refused connection");
        assert!(!scans_kept(&fake), "a refused connection");

        let (cache, fake) = (cached(), scanned());
        forget_host_if_unreachable(&classify(answer(500, "")), &cache, &fake.cache);
        assert_eq!(chosen(&cache), Some(0), "HTTP 500");
        assert!(scans_kept(&fake), "HTTP 500");

        let (cache, fake) = (cached(), scanned());
        forget_host_if_unreachable(&classify(answer(404, "")), &cache, &fake.cache);
        assert_eq!(chosen(&cache), Some(0), "HTTP 404");
        assert!(scans_kept(&fake), "HTTP 404");

        let (cache, fake) = (cached(), scanned());
        forget_host_if_unreachable(
            &classify(answer(200, "<html>Sign in</html>")),
            &cache,
            &fake.cache,
        );
        assert_eq!(chosen(&cache), Some(0), "a 200 with a bad body");
        assert!(scans_kept(&fake), "a 200 with a bad body");

        let (cache, fake) = (cached(), scanned());
        forget_host_if_unreachable(&classify(cut_short(200)), &cache, &fake.cache);
        assert_eq!(chosen(&cache), None, "a body cut short");
        assert!(!scans_kept(&fake), "a body cut short");
    }

    #[test]
    fn after_a_transport_failure_the_next_pick_probes_again() {
        let probe = Probe::new(&[
            (HOSTS[0], Err("timed out")),
            (HOSTS[1], Ok(TESTNET4_GENESIS)),
        ]);
        let cache = Mutex::new(Some(HostVerdict::Chosen(0, Instant::now())));
        forget_host_if_unreachable(&classify(no_answer()), &cache, &ScanCache::default());
        assert_eq!(probe.pick(Instant::now(), &cache).unwrap(), 1);
        assert_eq!(probe.asked(), HOSTS);
    }

    #[test]
    fn a_url_on_the_canonical_base_moves_to_the_chosen_host_and_others_stay() {
        let other = "https://mempool.space/testnet4/api";
        assert_eq!(
            on_host(&format!("{ESPLORA_TESTNET4}/address/tb1q/utxo"), other),
            format!("{other}/address/tb1q/utxo")
        );
        assert_eq!(
            on_host(
                &format!("{ESPLORA_TESTNET4}/blocks/tip/height"),
                ESPLORA_TESTNET4
            ),
            format!("{ESPLORA_TESTNET4}/blocks/tip/height")
        );
        // The base is matched as a prefix, not as a host: an unrelated URL is left alone.
        assert_eq!(
            on_host("https://example.org/api/tx", other),
            "https://example.org/api/tx"
        );
        assert_eq!(
            on_host("https://blockstream.info/testnet/api/tx", other),
            "https://blockstream.info/testnet/api/tx"
        );
    }

    // A private node, when the build has one, goes before blockstream.info since 2026-11-04;
    // blockstream.info was the only first host before.
    #[test]
    fn a_private_node_goes_before_the_public_hosts_which_are_blockstream_then_mempool_over_tls() {
        assert_eq!(ESPLORA_TESTNET4, "https://blockstream.info/testnet4/api");
        assert_eq!(*ESPLORA_TESTNET4_HOSTS, hosts_with(OWN_NODE));
        let public: Vec<&str> = hosts_with(None).iter().map(|h| h.base).collect();
        assert_eq!(
            public,
            [
                "https://blockstream.info/testnet4/api",
                "https://mempool.space/testnet4/api",
            ]
        );
        let hosts = hosts_with(Some(OWN_NODE_FIXTURE));
        assert_eq!(hosts[0].base, OWN_NODE_FIXTURE);
        let seconds: Vec<u64> = hosts.iter().map(|h| h.probe_timeout.as_secs()).collect();
        assert_eq!(seconds, [3, 8, 8]);
        let distinct: HashSet<_> = hosts.iter().map(|h| h.base).collect();
        assert_eq!(distinct.len(), hosts.len());
        for host in &hosts {
            assert!(host.base.ends_with("/api"), "{}", host.base);
        }
        // Cleartext is acceptable only for the private host, which Tailscale already encrypts.
        for host in &hosts[1..] {
            assert!(host.base.starts_with("https://"), "{}", host.base);
        }
    }

    #[test]
    fn a_broadcast_posts_nothing_when_no_host_passes_and_posts_to_the_chosen_one_otherwise() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 10_000, '1');
        let good = fake.ready(SOMEONE, 5_000, 2);
        let posted_to = Mutex::new(Vec::new());
        let post = |base: &str, _: &str| {
            posted_to.lock().unwrap().push(base.to_string());
            answer(200, &good.txid)
        };
        let lookup = |_: &str, _: &str| not_found();
        let no_host = Err(MobileSignerError::InvalidPsbtJson(
            "esplora get: no testnet4 host is reachable (both down)".into(),
        ));
        let result = broadcast_via(
            no_host,
            &post,
            &lookup,
            &|| {},
            &good.hex,
            &good.txid,
            &fake.cache,
        );
        match result {
            Err(MobileSignerError::InvalidPsbtJson(m)) => {
                assert!(m.starts_with("esplora get: no testnet4 host"), "{m}")
            }
            other => panic!("expected the no-host error, got {other:?}"),
        }
        assert!(posted_to.lock().unwrap().is_empty());

        let result = broadcast_via(
            Ok(HOSTS[1]),
            &post,
            &lookup,
            &|| {},
            &good.hex,
            &good.txid,
            &fake.cache,
        );
        assert_eq!(result.unwrap().txid, good.txid);
        assert_eq!(*posted_to.lock().unwrap(), vec![HOSTS[1]]);
    }

    /// A fee host scripted per endpoint, counting requests, with its own clock and fee cache.
    struct FeeHost {
        recommended: Mutex<Result<String, String>>,
        estimates: Result<String, String>,
        calls: Mutex<usize>,
        now: Mutex<Instant>,
        cache: FeeCache,
    }

    impl FeeHost {
        fn new(recommended: Result<&str, &str>, estimates: Result<&str, &str>) -> Self {
            let owned = |r: Result<&str, &str>| r.map(str::to_string).map_err(str::to_string);
            Self {
                recommended: Mutex::new(owned(recommended)),
                estimates: owned(estimates),
                calls: Mutex::new(0),
                now: Mutex::new(Instant::now()),
                cache: Mutex::new(None),
            }
        }

        fn get(&self, url: &str) -> Result<String, MobileSignerError> {
            *self.calls.lock().unwrap() += 1;
            let path = url.strip_prefix(ESPLORA_TESTNET4).expect("a testnet4 URL");
            let answer = match path {
                "/v1/fees/recommended" => self.recommended.lock().unwrap().clone(),
                "/fee-estimates" => self.estimates.clone(),
                other => panic!("unexpected fee URL {other}"),
            };
            answer.map_err(MobileSignerError::InvalidPsbtJson)
        }

        fn tiers(&self) -> FeeTiers {
            fee_tiers_from(
                &|url| self.get(url),
                KeyNetwork::Test,
                *self.now.lock().unwrap(),
                &self.cache,
            )
        }

        fn tick(&self, by: Duration) {
            *self.now.lock().unwrap() += by;
        }

        fn calls(&self) -> usize {
            *self.calls.lock().unwrap()
        }
    }

    fn live(slow: u64, normal: u64, fast: u64) -> FeeTiers {
        FeeTiers {
            slow_sat_vb: slow,
            normal_sat_vb: normal,
            fast_sat_vb: fast,
            live: true,
        }
    }

    const NOT_FOUND: Result<&str, &str> = Err("esplora get: HTTP status 404");
    const RECOMMENDED: &str =
        r#"{"fastestFee":15,"halfHourFee":8,"hourFee":4,"economyFee":2,"minimumFee":1}"#;

    #[test]
    fn mempool_recommended_fees_give_the_three_tiers_in_one_request() {
        let host = FeeHost::new(Ok(RECOMMENDED), NOT_FOUND);
        assert_eq!(host.tiers(), live(4, 8, 15));
        assert_eq!(host.calls(), 1);
    }

    #[test]
    fn without_recommended_fees_esplora_estimates_at_targets_six_three_and_one_are_used() {
        let estimates = r#"{"1":15.0,"2":12.0,"3":8.0,"4":6.0,"6":4.0,"144":1.0,"1008":1.0}"#;
        let host = FeeHost::new(NOT_FOUND, Ok(estimates));
        assert_eq!(host.tiers(), live(4, 8, 15));
        assert_eq!(host.calls(), 2, "the 404 then the estimates");
    }

    #[test]
    fn a_missing_target_takes_the_nearest_present_one_and_the_faster_on_a_tie() {
        // 3 sits between 2 and 4, so normal takes the faster target 2; 6 is nearest 4.
        let host = FeeHost::new(NOT_FOUND, Ok(r#"{"2":10,"4":6,"25":1}"#));
        assert_eq!(host.tiers(), live(6, 10, 10));
        let host = FeeHost::new(NOT_FOUND, Ok(r#"{"5":2.0}"#));
        assert_eq!(host.tiers(), live(2, 2, 2), "one target serves every tier");
    }

    #[test]
    fn fractional_rates_round_up_to_a_whole_sat_per_vb_and_never_below_one() {
        let host = FeeHost::new(
            Ok(r#"{"fastestFee":1.2,"halfHourFee":0.5,"hourFee":0.1}"#),
            NOT_FOUND,
        );
        assert_eq!(host.tiers(), live(1, 1, 2));
        let host = FeeHost::new(NOT_FOUND, Ok(r#"{"1":2.000001,"3":1.0,"6":0.9}"#));
        assert_eq!(host.tiers(), live(1, 1, 3));
    }

    #[test]
    fn rates_above_the_cores_limit_are_capped_at_2000() {
        let host = FeeHost::new(
            Ok(r#"{"fastestFee":5000,"halfHourFee":2000.5,"hourFee":100}"#),
            NOT_FOUND,
        );
        assert_eq!(host.tiers(), live(100, 2000, 2000));
        let host = FeeHost::new(NOT_FOUND, Ok(r#"{"1":1e30,"3":2000,"6":1999.1}"#));
        assert_eq!(host.tiers(), live(2000, 2000, 2000));
    }

    #[test]
    fn zero_negative_nan_string_and_missing_rates_are_no_estimate() {
        // Nothing usable on either endpoint: the fallback.
        let host = FeeHost::new(
            Ok(r#"{"fastestFee":"15","halfHourFee":-8,"hourFee":0}"#),
            Ok(r#"{"1":"x","3":null,"6":-1,"12":0,"a":5,"":7}"#),
        );
        assert_eq!(host.tiers(), DEFAULT_FEE_TIERS);
        assert_eq!(host.calls(), 2);
        for body in [
            "NaN",
            r#"{"1":NaN}"#,
            r#"{"1":1e400}"#,
            "[1,2,3]",
            "",
            "<html>",
        ] {
            let host = FeeHost::new(Ok(body), Ok(body));
            assert_eq!(host.tiers(), DEFAULT_FEE_TIERS, "{body:?}");
        }
        // The usable fields still serve every tier.
        let host = FeeHost::new(
            Ok(r#"{"fastestFee":15,"halfHourFee":"8","hourFee":0}"#),
            NOT_FOUND,
        );
        assert_eq!(host.tiers(), live(15, 15, 15));
        let host = FeeHost::new(Ok(r#"{"hourFee":4}"#), NOT_FOUND);
        assert_eq!(host.tiers(), live(4, 4, 4));
    }

    #[test]
    fn estimates_out_of_order_are_raised_so_slow_normal_fast_keep_their_order() {
        let host = FeeHost::new(NOT_FOUND, Ok(r#"{"1":5,"3":8,"6":3}"#));
        assert_eq!(host.tiers(), live(3, 8, 8));
        let host = FeeHost::new(
            Ok(r#"{"fastestFee":1,"halfHourFee":4,"hourFee":9}"#),
            NOT_FOUND,
        );
        assert_eq!(host.tiers(), live(9, 9, 9));
    }

    #[test]
    fn both_endpoints_failing_gives_the_defaults_and_mainnet_asks_nothing() {
        let host = FeeHost::new(NOT_FOUND, Err("esplora get: HTTP status 500"));
        assert_eq!(host.tiers(), DEFAULT_FEE_TIERS);
        assert_eq!(DEFAULT_FEE_TIERS, {
            FeeTiers {
                slow_sat_vb: 4,
                normal_sat_vb: 8,
                fast_sat_vb: 15,
                live: false,
            }
        });
        assert_eq!(host.calls(), 2);
        let host = FeeHost::new(Ok(RECOMMENDED), NOT_FOUND);
        let main = fee_tiers_from(
            &|url| host.get(url),
            KeyNetwork::Main,
            *host.now.lock().unwrap(),
            &host.cache,
        );
        assert_eq!(main, DEFAULT_FEE_TIERS);
        assert_eq!(host.calls(), 0);
    }

    #[test]
    fn live_tiers_are_answered_from_memory_for_five_minutes_then_fetched_once_more() {
        let host = FeeHost::new(Ok(RECOMMENDED), NOT_FOUND);
        assert_eq!(host.tiers(), live(4, 8, 15));
        *host.recommended.lock().unwrap() =
            Ok(r#"{"fastestFee":30,"halfHourFee":20,"hourFee":10}"#.into());
        host.tick(Duration::from_secs(5 * 60 - 1));
        assert_eq!(host.tiers(), live(4, 8, 15));
        assert_eq!(host.calls(), 1, "no request inside the window");
        host.tick(Duration::from_secs(1));
        assert_eq!(host.tiers(), live(10, 20, 30));
        assert_eq!(host.calls(), 2, "one request after the window");
        assert_eq!(host.tiers(), live(10, 20, 30));
        assert_eq!(host.calls(), 2);
    }

    #[test]
    fn the_fallback_is_retried_after_thirty_seconds() {
        let host = FeeHost::new(NOT_FOUND, NOT_FOUND);
        assert_eq!(host.tiers(), DEFAULT_FEE_TIERS);
        *host.recommended.lock().unwrap() = Ok(RECOMMENDED.into());
        host.tick(Duration::from_secs(29));
        assert_eq!(host.tiers(), DEFAULT_FEE_TIERS);
        assert_eq!(host.calls(), 2, "the fallback answers inside its window");
        host.tick(Duration::from_secs(1));
        assert_eq!(host.tiers(), live(4, 8, 15));
        assert_eq!(host.calls(), 3);
    }

    #[test]
    fn a_transport_failure_while_fetching_fees_forgets_the_host_and_the_scans_and_still_answers() {
        let hosts = Mutex::new(Some(HostVerdict::Chosen(0, Instant::now())));
        let fake = Fake::new();
        fake.snapshot("").unwrap();
        let get = |_: &str| get_forgetting(&|| classify(no_answer()), &|_| {}, &hosts, &fake.cache);
        let tiers = fee_tiers_from(&get, KeyNetwork::Test, Instant::now(), &Mutex::new(None));
        assert_eq!(tiers, DEFAULT_FEE_TIERS);
        assert_eq!(chosen(&hosts), None);
        assert!(fake.cache.lock().unwrap().entries.is_empty());
    }

    #[test]
    #[ignore = "needs the network and a testnet4 host, the own node over Tailscale first"]
    fn real_fee_tiers_of_the_host_in_use() {
        let start = Instant::now();
        let tiers = hot_fee_tiers(KeyNetwork::Test);
        println!("fee tiers {tiers:?} in {:?}", start.elapsed());
        assert!(tiers.slow_sat_vb <= tiers.normal_sat_vb);
        assert!(tiers.normal_sat_vb <= tiers.fast_sat_vb);
        assert!((1..=MAX_FEE_RATE).contains(&tiers.fast_sat_vb));
    }

    #[test]
    #[ignore = "needs the network and a testnet4 host, the own node over Tailscale first"]
    fn real_scan_of_the_public_test_phrase() {
        let requests = std::sync::atomic::AtomicUsize::new(0);
        let counted = |url: &str| {
            requests.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
            http_get(url)
        };
        let start = std::time::Instant::now();
        let wallet = scan_wallet(
            &counted,
            &abandon_about(),
            "",
            KeyNetwork::Test,
            Instant::now(),
            &ScanCache::default(),
        )
        .unwrap();
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

    // Open item 75. The test above scans the 12-word phrase, not the phone's wallet, so it passed on a
    // Mac while this phrase failed on the phone and on the Mac.
    #[test]
    #[ignore = "needs the network and a testnet4 host, the own node over Tailscale first"]
    fn real_snapshot_of_the_24_word_public_test_phrase_the_phone_imported() {
        let mut words = vec!["abandon".to_string(); 23];
        words.push("art".into());
        // Receive index 0, derived outside this crate (stdlib PBKDF2 + BIP-32 + bech32).
        assert_eq!(
            hot_receive_address(words.clone(), String::new(), KeyNetwork::Test, 0).unwrap(),
            "tb1qtk89me2ae95dmlp3yfl4q9ynpux8mxjujuf2fr"
        );
        let requests = std::sync::atomic::AtomicUsize::new(0);
        let counted = |url: &str| {
            requests.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
            http_get(url)
        };
        let start = std::time::Instant::now();
        let snapshot = snapshot_from(
            &counted,
            &words,
            "",
            KeyNetwork::Test,
            Instant::now(),
            &ScanCache::default(),
        )
        .unwrap();
        println!(
            "snapshot took {:?} for {} requests: {snapshot:?}",
            start.elapsed(),
            requests.load(std::sync::atomic::Ordering::SeqCst)
        );
    }

    // V2 of the 2026-11-05 interrogate. Coins were taken in the host's listing order, so dust listed
    // first inflated the fee and the host decided which coins paid.
    #[test]
    fn the_same_payment_spends_the_same_coins_and_pays_the_same_fee_whatever_order_the_host_lists_them_in()
     {
        let big = coin('9', 0, 1_000_000, true);
        let dust: Vec<String> = (0..100).map(|v| coin('1', v, 2_000, true)).collect();
        let mut seen = Vec::new();
        for big_first in [true, false] {
            let mut fake = Fake::new();
            fake.stats
                .insert(addr(Chain::External, 0), used_stats(1_200_000));
            let mut list = dust.clone();
            if big_first {
                list.insert(0, big.clone());
            } else {
                list.push(big.clone());
            }
            fake.utxos
                .insert(addr(Chain::External, 0), format!("[{}]", list.join(",")));
            let (tx, fee) = fake.send_at(SOMEONE, 150_000, 1).unwrap();
            assert_eq!(
                (tx.input.len(), fee),
                (1, 141),
                "big_first {big_first}: the one coin that covers the payment pays it"
            );
            assert_eq!(
                tx.input[0].previous_output.txid.to_string(),
                id('9'),
                "big_first {big_first}"
            );
            seen.push(serialize_hex(&tx));
        }
        assert_eq!(seen[0], seen[1], "the same transaction either way");
        // Equal values: the outpoint breaks the tie, so two listings of the same coins sign the same transaction.
        let mut tied = Vec::new();
        for reversed in [false, true] {
            let mut fake = Fake::new();
            fake.stats
                .insert(addr(Chain::External, 0), used_stats(10_000));
            let mut list = [
                coin('a', 1, 5_000, true),
                coin('a', 0, 5_000, true),
                coin('b', 0, 5_000, true),
            ];
            if reversed {
                list.reverse();
            }
            fake.utxos
                .insert(addr(Chain::External, 0), format!("[{}]", list.join(",")));
            tied.push(serialize_hex(&fake.send(SOMEONE, 6_000, 1).unwrap()));
        }
        assert_eq!(tied[0], tied[1], "the same transaction either way");
        // A thousand dust coins on the first address and 1 BTC on the second: the dust waits behind the big coin.
        let mut dusted = Fake::new();
        dusted
            .stats
            .insert(addr(Chain::External, 0), used_stats(546_000));
        let coins: Vec<String> = (0..1_000).map(|v| coin('d', v, 546, true)).collect();
        dusted
            .utxos
            .insert(addr(Chain::External, 0), format!("[{}]", coins.join(",")));
        dusted.fund(&addr(Chain::External, 1), 100_000_000, 'b');
        let (tx, fee) = dusted.send_at(SOMEONE, 50_000_000, 15).unwrap();
        assert_eq!((tx.input.len(), fee), (1, 15 * 141));
        assert_eq!(fee_paid(&dusted, &tx), fee);
    }

    // V3. Every unconfirmed coin on a change address was spent as own change, with no look at the
    // transaction that paid it. A change address is public once its first payment confirms.
    #[test]
    fn an_unconfirmed_coin_a_stranger_paid_to_a_change_address_is_neither_spent_nor_spendable() {
        let mut fake = Fake::new();
        fake.touch(&addr(Chain::External, 0));
        let change = addr(Chain::Internal, 0);
        fake.stats.insert(
            change.clone(),
            r#"{"chain_stats":{"funded_txo_sum":50000,"spent_txo_sum":0,"tx_count":1},"mempool_stats":{"funded_txo_sum":1000,"spent_txo_sum":0,"tx_count":1}}"#.into(),
        );
        fake.utxos.insert(
            change,
            format!(
                "[{},{}]",
                coin('c', 1, 50_000, true),
                coin('e', 0, 1_000, false)
            ),
        );
        fake.fund(&addr(Chain::External, 1), 100_000, 'a');
        let snapshot = fake.snapshot("").unwrap();
        assert_eq!(
            (snapshot.balance_sats, snapshot.spendable_sats),
            (151_000, 150_000),
            "the balance counts the stranger's coin, the spendable figure does not"
        );
        let (tx, fee) = fake.send_all_at(SOMEONE, 1).unwrap();
        let inputs: Vec<String> = tx
            .input
            .iter()
            .map(|i| i.previous_output.txid.to_string())
            .collect();
        assert!(
            !inputs.contains(&id('e')),
            "send all spent the stranger's coin: {inputs:?}"
        );
        assert_eq!(tx.input.len(), 2);
        assert_outputs(&tx, &[(SOMEONE, 150_000 - fee)]);
        // The ordinary path with nothing else: the stranger's coin is not funds.
        let mut only = Fake::new();
        only.fund_unconfirmed(&addr(Chain::Internal, 0), 5_000, 'e');
        assert_refused(
            only.send(SOMEONE, 1_000, 1),
            "insufficient funds: have 0 confirmed, need 1141",
        );
        assert_eq!(only.snapshot("").unwrap().spendable_sats, 0);
    }

    // V3. Own change is a coin whose transaction spends one of this wallet's coins. It is spent
    // unconfirmed as before, after one lookup of that transaction per call, however many of its
    // outputs are change.
    #[test]
    fn own_unconfirmed_change_is_spent_after_one_lookup_of_its_transaction_per_call() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 5_000, '1');
        fake.fund_change(&addr(Chain::Internal, 0), 3_000, '2');
        fake.utxos.insert(
            addr(Chain::Internal, 0),
            format!(
                "[{},{}]",
                coin('2', 0, 3_000, false),
                coin('2', 1, 2_000, false)
            ),
        );
        let before = fake.requests();
        let (tx, _) = fake.send_all_at(SOMEONE, 1).unwrap();
        assert_eq!(tx.input.len(), 3, "both change coins and the receive");
        let looked_up: Vec<String> = fake.calls.lock().unwrap()[before..]
            .iter()
            .filter(|url| url.contains("/tx/"))
            .cloned()
            .collect();
        assert_eq!(
            looked_up,
            vec![format!("{ESPLORA_TESTNET4}/tx/{}", id('2'))],
            "one lookup for the one transaction"
        );
        assert_eq!(fake.snapshot("").unwrap().spendable_sats, 10_000);
    }

    // V3. A lookup the explorer answers with something that is not that transaction fails the call,
    // as every other Esplora answer does. Defaulting would spend or hide a coin on a guess.
    #[test]
    fn a_change_transaction_the_explorer_cannot_show_fails_the_whole_call() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 5_000, '1');
        fake.fund_change(&addr(Chain::Internal, 0), 3_000, '2');
        let wrong_id = format!(r#"{{"txid":"{}","vin":[]}}"#, id('3'));
        let no_vin = format!(r#"{{"txid":"{}","vin":"none"}}"#, id('2'));
        for body in ["not json", wrong_id.as_str(), no_vin.as_str()] {
            fake.parents.insert(id('2'), body.into());
            for (what, result) in [
                ("send all", fake.send_all_at(SOMEONE, 1).map(|_| ())),
                ("payment", fake.send(SOMEONE, 1_000, 1).map(|_| ())),
                ("snapshot", fake.snapshot("").map(|_| ())),
            ] {
                match result {
                    Err(MobileSignerError::InvalidPsbtJson(m)) => {
                        assert!(m.starts_with("esplora utxo:"), "{what} with {body}: {m}")
                    }
                    other => panic!("{what} with {body}: expected a refusal, got {other:?}"),
                }
            }
        }
    }

    // V4. The snapshot's spendable figure is what a payment could spend: confirmed receives and own
    // change, not a receive still in the mempool. Send all pays exactly it less the fee.
    #[test]
    fn the_snapshot_spendable_balance_is_exactly_what_send_all_pays_plus_its_fee() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 1_000_000, '1');
        fake.fund_unconfirmed(&addr(Chain::External, 1), 9_000_000, '2');
        fake.fund_change(&addr(Chain::Internal, 0), 20_000, '3');
        let snapshot = fake.snapshot("").unwrap();
        assert_eq!(snapshot.balance_sats, 10_020_000);
        assert_eq!(snapshot.spendable_sats, 1_020_000);
        let (tx, fee) = fake.send_all_at(SOMEONE, 8).unwrap();
        assert_eq!(tx.output[0].value.to_sat() + fee, snapshot.spendable_sats);
        assert_eq!(fee_paid(&fake, &tx), fee);
    }

    // V4, open item 75. The coin lists cost one request per address, so only the used addresses that
    // hold something are asked, never the emptied ones an old wallet has by the dozen.
    #[test]
    fn a_snapshot_asks_for_the_coins_of_the_funded_addresses_only() {
        let mut fake = three_used();
        for i in 3..50 {
            fake.touch(&addr(Chain::External, i));
        }
        assert_eq!(fake.snapshot("").unwrap().spendable_sats, 4_000);
        let coin_lists = fake
            .calls
            .lock()
            .unwrap()
            .iter()
            .filter(|url| url.ends_with("/utxo"))
            .count();
        assert_eq!(
            coin_lists, 4,
            "one per funded address, none for the 47 emptied ones"
        );
    }

    // V5. Every output was sized as P2WPKH (31 vB). A taproot or P2WSH output is 43 vB, P2SH 32 and
    // P2PKH 34, so those payments paid under the rate asked for, below the relay floor at 1 sat/vB.
    #[test]
    fn the_fee_is_never_under_the_rate_asked_for_whatever_the_destination_script_on_both_paths() {
        let key = own(Chain::External, 0);
        let secp = Secp256k1::new();
        let redeem = key.address.script_pubkey();
        let destinations = [
            ("P2WPKH", SOMEONE.to_string()),
            (
                "P2TR",
                Address::p2tr(&secp, key.pubkey.0.into(), None, Network::Testnet4).to_string(),
            ),
            (
                "P2WSH",
                Address::p2wsh(&redeem, Network::Testnet4).to_string(),
            ),
            (
                "P2SH",
                Address::p2sh(&redeem, Network::Testnet4)
                    .unwrap()
                    .to_string(),
            ),
            (
                "P2PKH",
                Address::p2pkh(key.pubkey.pubkey_hash(), Network::Testnet4).to_string(),
            ),
        ];
        for (kind, to) in &destinations {
            for rate in [1u64, 7] {
                let mut fake = Fake::new();
                fake.fund(&addr(Chain::External, 0), 100_000, '1');
                fake.fund(&addr(Chain::External, 1), 100_000, '2');
                let (all, fee) = fake.send_all_at(to, rate).unwrap();
                assert!(
                    fee >= rate * all.vsize() as u64,
                    "{kind} send all at {rate} sat/vB: fee {fee} for {} vB",
                    all.vsize()
                );
                assert_eq!(fee_paid(&fake, &all), fee, "{kind} send all at {rate}");
                let (tx, fee) = fake.send_at(to, 150_000, rate).unwrap();
                assert!(
                    fee >= rate * tx.vsize() as u64,
                    "{kind} payment at {rate} sat/vB: fee {fee} for {} vB",
                    tx.vsize()
                );
                assert_eq!(fee_paid(&fake, &tx), fee, "{kind} payment at {rate}");
            }
        }
    }

    /// `tx_json` plus what only the detail reads, a sequence on each input, the size and the weight.
    fn full_tx(digit: char, vin: &[String], vout: &[String], status: &str) -> String {
        let mut tx: Value = serde_json::from_str(&tx_json(digit, vin, vout, status)).unwrap();
        for input in tx["vin"].as_array_mut().unwrap() {
            input["sequence"] = 0xffff_fffd_u32.into();
        }
        // A 1-in 2-out P2WPKH payment is 222 bytes and 561 weight units, so 141 vB.
        tx["size"] = 222.into();
        tx["weight"] = 561.into();
        tx.to_string()
    }

    fn detail(fake: &Fake, txid: &str) -> Result<Option<HotTxDetail>, MobileSignerError> {
        detail_of(
            &|url| fake.get(url),
            &abandon_about(),
            "",
            KeyNetwork::Test,
            txid,
        )
    }

    fn part(address: Option<&str>, value_sats: u64, owner: TxoOwner) -> HotTxo {
        HotTxo {
            address: address.map(str::to_string),
            value_sats,
            owner,
        }
    }

    #[test]
    fn a_detail_asks_for_the_transaction_then_the_tip_and_never_scans() {
        let ext0 = addr(Chain::External, 0);
        let mut fake = Fake::new();
        fake.parents.insert(
            id('1'),
            full_tx(
                '1',
                &[spent(&ext0, 10_000)],
                &[txo(SOMEONE, 9_000)],
                &at(100),
            ),
        );
        assert!(detail(&fake, &id('1')).unwrap().is_some());
        assert_eq!(
            *fake.calls.lock().unwrap(),
            [
                format!("{ESPLORA_TESTNET4}/tx/{}", id('1')),
                format!("{ESPLORA_TESTNET4}/blocks/tip/height"),
            ]
        );
    }

    // 10000 at 0/0 is spent, 6000 to someone, 3000 back as change to 1/0 and 1000 in fee.
    #[test]
    fn a_send_tags_its_outputs_as_destination_and_change_and_pays_inputs_minus_outputs() {
        let (ext0, int0) = (addr(Chain::External, 0), addr(Chain::Internal, 0));
        let mut fake = Fake::new();
        fake.parents.insert(
            id('1'),
            full_tx(
                '1',
                &[spent(&ext0, 10_000)],
                &[txo(SOMEONE, 6_000), txo(&int0, 3_000)],
                &at(100),
            ),
        );
        assert_eq!(
            detail(&fake, &id('1')).unwrap(),
            Some(HotTxDetail {
                tx: hot(&id('1'), -7_000, 6, Some(1_790_000_000)),
                block_height: Some(100),
                inputs: vec![part(Some(&ext0), 10_000, TxoOwner::Receive)],
                outputs: vec![
                    part(Some(SOMEONE), 6_000, TxoOwner::Other),
                    part(Some(&int0), 3_000, TxoOwner::Change),
                ],
                fee_sats: 1_000,
                size_bytes: 222,
                vsize: 141,
                signals_rbf: true,
            })
        );
    }

    // Someone pays 10000 to 0/0 out of 20000 and keeps 9000 as their own change. Still in the mempool.
    #[test]
    fn a_receive_tags_the_wallets_output_and_leaves_the_payers_untagged() {
        let ext0 = addr(Chain::External, 0);
        let mut fake = Fake::new();
        fake.parents.insert(
            id('2'),
            full_tx(
                '2',
                &[spent(SOMEONE, 20_000)],
                &[txo(&ext0, 10_000), txo(SOMEONE, 9_000)],
                MEMPOOL,
            ),
        );
        let found = detail(&fake, &id('2')).unwrap().unwrap();
        assert_eq!(found.tx, hot(&id('2'), 10_000, 0, None));
        assert_eq!(found.block_height, None);
        assert_eq!(found.inputs, [part(Some(SOMEONE), 20_000, TxoOwner::Other)]);
        assert_eq!(
            found.outputs,
            [
                part(Some(&ext0), 10_000, TxoOwner::Receive),
                part(Some(SOMEONE), 9_000, TxoOwner::Other),
            ]
        );
        assert_eq!(found.fee_sats, 1_000);
    }

    // Written against the constant, so a new cap keeps it true. The gap limit plays no part.
    #[test]
    fn an_address_below_the_scan_cap_is_the_wallets_even_past_the_gap() {
        let last = MAX_SCAN_PER_CHAIN as u32 - 1;
        let outputs = [
            txo(&addr(Chain::External, last), 1_000),
            txo(&addr(Chain::Internal, last), 2_000),
            txo(&addr(Chain::External, last + 1), 3_000),
        ];
        let mut fake = Fake::new();
        fake.parents.insert(
            id('3'),
            full_tx('3', &[spent(SOMEONE, 7_000)], &outputs, MEMPOOL),
        );
        let owners: Vec<TxoOwner> = detail(&fake, &id('3'))
            .unwrap()
            .unwrap()
            .outputs
            .iter()
            .map(|o| o.owner)
            .collect();
        assert_eq!(
            owners,
            [TxoOwner::Receive, TxoOwner::Change, TxoOwner::Other]
        );
    }

    /// `http://127.0.0.1:port` of a server that answers every request the way blockstream.info answers `/tx/{txid}` for a
    /// txid it never saw.
    fn not_found_host() -> String {
        use std::io::{Read, Write};
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        std::thread::spawn(move || {
            for mut socket in listener.incoming().flatten() {
                let _ = socket.read(&mut [0u8; 1024]);
                let _ = socket.write_all(
                    b"HTTP/1.1 404 Not Found\r\nContent-Length: 21\r\nConnection: close\r\n\r\nTransaction not found",
                );
            }
        });
        format!("http://127.0.0.1:{port}")
    }

    #[test]
    fn an_unknown_txid_is_none_after_one_request() {
        let host = not_found_host();
        let calls = Mutex::new(0);
        // http_get past the host choice, with the real request, classify, the retry and the forget.
        let esplora = |url: &str| {
            *calls.lock().unwrap() += 1;
            let url = on_host(url, &host);
            get_forgetting(
                &|| classify(AGENT.get(&url).call()),
                &|_| {},
                &Mutex::new(None),
                &ScanCache::default(),
            )
        };
        let found = detail_of(&esplora, &abandon_about(), "", KeyNetwork::Test, &id('1'));
        assert!(matches!(found, Ok(None)), "{found:?}");
        assert_eq!(*calls.lock().unwrap(), 1);
    }

    #[test]
    fn a_404_for_the_transaction_is_told_apart_from_every_other_failure() {
        let txid = Txid::from_str(&id('1')).unwrap();
        let host = not_found_host();
        let failed = |answer: Answer| MobileSignerError::from(classify(answer).unwrap_err());
        let asked = |path: String| failed(AGENT.get(&format!("{host}{path}")).call());
        assert!(is_not_found(&asked(format!("/tx/{txid}")), &txid));
        assert!(
            !is_not_found(&asked(format!("/tx/{}", id('2'))), &txid),
            "another txid"
        );
        assert!(
            !is_not_found(&asked("/blocks/tip/height".into()), &txid),
            "the tip"
        );
        assert!(!is_not_found(&failed(not_found()), &txid), "not a /tx URL");
        for status in [400, 410, 429, 500, 502] {
            assert!(
                !is_not_found(&failed(answer(status, "")), &txid),
                "{status}"
            );
        }
        assert!(!is_not_found(&failed(no_answer()), &txid));
        assert!(!is_not_found(&failed(cut_short(200)), &txid));
        assert!(!is_not_found(&failed(Err(refused())), &txid));
        // ureq 2.12.1 names the first URL after a redirect (error.rs:214).
        assert!(is_not_found(
            &MobileSignerError::InvalidPsbtJson(format!(
                "esplora get: https://h/tx/{txid}: status code 404 (redirected from https://g/tx/{txid})"
            )),
            &txid
        ));
    }

    #[test]
    fn a_txid_that_is_not_64_hex_or_a_network_with_no_host_is_refused_before_any_request() {
        let fake = Fake::new();
        let one = id('1');
        let bad = [
            String::new(),
            "abc".into(),
            one[1..].into(),
            format!("{one}0"),
            "g".repeat(64),
            format!("{}/../x", &one[..58]),
        ];
        for txid in &bad {
            match detail(&fake, txid) {
                Err(MobileSignerError::InvalidPsbtJson(m)) => assert!(
                    m.starts_with("esplora tx: txid is not 64 hex characters"),
                    "{txid}: {m}"
                ),
                other => panic!("{txid}: {other:?}"),
            }
        }
        let main = detail_of(
            &|url| fake.get(url),
            &abandon_about(),
            "",
            KeyNetwork::Main,
            &one,
        );
        assert!(
            matches!(&main, Err(MobileSignerError::InvalidPsbtJson(m)) if m.contains("mainnet")),
            "{main:?}"
        );
        assert_eq!(fake.requests(), 0);
    }

    fn assert_detail_rejected(json: &str, tip: &str, rule: &str) {
        match detail_from(
            json,
            tip,
            &Txid::from_str(&id('1')).unwrap(),
            &HashMap::new(),
        ) {
            Err(MobileSignerError::InvalidPsbtJson(m)) => {
                assert!(m.starts_with("esplora tx:"), "{rule}: {m}");
                assert!(m.contains(rule), "{rule}: {m}");
            }
            other => panic!("{rule}: {other:?}"),
        }
    }

    #[test]
    fn an_answer_for_another_transaction_is_an_error() {
        let ext0 = addr(Chain::External, 0);
        let other = full_tx('2', &[spent(SOMEONE, 2_000)], &[txo(&ext0, 1_000)], MEMPOOL);
        assert_detail_rejected(&other, TIP, "the answer is another transaction");
    }

    #[test]
    fn outputs_above_inputs_are_an_error_not_a_negative_fee() {
        let tx = full_tx(
            '1',
            &[spent(SOMEONE, 1_000)],
            &[txo(SOMEONE, 1_001)],
            MEMPOOL,
        );
        assert_detail_rejected(&tx, TIP, "outputs 1001 are more than inputs 1000");
    }

    #[test]
    fn an_answer_without_sequence_weight_or_size_is_an_error() {
        let good: Value = serde_json::from_str(&full_tx(
            '1',
            &[spent(SOMEONE, 2_000)],
            &[txo(SOMEONE, 1_000)],
            MEMPOOL,
        ))
        .unwrap();
        let sequence = "input 0 has no u32 sequence";
        let cases = [
            ("/vin/0/sequence", Value::Null, sequence),
            ("/vin/0/sequence", "4294967293".into(), sequence),
            ("/vin/0/sequence", 4_294_967_296u64.into(), sequence),
            ("/weight", Value::Null, "weight"),
            ("/weight", 0.into(), "weight"),
            ("/size", Value::Null, "size"),
            ("/size", 0.into(), "size"),
        ];
        for (field, value, rule) in cases {
            let mut tx = good.clone();
            *tx.pointer_mut(field).unwrap() = value;
            assert_detail_rejected(&tx.to_string(), TIP, rule);
        }
        assert_detail_rejected("<html>", TIP, "not JSON");
        assert_detail_rejected(&good.to_string(), "abc", "tip height");
    }

    // Under BIP-125 an input below 0xfffffffe signals, while 0xfffffffe (a locktime without replacement) and 0xffffffff do not.
    #[test]
    fn one_input_below_0xfffffffe_signals_replacement_and_none_at_or_above_it_does() {
        let signals = |sequences: &[u32]| {
            let vin: Vec<String> = sequences.iter().map(|_| spent(SOMEONE, 1_000)).collect();
            let mut tx: Value =
                serde_json::from_str(&full_tx('1', &vin, &[txo(SOMEONE, 500)], MEMPOOL)).unwrap();
            for (input, sequence) in tx["vin"].as_array_mut().unwrap().iter_mut().zip(sequences) {
                input["sequence"] = (*sequence).into();
            }
            let txid = Txid::from_str(&id('1')).unwrap();
            detail_from(&tx.to_string(), TIP, &txid, &HashMap::new())
                .unwrap()
                .signals_rbf
        };
        assert!(signals(&[0xffff_fffd]));
        assert!(signals(&[0xffff_ffff, 0, 0xffff_fffe]));
        assert!(!signals(&[0xffff_fffe]));
        assert!(!signals(&[0xffff_ffff, 0xffff_fffe]));
    }

    // Every expected figure is Esplora's own, from its fee field, its size, and its weight / 4 rounded up.
    #[test]
    fn the_real_recording_reads_back_with_esplora_s_own_figures() {
        let txs: Vec<Value> = serde_json::from_str(REAL_TXS).unwrap();
        let read = |i: usize| {
            let txid = Txid::from_str(REAL_TXIDS[i]).unwrap();
            detail_from(&txs[i].to_string(), REAL_TIP, &txid, &HashMap::new()).unwrap()
        };
        let expected = [
            (0, 225, 198, false),
            (176, 392, 201, true),
            (206, 814, 412, true),
            (104, 371, 209, true),
        ];
        for (i, (fee, size, vsize, rbf)) in expected.into_iter().enumerate() {
            let found = read(i);
            assert_eq!(
                (
                    found.fee_sats,
                    found.size_bytes,
                    found.vsize,
                    found.signals_rbf
                ),
                (fee, size, vsize, rbf),
                "{}",
                REAL_TXIDS[i]
            );
            assert_eq!(txs[i]["fee"], fee, "the recording's own fee");
            assert_eq!(found.block_height, Some(5_259_564));
            assert_eq!(found.tx, hot(REAL_TXIDS[i], 0, 2, Some(1_793_560_954)));
        }
        let coinbase = read(0);
        assert_eq!(coinbase.inputs, [part(None, 0, TxoOwner::Other)]);
        assert_eq!(coinbase.outputs.len(), 3);
        assert_eq!(
            coinbase.outputs[2],
            part(None, 0, TxoOwner::Other),
            "OP_RETURN"
        );
    }

    // http_get with the network swapped out. The real pick_host runs over the public hosts, where blockstream's probe gets a
    // real 404 for /block-height/0 through probe_genesis and mempool.space times out, so nothing asks for the transaction.
    #[test]
    fn a_probe_404_inside_no_host_reachable_is_a_failure_not_an_unknown_txid() {
        let answers_404: &'static str = Box::leak(not_found_host().into_boxed_str());
        let probe = |host: &HostSpec| match host.base {
            ESPLORA_TESTNET4 => probe_genesis(&HostSpec {
                base: answers_404,
                ..*host
            }),
            _ => Err(MobileSignerError::InvalidPsbtJson(
                no_answer().unwrap_err().to_string(),
            )),
        };
        let chosen = Mutex::new(None);
        let esplora = |url: &str| -> Result<String, MobileSignerError> {
            let index = pick_host(&PUBLIC_TESTNET4_HOSTS, &probe, Instant::now(), &chosen)?;
            unreachable!("no host passed its probe, yet {url} went to host {index}")
        };
        match detail_of(&esplora, &abandon_about(), "", KeyNetwork::Test, &id('1')) {
            Err(MobileSignerError::InvalidPsbtJson(m)) => assert!(
                m.starts_with("esplora get: no testnet4 host is reachable")
                    && m.contains("/block-height/0: status code 404"),
                "{m}"
            ),
            other => panic!("{other:?}"),
        }
    }

    // 21 million BTC is 2,100,000,000,000,000 sat, so no value or sum above it is real.
    #[test]
    fn a_value_or_a_sum_above_21_million_btc_is_an_error_and_21_million_is_the_largest_fee() {
        let max: u64 = 2_100_000_000_000_000;
        let cases = [
            (vec![spent(SOMEONE, max + 1)], vec![txo(SOMEONE, 1_000)]),
            (
                vec![spent(SOMEONE, max), spent(SOMEONE, 1)],
                vec![txo(SOMEONE, 1_000)],
            ),
            (vec![spent(SOMEONE, max + 2)], vec![txo(SOMEONE, max + 1)]),
            (
                vec![spent(SOMEONE, max)],
                vec![txo(SOMEONE, max), txo(SOMEONE, 1)],
            ),
        ];
        for (vin, vout) in cases {
            let tx = full_tx('1', &vin, &vout, MEMPOOL);
            assert_detail_rejected(&tx, TIP, "more than 21 million BTC");
        }
        let mut coinbase: Value =
            serde_json::from_str(&full_tx('1', &[], &[txo(SOMEONE, max + 1)], MEMPOOL)).unwrap();
        coinbase["vin"] = serde_json::json!([{"is_coinbase": true, "prevout": null, "sequence": 0xffff_ffff_u32}]);
        assert_detail_rejected(&coinbase.to_string(), TIP, "more than 21 million BTC");
        let all = full_tx('1', &[spent(SOMEONE, max)], &[txo(SOMEONE, 0)], MEMPOOL);
        let txid = Txid::from_str(&id('1')).unwrap();
        assert_eq!(
            detail_from(&all, TIP, &txid, &HashMap::new())
                .unwrap()
                .fee_sats,
            max
        );
    }

    // A coinbase makes new coins and is its transaction's only input. Beside a real input it zeroed the fee and skipped the
    // outputs-above-inputs check.
    #[test]
    fn a_coinbase_input_beside_another_input_is_an_error() {
        for at in 0..2 {
            let mut tx: Value = serde_json::from_str(&full_tx(
                '1',
                &[spent(SOMEONE, 10_000), spent(SOMEONE, 1)],
                &[txo(SOMEONE, 6_000), txo(SOMEONE, 3_000)],
                MEMPOOL,
            ))
            .unwrap();
            tx["vin"][at] = serde_json::json!({"is_coinbase": true, "prevout": null, "sequence": 0xffff_fffd_u32});
            assert_detail_rejected(&tx.to_string(), TIP, "coinbase");
        }
    }

    // Weight is three times the size without witnesses plus the whole size, so it runs from the size to four times the size.
    #[test]
    fn a_weight_outside_the_size_to_four_times_the_size_is_an_error() {
        let weighing = |weight: u64| {
            let mut tx: Value = serde_json::from_str(&full_tx(
                '1',
                &[spent(SOMEONE, 2_000)],
                &[txo(SOMEONE, 1_000)],
                MEMPOOL,
            ))
            .unwrap();
            tx["weight"] = weight.into();
            tx.to_string()
        };
        // The size stays 222.
        for weight in [100, 221, 889] {
            assert_detail_rejected(&weighing(weight), TIP, "4 x size");
        }
        let txid = Txid::from_str(&id('1')).unwrap();
        for (weight, vsize) in [(222, 56), (888, 222)] {
            let found = detail_from(&weighing(weight), TIP, &txid, &HashMap::new()).unwrap();
            assert_eq!(found.vsize, vsize, "{weight}");
        }
    }

    #[test]
    fn a_transaction_with_no_input_or_no_output_is_an_error() {
        let one_in = [spent(SOMEONE, 2_000)];
        let one_out = [txo(SOMEONE, 1_000)];
        let none: [String; 0] = [];
        for (vin, vout) in [
            (&none[..], &none[..]),
            (&one_in[..], &none[..]),
            (&none[..], &one_out[..]),
        ] {
            let tx = full_tx('1', vin, vout, &at(100));
            assert_detail_rejected(&tx, TIP, "no input or no output");
        }
    }

    // Prints what own_addresses costs at the cap and asserts no time. The phone runs a release build, so measure with
    // cargo test --release -p mobile-signer-ffi own_addresses_cost -- --ignored --nocapture
    #[test]
    #[ignore]
    fn own_addresses_cost_at_the_cap() {
        let account = Account::new(&abandon_about(), "", Network::Testnet4).unwrap();
        for _ in 0..7 {
            let start = Instant::now();
            let own = own_addresses(&account).unwrap();
            println!(
                "own_addresses: {} addresses in {:?}",
                own.len(),
                start.elapsed()
            );
        }
    }
}
