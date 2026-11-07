//! Single-sig hot wallet (free BTC) on one key account of the `AddressType` the user picked, BIP-84
//! P2WPKH unless they picked another.
//!
//! Shape matches the BDK hot-wallet half described in `mobile-signer-ffi/README.md`.
//! Address/UTXO/sign/broadcast are real against testnet Esplora; full `bdk_wallet`
//! persistence and coin selection land when Android binary size is measured.
//!
//! Every call syncs the account from Esplora: both BIP-84 chains, external `0/i` and internal
//! `1/i`, by the rule in `crate::scan`, which asks `GAP` indices past the highest index proven paid,
//! so no host moves a scan by claiming use. What a scan learned (which indices are used, how far use is
//! proven, no key and no amount) is kept in `SCAN_STORE`, in a file per wallet once the app names a
//! folder, so the next call, after a restart too, asks only what can have changed and proves each chain's
//! proven index used again before it trusts the file; see `synced`.
//!
//! Every read and the broadcast go through `crate::chain::ChainSource`, so the wallet logic here never
//! builds a URL, reads a JSON body or picks a host.

use std::collections::hash_map::Entry;
use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use std::str::FromStr;
use std::sync::{LazyLock, Mutex, PoisonError};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use crate::address_type::AddressType;
use crate::chain::{
    ChainSource, EsploraSource, Http, Operator, Summary, TxView, Txo, Utxo, WINDOW, check_network,
    history_error, throttled, tx_error,
};
use crate::proof::{Anchor, NotProven, prove_use, verify};
use crate::scan::{ChainScan, Event, GAP, Proof, ScanStatus, Seen, Step, step};
use crate::scan_store::{FullPass, Last, ScanStore, WalletId, WalletScan};
use crate::{KeyNetwork, MobileSignerError};
use bip39::Mnemonic;
use bitcoin::absolute::LockTime;
use bitcoin::bip32::{ChildNumber, Xpriv, Xpub};
use bitcoin::consensus::encode::{deserialize_hex, serialize_hex};
use bitcoin::hashes::{Hash, sha256};
use bitcoin::secp256k1::{All, Message, Secp256k1, SecretKey, ecdsa};
use bitcoin::transaction::{InputWeightPrediction, predict_weight};
use bitcoin::{
    Address, Amount, CompressedPublicKey, Network, OutPoint, PrivateKey, Script, ScriptBuf,
    Sequence, Transaction, TxIn, TxOut, Txid, Witness, sighash::SighashCache,
};

/// How long after a sync of a wallet ended the next call shares its answer with no request, so the
/// app's snapshot followed by history costs one sync. A used address's balance is never older than this.
const SHARE_TTL: Duration = Duration::from_secs(10);
/// How often a full pass asks every index again, when the own node, which has no request limit, serves.
const OWN_NODE_FULL_PASS: Duration = Duration::from_secs(30 * 60);
/// The same through a public host, which limits requests per IP (docs/04-open-items.md item 49). A pick
/// from the design. A full pass asks each index below the frontier plus `GAP` once and proves nothing again.
const PUBLIC_FULL_PASS: Duration = Duration::from_secs(6 * 60 * 60);
/// Unused indices after the last used one that a routine sync asks about. shortcut: a coin paid to an
/// index further out, to an unused index below the last used one that the wallet does not hand out, or again
/// to a used address that holds nothing shows at the next full pass; raise it when a wallet is seen handing
/// out that many addresses between two syncs.
const LOOKAHEAD: usize = 5;

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct HotWalletSnapshot {
    pub receive_address: String,
    /// Confirmed plus mempool, over every address of both chains: what the wallet holds, once `scan` is
    /// `Complete`. Before that, only what the scan has found so far, which no screen may show as the balance.
    pub balance_sats: u64,
    /// What a payment could spend now: the confirmed coins of receive addresses and the wallet's
    /// own change, confirmed or not. The same coins `hot_prepare_send_all` spends, so a receive
    /// still in the mempool counts in `balance_sats` and not here. 0 unless `scan` is `Complete`.
    pub spendable_sats: u64,
    pub network: KeyNetwork,
    pub scan: HotScan,
}

/// How far the scan behind a snapshot got.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum HotScan {
    /// Every address a restore finds was asked, so the figures are the wallet's.
    Complete,
    /// The scan stopped short and goes on at the next call, after a host's request limit or a payment that
    /// no block holds yet.
    Continuing,
    /// A host lied about the wallet's history, so the figures are not the wallet's.
    Unverified,
}

impl From<&ScanStatus> for HotScan {
    fn from(status: &ScanStatus) -> Self {
        match status {
            ScanStatus::Complete => HotScan::Complete,
            ScanStatus::Continuing(_) => HotScan::Continuing,
            ScanStatus::Unverified(_) => HotScan::Unverified,
        }
    }
}

fn network_of(key_network: KeyNetwork) -> Network {
    match key_network {
        KeyNetwork::Main => Network::Bitcoin,
        KeyNetwork::Test => Network::Testnet4,
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

impl Chain {
    fn name(self) -> &'static str {
        match self {
            Chain::External => "receive",
            Chain::Internal => "change",
        }
    }
}

/// One key of the wallet. It carries the account's address type, so each input signs and is checked
/// the way its own coin is locked.
pub(crate) struct OwnKey {
    pub(crate) address_type: AddressType,
    pub(crate) privkey: PrivateKey,
    pub(crate) pubkey: CompressedPublicKey,
    pub(crate) address: Address,
}

/// The account's two chain keys, derived once per call. `key` derives one index offline.
struct Account {
    secp: Secp256k1<All>,
    network: Network,
    address_type: AddressType,
    chains: [Xpriv; 2],
}

impl Account {
    fn new(
        words: &[String],
        passphrase: &str,
        network: Network,
        address_type: AddressType,
    ) -> Result<Self, MobileSignerError> {
        if !address_type.enabled_on(network) {
            return Err(MobileSignerError::InvalidPsbtJson(format!(
                "{address_type:?} is not enabled on mainnet in this build"
            )));
        }
        let bad = |e: bitcoin::bip32::Error| MobileSignerError::InvalidPsbtJson(e.to_string());
        let mnemonic = mnemonic_from(words)?;
        let secp = Secp256k1::new();
        let master = Xpriv::new_master(network, &mnemonic.to_seed(passphrase)).map_err(bad)?;
        let account = master
            .derive_priv(&secp, &address_type.account_path())
            .map_err(bad)?;
        let chain = |index| account.derive_priv(&secp, &[ChildNumber::Normal { index }]);
        let chains = [chain(0).map_err(bad)?, chain(1).map_err(bad)?];
        Ok(Self {
            secp,
            network,
            address_type,
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
        let address = self.address_type.address(&self.secp, &pubkey, self.network);
        Ok(OwnKey {
            address_type: self.address_type,
            privkey,
            pubkey,
            address,
        })
    }

    /// SHA-256 of the two chain xpubs. Public data, so the store can name a wallet without holding
    /// a key, and different words, passphrase or network give a different id.
    fn id(&self) -> WalletId {
        let mut bytes = Vec::with_capacity(2 * 78);
        for chain in &self.chains {
            bytes.extend_from_slice(&Xpub::from_priv(&self.secp, chain).encode());
        }
        sha256::Hash::hash(&bytes).to_byte_array()
    }
}

/// The proof of use a scan accepts on `network`.
fn anchor_of(network: KeyNetwork) -> Anchor {
    match network {
        KeyNetwork::Test => Anchor::Testnet4,
        KeyNetwork::Main => Anchor::Mainnet,
    }
}

/// Every wallet's scan, in the folder `hot_use_state_dir` names once the app has called it.
static SCAN_STORE: LazyLock<ScanStore> = LazyLock::new(ScanStore::default);

/// The testnet4 hosts this wallet reads and posts through outside a sync. A GET that loses the host
/// in use marks every wallet's picture stale, so the next call syncs on the host chosen next.
static TESTNET4_HOSTS: Http = Http::new(mark_scans_stale);
static TESTNET4: EsploraSource = EsploraSource(&TESTNET4_HOSTS);

/// The testnet4 hosts for one call that syncs. A host the scan catches lying is left for the next
/// one for the rest of the call.
fn testnet4() -> Http {
    Http::new(mark_scans_stale)
}

fn mark_scans_stale() {
    SCAN_STORE.mark_stale();
}

/// Both chains as a sync left them, with the key of every index it asked about and of the address each
/// chain hands out, and each index's balance by that sync.
struct Wallet {
    scans: [ChainScan; 2],
    keys: [Vec<OwnKey>; 2],
    balances: [Vec<u64>; 2],
    status: ScanStatus,
}

impl Wallet {
    /// The address the wallet hands out next on `chain`, by `ChainScan::issued`.
    fn next_unused(&self, chain: Chain) -> &OwnKey {
        let chain = chain as usize;
        &self.keys[chain][self.scans[chain].issued() as usize]
    }

    /// Each index the sync saw, with its key, what it keeps of the index and its balance.
    fn indices(&self) -> impl Iterator<Item = (Chain, &OwnKey, &Seen, u64)> {
        [Chain::External, Chain::Internal]
            .into_iter()
            .flat_map(move |chain| {
                let c = chain as usize;
                self.keys[c]
                    .iter()
                    .zip(&self.scans[c].seen)
                    .zip(&self.balances[c])
                    .map(move |((key, seen), balance)| (chain, key, seen, *balance))
            })
    }

    fn used(&self) -> impl Iterator<Item = (Chain, &OwnKey)> {
        self.indices()
            .filter(|(_, _, seen, _)| seen.used)
            .map(|(chain, key, _, _)| (chain, key))
    }

    /// The used addresses whose balance is above zero: the only ones whose coin lists are worth a
    /// request. A host whose list for an emptied address still holds a coin contradicts its own
    /// stats, and the coin is left alone.
    fn funded(&self) -> impl Iterator<Item = (Chain, &OwnKey)> {
        self.indices()
            .filter(|(_, _, seen, balance)| seen.used && *balance > 0)
            .map(|(chain, key, _, _)| (chain, key))
    }

    fn addresses(&self) -> Vec<String> {
        self.keys
            .iter()
            .flatten()
            .map(|key| key.address.to_string())
            .collect()
    }

    fn balance(&self) -> Result<u64, MobileSignerError> {
        self.indices()
            .try_fold(0u64, |sum, (_, _, _, balance)| sum.checked_add(balance))
            .ok_or_else(|| {
                MobileSignerError::InvalidPsbtJson(
                    "esplora balance: the sum over the wallet's addresses does not fit u64".into(),
                )
            })
    }
}

/// `wallet` when its scan reached the end of the wallet, so no payment or history works from part of it.
/// A scan that goes on fails with `esplora get:` and one a host lied in with `esplora scan:`, as the app maps them.
fn complete(wallet: Wallet) -> Result<Wallet, MobileSignerError> {
    match &wallet.status {
        ScanStatus::Complete => Ok(wallet),
        ScanStatus::Continuing(why) => Err(MobileSignerError::InvalidPsbtJson(format!(
            "esplora get: {why}"
        ))),
        ScanStatus::Unverified(why) => Err(MobileSignerError::InvalidPsbtJson(format!(
            "esplora scan: {why}"
        ))),
    }
}

/// The wallet as `store` and `source` give it, by one of three syncs:
///
/// - Shared: a sync of this wallet ended under `SHARE_TTL` ago and no payment went out or host was lost
///   since it started. Its answer is returned with no request, so the app's snapshot then history is one sync.
/// - Routine: the funded and pending indices, the proven index, the index handed out and the `LOOKAHEAD` indices after
///   the last used one of each chain are asked again, so balances are fresh, and the proofs stand while the host calls
///   their indices used. A lookahead index that turned out used, a pass cut short, and a wallet never scanned go on by
///   `crate::scan::step` from what is known. The index the file holds as proven for a chain is proven again first.
/// - Full: when the last full pass started `full_pass_every` ago or longer, every index below each chain's
///   frontier plus `GAP` is asked again from 0, and the frontier stands, so nothing is proven twice.
///
/// One sync of a wallet runs at a time, and a call that comes while one runs waits for it and shares its
/// answer. A sync ends `Continuing` when a host refuses a read with its request limit, or when every host is
/// under a request limit, before any read, and the next one goes on from what it kept, the routine part
/// included, so a wallet with more funded addresses than a host serves in an hour still completes. Any other failed read fails the call. Either way what the sync learned is kept, the scan
/// written to the wallet's file when the app named a folder and each balance in memory. Freshness counts from when
/// a sync ends, and the period of full passes from when one starts.
fn synced(
    source: &dyn ChainSource,
    words: &[String],
    passphrase: &str,
    network: KeyNetwork,
    address_type: AddressType,
    clock: &dyn Fn() -> SystemTime,
    store: &ScanStore,
) -> Result<Wallet, MobileSignerError> {
    let account = Account::new(words, passphrase, network_of(network), address_type)?;
    check_network(network)?;
    let id = account.id();
    let held = store.wallet(&id);
    let mut entry = held.lock().unwrap_or_else(PoisonError::into_inner);
    if let Some(last) = &entry.last
        && last.epoch == store.epoch()
        && elapsed(clock(), last.ended) < SHARE_TTL
    {
        return wallet_of(&account, &entry.scan, &entry.balances, last.status.clone());
    }
    let epoch = store.epoch();
    let started = clock();
    let entry = &mut *entry;
    let scan = &mut entry.scan;
    let start_full = match scan.full {
        None => true,
        Some(FullPass::Running(_)) => false,
        Some(FullPass::Ended(at)) => match source.operator() {
            Ok(operator) => since_unix(started, at) >= full_pass_every(operator),
            // Every host is limited. No full pass starts, and the routine reads below end the sync `Continuing` on the same limit.
            Err(e) if throttled(&e).is_some() => false,
            Err(e) => return Err(e),
        },
    };
    if start_full {
        for chain in &mut scan.chains {
            chain.seen.clear();
        }
        scan.full = Some(FullPass::Running(unix_seconds(started)));
        entry.round = None;
    }
    if entry.round.is_none() {
        entry.balances = scan
            .chains
            .each_ref()
            .map(|chain| vec![0; chain.seen.len()]);
    }
    let routine = entry.round.get_or_insert([0, 0]);
    let pass = scan_chains(
        source,
        anchor_of(network),
        &account,
        &mut scan.chains,
        &mut entry.balances,
        routine,
    );
    match &pass {
        Ok(ScanStatus::Complete) => {
            if let Some(FullPass::Running(at)) = scan.full {
                scan.full = Some(FullPass::Ended(at));
            }
            entry.round = None;
        }
        // A host lied, so its claims go and the next sync asks every index again. Proofs do not depend on who served them.
        Ok(ScanStatus::Unverified(_)) => {
            for chain in &mut scan.chains {
                chain.seen.clear();
            }
            scan.full = None;
            entry.round = None;
        }
        _ => {}
    }
    store.save(&id, &held, scan);
    entry.last = None;
    let last = Last {
        ended: clock(),
        epoch,
        status: pass?,
    };
    entry.last = Some(last.clone());
    wallet_of(&account, &entry.scan, &entry.balances, last.status)
}

/// How often a full pass runs on the host the next read goes to, by who runs it.
fn full_pass_every(operator: Operator) -> Duration {
    match operator {
        Operator::Mebit => OWN_NODE_FULL_PASS,
        Operator::Blockstream | Operator::MempoolSpace => PUBLIC_FULL_PASS,
    }
}

/// How long ago `since` was, or as long as can be when the clock reads earlier, so a clock moved back syncs.
fn elapsed(now: SystemTime, since: SystemTime) -> Duration {
    now.duration_since(since).unwrap_or(Duration::MAX)
}

/// `elapsed` since Unix second `at`, which a file gave, and as long as can be for a second no clock holds.
fn since_unix(now: SystemTime, at: u64) -> Duration {
    UNIX_EPOCH
        .checked_add(Duration::from_secs(at))
        .map_or(Duration::MAX, |at| elapsed(now, at))
}

fn unix_seconds(at: SystemTime) -> u64 {
    at.duration_since(UNIX_EPOCH)
        .map_or(0, |since| since.as_secs())
}

/// `scan`, `balances` and `status` as the wallet they describe, with every key derived again. No request.
fn wallet_of(
    account: &Account,
    scan: &WalletScan,
    balances: &[Vec<u64>; 2],
    status: ScanStatus,
) -> Result<Wallet, MobileSignerError> {
    Ok(Wallet {
        keys: [
            keys_of(account, Chain::External, &scan.chains[0])?,
            keys_of(account, Chain::Internal, &scan.chains[1])?,
        ],
        scans: scan.chains.clone(),
        balances: balances.clone(),
        status,
    })
}

/// Both chains brought up to date on `source`, each to the end of its pass, with each index's balance in
/// `balances` and the routine part's progress in `routine`. A host caught lying is left for the next one, which
/// is asked every index again from 0 while the proofs stand, since a proof does not depend on who served it. A
/// chain that does not complete ends the sync, and the status names it.
fn scan_chains(
    source: &dyn ChainSource,
    anchor: Anchor,
    account: &Account,
    scans: &mut [ChainScan; 2],
    balances: &mut [Vec<u64>; 2],
    routine: &mut [usize; 2],
) -> Result<ScanStatus, MobileSignerError> {
    loop {
        let mut ended = ScanStatus::Complete;
        for chain in [Chain::External, Chain::Internal] {
            let c = chain as usize;
            let status = scan_chain(
                source,
                anchor,
                account,
                chain,
                &mut scans[c],
                &mut balances[c],
                &mut routine[c],
            )?;
            ended = match status {
                ScanStatus::Complete => continue,
                ScanStatus::Continuing(why) => {
                    ScanStatus::Continuing(format!("{} {why}", chain.name()))
                }
                ScanStatus::Unverified(why) => {
                    ScanStatus::Unverified(format!("{} {why}", chain.name()))
                }
            };
            break;
        }
        if !matches!(ended, ScanStatus::Unverified(_)) || !source.skip_host() {
            return Ok(ended);
        }
        for scan in scans.iter_mut() {
            scan.seen.clear();
        }
        *balances = [Vec::new(), Vec::new()];
        *routine = [0, 0];
    }
}

/// `scan` of `chain` brought up to date on `source`, with each index's balance in `balances`. The index the scan
/// file holds as proven is proven again first, so nothing the file says is asked about again or trusted before.
/// Then the routine part goes on from `routine`, and `crate::scan::step` runs to the end of its pass, asking
/// `WINDOW` indices at a time, so a pass cut short keeps all but the last few it was told. A read the host throttles
/// ends it `Continuing`, and any other failed read fails it, with `scan`, `balances` and `routine` holding what it
/// learned either way.
fn scan_chain(
    source: &dyn ChainSource,
    anchor: Anchor,
    account: &Account,
    chain: Chain,
    scan: &mut ChainScan,
    balances: &mut Vec<u64>,
    routine: &mut usize,
) -> Result<ScanStatus, MobileSignerError> {
    let mut refreshed = false;
    let mut at = scan.next();
    loop {
        if !refreshed && !matches!(scan.proof, Some(Proof::Stored(_))) {
            refreshed = true;
            if let Some(status) = refresh(source, account, chain, scan, balances, routine)? {
                return Ok(status);
            }
            at = scan.next();
        }
        let event = match &at {
            Step::Ask(indices) => {
                let part = indices.start..indices.end.min(indices.start + WINDOW as u32);
                match lookup(source, account, chain, part) {
                    Ok(summaries) => {
                        balances.extend(summaries.iter().map(|summary| summary.balance));
                        Event::Asked(summaries)
                    }
                    Err(e) => match throttled(&e).map(str::to_string) {
                        Some(why) => Event::Throttled(why),
                        None => return Err(e),
                    },
                }
            }
            Step::Reprove(index) => {
                let address = account.key(chain, *index)?.address;
                match prove_use(source, anchor, &address, *index) {
                    Ok(proven) => Event::Proven(proven),
                    Err(not) => Event::NotProven(not),
                }
            }
            Step::Recheck(evidence) => {
                let address = account.key(chain, evidence.index)?.address;
                match verify(source, anchor, &address, *evidence) {
                    Ok(proven) => Event::Proven(proven),
                    Err(not) => Event::NotProven(not),
                }
            }
            Step::Prove(index) => {
                let address = account.key(chain, *index)?.address;
                match prove_use(source, anchor, &address, *index) {
                    Ok(proven) => Event::Proven(proven),
                    Err(not) => {
                        if let NotProven::Unconfirmed(summary) = &not {
                            balances[*index as usize] = summary.balance;
                        }
                        Event::NotProven(not)
                    }
                }
            }
            Step::Done(status) => return Ok(status.clone()),
        };
        at = step(scan, at, event);
        // A file's index that does not hold, or a payment shown again and gone, empties the chain.
        balances.truncate(scan.seen.len());
        *routine = match refreshed {
            true => scan.seen.len(),
            false => (*routine).min(scan.seen.len()),
        };
    }
}

/// The key of every index `scan` has seen below its frontier plus `GAP`, and of the one it hands out, which a
/// pass cut short may not have reached, derived again. No request. The index the scan file holds moves no
/// frontier before a source proves it used again, so a file never makes this derive keys a source did not prove.
fn keys_of(
    account: &Account,
    chain: Chain,
    scan: &ChainScan,
) -> Result<Vec<OwnKey>, MobileSignerError> {
    let seen = (scan.seen.len() as u32).min(scan.frontier() + GAP);
    (0..seen.max(scan.issued() + 1))
        .map(|index| account.key(chain, index))
        .collect()
}

/// What the source says of `indices` of `chain`, in order.
fn lookup(
    source: &dyn ChainSource,
    account: &Account,
    chain: Chain,
    indices: impl IntoIterator<Item = u32>,
) -> Result<Vec<Summary>, MobileSignerError> {
    let keys = indices
        .into_iter()
        .map(|index| account.key(chain, index))
        .collect::<Result<Vec<_>, _>>()?;
    let addresses: Vec<&Address> = keys.iter().map(|key| &key.address).collect();
    let summaries = source.summaries(&addresses)?;
    if summaries.len() != keys.len() {
        return Err(MobileSignerError::InvalidPsbtJson(
            "esplora scan: fewer answers than asked".into(),
        ));
    }
    Ok(summaries)
}

/// The routine part of a sync. It asks again the funded and pending indices of `scan`, its proven index, so a host
/// that no longer shows that payment has it shown again, the index the chain hands out, which a gap below the frontier
/// puts below the last used one, and the `LOOKAHEAD` indices after the last used one, each answer setting its index's
/// balance in `balances`. It asks `WINDOW` at a time from `routine`, the first index the
/// round has not asked, and moves `routine` past each part answered, so a throttled part ends it `Continuing` and
/// the next sync goes on from there. Every other index keeps what it was, and holds nothing, because every index
/// that held coins is asked. When a lookahead index turned out used the chain ends at the lookahead, so the pass
/// asks the rest of the window again and proves from there. The frontier stands. A chain with nothing seen asks
/// nothing here.
fn refresh(
    source: &dyn ChainSource,
    account: &Account,
    chain: Chain,
    scan: &mut ChainScan,
    balances: &mut Vec<u64>,
    routine: &mut usize,
) -> Result<Option<ScanStatus>, MobileSignerError> {
    let after_last_used = scan
        .seen
        .iter()
        .rposition(|seen| seen.used)
        .map_or(0, |last| last + 1);
    let edge = (after_last_used + LOOKAHEAD).min(scan.seen.len());
    let proven = scan.frontier().checked_sub(1).map(|index| index as usize);
    let issued = scan.issued() as usize;
    let asked: Vec<usize> = (*routine..edge)
        .filter(|&i| {
            let seen = scan.seen[i];
            i >= after_last_used || seen.funded || seen.pending || Some(i) == proven || i == issued
        })
        .collect();
    for part in asked.chunks(WINDOW) {
        let fresh = match lookup(source, account, chain, part.iter().map(|&i| i as u32)) {
            Ok(fresh) => fresh,
            Err(e) => {
                return match throttled(&e).map(str::to_string) {
                    Some(why) => Ok(Some(ScanStatus::Continuing(format!(
                        "index {} could not be asked again: {why}",
                        part[0]
                    )))),
                    None => Err(e),
                };
            }
        };
        for (&i, summary) in part.iter().zip(&fresh) {
            scan.seen[i] = Seen::from(summary);
            balances[i] = summary.balance;
        }
        *routine = part[part.len() - 1] + 1;
    }
    if scan.seen[after_last_used..edge]
        .iter()
        .any(|seen| seen.used)
    {
        scan.seen.truncate(edge);
        balances.truncate(edge);
    }
    *routine = scan.seen.len();
    Ok(None)
}

// The `///` text on each export stays as committed: UniFFI hashes it into the checksum the committed
// Kotlin bindings pin. What each call does now is on the inner function it hands over to.
/// Receive address `index` (usually 0) of `address_type`'s account. `passphrase` is the
/// BIP-39 passphrase the key was restored with, empty for none: a different
/// passphrase is a different wallet. Mainnet takes Native SegWit only.
#[uniffi::export]
pub fn hot_receive_address(
    mnemonic_words: Vec<String>,
    passphrase: String,
    network: KeyNetwork,
    address_type: AddressType,
    index: u32,
) -> Result<String, MobileSignerError> {
    let account = Account::new(
        &mnemonic_words,
        &passphrase,
        network_of(network),
        address_type,
    )?;
    Ok(account.key(Chain::External, index)?.address.to_string())
}

/// The account path the hot wallet derives `address_type`'s keys from, `m/{purpose}'/1'/0'` on either
/// network, as the fingerprint sheets print it.
#[uniffi::export]
pub fn hot_account_path(address_type: AddressType) -> String {
    format!("m/{}", address_type.account_path())
}

/// Whether a wallet on `network` can take `address_type`, as every call that derives one of its keys
/// enforces: every type on testnet4, Native SegWit alone on mainnet.
#[uniffi::export]
pub fn hot_address_type_enabled(address_type: AddressType, network: KeyNetwork) -> bool {
    address_type.enabled_on(network_of(network))
}

/// The virtual size the Send screen prices a payment of an `address_type` wallet at, before Review
/// quotes the real one with `hot_quote_send`: one of its coins paying an address of the same type,
/// with change, as `signed_payment` builds it. Native SegWit's is 141, the size every estimate used
/// before the other types.
#[uniffi::export]
pub fn hot_typical_vsize(address_type: AddressType) -> u64 {
    let script = address_type.script_len();
    vsize(&[address_type.input_weight()], &[script, script])
}

/// Confirmed + mempool balance for receive index 0 via Esplora.
#[uniffi::export]
pub fn hot_wallet_snapshot(
    mnemonic_words: Vec<String>,
    passphrase: String,
    network: KeyNetwork,
    address_type: AddressType,
) -> Result<HotWalletSnapshot, MobileSignerError> {
    snapshot_from(
        &EsploraSource(&testnet4()),
        &mnemonic_words,
        &passphrase,
        network,
        address_type,
        &SystemTime::now,
        &SCAN_STORE,
    )
}

/// Keeps each wallet's scan in `dir`, app storage that no backup copies, so the first sync after a restart
/// asks only what can have changed. Call it once at app start. Without it scans live in memory only.
#[uniffi::export]
pub fn hot_use_state_dir(dir: String) {
    SCAN_STORE.use_dir(PathBuf::from(dir));
}

/// Forgets every wallet's scan, in memory and in the folder `hot_use_state_dir` named. Call it when the
/// wallet is erased.
#[uniffi::export]
pub fn hot_forget_scan_state() {
    SCAN_STORE.forget();
}

/// The first unused receive address, the confirmed + mempool balance over every address of both
/// chains, and the spendable balance from the same coin lists a payment would spend, with how far the
/// scan got. About 40 Esplora calls for a fresh wallet, a routine sync's few after that, plus one coin
/// list per funded address once the scan is complete, so call it off the main thread. A scan that goes on
/// or that a host lied in still answers, so the app can say which, and no screen may show its figures.
fn snapshot_from(
    source: &dyn ChainSource,
    words: &[String],
    passphrase: &str,
    network: KeyNetwork,
    address_type: AddressType,
    clock: &dyn Fn() -> SystemTime,
    store: &ScanStore,
) -> Result<HotWalletSnapshot, MobileSignerError> {
    let wallet = synced(
        source,
        words,
        passphrase,
        network,
        address_type,
        clock,
        store,
    )?;
    let (scan, spendable_sats) = match HotScan::from(&wallet.status) {
        HotScan::Complete => match spendable_coins(source, &wallet) {
            Ok((_, have)) => (HotScan::Complete, have),
            // The request limit a read of the sync can meet, so the snapshot goes on later the same way.
            Err(e) if throttled(&e).is_some() => (HotScan::Continuing, 0),
            Err(e) => return Err(e),
        },
        scan @ (HotScan::Continuing | HotScan::Unverified) => (scan, 0),
    };
    Ok(HotWalletSnapshot {
        receive_address: wallet.next_unused(Chain::External).address.to_string(),
        balance_sats: wallet.balance()?,
        spendable_sats,
        network,
        scan,
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
    address_type: AddressType,
) -> Result<Vec<HotTx>, MobileSignerError> {
    history_of(
        &EsploraSource(&testnet4()),
        &mnemonic_words,
        &passphrase,
        network,
        address_type,
        &SystemTime::now,
        &SCAN_STORE,
    )
}

/// The latest transactions of every used address of the wallet, each once, as the wallet saw it:
/// the mempool first, then confirmed transactions newest first. Only each address's first page
/// comes back (about 25 confirmed transactions plus the mempool), so older history is not here.
///
/// Makes a network call per used address plus one for the tip, on top of whatever `synced`
/// needs, so call it off the main thread. An answer it cannot read fails the whole call, never one
/// transaction, and so does a scan that has not reached the end of the wallet.
fn history_of(
    source: &dyn ChainSource,
    words: &[String],
    passphrase: &str,
    network: KeyNetwork,
    address_type: AddressType,
    clock: &dyn Fn() -> SystemTime,
    store: &ScanStore,
) -> Result<Vec<HotTx>, MobileSignerError> {
    check_network(network)?;
    let wallet = complete(synced(
        source,
        words,
        passphrase,
        network,
        address_type,
        clock,
        store,
    )?)?;
    let used: Vec<&Address> = wallet.used().map(|(_, k)| &k.address).collect();
    if used.is_empty() {
        return Ok(Vec::new());
    }
    let (pages, tip) = source.history(&used)?;
    let own = wallet.addresses();
    let own: Vec<&str> = own.iter().map(String::as_str).collect();
    merge_history(&pages, tip, &own)
}

fn merge_history(
    pages: &[Vec<TxView>],
    tip: u64,
    own: &[&str],
) -> Result<Vec<HotTx>, MobileSignerError> {
    let mut seen = HashSet::new();
    let mut all: Vec<HotTx> = Vec::new();
    for page in pages {
        let page = hot_page(page, tip, own)?;
        all.extend(page.into_iter().filter(|tx| seen.insert(tx.txid.clone())));
    }
    // Esplora lists the mempool first, then the newest block first. A stable sort on
    // confirmations keeps that order once the pages are merged.
    all.sort_by_key(|tx| tx.confirmations);
    Ok(all)
}

/// One page of history as the wallet saw it, in the source's order.
fn hot_page(page: &[TxView], tip: u64, own: &[&str]) -> Result<Vec<HotTx>, MobileSignerError> {
    page.iter()
        .enumerate()
        .map(|(i, tx)| hot_tx(tx, tip, own).map_err(|e| history_error(format!("tx {i}: {e}"))))
        .collect()
}

fn hot_tx(tx: &TxView, tip: u64, own: &[&str]) -> Result<HotTx, String> {
    let is_own = |txo: &Txo| txo.address.as_deref().is_some_and(|a| own.contains(&a));

    let mut net = 0i64;
    for output in &tx.outputs {
        if is_own(output) {
            net = net
                .checked_add_unsigned(output.value)
                .ok_or("net sats overflow")?;
        }
    }
    for prevout in tx.inputs.iter().filter_map(|input| input.prevout.as_ref()) {
        if is_own(prevout) {
            net = net
                .checked_sub_unsigned(prevout.value)
                .ok_or("net sats overflow")?;
        }
    }

    let (confirmations, block_time) = match &tx.confirmed {
        Some(block) => {
            // A lagging backend or a reorg can leave the tip below the height. That still reads 1.
            let depth = tip.saturating_sub(block.height).saturating_add(1);
            let time = block.time.filter(|t| (1..=LATEST_BLOCK_TIME).contains(t));
            (u32::try_from(depth).unwrap_or(u32::MAX), time)
        }
        None => (0, None),
    };
    Ok(HotTx {
        txid: tx.txid.clone(),
        net_sats: net,
        confirmations,
        block_time,
    })
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
    address_type: AddressType,
    txid: String,
) -> Result<Option<HotTxDetail>, MobileSignerError> {
    detail_of(
        &TESTNET4,
        &mnemonic_words,
        &passphrase,
        network,
        address_type,
        &txid,
        &SCAN_STORE,
    )
}

/// Refuses a txid that is not 64 hex before any request. Then `/tx/{txid}` and, second so a block landing between the
/// two only adds a confirmation, the tip. A 404 for that transaction is None and asks nothing more. There is no scan, because
/// the wallet's own addresses are derived offline, as far as `own_addresses` says.
fn detail_of(
    source: &dyn ChainSource,
    words: &[String],
    passphrase: &str,
    network: KeyNetwork,
    address_type: AddressType,
    txid: &str,
    store: &ScanStore,
) -> Result<Option<HotTxDetail>, MobileSignerError> {
    let txid = Txid::from_str(txid)
        .map_err(|e| tx_error(format!("txid is not 64 hex characters: {e}")))?;
    check_network(network)?;
    let account = Account::new(words, passphrase, network_of(network), address_type)?;
    let Some((tx, tip)) = source.tx(&txid)? else {
        return Ok(None);
    };
    hot_detail(&tx, tip, &txid, &own_addresses(&account, store)?).map(Some)
}

/// Every address a scan of this wallet reaches, with its chain. That is each index below its chain's `frontier + GAP`
/// in the stored scan, which for a wallet never scanned, or one whose file no sync has proven again since the app
/// started, is the first `GAP` of each chain.
fn own_addresses(
    account: &Account,
    store: &ScanStore,
) -> Result<HashMap<String, Chain>, MobileSignerError> {
    let reach = store
        .wallet(&account.id())
        .lock()
        .unwrap_or_else(PoisonError::into_inner)
        .scan
        .chains
        .each_ref()
        .map(|scan| scan.frontier() + GAP);
    let mut own = HashMap::new();
    for chain in [Chain::External, Chain::Internal] {
        for index in 0..reach[chain as usize] {
            own.insert(account.key(chain, index)?.address.to_string(), chain);
        }
    }
    Ok(own)
}

/// The detail of `tx`, read at `tip`. The row comes from `hot_tx`, so its net and confirmations follow the history's
/// rule exactly. Strict like every reader here, so no input or no output, a sequence that is not a u32, a coinbase beside
/// other inputs, a side above 21 million BTC, outputs above the inputs, no size or weight, or a weight outside the size to
/// 4 x the size fails the whole detail.
fn hot_detail(
    tx: &TxView,
    tip: u64,
    txid: &Txid,
    own: &HashMap<String, Chain>,
) -> Result<HotTxDetail, MobileSignerError> {
    let bad = |what: String| tx_error(format!("transaction {txid}: {what}"));
    let own_keys: Vec<&str> = own.keys().map(String::as_str).collect();
    let row = hot_tx(tx, tip, &own_keys).map_err(bad)?;
    let part = |address: Option<&str>, value_sats: u64| HotTxo {
        address: address.map(str::to_string),
        value_sats,
        owner: match address.and_then(|a| own.get(a)) {
            Some(Chain::External) => TxoOwner::Receive,
            Some(Chain::Internal) => TxoOwner::Change,
            None => TxoOwner::Other,
        },
    };
    if tx.inputs.is_empty() || tx.outputs.is_empty() {
        return Err(bad("no input or no output".into()));
    }

    let mut inputs = Vec::with_capacity(tx.inputs.len());
    let mut coinbase = false;
    let mut signals_rbf = false;
    for (j, input) in tx.inputs.iter().enumerate() {
        let sequence = input
            .sequence
            .and_then(|s| u32::try_from(s).ok())
            .ok_or_else(|| bad(format!("input {j} has no u32 sequence")))?;
        signals_rbf |= sequence < 0xffff_fffe;
        let Some(prevout) = &input.prevout else {
            // A coinbase makes new coins, so it is its transaction's only input.
            if tx.inputs.len() > 1 {
                return Err(bad(format!("input {j} is a coinbase beside other inputs")));
            }
            coinbase = true;
            inputs.push(part(None, 0));
            continue;
        };
        inputs.push(part(prevout.address.as_deref(), prevout.value));
    }
    let outputs: Vec<HotTxo> = tx
        .outputs
        .iter()
        .map(|output| part(output.address.as_deref(), output.value))
        .collect();

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
    let positive = |field: &str, value: Option<u64>| {
        value
            .filter(|n| *n > 0)
            .ok_or_else(|| bad(format!("no positive integer {field}")))
    };
    let (size_bytes, weight) = (positive("size", tx.size)?, positive("weight", tx.weight)?);
    // Weight is three times the size without witnesses plus the whole size.
    if !(size_bytes..=size_bytes.saturating_mul(4)).contains(&weight) {
        return Err(bad(format!(
            "weight {weight} is outside size {size_bytes} to 4 x size"
        )));
    }
    Ok(HotTxDetail {
        block_height: tx.confirmed.as_ref().map(|block| block.height),
        tx: row,
        inputs,
        outputs,
        fee_sats,
        size_bytes,
        vsize: weight.div_ceil(4),
        signals_rbf,
    })
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
    /// The fee the transaction pays, in sats, its inputs less its outputs: its virtual size times the
    /// rate asked for, rounded up, plus `dust_change_sats`, or less where that would pass the
    /// network's 10,000 sat/vB and change at its dust floor takes the rest. A fee bump or a cancel
    /// pays what `hot_prepare_fee_bump` says.
    pub fee_sats: u64,
    /// What `destination` is paid, in sats: the amount asked for, or for `hot_prepare_send_all`
    /// the whole balance less `fee_sats`, which is only known once the coins are. For a fee bump it
    /// is what the earlier payment's destination gets, and for a cancel what comes back to this wallet.
    pub amount_sats: u64,
    /// What was left for change but could not be an output, in sats: change below the change
    /// address's dust floor, which the network would refuse, or too little to pay for a change output
    /// at the rate asked. It is part of `fee_sats` instead. 0 when there was none.
    #[uniffi(default = 0)]
    pub dust_change_sats: u64,
    /// How much less a fee bump's destination gets than in the payment it replaces, in sats, because
    /// that payment had no change and no confirmed coin could keep its amount, so the new fee came out
    /// of the amount. 0 for every other answer.
    #[uniffi(default = 0)]
    pub amount_cut_sats: u64,
}

/// Scan, select coins, check and sign a payment at `fee_rate_sat_vb`, and post nothing. The only
/// network use is the Esplora lookups of the scan. The app saves the `txid` as its payment guard
/// before it calls `hot_broadcast`, so a kill between the two can never lead to a second payment.
///
/// Coins are added largest first, one at a time, until they pay the amount and the fee of the
/// transaction they make, so any payment some of the coins can make is signed. The fee is the size
/// of the transaction as built times the rate, so a payment that needs more coins pays more, never a
/// fixed sum that falls under the rate. The answer's `fee_sats` is that fee, the one `hot_quote_send`
/// reads without signing on the same chain state. Refuses, before
/// signing, a rate outside 1 to 10,000 sat/vB (the network's limit), an amount below the dust floor
/// of the destination's script (294 sats for P2WPKH up to 546 for P2PKH, the outputs the network
/// refuses), an amount that overflows u64 with its fee, coins that cannot pay it (`insufficient
/// funds:`) and a payment that needs more than the network's 100,000 vB (`too many coins:`), and
/// after signing a fee above 10,000 sat/vB times the signed transaction's virtual size. Change below
/// the change address's dust floor would be refused by the network too, and change too small to pay
/// for its own output costs more than it returns, so either goes into the fee and the answer's
/// `dust_change_sats` names it. Where that would pass 10,000 sat/vB, the change stays at exactly its
/// floor and the fee is the rest, under the rate asked. A fee above the amount is signed, because
/// the fee is paid on top and the destination still gets the amount, so the app asks the user before
/// it posts.
/// Confirmed coins pay first, and the wallet's own unconfirmed change only when they fall short. A
/// coin someone else sent is spent only once confirmed. About 40 Esplora calls for a fresh wallet,
/// so call it off the main thread.
#[uniffi::export]
pub fn hot_prepare_send_at_rate(
    mnemonic_words: Vec<String>,
    passphrase: String,
    network: KeyNetwork,
    address_type: AddressType,
    destination: String,
    amount_sats: u64,
    fee_rate_sat_vb: u64,
) -> Result<PreparedSend, MobileSignerError> {
    Ok(prepared(signed_payment(
        &EsploraSource(&testnet4()),
        &mnemonic_words,
        &passphrase,
        network,
        address_type,
        &destination,
        amount_sats,
        fee_rate_sat_vb,
        &SystemTime::now,
        &SCAN_STORE,
    )?))
}

/// A transaction a builder signed, with the fee it pays, the change below its floor that went into
/// that fee and what a fee bump cut from the destination's amount. Named fields, because the sums side
/// by side would swap silently.
struct Signed {
    tx: Transaction,
    fee_sats: u64,
    dust_change_sats: u64,
    amount_cut_sats: u64,
}

fn prepared(signed: Signed) -> PreparedSend {
    PreparedSend {
        txid: signed.tx.compute_txid().to_string(),
        hex: serialize_hex(&signed.tx),
        fee_sats: signed.fee_sats,
        // Every builder puts the destination first and change, if any, second. A cancel's one output is what comes back.
        amount_sats: signed.tx.output[0].value.to_sat(),
        dust_change_sats: signed.dust_change_sats,
        amount_cut_sats: signed.amount_cut_sats,
    }
}

/// Scan, spend every coin `hot_prepare_send_at_rate` would consider, and sign one output paying
/// `destination` the whole balance less the fee, with no change, and post nothing. The fee is the
/// size of that transaction times `fee_rate_sat_vb`, so it and the amount are only known once the
/// coins are: the answer's `amount_sats` is what leaves. Refuses, before signing, a rate outside
/// 1 to 10,000 sat/vB, a wallet with nothing confirmed to spend, coins that need more than the
/// network's 100,000 vB in one transaction (`too many coins:`), and a balance that would leave
/// less than the destination's dust floor after the fee, and after signing what
/// `hot_prepare_send_at_rate` refuses past the network's limit. The same scan as
/// `hot_prepare_send_at_rate`, so call it off the main thread.
#[uniffi::export]
pub fn hot_prepare_send_all(
    mnemonic_words: Vec<String>,
    passphrase: String,
    network: KeyNetwork,
    address_type: AddressType,
    destination: String,
    fee_rate_sat_vb: u64,
) -> Result<PreparedSend, MobileSignerError> {
    Ok(prepared(signed_send_all(
        &EsploraSource(&testnet4()),
        &mnemonic_words,
        &passphrase,
        network,
        address_type,
        &destination,
        fee_rate_sat_vb,
        &SystemTime::now,
        &SCAN_STORE,
    )?))
}

/// What `hot_quote_send` reads: the figures of the payment the signer would make now.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct QuotedSend {
    /// `PreparedSend::fee_sats` of that payment.
    pub fee_sats: u64,
    /// `PreparedSend::amount_sats`: the amount asked for, or for send all the balance less `fee_sats`.
    pub amount_sats: u64,
    /// `PreparedSend::dust_change_sats`.
    pub dust_change_sats: u64,
    /// The payment's virtual size once signed, in vbytes.
    pub vsize: u64,
}

/// What `hot_prepare_send_at_rate` with `amount_sats`, or `hot_prepare_send_all` when it is null, would
/// sign for the same arguments on the chain as this call reads it: the same scan, coin lists, coin
/// selection, sizes and refusals, and no signature, so no key signs. A Legacy coin's value is proven
/// from its parent only when the payment is signed, so a host that lists one at another value fails
/// that payment, never this call. The same scan as the payment, so call it off the main thread.
#[uniffi::export]
pub fn hot_quote_send(
    mnemonic_words: Vec<String>,
    passphrase: String,
    network: KeyNetwork,
    address_type: AddressType,
    destination: String,
    amount_sats: Option<u64>,
    fee_rate_sat_vb: u64,
) -> Result<QuotedSend, MobileSignerError> {
    quoted(
        &EsploraSource(&testnet4()),
        &mnemonic_words,
        &passphrase,
        network,
        address_type,
        &destination,
        amount_sats.map_or(SendAmount::All, SendAmount::Exact),
        fee_rate_sat_vb,
        &SystemTime::now,
        &SCAN_STORE,
    )
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
    check_network(network)?;
    broadcast_via(&TESTNET4, &hex, &txid, &SCAN_STORE)
}

/// `source`'s broadcast. Once the payment is out, or may be, every wallet's picture is stale (the call
/// does not know which wallet paid), so the next call makes a routine sync, which asks the coins spent and
/// the change address. A refusal posted nothing.
fn broadcast_via(
    source: &dyn ChainSource,
    hex: &str,
    txid: &str,
    store: &ScanStore,
) -> Result<HotSendResult, MobileSignerError> {
    let result = source.broadcast(hex, txid);
    if matches!(result, Ok(_) | Err(MobileSignerError::BroadcastUnknown(_))) {
        store.mark_stale();
    }
    Ok(HotSendResult { txid: result? })
}

/// Bitcoin Core's default dust floor for an output paying `script`: P2WPKH 294, P2WSH and P2TR 330, P2SH
/// 540, P2PKH 546. Both testnet4 hosts refuse a transaction with a fee and any output below it, and take
/// one at it.
fn dust_floor(script: &Script) -> u64 {
    script.minimal_non_dust().to_sat()
}

const MIN_FEE_RATE: u64 = 1;
/// Bitcoin Core's default `maxfeerate` for `sendrawtransaction`, 0.1 BTC/kvB. Esplora's `POST /tx`
/// sends the hex alone, so both testnet4 hosts refuse a fee above this rate times the virtual size.
pub(crate) const MAX_FEE_RATE: u64 = 10_000;

/// The node's test on the signed bytes: `fee_sats` at most `MAX_FEE_RATE` times `vsize`, the signed
/// transaction's virtual size. `signed` makes that the size the fee was built for, and change put into
/// the fee raises the fee, so the real rate can sit above the rate asked.
fn check_node_rate(fee_sats: u64, vsize: u64) -> Result<(), String> {
    if fee_sats > MAX_FEE_RATE * vsize {
        return Err(above_limit(fee_sats, vsize));
    }
    Ok(())
}

fn above_limit(fee_sats: u64, vsize: u64) -> String {
    format!("fee {fee_sats} for {vsize} vB is above the network's {MAX_FEE_RATE} sat/vB limit")
}

/// Virtual size, rounded up, of a transaction spending coins spent as `inputs` to outputs whose
/// scripts are `output_scripts` bytes long, every count at its real varint width: from 253 inputs
/// the input count takes 3 bytes. Exact for what `signed` builds, which spends every input at its
/// type's `AddressType::input_weight`.
fn vsize(inputs: &[InputWeightPrediction], output_scripts: &[usize]) -> u64 {
    predict_weight(inputs.iter().copied(), output_scripts.iter().copied()).to_vbytes_ceil()
}

/// How the leftover of some coins, what they hold beyond the amount, settles once it pays a
/// transaction with no change.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Settle {
    /// Change at or above its script's dust floor, at the rate asked: a second output.
    Change(u64),
    /// Change from 0 up to its floor, into the fee, as Bitcoin Core's wallet does.
    Fold(u64),
    /// Too little for a change output at the rate asked: what is above the no-change fee goes into
    /// the fee.
    Fit(u64),
    /// Into the fee would pass the network's 10,000 sat/vB, and too little is left for change at the
    /// rate asked: change at exactly its floor and the rest the fee, under the rate asked and at least
    /// what the node takes.
    FloorChange(u64),
}

/// Why some coins do not settle, in rising order so the worst of several is the one reported: a
/// smaller amount clears `Funds`, fewer coins `Size` and a lower rate `Limit`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
enum Short {
    Funds,
    /// No transaction of these coins fits the network's 100,000 vB, and every coin more grows it.
    Size,
    /// Into the fee would make `fee` on `vsize`, past the network's limit, and change at its floor
    /// would pay less than the node takes.
    Limit {
        fee: u64,
        vsize: u64,
    },
}

/// The one stop rule of the payment and the fee bump loops. `left` is the coins less the amount,
/// `with` and `without` the sizes with and without change, `floor` the change script's dust floor,
/// `fee` the fee asked for a size and `least` the least fee the node takes for it.
fn settle(
    left: u64,
    with: u64,
    without: u64,
    floor: u64,
    fee: impl Fn(u64) -> u64,
    least: impl Fn(u64) -> u64,
) -> Result<Settle, Short> {
    if without > MAX_STANDARD_VSIZE {
        return Err(Short::Size);
    }
    // A fee bump's rules 3 and 4 can ask a fee above the limit, and the node refuses that change.
    let change = left
        .checked_sub(fee(with))
        .filter(|_| with <= MAX_STANDARD_VSIZE && fee(with) <= MAX_FEE_RATE * with);
    if let Some(change) = change.filter(|c| *c >= floor) {
        return Ok(Settle::Change(change));
    }
    if left < fee(without) {
        return Err(Short::Funds);
    }
    if left > MAX_FEE_RATE * without {
        return match left.checked_sub(floor) {
            Some(paid)
                if with <= MAX_STANDARD_VSIZE
                    && (least(with)..=MAX_FEE_RATE * with).contains(&paid) =>
            {
                Ok(Settle::FloorChange(floor))
            }
            _ => Err(Short::Limit {
                fee: left,
                vsize: without,
            }),
        };
    }
    Ok(change.map_or(Settle::Fit(left - fee(without)), Settle::Fold))
}

/// Bitcoin Core's MAX_STANDARD_TX_WEIGHT, 400,000 WU: the network refuses a larger transaction as
/// non-standard.
const MAX_STANDARD_VSIZE: u64 = 100_000;

/// The coins a payment of `amount` to a `dest`-byte script at `rate` spends, as a count from the
/// front of `coins`, and how its leftover settles with change to `change`. `coins` are each a value
/// and how it is spent, in the order to try them. It adds one coin at a time and stops at the fewest
/// whose leftover is change or change below its floor, and else takes the fewest that settle another
/// way, so every payment the old four passes signed keeps its coins, change and fee. It refuses
/// nothing a set of these coins could pay: a set that pays a no-change transaction at the rate asked
/// has a prefix as short that pays at least as much.
fn select(
    coins: &[(u64, InputWeightPrediction)],
    amount: u64,
    rate: u64,
    dest: usize,
    change: &Script,
) -> Result<(usize, Settle), Short> {
    let (floor, scripts) = (dust_floor(change), [dest, change.len()]);
    let (mut have, mut spends, mut fallback, mut short) = (0u64, Vec::new(), None, Short::Funds);
    for (value, spend) in coins {
        have = have.saturating_add(*value);
        spends.push(*spend);
        let Some(left) = have.checked_sub(amount) else {
            continue;
        };
        let (with, without) = (vsize(&spends, &scripts), vsize(&spends, &scripts[..1]));
        match settle(
            left,
            with,
            without,
            floor,
            |size| rate * size,
            |size| MIN_FEE_RATE * size,
        ) {
            Ok(settled @ (Settle::Change(_) | Settle::Fold(_))) => {
                return Ok((spends.len(), settled));
            }
            Ok(settled) => {
                fallback.get_or_insert((spends.len(), settled));
            }
            Err(Short::Size) => return fallback.ok_or(short.max(Short::Size)),
            Err(why) => short = short.max(why),
        }
    }
    fallback.ok_or(short)
}

fn check_rate(rate_sat_vb: u64) -> Result<(), MobileSignerError> {
    if rate_sat_vb < MIN_FEE_RATE {
        return Err(MobileSignerError::InvalidPsbtJson(format!(
            "fee rate {rate_sat_vb} is below {MIN_FEE_RATE} sat/vB"
        )));
    }
    if rate_sat_vb > MAX_FEE_RATE {
        return Err(MobileSignerError::InvalidPsbtJson(format!(
            "fee rate {rate_sat_vb} is above the network's {MAX_FEE_RATE} sat/vB limit"
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
    source: &dyn ChainSource,
    wallet: &'w Wallet,
) -> Result<(Coins<'w>, u64), MobileSignerError> {
    let owners: Vec<(Chain, &OwnKey)> = wallet.funded().collect();
    let addresses: Vec<&Address> = owners.iter().map(|(_, k)| &k.address).collect();
    let lists = source.utxos(&addresses)?;
    let mut listed: Vec<(Utxo, &OwnKey, Chain)> = Vec::new();
    for ((chain, owner), list) in owners.iter().zip(lists) {
        for utxo in list {
            listed.push((utxo, owner, *chain));
        }
    }
    let own_change = own_change_transactions(source, wallet, &listed)?;
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
    source: &dyn ChainSource,
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
    let spends = source.spends_from(&pending)?;
    let own = wallet.addresses();
    let own: Vec<&str> = own.iter().map(String::as_str).collect();
    let mut signed = HashSet::new();
    for (txid, from) in pending.iter().zip(&spends) {
        if from.iter().any(|a| own.contains(&a.as_str())) {
            signed.insert(*txid);
        }
    }
    Ok(signed)
}

/// Refuses unless each coin of `selected` whose signature commits to no value, a Legacy one, is output
/// `vout` of the transaction `source` serves for its txid, paying the coin's key exactly the value the
/// selection used. A host that listed a Legacy coin at another value would otherwise get a valid payment
/// whose fee is not the one Review shows. One read per transaction, none for the other types. A read
/// that got no answer fails with `esplora get:`, which asking again may pass, and an answer that is not
/// that coin, or does not know its transaction, with `esplora scan:`, as a scan maps the same answers.
fn prove_values(
    source: &dyn ChainSource,
    selected: &[(&Utxo, &OwnKey)],
) -> Result<(), MobileSignerError> {
    let mut read: HashMap<Txid, Transaction> = HashMap::new();
    for (utxo, owner) in selected {
        if owner.address_type.commits_to_value() {
            continue;
        }
        let coin = OutPoint {
            txid: utxo.txid,
            vout: utxo.vout,
        };
        let lie = |why: String| {
            MobileSignerError::InvalidPsbtJson(format!("esplora scan: coin {coin}: {why}"))
        };
        let tx = match read.entry(utxo.txid) {
            Entry::Occupied(known) => known.into_mut(),
            Entry::Vacant(slot) => {
                let tx = source.raw_tx(&utxo.txid).map_err(|not| match not {
                    NotProven::Unreachable(why) => MobileSignerError::InvalidPsbtJson(format!(
                        "esplora get: coin {coin} could not be read: {why}"
                    )),
                    NotProven::Gone(why) | NotProven::Lie(why) => lie(why),
                    NotProven::Unconfirmed(summary) => lie(format!("{summary:?}")),
                })?;
                if tx.compute_txid() != utxo.txid {
                    return Err(lie(format!("the raw transaction is {}", tx.compute_txid())));
                }
                slot.insert(tx)
            }
        };
        let paid = tx
            .output
            .get(utxo.vout as usize)
            .ok_or_else(|| lie(format!("its transaction has {} outputs", tx.output.len())))?;
        if paid.script_pubkey != owner.address.script_pubkey() {
            return Err(lie(format!(
                "its transaction pays it to another script than {}",
                owner.address
            )));
        }
        if paid.value.to_sat() != utxo.value {
            return Err(lie(format!(
                "listed at {} sats, and its transaction pays {}",
                utxo.value,
                paid.value.to_sat()
            )));
        }
    }
    Ok(())
}

/// `selected` spent to `outputs`, once `prove_values` holds for them on `source`, each input signed with
/// its coin's own key, and checked to weigh exactly what `vsize` sized its fee for.
fn signed(
    source: &dyn ChainSource,
    selected: &[(&Utxo, &OwnKey)],
    outputs: Vec<TxOut>,
) -> Result<Transaction, MobileSignerError> {
    prove_values(source, selected)?;
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
    let prevouts = prevouts_of(selected);
    // Every input is signed on the unsigned transaction first: `SighashCache` writes no script_sig.
    let mut cache = SighashCache::new(&tx);
    let spends = selected
        .iter()
        .enumerate()
        .map(|(i, (_, owner))| {
            owner
                .address_type
                .spend(&secp, &mut cache, i, &prevouts, owner)
        })
        .collect::<Result<Vec<_>, _>>()?;
    let mut tx = tx;
    for (input, spend) in tx.input.iter_mut().zip(spends) {
        input.script_sig = spend.script_sig;
        input.witness = spend.witness;
    }
    let built_for = weight_of(selected, &tx.output);
    if tx.weight() != built_for {
        return Err(MobileSignerError::InvalidPsbtJson(format!(
            "signed weight {} is not the {} its fee was built for",
            tx.weight(),
            built_for
        )));
    }
    Ok(tx)
}

/// The weight `selected` paying `outputs` has once signed: each input at its type's `input_weight`,
/// which `full_size` and `spend` make exact. `signed` refuses bytes of any other weight, and a quote
/// sizes its node-rate check with it.
fn weight_of(selected: &[(&Utxo, &OwnKey)], outputs: &[TxOut]) -> bitcoin::Weight {
    predict_weight(
        selected
            .iter()
            .map(|(_, owner)| owner.address_type.input_weight()),
        outputs.iter().map(|o| o.script_pubkey.len()),
    )
}

/// A signature of `msg` by `key` whose DER encoding is 71 bytes, 72 with the sighash byte: a 33-byte r
/// and a 32-byte s, the size every ECDSA `AddressType::input_weight` assumes. About half of all
/// signatures come out shorter, and a transaction a vbyte under the size its fee was built for passed
/// the checks on that size and failed the node's on its bytes near 10,000 sat/vB. RFC 6979 first, as before, then a counter as extra
/// nonce data, so the same payment always signs the same bytes. 1,024 short ones in a row (odds under
/// 2^-1000) end in an error, never a loop without end.
pub(crate) fn full_size(
    secp: &Secp256k1<All>,
    msg: &Message,
    key: &SecretKey,
) -> Result<ecdsa::Signature, MobileSignerError> {
    (0u32..1_024)
        .map(|n| {
            if n == 0 {
                return secp.sign_ecdsa(msg, key);
            }
            let mut extra = [0u8; 32];
            extra[..4].copy_from_slice(&n.to_le_bytes());
            secp.sign_ecdsa_with_noncedata(msg, key, &extra)
        })
        .find(|sig| sig.serialize_der().len() == 71)
        .ok_or_else(|| {
            MobileSignerError::InvalidPsbtJson("no full-size signature in 1024 tries".into())
        })
}

/// What a payment sends: the amount the user typed, or the whole balance less the fee.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum SendAmount {
    Exact(u64),
    All,
}

/// What `signed` spends and pays for one payment, decided from one sync and one read of the coin
/// lists, before any proof or signature.
struct Plan<'c> {
    /// In input order: largest first for an exact amount, the listing order for send all.
    selected: Vec<(&'c Utxo, &'c OwnKey)>,
    /// The destination first, then change if any, as `prepared` reads them.
    outputs: Vec<TxOut>,
    /// Inputs less outputs, change that went into the fee included.
    fee_sats: u64,
    dust_change_sats: u64,
}

/// The refusals before any lookup, each kind in the order it has always made them, and the
/// destination they parsed. An exact amount: the destination, the amount against its dust floor, the
/// rate, then the amount with one coin's fee, which must fit u64. Send all: the rate, then the
/// destination.
fn checked(
    destination: &str,
    network: KeyNetwork,
    address_type: AddressType,
    amount: SendAmount,
    rate_sat_vb: u64,
) -> Result<Address, MobileSignerError> {
    let SendAmount::Exact(amount_sats) = amount else {
        check_rate(rate_sat_vb)?;
        return destination_on(destination, network);
    };
    let dest = destination_on(destination, network)?;
    let floor = dust_floor(&dest.script_pubkey());
    if amount_sats < floor {
        return Err(MobileSignerError::InvalidPsbtJson(format!(
            "amount {amount_sats} is below the network's {floor} sat dust floor"
        )));
    }
    check_rate(rate_sat_vb)?;
    need(
        address_type.input_weight(),
        dest.script_pubkey().len(),
        amount_sats,
        rate_sat_vb,
        1,
    )?;
    Ok(dest)
}

/// The amount and the fee of `inputs` coins spent as `own` paying one output to a `dest_len`-byte
/// script: the smallest payment of them. The rate is at most 10,000, so the fee fits u64; the sum may
/// not.
fn need(
    own: InputWeightPrediction,
    dest_len: usize,
    amount_sats: u64,
    rate_sat_vb: u64,
    inputs: usize,
) -> Result<u64, MobileSignerError> {
    let fee = rate_sat_vb * vsize(&vec![own; inputs.max(1)], &[dest_len]);
    amount_sats.checked_add(fee).ok_or_else(|| {
        MobileSignerError::InvalidPsbtJson("amount plus fee does not fit u64".into())
    })
}

/// What `amount` to `destination` at `rate_sat_vb` spends and pays from the wallet `words` and
/// `passphrase` open, handed to `finish`: `checked`, then `check_network`, one `synced` (which may
/// share a sync), one `spendable_coins` and the plan. The only path to a plan, so nothing can decide a
/// payment the signer would not.
#[allow(clippy::too_many_arguments)]
fn planned<T>(
    source: &dyn ChainSource,
    words: &[String],
    passphrase: &str,
    network: KeyNetwork,
    address_type: AddressType,
    destination: &str,
    amount: SendAmount,
    rate_sat_vb: u64,
    clock: &dyn Fn() -> SystemTime,
    store: &ScanStore,
    finish: impl FnOnce(Plan<'_>) -> Result<T, MobileSignerError>,
) -> Result<T, MobileSignerError> {
    let dest = checked(destination, network, address_type, amount, rate_sat_vb)?;
    check_network(network)?;
    let wallet = complete(synced(
        source,
        words,
        passphrase,
        network,
        address_type,
        clock,
        store,
    )?)?;
    let (spendable, have) = spendable_coins(source, &wallet)?;
    let own = address_type.input_weight();
    let plan = match amount {
        SendAmount::Exact(amount_sats) => plan_payment(
            &spendable,
            have,
            wallet.next_unused(Chain::Internal).address.script_pubkey(),
            own,
            &dest,
            amount_sats,
            rate_sat_vb,
        )?,
        SendAmount::All => plan_send_all(&spendable, have, own, &dest, rate_sat_vb)?,
    };
    finish(plan)
}

/// The coins an exact amount spends and where its leftover goes. `select` picks the coins and how what
/// they hold beyond the amount settles. Change the network would refuse as an output, or too little
/// to pay for its own, goes into the fee, as Bitcoin Core's wallet does, and the plan names it for
/// Review. Pure: no lookup and no key use.
fn plan_payment<'c>(
    spendable: &'c [(Utxo, &'c OwnKey)],
    have: u64,
    change_script: ScriptBuf,
    own: InputWeightPrediction,
    dest: &Address,
    amount_sats: u64,
    rate_sat_vb: u64,
) -> Result<Plan<'c>, MobileSignerError> {
    let refuse = |what: String| Err(MobileSignerError::InvalidPsbtJson(what));
    let dest_len = dest.script_pubkey().len();
    // Largest first, ties by outpoint: which coins pay never depends on the host's listing order,
    // and dust is touched only once the big coins fall short.
    let mut every: Vec<&(Utxo, &OwnKey)> = spendable.iter().collect();
    every.sort_by(|(a, _), (b, _)| {
        b.value
            .cmp(&a.value)
            .then(a.txid.cmp(&b.txid))
            .then(a.vout.cmp(&b.vout))
    });
    // Confirmed coins first. The wallet's own unconfirmed change joins only when they fall short, and
    // then every coin is tried in the same order.
    let walk = |coins: &[&(Utxo, &OwnKey)]| {
        let spends: Vec<(u64, InputWeightPrediction)> =
            coins.iter().map(|(u, _)| (u.value, own)).collect();
        select(&spends, amount_sats, rate_sat_vb, dest_len, &change_script)
    };
    let confirmed: Vec<&(Utxo, &OwnKey)> =
        every.iter().copied().filter(|(u, _)| u.confirmed).collect();
    let picked = match walk(&confirmed) {
        Ok(found) => Ok((confirmed, found)),
        Err(first) => walk(&every)
            .map(|found| (every, found))
            .map_err(|second| first.max(second)),
    };
    let (coins, (count, settled)) = match picked {
        Ok(picked) => picked,
        Err(Short::Funds) => {
            let need = need(own, dest_len, amount_sats, rate_sat_vb, spendable.len())?;
            return refuse(format!(
                "insufficient funds: have {have} confirmed, need {need}"
            ));
        }
        Err(Short::Size) => {
            return refuse(format!(
                "too many coins: this amount needs more than the network's {MAX_STANDARD_VSIZE} vB, send a smaller amount"
            ));
        }
        Err(Short::Limit { fee, vsize: size }) => return refuse(above_limit(fee, size)),
    };
    let selected: Vec<(&Utxo, &OwnKey)> = coins
        .into_iter()
        .take(count)
        .map(|(u, k)| (u, *k))
        .collect();
    let left = selected.iter().map(|(u, _)| u.value).sum::<u64>() - amount_sats;
    let (change, dust_change_sats) = match settled {
        Settle::Change(change) | Settle::FloorChange(change) => (Some(change), 0),
        Settle::Fold(dust) | Settle::Fit(dust) => (None, dust),
    };
    let mut outputs = vec![TxOut {
        value: Amount::from_sat(amount_sats),
        script_pubkey: dest.script_pubkey(),
    }];
    outputs.extend(change.map(|value| TxOut {
        value: Amount::from_sat(value),
        script_pubkey: change_script,
    }));
    Ok(Plan {
        selected,
        outputs,
        fee_sats: left - change.unwrap_or(0),
        dust_change_sats,
    })
}

/// Every coin in listing order paying `dest` the whole balance less the fee, in one output and no
/// change. The fee is the size of that transaction times the rate. Pure.
fn plan_send_all<'c>(
    spendable: &'c [(Utxo, &'c OwnKey)],
    have: u64,
    own: InputWeightPrediction,
    dest: &Address,
    rate_sat_vb: u64,
) -> Result<Plan<'c>, MobileSignerError> {
    let refuse = |what: String| Err(MobileSignerError::InvalidPsbtJson(what));
    // One input and one output is the smallest this transaction can be, so a wallet with nothing
    // to spend is short by that fee and the destination's dust floor.
    let size = vsize(
        &vec![own; spendable.len().max(1)],
        &[dest.script_pubkey().len()],
    );
    let fee_sats = rate_sat_vb * size;
    let floor = dust_floor(&dest.script_pubkey());
    if spendable.is_empty() {
        return refuse(format!(
            "insufficient funds: have {have} confirmed, need {}",
            fee_sats + floor
        ));
    }
    if size > MAX_STANDARD_VSIZE {
        return refuse(format!(
            "too many coins: the whole balance needs more than the network's {MAX_STANDARD_VSIZE} vB, send a fixed amount"
        ));
    }
    let amount_sats = match have.checked_sub(fee_sats) {
        Some(amount) if amount >= floor => amount,
        _ => {
            return refuse(format!(
                "the whole balance {have} less the fee {fee_sats} is below the network's {floor} sat dust floor"
            ));
        }
    };
    Ok(Plan {
        selected: spendable.iter().map(|(u, k)| (u, *k)).collect(),
        outputs: vec![TxOut {
            value: Amount::from_sat(amount_sats),
            script_pubkey: dest.script_pubkey(),
        }],
        fee_sats,
        dust_change_sats: 0,
    })
}

/// The signer's finish: `signed`, which proves each Legacy coin's value from its parent, signs, and
/// refuses bytes that do not weigh what the fee was built for, then the node's rate on the signed
/// bytes.
fn signed_plan(source: &dyn ChainSource, plan: Plan<'_>) -> Result<Signed, MobileSignerError> {
    let tx = signed(source, &plan.selected, plan.outputs)?;
    check_node_rate(plan.fee_sats, tx.vsize() as u64)
        .map_err(MobileSignerError::InvalidPsbtJson)?;
    Ok(Signed {
        tx,
        fee_sats: plan.fee_sats,
        dust_change_sats: plan.dust_change_sats,
        amount_cut_sats: 0,
    })
}

/// A payment from the coins of every used address, each input signed with its own key, any change
/// paid to the first unused internal (change) address, not yet broadcast, with the fee it pays.
/// The amount, the rate, the destination and the sum of the amount and the smallest payment's fee
/// (which must fit u64) are checked before any network call. A fee above the amount is signed,
/// because the fee is paid on top and the destination still gets the amount. Every refusal is an
/// error, never a changed payment. The used-address set may come from a sync `synced` shares.
#[allow(clippy::too_many_arguments)]
fn signed_payment(
    source: &dyn ChainSource,
    words: &[String],
    passphrase: &str,
    network: KeyNetwork,
    address_type: AddressType,
    destination: &str,
    amount_sats: u64,
    rate_sat_vb: u64,
    clock: &dyn Fn() -> SystemTime,
    store: &ScanStore,
) -> Result<Signed, MobileSignerError> {
    planned(
        source,
        words,
        passphrase,
        network,
        address_type,
        destination,
        SendAmount::Exact(amount_sats),
        rate_sat_vb,
        clock,
        store,
        |plan| signed_plan(source, plan),
    )
}

/// The whole balance to `destination` in one output and no change: every coin `signed_payment`
/// would consider, the fee the size of that transaction times the rate, and the amount what is
/// left, not yet broadcast, with the fee it pays. The rate and the destination are checked before
/// any network call; the fee and the amount once the coins are known. Every refusal is an error,
/// never a changed payment.
#[allow(clippy::too_many_arguments)]
fn signed_send_all(
    source: &dyn ChainSource,
    words: &[String],
    passphrase: &str,
    network: KeyNetwork,
    address_type: AddressType,
    destination: &str,
    rate_sat_vb: u64,
    clock: &dyn Fn() -> SystemTime,
    store: &ScanStore,
) -> Result<Signed, MobileSignerError> {
    planned(
        source,
        words,
        passphrase,
        network,
        address_type,
        destination,
        SendAmount::All,
        rate_sat_vb,
        clock,
        store,
        |plan| signed_plan(source, plan),
    )
}

/// The quote's finish: the plan's figures, nothing proven or signed, refused where the signed bytes
/// would be, past the node's 10,000 sat/vB on the weight `signed` proves the bytes have.
#[allow(clippy::too_many_arguments)]
fn quoted(
    source: &dyn ChainSource,
    words: &[String],
    passphrase: &str,
    network: KeyNetwork,
    address_type: AddressType,
    destination: &str,
    amount: SendAmount,
    rate_sat_vb: u64,
    clock: &dyn Fn() -> SystemTime,
    store: &ScanStore,
) -> Result<QuotedSend, MobileSignerError> {
    planned(
        source,
        words,
        passphrase,
        network,
        address_type,
        destination,
        amount,
        rate_sat_vb,
        clock,
        store,
        |plan| {
            let vsize = weight_of(&plan.selected, &plan.outputs).to_vbytes_ceil();
            check_node_rate(plan.fee_sats, vsize).map_err(MobileSignerError::InvalidPsbtJson)?;
            Ok(QuotedSend {
                fee_sats: plan.fee_sats,
                amount_sats: plan.outputs[0].value.to_sat(),
                dust_change_sats: plan.dust_change_sats,
                vsize,
            })
        },
    )
}

/// What replaces a signed payment of unknown outcome. Both spend every coin of that payment, so at most
/// one of the two can ever confirm.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Replacement {
    /// The same destination and amount at a higher fee, change to the same change address or, for a
    /// payment with none, to the next unused one. The fee comes out of the amount only when a payment
    /// with no change has no confirmed coin to keep it.
    FeeBump,
    /// Every coin back to this wallet in one output, at a higher fee.
    Cancel,
}

/// Scan, then sign a fee bump of `earlier_hex`, the signed payment the app's guard keeps, and post
/// nothing. It spends every input of that payment in its order, so at most one of the two can ever
/// confirm, and its first output stays as it was. More confirmed coins join only when the earlier
/// inputs cannot pay the new fee, change goes to the same change address, or for a payment with no
/// change to the next unused one, as Bitcoin Core's bumpfee does, and change below its dust floor goes
/// into the fee. Where that would pass 10,000 sat/vB on the bump's size, the change stays at exactly its
/// floor and the fee is the rest, under the rate asked but within rules 3, 4 and 6, so the amount is
/// kept. Only a payment with no change (a send all, or one whose change went into its fee)
/// whose amount no confirmed coin can keep gets one output worth its own inputs less the new fee, and
/// `amount_cut_sats` says how much less the destination gets. The fee meets BIP-125 rules 3, 4 and 6
/// against that payment. It is at
/// least that payment's fee plus the new size at 1 sat/vB, at a rate strictly above its rate, so the
/// rate can be above `fee_rate_sat_vb`. Refuses, before any lookup, bytes that are not one transaction with inputs and one
/// or two outputs whose first is at least its dust floor, and a rate outside 1 to 10,000 sat/vB. After the scan
/// it refuses, after `replace: `, a second output that is not this wallet's change, inputs that are not
/// all coins this wallet can spend now (`coins spent:`, most likely because that payment is out), an
/// input without this wallet's own SIGHASH_ALL signature for its coin, a rate that would pass 10,000
/// sat/vB, on the rate asked or on the signed bytes, what is left below the dust floor of its
/// output, and a bump above the network's 100,000 vB (`too many coins:`), and without `replace: ` a
/// payment with change whose amount the confirmed coins cannot keep
/// (`insufficient funds:`). The same scan as `hot_prepare_send_at_rate`, so call it off the main thread.
#[uniffi::export]
pub fn hot_prepare_fee_bump(
    mnemonic_words: Vec<String>,
    passphrase: String,
    network: KeyNetwork,
    address_type: AddressType,
    earlier_hex: String,
    fee_rate_sat_vb: u64,
) -> Result<PreparedSend, MobileSignerError> {
    Ok(prepared(signed_replacement(
        &EsploraSource(&testnet4()),
        &mnemonic_words,
        &passphrase,
        network,
        address_type,
        &earlier_hex,
        fee_rate_sat_vb,
        Replacement::FeeBump,
        &SystemTime::now,
        &SCAN_STORE,
    )?))
}

/// Scan, then sign a cancel of `earlier_hex`, the signed payment the app's guard keeps, and post
/// nothing. It spends exactly that payment's inputs in its order and pays them back to this wallet in
/// one output, to its change address when it had change and else to the next unused one. The fee follows the rules of
/// `hot_prepare_fee_bump` for that one output, and `amount_sats` is what comes back. Until a block holds
/// one of the two, the earlier payment may still confirm instead. The same refusals as
/// `hot_prepare_fee_bump`, so call it off the main thread.
#[uniffi::export]
pub fn hot_prepare_cancel(
    mnemonic_words: Vec<String>,
    passphrase: String,
    network: KeyNetwork,
    address_type: AddressType,
    earlier_hex: String,
    fee_rate_sat_vb: u64,
) -> Result<PreparedSend, MobileSignerError> {
    Ok(prepared(signed_replacement(
        &EsploraSource(&testnet4()),
        &mnemonic_words,
        &passphrase,
        network,
        address_type,
        &earlier_hex,
        fee_rate_sat_vb,
        Replacement::Cancel,
        &SystemTime::now,
        &SCAN_STORE,
    )?))
}

/// How many of the largest spare coins a fee bump tries every set of: 65,535 sets at most, under the
/// 100,000 tries Bitcoin Core's wallet allows its own coin search.
const BUMP_SEARCH_COINS: usize = 16;

#[cfg(test)]
thread_local! {
    static SETS_TRIED: std::cell::Cell<u32> = const { std::cell::Cell::new(0) };
}

/// A `kind` replacement of `earlier_hex`, signed and not yet broadcast, with the fee it pays, the change
/// it put into that fee and, for a fee bump, what it cut from the destination's amount. The bytes,
/// their shape and the rate are checked before any network call, the change, the coins and the earlier
/// fee after the scan, and the signed transaction once more before it is returned. Every refusal is an
/// error, never a changed payment.
#[allow(clippy::too_many_arguments)]
fn signed_replacement(
    source: &dyn ChainSource,
    words: &[String],
    passphrase: &str,
    network: KeyNetwork,
    address_type: AddressType,
    earlier_hex: &str,
    rate_sat_vb: u64,
    kind: Replacement,
    clock: &dyn Fn() -> SystemTime,
    store: &ScanStore,
) -> Result<Signed, MobileSignerError> {
    let fail = |what: String| MobileSignerError::InvalidPsbtJson(format!("replace: {what}"));
    let not_ours = |why: &str| fail(format!("not this wallet's payment: {why}"));
    let earlier: Transaction =
        deserialize_hex(earlier_hex).map_err(|_| not_ours("not one transaction"))?;
    let spent: Vec<OutPoint> = earlier.input.iter().map(|i| i.previous_output).collect();
    if spent.is_empty() || !(1..=2).contains(&earlier.output.len()) {
        return Err(not_ours("it needs inputs and one or two outputs"));
    }
    // This wallet never signs an output below its floor, and a bump keeps the first output as it is.
    if earlier.output[0].value.to_sat() < dust_floor(&earlier.output[0].script_pubkey) {
        return Err(not_ours("its first output is below the dust limit"));
    }
    if (1..spent.len()).any(|i| spent[..i].contains(&spent[i])) {
        return Err(not_ours("it spends a coin twice"));
    }
    check_rate(rate_sat_vb)?;
    check_network(network)?;
    let wallet = complete(synced(
        source,
        words,
        passphrase,
        network,
        address_type,
        clock,
        store,
    )?)?;
    // Every address of the change chain the scan derived, which is where this wallet puts change.
    let own: Vec<ScriptBuf> = wallet.keys[Chain::Internal as usize]
        .iter()
        .map(|key| key.address.script_pubkey())
        .collect();
    let change = earlier.output.get(1).map(|o| o.script_pubkey.clone());
    if change.as_ref().is_some_and(|script| !own.contains(script)) {
        return Err(not_ours("its second output is not this wallet's change"));
    }
    let (spendable, _) = spendable_coins(source, &wallet)?;
    let mut selected: Vec<(&Utxo, &OwnKey)> = Vec::new();
    for outpoint in &spent {
        let (utxo, owner) = spendable
            .iter()
            .find(|(u, _)| u.txid == outpoint.txid && u.vout == outpoint.vout)
            .ok_or_else(|| {
                fail(format!(
                    "coins spent: {outpoint} is not a coin this wallet can spend"
                ))
            })?;
        selected.push((utxo, *owner));
    }
    // The bytes come from app storage, where anything could have written them, and the card names no
    // address, so nothing below may read them until this wallet's own signatures vouch for them.
    if !signed_here(&earlier, &selected) {
        return Err(not_ours("it is not signed by this wallet"));
    }
    let total = selected
        .iter()
        .try_fold(0u64, |sum, (u, _)| sum.checked_add(u.value))
        .ok_or_else(coins_overflow)?;
    let old_fee = earlier
        .output
        .iter()
        .try_fold(0u64, |sum, o| sum.checked_add(o.value.to_sat()))
        .and_then(|paid| total.checked_sub(paid))
        .ok_or_else(|| not_ours("it pays out more than its coins hold"))?;
    let old_vsize = earlier.vsize() as u64;
    // BIP-125 rule 6 asks a rate strictly above the earlier one, here in whole sat/vB.
    let rate = rate_sat_vb.max(old_fee / old_vsize + 1);
    if rate > MAX_FEE_RATE {
        return Err(fail(format!(
            "rate {rate} is above the network's {MAX_FEE_RATE} sat/vB limit"
        )));
    }
    // Rules 3 and 4 ask at least the earlier fee plus the new size at the 1 sat/vB incremental relay
    // rate. The size is the signed size, so the real rate is at least `rate`.
    let fee = |size: u64| (rate * size).max(old_fee.saturating_add(size));
    let own_spend = address_type.input_weight();
    let fee_for = |inputs: usize, scripts: &[usize]| fee(vsize(&vec![own_spend; inputs], scripts));
    // The least fee the node takes at a size: rules 3 and 4, and rule 6 a whole sat/kvB above the
    // earlier rate, as Bitcoin Core truncates both rates to whole sat/kvB before it compares them.
    let least = |size: u64| {
        let kvb = u128::from(old_fee) * 1000 / u128::from(old_vsize) + 1;
        let above = (kvb * u128::from(size)).div_ceil(1000);
        old_fee
            .saturating_add(size)
            .max(u64::try_from(above).unwrap_or(u64::MAX))
    };
    // One output to `script`, worth `have` less the fee of a transaction of `inputs` and that output.
    let one = |inputs: usize, have: u64, script: ScriptBuf| {
        let fee = fee_for(inputs, &[script.len()]);
        let floor = dust_floor(&script);
        match have.checked_sub(fee) {
            Some(value) if value >= floor => Ok(vec![TxOut {
                value: Amount::from_sat(value),
                script_pubkey: script,
            }]),
            _ => Err(fail(format!(
                "what is left, {have} less the fee {fee}, is below the network's {floor} sat dust floor"
            ))),
        }
    };
    let destination = &earlier.output[0];
    let change =
        change.unwrap_or_else(|| wallet.next_unused(Chain::Internal).address.script_pubkey());
    let (outputs, have, dust_change_sats) = match kind {
        Replacement::Cancel => (one(selected.len(), total, change)?, total, 0),
        Replacement::FeeBump => {
            let amount = destination.value.to_sat();
            let scripts = [destination.script_pubkey.len(), change.len()];
            // BIP-125 rule 2 lets in a coin the earlier payment did not spend only once a block
            // holds it. Largest first, ties by outpoint, as `signed_payment` picks.
            let mut more: Vec<&(Utxo, &OwnKey)> = spendable
                .iter()
                .filter(|(u, _)| {
                    u.confirmed
                        && !spent.contains(&OutPoint {
                            txid: u.txid,
                            vout: u.vout,
                        })
                })
                .collect();
            more.sort_by(|(a, _), (b, _)| {
                b.value
                    .cmp(&a.value)
                    .then(a.txid.cmp(&b.txid))
                    .then(a.vout.cmp(&b.vout))
            });
            let change_floor = dust_floor(&change);
            let mut short = Short::Funds;
            // The walk keeps every coin it adds. Near the limit the sets that pay leave a leftover in a
            // narrow band no walk finds every time, so only when the walk finds no bump does a search try
            // the sets of the largest spare coins.
            let (mut pending, mut searched, mut have) = (more.iter(), false, total);
            loop {
                let spends = vec![own_spend; selected.len()];
                let (with, without) = (vsize(&spends, &scripts), vsize(&spends, &scripts[..1]));
                // The earlier coins hold at least its outputs, so `have` covers the amount.
                let left = have.saturating_sub(amount);
                match settle(left, with, without, change_floor, &fee, &least) {
                    Ok(Settle::Change(rest) | Settle::FloorChange(rest)) => {
                        let back = TxOut {
                            value: Amount::from_sat(rest),
                            script_pubkey: change,
                        };
                        break (vec![destination.clone(), back], have, 0);
                    }
                    // Change below its dust floor goes into the fee, as Bitcoin Core's bumpfee does.
                    // None is reported when even a change output could not be paid for.
                    Ok(Settle::Fold(dust)) => break (vec![destination.clone()], have, dust),
                    Ok(Settle::Fit(_)) => break (vec![destination.clone()], have, 0),
                    Err(why) => short = short.max(why),
                }
                let Some((utxo, owner)) = pending.next() else {
                    if !searched {
                        searched = true;
                        let spares = &more[..more.len().min(BUMP_SEARCH_COINS)];
                        // Every coin spends as `own_spend`, so a set's sizes depend only on its count.
                        let sizes: Vec<(u64, u64)> = (0..=spares.len())
                            .map(|count| {
                                let spends = vec![own_spend; spent.len() + count];
                                (vsize(&spends, &scripts), vsize(&spends, &scripts[..1]))
                            })
                            .collect();
                        // A set that settles holds its no-change fee, and no set of a count holds more
                        // than the largest coins of that count.
                        let hopeless = (1..=spares.len()).all(|count| {
                            let most =
                                total + spares[..count].iter().map(|(u, _)| u.value).sum::<u64>();
                            most.saturating_sub(amount) < fee(sizes[count].1)
                        });
                        let coins = |set: u32| {
                            (0..spares.len())
                                .filter(move |i| set >> i & 1 == 1)
                                .map(|i| spares[i])
                        };
                        // The walk summed every spare coin with checks, so no set of them overflows.
                        let holds =
                            |set: u32| total + coins(set).map(|(u, _)| u.value).sum::<u64>();
                        let mut sets: Vec<u32> = if hopeless {
                            Vec::new()
                        } else {
                            (1..1 << spares.len()).collect()
                        };
                        // Fewest coins first, then in lexicographic order of the coins largest first.
                        sets.sort_by_key(|set| {
                            (set.count_ones(), std::cmp::Reverse(set.reverse_bits()))
                        });
                        let found = sets.into_iter().find(|&set| {
                            #[cfg(test)]
                            SETS_TRIED.with(|n| n.set(n.get() + 1));
                            let (with, without) = sizes[set.count_ones() as usize];
                            let left = holds(set).saturating_sub(amount);
                            settle(left, with, without, change_floor, &fee, &least).is_ok()
                        });
                        // The walk's next turn settles that set and signs it.
                        if let Some(set) = found {
                            selected.truncate(spent.len());
                            selected.extend(coins(set).map(|(utxo, owner)| (utxo, *owner)));
                            have = holds(set);
                            continue;
                        }
                    }
                    // A payment with no change (a send all, or change that went into the fee) whose
                    // amount no coin can keep takes the new fee from the amount, from its own coins only.
                    if earlier.output.len() == 1 {
                        selected.truncate(spent.len());
                        let script = destination.script_pubkey.clone();
                        break (one(spent.len(), total, script)?, total, 0);
                    }
                    return Err(match short {
                        Short::Limit { fee: paid, vsize } => fail(above_limit(paid, vsize)),
                        Short::Size => fail(format!(
                            "too many coins: no fee bump fits the network's {MAX_STANDARD_VSIZE} vB"
                        )),
                        Short::Funds => MobileSignerError::InvalidPsbtJson(format!(
                            "insufficient funds: have {have} confirmed, need {}",
                            amount.saturating_add(fee(with))
                        )),
                    });
                };
                selected.push((utxo, *owner));
                have = have.checked_add(utxo.value).ok_or_else(coins_overflow)?;
            }
        }
    };
    let tx = signed(source, &selected, outputs)?;
    let fee = tx
        .output
        .iter()
        .try_fold(have, |left, o| left.checked_sub(o.value.to_sat()))
        .unwrap_or(0);
    let scripts: Vec<usize> = tx.output.iter().map(|o| o.script_pubkey.len()).collect();
    let estimated = vsize(&vec![own_spend; tx.input.len()], &scripts);
    let first = &tx.output[0].script_pubkey;
    let checks = [
        (
            "it spends every earlier coin, in order",
            inputs_of(&tx).starts_with(&spent),
        ),
        (
            "a cancel spends no other coin",
            kind == Replacement::FeeBump || tx.input.len() == spent.len(),
        ),
        (
            "a fee bump pays the earlier destination first",
            kind == Replacement::Cancel || *first == destination.script_pubkey,
        ),
        (
            "a cancel pays one output to this wallet",
            kind == Replacement::FeeBump || (tx.output.len() == 1 && own.contains(first)),
        ),
        ("its fee is above the earlier fee", fee > old_fee),
        (
            "its fee pays the earlier fee and its own size at 1 sat/vB",
            u128::from(fee) >= u128::from(old_fee) + u128::from(estimated),
        ),
        (
            "its rate is above the earlier rate",
            u128::from(fee) * u128::from(old_vsize) > u128::from(old_fee) * u128::from(estimated),
        ),
    ];
    if let Some((what, _)) = checks.iter().find(|(_, holds)| !holds) {
        return Err(fail(format!("check failed: {what}")));
    }
    check_node_rate(fee, tx.vsize() as u64).map_err(fail)?;
    let amount_cut_sats = match kind {
        Replacement::FeeBump => destination
            .value
            .to_sat()
            .saturating_sub(tx.output[0].value.to_sat()),
        Replacement::Cancel => 0,
    };
    Ok(Signed {
        tx,
        fee_sats: fee,
        dust_change_sats,
        amount_cut_sats,
    })
}

fn inputs_of(tx: &Transaction) -> Vec<OutPoint> {
    tx.input.iter().map(|i| i.previous_output).collect()
}

/// Whether every input `i` of `tx` is spent exactly as `signed` spends `selected[i]`, by
/// `AddressType::spent_by` of that coin's key: the script_sig and witness shape `signed` writes and a
/// signature by that key that commits to every input and output, so none has changed.
fn signed_here(tx: &Transaction, selected: &[(&Utxo, &OwnKey)]) -> bool {
    let prevouts = prevouts_of(selected);
    let mut cache = SighashCache::new(tx);
    tx.input.len() == selected.len()
        && selected
            .iter()
            .enumerate()
            .all(|(i, (_, owner))| owner.address_type.spent_by(&mut cache, i, &prevouts, owner))
}

/// The coin each input of `selected` spends, as the sighashes read it.
fn prevouts_of(selected: &[(&Utxo, &OwnKey)]) -> Vec<TxOut> {
    selected
        .iter()
        .map(|(utxo, owner)| TxOut {
            value: Amount::from_sat(utxo.value),
            script_pubkey: owner.address.script_pubkey(),
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

type FeeCache = Mutex<Option<(FeeTiers, Instant)>>;
static FEE_CACHE: FeeCache = Mutex::new(None);

/// The fee tiers for the Send screen, from the testnet4 host in use. One network call in the usual
/// case, answered from memory for five minutes after. Never fails: when no host answers or the
/// answer is unusable the fixed tiers 4, 8 and 15 come back with `live` false.
#[uniffi::export]
pub fn hot_fee_tiers(network: KeyNetwork) -> FeeTiers {
    fee_tiers_from(&TESTNET4, network, Instant::now(), &FEE_CACHE)
}

fn fee_tiers_from(
    source: &dyn ChainSource,
    network: KeyNetwork,
    now: Instant,
    cache: &FeeCache,
) -> FeeTiers {
    if check_network(network).is_err() {
        return DEFAULT_FEE_TIERS;
    }
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
    let tiers = source.fee_tiers().unwrap_or(DEFAULT_FEE_TIERS);
    *cached = Some((tiers.clone(), now));
    tiers
}

#[cfg(test)]
mod tests {
    use std::cell::{Cell, RefCell};
    use std::collections::{HashMap, HashSet, VecDeque};
    use std::sync::Mutex;

    use serde_json::Value;

    use super::*;
    use crate::chain::*;
    use crate::scan_store::tests::Folder;
    use bitcoin::bip32::DerivationPath;
    use bitcoin::sighash::EcdsaSighashType;
    use std::fs;

    const OWN_SPEND: InputWeightPrediction = AddressType::NativeSegwit.input_weight();

    fn abandon_about() -> Vec<String> {
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
            .split_whitespace()
            .map(str::to_string)
            .collect()
    }

    /// A transport that answers every GET with `.0` and posts nothing.
    struct Reads<'a>(&'a (dyn Fn(&str) -> Result<String, MobileSignerError> + Sync));

    impl Transport for Reads<'_> {
        fn get(&self, url: &str) -> Result<String, MobileSignerError> {
            (self.0)(url)
        }

        fn broadcast(&self, _: &str, _: &str) -> Result<String, MobileSignerError> {
            unreachable!("a test that only reads posts nothing")
        }
    }

    /// A transport whose every read and host choice fails with `text`, as `Http` does while every host is
    /// under a request limit or down.
    struct Refusing(&'static str);

    impl Transport for Refusing {
        fn get(&self, _: &str) -> Result<String, MobileSignerError> {
            Err(MobileSignerError::InvalidPsbtJson(self.0.into()))
        }

        fn broadcast(&self, _: &str, _: &str) -> Result<String, MobileSignerError> {
            unreachable!("a test that only reads posts nothing")
        }

        fn operator(&self) -> Result<Operator, MobileSignerError> {
            Err(MobileSignerError::InvalidPsbtJson(self.0.into()))
        }
    }

    /// A transport that posts with `post` to `host` and looks up with `lookup`, pausing for nothing,
    /// and reads nothing.
    struct Posts<'a> {
        host: Result<&'static str, &'static str>,
        post: &'a (dyn Fn(&str, &str) -> Answer + Sync),
        lookup: &'a (dyn Fn(&str, &str) -> Answer + Sync),
    }

    impl Transport for Posts<'_> {
        fn get(&self, url: &str) -> Result<String, MobileSignerError> {
            unreachable!("a test that only posts asked for {url}")
        }

        fn broadcast(&self, hex: &str, txid: &str) -> Result<String, MobileSignerError> {
            let host = self
                .host
                .map_err(|why| MobileSignerError::InvalidPsbtJson(why.into()));
            broadcast_on(host, self.post, self.lookup, &|| {}, hex, txid)
        }
    }

    /// The fixtures' wallet address, for a URL that the scripted answers below ignore.
    fn wallet_address() -> Address {
        Address::from_str(WALLET).unwrap().assume_checked()
    }

    /// `json` read by `EsploraSource` as one address's summary.
    fn parse_address_stats(json: &str) -> Result<Summary, MobileSignerError> {
        EsploraSource(&Reads(&|_| Ok(json.to_string())))
            .summaries(&[&wallet_address()])
            .map(|mut found| found.remove(0))
    }

    /// `json` read by `EsploraSource` as one address's coin list.
    fn parse_utxos(json: &str) -> Result<Vec<Utxo>, MobileSignerError> {
        EsploraSource(&Reads(&|_| Ok(json.to_string())))
            .utxos(&[&wallet_address()])
            .map(|mut lists| lists.remove(0))
    }

    /// The page `txs` of one address and the tip read by `EsploraSource`, then the page as the wallet
    /// reads it.
    fn history_from(txs: &str, tip: &str, own: &[&str]) -> Result<Vec<HotTx>, MobileSignerError> {
        let answer = |url: &str| {
            let body = if url.ends_with("/blocks/tip/height") {
                tip
            } else {
                txs
            };
            Ok(body.to_string())
        };
        let (pages, height) = EsploraSource(&Reads(&answer)).history(&[&wallet_address()])?;
        hot_page(&pages[0], height, own)
    }

    /// The `/tx/{txid}` answer `json` and the tip read by `EsploraSource`, then detailed as the wallet
    /// details it.
    fn detail_from(
        json: &str,
        tip: &str,
        txid: &Txid,
        own: &HashMap<String, Chain>,
    ) -> Result<HotTxDetail, MobileSignerError> {
        let answer = |url: &str| {
            let body = if url.ends_with("/blocks/tip/height") {
                tip
            } else {
                json
            };
            Ok(body.to_string())
        };
        let (tx, height) = EsploraSource(&Reads(&answer))
            .tx(txid)?
            .expect("a scripted answer is never a 404");
        hot_detail(&tx, height, txid, own)
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
            let account = Account::new(
                &abandon_about(),
                passphrase,
                Network::Testnet4,
                AddressType::NativeSegwit,
            )
            .unwrap();
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
                    AddressType::NativeSegwit,
                    index,
                );
                assert_eq!(public.unwrap(), expected, "{passphrase:?} 0/{index}");
            }
        }
    }

    #[test]
    fn address_check_matches_what_hot_prepare_send_accepts() {
        let own = hot_receive_address(
            abandon_about(),
            String::new(),
            KeyNetwork::Test,
            AddressType::NativeSegwit,
            0,
        )
        .unwrap();
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
        let a = hot_receive_address(
            abandon_about(),
            "a".into(),
            KeyNetwork::Test,
            AddressType::NativeSegwit,
            0,
        )
        .unwrap();
        let b = hot_receive_address(
            abandon_about(),
            "b".into(),
            KeyNetwork::Test,
            AddressType::NativeSegwit,
            0,
        )
        .unwrap();
        let none = hot_receive_address(
            abandon_about(),
            String::new(),
            KeyNetwork::Test,
            AddressType::NativeSegwit,
            0,
        )
        .unwrap();
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

    // A routine sync asks a pending address again until a block takes its transaction.
    #[test]
    fn a_summary_says_whether_the_mempool_holds_a_transaction() {
        let stats = |chain: (u64, u64, u64), mempool: (u64, u64, u64)| {
            format!(
                r#"{{"mempool_stats":{{"tx_count":{},"funded_txo_sum":{},"spent_txo_sum":{}}},"chain_stats":{{"funded_txo_sum":{},"spent_txo_sum":{},"tx_count":{}}}}}"#,
                mempool.0, mempool.1, mempool.2, chain.1, chain.2, chain.0
            )
        };
        let read = |chain, mempool| parse_address_stats(&stats(chain, mempool)).unwrap();
        let arriving = read((0, 0, 0), (1, 2_500, 0));
        let arrived = read((1, 2_500, 0), (0, 0, 0));
        let spending = read((1, 2_500, 0), (1, 0, 2_500));
        assert_eq!(
            [arriving.pending, arrived.pending, spending.pending],
            [true, false, true]
        );
        assert!(!parse_address_stats(UNUSED).unwrap().pending);
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

    /// A transaction that spends coin 0 of the one whose id repeats `digit` to `outputs`.
    fn paying(digit: char, outputs: Vec<TxOut>) -> Transaction {
        Transaction {
            version: bitcoin::transaction::Version::TWO,
            lock_time: LockTime::ZERO,
            input: vec![TxIn {
                previous_output: OutPoint {
                    txid: Txid::from_str(&id(digit)).unwrap(),
                    vout: 0,
                },
                ..TxIn::default()
            }],
            output: outputs,
        }
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
    /// A P2WPKH output's script, this wallet's change and `SOMEONE`: 22 bytes.
    const P2WPKH_SCRIPT: usize = 22;
    /// `SOMEONE`'s script length.
    const SOMEONE_SCRIPT: usize = P2WPKH_SCRIPT;
    /// Stands in for the private node of a build, so the tests read the same with or without one.
    const OWN_NODE_FIXTURE: &str = "http://node.internal:8889/api";
    /// The reads of one proof of use, which are the address's history page and the tip, then the raw
    /// transaction, its merkle branch and its block's header. After a restart each chain's proven index is proven
    /// again once, because the scan file is outside data and names no payment to show again.
    const PROOF_READS: usize = 5;

    fn used_stats(balance: u64) -> String {
        format!(
            r#"{{"chain_stats":{{"funded_txo_sum":{balance},"spent_txo_sum":0,"tx_count":1}},"mempool_stats":{{"funded_txo_sum":0,"spent_txo_sum":0,"tx_count":0}}}}"#
        )
    }

    fn own(chain: Chain, index: u32) -> OwnKey {
        Account::new(
            &abandon_about(),
            "",
            Network::Testnet4,
            AddressType::NativeSegwit,
        )
        .unwrap()
        .key(chain, index)
        .unwrap()
    }

    fn addr(chain: Chain, index: u32) -> String {
        own(chain, index).address.to_string()
    }

    fn own_as(address_type: AddressType, chain: Chain, index: u32) -> OwnKey {
        Account::new(&abandon_about(), "", Network::Testnet4, address_type)
            .unwrap()
            .key(chain, index)
            .unwrap()
    }

    /// Esplora keyed by address. An address not set up answers as one nobody ever paid, with no
    /// history. One set up lists its payment in `PROOF` when that block pays it, so a claim of use
    /// proves like a real one. Each Fake has its own scan cache and a clock that moves only by `tick`,
    /// so no test sleeps and none sees another's cache.
    struct Fake {
        stats: HashMap<String, String>,
        txs: HashMap<String, String>,
        utxos: HashMap<String, String>,
        /// What `/tx/{txid}` answers. A transaction not set up is a stranger's: it spends none of the wallet's coins.
        parents: HashMap<String, String>,
        /// Transactions this host does not know, so every read of one answers 404: a payment a reorg undid, or one
        /// in a block the host never saw.
        unknown: HashSet<String>,
        /// Addresses and transactions whose every read times out.
        failing: HashSet<String>,
        /// What `/tx/{txid}/hex` answers for the transactions `fund_raw` made.
        raw: HashMap<String, String>,
        tip: String,
        calls: Mutex<Vec<String>>,
        now: Mutex<SystemTime>,
        store: ScanStore,
        /// The type of the wallet every call reads and signs, Native SegWit unless a test sets another.
        address_type: AddressType,
    }

    impl Fake {
        fn new() -> Self {
            Self {
                stats: HashMap::new(),
                txs: HashMap::new(),
                utxos: HashMap::new(),
                parents: HashMap::new(),
                unknown: HashSet::new(),
                failing: HashSet::new(),
                raw: HashMap::new(),
                tip: TIP.into(),
                calls: Mutex::new(Vec::new()),
                now: Mutex::new(UNIX_EPOCH + Duration::from_secs(1_762_300_000)),
                store: ScanStore::default(),
                address_type: AddressType::NativeSegwit,
            }
        }

        fn now(&self) -> SystemTime {
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

        /// `address` holds output 1 of a confirmed transaction that this host serves raw and that pays it `value` sats,
        /// and the coin list says `listed`, which an honest host makes `value`. Returns the transaction's id.
        fn fund_raw(&mut self, address: &str, value: u64, listed: u64, digit: char) -> Txid {
            let pay = |to: &str, sats| TxOut {
                value: Amount::from_sat(sats),
                script_pubkey: Address::from_str(to)
                    .unwrap()
                    .assume_checked()
                    .script_pubkey(),
            };
            let tx = paying(digit, vec![pay(SOMEONE, 1_000), pay(address, value)]);
            let txid = tx.compute_txid();
            self.stats.insert(address.into(), used_stats(listed));
            self.utxos.insert(
                address.into(),
                format!(r#"[{{"txid":"{txid}","vout":1,"status":{{"confirmed":true}},"value":{listed}}}]"#),
            );
            self.raw.insert(txid.to_string(), serialize_hex(&tx));
            txid
        }

        /// `address` holds one coin of each of `values`, each output 1 of its own confirmed transaction that this
        /// host serves raw, the first made with digit `1`, the next with `2`. Returns the transactions' ids.
        fn fund_coins(&mut self, address: &str, values: &[u64]) -> Vec<Txid> {
            let pay = |to: &str, sats| TxOut {
                value: Amount::from_sat(sats),
                script_pubkey: Address::from_str(to)
                    .unwrap()
                    .assume_checked()
                    .script_pubkey(),
            };
            let (mut listed, mut txids) = (Vec::new(), Vec::new());
            for (i, value) in values.iter().enumerate() {
                let digit = char::from_digit(i as u32 + 1, 16).unwrap();
                let tx = paying(digit, vec![pay(SOMEONE, 1_000), pay(address, *value)]);
                let txid = tx.compute_txid();
                listed.push(format!(
                    r#"{{"txid":"{txid}","vout":1,"status":{{"confirmed":true}},"value":{value}}}"#
                ));
                self.raw.insert(txid.to_string(), serialize_hex(&tx));
                txids.push(txid);
            }
            self.stats
                .insert(address.into(), used_stats(values.iter().sum()));
            self.utxos
                .insert(address.into(), format!("[{}]", listed.join(",")));
            txids
        }

        /// How many times `txid`'s raw bytes were read.
        fn reads_of(&self, txid: Txid) -> usize {
            let path = format!("/tx/{txid}/hex");
            self.calls
                .lock()
                .unwrap()
                .iter()
                .filter(|url| url.ends_with(&path))
                .count()
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
            if let Some(rest) = path.strip_prefix("/tx/")
                && self
                    .unknown
                    .contains(rest.split('/').next().unwrap_or(rest))
            {
                return Err(MobileSignerError::InvalidPsbtJson(format!(
                    "esplora get: {url}: status code 404"
                )));
            }
            if let Some(rest) = path.strip_prefix("/tx/")
                && self
                    .failing
                    .contains(rest.split('/').next().unwrap_or(rest))
            {
                return Err(MobileSignerError::InvalidPsbtJson(format!(
                    "esplora get: {url}: timed out"
                )));
            }
            if let Some(hex) = path
                .strip_prefix("/tx/")
                .and_then(|rest| rest.strip_suffix("/hex"))
                .and_then(|txid| self.raw.get(txid))
            {
                return Ok(hex.clone());
            }
            if let Some(answer) = proof_answer(path) {
                return answer;
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
            Ok(table.get(address).cloned().unwrap_or_else(|| match what {
                "txs" if self.stats.contains_key(address) => {
                    PROOF.pages.get(address).cloned().unwrap_or(fresh.into())
                }
                _ => fresh.to_string(),
            }))
        }

        fn asked_about(&self, address: &str) -> bool {
            self.calls
                .lock()
                .unwrap()
                .iter()
                .any(|url| url.contains(address))
        }

        /// Whether a snapshot now, inside the share window of the last sync, asks the host about the wallet:
        /// not while that sync's picture stands, and once a payment or a lost host made it stale, a routine sync.
        /// A wallet with nothing funded asks no coin list, so any request is the sync's.
        fn next_snapshot_syncs(&self) -> bool {
            let before = self.requests();
            self.snapshot("").unwrap();
            self.requests() > before
        }

        fn snapshot(&self, passphrase: &str) -> Result<HotWalletSnapshot, MobileSignerError> {
            self.snapshot_of(&abandon_about(), passphrase)
        }

        /// A snapshot of the wallet through `store`, as after a restart that read the same folder.
        fn snapshot_in(&self, store: &ScanStore) -> Result<HotWalletSnapshot, MobileSignerError> {
            snapshot_from(
                &EsploraSource(&Reads(&|url| self.get(url))),
                &abandon_about(),
                "",
                KeyNetwork::Test,
                self.address_type,
                &|| self.now(),
                store,
            )
        }

        fn snapshot_of(
            &self,
            words: &[String],
            passphrase: &str,
        ) -> Result<HotWalletSnapshot, MobileSignerError> {
            snapshot_from(
                &EsploraSource(&Reads(&|url| self.get(url))),
                words,
                passphrase,
                KeyNetwork::Test,
                self.address_type,
                &|| self.now(),
                &self.store,
            )
        }

        fn history(&self) -> Result<Vec<HotTx>, MobileSignerError> {
            history_of(
                &EsploraSource(&Reads(&|url| self.get(url))),
                &abandon_about(),
                "",
                KeyNetwork::Test,
                self.address_type,
                &|| self.now(),
                &self.store,
            )
        }

        /// A payment at `rate` sat/vB, and the fee it pays.
        fn send_at(
            &self,
            to: &str,
            amount: u64,
            rate: u64,
        ) -> Result<(Transaction, u64), MobileSignerError> {
            self.payment(to, amount, rate).map(|s| (s.tx, s.fee_sats))
        }

        /// `send_at` with the change that went into the fee.
        fn payment(&self, to: &str, amount: u64, rate: u64) -> Result<Signed, MobileSignerError> {
            let words = abandon_about();
            signed_payment(
                &EsploraSource(&Reads(&|url| self.get(url))),
                &words,
                "",
                KeyNetwork::Test,
                self.address_type,
                to,
                amount,
                rate,
                &|| self.now(),
                &self.store,
            )
        }

        fn send(&self, to: &str, amount: u64, rate: u64) -> Result<Transaction, MobileSignerError> {
            self.send_at(to, amount, rate).map(|(tx, _)| tx)
        }

        /// `hot_quote_send` as the app would reach it, against this Fake.
        fn quote(
            &self,
            to: &str,
            amount: SendAmount,
            rate: u64,
        ) -> Result<QuotedSend, MobileSignerError> {
            quoted(
                &EsploraSource(&Reads(&|url| self.get(url))),
                &abandon_about(),
                "",
                KeyNetwork::Test,
                self.address_type,
                to,
                amount,
                rate,
                &|| self.now(),
                &self.store,
            )
        }

        /// What the payment `quote` reads signs: `payment`, or `hot_prepare_send_all`'s for send all.
        fn signed_for(
            &self,
            to: &str,
            amount: SendAmount,
            rate: u64,
        ) -> Result<Signed, MobileSignerError> {
            match amount {
                SendAmount::Exact(sats) => self.payment(to, sats, rate),
                SendAmount::All => signed_send_all(
                    &EsploraSource(&Reads(&|url| self.get(url))),
                    &abandon_about(),
                    "",
                    KeyNetwork::Test,
                    self.address_type,
                    to,
                    rate,
                    &|| self.now(),
                    &self.store,
                ),
            }
        }

        /// The whole balance at `rate` sat/vB, as `hot_prepare_send_all` reaches it, and the fee it pays.
        fn send_all_at(
            &self,
            to: &str,
            rate: u64,
        ) -> Result<(Transaction, u64), MobileSignerError> {
            let words = abandon_about();
            signed_send_all(
                &EsploraSource(&Reads(&|url| self.get(url))),
                &words,
                "",
                KeyNetwork::Test,
                self.address_type,
                to,
                rate,
                &|| self.now(),
                &self.store,
            )
            .map(|s| (s.tx, s.fee_sats))
        }

        /// A `kind` replacement of `earlier` at `rate` sat/vB, as `hot_prepare_fee_bump` and
        /// `hot_prepare_cancel` reach it, and the fee it pays.
        fn replace(
            &self,
            earlier: &Transaction,
            rate: u64,
            kind: Replacement,
        ) -> Result<(Transaction, u64), MobileSignerError> {
            self.replace_hex(&serialize_hex(earlier), rate, kind)
        }

        fn replace_hex(
            &self,
            hex: &str,
            rate: u64,
            kind: Replacement,
        ) -> Result<(Transaction, u64), MobileSignerError> {
            self.replacement(hex, rate, kind)
                .map(|s| (s.tx, s.fee_sats))
        }

        /// `replace_hex` with the change that went into the fee.
        fn replacement(
            &self,
            hex: &str,
            rate: u64,
            kind: Replacement,
        ) -> Result<Signed, MobileSignerError> {
            signed_replacement(
                &EsploraSource(&Reads(&|url| self.get(url))),
                &abandon_about(),
                "",
                KeyNetwork::Test,
                self.address_type,
                hex,
                rate,
                kind,
                &|| self.now(),
                &self.store,
            )
        }

        /// `hot_prepare_send_at_rate` as the app would reach it, against this Fake.
        fn ready(&self, to: &str, amount: u64, rate: u64) -> PreparedSend {
            prepared(self.payment(to, amount, rate).unwrap())
        }

        /// `hot_broadcast` as the app would reach it, against this Fake's scan cache.
        fn broadcast(
            &self,
            ready: &PreparedSend,
            post: Answer,
        ) -> Result<HotSendResult, MobileSignerError> {
            let post = Mutex::new(Some(post));
            broadcast_via(
                &EsploraSource(&Posts {
                    host: Ok(ESPLORA_TESTNET4),
                    post: &|_, _| post.lock().unwrap().take().expect("one post"),
                    lookup: &|_, _| not_found(),
                }),
                &ready.hex,
                &ready.txid,
                &self.store,
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
        // The documented cost of a wallet nobody paid: GAP lookups per chain and no more.
        assert_eq!(fake.calls.lock().unwrap().len(), 40);
        assert!(fake.history().unwrap().is_empty());
        match fake.send(SOMEONE, 1_000, 1) {
            Err(MobileSignerError::InvalidPsbtJson(m)) => {
                assert_eq!(m, "insufficient funds: have 0 confirmed, need 1110")
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

    // Finding 6, settled by the network's rule. Both testnet4 hosts refuse a payment with change below
    // the change address's floor (294 sats, P2WPKH), so that change goes into the fee, as Bitcoin Core's
    // wallet does, and the answer names it for Review. Change at the floor is ordinary change.
    #[test]
    fn change_below_its_floor_goes_into_the_fee_and_is_named() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 10_000, '1');
        // At 1 sat/vB the fee is 141: 9,565 leaves exactly 294, 9,566 leaves 293 and 9,858 leaves 1.
        assert_outputs(
            &fake.send(SOMEONE, 9_565, 1).unwrap(),
            &[(SOMEONE, 9_565), (&addr(Chain::Internal, 0), 294)],
        );
        let kept = fake.ready(SOMEONE, 9_565, 1);
        assert_eq!((kept.fee_sats, kept.dust_change_sats), (141, 0));
        for (amount, dust) in [(9_566, 293), (9_858, 1)] {
            let (tx, fee) = fake.send_at(SOMEONE, amount, 1).unwrap();
            assert_outputs(&tx, &[(SOMEONE, amount)]);
            assert_eq!(fee, 141 + dust, "{amount}");
            assert_eq!(fee_paid(&fake, &tx), fee, "{amount}");
            let ready = fake.ready(SOMEONE, amount, 1);
            assert_eq!(
                (ready.amount_sats, ready.fee_sats, ready.dust_change_sats),
                (amount, 141 + dust, dust)
            );
        }
        // 10,000 less 9,000 less 141 x 4 (436) is change; less 141 x 7 (13) goes into the fee.
        assert_outputs(
            &fake.send(SOMEONE, 9_000, 4).unwrap(),
            &[(SOMEONE, 9_000), (&addr(Chain::Internal, 0), 436)],
        );
        assert_eq!(fake.ready(SOMEONE, 9_000, 7).dust_change_sats, 13);
        // Send all, as the app computes it: the balance less the 141 vB estimate. One output, and
        // the estimate is kept as the fee with no change to fold.
        let (all, fee) = fake.send_at(SOMEONE, 10_000 - 141, 1).unwrap();
        assert_outputs(&all, &[(SOMEONE, 9_859)]);
        assert_eq!(fee, 141);
        assert_eq!(fee_paid(&fake, &all), 141);
        assert_eq!(fake.ready(SOMEONE, 9_859, 1).dust_change_sats, 0);
    }

    // Finding 5. Each guard is a distinct error before any signing.
    #[test]
    fn a_dust_amount_or_a_rate_out_of_range_is_refused_before_any_lookup() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 10_000, '1');
        assert_refused(
            fake.send(SOMEONE, 0, 0),
            "amount 0 is below the network's 294 sat dust floor",
        );
        assert_refused(fake.send(SOMEONE, 1_000, 0), "fee rate 0 is below 1 sat/vB");
        assert_refused(
            fake.send(SOMEONE, 1_000, 10_001),
            "fee rate 10001 is above the network's 10000 sat/vB limit",
        );
        assert!(fake.calls.lock().unwrap().is_empty());
    }

    // Both testnet4 hosts refuse an output one sat below Bitcoin Core's dust floor for its script and take
    // one at it. 546 was the P2PKH floor, applied to every address.
    #[test]
    fn an_amount_below_its_addresses_floor_is_refused_before_any_lookup_and_one_at_it_is_signed() {
        for ((kind, to), floor) in destinations().iter().zip([294, 330, 330, 540, 546]) {
            let mut fake = Fake::new();
            fake.fund(&addr(Chain::External, 0), 10_000, '1');
            assert_refused(
                fake.send(to, floor - 1, 1),
                &format!(
                    "amount {} is below the network's {floor} sat dust floor",
                    floor - 1
                ),
            );
            assert!(fake.calls.lock().unwrap().is_empty(), "{kind}");
            let tx = fake.send(to, floor, 1).unwrap();
            assert_eq!(tx.output[0].value.to_sat(), floor, "{kind}");
        }
    }

    // The fee is paid on top of the amount, so a fee above the amount still pays the destination in
    // full. The app asks the user before it posts; the core only signs. 1,000 sat at 101 sat/vB was
    // the payment an iPhone refused.
    #[test]
    fn a_fee_above_the_amount_is_signed_and_the_destination_gets_the_whole_amount() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 100_000, '1');
        // One coin and change is 141 vB, so the fee is 14,241, first above the amount, then equal to it,
        // then below it.
        for amount in [1_000, 14_240, 14_241, 14_242] {
            let (tx, fee) = fake.send_at(SOMEONE, amount, 101).unwrap();
            assert_eq!(fee, 14_241, "amount {amount}");
            assert_outputs(
                &tx,
                &[
                    (SOMEONE, amount),
                    (&addr(Chain::Internal, 0), 100_000 - amount - 14_241),
                ],
            );
            assert_eq!(fee_paid(&fake, &tx), 14_241, "amount {amount}");
        }
        // The payment the old rule refused at 8 sat/vB, a fee of 1,128 on 1,000 sat.
        let mut small = Fake::new();
        small.fund(&addr(Chain::External, 0), 10_000, '1');
        assert_outputs(
            &small.send(SOMEONE, 1_000, 8).unwrap(),
            &[(SOMEONE, 1_000), (&addr(Chain::Internal, 0), 7_872)],
        );
    }

    // What is left of the fee check: an amount that cannot add the fee in u64 is refused before any
    // lookup.
    #[test]
    fn an_amount_that_overflows_u64_with_the_fee_is_refused_before_any_lookup() {
        let fake = Fake::new();
        assert_refused(
            fake.send(SOMEONE, u64::MAX, 1),
            "amount plus fee does not fit u64",
        );
        assert!(fake.calls.lock().unwrap().is_empty());
    }

    // Findings 5 and 7. One P2WPKH input and two outputs is 141 vB; one output is 110 vB.
    #[test]
    fn the_fee_is_the_size_of_the_transaction_as_built_times_the_rate() {
        assert_eq!(vsize(&[OWN_SPEND], &[P2WPKH_SCRIPT; 2]), 141);
        assert_eq!(vsize(&[OWN_SPEND], &[P2WPKH_SCRIPT]), 110);
        assert_eq!(vsize(&[OWN_SPEND; 11], &[P2WPKH_SCRIPT]), 790);
        let mut fake = Fake::new();
        for i in 0..3u32 {
            fake.fund(&addr(Chain::External, i), 4_000, char::from(b'1' + i as u8));
        }
        for (amount, inputs) in [(3_000, 1), (6_000, 2), (10_000, 3)] {
            let (tx, fee) = fake.send_at(SOMEONE, amount, 2).unwrap();
            assert_eq!((tx.input.len(), tx.output.len()), (inputs, 2));
            assert_eq!(
                fee,
                2 * vsize(&vec![OWN_SPEND; inputs], &[P2WPKH_SCRIPT; 2]),
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
            assert_eq!(
                fee,
                2 * vsize(&vec![OWN_SPEND; coins], &[P2WPKH_SCRIPT; 2]),
                "{coins} coins"
            );
            assert_eq!(fee_paid(&fake, &tx), fee, "{coins} coins");
        }
        let mut big = Fake::new();
        big.fund(&addr(Chain::External, 0), 1_000_000, '1');
        assert_eq!(big.send_at(SOMEONE, 600_000, 2_000).unwrap().1, 282_000);
    }

    // From 253 inputs the input count is a 3-byte varint, so one more coin adds 70 vB, not 68. The 1-byte count paid
    // 2 vB times the rate under it, at 1 sat/vB under the minimum relay fee.
    #[test]
    fn the_size_counts_the_three_byte_input_count_from_253_inputs() {
        assert_eq!(vsize(&[OWN_SPEND; 252], &[P2WPKH_SCRIPT]), 17_178);
        assert_eq!(vsize(&[OWN_SPEND; 253], &[P2WPKH_SCRIPT]), 17_248);
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
        let ready = prepared(Signed {
            tx,
            fee_sats: fee,
            dust_change_sats: 0,
            amount_cut_sats: 0,
        });
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
            assert_eq!(
                fee,
                2 * vsize(&vec![OWN_SPEND; coins], &[P2WPKH_SCRIPT]),
                "{coins} coins"
            );
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

    // At 1 sat/vB one coin and one output cost the transaction's size, 110 vB to P2WPKH, 122 to P2TR and
    // P2WSH, 111 to P2SH and 113 to P2PKH, so that plus the destination's floor leaves exactly the floor
    // and goes, and one sat less is refused.
    #[test]
    fn send_all_that_would_leave_less_than_the_destinations_floor_after_the_fee_is_refused() {
        let sizes_and_floors = [(110, 294), (122, 330), (122, 330), (111, 540), (113, 546)];
        for ((kind, to), (size, floor)) in destinations().iter().zip(sizes_and_floors) {
            let mut fake = Fake::new();
            fake.fund(&addr(Chain::External, 0), size + floor, '1');
            let (tx, fee) = fake.send_all_at(to, 1).unwrap();
            assert_eq!(fee, size, "{kind}");
            assert_outputs(&tx, &[(to, floor)]);
            let mut short = Fake::new();
            short.fund(&addr(Chain::External, 0), size + floor - 1, '1');
            assert_refused(
                short.send_all_at(to, 1),
                &format!(
                    "the whole balance {} less the fee {size} is below the network's {floor} sat dust floor",
                    size + floor - 1
                ),
            );
        }
        let mut tiny = Fake::new();
        tiny.fund(&addr(Chain::External, 0), 100, '1');
        assert_refused(
            tiny.send_all_at(SOMEONE, 1),
            "the whole balance 100 less the fee 110 is below the network's 294 sat dust floor",
        );
    }

    #[test]
    fn send_all_of_an_empty_wallet_or_one_whose_only_coin_is_unconfirmed_is_refused() {
        let fake = Fake::new();
        assert_refused(
            fake.send_all_at(SOMEONE, 1),
            "insufficient funds: have 0 confirmed, need 404",
        );
        let mut pending = Fake::new();
        pending.fund_unconfirmed(&addr(Chain::External, 0), 5_000, '1');
        assert_refused(
            pending.send_all_at(SOMEONE, 2),
            "insufficient funds: have 0 confirmed, need 514",
        );
    }

    #[test]
    fn send_all_at_a_rate_out_of_range_or_to_the_wrong_network_is_refused_before_any_lookup() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 10_000, '1');
        assert_refused(fake.send_all_at(SOMEONE, 0), "fee rate 0 is below 1 sat/vB");
        assert_refused(
            fake.send_all_at(SOMEONE, 10_001),
            "fee rate 10001 is above the network's 10000 sat/vB limit",
        );
        assert!(
            fake.send_all_at("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", 1)
                .is_err()
        );
        assert_eq!(fake.requests(), 0);
    }

    // 1,400 coins of 137 sats each add 69 sats beyond their own input at 1 sat/vB, too little to pay 100,000: the four
    // passes ran out first ("fee did not settle"), and the walk names the funds it needs. Over 1,000-sat coins the
    // payment the third pass settled signs the same: 12 inputs and change, 889 sats.
    #[test]
    fn coins_that_barely_pay_for_their_own_inputs_fall_short_and_twelve_1000_sat_coins_pay() {
        let mut fake = Fake::new();
        let coins: Vec<String> = (0..1_400u32).map(|i| coin('a', i, 137, true)).collect();
        fake.stats
            .insert(addr(Chain::External, 0), used_stats(137 * 1_400));
        fake.utxos
            .insert(addr(Chain::External, 0), format!("[{}]", coins.join(",")));
        assert_refused(
            fake.send(SOMEONE, 100_000, 1),
            "insufficient funds: have 191800 confirmed, need 195244",
        );
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
        assert_eq!(fee, vsize(&[OWN_SPEND; 12], &[P2WPKH_SCRIPT; 2]));
        assert_eq!(fee_paid(&many, &tx), 889);
    }

    // Research section 2. Ten confirmed 2,000-sat coins at 20 sat/vB: the four passes refused 2,000, 3,000 and 5,000
    // with "fee did not settle". One coin at a time pays 2,000 with 6 coins and 380 change, 3,000 with 7 and 20 into the
    // fee, and 5,000 with all 10 and no room for change, so 560 goes into the fee. 1,000 pays as before; 6,000 is more
    // than the coins can pay.
    #[test]
    fn ten_2000_sat_coins_at_20_sat_vb_pay_every_amount_the_coins_can() {
        let mut fake = Fake::new();
        for (i, digit) in "0123456789".chars().enumerate() {
            fake.fund(&addr(Chain::External, i as u32), 2_000, digit);
        }
        let change = addr(Chain::Internal, 0);
        for (amount, inputs, kept, fee, dust) in [
            (1_000, 4, None, 7_000, 100),
            (2_000, 6, Some(380), 9_620, 0),
            (3_000, 7, None, 11_000, 20),
            (5_000, 10, None, 15_000, 560),
        ] {
            let paid = fake.payment(SOMEONE, amount, 20).unwrap();
            let mut outputs = vec![(SOMEONE, amount)];
            outputs.extend(kept.map(|sats| (change.as_str(), sats)));
            assert_outputs(&paid.tx, &outputs);
            assert_eq!(
                (paid.tx.input.len(), paid.fee_sats, paid.dust_change_sats),
                (inputs, fee, dust),
                "{amount}"
            );
            assert_eq!(fee_paid(&fake, &paid.tx), fee, "{amount}");
        }
        assert_refused(
            fake.send(SOMEONE, 6_000, 20),
            "insufficient funds: have 20000 confirmed, need 20440",
        );
    }

    /// A seeded xorshift, so a random test runs the same cases every time.
    fn xorshift(mut state: u64) -> impl FnMut(u64) -> u64 {
        move |n| {
            state ^= state << 13;
            state ^= state >> 7;
            state ^= state << 17;
            state % n
        }
    }

    /// The script of each address of `destinations`, in its order: P2WPKH, P2TR, P2WSH, P2SH, P2PKH.
    fn scripts() -> Vec<ScriptBuf> {
        destinations()
            .iter()
            .map(|(_, a)| {
                Address::from_str(a)
                    .unwrap()
                    .assume_checked()
                    .script_pubkey()
            })
            .collect()
    }

    // A payment some set of the coins can make is never refused. A set can pay when it covers the amount and a payment of
    // its size with no change at the rate asked, the smallest it can make, and the walk pays if and only if some set can,
    // over wallets of 1 to 7 coins, every destination and change type, and rates up to the 9,900 sat/vB fast tier, where
    // putting the leftover into the fee can pass the network's 10,000 sat/vB. What it pays stays within the network's
    // limits, and only change at its floor pays under the rate asked.
    #[test]
    fn the_walk_pays_if_and_only_if_some_set_of_the_coins_can() {
        let scripts = scripts();
        let mut rnd = xorshift(0x9E37_79B9_7F4A_7C15);
        let (mut paid, mut refused, mut under_rate) = (0, 0, 0);
        for case in 0..30_000 {
            let n = 1 + rnd(7) as usize;
            let scale = [3_000, 60_000, 3_000_000][rnd(3) as usize];
            let mut coins: Vec<u64> = (0..n).map(|_| 300 + rnd(scale)).collect();
            coins.sort_by(|a, b| b.cmp(a));
            let rates = [
                1, 2, 7, 30, 400, 3_000, 7_000, 7_800, 8_500, 9_000, 9_500, 9_900,
            ];
            let rate = rates[rnd(rates.len() as u64) as usize];
            let (dest, change) = (&scripts[rnd(5) as usize], &scripts[rnd(5) as usize]);
            let amount = dust_floor(dest) + rnd(coins.iter().sum());
            let size = |k: usize, outputs: &[usize]| vsize(&vec![OWN_SPEND; k], outputs);
            let can = (1..1u32 << n).any(|set| {
                let sum: u64 = (0..n).filter(|i| set >> i & 1 == 1).map(|i| coins[i]).sum();
                sum >= amount + rate * size(set.count_ones() as usize, &[dest.len()])
            });
            let spends: Vec<_> = coins.iter().map(|value| (*value, OWN_SPEND)).collect();
            let walked = select(&spends, amount, rate, dest.len(), change);
            let case =
                format!("case {case}: coins {coins:?} amount {amount} rate {rate}: {walked:?}");
            assert_eq!(walked.is_ok(), can, "{case}");
            let Ok((k, settled)) = walked else {
                refused += 1;
                continue;
            };
            paid += 1;
            let left = coins[..k].iter().sum::<u64>() - amount;
            let (fee, vb) = match settled {
                Settle::Change(sats) | Settle::FloorChange(sats) => {
                    assert!(sats >= dust_floor(change), "{case}");
                    (left - sats, size(k, &[dest.len(), change.len()]))
                }
                Settle::Fold(_) | Settle::Fit(_) => (left, size(k, &[dest.len()])),
            };
            assert!((vb..=MAX_FEE_RATE * vb).contains(&fee), "{case}");
            if fee < rate * vb {
                assert!(matches!(settled, Settle::FloorChange(_)), "{case}");
                under_rate += 1;
            }
        }
        assert!(
            paid > 10_000 && refused > 5_000 && under_rate > 20,
            "{paid} paid, {refused} refused, {under_rate} under the rate"
        );
    }

    /// The four passes `select` replaced, as they were at a2f06ac, over coin values largest first and sizes from
    /// `vsize`: the coins they spend, the change they keep and the fee, or None where they refused, the node's limit
    /// included.
    fn four_passes(
        coins: &[u64],
        amount: u64,
        rate: u64,
        dest: usize,
    ) -> Option<(usize, Option<u64>, u64)> {
        let outputs = [dest, P2WPKH_SCRIPT];
        let fee_for = |inputs: usize, change: bool| {
            rate * vsize(
                &vec![OWN_SPEND; inputs],
                &outputs[..if change { 2 } else { 1 }],
            )
        };
        let have: u64 = coins.iter().sum();
        let mut fee_sats = fee_for(1, true);
        let mut passes = 0;
        let (count, change) = loop {
            let need = amount + fee_sats;
            if have < need {
                return None;
            }
            let (mut count, mut total) = (0, 0);
            for value in coins {
                total += value;
                count += 1;
                if total >= need {
                    break;
                }
            }
            let change = total - need;
            let real = fee_for(count, change > 0);
            if real <= fee_sats {
                break (count, change);
            }
            passes += 1;
            if passes == 4 {
                return None;
            }
            fee_sats = real;
        };
        let kept = change >= 294;
        let fee = fee_sats + if kept { 0 } else { change };
        let size = vsize(
            &vec![OWN_SPEND; count],
            &outputs[..if kept { 2 } else { 1 }],
        );
        check_node_rate(fee, size).ok()?;
        Some((count, kept.then_some(change), fee))
    }

    // Every payment the four passes signed signs the same coins, change and fee: where they signed, the walk stops at
    // the same coin and keeps or folds the same change, over wallets of 1 to 8 confirmed coins and rates up to 9,900.
    #[test]
    fn every_payment_the_four_passes_signed_signs_the_same_transaction() {
        let change = own(Chain::Internal, 0).address.script_pubkey();
        let mut rnd = xorshift(0x2545_F491_4F6C_DD1D);
        let mut compared = 0;
        for case in 0..40_000 {
            let n = 1 + rnd(8) as usize;
            let scale = [1_000, 20_000, 500_000, 5_000_000][rnd(4) as usize];
            let mut coins: Vec<u64> = (0..n).map(|_| 200 + rnd(scale)).collect();
            coins.sort_by(|a, b| b.cmp(a));
            let rates = [1, 2, 3, 5, 10, 20, 50, 100, 1_000, 5_000, 9_900];
            let rate = rates[rnd(rates.len() as u64) as usize];
            let amount = 294 + rnd(coins.iter().sum());
            let Some(before) = four_passes(&coins, amount, rate, P2WPKH_SCRIPT) else {
                continue;
            };
            let spends: Vec<_> = coins.iter().map(|value| (*value, OWN_SPEND)).collect();
            let case =
                format!("case {case}: coins {coins:?} amount {amount} rate {rate}: {before:?}");
            let (k, settled) = select(&spends, amount, rate, P2WPKH_SCRIPT, &change).expect(&case);
            let left = coins[..k].iter().sum::<u64>() - amount;
            let now = match settled {
                Settle::Change(sats) => (k, Some(sats), left - sats),
                Settle::Fold(_) => (k, None, left),
                other => panic!("{case}: the walk settles {other:?}"),
            };
            assert_eq!(now, before, "{case}");
            compared += 1;
        }
        assert!(compared > 15_000, "{compared} payments compared");
    }

    // The change floor follows the change script: P2WPKH 294, P2TR and P2WSH 330, P2SH 540, P2PKH 546, and so does the
    // change output's size. One 100,000-sat coin to P2WPKH at 1 sat/vB: change at its floor is an output, and one sat
    // less goes into the fee.
    #[test]
    fn change_one_sat_under_its_scripts_floor_goes_into_the_fee_and_change_at_it_stays() {
        let coin = [(100_000, OWN_SPEND)];
        let floors_and_sizes = [(294, 141), (330, 153), (330, 153), (540, 142), (546, 144)];
        for (change, (floor, size)) in scripts().iter().zip(floors_and_sizes) {
            let at_floor = 100_000 - size - floor;
            assert_eq!(
                select(&coin, at_floor, 1, P2WPKH_SCRIPT, change),
                Ok((1, Settle::Change(floor))),
                "{change}"
            );
            assert_eq!(
                select(&coin, at_floor + 1, 1, P2WPKH_SCRIPT, change),
                Ok((1, Settle::Fold(floor - 1))),
                "{change}"
            );
        }
    }

    // Bitcoin Core refuses a transaction above 400,000 WU (100,000 vB) as non-standard. 1,600 coins of 2,000 sats pay
    // 2,900,000 only with 1,502 inputs, 102,211 vB, and a send all of them is 108,844 vB: both are refused before any
    // signing, where both were signed and then refused by the network. 1,469 inputs and change are 99,967 vB and pay;
    // 1,470 inputs and one output are already 100,004.
    #[test]
    fn a_payment_or_send_all_above_100000_vb_is_refused_before_signing() {
        let mut fake = Fake::new();
        let coins: Vec<String> = (0..1_600u32).map(|i| coin('a', i, 2_000, true)).collect();
        fake.stats
            .insert(addr(Chain::External, 0), used_stats(2_000 * 1_600));
        fake.utxos
            .insert(addr(Chain::External, 0), format!("[{}]", coins.join(",")));
        assert_refused(
            fake.send(SOMEONE, 2_900_000, 1),
            "too many coins: this amount needs more than the network's 100000 vB, send a smaller amount",
        );
        assert_refused(
            fake.send_all_at(SOMEONE, 1),
            "too many coins: the whole balance needs more than the network's 100000 vB, send a fixed amount",
        );
        let change = own(Chain::Internal, 0).address.script_pubkey();
        let coins = vec![(2_000, OWN_SPEND); 1_600];
        let amount = 1_469 * 2_000 - 99_967 - 294;
        assert_eq!(
            select(&coins, amount, 1, P2WPKH_SCRIPT, &change),
            Ok((1_469, Settle::Change(294)))
        );
        assert_eq!(
            select(&coins, amount + 2_000, 1, P2WPKH_SCRIPT, &change),
            Err(Short::Size)
        );
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

    // Open item 75. The 24-word public test phrase the phone imported uses receive index 250, and a
    // cap of 200 refused it on every call.
    #[test]
    fn a_wallet_used_through_receive_index_300_loads_with_its_balance_and_next_receive_address() {
        let account = Account::new(
            &abandon_about(),
            "",
            Network::Testnet4,
            AddressType::NativeSegwit,
        )
        .unwrap();
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
        // 301 used receive indices and their gap, 10 used change indices and theirs, 3 coin lists,
        // and one proof per window, of receive 19 to 299 by 20, then 300, and of change 9.
        assert_eq!(fake.requests(), 321 + 30 + 3 + (15 + 1 + 1) * PROOF_READS);
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
            "insufficient funds: have 3000 confirmed, need 4220",
        );
    }

    // Confirmed coins pay first. A payment that chained on the wallet's own unconfirmed change, the larger coin here,
    // while a confirmed coin could pay now spends only the confirmed coin, and the change joins only when confirmed
    // coins fall short, in the same order. A stranger's unconfirmed coin is never spent (findings 14 and the stranger's
    // change, above and below).
    #[test]
    fn confirmed_coins_pay_before_the_wallets_own_unconfirmed_change() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 5_000, '1');
        fake.fund_change(&addr(Chain::Internal, 0), 8_000, '2');
        let spent = |tx: &Transaction| -> Vec<String> {
            inputs_of(tx).iter().map(|o| o.txid.to_string()).collect()
        };
        assert_eq!(spent(&fake.send(SOMEONE, 3_000, 1).unwrap()), [id('1')]);
        assert_eq!(
            spent(&fake.send(SOMEONE, 9_000, 1).unwrap()),
            [id('2'), id('1')]
        );
        assert_refused(
            fake.send(SOMEONE, 13_000, 1),
            "insufficient funds: have 13000 confirmed, need 13178",
        );
    }

    // Finding 1. What the app saves as its guard is the id of the transaction it then posts.
    #[test]
    fn a_prepared_send_posts_nothing_and_its_txid_is_the_transaction_in_its_hex() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 10_000, '1');
        let (signed, fee) = fake.send_at(SOMEONE, 5_000, 2).unwrap();
        let ready = prepared(Signed {
            tx: signed.clone(),
            fee_sats: fee,
            dust_change_sats: 0,
            amount_cut_sats: 0,
        });
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

    const KINDS: [Replacement; 2] = [Replacement::FeeBump, Replacement::Cancel];

    /// `next` may replace `earlier` in a mempool. It spends every coin `earlier` spends, and by their exact
    /// sizes it meets BIP-125 rules 3 and 4 (the earlier fee plus its own size at 1 sat/vB, at least) and 6
    /// (a rate above the earlier one once Bitcoin Core truncates both to whole sat/kvB).
    #[track_caller]
    fn assert_replaces(fake: &Fake, earlier: &Transaction, next: &Transaction) {
        for coin in inputs_of(earlier) {
            assert!(inputs_of(next).contains(&coin), "{coin} is spent again");
        }
        let (old_fee, fee) = (fee_paid(fake, earlier), fee_paid(fake, next));
        let (old_vsize, vsize) = (earlier.vsize() as u64, next.vsize() as u64);
        assert!(
            fee >= old_fee + vsize,
            "rules 3 and 4: {fee} against {old_fee} + {vsize}"
        );
        assert!(
            fee * 1000 / vsize > old_fee * 1000 / old_vsize,
            "rule 6 in whole sat/kvB: {fee}/{vsize} against {old_fee}/{old_vsize}"
        );
    }

    /// A payment of `amount` to `SOMEONE` from the Fake's coin `digit` (vout 0, `value` sats on receive 0),
    /// with `change` to change 0, built and signed here, so its change can be smaller than any the wallet
    /// would make.
    fn hand_made(digit: char, value: u64, amount: u64, change: u64) -> Transaction {
        let coin = Utxo {
            txid: Txid::from_str(&id(digit)).unwrap(),
            vout: 0,
            value,
            confirmed: true,
        };
        let to = |address: &str, sats: u64| TxOut {
            value: Amount::from_sat(sats),
            script_pubkey: Address::from_str(address)
                .unwrap()
                .assume_checked()
                .script_pubkey(),
        };
        let key = own(Chain::External, 0);
        signed(
            &EsploraSource(&Refusing("a Native SegWit coin is never read")),
            &[(&coin, &key)],
            vec![to(SOMEONE, amount), to(&addr(Chain::Internal, 0), change)],
        )
        .unwrap()
    }

    // C3. The bump pays the same destination the same amount and takes the new fee out of the change,
    // which goes to the same change address, so the earlier payment and the bump cannot both confirm.
    #[test]
    fn a_fee_bump_keeps_the_amount_and_the_change_address_and_takes_the_fee_from_the_change() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 10_000, '1');
        let earlier = fake.send(SOMEONE, 5_000, 2).unwrap();
        assert_outputs(
            &earlier,
            &[(SOMEONE, 5_000), (&addr(Chain::Internal, 0), 4_718)],
        );
        let (bump, fee) = fake.replace(&earlier, 10, Replacement::FeeBump).unwrap();
        // 141 vB at 10 sat/vB, above the 282 + 141 that rule 4 asks.
        assert_eq!(fee, 1_410);
        assert_outputs(
            &bump,
            &[(SOMEONE, 5_000), (&addr(Chain::Internal, 0), 3_590)],
        );
        assert_eq!(inputs_of(&bump), inputs_of(&earlier));
        assert!(signed_by(&bump, 0, 10_000, &own(Chain::External, 0)));
        assert_eq!(fee_paid(&fake, &bump), fee);
        assert_replaces(&fake, &earlier, &bump);
        let ready = prepared(Signed {
            tx: bump,
            fee_sats: fee,
            dust_change_sats: 0,
            amount_cut_sats: 0,
        });
        assert_eq!((ready.amount_sats, ready.fee_sats), (5_000, 1_410));
    }

    // BIP-125 rule 2 lets a replacement spend an unconfirmed coin only when the earlier payment spent it.
    // The own change in the mempool is the largest coin, so largest first would take it if it could.
    #[test]
    fn a_fee_bump_that_needs_another_coin_takes_a_confirmed_one_and_never_the_wallets_unconfirmed_change()
     {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 10_000, '1');
        fake.fund(&addr(Chain::External, 1), 5_000, '2');
        fake.fund_change(&addr(Chain::Internal, 0), 8_000, '3');
        let earlier = fake.send(SOMEONE, 9_000, 1).unwrap();
        assert_eq!(inputs_of(&earlier).len(), 1);
        assert_eq!(earlier.output[1].value.to_sat(), 859);
        let (bump, fee) = fake.replace(&earlier, 20, Replacement::FeeBump).unwrap();
        assert_eq!(inputs_of(&bump)[0], inputs_of(&earlier)[0]);
        assert_eq!(bump.input.len(), 2);
        assert_eq!(bump.input[1].previous_output.txid.to_string(), id('2'));
        // Two inputs and change are 209 vB, at 20 sat/vB.
        assert_eq!(fee, 4_180);
        assert_eq!(bump.output[0], earlier.output[0]);
        assert_eq!(
            bump.output[1].script_pubkey,
            earlier.output[1].script_pubkey
        );
        assert_eq!(bump.output[1].value.to_sat(), 15_000 - 9_000 - 4_180);
        assert!(signed_by(&bump, 0, 10_000, &own(Chain::External, 0)));
        assert!(signed_by(&bump, 1, 5_000, &own(Chain::External, 1)));
        assert_eq!(fee_paid(&fake, &bump), fee);
        assert_replaces(&fake, &earlier, &bump);
    }

    // A fixed payment whose change went into the fee has one output, as a send all has, and its bytes do not
    // tell the two apart. Its bump keeps the amount and takes the new fee from a confirmed coin, with the
    // change at the next unused change address, as a bump of a payment with change does.
    #[test]
    fn a_fee_bump_of_a_payment_whose_change_went_into_the_fee_keeps_the_amount_when_a_coin_can_pay()
    {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 10_000, '1');
        fake.fund(&addr(Chain::External, 1), 5_000, '2');
        // At 1 sat/vB the change is 293, below its floor, so the fee is 141 + 293.
        let earlier = fake.payment(SOMEONE, 9_566, 1).unwrap();
        assert_eq!((earlier.fee_sats, earlier.dust_change_sats), (434, 293));
        let earlier = earlier.tx;
        assert_outputs(&earlier, &[(SOMEONE, 9_566)]);
        // Two inputs and change are 209 vB. The earlier rate is 434 over 110 vB, so rule 6 raises 2 sat/vB to 4.
        for (rate, fee) in [(2, 836), (5, 1_045)] {
            let bump = prepared(
                fake.replacement(&serialize_hex(&earlier), rate, Replacement::FeeBump)
                    .unwrap(),
            );
            assert_eq!(
                (bump.amount_sats, bump.fee_sats, bump.amount_cut_sats),
                (9_566, fee, 0)
            );
            let tx: Transaction = deserialize_hex(&bump.hex).unwrap();
            assert_outputs(
                &tx,
                &[
                    (SOMEONE, 9_566),
                    (&addr(Chain::Internal, 0), 15_000 - 9_566 - fee),
                ],
            );
            assert_eq!(fee_paid(&fake, &tx), fee);
            assert_replaces(&fake, &earlier, &tx);
        }
    }

    /// A send all of one 10,000-sat coin at 1 sat/vB, signed by a wallet that holds only that coin.
    fn send_all_of_one_coin() -> Transaction {
        let mut alone = Fake::new();
        alone.fund(&addr(Chain::External, 0), 10_000, '1');
        let (earlier, _) = alone.send_all_at(SOMEONE, 1).unwrap();
        assert_outputs(&earlier, &[(SOMEONE, 9_890)]);
        earlier
    }

    // A send all has no change, so with no coin to spare the new fee comes out of the amount, from its own
    // coins only, and the answer names how much less the destination gets. A coin too small to pay for its
    // own input (68 vB, 340 sats at 5 sat/vB) is not spent.
    #[test]
    fn a_fee_bump_of_a_send_all_with_no_coin_to_spare_cuts_the_amount_and_names_the_cut() {
        let earlier = send_all_of_one_coin();
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 10_000, '1');
        fake.fund(&addr(Chain::External, 1), 300, '2');
        let bump = prepared(
            fake.replacement(&serialize_hex(&earlier), 5, Replacement::FeeBump)
                .unwrap(),
        );
        // One input and one output are 110 vB, 550 at 5 sat/vB, which is 440 more than the earlier 110.
        assert_eq!(
            (bump.amount_sats, bump.fee_sats, bump.amount_cut_sats),
            (9_450, 550, 440)
        );
        let tx: Transaction = deserialize_hex(&bump.hex).unwrap();
        assert_outputs(&tx, &[(SOMEONE, 9_450)]);
        assert_eq!(inputs_of(&tx), inputs_of(&earlier));
        assert_eq!(fee_paid(&fake, &tx), 550);
        assert_replaces(&fake, &earlier, &tx);
    }

    // A coin confirmed after a send all keeps its amount on a bump, as Bitcoin Core's bumpfee does: the coin
    // pays the new fee and the rest comes back as change.
    #[test]
    fn a_fee_bump_of_a_send_all_keeps_the_amount_when_a_confirmed_coin_can_pay() {
        let earlier = send_all_of_one_coin();
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 10_000, '1');
        fake.fund(&addr(Chain::External, 1), 3_000, '2');
        let bump = prepared(
            fake.replacement(&serialize_hex(&earlier), 5, Replacement::FeeBump)
                .unwrap(),
        );
        // Two inputs and change are 209 vB, 1,045 at 5 sat/vB.
        assert_eq!(
            (bump.amount_sats, bump.fee_sats, bump.amount_cut_sats),
            (9_890, 1_045, 0)
        );
        let tx: Transaction = deserialize_hex(&bump.hex).unwrap();
        assert_outputs(
            &tx,
            &[
                (SOMEONE, 9_890),
                (&addr(Chain::Internal, 0), 13_000 - 9_890 - 1_045),
            ],
        );
        assert_eq!(fee_paid(&fake, &tx), 1_045);
        assert_replaces(&fake, &earlier, &tx);
    }

    // Change below its dust floor, 294 sats for the P2WPKH change address, goes into the fee, as Bitcoin
    // Core's bumpfee does. It is never refused, and change at the floor is kept.
    #[test]
    fn dust_change_of_a_fee_bump_goes_into_the_fee() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 10_000, '1');
        // At 1 sat/vB the change is 858, and a bump at 5 sat/vB pays 705, so 294 is left and stays.
        let earlier = fake.send(SOMEONE, 9_001, 1).unwrap();
        let (kept, fee) = fake.replace(&earlier, 5, Replacement::FeeBump).unwrap();
        assert_eq!(fee, 705);
        assert_outputs(&kept, &[(SOMEONE, 9_001), (&addr(Chain::Internal, 0), 294)]);
        assert_replaces(&fake, &earlier, &kept);
        let kept = fake
            .replacement(&serialize_hex(&earlier), 5, Replacement::FeeBump)
            .unwrap();
        assert_eq!(kept.dust_change_sats, 0);
        // One sat more to the destination leaves 293, so the whole 998 is the fee, and the answer names
        // the 293.
        let earlier = fake.send(SOMEONE, 9_002, 1).unwrap();
        let (folded, fee) = fake.replace(&earlier, 5, Replacement::FeeBump).unwrap();
        assert_eq!(fee, 998);
        assert_outputs(&folded, &[(SOMEONE, 9_002)]);
        assert_eq!(fee_paid(&fake, &folded), 998);
        assert_replaces(&fake, &earlier, &folded);
        let named = fake
            .replacement(&serialize_hex(&earlier), 5, Replacement::FeeBump)
            .unwrap();
        assert_eq!((named.fee_sats, named.dust_change_sats), (998, 293));
    }

    // A1. The cancel spends exactly the earlier coins and pays them back to the earlier change address.
    #[test]
    fn a_cancel_pays_every_earlier_coin_back_to_the_earlier_change_address_and_takes_no_other() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 30_000, '1');
        fake.fund(&addr(Chain::External, 1), 4_000, '2');
        let earlier = fake.send(SOMEONE, 20_000, 2).unwrap();
        assert_eq!(inputs_of(&earlier).len(), 1);
        let (cancel, fee) = fake.replace(&earlier, 5, Replacement::Cancel).unwrap();
        // One input and one output are 110 vB, at 5 sat/vB, above the 282 + 110 that rule 4 asks.
        assert_eq!(fee, 550);
        assert_outputs(&cancel, &[(&addr(Chain::Internal, 0), 30_000 - 550)]);
        assert_eq!(
            cancel.output[0].script_pubkey,
            earlier.output[1].script_pubkey
        );
        assert_eq!(inputs_of(&cancel), inputs_of(&earlier));
        assert!(signed_by(&cancel, 0, 30_000, &own(Chain::External, 0)));
        assert_eq!(fee_paid(&fake, &cancel), fee);
        assert_replaces(&fake, &earlier, &cancel);
        let ready = prepared(Signed {
            tx: cancel,
            fee_sats: fee,
            dust_change_sats: 0,
            amount_cut_sats: 0,
        });
        assert_eq!((ready.amount_sats, ready.fee_sats), (29_450, 550));
    }

    // A1. With no change to send it to, the cancel pays the next change address the wallet hands out.
    #[test]
    fn a_cancel_of_a_payment_with_no_change_pays_the_next_unused_change_address() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 10_000, '1');
        fake.fund(&addr(Chain::External, 1), 4_000, '2');
        let (earlier, _) = fake.send_all_at(SOMEONE, 1).unwrap();
        assert_outputs(&earlier, &[(SOMEONE, 14_000 - 178)]);
        let (cancel, fee) = fake.replace(&earlier, 3, Replacement::Cancel).unwrap();
        // Two inputs and one output are 178 vB, at 3 sat/vB, above the 178 + 178 that rule 4 asks.
        assert_eq!(fee, 534);
        assert_outputs(&cancel, &[(&addr(Chain::Internal, 0), 14_000 - 534)]);
        assert_eq!(inputs_of(&cancel), inputs_of(&earlier));
        assert!(signed_by(&cancel, 0, 10_000, &own(Chain::External, 0)));
        assert!(signed_by(&cancel, 1, 4_000, &own(Chain::External, 1)));
        assert_replaces(&fake, &earlier, &cancel);
    }

    // A cancel's one output goes to this wallet's P2WPKH change and a changeless bump's to its P2WPKH
    // destination, so both are refused below 294 sats. At 5 sat/vB one coin and one output pay 550.
    #[test]
    fn a_replacement_that_would_leave_less_than_the_floor_is_refused() {
        let mut short = Fake::new();
        short.fund(&addr(Chain::External, 0), 843, '1');
        let (earlier, _) = short.send_all_at(SOMEONE, 1).unwrap();
        for kind in KINDS {
            assert_refused(
                short.replace(&earlier, 5, kind),
                "replace: what is left, 843 less the fee 550, is below the network's 294 sat dust floor",
            );
        }
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 844, '1');
        let (earlier, _) = fake.send_all_at(SOMEONE, 1).unwrap();
        let (cancel, _) = fake.replace(&earlier, 5, Replacement::Cancel).unwrap();
        assert_outputs(&cancel, &[(&addr(Chain::Internal, 0), 294)]);
        let (bump, _) = fake.replace(&earlier, 5, Replacement::FeeBump).unwrap();
        assert_outputs(&bump, &[(SOMEONE, 294)]);
    }

    // The first-output check took 546 for every address, so a 300-sat payment to P2WPKH, which the
    // network takes and this wallet signs, read as not this wallet's and could be neither bumped nor
    // cancelled.
    #[test]
    fn a_300_sat_payment_can_be_bumped_and_cancelled() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 10_000, '1');
        let earlier = fake.send(SOMEONE, 300, 1).unwrap();
        for kind in KINDS {
            let (next, _) = fake.replace(&earlier, 2, kind).unwrap();
            assert_replaces(&fake, &earlier, &next);
        }
    }

    // Every rate asked, below the earlier one too, gives a replacement the network takes under rules 3, 4
    // and 6, for a payment with change, one without, and one whose change is too small to fold.
    #[test]
    fn every_replacement_meets_bip125_rules_3_4_and_6_at_every_rate_asked() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 1_000_000, '1');
        fake.fund(&addr(Chain::External, 1), 600_000, '2');
        fake.fund(&addr(Chain::External, 2), 400_000, '3');
        let with_change = fake.send(SOMEONE, 900_000, 10).unwrap();
        let without = fake.send(SOMEONE, 1_000_000 - 1_410, 10).unwrap();
        assert_eq!((with_change.output.len(), without.output.len()), (2, 1));
        for earlier in [&with_change, &without] {
            let old_rate = fee_paid(&fake, earlier) / earlier.vsize() as u64;
            for rate in [1, 5, 9, 10, 11, 12, 50, 300, 1_999, 2_000, 2_001, 3_000] {
                for kind in KINDS {
                    let case = format!("{kind:?} of {} outputs at {rate}", earlier.output.len());
                    let (next, fee) = fake.replace(earlier, rate, kind).expect(&case);
                    assert_replaces(&fake, earlier, &next);
                    assert_eq!(fee_paid(&fake, &next), fee, "{case}");
                    assert!(
                        fee >= rate.max(old_rate + 1) * next.vsize() as u64,
                        "{case}: {fee}"
                    );
                }
            }
        }
        // Change of 50 cannot pay the bump even folded, so another coin joins and the bump grows. The earlier
        // rate is exactly 10 sat/vB, so only a floor strictly above it keeps rule 6.
        let mut small = Fake::new();
        small.fund(&addr(Chain::External, 0), 6_000, '1');
        small.fund(&addr(Chain::External, 1), 3_000, '2');
        let earlier = hand_made('1', 6_000, 4_540, 50);
        assert_eq!((earlier.vsize(), fee_paid(&small, &earlier)), (141, 1_410));
        let (bump, fee) = small.replace(&earlier, 1, Replacement::FeeBump).unwrap();
        assert_eq!(bump.input.len(), 2);
        // 209 vB at 11 sat/vB, the floor above 10.
        assert_eq!(fee, 2_299);
        assert_replaces(&small, &earlier, &bump);
        let (cancel, fee) = small.replace(&earlier, 1, Replacement::Cancel).unwrap();
        assert_eq!((cancel.input.len(), fee), (1, 1_520));
        assert_replaces(&small, &earlier, &cancel);
    }

    // BIP-125 rule 6 asks a rate above the earlier one, so a payment at the network's 10,000 sat/vB can
    // never be replaced, and one at 2,000, which was the old cap, can be now.
    #[test]
    fn a_replacement_whose_rate_would_pass_10000_sat_vb_is_refused() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 3_000_000, '1');
        let earlier = fake.send(SOMEONE, 1_000_000, 10_000).unwrap();
        for kind in KINDS {
            assert_refused(
                fake.replace(&earlier, 1, kind),
                "replace: rate 10001 is above the network's 10000 sat/vB limit",
            );
        }
        let earlier = fake.send(SOMEONE, 600_000, 2_000).unwrap();
        for kind in KINDS {
            let (next, fee) = fake.replace(&earlier, 1, kind).unwrap();
            assert_replaces(&fake, &earlier, &next);
            assert!(fee >= 2_001 * next.vsize() as u64, "{kind:?}: {fee}");
        }
    }

    // Bitcoin Core refuses a fee above 10,000 sat/vB times the signed transaction's own size, the size the fee
    // was built for, and change put into the fee raises the fee. One coin and one output is 110 vB signed: change
    // of 100 sats folded at 7,000 sat/vB pays 987,100, under 1,100,000, and at 8,000 sat/vB would pay 1,128,100,
    // over it, so the change stays at its 294-sat floor and the fee is the rest, 1,127,806 on 141 vB.
    #[test]
    fn the_networks_rate_limit_holds_on_the_rate_asked_and_on_the_signed_bytes() {
        assert!(check_rate(10_000).is_ok());
        assert_eq!(check_node_rate(1_410_000, 141), Ok(()));
        assert_eq!(
            check_node_rate(1_410_001, 141),
            Err("fee 1410001 for 141 vB is above the network's 10000 sat/vB limit".into())
        );
        assert_eq!(
            check_node_rate(1_410_000, 140),
            Err("fee 1410000 for 140 vB is above the network's 10000 sat/vB limit".into())
        );
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 2_000_000, '1');
        let signed = fake.ready(SOMEONE, 2_000_000 - 141 * 7_000 - 100, 7_000);
        assert_eq!((signed.fee_sats, signed.dust_change_sats), (987_100, 100));
        let floored = fake
            .payment(SOMEONE, 2_000_000 - 141 * 8_000 - 100, 8_000)
            .unwrap();
        assert_outputs(
            &floored.tx,
            &[(SOMEONE, 871_900), (&addr(Chain::Internal, 0), 294)],
        );
        assert_eq!(
            (
                floored.fee_sats,
                floored.dust_change_sats,
                floored.tx.vsize()
            ),
            (1_127_806, 0, 141)
        );
        assert_eq!(fee_paid(&fake, &floored.tx), 1_127_806);
        // A fee bump at 9,000 sat/vB that folded 100 sats of change would pay 1,269,100 on 110 vB, over the limit, so
        // the change stays at its 294-sat floor and the fee is the rest, 1,268,806 on 141 vB: under the 9,000 asked and
        // above what rules 3, 4 and 6 ask.
        let mut bumped = Fake::new();
        bumped.fund(&addr(Chain::External, 0), 1_369_100, '1');
        let earlier = bumped.send(SOMEONE, 100_000, 1).unwrap();
        let bump = bumped
            .replacement(&serialize_hex(&earlier), 9_000, Replacement::FeeBump)
            .unwrap();
        assert_outputs(
            &bump.tx,
            &[(SOMEONE, 100_000), (&addr(Chain::Internal, 0), 294)],
        );
        assert_eq!(
            (bump.fee_sats, bump.dust_change_sats, bump.tx.vsize()),
            (1_268_806, 0, 141)
        );
        assert_replaces(&bumped, &earlier, &bump.tx);
    }

    // Repro 2 of the round-2 review (open item 86 point 7). A send all of one 10,000,000-sat coin at 1 sat/vB, bumped
    // at 9,900 sat/vB with a spare confirmed coin. Putting what is left into the fee passed 10,000 sat/vB on 178 vB from
    // a spare coin of 1,779,891 sats, and the bump was refused up to 2,069,283. Now the change stays at its 294-sat
    // floor and the fee falls under the rate asked, from 8,515 to 9,899.99 sat/vB, so the amount is kept wherever a
    // coin can keep it. Below 1,762,090 no spare coin pays the bump, and the amount is cut as without one.
    #[test]
    fn a_send_all_bump_with_a_spare_coin_keeps_the_amount_at_every_size_near_the_limit() {
        let mut alone = Fake::new();
        alone.fund(&addr(Chain::External, 0), 10_000_000, '1');
        let (earlier, _) = alone.send_all_at(SOMEONE, 1).unwrap();
        assert_outputs(&earlier, &[(SOMEONE, 9_999_890)]);
        let change = addr(Chain::Internal, 0);
        let change = change.as_str();
        for (spare, fee, outputs) in [
            (1_762_089, 1_089_000, vec![(SOMEONE, 8_911_000)]),
            (1_762_090, 1_762_200, vec![(SOMEONE, 9_999_890)]),
            (1_779_890, 1_780_000, vec![(SOMEONE, 9_999_890)]),
            (
                1_779_891,
                1_779_707,
                vec![(SOMEONE, 9_999_890), (change, 294)],
            ),
            (
                2_000_000,
                1_999_816,
                vec![(SOMEONE, 9_999_890), (change, 294)],
            ),
            (
                2_069_283,
                2_069_099,
                vec![(SOMEONE, 9_999_890), (change, 294)],
            ),
            (
                2_069_284,
                2_069_100,
                vec![(SOMEONE, 9_999_890), (change, 294)],
            ),
        ] {
            let mut fake = Fake::new();
            fake.fund(&addr(Chain::External, 0), 10_000_000, '1');
            fake.fund(&addr(Chain::External, 1), spare, '2');
            let bump = fake
                .replacement(&serialize_hex(&earlier), 9_900, Replacement::FeeBump)
                .unwrap_or_else(|e| panic!("spare {spare}: {e:?}"));
            assert_outputs(&bump.tx, &outputs);
            assert_eq!(
                (bump.fee_sats, fee_paid(&fake, &bump.tx)),
                (fee, fee),
                "spare {spare}"
            );
            assert_eq!(
                bump.amount_cut_sats,
                9_999_890 - outputs[0].1,
                "spare {spare}"
            );
            assert!(
                fee <= MAX_FEE_RATE * bump.tx.vsize() as u64,
                "spare {spare}"
            );
            assert_replaces(&fake, &earlier, &bump.tx);
        }
    }

    // The round-3 review's repros. The bump walk kept the largest spare coin, so near the limit it pushed what
    // is left past 10,000 sat/vB, change at its floor paid less than rule 6 asks, and the bump was refused or cut,
    // while the next smaller coin alone paid at the rate asked. Once the walk as it was finds no bump, the search
    // over sets of spare coins finds that coin, so every bump the walk signed is still signed the same.
    #[test]
    fn a_fee_bump_skips_a_spare_coin_that_passes_the_limit_for_a_smaller_one_that_pays() {
        // A payment of 100,000 with change 294 at 9,000 sat/vB, 1,269,000 on 141 vB.
        let mut alone = Fake::new();
        alone.fund(&addr(Chain::External, 0), 1_369_294, '1');
        let earlier = alone.send(SOMEONE, 100_000, 9_000).unwrap();
        assert_outputs(
            &earlier,
            &[(SOMEONE, 100_000), (&addr(Chain::Internal, 0), 294)],
        );
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 1_369_294, '1');
        fake.fund(&addr(Chain::External, 1), 560_000, '2');
        fake.fund(&addr(Chain::External, 2), 500_000, '3');
        let hex = serialize_hex(&earlier);
        // At 9,900 the 560,000 coin leaves 1,829,294 on 178 vB and change at its floor is under rule 6 on 209 vB.
        let bump = fake.replacement(&hex, 9_900, Replacement::FeeBump).unwrap();
        assert_eq!(inputs_of(&bump.tx)[1].txid.to_string(), id('3'));
        assert_outputs(&bump.tx, &[(SOMEONE, 100_000)]);
        assert_eq!(
            (bump.fee_sats, bump.amount_cut_sats, bump.tx.vsize()),
            (1_769_294, 0, 178)
        );
        assert_replaces(&fake, &earlier, &bump.tx);
        // At 9,001, rule 6's least, the walk as it was signs with both spare coins on 246 vB, and still does.
        let (kept, fee) = fake.replace(&earlier, 9_000, Replacement::FeeBump).unwrap();
        assert_eq!((kept.input.len(), fee, kept.vsize()), (3, 2_329_294, 246));
        assert_outputs(&kept, &[(SOMEONE, 100_000)]);
        assert_replaces(&fake, &earlier, &kept);

        // A send all of 922,000 at 9,800 sat/vB, 1,078,000 on 110 vB, was cut by 11,000 with spare coins of 800,000
        // and 690,000. The 690,000 coin alone keeps the amount.
        let mut alone = Fake::new();
        alone.fund(&addr(Chain::External, 0), 2_000_000, '1');
        let (earlier, _) = alone.send_all_at(SOMEONE, 9_800).unwrap();
        assert_outputs(&earlier, &[(SOMEONE, 922_000)]);
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 2_000_000, '1');
        fake.fund(&addr(Chain::External, 1), 800_000, '2');
        fake.fund(&addr(Chain::External, 2), 690_000, '3');
        let bump = fake
            .replacement(&serialize_hex(&earlier), 9_900, Replacement::FeeBump)
            .unwrap();
        assert_eq!(inputs_of(&bump.tx)[1].txid.to_string(), id('3'));
        assert_outputs(&bump.tx, &[(SOMEONE, 922_000)]);
        assert_eq!(
            (bump.fee_sats, bump.amount_cut_sats, bump.tx.vsize()),
            (1_768_000, 0, 178)
        );
        assert_replaces(&fake, &earlier, &bump.tx);
    }

    // Bitcoin Core truncates both rates to whole sat/kvB and refuses a replacement whose rate is not above. A payment
    // at 9,000 sat/vB whose leftover went into the fee paid 990,001 on 110 vB, 9,000,009 sat/kvB. Its bump at 9,900 with
    // a spare coin of 891,295 kept change at its floor for 1,881,002 on 209 vB, above as a fraction and 9,000,009 once
    // truncated. Change at its floor now asks 1,881,003, which that coin cannot leave, so the amount is cut as without it.
    #[test]
    fn a_fee_bump_with_change_at_its_floor_meets_rule_6_in_whole_sat_per_kvb() {
        let mut alone = Fake::new();
        alone.fund(&addr(Chain::External, 0), 2_000_000, '1');
        let earlier = alone.payment(SOMEONE, 1_009_999, 9_000).unwrap();
        assert_eq!((earlier.fee_sats, earlier.tx.vsize()), (990_001, 110));
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 2_000_000, '1');
        fake.fund(&addr(Chain::External, 1), 891_295, '2');
        let bump = fake
            .replacement(&serialize_hex(&earlier.tx), 9_900, Replacement::FeeBump)
            .unwrap();
        assert_outputs(&bump.tx, &[(SOMEONE, 911_000)]);
        assert_eq!(
            (bump.fee_sats, bump.amount_cut_sats, bump.tx.vsize()),
            (1_089_000, 98_999, 110)
        );
        assert_replaces(&fake, &earlier.tx, &bump.tx);
    }

    // The round-4 review's repros. Near the limit a bump's fee must land between rule 6's least and 10,000 sat/vB on
    // its signed size, and both walks over the spare coins missed the pair that lands there, so each bump was cut or
    // refused. Each now keeps the amount with that pair and no change, on 246 vB.
    #[test]
    fn a_fee_bump_keeps_the_amount_with_a_set_of_spare_coins_no_walk_finds() {
        // A payment whose leftover went into its fee, 1,009,999 at 9,000 sat/vB: 990,001 on 110 vB.
        let mut alone = Fake::new();
        alone.fund(&addr(Chain::External, 0), 2_000_000, '1');
        let fit = alone.payment(SOMEONE, 1_009_999, 9_000).unwrap();
        assert_eq!((fit.fee_sats, fit.tx.vsize()), (990_001, 110));
        // A payment of 100,000 with change 294 at 9,000 sat/vB.
        let mut alone = Fake::new();
        alone.fund(&addr(Chain::External, 0), 1_369_294, '1');
        let with_change = alone.send(SOMEONE, 100_000, 9_000).unwrap();
        assert_outputs(
            &with_change,
            &[(SOMEONE, 100_000), (&addr(Chain::Internal, 0), 294)],
        );
        // Send alls of 2,000,000 at 9,000 and of 1,732,853 at 9,800.
        let mut alone = Fake::new();
        alone.fund(&addr(Chain::External, 0), 2_000_000, '1');
        let (send_all, _) = alone.send_all_at(SOMEONE, 9_000).unwrap();
        let mut alone = Fake::new();
        alone.fund(&addr(Chain::External, 0), 1_732_853, '1');
        let (seed_777, _) = alone.send_all_at(SOMEONE, 9_800).unwrap();
        for (earlier, held, spares, rate, amount, fee) in [
            (
                &fit.tx,
                2_000_000,
                &[850_000, 629_999, 604_999][..],
                9_900,
                1_009_999,
                2_445_000,
            ),
            (
                &with_change,
                1_369_294,
                &[600_000, 595_000, 580_000],
                9_900,
                100_000,
                2_449_294,
            ),
            (
                &with_change,
                1_369_294,
                &[611_000, 600_000, 556_000],
                9_900,
                100_000,
                2_436_294,
            ),
            (
                &send_all,
                2_000_000,
                &[880_000, 600_000, 580_000],
                9_900,
                1_010_000,
                2_450_000,
            ),
            (
                &seed_777,
                1_732_853,
                &[501_594, 718_324, 5_069, 714_455, 649_244, 558_285],
                9_800,
                654_853,
                2_445_568,
            ),
        ] {
            let mut fake = Fake::new();
            fake.fund(&addr(Chain::External, 0), held, '1');
            for (i, value) in spares.iter().enumerate() {
                let digit = char::from(b'2' + i as u8);
                fake.fund(&addr(Chain::External, 1 + i as u32), *value, digit);
            }
            SETS_TRIED.with(|n| n.set(0));
            let bump = fake
                .replacement(&serialize_hex(earlier), rate, Replacement::FeeBump)
                .unwrap_or_else(|e| panic!("spares {spares:?}: {e:?}"));
            assert!(SETS_TRIED.with(|n| n.get()) > 0, "spares {spares:?}");
            assert_outputs(&bump.tx, &[(SOMEONE, amount)]);
            assert_eq!(
                (bump.fee_sats, bump.amount_cut_sats, bump.tx.vsize()),
                (fee, 0, 246),
                "spares {spares:?}"
            );
            assert!(fee <= MAX_FEE_RATE * 246, "spares {spares:?}");
            assert_replaces(&fake, earlier, &bump.tx);
        }
    }

    // A fee bump keeps the amount if and only if some set of the spare coins settles by the same `settle`, fee and
    // least it signs with, over earlier payments and send alls at up to 9,900 sat/vB with 1 to 7 spare coins, bumped at
    // 9,000 to 10,000. Where the walk finds a bump it signs as it did before the search, the first run of the largest
    // coins that settles; else it signs the first set that settles, fewest coins first and then the larger coins. What
    // signs replaces the earlier payment within the network's limit, and a bump no set keeps is cut or refused. Each
    // earlier payment is bumped with its first draw of spare coins and with the first of 200 draws that only the search
    // signs, so that rare case is tried every time.
    #[test]
    fn a_fee_bump_keeps_the_amount_if_and_only_if_some_set_of_spare_coins_can() {
        let mut rnd = xorshift(0xD1B5_4A32_D192_ED03);
        let (mut walked, mut searched, mut cut, mut refused) = (0, 0, 0, 0);
        for case in 0..60 {
            let (mut alone, mut held) = (Fake::new(), Vec::new());
            for digit in ['1', '2'].into_iter().take(1 + rnd(2) as usize) {
                let value = [20_000, 300_000, 1_400_000, 3_000_000][rnd(4) as usize] + rnd(900_000);
                alone.fund(&addr(Chain::External, held.len() as u32), value, digit);
                held.push((value, digit));
            }
            let rate =
                [1, 7, 300, 3_000, 7_800, 8_500, 9_000, 9_500, 9_800, 9_900][rnd(10) as usize];
            let earlier = match rnd(3) {
                0 => alone.send_all_at(SOMEONE, rate).map(|(tx, _)| tx),
                _ => alone.send(SOMEONE, 294 + rnd(held.iter().map(|c| c.0).sum()), rate),
            };
            let Ok(earlier) = earlier else { continue };
            let spent: Vec<String> = inputs_of(&earlier)
                .iter()
                .map(|o| o.txid.to_string())
                .collect();
            let (old_fee, old_vsize) = (fee_paid(&alone, &earlier), earlier.vsize() as u64);
            let asked = 9_000 + rnd(1_001);
            let rate = asked.max(old_fee / old_vsize + 1);
            let fee = |size: u64| (rate * size).max(old_fee + size);
            let kvb = old_fee * 1000 / old_vsize + 1;
            let least = |size: u64| (old_fee + size).max((kvb * size).div_ceil(1000));
            let amount = earlier.output[0].value.to_sat();
            let had = old_fee + earlier.output.iter().map(|o| o.value.to_sat()).sum::<u64>();
            let scripts = [earlier.output[0].script_pubkey.len(), P2WPKH_SCRIPT];
            // The coins the earlier payment left and `drawn`, largest first and ties by txid.
            let spares_of = |drawn: &[(u64, char)]| {
                let mut spares: Vec<(u64, char)> = held
                    .iter()
                    .filter(|(_, digit)| !spent.contains(&id(*digit)))
                    .chain(drawn)
                    .copied()
                    .collect();
                spares.sort_by(|a, b| b.0.cmp(&a.0).then(a.1.cmp(&b.1)));
                spares
            };
            // The bump these spare coins should sign: the coins it adds as indexes into them, its fee, and whether
            // the walk finds it.
            let expect = |spares: &[(u64, char)]| {
                let pays = |set: &[usize]| {
                    let spends = vec![OWN_SPEND; spent.len() + set.len()];
                    let left = had + set.iter().map(|&i| spares[i].0).sum::<u64>() - amount;
                    let sizes = (vsize(&spends, &scripts), vsize(&spends, &scripts[..1]));
                    let settled = settle(left, sizes.0, sizes.1, 294, &fee, &least);
                    settled.ok().map(|settled| match settled {
                        Settle::Change(rest) | Settle::FloorChange(rest) => left - rest,
                        Settle::Fold(_) | Settle::Fit(_) => left,
                    })
                };
                let n = spares.len();
                let walk = (0..=n)
                    .map(|k| (0..k).collect::<Vec<_>>())
                    .find(|run| pays(run).is_some());
                let mut sets: Vec<Vec<usize>> = (0..1u32 << n)
                    .map(|set| (0..n).filter(|i| set >> i & 1 == 1).collect())
                    .collect();
                sets.sort_by(|a, b| a.len().cmp(&b.len()).then(a.cmp(b)));
                let found = match walk {
                    Some(run) => Some((run, true)),
                    None => sets
                        .into_iter()
                        .find(|set| pays(set).is_some())
                        .map(|set| (set, false)),
                };
                let found = found.filter(|_| rate <= MAX_FEE_RATE);
                found.map(|(set, by_walk)| (pays(&set).unwrap(), set, by_walk))
            };
            let draws: Vec<Vec<(u64, char)>> = (0..200)
                .map(|_| {
                    ('3'..='9')
                        .take(1 + rnd(7) as usize)
                        .map(|digit| match rnd(4) {
                            0 => (300 + rnd(5_000), digit),
                            1 => (300 + rnd(2_000_000), digit),
                            2 => (500_000 + rnd(300_000), digit),
                            _ => (640_000 + rnd(80_000), digit),
                        })
                        .collect()
                })
                .collect();
            let only_search = draws
                .iter()
                .find(|drawn| matches!(expect(&spares_of(drawn)), Some((_, _, false))));
            for drawn in [Some(&draws[0]), only_search].into_iter().flatten() {
                let spares = spares_of(drawn);
                let mut fake = Fake::new();
                for (i, (value, digit)) in held.iter().chain(drawn).enumerate() {
                    fake.fund(&addr(Chain::External, i as u32), *value, *digit);
                }
                let bump = fake.replacement(&serialize_hex(&earlier), asked, Replacement::FeeBump);
                let case = format!(
                    "case {case}: held {held:?}, spares {spares:?}, earlier {old_fee} on {old_vsize} vB to {amount}, bump at {asked}: {:?}",
                    bump.as_ref()
                        .map(|b| (b.fee_sats, b.amount_cut_sats, inputs_of(&b.tx)))
                );
                match (expect(&spares), &bump) {
                    (Some((fee, set, by_walk)), Ok(b)) => {
                        let added: Vec<String> = inputs_of(&b.tx)[spent.len()..]
                            .iter()
                            .map(|o| o.txid.to_string())
                            .collect();
                        let want: Vec<String> = set.iter().map(|&i| id(spares[i].1)).collect();
                        assert_eq!(
                            (added, b.fee_sats, b.amount_cut_sats),
                            (want, fee, 0),
                            "{case}"
                        );
                        if by_walk {
                            walked += 1;
                        } else {
                            searched += 1;
                        }
                    }
                    (Some(_), Err(_)) => panic!("refused while a set keeps the amount, {case}"),
                    (None, Ok(b)) => {
                        assert!(b.amount_cut_sats > 0, "{case}");
                        cut += 1;
                    }
                    (None, Err(_)) => refused += 1,
                }
                if let Ok(b) = &bump {
                    assert_replaces(&fake, &earlier, &b.tx);
                    assert!(b.fee_sats <= MAX_FEE_RATE * b.tx.vsize() as u64, "{case}");
                }
            }
        }
        eprintln!("{walked} walked, {searched} searched, {cut} cut, {refused} refused");
        assert!(
            walked > 0 && searched > 0 && cut > 0 && refused > 0,
            "{walked} walked, {searched} searched, {cut} cut, {refused} refused"
        );
    }

    // The search tries every set of the 16 largest spare coins and no other. A payment of 100,000 with change 294 at
    // 9,000 sat/vB, bumped at 9,900: a spare coin of 560,000 alone passes the limit and two or more fall short, while
    // one of 500,000 alone pays 1,769,294 on 178 vB. Fifteen coins of 560,000 and one of 500,000 sign with the 500,000.
    // Sixteen of 560,000 leave four coins of 500,000 out of reach, and the bump is refused after all 65,535 sets.
    #[test]
    fn a_fee_bump_searches_the_16_largest_spare_coins_and_no_others() {
        let mut alone = Fake::new();
        alone.fund(&addr(Chain::External, 0), 1_369_294, '1');
        let earlier = alone.send(SOMEONE, 100_000, 9_000).unwrap();
        let bump = |large: u32, small: u32| {
            let mut fake = Fake::new();
            fake.fund(&addr(Chain::External, 0), 1_369_294, '1');
            for (index, digit, count, value) in [(1, '5', large, 560_000), (2, '6', small, 500_000)]
            {
                let coins: Vec<String> = (0..count).map(|i| coin(digit, i, value, true)).collect();
                fake.stats.insert(
                    addr(Chain::External, index),
                    used_stats(value * u64::from(count)),
                );
                fake.utxos.insert(
                    addr(Chain::External, index),
                    format!("[{}]", coins.join(",")),
                );
            }
            let start = Instant::now();
            let got = fake.replacement(&serialize_hex(&earlier), 9_900, Replacement::FeeBump);
            (got, start.elapsed(), fake)
        };
        let (signed, _, fake) = bump(15, 1);
        let signed = signed.unwrap();
        assert_eq!(inputs_of(&signed.tx)[1].txid.to_string(), id('6'));
        assert_eq!(
            (signed.fee_sats, signed.amount_cut_sats, signed.tx.vsize()),
            (1_769_294, 0, 178)
        );
        assert_replaces(&fake, &earlier, &signed.tx);
        let (refused, took, _) = bump(16, 4);
        eprintln!("16 spare coins of 560,000 and 4 of 500,000 refused in {took:?}");
        assert_refused(
            refused.map(|b| b.fee_sats),
            "replace: fee 1829294 for 178 vB is above the network's 10000 sat/vB limit",
        );
        assert!(took < Duration::from_secs(2), "{took:?}");
    }

    // An earlier payment at 9,999.5 sat/vB (fee 1,409,930 on 141 vB): rules 3 and 4 ask its change 500 to pay
    // 1,410,071 on 141 vB, above the limit, so the bump adds the spare coin instead of a change the node refuses.
    #[test]
    fn a_fee_bump_adds_a_spare_coin_where_its_change_would_pay_above_the_limit() {
        let value = 100_000 + 500 + 1_409_930;
        let earlier = hand_made('1', value, 100_000, 500);
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), value, '1');
        fake.fund(&addr(Chain::External, 1), 700_000, '2');
        let bump = fake
            .replacement(&serialize_hex(&earlier), 10_000, Replacement::FeeBump)
            .unwrap();
        assert_eq!(
            (
                bump.fee_sats,
                bump.amount_cut_sats,
                bump.tx.vsize(),
                bump.tx.input.len()
            ),
            (2_090_000, 0, 209, 2)
        );
        assert_replaces(&fake, &earlier, &bump.tx);
    }

    // 300 coins of 10,000 paid at 1 sat/vB, bumped to 2 with 16 spare coins of 1,000: no set of them pays,
    // so the bump refuses as the walk left it, with none of the 65,535 sets tried.
    #[test]
    fn a_fee_bump_no_set_of_spare_coins_can_pay_refuses_without_the_search() {
        let held = |fake: &mut Fake| {
            let coins: Vec<String> = (0..300).map(|i| coin('1', i, 10_000, true)).collect();
            fake.stats
                .insert(addr(Chain::External, 0), used_stats(3_000_000));
            fake.utxos
                .insert(addr(Chain::External, 0), format!("[{}]", coins.join(",")));
        };
        let mut alone = Fake::new();
        held(&mut alone);
        let fee = vsize(&[OWN_SPEND; 300], &[P2WPKH_SCRIPT, P2WPKH_SCRIPT]);
        let earlier = alone.send(SOMEONE, 3_000_000 - fee - 1_000, 1).unwrap();
        let bump = |spares: u32| {
            let mut fake = Fake::new();
            held(&mut fake);
            let coins: Vec<String> = (0..spares).map(|i| coin('5', i, 1_000, true)).collect();
            fake.stats.insert(
                addr(Chain::External, 1),
                used_stats(1_000 * u64::from(spares)),
            );
            fake.utxos
                .insert(addr(Chain::External, 1), format!("[{}]", coins.join(",")));
            SETS_TRIED.with(|n| n.set(0));
            fake.replacement(&serialize_hex(&earlier), 2, Replacement::FeeBump)
                .map(|b| b.fee_sats)
        };
        assert_refused(
            bump(16),
            "insufficient funds: have 3016000 confirmed, need 3021651",
        );
        assert_eq!(SETS_TRIED.with(|n| n.get()), 0);
    }

    #[test]
    fn a_replacement_is_refused_before_any_lookup_for_bytes_that_are_not_a_payment_or_a_rate_out_of_range()
     {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 10_000, '1');
        let hex = serialize_hex(&fake.send(SOMEONE, 5_000, 2).unwrap());
        let fresh = Fake::new();
        let not_one = "replace: not this wallet's payment: not one transaction";
        for kind in KINDS {
            for bytes in ["", "zz", "00", &hex[..40], &format!("{hex}00")] {
                assert_refused(fresh.replace_hex(bytes, 5, kind), not_one);
            }
            assert_refused(
                fresh.replace_hex(&hex, 0, kind),
                "fee rate 0 is below 1 sat/vB",
            );
            assert_refused(
                fresh.replace_hex(&hex, 10_001, kind),
                "fee rate 10001 is above the network's 10000 sat/vB limit",
            );
        }
        assert_eq!(fresh.requests(), 0);
    }

    #[test]
    fn a_replacement_is_refused_for_a_payment_that_is_not_this_wallets() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 10_000, '1');
        let earlier = fake.send(SOMEONE, 5_000, 2).unwrap();
        let changed = |change: &dyn Fn(&mut Transaction)| {
            let mut tx = earlier.clone();
            change(&mut tx);
            tx
        };
        let someone = Address::from_str(SOMEONE)
            .unwrap()
            .assume_checked()
            .script_pubkey();
        let receive = own(Chain::External, 1).address.script_pubkey();
        let cases = [
            (
                changed(&|tx| tx.output[1].script_pubkey = someone.clone()),
                "its second output is not this wallet's change",
            ),
            (
                changed(&|tx| tx.output[1].script_pubkey = receive.clone()),
                "its second output is not this wallet's change",
            ),
            (
                changed(&|tx| tx.output.push(tx.output[1].clone())),
                "it needs inputs and one or two outputs",
            ),
            (
                changed(&|tx| tx.output[0].value = Amount::from_sat(293)),
                "its first output is below the dust limit",
            ),
            // At the floor the check passes, and the changed value is caught by the signature.
            (
                changed(&|tx| tx.output[0].value = Amount::from_sat(294)),
                "it is not signed by this wallet",
            ),
            (
                changed(&|tx| tx.input.push(tx.input[0].clone())),
                "it spends a coin twice",
            ),
            // Signed as it is, because a value changed after signing is refused as unsigned first.
            (
                hand_made('1', 10_000, 20_000, 4_718),
                "it pays out more than its coins hold",
            ),
        ];
        for kind in KINDS {
            for (tx, why) in &cases {
                assert_refused(
                    fake.replace(tx, 5, kind),
                    &format!("replace: not this wallet's payment: {why}"),
                );
            }
        }
    }

    // The guard's bytes come from app storage, so bytes this wallet did not sign could aim a fee bump at
    // another address, and the card names no address. Every earlier input must carry this wallet's own
    // SIGHASH_ALL signature for its coin, which commits to every output.
    #[test]
    fn a_replacement_is_refused_for_a_payment_this_wallet_did_not_sign() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 10_000, '1');
        let earlier = fake.send(SOMEONE, 5_000, 2).unwrap();
        let changed = |change: &dyn Fn(&mut Transaction)| {
            let mut tx = earlier.clone();
            change(&mut tx);
            tx
        };
        let thief = ScriptBuf::new_p2wpkh(&bitcoin::WPubkeyHash::from_byte_array([0xaa; 20]));
        let key = own(Chain::External, 0);
        let sign_as = |tx: &mut Transaction, hash_type: EcdsaSighashType| {
            let script = key.address.script_pubkey();
            let sighash = SighashCache::new(&*tx)
                .p2wpkh_signature_hash(0, &script, Amount::from_sat(10_000), hash_type)
                .unwrap();
            let msg = Message::from_digest(sighash.to_byte_array());
            let mut der = Secp256k1::new()
                .sign_ecdsa(&msg, &key.privkey.inner)
                .serialize_der()
                .to_vec();
            der.push(hash_type as u8);
            tx.input[0].witness = Witness::from_slice(&[der.as_slice(), &key.pubkey.to_bytes()]);
        };
        let coin = Utxo {
            txid: Txid::from_str(&id('1')).unwrap(),
            vout: 0,
            value: 10_000,
            confirmed: true,
        };
        let cases = [
            (
                "output 0 aimed elsewhere after signing",
                changed(&|tx| tx.output[0].script_pubkey = thief.clone()),
            ),
            (
                "the witness stripped",
                changed(&|tx| tx.input[0].witness = Witness::new()),
            ),
            (
                "signed by another key of this wallet",
                signed(
                    &EsploraSource(&Refusing("a Native SegWit coin is never read")),
                    &[(&coin, &own(Chain::External, 1))],
                    earlier.output.clone(),
                )
                .unwrap(),
            ),
            (
                "signed SIGHASH_NONE, then output 0 aimed elsewhere",
                changed(&|tx| {
                    sign_as(tx, EcdsaSighashType::None);
                    tx.output[0].script_pubkey = thief.clone();
                }),
            ),
            (
                "the type byte after its signature is not ALL",
                changed(&|tx| {
                    let mut items: Vec<Vec<u8>> =
                        tx.input[0].witness.iter().map(<[u8]>::to_vec).collect();
                    *items[0].last_mut().unwrap() = EcdsaSighashType::AllPlusAnyoneCanPay as u8;
                    tx.input[0].witness = Witness::from_slice(&items);
                }),
            ),
        ];
        let refusal = "replace: not this wallet's payment: it is not signed by this wallet";
        let mut wrong = Vec::new();
        for kind in KINDS {
            for (case, tx) in &cases {
                match fake.replace(tx, 5, kind) {
                    Err(MobileSignerError::InvalidPsbtJson(m)) if m == refusal => {}
                    other => wrong.push(format!(
                        "{kind:?}, {case}: {:?}",
                        other.map(|(tx, _)| tx.output.iter().map(pays).collect::<Vec<_>>())
                    )),
                }
            }
        }
        assert!(
            wrong.is_empty(),
            "not refused as unsigned:\n{}",
            wrong.join("\n")
        );
    }

    // Something spent a coin of the earlier payment, most likely that payment itself, so it may be out.
    #[test]
    fn a_replacement_is_refused_when_a_coin_of_the_earlier_payment_is_no_longer_spendable() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 10_000, '1');
        fake.fund(&addr(Chain::External, 1), 4_000, '2');
        let earlier = fake.send(SOMEONE, 12_000, 2).unwrap();
        assert_eq!(inputs_of(&earlier).len(), 2);
        fake.utxos.insert(addr(Chain::External, 1), "[]".into());
        for kind in KINDS {
            assert_refused(
                fake.replace(&earlier, 5, kind),
                &format!(
                    "replace: coins spent: {}:0 is not a coin this wallet can spend",
                    id('2')
                ),
            );
        }
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

    /// Three used receive addresses and one used change address, each funded. Discovery is 23 + 21 = 44 lookups
    /// and the proofs of receive 2 and change 0, so 54 requests. A routine sync is 3 + 5 and 1 + 5 = 14, and a
    /// full pass 44 again, and neither proves what was proven. Each snapshot adds the four coin lists.
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
        assert_eq!(fake.requests(), 54 + coins);
        fake.tick(just_under(SHARE_TTL));
        assert_eq!(fake.snapshot("").unwrap().balance_sats, 4_000);
        fake.history().unwrap();
        assert_eq!(
            fake.requests(),
            58 + coins + 5,
            "shared: the coin lists, four pages and the tip"
        );
        fake.tick(Duration::from_millis(1));
        assert_eq!(
            receive_and_balance(fake.snapshot("").unwrap()),
            (addr(Chain::External, 3), 4_000)
        );
        assert_eq!(fake.requests(), 67 + 14 + coins, "incremental");
        fake.history().unwrap();
        assert_eq!(fake.requests(), 85 + 5, "shared with the incremental scan");
        fake.tick(PUBLIC_FULL_PASS);
        assert_eq!(fake.snapshot("").unwrap().balance_sats, 4_000);
        assert_eq!(
            fake.requests(),
            90 + 44 + coins,
            "a full pass after PUBLIC_FULL_PASS asks every index again and proves nothing again"
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
        // The full scan's two proofs, the proof of index 3 that moved the scan past it, and the coin
        // lists of the four, then five, funded addresses.
        assert_eq!(
            fake.requests(),
            44 + 2 * PROOF_READS + 4 + 30 + PROOF_READS + 5
        );
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
        fake.tick(PUBLIC_FULL_PASS);
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
            "insufficient funds: have 0 confirmed, need 2110",
        );
        assert_eq!(fake.requests(), before + 8);
    }

    // The mutation check of S4: make a payment forget the scan again, and the counts below turn into the full
    // discovery's.
    #[test]
    fn a_broadcast_that_went_out_or_is_unclear_makes_the_next_call_a_routine_sync_and_a_refusal_shares_the_last()
     {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 10_000, '1');
        let ready = fake.ready(SOMEONE, 5_000, 2);
        // Discovery with the proof of receive 0, and the one funded address's coin list that every snapshot and
        // payment asks. A routine sync asks receive 0 and the five after it, and the first five of change.
        let (full, routine, coins) = (41 + PROOF_READS, 6 + 5, 1);
        assert_eq!(fake.requests(), full + coins);

        assert!(fake.broadcast(&ready, answer(400, NODE_REFUSES)).is_err());
        fake.snapshot("").unwrap();
        assert_eq!(
            fake.requests(),
            full + 2 * coins,
            "a refusal posted nothing: the sync is shared, only the coin list is asked"
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
            full + routine + 3 * coins,
            "sent: the next call is a routine sync, inside the share window too"
        );

        match fake.broadcast(&ready, no_answer()) {
            Err(MobileSignerError::BroadcastUnknown(t)) => assert_eq!(t, ready.txid),
            other => panic!("expected BroadcastUnknown, got {other:?}"),
        }
        fake.snapshot("").unwrap();
        assert_eq!(
            fake.requests(),
            full + 2 * routine + 4 * coins,
            "unclear: the next call is a routine sync"
        );
    }

    const EVERY_HOST_LIMITED: &str = "esplora get: no testnet4 host is reachable (https://blockstream.info/testnet4/api: HTTP status 429, limited for 42 s more; https://mempool.space/testnet4/api: HTTP status 429, limited for 42 s more)";

    // Home retries a `Continuing` snapshot by itself and shows the address the stored scan hands out, where a
    // thrown one shows a dash and index 0 and never retries. Any other failure of the host choice still throws.
    #[test]
    fn a_sync_with_every_host_limited_goes_on_later_and_starts_no_full_pass() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 10_000, '1');
        assert_eq!(fake.snapshot("").unwrap().scan, HotScan::Complete);
        fake.tick(SHARE_TTL);
        let before = fake.requests();
        let limited = snapshot_from(
            &EsploraSource(&Refusing(EVERY_HOST_LIMITED)),
            &abandon_about(),
            "",
            KeyNetwork::Test,
            AddressType::NativeSegwit,
            &|| fake.now(),
            &fake.store,
        )
        .unwrap();
        assert_eq!(limited.scan, HotScan::Continuing);
        assert_eq!(limited.receive_address, addr(Chain::External, 1));
        assert_eq!(
            fake.requests(),
            before,
            "no request while every host is limited"
        );
        fake.tick(SHARE_TTL);
        let recovered = fake.snapshot("").unwrap();
        assert_eq!(
            (recovered.scan, recovered.balance_sats),
            (HotScan::Complete, 10_000)
        );
        assert_eq!(
            fake.requests(),
            before + 6 + 5 + 1,
            "a routine sync, receive 0 and the five after it, the first five of change, and the coin list"
        );
        let down =
            "esplora get: no testnet4 host is reachable (a: Connection Failed; b: timed out)";
        assert!(
            snapshot_from(
                &EsploraSource(&Refusing(down)),
                &abandon_about(),
                "",
                KeyNetwork::Test,
                AddressType::NativeSegwit,
                &|| fake.now(),
                &fake.store,
            )
            .is_err()
        );
    }

    // The coin lists a complete sync asks next meet the same request limit as its reads, so Home retries that snapshot
    // by itself too.
    #[test]
    fn a_429_on_the_coin_list_ends_the_snapshot_continuing_and_any_other_failure_still_throws() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 10_000, '1');
        let coin_list_fails = |text: &str| {
            snapshot_from(
                &EsploraSource(&Reads(&|url| {
                    if url.ends_with("/utxo") {
                        Err(MobileSignerError::InvalidPsbtJson(text.into()))
                    } else {
                        fake.get(url)
                    }
                })),
                &abandon_about(),
                "",
                KeyNetwork::Test,
                AddressType::NativeSegwit,
                &|| fake.now(),
                &fake.store,
            )
        };
        let limited = coin_list_fails(
            "esplora get: https://blockstream.info/testnet4/api: HTTP status 429, limited for 60 s more",
        )
        .unwrap();
        assert_eq!(
            (limited.scan, limited.spendable_sats),
            (HotScan::Continuing, 0)
        );
        assert_eq!(limited.receive_address, addr(Chain::External, 1));
        assert!(coin_list_fails("esplora get: HTTP status 503, gave up after 3 attempts").is_err());
        let free = fake.snapshot("").unwrap();
        assert_eq!(
            (free.scan, free.spendable_sats),
            (HotScan::Complete, 10_000)
        );
    }

    // Inside the share window the sync is shared, so the coin lists are the snapshot's only reads.
    #[test]
    fn every_host_limited_inside_the_share_window_ends_the_snapshot_continuing() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 10_000, '1');
        assert_eq!(fake.snapshot("").unwrap().scan, HotScan::Complete);
        let refused = |text: &'static str| {
            snapshot_from(
                &EsploraSource(&Refusing(text)),
                &abandon_about(),
                "",
                KeyNetwork::Test,
                AddressType::NativeSegwit,
                &|| fake.now(),
                &fake.store,
            )
        };
        let limited = refused(EVERY_HOST_LIMITED).unwrap();
        assert_eq!(
            (limited.scan, limited.spendable_sats),
            (HotScan::Continuing, 0)
        );
        assert_eq!(limited.receive_address, addr(Chain::External, 1));
        assert!(
            refused(
                "esplora get: no testnet4 host is reachable (a: Connection Failed; b: timed out)"
            )
            .is_err()
        );
        let free = fake.snapshot("").unwrap();
        assert_eq!(
            (free.scan, free.spendable_sats),
            (HotScan::Complete, 10_000)
        );
    }

    // Fix 2 of issue #26. A payment marks the scan stale, not wiped, so the sync after it is routine and
    // still sees the spent coin gone and the change arrived, inside the share window of the sync before it.
    #[test]
    fn after_a_broadcast_the_spent_coin_and_the_change_are_right_without_a_full_pass() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 10_000, '1');
        assert_eq!(fake.snapshot("").unwrap().balance_sats, 10_000);
        let ready = fake.ready(SOMEONE, 3_000, 1);
        let change = 10_000 - 3_000 - ready.fee_sats;
        let paid: Transaction = deserialize_hex(&ready.hex).unwrap();
        assert_outputs(
            &paid,
            &[(SOMEONE, 3_000), (&addr(Chain::Internal, 0), change)],
        );
        fake.broadcast(&ready, answer(200, &ready.txid)).unwrap();
        let (snapshot, asked) = synced_after_payment(
            &mut fake,
            &[&addr(Chain::External, 0)],
            (&addr(Chain::Internal, 0), change, 'b'),
        );
        assert_eq!(
            (snapshot.balance_sats, snapshot.spendable_sats),
            (change, change)
        );
        assert_eq!(
            asked,
            6 + 5 + 15 + 1 + 1,
            "receive 0 and the five after it, change 0 to 4, then change 5 to 19 once 0 turned used, the change's coin list, and the change's parent"
        );
        let next = fake.send(SOMEONE, 1_000, 1).unwrap();
        assert_eq!(next.input.len(), 1, "the change coin alone");
        let spent = &next.input[0].previous_output;
        assert_eq!((spent.txid.to_string(), spent.vout), (id('b'), 0));
        assert_eq!(
            pays(&next.output[1]),
            addr(Chain::Internal, 1),
            "the next change moves on"
        );
    }

    /// The chain after a payment that is out: each address in `spent` emptied, and `back` the change it paid
    /// this wallet, still in the mempool. Then one snapshot, with the requests it cost.
    fn synced_after_payment(
        fake: &mut Fake,
        spent: &[&str],
        back: (&str, u64, char),
    ) -> (HotWalletSnapshot, usize) {
        for address in spent {
            fake.touch(address);
            fake.utxos.insert((*address).to_string(), "[]".into());
        }
        fake.fund_change(back.0, back.1, back.2);
        let before = fake.requests();
        let snapshot = fake.snapshot("").unwrap();
        let asked = fake.requests() - before;
        (snapshot, asked)
    }

    // A cancel pays every coin back to the earlier payment's change script. The next routine sync asks the
    // funded receive addresses, the five past the last used one, change 0 to 4, then change 5 to 19 once
    // 0 turned used, the two coin lists and the change's parent.
    #[test]
    fn a_cancel_with_change_is_seen_by_the_next_routine_sync() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 30_000, '1');
        fake.fund(&addr(Chain::External, 1), 4_000, '2');
        let earlier = fake.send(SOMEONE, 20_000, 2).unwrap();
        let (cancel, fee) = fake.replace(&earlier, 5, Replacement::Cancel).unwrap();
        assert_eq!(
            cancel.output[0].script_pubkey,
            earlier.output[1].script_pubkey
        );
        let ready = prepared(Signed {
            tx: cancel,
            fee_sats: fee,
            dust_change_sats: 0,
            amount_cut_sats: 0,
        });
        fake.broadcast(&ready, answer(200, &ready.txid)).unwrap();
        let (snapshot, asked) = synced_after_payment(
            &mut fake,
            &[&addr(Chain::External, 0)],
            (&addr(Chain::Internal, 0), 30_000 - fee, 'c'),
        );
        assert_eq!(snapshot.scan, HotScan::Complete);
        assert_eq!(
            (snapshot.balance_sats, snapshot.spendable_sats),
            (30_000 - fee + 4_000, 30_000 - fee + 4_000)
        );
        assert_eq!(asked, 7 + 5 + 15 + 2 + 1, "a routine sync, not a full pass");
    }

    // A cancel of a payment with no change pays everything to the next unused change address.
    #[test]
    fn a_cancel_without_change_is_seen_on_the_next_unused_change_address() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 10_000, '1');
        fake.fund(&addr(Chain::External, 1), 4_000, '2');
        let (earlier, _) = fake.send_all_at(SOMEONE, 1).unwrap();
        let (cancel, fee) = fake.replace(&earlier, 3, Replacement::Cancel).unwrap();
        let ready = prepared(Signed {
            tx: cancel,
            fee_sats: fee,
            dust_change_sats: 0,
            amount_cut_sats: 0,
        });
        fake.broadcast(&ready, answer(200, &ready.txid)).unwrap();
        let (snapshot, asked) = synced_after_payment(
            &mut fake,
            &[&addr(Chain::External, 0), &addr(Chain::External, 1)],
            (&addr(Chain::Internal, 0), 14_000 - fee, 'c'),
        );
        assert_eq!(snapshot.scan, HotScan::Complete);
        assert_eq!(
            (snapshot.balance_sats, snapshot.spendable_sats),
            (14_000 - fee, 14_000 - fee)
        );
        assert_eq!(
            asked,
            7 + 5 + 15 + 1 + 1,
            "a routine sync with one coin list"
        );
    }

    // A fee bump keeps the earlier payment's change script, so the next routine sync finds its change there.
    #[test]
    fn a_fee_bump_keeps_the_change_where_the_next_routine_sync_finds_it() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 30_000, '1');
        fake.fund(&addr(Chain::External, 1), 4_000, '2');
        let earlier = fake.send(SOMEONE, 20_000, 2).unwrap();
        let (bump, fee) = fake.replace(&earlier, 5, Replacement::FeeBump).unwrap();
        assert_eq!(
            bump.output[1].script_pubkey,
            earlier.output[1].script_pubkey
        );
        let change = bump.output[1].value.to_sat();
        let ready = prepared(Signed {
            tx: bump,
            fee_sats: fee,
            dust_change_sats: 0,
            amount_cut_sats: 0,
        });
        fake.broadcast(&ready, answer(200, &ready.txid)).unwrap();
        let (snapshot, asked) = synced_after_payment(
            &mut fake,
            &[&addr(Chain::External, 0)],
            (&addr(Chain::Internal, 0), change, 'c'),
        );
        assert_eq!(snapshot.scan, HotScan::Complete);
        assert_eq!(snapshot.balance_sats, change + 4_000);
        assert_eq!(asked, 7 + 5 + 15 + 2 + 1, "a routine sync, not a full pass");
    }

    // A routine sync asks only up to LOOKAHEAD past the last used index, so a coin paid further out waits
    // for the full pass, every PUBLIC_FULL_PASS on a public host.
    #[test]
    fn a_payment_to_an_unused_address_far_past_the_last_used_waits_for_the_full_pass() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 10_000, '1');
        fake.snapshot("").unwrap();
        fake.tick(SHARE_TTL);
        fake.fund_unconfirmed(&addr(Chain::External, 12), 7_000, '7');
        let routine = fake.snapshot("").unwrap();
        assert_eq!(routine.balance_sats, 10_000, "the routine sync misses it");
        fake.tick(PUBLIC_FULL_PASS);
        let full = fake.snapshot("").unwrap();
        assert_eq!(full.balance_sats, 17_000, "the full pass finds it");
    }

    #[test]
    fn a_sync_that_fails_keeps_what_it_knew_so_the_next_call_is_a_routine_sync() {
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
            before + 6 + 5 + 1,
            "receive 0 and the five after it, the first five of change, and the one coin list"
        );
    }

    #[test]
    fn a_restart_reads_the_scan_file_proves_each_chains_index_again_and_makes_a_routine_sync() {
        let fake = three_used();
        let folder = Folder::new("restart");
        let store = ScanStore::default();
        store.use_dir(folder.0.clone());
        fake.snapshot_in(&store).unwrap();
        assert_eq!(fake.requests(), 54 + 4);
        let restarted = ScanStore::default();
        restarted.use_dir(folder.0.clone());
        let snapshot = fake.snapshot_in(&restarted).unwrap();
        assert_eq!(
            (
                snapshot.receive_address,
                snapshot.balance_sats,
                snapshot.spendable_sats,
                snapshot.scan
            ),
            (addr(Chain::External, 3), 4_000, 4_000, HotScan::Complete)
        );
        assert_eq!(
            fake.requests(),
            58 + 2 * PROOF_READS + 14 + 4,
            "each chain's proven index proven again, a routine sync and the coin lists"
        );
        // The file holds flags and each chain's proven index. No address, amount, key or txid.
        let [file] = folder.files().try_into().unwrap();
        let text = fs::read_to_string(folder.0.join(file)).unwrap();
        assert!(!text.contains("tb1"), "{text}");
        let json: Value = serde_json::from_str(&text).unwrap();
        let mut keys: Vec<&String> = json.as_object().unwrap().keys().collect();
        keys.sort();
        assert_eq!(keys, ["chains", "full", "version", "wallet"]);
        for chain in json["chains"].as_array().unwrap() {
            let mut keys: Vec<&String> = chain.as_object().unwrap().keys().collect();
            keys.sort();
            assert_eq!(keys, ["proof", "seen"]);
            assert!(chain["proof"].is_u64(), "{chain}");
            for seen in chain["seen"].as_array().unwrap() {
                assert!(seen.as_u64().is_some_and(|flags| flags < 16), "{seen}");
            }
        }
    }

    #[test]
    fn a_lost_host_makes_the_next_call_a_routine_sync_that_proves_nothing_again() {
        let fake = three_used();
        fake.snapshot("").unwrap();
        assert_eq!(fake.requests(), 54 + 4);
        let hosts = Mutex::new(Some(HostVerdict::Chosen(0, Instant::now())));
        let lost = get_forgetting(&|| classify(no_answer()), &|_| {}, &hosts, &|| {
            fake.store.mark_stale()
        });
        assert!(lost.is_err());
        assert_eq!(chosen(&hosts), None);
        assert_eq!(
            receive_and_balance(fake.snapshot("").unwrap()),
            (addr(Chain::External, 3), 4_000)
        );
        assert_eq!(
            fake.requests(),
            58 + 14 + 4,
            "inside the share window, a routine sync on the next host"
        );
    }

    #[test]
    fn an_unreadable_foreign_or_newer_scan_file_is_ignored_and_discovery_starts_over() {
        let fake = three_used();
        let id = Account::new(
            &abandon_about(),
            "",
            Network::Testnet4,
            AddressType::NativeSegwit,
        )
        .unwrap()
        .id();
        let name = format!(
            "{}.json",
            id.iter().map(|b| format!("{b:02x}")).collect::<String>()
        );
        let written = |passphrase: &str| {
            let folder = Folder::new("written");
            let store = ScanStore::default();
            store.use_dir(folder.0.clone());
            snapshot_from(
                &EsploraSource(&Reads(&|url| fake.get(url))),
                &abandon_about(),
                passphrase,
                KeyNetwork::Test,
                AddressType::NativeSegwit,
                &|| fake.now(),
                &store,
            )
            .unwrap();
            let [file] = folder.files().try_into().unwrap();
            fs::read_to_string(folder.0.join(file)).unwrap()
        };
        let good = written("");
        let newer = good.replace("\"version\":2", "\"version\":3");
        assert_ne!(newer, good);
        for (case, text) in [
            ("unreadable", good[..good.len() - 3].to_string()),
            ("newer", newer),
            ("another wallet's", written("TREZOR")),
        ] {
            let folder = Folder::new("ignored");
            fs::create_dir_all(&folder.0).unwrap();
            fs::write(folder.0.join(&name), &text).unwrap();
            let store = ScanStore::default();
            store.use_dir(folder.0.clone());
            let before = fake.requests();
            assert_eq!(
                receive_and_balance(fake.snapshot_in(&store).unwrap()),
                (addr(Chain::External, 3), 4_000),
                "{case}"
            );
            assert_eq!(
                fake.requests() - before,
                54 + 4,
                "{case}: discovery from index 0 with its proofs"
            );
            let restarted = ScanStore::default();
            restarted.use_dir(folder.0.clone());
            let before = fake.requests();
            fake.snapshot_in(&restarted).unwrap();
            assert_eq!(
                fake.requests() - before,
                2 * PROOF_READS + 14 + 4,
                "{case}: the file was written again, so the next restart proves its indices again and is a routine sync"
            );
        }
    }

    // A file is outside data, so a time in it that no clock reaches must not stop the wallet from loading.
    #[test]
    fn a_scan_file_whose_last_full_pass_no_clock_can_hold_loads_with_a_full_pass() {
        let fake = three_used();
        let folder = Folder::new("far");
        let store = ScanStore::default();
        store.use_dir(folder.0.clone());
        fake.snapshot_in(&store).unwrap();
        let [file] = folder.files().try_into().unwrap();
        let path = folder.0.join(file);
        let text = fs::read_to_string(&path).unwrap();
        let json: Value = serde_json::from_str(&text).unwrap();
        let at = json["full"]["started"].as_u64().unwrap();
        fs::write(
            &path,
            text.replace(
                &format!("\"started\":{at}"),
                &format!("\"started\":{}", u64::MAX),
            ),
        )
        .unwrap();
        let restarted = ScanStore::default();
        restarted.use_dir(folder.0.clone());
        let before = fake.requests();
        assert_eq!(
            receive_and_balance(fake.snapshot_in(&restarted).unwrap()),
            (addr(Chain::External, 3), 4_000)
        );
        assert_eq!(
            fake.requests() - before,
            2 * PROOF_READS + 44 + 4,
            "the proven indices proven again, then a full pass, which proves nothing else"
        );
    }

    #[test]
    fn two_syncs_of_one_wallet_at_once_make_one_set_of_requests() {
        use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering::SeqCst};
        let fake = three_used();
        let (first, others) = (AtomicBool::new(true), AtomicUsize::new(0));
        // The first request waits for the other sync to ask anything, which it can while the first one runs only
        // when the two do not take turns.
        let gated = |url: &str| {
            if first.swap(false, SeqCst) {
                let waited = Instant::now();
                while others.load(SeqCst) == 0 && waited.elapsed() < Duration::from_millis(300) {
                    std::thread::sleep(Duration::from_millis(5));
                }
            } else {
                others.fetch_add(1, SeqCst);
            }
            fake.get(url)
        };
        let snapshots: Vec<HotWalletSnapshot> = std::thread::scope(|scope| {
            let sync = || {
                snapshot_from(
                    &EsploraSource(&Reads(&gated)),
                    &abandon_about(),
                    "",
                    KeyNetwork::Test,
                    AddressType::NativeSegwit,
                    &|| fake.now(),
                    &fake.store,
                )
            };
            let threads = [scope.spawn(sync), scope.spawn(sync)];
            threads.map(|thread| thread.join().unwrap().unwrap()).into()
        });
        assert_eq!(snapshots[0], snapshots[1]);
        assert_eq!(
            fake.requests(),
            54 + 2 * 4,
            "one sync, then each snapshot's own coin lists"
        );
    }

    #[test]
    fn freshness_counts_from_when_a_sync_ends_so_a_history_after_a_slow_snapshot_shares_it() {
        let fake = three_used();
        let started = fake.now();
        let slow = |url: &str| {
            fake.tick(Duration::from_secs(1));
            fake.get(url)
        };
        snapshot_from(
            &EsploraSource(&Reads(&slow)),
            &abandon_about(),
            "",
            KeyNetwork::Test,
            AddressType::NativeSegwit,
            &|| fake.now(),
            &fake.store,
        )
        .unwrap();
        assert!(fake.now().duration_since(started).unwrap() > 5 * SHARE_TTL);
        let before = fake.requests();
        fake.history().unwrap();
        assert_eq!(
            fake.requests() - before,
            4 + 1,
            "the four used addresses' pages and the tip, and no sync"
        );
    }

    /// A transport that reads through `.1` from a host run by `.0`.
    struct RunBy<'a>(
        Operator,
        &'a (dyn Fn(&str) -> Result<String, MobileSignerError> + Sync),
    );

    impl Transport for RunBy<'_> {
        fn get(&self, url: &str) -> Result<String, MobileSignerError> {
            (self.1)(url)
        }

        fn broadcast(&self, _: &str, _: &str) -> Result<String, MobileSignerError> {
            unreachable!("a sync posts nothing")
        }

        fn operator(&self) -> Result<Operator, MobileSignerError> {
            Ok(self.0)
        }
    }

    #[test]
    fn a_full_pass_runs_every_thirty_minutes_on_the_own_node_and_every_six_hours_on_a_public_host_and_proves_nothing_again()
     {
        for (operator, every) in [
            (Operator::Mebit, OWN_NODE_FULL_PASS),
            (Operator::Blockstream, PUBLIC_FULL_PASS),
            (Operator::MempoolSpace, PUBLIC_FULL_PASS),
        ] {
            // The file keeps when a full pass ended in whole seconds, so the steps stay a second clear of it.
            let fake = three_used();
            let get = |url: &str| fake.get(url);
            let snapshot = || {
                snapshot_from(
                    &EsploraSource(&RunBy(operator, &get)),
                    &abandon_about(),
                    "",
                    KeyNetwork::Test,
                    AddressType::NativeSegwit,
                    &|| fake.now(),
                    &fake.store,
                )
                .unwrap()
            };
            snapshot();
            assert_eq!(fake.requests(), 58, "{operator:?}: discovery");
            fake.tick(every - Duration::from_secs(1));
            snapshot();
            assert_eq!(
                fake.requests(),
                58 + 18,
                "{operator:?}: routine before the period"
            );
            // Past the share window of the routine sync, and so past the period too.
            fake.tick(SHARE_TTL);
            assert_eq!(
                receive_and_balance(snapshot()),
                (addr(Chain::External, 3), 4_000),
                "{operator:?}"
            );
            assert_eq!(
                fake.requests(),
                76 + 44 + 4,
                "{operator:?}: every index below each frontier plus the gap, and no proof"
            );
            fake.tick(every - Duration::from_secs(1));
            snapshot();
            assert_eq!(
                fake.requests(),
                124 + 18,
                "{operator:?}: the period counts from when the full pass started"
            );
        }
    }

    // A public host answers 429 once its hourly quota is spent. Home keeps the dash and goes on later, and the
    // next sync asks only the rest, proving nothing twice.
    #[test]
    fn a_host_that_throttles_ends_the_snapshot_continuing_and_the_next_call_goes_on_from_what_it_kept()
     {
        use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering::SeqCst};
        let mut fake = Fake::new();
        for i in 0..=60 {
            fake.touch(&addr(Chain::External, i));
        }
        fake.fund(&addr(Chain::External, 60), 60_000, 'a');
        // Uninterrupted: 81 receive indices and 20 change, and the proofs of receive 19, 39, 59 and 60.
        let (summaries_whole, proofs_whole) = (81 + 20, 4);
        let (limited, summaries) = (AtomicBool::new(true), AtomicUsize::new(0));
        let throttling = |url: &str| {
            let path = url.strip_prefix(ESPLORA_TESTNET4).unwrap();
            let summary = path.starts_with("/address/") && path.matches('/').count() == 2;
            if summary && summaries.fetch_add(1, SeqCst) >= 45 && limited.load(SeqCst) {
                return Err(MobileSignerError::InvalidPsbtJson(
                    "esplora get: HTTP status 429, gave up after 3 attempts".into(),
                ));
            }
            fake.get(url)
        };
        let snapshot = || {
            snapshot_from(
                &EsploraSource(&Reads(&throttling)),
                &abandon_about(),
                "",
                KeyNetwork::Test,
                AddressType::NativeSegwit,
                &|| fake.now(),
                &fake.store,
            )
            .unwrap()
        };
        let cut = snapshot();
        assert_eq!((cut.scan, cut.spendable_sats), (HotScan::Continuing, 0));
        limited.store(false, SeqCst);
        fake.tick(Duration::from_secs(60));
        let done = snapshot();
        assert_eq!(
            (done.receive_address, done.balance_sats, done.scan),
            (addr(Chain::External, 61), 60_000, HotScan::Complete)
        );
        let proofs = fake
            .calls
            .lock()
            .unwrap()
            .iter()
            .filter(|url| url.ends_with("/txs"))
            .count();
        assert_eq!(proofs, proofs_whole, "no window proven twice");
        let summaries = summaries.load(SeqCst);
        assert!(
            summaries <= summaries_whole + WINDOW,
            "{summaries}: only the window that was throttled is asked twice"
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

    /// An input that spends output `vout` of `txid`, which paid `address` `value` sats, as Esplora lists one.
    fn spent_at(txid: &str, vout: u32, address: &str, value: u64) -> String {
        format!(
            r#"{{"txid":"{txid}","vout":{vout},"is_coinbase":false,"prevout":{}}}"#,
            txo(address, value)
        )
    }

    fn tx_json(digit: char, vin: &[String], vout: &[String], status: &str) -> String {
        tx_of(&id(digit), vin, vout, status)
    }

    fn tx_of(txid: &str, vin: &[String], vout: &[String], status: &str) -> String {
        format!(
            r#"{{"txid":"{txid}","vin":[{}],"vout":[{}],"status":{status}}}"#,
            vin.join(","),
            vout.join(",")
        )
    }

    fn at(height: u32) -> String {
        format!(r#"{{"confirmed":true,"block_height":{height},"block_time":1790000000}}"#)
    }

    // 10000 at 0/0 is spent: 6000 to someone, 3000 back as change to 1/0, 1000 fee. Both pages
    // list that transaction. The wallet is 7000 lighter, once. A mempool receive on 1/0 comes
    // first and the older funding of 0/0 last. The funding and the payment are the proof block's,
    // which proves 0/0 and 1/0 used.
    #[test]
    fn a_send_with_change_is_one_outgoing_transaction_in_the_history() {
        let (ext0, int0) = (addr(Chain::External, 0), addr(Chain::Internal, 0));
        let (funded, paid) = (
            PROOF.txids[0].to_string(),
            PROOF.txids.last().unwrap().to_string(),
        );
        let funding = tx_of(
            &funded,
            &[spent(SOMEONE, 20_000)],
            &[txo(&ext0, 10_000)],
            &in_proof_block(&PROOF.header, 90),
        );
        let payment = tx_of(
            &paid,
            &[spent(&ext0, 10_000)],
            &[txo(SOMEONE, 6_000), txo(&int0, 3_000)],
            &in_proof_block(&PROOF.header, 100),
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
                hot(&paid, -7_000, 6, Some(1_790_000_000)),
                hot(&funded, 10_000, 16, Some(1_790_000_000)),
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
        GetError::Throttled {
            retry_after: None,
            what: "esplora get: HTTP status 429".into(),
        }
    }

    fn outage() -> GetError {
        GetError::Retryable {
            retry_after: None,
            what: "esplora get: HTTP status 503".into(),
        }
    }

    fn throttled_after(secs: u64) -> GetError {
        match throttled() {
            GetError::Throttled { what, .. } => GetError::Throttled {
                retry_after: Some(Duration::from_secs(secs)),
                what,
            },
            _ => unreachable!(),
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

    // A host's request limit ends a sync `Continuing`, to go on later, where any other failed read fails it.
    // One formatter writes every 429 text, so each producer's text is pinned here against the detector
    // and against the exact string the app's Kotlin rows copy.
    #[test]
    fn only_a_429_or_a_host_under_a_limit_reads_as_the_host_limiting_requests() {
        let limited = |e: &MobileSignerError| crate::chain::throttled(e).is_some();
        let text = |e: MobileSignerError| match e {
            MobileSignerError::InvalidPsbtJson(t) => t,
            other => panic!("{other:?}"),
        };
        let t0 = Instant::now();
        let half = Duration::from_millis(500);
        // classify's raw 429, which only a test that skips the wrapper sees, asked once.
        let (raw, attempts, _) = retried(vec![classify(answer(429, ""))]);
        let raw = raw.unwrap_err();
        assert!(limited(&raw) && attempts == 1);
        assert_eq!(text(raw), "esplora get: HTTP status 429");
        let outages = (0..3).map(|_| classify(answer(503, ""))).collect();
        assert!(!limited(&retried(outages).0.unwrap_err()));
        // A GET answered 429, and one not made because its host is under that limit. The seconds round up.
        let limits = Limits::default();
        let answered = attempt_unless_limited(HOSTS[0], &|| Err(throttled()), &limits, &|| t0);
        let answered = text(MobileSignerError::from(answered.unwrap_err()));
        assert!(limited(&MobileSignerError::InvalidPsbtJson(
            answered.clone()
        )));
        assert_eq!(
            answered,
            "esplora get: https://first.example/api: HTTP status 429, limited for 60 s more"
        );
        let unasked = |after: Duration| {
            let refused =
                attempt_unless_limited(HOSTS[0], &|| unreachable!(), &limits, &|| t0 + after);
            text(MobileSignerError::from(refused.unwrap_err()))
        };
        assert_eq!(
            unasked(half),
            "esplora get: https://first.example/api: HTTP status 429, limited for 60 s more"
        );
        assert_eq!(
            unasked(Duration::from_secs(59) + half),
            "esplora get: https://first.example/api: HTTP status 429, limited for 1 s more"
        );
        // A probe round in which every host answered 429, one with a 404, and one with a host under a
        // limit beside a host that is down.
        let probed = |status: u16| {
            let probe = |_: &HostSpec| classify(answer(status, ""));
            pick_host(
                &SPECS,
                &probe,
                t0,
                &Mutex::new(None),
                &[],
                &Limits::default(),
            )
            .unwrap_err()
        };
        let round = probed(429);
        assert!(limited(&round));
        assert_eq!(
            text(round),
            "esplora get: no testnet4 host is reachable (https://first.example/api: HTTP status 429, limited for 60 s more; https://second.example/api: HTTP status 429, limited for 60 s more)"
        );
        assert!(!limited(&probed(404)));
        let probe = Probe::new(&[(HOSTS[1], Err("down"))]);
        let _ = attempt_unless_limited(HOSTS[0], &|| Err(throttled()), &probe.limits, &|| t0);
        let mixed = probe.pick(t0 + half, &Mutex::new(None)).unwrap_err();
        assert!(limited(&mixed));
        assert_eq!(
            text(mixed),
            "esplora get: no testnet4 host is reachable (https://first.example/api: HTTP status 429, limited for 60 s more; https://second.example/api: down)"
        );
        assert!(!limited(
            &retried(vec![lost(), lost(), lost()]).0.unwrap_err()
        ));
        assert!(!limited(&MobileSignerError::InvalidPsbtJson(
            "esplora get: https://blockstream.info/testnet4/api/address/tb1q4290: Connection Failed"
                .into()
        )));
    }

    #[test]
    fn a_brief_outage_then_a_200_returns_that_body() {
        let (result, attempts, sleeps) = retried(vec![Err(outage()), Ok("body".into())]);
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
    fn three_outages_fail_naming_the_status_and_the_attempts() {
        let (result, attempts, sleeps) = retried(vec![Err(outage()), Err(outage()), Err(outage())]);
        let MobileSignerError::InvalidPsbtJson(message) = result.unwrap_err() else {
            panic!("wrong error kind");
        };
        assert!(
            message.contains("503") && message.contains("3 attempts"),
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
    fn classify_limits_on_a_429_and_retries_only_502_503_504() {
        let got = classified("HTTP/1.1 429 -\r\nContent-Length: 0\r\n\r\n");
        assert!(matches!(got, Err(GetError::Throttled { .. })));
        for status in [502, 503, 504] {
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
            Err(GetError::Throttled { retry_after, .. }) => retry_after,
            _ => panic!("not throttled"),
        };
        assert_eq!(wait("2"), Some(Duration::from_secs(2)));
        assert_eq!(wait("Wed, 21 Oct 2026 07:28:00 GMT"), None);
        let (_, _, sleeps) = retried(vec![Err(after(None)), Err(after(None)), Ok("b".into())]);
        assert_eq!(sleeps, secs(&[1, 2]));
    }

    #[test]
    fn a_retried_200_still_goes_through_the_strict_parser() {
        let (result, _, _) = retried(vec![Err(outage()), Ok(r#"{"chain_stats":{}}"#.into())]);
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
    // and two coin lists, about the 299 the Mac measured, plus a proof per window (receive 19 to
    // 139 by 20 and 149, change 19 to 99 by 20), and one of them loses its connection.
    #[test]
    fn one_lost_connection_among_a_large_wallets_requests_still_loads_it_on_the_same_host() {
        use std::sync::atomic::{AtomicUsize, Ordering::SeqCst};
        let losses = [
            ("a reset", lost as fn() -> Result<String, GetError>),
            ("a refused connection", || classify(Err(refused()))),
            ("a timeout", stalled),
            ("a body cut short", || classify(cut_short(200))),
        ];
        let account = Account::new(
            &abandon_about(),
            "",
            Network::Testnet4,
            AddressType::NativeSegwit,
        )
        .unwrap();
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
            let hosts = Mutex::new(Some(HostVerdict::Chosen(0, Instant::now())));
            let attempts = AtomicUsize::new(0);
            let esplora = |url: &str| {
                get_forgetting(
                    &|| match attempts.fetch_add(1, SeqCst) {
                        200 => lose(),
                        _ => classify(answer(200, &fake.get(url).unwrap())),
                    },
                    &|_| {},
                    &hosts,
                    &|| fake.store.mark_stale(),
                )
            };
            let snapshot = match snapshot_from(
                &EsploraSource(&Reads(&esplora)),
                &abandon_about(),
                "",
                KeyNetwork::Test,
                AddressType::NativeSegwit,
                &|| fake.now(),
                &fake.store,
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
            assert_eq!(fake.requests(), 292 + 13 * PROOF_READS, "{loss}");
            assert_eq!(
                attempts.load(SeqCst),
                293 + 13 * PROOF_READS,
                "{loss}: the lost one is asked once more"
            );
            assert_eq!(
                chosen(&hosts),
                Some(0),
                "{loss}: the host that answered stays"
            );
            let before = fake.requests();
            fake.snapshot("").unwrap();
            assert_eq!(
                fake.requests() - before,
                2,
                "{loss}: the next call shares the sync and asks only the two coin lists"
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
    fn the_host_is_forgotten_and_the_scans_marked_stale_only_when_the_last_attempt_got_no_whole_answer()
     {
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
            ("a 429", vec![Err(throttled())], false),
            ("a loss, then a 429", vec![lost(), Err(throttled())], false),
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
            let _ = get_forgetting(&next, &|_| {}, &hosts, &|| fake.store.mark_stale());
            assert!(
                script.lock().unwrap().is_empty(),
                "{case}: every attempt was asked"
            );
            assert_eq!(chosen(&hosts).is_none(), forgotten, "{case}");
            assert_eq!(fake.next_snapshot_syncs(), forgotten, "{case}");
        }
    }

    /// A transport over one host with no host choice, so every lookup of a window goes to that host by
    /// construction. `answer` is the network, and `sleep` is the wait between a lookup's attempts.
    struct OneHost<'a> {
        answer: &'a (dyn Fn(&str) -> Result<String, GetError> + Sync),
        sleep: &'a (dyn Fn(Duration) + Sync),
        limits: &'a Limits,
    }

    impl Transport for OneHost<'_> {
        fn get(&self, url: &str) -> Result<String, MobileSignerError> {
            get_forgetting(
                &|| {
                    attempt_unless_limited(
                        HOSTS[0],
                        &|| (self.answer)(url),
                        self.limits,
                        &Instant::now,
                    )
                },
                self.sleep,
                &Mutex::new(None),
                &|| {},
            )
        }

        fn broadcast(&self, _: &str, _: &str) -> Result<String, MobileSignerError> {
            unreachable!("a test that only reads posts nothing")
        }
    }

    // Ten lookups in flight: the fourth is refused with 429 and the rest get a brief 503. Without the
    // check before each attempt, each 503 would be asked twice more, 28 requests, and the first error
    // would read as the 503.
    #[test]
    fn a_429_in_a_batch_ends_the_batch_and_the_retries_of_its_siblings() {
        use std::sync::Barrier;
        use std::sync::atomic::{AtomicUsize, Ordering::SeqCst};
        let addresses: Vec<Address> = (0..2 * WINDOW as u32)
            .map(|i| own(Chain::External, i).address)
            .collect();
        let refused = addresses[3].to_string();
        let calls = AtomicUsize::new(0);
        let in_flight = Barrier::new(WINDOW);
        let limits = Limits::default();
        let answer = |url: &str| {
            if calls.fetch_add(1, SeqCst) < WINDOW {
                in_flight.wait();
            }
            if url.ends_with(&refused) {
                Err(throttled())
            } else {
                Err(outage())
            }
        };
        let limited = || {
            limits
                .lock()
                .unwrap()
                .iter()
                .any(|(host, _)| *host == HOSTS[0])
        };
        let sleep = |_| {
            let start = Instant::now();
            while !limited() && start.elapsed() < Duration::from_secs(2) {
                std::thread::sleep(Duration::from_millis(5));
            }
        };
        let host = OneHost {
            answer: &answer,
            sleep: &sleep,
            limits: &limits,
        };
        let refs: Vec<&Address> = addresses.iter().collect();
        let error = EsploraSource(&host).summaries(&refs).unwrap_err();
        assert_eq!(
            calls.load(SeqCst),
            WINDOW,
            "one request per lookup of the window, none after"
        );
        assert!(crate::chain::throttled(&error).is_some(), "{error:?}");
    }

    /// Whether `call` runs at `t0 + after` on a host that answered 429 at `t0` with `retry_after`.
    fn asked_again(retry_after: Option<u64>, after: Duration) -> bool {
        let limits = Limits::default();
        let t0 = Instant::now();
        let first = attempt_unless_limited(
            HOSTS[0],
            &|| {
                Err(GetError::Throttled {
                    retry_after: retry_after.map(Duration::from_secs),
                    what: "esplora get: HTTP status 429".into(),
                })
            },
            &limits,
            &|| t0,
        );
        assert!(matches!(first, Err(GetError::Throttled { .. })));
        let asked = Cell::new(false);
        let _ = attempt_unless_limited(
            HOSTS[0],
            &|| {
                asked.set(true);
                Ok(String::new())
            },
            &limits,
            &|| t0 + after,
        );
        asked.get()
    }

    #[test]
    fn a_limit_lasts_the_retry_after_capped_at_ten_minutes_and_a_minute_without_one_or_with_zero() {
        let second = Duration::from_secs(1);
        assert!(!asked_again(Some(30), Duration::from_secs(29)));
        assert!(asked_again(Some(30), Duration::from_secs(30)));
        assert!(!asked_again(Some(86_400), LIMIT_MAX - second));
        assert!(asked_again(Some(86_400), LIMIT_MAX));
        assert!(!asked_again(None, LIMIT_DEFAULT - second));
        assert!(asked_again(None, LIMIT_DEFAULT));
        assert!(
            !asked_again(Some(0), LIMIT_DEFAULT - second),
            "Retry-After 0 is not an invitation"
        );
        assert!(asked_again(Some(0), LIMIT_DEFAULT));
        // Two lookups in flight together, past the check before either answers. The first answers 429
        // with no Retry-After at t0. The second waits for that to be recorded, then answers 429 with
        // Retry-After 5 at t0 + 1 s. The later answer never shortens the limit.
        let limits = Limits::default();
        let t0 = Instant::now();
        let recorded = || {
            limits
                .lock()
                .unwrap()
                .iter()
                .any(|(host, _)| *host == HOSTS[0])
        };
        let both_in_flight = std::sync::Barrier::new(2);
        std::thread::scope(|scope| {
            scope.spawn(|| {
                let first = || {
                    both_in_flight.wait();
                    Err(throttled())
                };
                let _ = attempt_unless_limited(HOSTS[0], &first, &limits, &|| t0);
            });
            scope.spawn(|| {
                let second_answer = || {
                    both_in_flight.wait();
                    while !recorded() {
                        std::thread::sleep(Duration::from_millis(1));
                    }
                    Err(throttled_after(5))
                };
                let _ = attempt_unless_limited(HOSTS[0], &second_answer, &limits, &|| t0 + second);
            });
        });
        let asked = Cell::new(false);
        let late = attempt_unless_limited(
            HOSTS[0],
            &|| {
                asked.set(true);
                Ok(String::new())
            },
            &limits,
            &|| t0 + Duration::from_secs(59),
        );
        assert!(
            !asked.get() && late.is_err(),
            "the 60 s limit stands over the 5 s one"
        );
    }

    #[test]
    fn a_get_whose_host_is_limited_is_refused_unasked_and_moves_no_limit() {
        let limits = Limits::default();
        let t0 = Instant::now();
        let _ = attempt_unless_limited(HOSTS[0], &|| Err(throttled_after(5)), &limits, &|| t0);
        let asked = Cell::new(0);
        let count = || {
            asked.set(asked.get() + 1);
            Ok(String::new())
        };
        let refused =
            attempt_unless_limited(HOSTS[0], &count, &limits, &|| t0 + Duration::from_secs(1));
        let MobileSignerError::InvalidPsbtJson(text) =
            MobileSignerError::from(refused.unwrap_err())
        else {
            panic!("wrong error kind")
        };
        assert!(text.contains("limited for 4 s more"), "{text}");
        assert_eq!(asked.get(), 0, "refused without a request");
        let free =
            attempt_unless_limited(HOSTS[0], &count, &limits, &|| t0 + Duration::from_secs(5));
        assert!(free.is_ok());
        assert_eq!(asked.get(), 1, "a refusal moved no limit");
    }

    // Forget once, on the final result, with the limit wrapper inside. Moving the forget into the
    // attempt closure would count three, and a 429 after a loss would forget nothing either way.
    #[test]
    fn a_loss_is_forgotten_once_whatever_the_attempt_wrapper_sees() {
        let through = |script: Vec<Result<String, GetError>>| {
            let hosts = Mutex::new(Some(HostVerdict::Chosen(0, Instant::now())));
            let limits = Limits::default();
            let script = Mutex::new(VecDeque::from(script));
            let attempts = Cell::new(0);
            let lost_calls = Cell::new(0);
            let result = get_forgetting(
                &|| {
                    attempt_unless_limited(
                        HOSTS[0],
                        &|| {
                            attempts.set(attempts.get() + 1);
                            script
                                .lock()
                                .unwrap()
                                .pop_front()
                                .expect("one attempt too many")
                        },
                        &limits,
                        &Instant::now,
                    )
                },
                &|_| {},
                &hosts,
                &|| lost_calls.set(lost_calls.get() + 1),
            );
            assert!(result.is_err());
            (attempts.get(), lost_calls.get(), chosen(&hosts))
        };
        assert_eq!(through(vec![lost(), lost(), lost()]), (3, 1, None));
        assert_eq!(through(vec![lost(), Err(throttled())]), (2, 0, Some(0)));
    }

    const HOSTS: [&str; 2] = ["https://first.example/api", "https://second.example/api"];
    const SPECS: [HostSpec; 2] = [
        HostSpec {
            base: HOSTS[0],
            operator: Operator::Blockstream,
        },
        HostSpec {
            base: HOSTS[1],
            operator: Operator::MempoolSpace,
        },
    ];
    const TESTNET3_GENESIS: &str =
        "000000000933ea01ad0ee984209779baaec3ced90fa3f408719526f8d77f4943";
    const MAINNET_GENESIS: &str =
        "000000000019d6689c085ae165831e9934ff763ae46a2a6c172b3f1b60a8ce26";

    /// A probe scripted per host, counting the hosts it was asked about, with its own limit table.
    struct Probe {
        answers: HashMap<&'static str, Result<&'static str, &'static str>>,
        asked: Mutex<Vec<String>>,
        limits: Limits,
    }

    impl Probe {
        fn new(answers: &[(&'static str, Result<&'static str, &'static str>)]) -> Self {
            Self {
                answers: answers.iter().copied().collect(),
                asked: Mutex::new(Vec::new()),
                limits: Limits::default(),
            }
        }

        fn run(&self, host: &str) -> Result<String, GetError> {
            self.asked.lock().unwrap().push(host.to_string());
            match self.answers.get(host).copied() {
                Some(Ok(body)) => Ok(body.to_string()),
                Some(Err(why)) => Err(GetError::Unreachable(why.to_string())),
                None => panic!("unexpected probe of {host}"),
            }
        }

        fn asked(&self) -> Vec<String> {
            self.asked.lock().unwrap().clone()
        }

        fn pick(&self, now: Instant, cache: &HostChoice) -> Result<usize, MobileSignerError> {
            pick_host(
                &SPECS,
                &|host| self.run(host.base),
                now,
                cache,
                &[],
                &self.limits,
            )
        }
    }

    /// `Http::get` with the network swapped out: the real host choice, limit and retry over `SPECS`,
    /// with its own clock, choice and limits (the probe's). `answer(base, path)` is the network. Every
    /// request that reached a host is logged, and the `lost` calls are counted.
    struct World<'a> {
        probe: Probe,
        choice: HostChoice,
        answer: &'a (dyn Fn(&str, &str) -> Result<String, GetError> + Sync),
        at: Mutex<Instant>,
        requests: Mutex<Vec<(&'static str, String)>>,
        lost: Mutex<usize>,
    }

    impl<'a> World<'a> {
        fn new(answer: &'a (dyn Fn(&str, &str) -> Result<String, GetError> + Sync)) -> Self {
            Self {
                probe: Probe::new(&[
                    (HOSTS[0], Ok(TESTNET4_GENESIS)),
                    (HOSTS[1], Ok(TESTNET4_GENESIS)),
                ]),
                choice: Mutex::new(None),
                answer,
                at: Mutex::new(Instant::now()),
                requests: Mutex::new(Vec::new()),
                lost: Mutex::new(0),
            }
        }

        fn get_at(&self, at: Instant, path: &str) -> Result<String, MobileSignerError> {
            *self.at.lock().unwrap() = at;
            self.get(&format!("{ESPLORA_TESTNET4}{path}"))
        }

        fn hosts_asked(&self) -> Vec<&'static str> {
            self.requests
                .lock()
                .unwrap()
                .iter()
                .map(|(base, _)| *base)
                .collect()
        }
    }

    impl Transport for World<'_> {
        fn get(&self, url: &str) -> Result<String, MobileSignerError> {
            let at = *self.at.lock().unwrap();
            let index = pick_host(
                &SPECS,
                &|host| self.probe.run(host.base),
                at,
                &self.choice,
                &[],
                &self.probe.limits,
            )?;
            let base = SPECS[index].base;
            let path = url.strip_prefix(ESPLORA_TESTNET4).expect("a testnet4 URL");
            get_forgetting(
                &|| {
                    attempt_unless_limited(
                        base,
                        &|| {
                            self.requests.lock().unwrap().push((base, path.to_string()));
                            (self.answer)(base, path)
                        },
                        &self.probe.limits,
                        &|| at,
                    )
                },
                &|_| {},
                &self.choice,
                &|| *self.lost.lock().unwrap() += 1,
            )
        }

        fn broadcast(&self, _: &str, _: &str) -> Result<String, MobileSignerError> {
            unreachable!("a test that only reads posts nothing")
        }
    }

    /// The first host answers its first request 429 with a Retry-After of 30 s, and everything after that
    /// with its own base. The second host always answers with its base.
    fn first_refuses_once(
        refused: &std::sync::atomic::AtomicBool,
    ) -> impl Fn(&str, &str) -> Result<String, GetError> + Sync + '_ {
        use std::sync::atomic::Ordering::SeqCst;
        move |base, _| {
            if base == HOSTS[0] && !refused.swap(true, SeqCst) {
                classified("HTTP/1.1 429 -\r\nRetry-After: 30\r\nContent-Length: 0\r\n\r\n")
            } else {
                Ok(base.to_string())
            }
        }
    }

    const TIP_PATH: &str = "/blocks/tip/height";

    #[test]
    fn a_429_is_not_asked_again_and_the_next_call_goes_to_the_second_host() {
        let refused = std::sync::atomic::AtomicBool::new(false);
        let answer = first_refuses_once(&refused);
        let world = World::new(&answer);
        let t0 = Instant::now();
        let error = world.get_at(t0, TIP_PATH).unwrap_err();
        let text = crate::chain::throttled(&error).expect("reads as the limit");
        assert!(text.contains(HOSTS[0]), "{text}");
        assert_eq!(
            world.hosts_asked(),
            [HOSTS[0]],
            "no retry against the host refusing us"
        );
        let second = Duration::from_secs(1);
        assert_eq!(world.get_at(t0 + second, TIP_PATH).unwrap(), HOSTS[1]);
        assert_eq!(world.get_at(t0 + 2 * second, TIP_PATH).unwrap(), HOSTS[1]);
        assert_eq!(world.hosts_asked(), [HOSTS[0], HOSTS[1], HOSTS[1]]);
        assert_eq!(
            world.probe.asked(),
            HOSTS,
            "the limited host is not probed either"
        );
        assert_eq!(*world.lost.lock().unwrap(), 0, "a 429 forgets nothing");
    }

    #[test]
    fn the_first_host_is_used_again_once_its_limit_is_over() {
        let refused = std::sync::atomic::AtomicBool::new(false);
        let answer = first_refuses_once(&refused);
        let world = World::new(&answer);
        let t0 = Instant::now();
        assert!(world.get_at(t0, TIP_PATH).is_err());
        assert_eq!(
            world
                .get_at(t0 + Duration::from_secs(29), TIP_PATH)
                .unwrap(),
            HOSTS[1]
        );
        // The second host is cached for HOST_TRUST, but the own node, or any earlier host, wins back.
        assert_eq!(
            world
                .get_at(t0 + Duration::from_secs(30), TIP_PATH)
                .unwrap(),
            HOSTS[0]
        );
        assert_eq!(
            world.probe.asked(),
            [HOSTS[0], HOSTS[1], HOSTS[0]],
            "exactly one probe brought it back"
        );
        assert_eq!(chosen(&world.choice), Some(0));
    }

    #[test]
    fn every_host_limited_names_the_429_and_probes_nothing() {
        let probe = Probe::new(&[
            (HOSTS[0], Ok(TESTNET4_GENESIS)),
            (HOSTS[1], Ok(TESTNET4_GENESIS)),
        ]);
        let t0 = Instant::now();
        for host in HOSTS {
            let _ = attempt_unless_limited(host, &|| Err(throttled()), &probe.limits, &|| t0);
        }
        let cache = Mutex::new(None);
        let message = no_host_message(probe.pick(t0, &cache));
        assert!(
            message.starts_with("esplora get: no testnet4 host is reachable ("),
            "{message}"
        );
        for host in HOSTS {
            let entry = format!("{host}: HTTP status 429, limited for 60 s more");
            assert!(message.contains(&entry), "{message}");
        }
        assert!(crate::chain::throttled(&MobileSignerError::InvalidPsbtJson(message)).is_some());
        assert!(probe.asked().is_empty());
        no_host_message(probe.pick(t0 + Duration::from_secs(1), &cache));
        no_host_message(probe.pick(t0 + Duration::from_secs(59), &cache));
        assert!(
            probe.asked().is_empty(),
            "no probe while every host is limited"
        );
        assert_eq!(probe.pick(t0 + Duration::from_secs(60), &cache).unwrap(), 0);
        assert_eq!(probe.asked(), [HOSTS[0]]);
    }

    #[test]
    fn a_429_in_a_probe_round_limits_that_host_until_its_retry_after() {
        let asked = Mutex::new(Vec::new());
        let refused = Cell::new(false);
        let probe = |host: &HostSpec| {
            asked.lock().unwrap().push(host.base);
            if host.base == HOSTS[0] && !refused.replace(true) {
                Err(throttled_after(30))
            } else {
                Ok(TESTNET4_GENESIS.to_string())
            }
        };
        let limits = Limits::default();
        let cache = Mutex::new(None);
        let t0 = Instant::now();
        let pick = |after: u64| {
            let at = t0 + Duration::from_secs(after);
            pick_host(&SPECS, &probe, at, &cache, &[], &limits).unwrap()
        };
        assert_eq!(pick(0), 1);
        assert_eq!(pick(29), 1);
        assert_eq!(
            *asked.lock().unwrap(),
            [HOSTS[0], HOSTS[1]],
            "no probe while the cached host serves"
        );
        // The limit counts from the probe's answer, a moment after t0.
        assert_eq!(pick(31), 0);
        assert_eq!(
            *asked.lock().unwrap(),
            [HOSTS[0], HOSTS[1], HOSTS[0]],
            "exactly one more probe, of host 0"
        );
    }

    /// Whether a GET of `base` at `at` reaches the host, or `limits` refuses it unasked.
    fn asked_at(base: &'static str, limits: &Limits, at: Instant) -> bool {
        let asked = Cell::new(false);
        let _ = attempt_unless_limited(
            base,
            &|| {
                asked.set(true);
                Ok(String::new())
            },
            limits,
            &|| at,
        );
        asked.get()
    }

    // With the own node down, its probe takes its whole 3 s before the next host answers. That host's Retry-After
    // counts from its answer, as a GET's does, not from the start of the round.
    #[test]
    fn a_probe_429_after_a_slow_probe_of_an_earlier_host_limits_for_the_whole_retry_after() {
        let answered = Cell::new(None);
        let limits = Limits::default();
        let probe = |host: &HostSpec| {
            if host.base == HOSTS[0] {
                std::thread::sleep(Duration::from_secs(3));
                return Err(GetError::TimedOut("esplora get: timed out".into()));
            }
            answered.set(Some(Instant::now()));
            Err(throttled_after(60))
        };
        no_host_message(pick_host(
            &SPECS,
            &probe,
            Instant::now(),
            &Mutex::new(None),
            &[],
            &limits,
        ));
        let answered = answered.get().expect("the second host was probed");
        let after = |secs| answered + Duration::from_secs(secs);
        assert!(!asked_at(HOSTS[1], &limits, after(59)));
        assert!(asked_at(HOSTS[1], &limits, after(61)));
    }

    #[test]
    fn a_probe_429_counts_its_retry_after_from_the_answer_not_from_the_start_of_the_round() {
        let answered = Cell::new(None);
        let limits = Limits::default();
        let probe = |host: &HostSpec| {
            if host.base == HOSTS[0] {
                std::thread::sleep(Duration::from_millis(1500));
                answered.set(Some(Instant::now()));
                Err(throttled_after(5))
            } else {
                Ok(TESTNET4_GENESIS.to_string())
            }
        };
        let start = Instant::now();
        let picked = pick_host(&SPECS, &probe, start, &Mutex::new(None), &[], &limits);
        assert_eq!(picked.unwrap(), 1);
        let answered = answered.get().expect("the first host was probed");
        let after = |secs| answered + Duration::from_secs(secs);
        assert!(!asked_at(HOSTS[0], &limits, after(4)));
        assert!(asked_at(HOSTS[0], &limits, after(6)));
    }

    // An earlier host's slow probe can outlast a later host's short Retry-After. Counted from the start of the round,
    // that limit would be over before it was recorded, and the next pick would ask the host again at once.
    #[test]
    fn a_retry_after_shorter_than_the_probes_before_it_keeps_the_next_pick_off_that_host() {
        let second_probed = Cell::new(0);
        let probe = |host: &HostSpec| {
            if host.base == HOSTS[0] {
                std::thread::sleep(Duration::from_millis(1500));
                return Err(GetError::TimedOut("esplora get: timed out".into()));
            }
            second_probed.set(second_probed.get() + 1);
            Err(throttled_after(1))
        };
        let limits = Limits::default();
        let cache = Mutex::new(None);
        for _ in 0..2 {
            no_host_message(pick_host(
                &SPECS,
                &probe,
                Instant::now(),
                &cache,
                &[],
                &limits,
            ));
        }
        assert_eq!(
            second_probed.get(),
            1,
            "the next pick does not ask that host again"
        );
    }

    // A liar whose only sibling is limited leaves nothing to read from, so the scan ends `Unverified`, as
    // with a sibling that is down. A `Hosts` transport pins that a false `skip_host` ends it so.
    #[test]
    fn a_lie_with_the_only_other_host_limited_leaves_no_host_to_skip_to() {
        let probes = Cell::new(0);
        let probe = |host: &HostSpec| {
            probes.set(probes.get() + 1);
            assert_eq!(host.base, HOSTS[1], "a skipped host is never probed");
            Ok(TESTNET4_GENESIS.to_string())
        };
        let t0 = Instant::now();
        let limits = Limits::default();
        let _ = attempt_unless_limited(HOSTS[1], &|| Err(throttled()), &limits, &|| t0);
        let message = no_host_message(pick_host(
            &SPECS,
            &probe,
            t0,
            &Mutex::new(None),
            &[0],
            &limits,
        ));
        assert!(
            message.contains("skipped, it lied") && message.contains("limited for"),
            "{message}"
        );
        assert_eq!(probes.get(), 0, "a limited host is never probed");
        let free = pick_host(
            &SPECS,
            &probe,
            t0 + LIMIT_DEFAULT,
            &Mutex::new(None),
            &[0],
            &limits,
        );
        assert_eq!(free.unwrap(), 1);
        assert_eq!(probes.get(), 1);
    }

    #[test]
    fn a_429_on_the_fee_recommendation_sends_the_estimates_to_the_next_host() {
        let answer = |base: &str, path: &str| match (base, path) {
            (b, "/v1/fees/recommended") if b == HOSTS[0] => Err(throttled()),
            (b, "/fee-estimates") if b == HOSTS[1] => {
                Ok(r#"{"1": 12.5, "3": 8.1, "6": 4.0}"#.into())
            }
            other => panic!("unexpected request {other:?}"),
        };
        let world = World::new(&answer);
        let tiers = fee_tiers_from(
            &EsploraSource(&world),
            KeyNetwork::Test,
            Instant::now(),
            &Mutex::new(None),
        );
        assert_eq!(tiers, live(4, 9, 13));
        assert_eq!(
            *world.requests.lock().unwrap(),
            [
                (HOSTS[0], "/v1/fees/recommended".to_string()),
                (HOSTS[1], "/fee-estimates".to_string()),
            ]
        );
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
        for operator in [Operator::Mebit, Operator::Blockstream] {
            let host = HostSpec {
                base: Box::leak(dripping_host(Duration::from_secs(3), 10).into_boxed_str()),
                operator,
            };
            let probe_timeout = host.probe_timeout();
            let cache = Mutex::new(None);
            let start = Instant::now();
            let result = pick_host(
                &[host],
                &probe_genesis,
                start,
                &cache,
                &[],
                &Limits::default(),
            );
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
            asked
                .lock()
                .unwrap()
                .push((host.base, host.probe_timeout()));
            if host.base.starts_with("http://") {
                Err(GetError::TimedOut(format!(
                    "timed out after {:?}",
                    host.probe_timeout()
                )))
            } else {
                Ok(TESTNET4_GENESIS.to_string())
            }
        };
        let cache = Mutex::new(None);
        let hosts = hosts_with(Some(OWN_NODE_FIXTURE));
        let picked = pick_host(
            &hosts,
            &probe,
            Instant::now(),
            &cache,
            &[],
            &Limits::default(),
        )
        .unwrap();
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
        forget_host_if_unreachable(&got, &cache, &|| fake.store.mark_stale());
        assert!(matches!(got, Err(GetError::TimedOut(_))));
        assert!(took < whole + Duration::from_secs(3), "{took:?}");
        assert_eq!(chosen(&cache), None);
        assert!(fake.next_snapshot_syncs());
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
    fn a_sync_overtaken_by_a_broadcast_leaves_its_picture_stale_so_the_next_call_sees_the_payment()
    {
        use std::sync::atomic::{AtomicBool, Ordering::SeqCst};
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 50_000, 'a');
        assert_eq!(fake.snapshot("").unwrap().balance_sats, 50_000);
        fake.tick(SHARE_TTL);
        let forgot = AtomicBool::new(false);
        let get = |url: &str| {
            if !forgot.swap(true, SeqCst) {
                fake.store.mark_stale(); // what `broadcast_via` does once the payment is out
            }
            fake.get(url)
        };
        let stale = snapshot_from(
            &EsploraSource(&Reads(&get)),
            &abandon_about(),
            "",
            KeyNetwork::Test,
            AddressType::NativeSegwit,
            &|| fake.now(),
            &fake.store,
        )
        .unwrap();
        assert_eq!(
            stale.balance_sats, 50_000,
            "the overtaken sync still answers its own caller"
        );
        // The chain after the payment: the coin spent, 40,000 change at internal 0.
        fake.touch(&addr(Chain::External, 0));
        fake.utxos.insert(addr(Chain::External, 0), "[]".into());
        fake.fund(&addr(Chain::Internal, 0), 40_000, 'b');
        fake.tick(Duration::from_secs(3));
        assert_eq!(
            fake.snapshot("").unwrap().balance_sats,
            40_000,
            "inside the share window, and still a sync, because the payment came after the last one started"
        );
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
        let result = pick_host(
            &SPECS[..1],
            &|h| probe.run(h.base),
            Instant::now(),
            &cache,
            &[],
            &probe.limits,
        );
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
    fn a_transport_failure_forgets_the_host_and_marks_the_scans_stale_but_a_status_or_a_bad_body_does_not()
     {
        let cached = || Mutex::new(Some(HostVerdict::Chosen(0, Instant::now())));
        let scanned = || {
            let fake = Fake::new();
            fake.snapshot("").unwrap();
            fake
        };
        let scans_kept = |fake: &Fake| !fake.next_snapshot_syncs();

        let (cache, fake) = (cached(), scanned());
        forget_host_if_unreachable(&classify(no_answer()), &cache, &|| fake.store.mark_stale());
        assert_eq!(chosen(&cache), None, "a timeout");
        assert!(!scans_kept(&fake), "a timeout");

        let (cache, fake) = (cached(), scanned());
        let refused = std::io::Error::new(std::io::ErrorKind::ConnectionRefused, "no");
        forget_host_if_unreachable(&classify(Err(refused.into())), &cache, &|| {
            fake.store.mark_stale()
        });
        assert_eq!(chosen(&cache), None, "a refused connection");
        assert!(!scans_kept(&fake), "a refused connection");

        let (cache, fake) = (cached(), scanned());
        forget_host_if_unreachable(&classify(answer(500, "")), &cache, &|| {
            fake.store.mark_stale()
        });
        assert_eq!(chosen(&cache), Some(0), "HTTP 500");
        assert!(scans_kept(&fake), "HTTP 500");

        let (cache, fake) = (cached(), scanned());
        forget_host_if_unreachable(&classify(answer(404, "")), &cache, &|| {
            fake.store.mark_stale()
        });
        assert_eq!(chosen(&cache), Some(0), "HTTP 404");
        assert!(scans_kept(&fake), "HTTP 404");

        let (cache, fake) = (cached(), scanned());
        forget_host_if_unreachable(
            &classify(answer(200, "<html>Sign in</html>")),
            &cache,
            &|| fake.store.mark_stale(),
        );
        assert_eq!(chosen(&cache), Some(0), "a 200 with a bad body");
        assert!(scans_kept(&fake), "a 200 with a bad body");

        let (cache, fake) = (cached(), scanned());
        forget_host_if_unreachable(&classify(cut_short(200)), &cache, &|| {
            fake.store.mark_stale()
        });
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
        forget_host_if_unreachable(&classify(no_answer()), &cache, &|| {});
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
        let seconds: Vec<u64> = hosts.iter().map(|h| h.probe_timeout().as_secs()).collect();
        assert_eq!(seconds, [3, 8, 8]);
        let operators: Vec<Operator> = hosts.iter().map(|h| h.operator).collect();
        assert_eq!(
            operators,
            [
                Operator::Mebit,
                Operator::Blockstream,
                Operator::MempoolSpace
            ]
        );
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
        let no_host = Posts {
            host: Err("esplora get: no testnet4 host is reachable (both down)"),
            post: &post,
            lookup: &lookup,
        };
        let result = broadcast_via(&EsploraSource(&no_host), &good.hex, &good.txid, &fake.store);
        match result {
            Err(MobileSignerError::InvalidPsbtJson(m)) => {
                assert!(m.starts_with("esplora get: no testnet4 host"), "{m}")
            }
            other => panic!("expected the no-host error, got {other:?}"),
        }
        assert!(posted_to.lock().unwrap().is_empty());

        let second = Posts {
            host: Ok(HOSTS[1]),
            post: &post,
            lookup: &lookup,
        };
        let result = broadcast_via(&EsploraSource(&second), &good.hex, &good.txid, &fake.store);
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
                &EsploraSource(&Reads(&|url| self.get(url))),
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
    fn rates_near_the_networks_limit_are_capped_1_percent_under_it() {
        let host = FeeHost::new(
            Ok(r#"{"fastestFee":20000,"halfHourFee":10000.5,"hourFee":5000}"#),
            NOT_FOUND,
        );
        assert_eq!(host.tiers(), live(5000, 9900, 9900));
        let host = FeeHost::new(NOT_FOUND, Ok(r#"{"1":1e30,"3":10000,"6":9900.1}"#));
        assert_eq!(host.tiers(), live(9900, 9900, 9900));
    }

    // The fee is the rate times the size estimate. Plain signing came out short about half the time, so two inputs
    // and one output, 178 vB estimated, signed at 177 when both signatures did. Every signature is now ground to the
    // estimate's 72 bytes, so the cancel the app asks at the fast tier signs at exactly 178 vB, over 20 values of the
    // second coin.
    #[test]
    fn a_two_coin_cancel_at_the_highest_fast_tier_signs_at_the_size_its_fee_was_built_for() {
        let fast = FeeHost::new(
            Ok(r#"{"fastestFee":20000,"halfHourFee":1,"hourFee":1}"#),
            NOT_FOUND,
        )
        .tiers()
        .fast_sat_vb;
        let estimate = vsize(&[OWN_SPEND; 2], &[P2WPKH_SCRIPT]) as usize;
        for second in 0..20 {
            let mut fake = Fake::new();
            fake.fund(&addr(Chain::External, 0), 10_000_000, '1');
            fake.fund(&addr(Chain::External, 1), 6_000_000 + second, '2');
            let earlier = fake.send(SOMEONE, 15_000_000, 1).unwrap();
            assert_eq!(earlier.input.len(), 2);
            let (cancel, _) = fake
                .replace(&earlier, fast, Replacement::Cancel)
                .unwrap_or_else(|e| panic!("second coin {second}: {e:?}"));
            assert_eq!(cancel.vsize(), estimate, "second coin {second}");
            assert_replaces(&fake, &earlier, &cancel);
        }
    }

    // Repro 1 of the round-2 review (open item 86 point 7). A fast-tier send all of two coins and its fast-tier bump or
    // cancel both signed 1 vB under the estimate when their signatures came out short, and rule 6 then asked a rate the
    // node's 10,000 sat/vB refused on the bytes: 7 of 80 refused. With full-size signatures all 80 sign.
    #[test]
    fn a_fast_tier_replacement_of_a_fast_tier_two_coin_send_all_signs() {
        let fast = FeeHost::new(
            Ok(r#"{"fastestFee":20000,"halfHourFee":1,"hourFee":1}"#),
            NOT_FOUND,
        )
        .tiers()
        .fast_sat_vb;
        for second in 0..40u64 {
            let mut fake = Fake::new();
            fake.fund(&addr(Chain::External, 0), 10_000_000, '1');
            fake.fund(&addr(Chain::External, 1), 6_000_000 + second, '2');
            let (earlier, _) = fake.send_all_at(SOMEONE, fast).unwrap();
            for kind in KINDS {
                let (next, fee) = fake
                    .replace(&earlier, fast, kind)
                    .unwrap_or_else(|e| panic!("second coin {second}, {kind:?}: {e:?}"));
                assert_replaces(&fake, &earlier, &next);
                assert!(fee <= MAX_FEE_RATE * next.vsize() as u64, "{kind:?}: {fee}");
            }
        }
    }

    // Every fee is the rate times the size of 72-byte signatures, and the node judges the signed bytes, so a payment,
    // a send all, a fee bump and a cancel each sign every input at 72 bytes and weigh what the fee was built for.
    #[test]
    fn every_signature_is_ground_to_the_size_the_fee_was_built_for() {
        for second in 0..30u64 {
            let mut fake = Fake::new();
            fake.fund(&addr(Chain::External, 0), 100_000, '1');
            fake.fund(&addr(Chain::External, 1), 50_000 + second, '2');
            let payment = fake.send(SOMEONE, 120_000, 3).unwrap();
            let (all, _) = fake.send_all_at(SOMEONE, 2).unwrap();
            let mut txs = vec![payment.clone(), all];
            for kind in KINDS {
                txs.push(fake.replace(&payment, 10, kind).unwrap().0);
            }
            for tx in &txs {
                let scripts: Vec<usize> = tx.output.iter().map(|o| o.script_pubkey.len()).collect();
                let estimate = vsize(&vec![OWN_SPEND; tx.input.len()], &scripts);
                assert_eq!(tx.vsize() as u64, estimate, "second coin {second}");
                for input in &tx.input {
                    assert_eq!(input.witness.nth(0).map(<[u8]>::len), Some(72));
                }
            }
        }
        // The same payment signs the same bytes.
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 100_000, '1');
        assert_eq!(
            fake.send(SOMEONE, 5_000, 3).unwrap(),
            fake.send(SOMEONE, 5_000, 3).unwrap()
        );
    }

    // A Native SegWit payment, send all, fee bump and cancel sign exactly the bytes they signed before the address type existed
    // (df1a4a8), so a wallet with no saved type spends as it always did.
    #[test]
    fn a_native_segwit_payment_send_all_bump_and_cancel_sign_the_bytes_they_signed_before_address_types()
     {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 100_000, '1');
        fake.fund(&addr(Chain::External, 1), 50_000, '2');
        let payment = fake.send(SOMEONE, 120_000, 3).unwrap();
        let (bump, _) = fake.replace(&payment, 10, Replacement::FeeBump).unwrap();
        let (cancel, _) = fake.replace(&payment, 10, Replacement::Cancel).unwrap();
        let (all, _) = fake.send_all_at(SOMEONE, 2).unwrap();
        for (name, tx, before) in [
            (
                "payment",
                &payment,
                concat!(
                    "0200000000010211111111111111111111111111111111111111111111111111111111111111110000000000fdffffff",
                    "22222222222222222222222222222222222222222222222222222222222222220000000000fdffffff02c0d401000000",
                    "00001600149c98b5a27f7a2c6994a8dc1a741c420e7d3c5a10bd720000000000001600142f34aa1cf00a53b055a291a0",
                    "3a7d45f0a6988b5202483045022100f7d64402017123e05da0f29844a288aaed3b519d82db026974f5fb30046856e502",
                    "205cdd2cd771f484be996b86bbb8980a4dcfea0eb2efeb3336f9f3dcde3ac3ad97012102e7ab2537b5d49e970309aae0",
                    "6e9e49f36ce1c9febbd44ec8e0d1cca0b4f9c31902483045022100a01fb7f137e28917158d8fc0074cf3b05199162064",
                    "5be89ef8cd10b1192d1e00022061c02acb8cd1b0e9a85758f4bb1357f8120c8fe1873c4993af02897482e3c9bd012103",
                    "eeed205a69022fed4a62a02457f3699b19c06bf74bf801acc6d9ae84bc16a9e100000000"
                ),
            ),
            (
                "bump",
                &bump,
                concat!(
                    "0200000000010211111111111111111111111111111111111111111111111111111111111111110000000000fdffffff",
                    "22222222222222222222222222222222222222222222222222222222222222220000000000fdffffff02c0d401000000",
                    "00001600149c98b5a27f7a2c6994a8dc1a741c420e7d3c5a10066d0000000000001600142f34aa1cf00a53b055a291a0",
                    "3a7d45f0a6988b52024830450221009dfae3e9be6814c123535b94186732eca99c601f8723dc05d993949574dc0d5b02",
                    "203fad84d87be16105dd46aa665b78de7b0a86a2baad18fd65c3b2b50b27cd64c1012102e7ab2537b5d49e970309aae0",
                    "6e9e49f36ce1c9febbd44ec8e0d1cca0b4f9c31902483045022100d021e406161ded0a1bce59a326867f94b39c983384",
                    "450c1799b675f320f9ccdc0220583b4da5f021b22276007d5a8537f06a3eb86b24345ce2c0b39a2e0fa9e2ae1a012103",
                    "eeed205a69022fed4a62a02457f3699b19c06bf74bf801acc6d9ae84bc16a9e100000000"
                ),
            ),
            (
                "cancel",
                &cancel,
                concat!(
                    "0200000000010211111111111111111111111111111111111111111111111111111111111111110000000000fdffffff",
                    "22222222222222222222222222222222222222222222222222222222222222220000000000fdffffff01fc4202000000",
                    "00001600142f34aa1cf00a53b055a291a03a7d45f0a6988b5202483045022100ff98e855ca7022522edd94a4de814519",
                    "500a6c9bb14f4d274bb6c10d9fea50f50220043d56eef658ca1d29a5c2336c6082b6d377fac83c43dbb34afe6c583319",
                    "77ed012102e7ab2537b5d49e970309aae06e9e49f36ce1c9febbd44ec8e0d1cca0b4f9c3190248304502210080eb5fd7",
                    "642961da6bd1f6a51fac236ec514a98eb0b5d5353e602deee620aae20220435285055b96ccda54cb9d36367e881c3461",
                    "62ecda271022af007c226611b5dc012103eeed205a69022fed4a62a02457f3699b19c06bf74bf801acc6d9ae84bc16a9",
                    "e100000000"
                ),
            ),
            (
                "all",
                &all,
                concat!(
                    "0200000000010211111111111111111111111111111111111111111111111111111111111111110000000000fdffffff",
                    "22222222222222222222222222222222222222222222222222222222222222220000000000fdffffff018c4802000000",
                    "00001600149c98b5a27f7a2c6994a8dc1a741c420e7d3c5a1002483045022100e8e2472612a17188f0b247e65462c126",
                    "a3a10287f441bcf89c101a3ce0a6c9fb02206ddd66ebefa3b95a829ef35c5478ab1e7332d35d3cd93f4e34325a405d8c",
                    "2574012102e7ab2537b5d49e970309aae06e9e49f36ce1c9febbd44ec8e0d1cca0b4f9c31902483045022100d393561d",
                    "907f5b4e60f4a107dfb4e5f7893f983fb2e8d2357dd6449c4c7756cc02206dbc33b0af4370531aff9ee5be107372a90a",
                    "e1db0e1b133b7c004789932e93b9012103eeed205a69022fed4a62a02457f3699b19c06bf74bf801acc6d9ae84bc16a9",
                    "e100000000"
                ),
            ),
        ] {
            assert_eq!(serialize_hex(tx), before, "{name}");
        }
    }

    // embit 0.8 (an implementation apart from rust-bitcoin) derived these, after it reproduced the 8 BIP-84 vectors above,
    // BIP-49's published testnet vector (m/49'/1'/0'/0/0, the first Nested row) and the mainnet vectors of
    // `each_type_derives_its_published_mainnet_vector`. The script, in short:
    //     root = bip32.HDKey.from_seed(bip39.mnemonic_to_seed(WORDS, passphrase))
    //     key = root.derive(f"m/{purpose}h/1h/0h/{chain}/{index}").key
    //     {44: script.p2pkh, 49: lambda k: script.p2sh(script.p2wpkh(k)), 86: script.p2tr}[purpose](key)
    //         .address(NETWORKS["test"])
    const TYPE_VECTORS: &[(AddressType, &str, u32, u32, &str)] = &[
        (
            AddressType::Taproot,
            "",
            0,
            0,
            "tb1p8wpt9v4frpf3tkn0srd97pksgsxc5hs52lafxwru9kgeephvs7rqlqt9zj",
        ),
        (
            AddressType::Taproot,
            "",
            0,
            1,
            "tb1p90h6z3p36n9hrzy7580h5l429uwchyg8uc9sz4jwzhdtuhqdl5eqmpwq6n",
        ),
        (
            AddressType::Taproot,
            "",
            1,
            0,
            "tb1p6uav7en8k7zsumsqugdmg5j6930zmzy4dg7jcddshsr0fvxlqx7q7p5els",
        ),
        (
            AddressType::Taproot,
            "",
            1,
            1,
            "tb1pwhn9lzpaukrjwvwe365x7hcgvtcfywwsaxcq7j04jgrfcxzdq23qhzr7wt",
        ),
        (
            AddressType::Taproot,
            "TREZOR",
            0,
            0,
            "tb1paaphx7mxrkhpy3fel08k96fgul89tl5rwxweh7v4fevc72mav7gqvwj2e3",
        ),
        (
            AddressType::Taproot,
            "TREZOR",
            0,
            1,
            "tb1px406xwc209wg0w9kh5lwg07j9en7ene4e869duh390gvezh9tqdst02lnh",
        ),
        (
            AddressType::Taproot,
            "TREZOR",
            1,
            0,
            "tb1p99xgn6gy9va6y2zez3cwvwmrl04q3qhfxgzgef5c3k3zhw2gan8seqwkcz",
        ),
        (
            AddressType::Taproot,
            "TREZOR",
            1,
            1,
            "tb1pujy7qru25eqzjl2jqe5fkwexvafekg4pv9l7ljq905ry4z0n4ygs05jzme",
        ),
        (
            AddressType::Legacy,
            "",
            0,
            0,
            "mkpZhYtJu2r87Js3pDiWJDmPte2NRZ8bJV",
        ),
        (
            AddressType::Legacy,
            "",
            0,
            1,
            "mzpbWabUQm1w8ijuJnAof5eiSTep27deVH",
        ),
        (
            AddressType::Legacy,
            "",
            1,
            0,
            "mi8nhzZgGZQthq6DQHbru9crMDerUdTKva",
        ),
        (
            AddressType::Legacy,
            "",
            1,
            1,
            "mz9HfS6y833A8HP8bfpLikzCbjonJXaAGW",
        ),
        (
            AddressType::Legacy,
            "TREZOR",
            0,
            0,
            "muGDAb8H52iHAjF463rTazTjCWADPW7Yic",
        ),
        (
            AddressType::Legacy,
            "TREZOR",
            0,
            1,
            "mvdfAFHYzRvt4Te9FKZ68D7ZACcr3dTmp3",
        ),
        (
            AddressType::Legacy,
            "TREZOR",
            1,
            0,
            "mwxfSYqTaJEv8uy93Z5vPoyUnrFHhYp8xE",
        ),
        (
            AddressType::Legacy,
            "TREZOR",
            1,
            1,
            "mxYucRqt4oVLiiDEcreQzEaFbL8iZW4EqD",
        ),
        (
            AddressType::NestedSegwit,
            "",
            0,
            0,
            "2Mww8dCYPUpKHofjgcXcBCEGmniw9CoaiD2",
        ),
        (
            AddressType::NestedSegwit,
            "",
            0,
            1,
            "2N55m54k8vr95ggehfUcNkdbUuQvaqG2GxK",
        ),
        (
            AddressType::NestedSegwit,
            "",
            1,
            0,
            "2MvdUi5o3f2tnEFh9yGvta6FzptTZtkPJC8",
        ),
        (
            AddressType::NestedSegwit,
            "",
            1,
            1,
            "2NCtHHE9TjYrYnUWfZv79w9ktk1f2uPUzqu",
        ),
        (
            AddressType::NestedSegwit,
            "TREZOR",
            0,
            0,
            "2NB2kUBW5uEQHm3qaRWaaBGU8squWdSHQro",
        ),
        (
            AddressType::NestedSegwit,
            "TREZOR",
            0,
            1,
            "2Mu892r7eH1N8FqRqPgaoBNMZoTWvDYEZpR",
        ),
        (
            AddressType::NestedSegwit,
            "TREZOR",
            1,
            0,
            "2NArKE8G1CQx274FKnJ941fQpMMh1bwtLq1",
        ),
        (
            AddressType::NestedSegwit,
            "TREZOR",
            1,
            1,
            "2N8zhjFM8RsTH2XgLkiBXmRvY7UkYmNNhpv",
        ),
    ];

    #[test]
    fn mainnet_refuses_every_type_but_native_segwit() {
        for address_type in AddressType::ALL {
            let main = hot_receive_address(
                abandon_about(),
                String::new(),
                KeyNetwork::Main,
                address_type,
                0,
            );
            match address_type {
                AddressType::NativeSegwit => assert!(main.unwrap().starts_with("bc1q")),
                _ => assert!(
                    matches!(&main, Err(MobileSignerError::InvalidPsbtJson(m)) if m.contains("mainnet")),
                    "{address_type:?}: {main:?}"
                ),
            }
            assert!(
                hot_receive_address(
                    abandon_about(),
                    String::new(),
                    KeyNetwork::Test,
                    address_type,
                    0
                )
                .is_ok()
            );
        }
    }

    // Address Type enables a row only where the core derives that account: the app reads the rule through
    // `hot_address_type_enabled`, and `Account::new` refuses by the same rule.
    #[test]
    fn the_rows_the_app_enables_are_the_accounts_the_core_derives() {
        for (network, enabled) in [
            (KeyNetwork::Test, [true; 4]),
            (KeyNetwork::Main, [false, false, true, false]),
        ] {
            assert_eq!(
                AddressType::ALL.map(|t| hot_address_type_enabled(t, network)),
                enabled,
                "{network:?}"
            );
            for address_type in AddressType::ALL {
                let derives =
                    Account::new(&abandon_about(), "", network_of(network), address_type).is_ok();
                assert_eq!(
                    derives,
                    hot_address_type_enabled(address_type, network),
                    "{address_type:?} on {network:?}"
                );
            }
        }
    }

    #[test]
    fn addresses_of_each_type_match_an_independent_derivation() {
        for &(address_type, passphrase, chain, index, expected) in TYPE_VECTORS {
            let account = Account::new(
                &abandon_about(),
                passphrase,
                Network::Testnet4,
                address_type,
            )
            .unwrap();
            let chain = [Chain::External, Chain::Internal][chain as usize];
            let found = account.key(chain, index).unwrap().address.to_string();
            assert_eq!(
                found, expected,
                "{address_type:?} {passphrase:?} {}/{index}",
                chain as u32
            );
        }
    }

    // What the fingerprint sheets print for each type: the account path `TYPE_VECTORS` were derived at.
    #[test]
    fn each_type_prints_its_hot_wallet_account_path() {
        assert_eq!(
            AddressType::ALL.map(hot_account_path),
            ["m/44'/1'/0'", "m/49'/1'/0'", "m/84'/1'/0'", "m/86'/1'/0'"]
        );
    }

    // The published first mainnet address of `abandon_about` at m/{purpose}'/0'/0'/0/0, which embit reproduces too.
    #[test]
    fn each_type_derives_its_published_mainnet_vector() {
        let published = |address_type| match address_type {
            AddressType::Legacy => "1LqBGSKuX5yYUonjxT5qGfpUsXKYYWeabA",
            AddressType::NestedSegwit => "37VucYSaXLCAsxYyAPfbSi9eh4iEcbShgf",
            AddressType::NativeSegwit => "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu",
            AddressType::Taproot => {
                "bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr"
            }
        };
        let secp = Secp256k1::new();
        let seed = mnemonic_from(&abandon_about()).unwrap().to_seed("");
        let master = Xpriv::new_master(Network::Bitcoin, &seed).unwrap();
        for address_type in AddressType::ALL {
            let path: DerivationPath = format!("m/{}'/0'/0'/0/0", address_type.purpose())
                .parse()
                .unwrap();
            let key = master.derive_priv(&secp, &path).unwrap();
            let pubkey = CompressedPublicKey::from_private_key(
                &secp,
                &PrivateKey::new(key.private_key, Network::Bitcoin),
            )
            .unwrap();
            assert_eq!(
                address_type
                    .address(&secp, &pubkey, Network::Bitcoin)
                    .to_string(),
                published(address_type),
            );
        }
    }

    /// A Fake of `address_type`'s wallet whose receive 0 and 1 hold 100,000 and 50,000 sats, each output 1 of a
    /// transaction the Fake serves raw, which it lists at `listed`. The ids of those two transactions.
    fn funded_as(address_type: AddressType, listed: [u64; 2]) -> (Fake, [Txid; 2]) {
        let mut fake = Fake::new();
        fake.address_type = address_type;
        let addr = |index| {
            own_as(address_type, Chain::External, index)
                .address
                .to_string()
        };
        let txids = [
            fake.fund_raw(&addr(0), 100_000, listed[0], '1'),
            fake.fund_raw(&addr(1), 50_000, listed[1], '2'),
        ];
        (fake, txids)
    }

    // Each type's payment, send all, fee bump and cancel sign its own inputs, pay change to its own change address,
    // weigh what their fee was built for at that type's input weight, and sign the same bytes twice.
    #[test]
    fn each_type_pays_sends_all_bumps_and_cancels_at_the_size_its_fee_was_built_for() {
        for address_type in AddressType::ALL {
            let (fake, _) = funded_as(address_type, [100_000, 50_000]);
            let own = address_type.input_weight();
            let change = own_as(address_type, Chain::Internal, 0).address.to_string();
            let (payment, fee) = fake.send_at(SOMEONE, 120_000, 3).unwrap();
            let scripts = [
                SOMEONE_SCRIPT,
                own_as(address_type, Chain::Internal, 0)
                    .address
                    .script_pubkey()
                    .len(),
            ];
            assert_eq!(
                payment.vsize() as u64,
                vsize(&[own; 2], &scripts),
                "{address_type:?}"
            );
            assert_eq!(fee, 3 * payment.vsize() as u64, "{address_type:?}");
            assert_outputs(&payment, &[(SOMEONE, 120_000), (&change, 30_000 - fee)]);
            assert_eq!(
                fake.send(SOMEONE, 120_000, 3).unwrap(),
                payment,
                "{address_type:?}"
            );
            let (all, fee) = fake.send_all_at(SOMEONE, 2).unwrap();
            assert_eq!(
                all.vsize() as u64,
                vsize(&[own; 2], &[SOMEONE_SCRIPT]),
                "{address_type:?}"
            );
            assert_eq!(fee, 2 * all.vsize() as u64, "{address_type:?}");
            for kind in KINDS {
                let (next, fee) = fake
                    .replace(&payment, 10, kind)
                    .unwrap_or_else(|e| panic!("{address_type:?} {kind:?}: {e:?}"));
                let scripts: Vec<usize> =
                    next.output.iter().map(|o| o.script_pubkey.len()).collect();
                assert_eq!(
                    next.vsize() as u64,
                    vsize(&[own; 2], &scripts),
                    "{address_type:?} {kind:?}"
                );
                assert_eq!(fee, 10 * next.vsize() as u64, "{address_type:?} {kind:?}");
                assert_eq!(
                    fake.replace(&payment, 10, kind).unwrap().0,
                    next,
                    "{address_type:?} {kind:?}"
                );
            }
        }
    }

    // The round-2 review's fee repro: the Send and Review screens priced every type at Native SegWit's 141 vB, so Review
    // showed 1,128 at 8 sat/vB where a Legacy payment signed 1,784. They now price a payment at `hot_typical_vsize`, the size
    // one coin of the type signs paying an address of its own type with change, so Review's fee for that payment is the fee
    // signed and Native SegWit stays at 141. A send all of that coin is smaller, so it never sends less than Review showed.
    // The app's SendDraftTest pins these fees and amounts.
    #[test]
    fn each_types_typical_size_is_what_one_coin_paying_its_own_type_signs() {
        assert_eq!(
            AddressType::ALL.map(hot_typical_vsize),
            [226, 166, 141, 154]
        );
        let mut sent_all = Vec::new();
        for address_type in AddressType::ALL {
            let mut fake = Fake::new();
            fake.address_type = address_type;
            let own = own_as(address_type, Chain::External, 0).address.to_string();
            fake.fund_raw(&own, 100_000, 100_000, '1');
            let same_type =
                Account::new(&abandon_about(), "TREZOR", Network::Testnet4, address_type)
                    .unwrap()
                    .key(Chain::External, 0)
                    .unwrap()
                    .address
                    .to_string();
            let typical = hot_typical_vsize(address_type);
            let (payment, fee) = fake.send_at(&same_type, 30_000, 8).unwrap();
            assert_eq!(
                (
                    payment.input.len(),
                    payment.output.len(),
                    payment.vsize() as u64,
                    fee
                ),
                (1, 2, typical, 8 * typical),
                "{address_type:?}"
            );
            let (all, fee) = fake.send_all_at(SOMEONE, 8).unwrap();
            assert!(fee <= 8 * typical, "{address_type:?}: send all paid {fee}");
            sent_all.push(all.output[0].value.to_sat());
        }
        assert_eq!(sent_all, [98_488, 98_936, 99_120, 99_208]);
    }

    /// The quote and the payment of one chain state side by side. Both answer with the same figures, or both refuse with the
    /// same text, which this returns. The payment's output count and folded change, so a caller can see how its leftover
    /// settled.
    #[track_caller]
    fn quote_and_payment(
        fake: &Fake,
        to: &str,
        amount: SendAmount,
        rate: u64,
    ) -> Result<(usize, u64), String> {
        let case = format!(
            "{:?} wallet, {amount:?} to {to} at {rate} sat/vB",
            fake.address_type
        );
        let text = |e: MobileSignerError| match e {
            MobileSignerError::InvalidPsbtJson(m) => m,
            other => other.to_string(),
        };
        match (
            fake.quote(to, amount, rate),
            fake.signed_for(to, amount, rate),
        ) {
            (Ok(quote), Ok(signed)) => {
                let paid = QuotedSend {
                    fee_sats: signed.fee_sats,
                    amount_sats: signed.tx.output[0].value.to_sat(),
                    dust_change_sats: signed.dust_change_sats,
                    vsize: signed.tx.vsize() as u64,
                };
                assert_eq!(quote, paid, "{case}");
                Ok((signed.tx.output.len(), signed.dust_change_sats))
            }
            (Err(quote), Err(signed)) => {
                let (quote, signed) = (text(quote), text(signed));
                assert_eq!(quote, signed, "{case}");
                Err(quote)
            }
            (quote, signed) => panic!(
                "{case}: the quote {quote:?}, the payment's fee {:?}",
                signed.map(|s| s.fee_sats)
            ),
        }
    }

    // The round-3 repro turned around (`.audit/address-type-r3-repros`): Review priced one coin of the wallet's type paying
    // its own type, so 12 of the 16 wallet and destination types, and every payment of more coins, signed another fee than
    // it showed. The quote runs the payment's own plan, so for every pair, 1, 3 and 8 coins, each way the leftover settles
    // and send all, it reads the signed payment's fee, amount, folded change and size, and each refusal is the payment's.
    #[test]
    fn a_quote_is_what_the_signer_signs_for_the_same_chain_state() {
        const COIN: u64 = 50_000_000;
        for wallet_type in AddressType::ALL {
            let wallet = own_as(wallet_type, Chain::External, 0).address.to_string();
            let floor = dust_floor(
                &own_as(wallet_type, Chain::Internal, 0)
                    .address
                    .script_pubkey(),
            );
            for coins in [1, 3, 8] {
                let mut fake = Fake::new();
                fake.address_type = wallet_type;
                fake.fund_coins(&wallet, &vec![COIN; coins]);
                let have = COIN * coins as u64;
                for dest_type in AddressType::ALL {
                    let dest =
                        Account::new(&abandon_about(), "TREZOR", Network::Testnet4, dest_type)
                            .unwrap()
                            .key(Chain::External, 0)
                            .unwrap()
                            .address;
                    let to = dest.to_string();
                    let spends = vec![wallet_type.input_weight(); coins];
                    let with = vsize(&spends, &[dest_type.script_len(), wallet_type.script_len()]);
                    let without = vsize(&spends, &[dest_type.script_len()]);
                    // What every coin holds beyond the amount, and how it settles at the rate asked: change above its
                    // floor, change under its floor into the fee, too little for a change output, and change at its
                    // floor because into the fee would pass 10,000 sat/vB. Each needs every coin.
                    for (kind, rate, left, settled) in [
                        ("Change", 8, 8 * with + floor + 1_000, (2, 0)),
                        ("Fold", 8, 8 * with + floor / 2, (1, floor / 2)),
                        ("Fit", 8, 8 * without + 100, (1, 100)),
                        (
                            "FloorChange",
                            MAX_FEE_RATE,
                            MAX_FEE_RATE * with + floor - 1,
                            (2, 0),
                        ),
                    ] {
                        assert_eq!(
                            quote_and_payment(&fake, &to, SendAmount::Exact(have - left), rate),
                            Ok(settled),
                            "{kind}, {coins} coins to {dest_type:?}"
                        );
                    }
                    assert_eq!(
                        quote_and_payment(&fake, &to, SendAmount::All, 8),
                        Ok((1, 0))
                    );
                    let dest_floor = dust_floor(&dest.script_pubkey());
                    assert_eq!(
                        quote_and_payment(&fake, &to, SendAmount::Exact(dest_floor - 1), 8),
                        Err(format!(
                            "amount {} is below the network's {dest_floor} sat dust floor",
                            dest_floor - 1
                        ))
                    );
                }
                let short = quote_and_payment(&fake, SOMEONE, SendAmount::Exact(have + 1), 8);
                assert!(
                    short
                        .clone()
                        .unwrap_err()
                        .starts_with(&format!("insufficient funds: have {have} confirmed, need ")),
                    "{short:?}"
                );
                for amount in [SendAmount::Exact(1_000), SendAmount::All] {
                    assert_eq!(
                        quote_and_payment(&fake, SOMEONE, amount, MAX_FEE_RATE + 1),
                        Err("fee rate 10001 is above the network's 10000 sat/vB limit".into())
                    );
                }
            }
            let mut small = Fake::new();
            small.address_type = wallet_type;
            small.fund_coins(&wallet, &[1_000]);
            let refused = quote_and_payment(&small, SOMEONE, SendAmount::All, 10);
            assert!(
                refused
                    .clone()
                    .unwrap_err()
                    .starts_with("the whole balance 1000 less the fee "),
                "{refused:?}"
            );
        }
        let mut many = Fake::new();
        let coins: Vec<String> = (0..1_600u32).map(|i| coin('a', i, 2_000, true)).collect();
        many.stats
            .insert(addr(Chain::External, 0), used_stats(2_000 * 1_600));
        many.utxos
            .insert(addr(Chain::External, 0), format!("[{}]", coins.join(",")));
        assert_eq!(
            quote_and_payment(&many, SOMEONE, SendAmount::Exact(2_900_000), 1),
            Err("too many coins: this amount needs more than the network's 100000 vB, send a smaller amount".into())
        );
        assert_eq!(
            quote_and_payment(&many, SOMEONE, SendAmount::All, 1),
            Err("too many coins: the whole balance needs more than the network's 100000 vB, send a fixed amount".into())
        );
        let empty = quote_and_payment(&Fake::new(), SOMEONE, SendAmount::All, 1);
        assert!(
            empty
                .clone()
                .unwrap_err()
                .starts_with("insufficient funds: have 0 confirmed, need "),
            "{empty:?}"
        );
    }

    // A quote signs nothing, so it proves nothing: a Legacy quote reads the sync and the coin lists and no raw transaction,
    // and the payment after it still reads each coin's transaction once before it signs.
    #[test]
    fn a_quote_reads_no_parent_and_the_payment_still_proves_every_legacy_coin() {
        let mut fake = Fake::new();
        fake.address_type = AddressType::Legacy;
        let wallet = own_as(AddressType::Legacy, Chain::External, 0)
            .address
            .to_string();
        let txids = fake.fund_coins(&wallet, &[40_000, 30_000, 20_000]);
        let reads = |fake: &Fake| txids.iter().map(|t| fake.reads_of(*t)).collect::<Vec<_>>();
        fake.quote(SOMEONE, SendAmount::Exact(80_000), 2).unwrap();
        fake.quote(SOMEONE, SendAmount::All, 2).unwrap();
        assert_eq!(reads(&fake), [0, 0, 0], "a quote read a parent");
        let payment = fake.payment(SOMEONE, 80_000, 2).unwrap();
        assert_eq!(payment.tx.input.len(), 3);
        assert_eq!(reads(&fake), [1, 1, 1], "the payment proved each coin once");
    }

    // The chain can move between Review's quote and the signature. A coin that confirms on a funded address in between is the
    // largest, so the payment spends it alone and pays another fee than the quote read, which is why Review compares the two
    // before it posts. A quote taken again reads the payment.
    #[test]
    fn a_coin_that_arrives_between_quote_and_payment_changes_the_payment() {
        let mut fake = Fake::new();
        let wallet = addr(Chain::External, 0);
        fake.fund_coins(&wallet, &[20_000, 20_000]);
        let before = fake.quote(SOMEONE, SendAmount::Exact(30_000), 2).unwrap();
        fake.fund_coins(&wallet, &[20_000, 20_000, 100_000]);
        let payment = fake.payment(SOMEONE, 30_000, 2).unwrap();
        assert_eq!(
            payment.tx.input.len(),
            1,
            "the payment spends the coin that arrived"
        );
        assert_eq!(
            (before.fee_sats, payment.fee_sats),
            (
                2 * vsize(&[OWN_SPEND; 2], &[SOMEONE_SCRIPT, P2WPKH_SCRIPT]),
                2 * 141
            )
        );
        let again = fake.quote(SOMEONE, SendAmount::Exact(30_000), 2).unwrap();
        assert_eq!(again.fee_sats, payment.fee_sats);
    }

    // The round-1 review's repro: a host lists a coin of 100,000 sats at 60,000. The legacy sighash commits to no value,
    // so the Legacy payment would be valid at a fee of 40,669 where Review shows 669; it is refused once the coin's
    // transaction is read. Every other type reads nothing more, and its signature is invalid at the coin's true value.
    #[test]
    fn a_coin_listed_below_its_value_never_signs_a_valid_payment_at_another_fee() {
        for address_type in AddressType::ALL {
            let (liar, [txid, _]) = funded_as(address_type, [60_000, 50_000]);
            let key = own_as(address_type, Chain::External, 0);
            let coin = Utxo {
                txid,
                vout: 1,
                value: 100_000,
                confirmed: true,
            };
            let answer = liar.send_at(SOMEONE, 30_000, 3);
            match (address_type, &answer) {
                (AddressType::Legacy, Err(MobileSignerError::InvalidPsbtJson(m))) => {
                    assert!(m.starts_with("esplora scan:"), "{m}")
                }
                (AddressType::Legacy, _) => panic!("Legacy: {answer:?}"),
                (_, Ok((tx, _))) => assert!(
                    !signed_here(tx, &[(&coin, &key)]),
                    "{address_type:?} is valid at the coin's true value"
                ),
                (_, Err(e)) => panic!("{address_type:?}: {e:?}"),
            }
            assert_eq!(
                liar.reads_of(txid),
                usize::from(address_type == AddressType::Legacy),
                "{address_type:?}"
            );
        }
    }

    // A Legacy coin that its transaction does not show at the value listed, under or over, gets no signature: in a
    // payment, a send all, a fee bump or a cancel, as an earlier payment's coin or as the spare coin a bump adds. A
    // transaction the host does not know refuses the same way, and one it does not answer for refuses as a read to try
    // again.
    #[test]
    fn a_legacy_coin_its_transaction_does_not_show_at_the_value_listed_is_refused_before_signing() {
        let (honest, _) = funded_as(AddressType::Legacy, [100_000, 50_000]);
        let (earlier, _) = honest.send_at(SOMEONE, 30_000, 3).unwrap();
        // So little change that a bump at 40 sat/vB must add receive 1's coin.
        let (tight, _) = honest.send_at(SOMEONE, 95_000, 2).unwrap();
        let refused = |answer: &Result<(Transaction, u64), MobileSignerError>, why: &str| matches!(answer, Err(MobileSignerError::InvalidPsbtJson(m)) if m.starts_with(why));
        // 99,500 still holds what `earlier` paid out, so no check of the replacement refuses before the read.
        for listed in [[99_500, 50_000], [140_000, 50_000]] {
            let (liar, _) = funded_as(AddressType::Legacy, listed);
            for (flow, answer) in [
                ("payment", liar.send_at(SOMEONE, 30_000, 3)),
                ("send all", liar.send_all_at(SOMEONE, 2)),
                ("fee bump", liar.replace(&earlier, 10, Replacement::FeeBump)),
                ("cancel", liar.replace(&earlier, 10, Replacement::Cancel)),
            ] {
                assert!(
                    refused(&answer, "esplora scan:"),
                    "{listed:?} {flow}: {answer:?}"
                );
            }
        }
        for listed in [[100_000, 45_000], [100_000, 55_000]] {
            let (liar, _) = funded_as(AddressType::Legacy, listed);
            let answer = liar.replace(&tight, 40, Replacement::FeeBump);
            assert!(
                refused(&answer, "esplora scan:"),
                "{listed:?} spare coin: {answer:?}"
            );
        }
        let (mut gone, [txid, _]) = funded_as(AddressType::Legacy, [100_000, 50_000]);
        gone.unknown.insert(txid.to_string());
        let answer = gone.send_at(SOMEONE, 30_000, 3);
        assert!(refused(&answer, "esplora scan:"), "unknown: {answer:?}");
        let (mut slow, [txid, _]) = funded_as(AddressType::Legacy, [100_000, 50_000]);
        slow.failing.insert(txid.to_string());
        let answer = slow.send_at(SOMEONE, 30_000, 3);
        assert!(refused(&answer, "esplora get:"), "timed out: {answer:?}");
    }

    // An honest host's Legacy payment, send all, fee bump, cancel and bump that adds a spare coin sign the bytes and pay
    // the fees they did before `signed` read a Legacy coin's transaction (d87c4ae), and the payment reads each of its
    // two coins' transactions once. A Legacy transaction has no witness, so its id covers every byte.
    #[test]
    fn honest_legacy_payments_send_alls_bumps_and_cancels_sign_what_they_signed_before_the_value_check()
     {
        let (fake, [a, b]) = funded_as(AddressType::Legacy, [100_000, 50_000]);
        let payment = fake.send_at(SOMEONE, 120_000, 3).unwrap();
        assert_eq!((fake.reads_of(a), fake.reads_of(b)), (1, 1));
        let one_coin = fake.send_at(SOMEONE, 95_000, 2).unwrap();
        for (name, (tx, fee), before) in [
            (
                "payment",
                payment.clone(),
                (
                    "69925df432b58eff257aeacaed440a9859989c7fac7a1451493d9651cbd0f98c",
                    1_113,
                ),
            ),
            (
                "fee bump",
                fake.replace(&payment.0, 10, Replacement::FeeBump).unwrap(),
                (
                    "955caf686b87ca19c7ac3bcb898a5cad2715f55dfcfd000ec18cf6ac722007dc",
                    3_710,
                ),
            ),
            (
                "cancel",
                fake.replace(&payment.0, 10, Replacement::Cancel).unwrap(),
                (
                    "ec2cb3241a0e9c7285b7177ba5c075c4f7eeb50d2be648b9b51368120a02ca61",
                    3_400,
                ),
            ),
            (
                "send all",
                fake.send_all_at(SOMEONE, 2).unwrap(),
                (
                    "33f7c59f36d64998a6e83a8406735b8cb526023d61fd6d7762fd2fa2c43eef24",
                    674,
                ),
            ),
            (
                "one coin",
                one_coin.clone(),
                (
                    "87c3ccb027bd7ee9f9303ffd5d2e26948f59408850525b19b3ac3c3fb6478be4",
                    446,
                ),
            ),
            (
                "fee bump with a spare coin",
                fake.replace(&one_coin.0, 40, Replacement::FeeBump).unwrap(),
                (
                    "9a173d0a37ecca7032463967d72ca5641a39617710418dc9e52e4b60cac0caa0",
                    14_840,
                ),
            ),
        ] {
            assert_eq!(
                (tx.compute_wtxid().to_string().as_str(), fee),
                before,
                "{name}"
            );
        }
    }

    /// `count` confirmed coins of `value` sats each to `key`, as `signed` takes them: the outputs of one
    /// transaction, which the returned Fake serves raw.
    fn coins_of(key: &OwnKey, count: u32, value: u64) -> (Vec<Utxo>, Fake) {
        let output = TxOut {
            value: Amount::from_sat(value),
            script_pubkey: key.address.script_pubkey(),
        };
        let tx = paying('7', vec![output; count as usize]);
        let txid = tx.compute_txid();
        let mut fake = Fake::new();
        fake.raw.insert(txid.to_string(), serialize_hex(&tx));
        let coins = (0..count)
            .map(|vout| Utxo {
                txid,
                vout,
                value,
                confirmed: true,
            })
            .collect();
        (coins, fake)
    }

    // `signed` refuses a transaction that does not weigh what `vsize` sized its fee for, so each type signing from 1 to
    // 300 coins, past the 253-input varint step, proves its input weight is exact, and `signed_here` accepts each.
    #[test]
    fn each_type_signs_at_its_input_weight_from_1_to_300_coins() {
        for address_type in AddressType::ALL {
            let key = own_as(address_type, Chain::External, 0);
            for count in [1, 2, 252, 253, 300] {
                let (coins, fake) = coins_of(&key, count, 10_000);
                let selected: Vec<(&Utxo, &OwnKey)> = coins.iter().map(|u| (u, &key)).collect();
                let pay = TxOut {
                    value: Amount::from_sat(5_000),
                    script_pubkey: key.address.script_pubkey(),
                };
                let tx = signed(
                    &EsploraSource(&Reads(&|url| fake.get(url))),
                    &selected,
                    vec![pay],
                )
                .unwrap_or_else(|e| panic!("{address_type:?} {count}: {e:?}"));
                assert_eq!(
                    tx.vsize() as u64,
                    vsize(
                        &vec![address_type.input_weight(); count as usize],
                        &[key.address.script_pubkey().len()]
                    ),
                    "{address_type:?} {count}"
                );
                assert!(signed_here(&tx, &selected), "{address_type:?} {count}");
            }
        }
    }

    /// One coin of `key` signed to itself by `signed`, with what `signed_here` reads for it.
    fn one_signed(key: &OwnKey) -> (Transaction, Vec<Utxo>) {
        let (coins, fake) = coins_of(key, 1, 10_000);
        let pay = TxOut {
            value: Amount::from_sat(5_000),
            script_pubkey: key.address.script_pubkey(),
        };
        let tx = signed(
            &EsploraSource(&Reads(&|url| fake.get(url))),
            &[(&coins[0], key)],
            vec![pay],
        );
        (tx.unwrap(), coins)
    }

    #[test]
    fn a_taproot_spend_in_another_shape_is_not_signed_here() {
        let key = own_as(AddressType::Taproot, Chain::External, 0);
        let (tx, coins) = one_signed(&key);
        let selected = [(&coins[0], &key)];
        assert!(signed_here(&tx, &selected));
        let sig = tx.input[0].witness.nth(0).unwrap().to_vec();
        let with = |witness: Witness| {
            let mut forged = tx.clone();
            forged.input[0].witness = witness;
            forged
        };
        let mut all = sig.clone();
        all.push(0x01); // SIGHASH_ALL, a 65-byte signature
        let mut script_sig = tx.clone();
        script_sig.input[0].script_sig = ScriptBuf::from(vec![0x51]);
        let mut flipped = sig.clone();
        flipped[63] ^= 1;
        // The same key spending as P2WPKH would: a 72-byte ECDSA signature and the key.
        let (native, _) = one_signed(&OwnKey {
            address_type: AddressType::NativeSegwit,
            ..own_as(AddressType::Taproot, Chain::External, 0)
        });
        for (what, forged) in [
            ("65 bytes", with(Witness::from_slice(&[all]))),
            (
                "two items",
                with(Witness::from_slice(&[sig.clone(), sig.clone()])),
            ),
            ("flipped", with(Witness::from_slice(&[flipped]))),
            ("p2wpkh shape", with(native.input[0].witness.clone())),
            ("script_sig", script_sig),
        ] {
            assert!(!signed_here(&forged, &selected), "{what}");
        }
    }

    #[test]
    fn a_legacy_spend_with_a_witness_or_another_script_sig_is_not_signed_here() {
        let key = own_as(AddressType::Legacy, Chain::External, 0);
        let (tx, coins) = one_signed(&key);
        let selected = [(&coins[0], &key)];
        assert!(signed_here(&tx, &selected));
        let script_sig = tx.input[0].script_sig.clone();
        let mut witnessed = tx.clone();
        witnessed.input[0].witness = Witness::from_slice(&[[0u8; 1]]);
        let mut empty = tx.clone();
        empty.input[0].script_sig = ScriptBuf::new();
        // The same pushes behind a no-op: the node runs it, but it is not what `signed` writes.
        let mut padded = tx.clone();
        let mut bytes = vec![0x61]; // OP_NOP
        bytes.extend_from_slice(script_sig.as_bytes());
        padded.input[0].script_sig = ScriptBuf::from(bytes);
        let other = own_as(AddressType::Legacy, Chain::External, 1);
        let mut other_key = tx.clone();
        other_key.input[0].script_sig = legacy_like(&script_sig, &other);
        for (what, forged) in [
            ("witness", witnessed),
            ("empty", empty),
            ("padded", padded),
            ("other key", other_key),
        ] {
            assert!(!signed_here(&forged, &selected), "{what}");
        }
    }

    /// `script_sig`'s signature push followed by `key`'s public key.
    fn legacy_like(script_sig: &Script, key: &OwnKey) -> ScriptBuf {
        let sig = match script_sig.instructions().next() {
            Some(Ok(bitcoin::script::Instruction::PushBytes(sig))) => sig.to_owned(),
            other => panic!("not a push: {other:?}"),
        };
        let pubkey =
            bitcoin::script::PushBytesBuf::try_from(key.pubkey.to_bytes().to_vec()).unwrap();
        ScriptBuf::builder()
            .push_slice(sig)
            .push_slice(pubkey)
            .into_script()
    }

    #[test]
    fn a_nested_segwit_spend_without_its_redeem_push_is_not_signed_here() {
        let key = own_as(AddressType::NestedSegwit, Chain::External, 0);
        let (tx, coins) = one_signed(&key);
        let selected = [(&coins[0], &key)];
        assert!(signed_here(&tx, &selected));
        let other = own_as(AddressType::NestedSegwit, Chain::External, 1);
        for script_sig in [
            ScriptBuf::new(),
            other.address.script_pubkey(),
            tx.input[0].script_sig.clone().into_bytes()[1..]
                .to_vec()
                .into(),
        ] {
            let mut forged = tx.clone();
            forged.input[0].script_sig = script_sig.clone();
            assert!(!signed_here(&forged, &selected), "{script_sig:?}");
        }
        // The same key's Native SegWit spend: the right witness and no redeem push.
        let mut native = tx.clone();
        native.input[0].script_sig = ScriptBuf::new();
        assert!(!signed_here(&native, &selected));
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
            &EsploraSource(&Reads(&|url| host.get(url))),
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
    fn a_transport_failure_while_fetching_fees_forgets_the_host_and_marks_the_scans_stale_and_still_answers()
     {
        let hosts = Mutex::new(Some(HostVerdict::Chosen(0, Instant::now())));
        let fake = Fake::new();
        fake.snapshot("").unwrap();
        let get = |_: &str| {
            get_forgetting(&|| classify(no_answer()), &|_| {}, &hosts, &|| {
                fake.store.mark_stale()
            })
        };
        let tiers = fee_tiers_from(
            &EsploraSource(&Reads(&get)),
            KeyNetwork::Test,
            Instant::now(),
            &Mutex::new(None),
        );
        assert_eq!(tiers, DEFAULT_FEE_TIERS);
        assert_eq!(chosen(&hosts), None);
        assert!(fake.next_snapshot_syncs());
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
            TESTNET4.0.get(url)
        };
        let start = std::time::Instant::now();
        let wallet = synced(
            &EsploraSource(&Reads(&counted)),
            &abandon_about(),
            "",
            KeyNetwork::Test,
            AddressType::NativeSegwit,
            &SystemTime::now,
            &ScanStore::default(),
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
    // Mac while this phrase failed on the phone and on the Mac. It keeps its scan in MEBIT_SCAN_DIR, or a
    // folder under the temp folder, so a second run is a restart, which syncs from the file.
    #[test]
    #[ignore = "needs the network and a testnet4 host, the own node over Tailscale first"]
    fn real_snapshot_of_the_24_word_public_test_phrase_the_phone_imported() {
        let mut words = vec!["abandon".to_string(); 23];
        words.push("art".into());
        // Receive index 0, derived outside this crate (stdlib PBKDF2 + BIP-32 + bech32).
        assert_eq!(
            hot_receive_address(
                words.clone(),
                String::new(),
                KeyNetwork::Test,
                AddressType::NativeSegwit,
                0
            )
            .unwrap(),
            "tb1qtk89me2ae95dmlp3yfl4q9ynpux8mxjujuf2fr"
        );
        let asked = Mutex::new(Vec::new());
        let counted = |url: &str| {
            asked.lock().unwrap().push(url.to_string());
            TESTNET4.0.get(url)
        };
        let dir = std::env::var_os("MEBIT_SCAN_DIR")
            .map(PathBuf::from)
            .unwrap_or_else(|| std::env::temp_dir().join("mebit-real-scan"));
        let store = ScanStore::default();
        store.use_dir(dir.clone());
        let id = Account::new(&words, "", Network::Testnet4, AddressType::NativeSegwit)
            .unwrap()
            .id();
        let file_before = fs::metadata(dir.join(format!(
            "{}.json",
            id.iter().map(|b| format!("{b:02x}")).collect::<String>()
        )))
        .is_ok();
        let start = std::time::Instant::now();
        let snapshot = snapshot_from(
            &EsploraSource(&Reads(&counted)),
            &words,
            "",
            KeyNetwork::Test,
            AddressType::NativeSegwit,
            &SystemTime::now,
            &store,
        );
        let took = start.elapsed();
        let asked = asked.into_inner().unwrap();
        let count = |kind: fn(&str) -> bool| {
            asked
                .iter()
                .filter(|url| kind(url.strip_prefix(ESPLORA_TESTNET4).unwrap()))
                .count()
        };
        let summaries = count(|p| p.starts_with("/address/") && p.matches('/').count() == 2);
        let proofs = count(|p| p.ends_with("/txs"));
        let proof_reads = count(|p| {
            [
                "/txs",
                "/blocks/tip/height",
                "/hex",
                "/merkle-proof",
                "/header",
            ]
            .iter()
            .any(|end| p.ends_with(end))
        });
        let coins = count(|p| p.ends_with("/utxo"));
        let lookups = count(|p| p.starts_with("/tx/") && p.matches('/').count() == 2);
        println!(
            "snapshot took {took:?} for {} requests, with a scan file in {} {}: {summaries} summaries, {proofs} proofs in {proof_reads} reads, {coins} coin lists, {lookups} transaction lookups: {snapshot:?}",
            asked.len(),
            dir.display(),
            if file_before {
                "before it"
            } else {
                "only after it"
            },
        );
        let entry = store.wallet(&id);
        let entry = entry.lock().unwrap();
        if let Some(last) = &entry.last {
            println!("status: {:?}", last.status);
        }
        for (scan, name) in entry.scan.chains.iter().zip(["receive", "change"]) {
            let flagged = |flag: fn(&Seen) -> bool| scan.seen.iter().filter(|s| flag(s)).count();
            println!(
                "{name}: frontier {}, {} asked, {} used, {} funded, {} pending",
                scan.frontier(),
                scan.seen.len(),
                flagged(|s| s.used),
                flagged(|s| s.funded),
                flagged(|s| s.pending)
            );
        }
        snapshot.unwrap();
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
            "insufficient funds: have 0 confirmed, need 1110",
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
        // Transaction lookups, not a proof's reads of the raw transaction and its branch.
        let looked_up: Vec<String> = fake.calls.lock().unwrap()[before..]
            .iter()
            .filter(|url| {
                url.contains("/tx/") && !url.ends_with("/hex") && !url.ends_with("/merkle-proof")
            })
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

    /// A testnet4 address of each script type a destination can have, in this order: P2WPKH, P2TR, P2WSH,
    /// P2SH, P2PKH.
    fn destinations() -> [(&'static str, String); 5] {
        let key = own(Chain::External, 0);
        let secp = Secp256k1::new();
        let redeem = key.address.script_pubkey();
        [
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
        ]
    }

    // V5. Every output was sized as P2WPKH (31 vB). A taproot or P2WSH output is 43 vB, P2SH 32 and
    // P2PKH 34, so those payments paid under the rate asked for, below the relay floor at 1 sat/vB.
    #[test]
    fn the_fee_is_never_under_the_rate_asked_for_whatever_the_destination_script_on_both_paths() {
        for (kind, to) in &destinations() {
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
            &EsploraSource(&Reads(&|url| fake.get(url))),
            &abandon_about(),
            "",
            KeyNetwork::Test,
            AddressType::NativeSegwit,
            txid,
            &fake.store,
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

    // The detail tags what a scan reaches, which is each chain's indices below frontier + GAP once a
    // scan is cached, and the first GAP of each chain before.
    #[test]
    fn an_address_below_the_cached_frontier_plus_the_gap_is_the_wallets_and_the_next_is_not() {
        let owners = |fake: &Fake, receive: u32, change: u32| -> Vec<TxoOwner> {
            let outputs = [
                txo(&addr(Chain::External, receive - 1), 1_000),
                txo(&addr(Chain::Internal, change - 1), 2_000),
                txo(&addr(Chain::External, receive), 3_000),
                txo(&addr(Chain::Internal, change), 4_000),
            ];
            let mut fake_tx = HashMap::new();
            fake_tx.insert(
                id('3'),
                full_tx('3', &[spent(SOMEONE, 11_000)], &outputs, MEMPOOL),
            );
            let answer = |url: &str| match url.strip_prefix(&format!("{ESPLORA_TESTNET4}/tx/")) {
                Some(txid) => Ok(fake_tx[txid].clone()),
                None => fake.get(url),
            };
            detail_of(
                &EsploraSource(&Reads(&answer)),
                &abandon_about(),
                "",
                KeyNetwork::Test,
                AddressType::NativeSegwit,
                &id('3'),
                &fake.store,
            )
            .unwrap()
            .unwrap()
            .outputs
            .iter()
            .map(|o| o.owner)
            .collect()
        };
        let mine = [
            TxoOwner::Receive,
            TxoOwner::Change,
            TxoOwner::Other,
            TxoOwner::Other,
        ];
        let mut fake = Fake::new();
        assert_eq!(owners(&fake, GAP, GAP), mine, "no scan cached");
        fake.touch(&addr(Chain::External, 30));
        for i in (0..=30).step_by(10) {
            fake.touch(&addr(Chain::External, i));
        }
        fake.touch(&addr(Chain::Internal, 4));
        fake.snapshot("").unwrap();
        assert_eq!(owners(&fake, 31 + GAP, 5 + GAP), mine, "scan cached");
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
        // `Http::get` past the host choice, with the real request, classify, the retry and the forget.
        let esplora = |url: &str| {
            *calls.lock().unwrap() += 1;
            let url = on_host(url, &host);
            get_forgetting(
                &|| classify(AGENT.get(&url).call()),
                &|_| {},
                &Mutex::new(None),
                &|| {},
            )
        };
        let found = detail_of(
            &EsploraSource(&Reads(&esplora)),
            &abandon_about(),
            "",
            KeyNetwork::Test,
            AddressType::NativeSegwit,
            &id('1'),
            &ScanStore::default(),
        );
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
            &EsploraSource(&Reads(&|url| fake.get(url))),
            &abandon_about(),
            "",
            KeyNetwork::Main,
            AddressType::NativeSegwit,
            &one,
            &ScanStore::default(),
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

    // `Http::get` with the network swapped out. The real pick_host runs over the public hosts, where blockstream's probe gets a
    // real 404 for /block-height/0 through probe_genesis and mempool.space times out, so nothing asks for the transaction.
    #[test]
    fn a_probe_404_inside_no_host_reachable_is_a_failure_not_an_unknown_txid() {
        let answers_404: &'static str = Box::leak(not_found_host().into_boxed_str());
        let probe = |host: &HostSpec| match host.base {
            ESPLORA_TESTNET4 => probe_genesis(&HostSpec {
                base: answers_404,
                ..*host
            }),
            _ => classify(no_answer()),
        };
        let chosen = Mutex::new(None);
        let limits = Limits::default();
        let esplora = |url: &str| -> Result<String, MobileSignerError> {
            let index = pick_host(
                &PUBLIC_TESTNET4_HOSTS,
                &probe,
                Instant::now(),
                &chosen,
                &[],
                &limits,
            )?;
            unreachable!("no host passed its probe, yet {url} went to host {index}")
        };
        match detail_of(
            &EsploraSource(&Reads(&esplora)),
            &abandon_about(),
            "",
            KeyNetwork::Test,
            AddressType::NativeSegwit,
            &id('1'),
            &ScanStore::default(),
        ) {
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

    /// Where the proof block's header search starts, and what `mine_the_proof_block` found, in 648 s on
    /// 8 threads (2026-11-07, after the block began paying `OTHER_TYPES_PAID`).
    const MINED_TIME: u32 = 1_762_387_210;
    const MINED_NONCE: u32 = 0x34fe_799d;
    /// How many addresses of each chain of `abandon_about` the proof block pays, `PAID_SATS` each.
    const PAID: [u32; 2] = [320, 120];
    /// Receive 0 and 1 and change 0 and 1 of `abandon_about` as Legacy, Nested SegWit and Taproot, which the proof
    /// block pays too, from `TYPE_VECTORS`.
    const OTHER_TYPES_PAID: [&str; 12] = [
        "mkpZhYtJu2r87Js3pDiWJDmPte2NRZ8bJV",
        "mzpbWabUQm1w8ijuJnAof5eiSTep27deVH",
        "mi8nhzZgGZQthq6DQHbru9crMDerUdTKva",
        "mz9HfS6y833A8HP8bfpLikzCbjonJXaAGW",
        "2Mww8dCYPUpKHofjgcXcBCEGmniw9CoaiD2",
        "2N55m54k8vr95ggehfUcNkdbUuQvaqG2GxK",
        "2MvdUi5o3f2tnEFh9yGvta6FzptTZtkPJC8",
        "2NCtHHE9TjYrYnUWfZv79w9ktk1f2uPUzqu",
        "tb1p8wpt9v4frpf3tkn0srd97pksgsxc5hs52lafxwru9kgeephvs7rqlqt9zj",
        "tb1p90h6z3p36n9hrzy7580h5l429uwchyg8uc9sz4jwzhdtuhqdl5eqmpwq6n",
        "tb1p6uav7en8k7zsumsqugdmg5j6930zmzy4dg7jcddshsr0fvxlqx7q7p5els",
        "tb1pwhn9lzpaukrjwvwe365x7hcgvtcfywwsaxcq7j04jgrfcxzdq23qhzr7wt",
    ];
    const PAID_SATS: u64 = 10_000;
    /// The proof block's height on the pages that list its payments.
    const PROOF_HEIGHT: u64 = 100;

    /// A block that pays each of the first `PAID` addresses of each chain, then spends receive 0's payment as
    /// `a_send_with_change_is_one_outgoing_transaction_in_the_history` lists it, with a header mined at
    /// testnet4's minimum difficulty. Through it the Fake proves those addresses used the way a real
    /// source would, so every scan here runs the real `prove_use`.
    struct ProofBlock {
        txs: Vec<Transaction>,
        txids: Vec<Txid>,
        header: bitcoin::block::Header,
        /// The history page of each paid address, which lists its payment as confirmed in this block.
        pages: HashMap<String, String>,
        /// What `/tx/{txid}` answers for each payment, confirmed in this block.
        views: HashMap<String, String>,
    }

    static PROOF: LazyLock<ProofBlock> = LazyLock::new(|| {
        let account = Account::new(
            &abandon_about(),
            "",
            Network::Testnet4,
            AddressType::NativeSegwit,
        )
        .unwrap();
        let address = |chain, index| account.key(chain, index).unwrap().address;
        let tx = |previous_output, output| Transaction {
            version: bitcoin::transaction::Version::TWO,
            lock_time: LockTime::ZERO,
            input: vec![TxIn {
                previous_output,
                ..TxIn::default()
            }],
            output,
        };
        let mut txs = Vec::new();
        let mut paid = Vec::new();
        for chain in [Chain::External, Chain::Internal] {
            for index in 0..PAID[chain as usize] {
                let funding = OutPoint {
                    txid: Txid::from_byte_array([chain as u8 + 1; 32]),
                    vout: index,
                };
                let to = address(chain, index);
                let output = TxOut {
                    value: Amount::from_sat(PAID_SATS),
                    script_pubkey: to.script_pubkey(),
                };
                txs.push(tx(funding, vec![output]));
                paid.push(to.to_string());
            }
        }
        for (index, to) in OTHER_TYPES_PAID.iter().enumerate() {
            let funding = OutPoint {
                txid: Txid::from_byte_array([3; 32]),
                vout: index as u32,
            };
            let to = Address::from_str(to).unwrap().assume_checked();
            let output = TxOut {
                value: Amount::from_sat(PAID_SATS),
                script_pubkey: to.script_pubkey(),
            };
            txs.push(tx(funding, vec![output]));
            paid.push(to.to_string());
        }
        let receive_0 = OutPoint {
            txid: txs[0].compute_txid(),
            vout: 0,
        };
        let someone = Address::from_str(SOMEONE).unwrap().assume_checked();
        let pays = |address: &Address, sats| TxOut {
            value: Amount::from_sat(sats),
            script_pubkey: address.script_pubkey(),
        };
        txs.push(tx(
            receive_0,
            vec![
                pays(&someone, 6_000),
                pays(&address(Chain::Internal, 0), 3_000),
            ],
        ));
        let txids: Vec<Txid> = txs.iter().map(Transaction::compute_txid).collect();
        let header = proof_header(merkle(&txids, 0).0, MINED_TIME, MINED_NONCE);
        let status = in_proof_block(&header, PROOF_HEIGHT);
        let (mut pages, mut views) = (HashMap::new(), HashMap::new());
        for (address, txid) in paid.into_iter().zip(&txids) {
            let payment = tx_of(
                &txid.to_string(),
                &[spent(SOMEONE, PAID_SATS + 1_000)],
                &[txo(&address, PAID_SATS)],
                &status,
            );
            pages.insert(address, format!("[{payment}]"));
            views.insert(txid.to_string(), payment);
        }
        ProofBlock {
            txs,
            txids,
            header,
            pages,
            views,
        }
    });

    /// A transaction's status as a source lists it once `header`'s block holds it at `height`.
    fn in_proof_block(header: &bitcoin::block::Header, height: u64) -> String {
        format!(
            r#"{{"confirmed":true,"block_height":{height},"block_time":1790000000,"block_hash":"{}"}}"#,
            header.block_hash()
        )
    }

    /// The merkle root of `txids` and the branch of the one at `pos`. An odd level repeats its last node, as in Bitcoin.
    fn merkle(
        txids: &[Txid],
        mut pos: usize,
    ) -> (bitcoin::TxMerkleNode, Vec<bitcoin::TxMerkleNode>) {
        let mut level: Vec<[u8; 32]> = txids.iter().map(|txid| txid.to_byte_array()).collect();
        let mut branch = Vec::new();
        while level.len() > 1 {
            if level.len() % 2 == 1 {
                level.push(level[level.len() - 1]);
            }
            branch.push(bitcoin::TxMerkleNode::from_byte_array(level[pos ^ 1]));
            level = level
                .chunks(2)
                .map(|pair| bitcoin::TxMerkleNode::hash(&pair.concat()).to_byte_array())
                .collect();
            pos /= 2;
        }
        (bitcoin::TxMerkleNode::from_byte_array(level[0]), branch)
    }

    fn proof_header(root: bitcoin::TxMerkleNode, time: u32, nonce: u32) -> bitcoin::block::Header {
        bitcoin::block::Header {
            version: bitcoin::block::Version::from_consensus(0x2000_0000),
            prev_blockhash: bitcoin::BlockHash::from_byte_array([0x33; 32]),
            merkle_root: root,
            time,
            bits: bitcoin::pow::CompactTarget::from_consensus(0x1d00ffff),
            nonce,
        }
    }

    /// What a source answers for a read of the proof block, which is a payment, a transaction's raw bytes or
    /// merkle branch or the header, or None for any other path. Another transaction or block is a 404.
    fn proof_answer(path: &str) -> Option<Result<String, MobileSignerError>> {
        let not_found = || {
            Err(MobileSignerError::InvalidPsbtJson(format!(
                "esplora get: {ESPLORA_TESTNET4}{path}: status code 404"
            )))
        };
        let at = |txid: &str| PROOF.txids.iter().position(|t| t.to_string() == txid);
        let tx = path.strip_prefix("/tx/");
        if let Some(view) = tx.and_then(|txid| PROOF.views.get(txid)) {
            return Some(Ok(view.clone()));
        }
        if let Some(txid) = tx.and_then(|rest| rest.strip_suffix("/hex")) {
            return Some(at(txid).map_or_else(not_found, |pos| Ok(serialize_hex(&PROOF.txs[pos]))));
        }
        if let Some(txid) = tx.and_then(|rest| rest.strip_suffix("/merkle-proof")) {
            return Some(at(txid).map_or_else(not_found, |pos| {
                let hashes: Vec<String> = merkle(&PROOF.txids, pos)
                    .1
                    .iter()
                    .map(|hash| format!("\"{hash}\""))
                    .collect();
                Ok(format!(
                    r#"{{"block_height":{PROOF_HEIGHT},"merkle":[{}],"pos":{pos}}}"#,
                    hashes.join(",")
                ))
            }));
        }
        let hash = path.strip_prefix("/block/")?.strip_suffix("/header")?;
        Some(if hash == PROOF.header.block_hash().to_string() {
            Ok(serialize_hex(&PROOF.header))
        } else {
            not_found()
        })
    }

    #[test]
    #[ignore = "mines MINED_NONCE, minutes of CPU: cargo test --release -p mobile-signer-ffi mine_the_proof_block -- --ignored --nocapture"]
    fn mine_the_proof_block() {
        use std::sync::atomic::{AtomicBool, Ordering};
        let found = AtomicBool::new(false);
        let start = proof_header(PROOF.header.merkle_root, MINED_TIME, 0);
        let target = start.target();
        std::thread::scope(|scope| {
            for thread in 0..8 {
                let found = &found;
                scope.spawn(move || {
                    let mut header = start;
                    header.time += thread;
                    for nonce in 0..=u32::MAX {
                        if nonce % (1 << 20) == 0 && found.load(Ordering::Relaxed) {
                            return;
                        }
                        header.nonce = nonce;
                        if target.is_met_by(header.block_hash()) {
                            found.store(true, Ordering::Relaxed);
                            println!("MINED_TIME = {}, MINED_NONCE = {nonce:#010x}", header.time);
                            return;
                        }
                    }
                });
            }
        });
        assert!(found.load(Ordering::Relaxed), "no nonce at these 8 times");
    }

    /// What a lying host serves to back a claim, which is the history page that lists a payment, the
    /// raw transaction, its merkle branch and the block header.
    #[derive(Clone)]
    struct Forgery {
        page: String,
        raw: String,
        branch: String,
        header: String,
    }

    /// A made-up payment of `PAID_SATS` to `to`, unique by `lock_time`, that no block holds.
    fn made_up(to: &Address, lock_time: u32) -> Transaction {
        Transaction {
            version: bitcoin::transaction::Version::TWO,
            lock_time: LockTime::from_consensus(lock_time),
            input: vec![TxIn {
                previous_output: OutPoint {
                    txid: Txid::from_byte_array([0x11; 32]),
                    vout: 0,
                },
                ..TxIn::default()
            }],
            output: vec![TxOut {
                value: Amount::from_sat(PAID_SATS),
                script_pubkey: to.script_pubkey(),
            }],
        }
    }

    /// `tx` listed as paying `to`, alone in a block whose header has `bits` and no work done.
    fn forged(tx: &Transaction, to: &Address, bits: u32) -> Forgery {
        let txid = tx.compute_txid();
        let root = bitcoin::TxMerkleNode::from_byte_array(txid.to_byte_array());
        let mut header = proof_header(root, MINED_TIME, 0);
        header.bits = bitcoin::pow::CompactTarget::from_consensus(bits);
        let payment = tx_of(
            &txid.to_string(),
            &[spent(SOMEONE, 20_000)],
            &[txo(&to.to_string(), PAID_SATS)],
            &in_proof_block(&header, PROOF_HEIGHT),
        );
        Forgery {
            page: format!("[{payment}]"),
            raw: serialize_hex(tx),
            branch: format!(r#"{{"block_height":{PROOF_HEIGHT},"merkle":[],"pos":0}}"#),
            header: serialize_hex(&header),
        }
    }

    /// A host that calls every address used, with confirmed history and nothing to spend, and backs
    /// each claim with `forgery`. Past one window of summaries or one proof it fails the test, because
    /// that is the most a scan may ask a host that cannot prove its claims.
    fn liar(forgery: Forgery) -> impl Fn(&str) -> Result<String, MobileSignerError> + Sync {
        let asked = Mutex::new((0, 0));
        move |url: &str| {
            let path = url.strip_prefix(ESPLORA_TESTNET4).expect("a testnet4 URL");
            let mut asked = asked.lock().unwrap();
            if path.starts_with("/address/") && path.ends_with("/txs") {
                asked.1 += 1;
                assert!(asked.1 <= 1, "a liar was asked for a second proof");
                return Ok(forgery.page.clone());
            }
            if path.starts_with("/address/") {
                asked.0 += 1;
                assert!(asked.0 <= GAP, "a liar was asked past one window");
                return Ok(used_stats(0));
            }
            Ok(match path {
                "/blocks/tip/height" => TIP.into(),
                p if p.ends_with("/hex") => forgery.raw.clone(),
                p if p.ends_with("/merkle-proof") => forgery.branch.clone(),
                p if p.ends_with("/header") => forgery.header.clone(),
                other => panic!("a liar was asked {other}"),
            })
        }
    }

    fn snapshot_on(transport: &dyn Transport) -> Result<HotWalletSnapshot, MobileSignerError> {
        snapshot_from(
            &EsploraSource(transport),
            &abandon_about(),
            "",
            KeyNetwork::Test,
            AddressType::NativeSegwit,
            &SystemTime::now,
            &ScanStore::default(),
        )
    }

    /// A snapshot on `transport` answers that a host lied, and the history after it, which shares its sync,
    /// refuses naming the lie.
    #[track_caller]
    fn assert_unverified(transport: &dyn Transport, why: &str) {
        let (source, words, store) = (
            EsploraSource(transport),
            abandon_about(),
            ScanStore::default(),
        );
        let snapshot = snapshot_from(
            &source,
            &words,
            "",
            KeyNetwork::Test,
            AddressType::NativeSegwit,
            &SystemTime::now,
            &store,
        );
        assert!(
            matches!(&snapshot, Ok(s) if s.scan == HotScan::Unverified),
            "{why}: {snapshot:?}"
        );
        let lead = format!("esplora scan: receive index {}: ", GAP - 1);
        match history_of(
            &source,
            &words,
            "",
            KeyNetwork::Test,
            AddressType::NativeSegwit,
            &SystemTime::now,
            &store,
        ) {
            Err(MobileSignerError::InvalidPsbtJson(m))
                if m.starts_with(&lead) && m.contains(why) => {}
            other => panic!("{why}: expected {lead}...{why}..., got {other:?}"),
        }
    }

    // Each lie `prove_use` catches on testnet4. The mainnet period checks are not reachable here, because
    // a scan anchors on its own network and this build refuses mainnet before any read.
    #[test]
    fn a_host_that_claims_every_address_used_is_unverified_after_one_window_and_one_proof_whatever_it_forged()
     {
        let to = own(Chain::External, GAP - 1).address;
        let someone = Address::from_str(SOMEONE).unwrap().assume_checked();
        let base = || forged(&made_up(&to, 0), &to, 0x1d00ffff);
        let header: bitcoin::block::Header =
            bitcoin::consensus::encode::deserialize_hex(&base().header).unwrap();
        let mut another_block = header;
        another_block.nonce = 1;
        // One input with no scriptSig and one output to the 4-byte P2A script make a merkle node's size.
        let t64 = Transaction {
            version: bitcoin::transaction::Version::TWO,
            lock_time: LockTime::ZERO,
            input: vec![TxIn {
                sequence: Sequence::MAX,
                ..TxIn::default()
            }],
            output: vec![TxOut {
                value: Amount::from_sat(240),
                script_pubkey: ScriptBuf::new_p2a(),
            }],
        };
        assert_eq!(t64.base_size(), 64);
        // A payment whose txid reads as a transaction's first half, one input coming, and the second
        // half of that transaction, so that the two hashed together pass for a branch (CVE-2017-12842).
        let first_half = (1..)
            .map(|lock_time| made_up(&to, lock_time))
            .find(|tx| tx.compute_txid().to_byte_array()[4] == 1)
            .unwrap();
        let mut second_half = vec![0; 5];
        second_half.extend(0u32.to_le_bytes());
        second_half.push(0);
        second_half.extend(u32::MAX.to_le_bytes());
        second_half.push(1);
        second_half.extend(240u64.to_le_bytes());
        second_half.push(4);
        second_half.extend(ScriptBuf::new_p2a().as_bytes());
        second_half.extend(0u32.to_le_bytes());
        let second_half = bitcoin::TxMerkleNode::from_byte_array(second_half.try_into().unwrap());
        let branch = |hashes: &str, pos: u32| {
            format!(r#"{{"block_height":{PROOF_HEIGHT},"merkle":[{hashes}],"pos":{pos}}}"#)
        };
        let txid = made_up(&to, 0).compute_txid().to_string();
        let kinds = [
            (
                "the raw answer is not a transaction",
                Forgery {
                    raw: "zz".into(),
                    ..base()
                },
            ),
            (
                "the raw transaction",
                Forgery {
                    raw: serialize_hex(&made_up(&to, 1)),
                    ..base()
                },
            ),
            ("64 bytes", forged(&t64, &to, 0x1d00ffff)),
            (
                "does not pay it",
                forged(&made_up(&someone, 0), &to, 0x1d00ffff),
            ),
            (
                "merkle proof is not JSON",
                Forgery {
                    branch: "<html>".into(),
                    ..base()
                },
            ),
            (
                "does not reach the merkle root",
                Forgery {
                    branch: branch(&format!("\"{}\"", "44".repeat(32)), 0),
                    ..base()
                },
            ),
            (
                "does not fit a branch",
                Forgery {
                    branch: branch("", 1),
                    ..base()
                },
            ),
            (
                "reads as a transaction",
                Forgery {
                    branch: branch(&format!("\"{second_half}\""), 0),
                    ..forged(&first_half, &to, 0x1d00ffff)
                },
            ),
            (
                "the header of block",
                Forgery {
                    header: serialize_hex(&another_block),
                    ..base()
                },
            ),
            ("does not meet its own target", base()),
            (
                "easier than the network allows",
                forged(&made_up(&to, 0), &to, 0x207fffff),
            ),
            (
                "the header answer is not a header",
                Forgery {
                    header: "zz".into(),
                    ..base()
                },
            ),
            (
                "which is no txid",
                Forgery {
                    page: base().page.replace(&txid, "zz"),
                    ..base()
                },
            ),
            (
                "confirmed in no block the source names",
                Forgery {
                    page: base()
                        .page
                        .replace(&format!(r#","block_hash":"{}""#, header.block_hash()), ""),
                    ..base()
                },
            ),
        ];
        for (why, forgery) in kinds {
            assert_unverified(&Reads(&liar(forgery)), why);
        }
    }

    /// A host's answer to each GET, as a test scripts it.
    type Host<'a> = &'a (dyn Fn(&str) -> Result<String, MobileSignerError> + Sync);

    /// Hosts in the order a scan tries them, each answering with its own function, the way `Http`
    /// leaves a host it caught lying for the next.
    struct Hosts<'a> {
        hosts: Vec<Host<'a>>,
        at: Mutex<usize>,
    }

    impl Transport for Hosts<'_> {
        fn get(&self, url: &str) -> Result<String, MobileSignerError> {
            let at = *self.at.lock().unwrap();
            (self.hosts[at])(url)
        }

        fn broadcast(&self, _: &str, _: &str) -> Result<String, MobileSignerError> {
            unreachable!("a scan posts nothing")
        }

        fn skip_host(&self) -> bool {
            let mut at = self.at.lock().unwrap();
            *at += 1;
            *at < self.hosts.len()
        }
    }

    #[test]
    fn a_host_caught_lying_is_left_for_the_next_which_asks_again_from_0_and_completes() {
        let to = own(Chain::External, GAP - 1).address;
        let lying = liar(forged(&made_up(&to, 0), &to, 0x1d00ffff));
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 3), 7_000, '3');
        let honest = |url: &str| fake.get(url);
        let hosts = Hosts {
            hosts: vec![&lying, &honest],
            at: Mutex::new(0),
        };
        let snapshot = snapshot_on(&hosts).unwrap();
        assert_eq!(
            receive_and_balance(snapshot),
            (addr(Chain::External, 4), 7_000),
            "the honest host's picture, none of the liar's claims"
        );
        assert_eq!(
            fake.requests(),
            24 + PROOF_READS + GAP as usize + 1,
            "the next host is asked both chains from index 0"
        );
    }

    #[test]
    fn two_lying_hosts_each_get_one_window_and_one_proof_then_the_scan_is_unverified() {
        let to = own(Chain::External, GAP - 1).address;
        let first = liar(forged(&made_up(&to, 0), &to, 0x1d00ffff));
        let second = liar(Forgery {
            raw: "zz".into(),
            ..forged(&made_up(&to, 0), &to, 0x1d00ffff)
        });
        let hosts = Hosts {
            hosts: vec![&first, &second],
            at: Mutex::new(0),
        };
        assert_unverified(&hosts, "the raw answer is not a transaction");
    }

    // The real recording `proof.rs` tests against. Receive 0 of the 24-word phrase the phone imported was
    // paid by c5ed075e... in testnet4 block 156,696, read from blockstream.info on 5 Nov 2026.
    #[test]
    fn a_real_testnet4_payment_proves_receive_0_of_the_24_word_phrase_and_the_scan_completes() {
        let mut words = vec!["abandon".to_string(); 23];
        words.push("art".into());
        let account =
            Account::new(&words, "", Network::Testnet4, AddressType::NativeSegwit).unwrap();
        let receive = |index| {
            account
                .key(Chain::External, index)
                .unwrap()
                .address
                .to_string()
        };
        let paid = receive(0);
        let (txid, block) = (
            "c5ed075ef44b807f51123cf0c6e53c75385a17a510f995b560bf73b1102d5474",
            "000000000000000123d492d5719746cbd6f43ea40aff45cb45ed5d8a6cff003d",
        );
        let calls = Mutex::new(Vec::new());
        let answer = |url: &str| {
            calls.lock().unwrap().push(url.to_string());
            let path = url.strip_prefix(ESPLORA_TESTNET4).unwrap();
            Ok(if path == format!("/address/{paid}") {
                used_stats(0)
            } else if path == format!("/address/{paid}/txs") {
                include_str!("../fixtures/proof/testnet4_address_txs_real.json").into()
            } else if path == format!("/tx/{txid}/hex") {
                include_str!("../fixtures/proof/testnet4_tx_hex_real.txt").into()
            } else if path == format!("/tx/{txid}/merkle-proof") {
                include_str!("../fixtures/proof/testnet4_merkle_proof_real.json").into()
            } else if path == format!("/block/{block}/header") {
                include_str!("../fixtures/proof/testnet4_header_156696_real.txt").into()
            } else if path == "/blocks/tip/height" {
                "974650".into()
            } else if path.starts_with("/address/") && !path[9..].contains('/') {
                UNUSED.into()
            } else {
                panic!("unexpected {path}")
            })
        };
        let snapshot = snapshot_from(
            &EsploraSource(&Reads(&answer)),
            &words,
            "",
            KeyNetwork::Test,
            AddressType::NativeSegwit,
            &SystemTime::now,
            &ScanStore::default(),
        )
        .unwrap();
        assert_eq!(snapshot.receive_address, receive(1));
        // The receive window, the proof, the one index it opened, and the change window.
        assert_eq!(calls.lock().unwrap().len(), 20 + PROOF_READS + 1 + 20);
    }

    // No partial balance, the user's decision 2. A pass that has not finished finding the wallet answers
    // the snapshot with how far it got, so the app keeps the dash and says why, with nothing to spend, and
    // refuses the history and a payment in text the app maps.
    #[test]
    fn a_pass_that_does_not_complete_answers_the_snapshot_with_its_status_and_refuses_the_history_and_the_send()
     {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 3), 7_000, '3');
        let throttled = |url: &str| match url.ends_with("/hex") {
            true => Err(MobileSignerError::InvalidPsbtJson(
                "esplora get: HTTP status 429, gave up after 3 attempts".into(),
            )),
            false => fake.get(url),
        };
        let mut empty = Fake::new();
        empty.fund(&addr(Chain::External, 3), 7_000, '3');
        empty.txs.insert(addr(Chain::External, 3), "[]".into());
        let forgery = forged(
            &made_up(&own(Chain::External, 3).address, 0),
            &own(Chain::External, 3).address,
            0x1d00ffff,
        );
        let lying = |url: &str| match url.strip_prefix(ESPLORA_TESTNET4).unwrap() {
            p if p.ends_with("/txs") => Ok(forgery.page.clone()),
            p if p.ends_with("/hex") => Ok(forgery.raw.clone()),
            p if p.ends_with("/merkle-proof") => Ok(forgery.branch.clone()),
            p if p.ends_with("/header") => Ok(forgery.header.clone()),
            _ => fake.get(url),
        };
        let empty_get = |url: &str| empty.get(url);
        let cases: [(Host, HotScan, &str); 3] = [
            (
                &throttled,
                HotScan::Continuing,
                "esplora get: receive index 3 could not be proven: esplora get: HTTP status 429, gave up after 3 attempts",
            ),
            (
                &empty_get,
                HotScan::Unverified,
                "esplora scan: receive index 3: ",
            ),
            (
                &lying,
                HotScan::Unverified,
                "esplora scan: receive index 3: ",
            ),
        ];
        for (host, scan, text) in cases {
            let source = EsploraSource(&Reads(host));
            let store = ScanStore::default();
            let (words, now) = (abandon_about(), &|| fake.now());
            let snapshot = snapshot_from(
                &source,
                &words,
                "",
                KeyNetwork::Test,
                AddressType::NativeSegwit,
                now,
                &store,
            )
            .unwrap();
            assert_eq!(
                (snapshot.scan, snapshot.spendable_sats),
                (scan, 0),
                "{text}"
            );
            let calls = [
                history_of(
                    &source,
                    &words,
                    "",
                    KeyNetwork::Test,
                    AddressType::NativeSegwit,
                    now,
                    &store,
                )
                .map(|_| ()),
                signed_payment(
                    &source,
                    &words,
                    "",
                    KeyNetwork::Test,
                    AddressType::NativeSegwit,
                    SOMEONE,
                    1_000,
                    1,
                    now,
                    &store,
                )
                .map(|_| ()),
                signed_send_all(
                    &source,
                    &words,
                    "",
                    KeyNetwork::Test,
                    AddressType::NativeSegwit,
                    SOMEONE,
                    1,
                    now,
                    &store,
                )
                .map(|_| ()),
            ];
            for result in calls {
                match result {
                    Err(MobileSignerError::InvalidPsbtJson(m)) => {
                        assert!(m.starts_with(text), "{text}: {m}")
                    }
                    other => panic!("{text}: expected the call to fail, got {other:?}"),
                }
            }
        }
    }

    /// Used by a payment and a spend both still in the mempool, a claim no proof can back yet.
    const PENDING: &str = r#"{"chain_stats":{"funded_txo_sum":0,"spent_txo_sum":0,"tx_count":0},"mempool_stats":{"funded_txo_sum":1000,"spent_txo_sum":1000,"tx_count":2}}"#;

    // Every address of both chains claimed in the mempool, and receive 5 paid. The window past each
    // frontier is full, so each chain hands out the window's last index, which a restore reaches.
    #[test]
    fn claims_on_every_address_keep_the_receive_and_change_addresses_inside_the_window() {
        let mut fake = Fake::new();
        for i in 0..3 * GAP {
            fake.stats.insert(addr(Chain::External, i), PENDING.into());
            fake.stats.insert(addr(Chain::Internal, i), PENDING.into());
        }
        fake.fund(&addr(Chain::External, 5), 9_000, '3');
        let frontier = 5 + 1;
        assert_eq!(
            fake.snapshot("").unwrap().receive_address,
            addr(Chain::External, frontier + GAP - 1)
        );
        let tx = fake.send(SOMEONE, 1_000, 1).unwrap();
        assert_eq!(pays(&tx.output[1]), addr(Chain::Internal, GAP - 1));
    }

    // The issuing rule as a property of the whole wallet. Whatever a Fake claims, every address a
    // completed scan hands out is one a plain gap-GAP restore over the really paid indices reaches.
    #[test]
    fn whatever_a_fake_claims_a_plain_restore_reaches_the_receive_and_change_addresses_it_hands_out()
     {
        let account = Account::new(
            &abandon_about(),
            "",
            Network::Testnet4,
            AddressType::NativeSegwit,
        )
        .unwrap();
        let span = 3 * GAP;
        let index_of = |chain, address: &str| {
            (0..2 * span)
                .find(|&i| account.key(chain, i).unwrap().address.to_string() == address)
                .unwrap()
        };
        let reach = |paid: &[u32]| {
            let (mut reach, mut i) = (GAP, 0);
            while i < reach {
                if paid.contains(&i) {
                    reach = i + 1 + GAP;
                }
                i += 1;
            }
            reach
        };
        let mut state = 0x9e37_79b9_7f4a_7c15_u64;
        let mut chance = |percent: u64| {
            state ^= state << 13;
            state ^= state >> 7;
            state ^= state << 17;
            state % 100 < percent
        };
        for run in 0..24 {
            let mut fake = Fake::new();
            let mut paid = [Vec::new(), Vec::new()];
            for chain in [Chain::External, Chain::Internal] {
                for i in 0..span {
                    let address = account.key(chain, i).unwrap().address.to_string();
                    if chance(12) {
                        let vout = chain as u32 * span + i;
                        fake.stats.insert(address.clone(), used_stats(2_000));
                        fake.utxos
                            .insert(address, format!("[{}]", coin('4', vout, 2_000, true)));
                        paid[chain as usize].push(i);
                    } else if chance(20) {
                        fake.stats.insert(address, PENDING.into());
                    }
                }
            }
            let receive = fake.snapshot("").unwrap().receive_address;
            let receive = index_of(Chain::External, &receive);
            assert!(
                receive < reach(&paid[0]),
                "run {run}: receive {receive}, paid {:?}",
                paid[0]
            );
            if let Ok(tx) = fake.send(SOMEONE, 1_000, 1) {
                let change = index_of(Chain::Internal, &pays(&tx.output[1]));
                assert!(
                    change < reach(&paid[1]),
                    "run {run}: change {change}, paid {:?}",
                    paid[1]
                );
            }
        }
    }

    // The 5 Nov 2026 interrogate of the scanning rebuild proved six root causes, R1 to R6, each with a reviewer's
    // repro. Each test below is that repro, asserting what the wallet must do instead.

    /// The flags a scan file keeps for an index: used, and used with confirmed history.
    const CLAIMED_USED: u64 = 1;
    const CLAIMED_PAID: u64 = 3;

    /// The one scan file in `folder`, with its JSON.
    fn scan_file(folder: &Folder) -> (PathBuf, Value) {
        let [file] = folder.files().try_into().unwrap();
        let path = folder.0.join(file);
        let json = serde_json::from_str(&fs::read_to_string(&path).unwrap()).unwrap();
        (path, json)
    }

    /// The scan file in `folder` edited the way someone with write access to the app's folder could, so that it
    /// still parses: the receive chain's indices carry `flags`, and its proven index is `frontier - 1`, which no source
    /// shows paid.
    fn push_receive_frontier(folder: &Folder, frontier: u32, flags: &[u64]) {
        let (path, mut json) = scan_file(folder);
        json["chains"][0] = serde_json::json!({"proof": frontier - 1, "seen": flags});
        fs::write(&path, json.to_string()).unwrap();
        let read = ScanStore::default();
        read.use_dir(folder.0.clone());
        let id = Account::new(
            &abandon_about(),
            "",
            Network::Testnet4,
            AddressType::NativeSegwit,
        )
        .unwrap()
        .id();
        assert_eq!(
            read.wallet(&id).lock().unwrap().scan.chains[0].proof,
            Some(Proof::Stored(frontier - 1)),
            "the edited file still parses"
        );
    }

    // R1 (sonnet 1 and 2, haiku B, opus F3a). A restart trusted a file's frontier: Complete with a receive address
    // no gap-20 restore reaches and a wrong balance, or 20,000 requests and then Unverified.
    #[test]
    fn a_scan_file_whose_frontier_was_pushed_is_proven_again_and_the_wallet_found_as_it_is() {
        let cases = [
            (
                "5,000 used then 20 unused",
                three_used(),
                5_000,
                [vec![CLAIMED_USED; 5_000], vec![0; GAP as usize]].concat(),
                3,
                4_000,
            ),
            (
                "index 39 claimed paid",
                three_used(),
                40,
                (0..60)
                    .map(|i| if i == 39 { CLAIMED_PAID } else { 0 })
                    .collect(),
                3,
                4_000,
            ),
            (
                "20,000 with nothing asked",
                three_used(),
                20_000,
                Vec::new(),
                3,
                4_000,
            ),
            (
                "index 99 claimed paid on a wallet nobody paid",
                Fake::new(),
                100,
                (0..120)
                    .map(|i| if i == 99 { CLAIMED_PAID } else { 0 })
                    .collect(),
                0,
                0,
            ),
        ];
        for (case, fake, frontier, flags, receive, balance) in cases {
            let folder = Folder::new("pushed");
            let store = ScanStore::default();
            store.use_dir(folder.0.clone());
            fake.snapshot_in(&store).unwrap();
            push_receive_frontier(&folder, frontier, &flags);
            let restarted = ScanStore::default();
            restarted.use_dir(folder.0.clone());
            let before = fake.requests();
            let found = fake.snapshot_in(&restarted).unwrap();
            assert_eq!(
                (found.receive_address, found.balance_sats, found.scan),
                (addr(Chain::External, receive), balance, HotScan::Complete),
                "{case}"
            );
            let asked = fake.requests() - before;
            assert!(asked < 100, "{case}: {asked} requests");
            fake.tick(PUBLIC_FULL_PASS);
            let later = fake.snapshot_in(&restarted).unwrap();
            assert_eq!(
                (later.receive_address, later.scan),
                (addr(Chain::External, receive), HotScan::Complete),
                "{case}: the next full pass"
            );
        }
    }

    // R1 (opus F3b). The largest frontier a file could hold, 2^31 - 20 with nothing asked, made the detail derive every
    // key below it and a throttled snapshot every key up to it, about 409 hours of work.
    #[test]
    fn a_scan_file_with_the_largest_frontier_hangs_neither_the_detail_nor_a_throttled_snapshot() {
        let fake = Fake::new();
        let folder = Folder::new("largest");
        let store = ScanStore::default();
        store.use_dir(folder.0.clone());
        fake.snapshot_in(&store).unwrap();
        push_receive_frontier(&folder, (1 << 31) - GAP, &[]);
        let (done, answers) = std::sync::mpsc::channel();
        for call in ["detail", "snapshot"] {
            let (done, dir) = (done.clone(), folder.0.clone());
            std::thread::spawn(move || {
                let fake = Fake::new();
                let store = ScanStore::default();
                store.use_dir(dir);
                let throttled = |url: &str| match url.contains("/address/") {
                    true => Err(MobileSignerError::InvalidPsbtJson(
                        "esplora get: HTTP status 429, gave up after 3 attempts".into(),
                    )),
                    false => fake.get(url),
                };
                let source = EsploraSource(&Reads(&throttled));
                let words = abandon_about();
                let answered = match call {
                    "detail" => {
                        let _ = detail_of(
                            &source,
                            &words,
                            "",
                            KeyNetwork::Test,
                            AddressType::NativeSegwit,
                            &id('1'),
                            &store,
                        );
                        true
                    }
                    _ => snapshot_from(
                        &source,
                        &words,
                        "",
                        KeyNetwork::Test,
                        AddressType::NativeSegwit,
                        &|| fake.now(),
                        &store,
                    )
                    .is_ok_and(|snapshot| snapshot.scan == HotScan::Continuing),
                };
                let _ = done.send((call, answered));
            });
        }
        drop(done);
        let deadline = Instant::now() + Duration::from_secs(10);
        let mut answered = Vec::new();
        while let Ok(answer) =
            answers.recv_timeout(deadline.saturating_duration_since(Instant::now()))
        {
            answered.push(answer);
        }
        answered.sort();
        assert_eq!(answered, [("detail", true), ("snapshot", true)]);
    }

    // R2 (opus F1, haiku A, sonnet 3). A host that counts confirmed history for an address and lists none on its first
    // page, or lists a payment and 404s it, kept every pass Continuing on it, and the next host was never asked.
    #[test]
    fn a_host_that_cannot_show_the_payment_it_counts_is_unverified_and_left_for_the_next() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 3), 7_000, '3');
        let to = own(Chain::External, GAP - 1).address;
        let forgery = forged(&made_up(&to, 0), &to, 0x1d00ffff);
        for empty_page in [true, false] {
            let liar = |url: &str| -> Result<String, MobileSignerError> {
                match url.strip_prefix(ESPLORA_TESTNET4).unwrap() {
                    "/blocks/tip/height" => Ok(TIP.into()),
                    p if p.ends_with("/txs") => Ok(match empty_page {
                        true => "[]".into(),
                        false => forgery.page.clone(),
                    }),
                    p if p.ends_with("/hex") => Err(MobileSignerError::InvalidPsbtJson(format!(
                        "esplora get: {url}: status code 404"
                    ))),
                    p if p.starts_with("/address/") => Ok(used_stats(0)),
                    other => panic!("the liar was asked {other}"),
                }
            };
            let alone = snapshot_on(&Reads(&liar)).unwrap();
            assert_eq!(alone.scan, HotScan::Unverified, "empty page {empty_page}");
            let honest = |url: &str| fake.get(url);
            let hosts = Hosts {
                hosts: vec![&liar, &honest],
                at: Mutex::new(0),
            };
            let snapshot = snapshot_on(&hosts).unwrap();
            assert_eq!(
                (
                    snapshot.scan,
                    snapshot.balance_sats,
                    *hosts.at.lock().unwrap()
                ),
                (HotScan::Complete, 7_000, 1),
                "empty page {empty_page}"
            );
        }
        let mut wedged = Fake::new();
        wedged.touch(&addr(Chain::External, 3));
        wedged.txs.insert(addr(Chain::External, 3), "[]".into());
        assert_eq!(wedged.snapshot("").unwrap().scan, HotScan::Unverified);
    }

    // R2 (opus F1b, haiku C). Receive 3 was paid 26 times and spent from in 25 later transactions, so its first history
    // page, the 25 newest confirmed, lists no payment to it. It was never proven and the wallet never completed. A
    // confirmed spend of it names the payment it spends, which is confirmed and paid it.
    #[test]
    fn an_address_whose_payment_is_off_its_first_page_is_proven_through_the_payment_a_spend_of_it_names()
     {
        let mut fake = Fake::new();
        let reused = addr(Chain::External, 3);
        fake.stats.insert(
            reused.clone(),
            r#"{"chain_stats":{"funded_txo_sum":260000,"spent_txo_sum":250000,"tx_count":51},"mempool_stats":{"funded_txo_sum":0,"spent_txo_sum":0,"tx_count":0}}"#.into(),
        );
        fake.utxos
            .insert(reused.clone(), format!("[{}]", coin('7', 0, 10_000, true)));
        // Each spends the proof block's payment to receive 3, the one payment to it this Fake can prove.
        let paid = PROOF.txids[3].to_string();
        let spends: Vec<String> = (0..25u32)
            .map(|n| {
                tx_of(
                    &format!("{:064x}", 0xabc0 + n),
                    &[spent_at(&paid, 0, &reused, 10_000)],
                    &[txo(SOMEONE, 9_000)],
                    &at(125 - n),
                )
            })
            .collect();
        fake.txs.insert(reused, format!("[{}]", spends.join(",")));
        let snapshot = fake.snapshot("").unwrap();
        assert_eq!(
            (
                snapshot.scan,
                snapshot.balance_sats,
                snapshot.receive_address
            ),
            (HotScan::Complete, 10_000, addr(Chain::External, 4))
        );
        assert!(fake.send(SOMEONE, 1_000, 1).is_ok());
    }

    // R3 (opus F2). A proven payment that a reorg undid made every honest host a liar on every sync, through restarts,
    // until the wallet was forgotten. A host whose own data still proves the payment while it calls the address unused
    // is a liar still.
    #[test]
    fn a_proven_payment_a_reorg_undid_is_dropped_and_the_wallet_found_again_while_a_host_its_own_data_contradicts_is_unverified()
     {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 3), 7_000, '3');
        assert_eq!(fake.snapshot("").unwrap().scan, HotScan::Complete);
        fake.stats.remove(&addr(Chain::External, 3));
        fake.tick(SHARE_TTL);
        assert_eq!(
            fake.snapshot("").unwrap().scan,
            HotScan::Unverified,
            "the host still proves the payment it calls unpaid"
        );

        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 3), 7_000, '3');
        let folder = Folder::new("reorg");
        let store = ScanStore::default();
        store.use_dir(folder.0.clone());
        assert_eq!(
            receive_and_balance(fake.snapshot_in(&store).unwrap()),
            (addr(Chain::External, 4), 7_000)
        );
        // A reorg undid the payment and a double spend took its coin, so no host knows it any more.
        fake.stats.remove(&addr(Chain::External, 3));
        fake.utxos.remove(&addr(Chain::External, 3));
        fake.txs.insert(addr(Chain::External, 3), "[]".into());
        fake.unknown.insert(PROOF.txids[3].to_string());
        fake.tick(SHARE_TTL);
        let restarted = ScanStore::default();
        restarted.use_dir(folder.0.clone());
        let before = fake.requests();
        let read_back = fake.snapshot_in(&restarted).unwrap();
        assert_eq!(
            (
                read_back.scan,
                read_back.balance_sats,
                read_back.receive_address
            ),
            (HotScan::Complete, 0, addr(Chain::External, 0)),
            "a restart whose file names the index the reorg left unpaid"
        );
        assert_eq!(
            fake.requests() - before,
            3 + GAP as usize + LOOKAHEAD,
            "its page, the tip and its summary, then the receive chain from 0 and the change lookahead"
        );
        let (a, b) = (|url: &str| fake.get(url), |url: &str| fake.get(url));
        let hosts = Hosts {
            hosts: vec![&a, &b],
            at: Mutex::new(0),
        };
        let after = snapshot_from(
            &EsploraSource(&hosts),
            &abandon_about(),
            "",
            KeyNetwork::Test,
            AddressType::NativeSegwit,
            &|| fake.now(),
            &store,
        )
        .unwrap();
        assert_eq!(
            (
                after.scan,
                after.balance_sats,
                after.receive_address,
                *hosts.at.lock().unwrap()
            ),
            (HotScan::Complete, 0, addr(Chain::External, 0), 0)
        );
        fake.tick(PUBLIC_FULL_PASS);
        let restarted = ScanStore::default();
        restarted.use_dir(folder.0.clone());
        let later = fake.snapshot_in(&restarted).unwrap();
        assert_eq!(
            (later.scan, later.receive_address),
            (HotScan::Complete, addr(Chain::External, 0)),
            "after a restart and a full pass"
        );
    }

    // R3 (opus F2b). A testnet4 host that backs its claims with a block it mined at minimum difficulty moved the frontier
    // to 60 in one pass, and the honest host's syncs kept handing out receive 60, which a gap-20 restore never reaches.
    // The forgery is the risk the user accepted for testnet4. It must end once an honest host answers.
    #[test]
    fn a_forged_testnet4_proof_leaves_no_address_past_a_restore_once_an_honest_host_answers() {
        let mut liar = Fake::new();
        for i in [19, 39, 59] {
            liar.stats.insert(addr(Chain::External, i), used_stats(0));
        }
        let mut honest = Fake::new();
        honest
            .unknown
            .extend(PROOF.txids.iter().map(|txid| txid.to_string()));
        let folder = Folder::new("forged");
        let store = ScanStore::default();
        store.use_dir(folder.0.clone());
        let sync = |fake: &Fake, store: &ScanStore| {
            snapshot_from(
                &EsploraSource(&Reads(&|url| fake.get(url))),
                &abandon_about(),
                "",
                KeyNetwork::Test,
                AddressType::NativeSegwit,
                &|| honest.now(),
                store,
            )
            .unwrap()
        };
        let forged = sync(&liar, &store);
        assert_eq!(
            (forged.scan, forged.receive_address),
            (HotScan::Complete, addr(Chain::External, 60)),
            "the accepted testnet4 risk"
        );
        honest.tick(SHARE_TTL);
        let restarted = ScanStore::default();
        restarted.use_dir(folder.0.clone());
        let read_back = sync(&honest, &restarted);
        assert_eq!(
            (read_back.scan, read_back.receive_address),
            (HotScan::Complete, addr(Chain::External, 0)),
            "a restart whose file names the forged index, on an honest host"
        );
        assert_eq!(
            honest.requests(),
            3 + GAP as usize + LOOKAHEAD,
            "its page, the tip and its summary, then the receive chain from 0 and the change lookahead"
        );
        let next = sync(&honest, &store);
        assert_eq!(
            (next.scan, next.receive_address),
            (HotScan::Complete, addr(Chain::External, 0)),
            "the next sync, on an honest host"
        );
    }

    // R4 (opus F4). The period of full passes counted from when one ended, so a full pass cut short by a 429 and finished
    // hours later left a payment to an index it had already asked unseen for longer than a period.
    #[test]
    fn the_period_of_full_passes_counts_from_when_one_started() {
        use std::sync::atomic::{AtomicBool, Ordering::SeqCst};
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 0), 1_000, '1');
        fake.touch(&addr(Chain::External, 1));
        fake.fund(&addr(Chain::External, 2), 1_000, '2');
        let throttle = AtomicBool::new(false);
        let late: Vec<String> = (10..40)
            .map(|i| format!("/address/{}", addr(Chain::External, i)))
            .collect();
        let sync = |fake: &Fake| {
            let source = |url: &str| {
                if throttle.load(SeqCst) && late.iter().any(|path| url.ends_with(path.as_str())) {
                    return Err(MobileSignerError::InvalidPsbtJson(
                        "esplora get: HTTP status 429, gave up after 3 attempts".into(),
                    ));
                }
                fake.get(url)
            };
            snapshot_from(
                &EsploraSource(&Reads(&source)),
                &abandon_about(),
                "",
                KeyNetwork::Test,
                AddressType::NativeSegwit,
                &|| fake.now(),
                &fake.store,
            )
            .unwrap()
        };
        assert_eq!(sync(&fake).balance_sats, 2_000);
        fake.tick(PUBLIC_FULL_PASS);
        throttle.store(true, SeqCst);
        let pass_started = fake.now();
        assert_eq!(
            sync(&fake).scan,
            HotScan::Continuing,
            "a full pass, cut short at index 10"
        );
        fake.tick(Duration::from_secs(60));
        fake.fund(&addr(Chain::External, 1), 50_000, '9');
        fake.tick(Duration::from_secs(3 * 60 * 60));
        throttle.store(false, SeqCst);
        let ended = sync(&fake);
        assert_eq!(
            (ended.scan, ended.balance_sats),
            (HotScan::Complete, 2_000),
            "the pass ends, having asked receive 1 before it was paid"
        );
        let due = (pass_started + PUBLIC_FULL_PASS)
            .duration_since(fake.now())
            .unwrap();
        fake.tick(due + Duration::from_secs(1));
        assert_eq!(
            sync(&fake).balance_sats,
            52_000,
            "the next full pass, one period after the last one started"
        );
    }

    // R5 (opus F5). The file kept for each index 8 bytes of an unsalted SHA-256 of the address's Esplora counts and sums,
    // and trying amounts gave back 1,234,567 sats in 2.2 s. Nothing read it.
    #[test]
    fn the_scan_file_keeps_no_number_an_amount_comes_back_from() {
        let mut fake = Fake::new();
        let secret = 1_234_567u64;
        fake.fund(&addr(Chain::External, 0), secret, '1');
        let folder = Folder::new("amount");
        let store = ScanStore::default();
        store.use_dir(folder.0.clone());
        fake.snapshot_in(&store).unwrap();
        let (_, json) = scan_file(&folder);
        let hash = sha256::Hash::hash(format!("1,{secret},0,0,0,0").as_bytes()).to_byte_array();
        let token = u64::from_le_bytes(hash[..8].try_into().unwrap());
        let text = json.to_string();
        assert!(!text.contains(&token.to_string()), "{text}");
        assert!(!text.contains(&secret.to_string()), "{text}");
        for chain in json["chains"].as_array().unwrap() {
            for seen in chain["seen"].as_array().unwrap() {
                assert!(
                    seen.as_u64().is_some_and(|flags| flags < 16),
                    "an index keeps its flags and nothing else: {seen}"
                );
            }
        }
    }

    // The user approved the scan file on the condition that it holds no key, address or amount. A txid or a block
    // hash breaks that condition too, because any explorer turns either into the address, the amount and the
    // wallet's history.
    #[test]
    fn the_scan_file_names_no_payment_address_or_amount_the_source_served() {
        let fake = three_used();
        let folder = Folder::new("private");
        let store = ScanStore::default();
        store.use_dir(folder.0.clone());
        fake.snapshot_in(&store).unwrap();
        let asked = fake.calls.lock().unwrap().clone();
        let block = PROOF.header.block_hash().to_string();
        assert!(
            asked.iter().any(|url| url.contains(&block)),
            "a payment was proven"
        );
        let (path, json) = scan_file(&folder);
        let text = fs::read_to_string(path).unwrap();
        for part in asked.iter().flat_map(|url| url.split('/')) {
            if part.parse::<Txid>().is_ok() || part.parse::<Address<_>>().is_ok() {
                assert!(!text.contains(part), "the file names {part}: {text}");
            }
        }
        fn numbers(value: &Value) -> Vec<u64> {
            match value {
                Value::Array(items) => items.iter().flat_map(numbers).collect(),
                Value::Object(fields) => fields.values().flat_map(numbers).collect(),
                other => other.as_u64().into_iter().collect(),
            }
        }
        for amount in [1_000, PAID_SATS] {
            assert!(
                !numbers(&json).contains(&amount),
                "the file holds {amount}: {text}"
            );
        }
    }

    // R6 (opus F6). The routine part of a sync asked every funded index of a chain in one batch that failed whole on a
    // 429, so a wallet with more funded addresses than a host serves between two syncs never completed: 300 requests an
    // hour against 320 funded stayed short for 22 hours. The scan is asked through `synced`, because a snapshot also asks
    // one coin list per funded address.
    #[test]
    fn a_wallet_with_more_funded_addresses_than_a_host_serves_an_hour_completes_its_scan() {
        let mut fake = Fake::new();
        for i in 0..320 {
            fake.fund(&addr(Chain::External, i), 10_000, '5');
        }
        let quota = 300;
        let spent = Mutex::new((u64::MAX, 0));
        let limited = |url: &str| {
            let hour = unix_seconds(fake.now()) / 3600;
            let mut spent = spent.lock().unwrap();
            if spent.0 != hour {
                *spent = (hour, 0);
            }
            if spent.1 >= quota {
                return Err(MobileSignerError::InvalidPsbtJson(
                    "esplora get: HTTP status 429, gave up after 3 attempts".into(),
                ));
            }
            spent.1 += 1;
            fake.get(url)
        };
        let mut completed = Vec::new();
        for hour in 0..24 {
            let wallet = synced(
                &EsploraSource(&Reads(&limited)),
                &abandon_about(),
                "",
                KeyNetwork::Test,
                AddressType::NativeSegwit,
                &|| fake.now(),
                &fake.store,
            )
            .unwrap();
            if wallet.status == ScanStatus::Complete {
                assert_eq!(wallet.balance().unwrap(), 3_200_000, "hour {hour}");
                completed.push(hour);
            }
            fake.tick(Duration::from_secs(3600));
        }
        assert!(
            completed.first().is_some_and(|&first| first <= 2)
                && completed.last().is_some_and(|&last| last >= 21)
                && completed.windows(2).all(|pair| pair[1] - pair[0] <= 2),
            "complete at hours {completed:?}"
        );
    }

    // R7, a later reviewer's repro. The index a chain hands out stood on its highest proven index, while a restore
    // reaches an index only through used indices from 0 with fewer than `GAP` unused between any two. When a lower one
    // of them came undone, by a reorg or because a forged proof was never shown again, the wallet handed out an address
    // no restore asks, and a coin paid to it was lost to every restore, mebit's after a reinstall too.

    /// The last index a plain gap-`GAP` restore asks when `used` are the indices the real chain shows used.
    fn last_asked_by_a_restore(used: &[u32]) -> u32 {
        let (mut stop, mut i) = (GAP, 0);
        while i < stop {
            if used.contains(&i) {
                stop = i + 1 + GAP;
            }
            i += 1;
        }
        stop - 1
    }

    fn receive_index(address: &str) -> u32 {
        let account = Account::new(
            &abandon_about(),
            "",
            Network::Testnet4,
            AddressType::NativeSegwit,
        )
        .unwrap();
        (0..PAID[0])
            .find(|&i| account.key(Chain::External, i).unwrap().address.to_string() == address)
            .unwrap()
    }

    /// A reorg undid the payment to receive `index` and a double spend took its coin, so no host knows it any more.
    fn undo_payment(fake: &mut Fake, index: u32) {
        let address = addr(Chain::External, index);
        fake.stats.remove(&address);
        fake.utxos.remove(&address);
        fake.txs.insert(address, "[]".into());
        fake.unknown.insert(PROOF.txids[index as usize].to_string());
    }

    // R7. Receive 5 and receive 24 are paid and proven, so the frontier is 25. A reorg undoes the payment to receive 5,
    // so the real chain shows receive 24 alone and a restore asks receive 0 to 19 and stops.
    #[test]
    fn a_reorg_that_undoes_a_lower_proven_payment_leaves_no_receive_address_past_a_restore() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 5), 7_000, '5');
        fake.fund(&addr(Chain::External, 24), 9_000, '9');
        let before = fake.snapshot("").unwrap();
        assert_eq!(
            (
                before.scan,
                before.balance_sats,
                receive_index(&before.receive_address)
            ),
            (HotScan::Complete, 16_000, 25)
        );
        undo_payment(&mut fake, 5);
        fake.tick(PUBLIC_FULL_PASS);
        let after = fake.snapshot("").unwrap();
        assert_eq!((after.scan, after.balance_sats), (HotScan::Complete, 9_000));
        let (receive, reach) = (
            receive_index(&after.receive_address),
            last_asked_by_a_restore(&[24]),
        );
        assert!(
            receive <= reach,
            "the wallet hands out receive {receive}, and a restore over the real chain stops at {reach}"
        );
    }

    // R7, through the testnet4 forgery the user accepted. A liar forges receive 19 and lists a real payment to receive
    // 20, the address the forgery made the wallet hand out. Then only honest hosts answer, which know the payment to
    // receive 20 and not the forged one, so the proof of 20 holds and the forged 19 under it is never shown again.
    #[test]
    fn a_forged_proof_under_a_real_one_leaves_no_receive_address_past_a_restore_once_an_honest_host_answers()
     {
        let mut liar = Fake::new();
        liar.stats.insert(addr(Chain::External, 19), used_stats(0));
        liar.fund(&addr(Chain::External, 20), 9_000, '9');
        let mut honest = Fake::new();
        honest.fund(&addr(Chain::External, 20), 9_000, '9');
        let real = PROOF.txids[20].to_string();
        honest.unknown.extend(
            PROOF
                .txids
                .iter()
                .map(|txid| txid.to_string())
                .filter(|txid| *txid != real),
        );
        let store = ScanStore::default();
        let sync = |fake: &Fake| {
            snapshot_from(
                &EsploraSource(&Reads(&|url| fake.get(url))),
                &abandon_about(),
                "",
                KeyNetwork::Test,
                AddressType::NativeSegwit,
                &|| honest.now(),
                &store,
            )
            .unwrap()
        };
        let forged = sync(&liar);
        assert_eq!(
            (forged.scan, receive_index(&forged.receive_address)),
            (HotScan::Complete, 21),
            "the accepted testnet4 risk"
        );
        let reach = last_asked_by_a_restore(&[20]);
        for pass in ["first", "second"] {
            honest.tick(PUBLIC_FULL_PASS);
            let snapshot = sync(&honest);
            assert_eq!(
                (snapshot.scan, snapshot.balance_sats),
                (HotScan::Complete, 9_000),
                "{pass} honest full pass"
            );
            let receive = receive_index(&snapshot.receive_address);
            assert!(
                receive <= reach,
                "{pass} honest full pass: the wallet hands out receive {receive}, past a restore that stops at {reach}"
            );
        }
    }

    // R7, the bridge. A reorg undid receive 5 and receive 24, so the real chain shows receive 43 alone, a restore asks
    // receive 0 to 19, and a reinstall finds nothing. The wallet hands out receive 19, the last index that restore
    // asks, so a payment there carries a restore furthest. Paid in a block, it carries a restore to 39, still short of
    // 43, so the wallet hands out 39. Paid in a block too, a restore reaches 43 and the wallet hands out 44 again. Each
    // payment shows at the routine sync after it, and a reinstall's own restore proves 19, 39 and 43 and finds every
    // coin. The next test shows a payment in the mempool only moves nothing.
    #[test]
    fn payments_to_the_addresses_handed_out_past_a_gap_bridge_it_until_a_restore_reaches_the_stranded_payment()
     {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 5), 7_000, '5');
        fake.fund(&addr(Chain::External, 24), 8_000, '8');
        fake.fund(&addr(Chain::External, 43), 9_000, '9');
        let receive = |snapshot: HotWalletSnapshot| {
            (
                snapshot.scan,
                snapshot.balance_sats,
                receive_index(&snapshot.receive_address),
            )
        };
        assert_eq!(
            receive(fake.snapshot("").unwrap()),
            (HotScan::Complete, 24_000, 44)
        );
        undo_payment(&mut fake, 5);
        undo_payment(&mut fake, 24);
        fake.tick(PUBLIC_FULL_PASS);
        assert_eq!(
            receive(fake.snapshot("").unwrap()),
            (HotScan::Complete, 9_000, 19)
        );
        assert_eq!(
            receive(fake.snapshot_in(&ScanStore::default()).unwrap()),
            (HotScan::Complete, 0, 0),
            "a reinstall finds nothing past the gap"
        );
        fake.fund(&addr(Chain::External, 19), 1_000, 'a');
        fake.tick(SHARE_TTL);
        assert_eq!(
            receive(fake.snapshot("").unwrap()),
            (HotScan::Complete, 10_000, 39),
            "the first bridge, paid"
        );
        fake.fund(&addr(Chain::External, 39), 2_000, 'b');
        fake.tick(SHARE_TTL);
        assert_eq!(
            receive(fake.snapshot("").unwrap()),
            (HotScan::Complete, 12_000, 44),
            "the second bridge, paid"
        );
        assert_eq!(
            receive(fake.snapshot_in(&ScanStore::default()).unwrap()),
            (HotScan::Complete, 12_000, 44),
            "a reinstall"
        );
    }

    // R7, a bridge counts once its payment confirms. Paid in the mempool only, it can still be replaced, which would
    // open the gap again behind an address handed out past it, so the wallet hands out the same bridge until then. A
    // reorg undid receive 5, so the real chain shows receive 24 alone and the wallet hands out receive 19. Its payment
    // confirmed, a restore reaches 24 through it, and the wallet hands out 25 again.
    #[test]
    fn a_bridge_paid_in_the_mempool_only_lifts_no_cap_until_its_payment_confirms() {
        let mut fake = Fake::new();
        fake.fund(&addr(Chain::External, 5), 7_000, '5');
        fake.fund(&addr(Chain::External, 24), 9_000, '9');
        fake.snapshot("").unwrap();
        undo_payment(&mut fake, 5);
        fake.tick(PUBLIC_FULL_PASS);
        let receive = |fake: &Fake| {
            let snapshot = fake.snapshot("").unwrap();
            (
                snapshot.scan,
                snapshot.balance_sats,
                receive_index(&snapshot.receive_address),
            )
        };
        assert_eq!(receive(&fake), (HotScan::Complete, 9_000, 19));
        fake.fund_unconfirmed(&addr(Chain::External, 19), 1_000, 'a');
        fake.tick(SHARE_TTL);
        assert_eq!(
            receive(&fake),
            (HotScan::Complete, 10_000, 19),
            "the bridge paid in the mempool"
        );
        fake.fund(&addr(Chain::External, 19), 1_000, 'a');
        fake.tick(SHARE_TTL);
        assert_eq!(
            receive(&fake),
            (HotScan::Complete, 10_000, 25),
            "the bridge's payment confirmed"
        );
    }

    // A public host cuts a big wallet's full pass short every hour or two. A pass that has answered no gap yet leaves
    // the wallet handing out the address after the last one it used, never one it used already.
    #[test]
    fn a_full_pass_cut_short_before_any_gap_hands_out_the_address_after_the_last_used_one() {
        use std::sync::atomic::{AtomicBool, Ordering::SeqCst};
        let mut fake = Fake::new();
        for i in 0..60 {
            fake.touch(&addr(Chain::External, i));
        }
        assert_eq!(
            receive_index(&fake.snapshot("").unwrap().receive_address),
            60
        );
        let limited = AtomicBool::new(true);
        let cut = format!("/address/{}", addr(Chain::External, 20));
        let throttling = |url: &str| match limited.load(SeqCst) && url.ends_with(&cut) {
            true => Err(MobileSignerError::InvalidPsbtJson(
                "esplora get: HTTP status 429, gave up after 3 attempts".into(),
            )),
            false => fake.get(url),
        };
        let sync = || {
            let snapshot = snapshot_from(
                &EsploraSource(&Reads(&throttling)),
                &abandon_about(),
                "",
                KeyNetwork::Test,
                AddressType::NativeSegwit,
                &|| fake.now(),
                &fake.store,
            )
            .unwrap();
            (snapshot.scan, receive_index(&snapshot.receive_address))
        };
        fake.tick(PUBLIC_FULL_PASS);
        assert_eq!(
            sync(),
            (HotScan::Continuing, 60),
            "the full pass cut short at receive 20"
        );
        limited.store(false, SeqCst);
        fake.tick(SHARE_TTL);
        assert_eq!(sync(), (HotScan::Complete, 60), "the full pass ended");
    }

    // A host caught lying has its claims dropped, so the sync after it has answered no gap and the wallet hands out the
    // address after the proven one, not receive 19, which it used already.
    #[test]
    fn an_unverified_sync_hands_out_the_address_after_the_proven_one() {
        let mut fake = Fake::new();
        for i in 0..60 {
            fake.touch(&addr(Chain::External, i));
        }
        assert_eq!(
            receive_index(&fake.snapshot("").unwrap().receive_address),
            60
        );
        fake.stats.remove(&addr(Chain::External, 59));
        fake.tick(SHARE_TTL);
        let caught = fake.snapshot("").unwrap();
        assert_eq!(
            (caught.scan, receive_index(&caught.receive_address)),
            (HotScan::Unverified, 60),
            "the host still proves the payment it calls unpaid"
        );
    }
}
