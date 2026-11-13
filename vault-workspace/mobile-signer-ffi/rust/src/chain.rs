//! The seam between the wallet and a chain source. `hot_wallet` reads address summaries, history,
//! transactions and coins, posts payments and asks for fee rates through `ChainSource`, and never sees a
//! URL, a JSON body or a host. `EsploraSource` speaks Esplora's REST API over a `Transport`. `Http`, the
//! real transport, owns the host list, the genesis probe, the retry, the request limits and the HTTP
//! agent, and a test answers URLs with its own.

use std::collections::HashSet;
use std::str::FromStr;
use std::sync::{LazyLock, Mutex};
use std::time::{Duration, Instant};

use bitcoin::block::Header;
use bitcoin::consensus::encode::deserialize_hex;
use bitcoin::{Address, BlockHash, Transaction, TxMerkleNode, Txid};
use serde_json::Value;

use crate::hot_wallet::{FeeTiers, MAX_FEE_RATE};
use crate::proof::NotProven;
use crate::{KeyNetwork, MobileSignerError};

/// What the wallet reads from a chain, and the payment it posts. Every read answers in the order asked,
/// and one failure fails the whole call, so a caller never works from a partial picture of the wallet.
pub(crate) trait ChainSource {
    /// Whether each address was ever paid, and what it holds, the mempool included.
    fn summaries(&self, addresses: &[&Address]) -> Result<Vec<Summary>, MobileSignerError>;
    /// The first page of each address's history, the mempool first and then confirmed transactions
    /// newest first, and the tip height. The tip is read after the pages, so a block landing between
    /// them only raises it above every listed transaction.
    fn history(&self, addresses: &[&Address])
    -> Result<(Vec<Vec<TxView>>, u64), MobileSignerError>;
    /// `txid` and the tip height, read after it, or None when the source does not know `txid`.
    fn tx(&self, txid: &Txid) -> Result<Option<(TxView, u64)>, MobileSignerError>;
    /// The addresses whose coins each transaction spends.
    fn spends_from(&self, txids: &[Txid]) -> Result<Vec<Vec<String>>, MobileSignerError>;
    /// Each address's unspent coins. No outpoint is listed twice across them.
    fn utxos(&self, addresses: &[&Address]) -> Result<Vec<Vec<Utxo>>, MobileSignerError>;
    /// Posts `hex`, a signed transaction whose id is `txid`, once, and returns `txid` once the source
    /// shows it took it. A `hex` that is not that transaction is refused before anything is posted, and
    /// a payment that may be out fails with `BroadcastUnknown`.
    fn broadcast(&self, hex: &str, txid: &str) -> Result<String, MobileSignerError>;
    /// Slow, normal and fast rates in sat/vB, or None when the source gives no usable estimate.
    fn fee_tiers(&self) -> Option<FeeTiers>;
    /// `txid` from the source's raw bytes, unchecked. `crate::proof` checks it and the next two answers.
    /// Each fails `Unreachable` when no answer came, `Gone` when the source answered that it does not know
    /// the thing asked, and `Lie` when the answer is not the thing asked.
    fn raw_tx(&self, txid: &Txid) -> Result<Transaction, NotProven>;
    /// Where `txid` sits in the merkle tree of the block that holds it.
    fn merkle_branch(&self, txid: &Txid) -> Result<MerkleBranch, NotProven>;
    /// The header of block `hash`.
    fn header(&self, hash: &BlockHash) -> Result<Header, NotProven>;
    /// Leaves the host that gave the last answers, which lied, for the rest of this source's life, and says
    /// whether another host passed the network check to read from instead.
    fn skip_host(&self) -> bool;
    /// Who runs the host the next read goes to, which sets how often a full pass runs.
    fn operator(&self) -> Result<Operator, MobileSignerError>;
}

/// What a source says about one address.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Summary {
    pub(crate) used: bool,
    /// A block holds a transaction of it, so `prove_use` can prove it used.
    pub(crate) confirmed: bool,
    /// The mempool holds a transaction of it, so its picture changes once a block takes that one.
    pub(crate) pending: bool,
    pub(crate) balance: u64,
}

/// A transaction as a source reports it, read as far as every reader needs and no further. Each value
/// is at most `i64::MAX`, the most a net in sats can hold.
pub(crate) struct TxView {
    pub(crate) txid: String,
    pub(crate) inputs: Vec<Input>,
    pub(crate) outputs: Vec<Txo>,
    /// None while unconfirmed.
    pub(crate) confirmed: Option<Confirmed>,
    // Only the detail reads these, so they stay as the source gave them.
    pub(crate) size: Option<u64>,
    pub(crate) weight: Option<u64>,
}

pub(crate) struct Input {
    /// None for a coinbase, which spends no coin.
    pub(crate) prevout: Option<Txo>,
    /// The transaction that created the coin it spends, or None when the source gave none that reads as a
    /// txid. Only a proof needs it.
    pub(crate) prev_txid: Option<Txid>,
    /// As the source gave it, since only the detail reads it.
    pub(crate) sequence: Option<u64>,
}

/// An output, whether the transaction creates it or an input spends it. A script with no address has none.
pub(crate) struct Txo {
    pub(crate) address: Option<String>,
    pub(crate) value: u64,
}

/// The block that holds a transaction.
pub(crate) struct Confirmed {
    pub(crate) height: u64,
    /// Unix seconds as the source gave them, unchecked.
    pub(crate) time: Option<u64>,
    /// The block's id, or None when the source gave none that reads as one. Only a proof needs it.
    pub(crate) block_hash: Option<BlockHash>,
}

/// A transaction's place in its block's merkle tree, as Electrum's protocol and Esplora give it.
pub(crate) struct MerkleBranch {
    /// The transaction's index in the block. Read lowest bit first, a 1 says the node climbed so far
    /// is the right child at that level, and a 0 says it is the left.
    pub(crate) pos: u64,
    /// The sibling at each level, from the transaction's up to the root's children.
    pub(crate) hashes: Vec<TxMerkleNode>,
}

pub(crate) struct Utxo {
    pub(crate) txid: Txid,
    pub(crate) vout: u32,
    pub(crate) value: u64,
    pub(crate) confirmed: bool,
}

/// Refuses mainnet, which this build has no host for.
pub(crate) fn check_network(network: KeyNetwork) -> Result<(), MobileSignerError> {
    match network {
        KeyNetwork::Test => Ok(()),
        KeyNetwork::Main => Err(MobileSignerError::InvalidPsbtJson(
            "mainnet Esplora is not enabled in this build".into(),
        )),
    }
}

pub(crate) fn history_error(what: String) -> MobileSignerError {
    MobileSignerError::InvalidPsbtJson(format!("esplora history: {what}"))
}

pub(crate) fn tx_error(what: String) -> MobileSignerError {
    MobileSignerError::InvalidPsbtJson(format!("esplora tx: {what}"))
}

// Lookups in flight at once. Ten fills a fresh chain's gap in two windows and is a small burst for a
// public explorer.
pub(crate) const WINDOW: usize = 10;

/// Esplora's REST API over a `Transport`. Every URL is built on `ESPLORA_TESTNET4`, which `Http` moves
/// to the host in use.
pub(crate) struct EsploraSource<'a>(pub(crate) &'a dyn Transport);

impl EsploraSource<'_> {
    /// The body at `path` for a proof, or `Gone` saying `unknown` when the source answers 404.
    fn proof_read(
        &self,
        path: &str,
        unknown: impl FnOnce() -> String,
    ) -> Result<String, NotProven> {
        self.0
            .get(&format!("{ESPLORA_TESTNET4}{path}"))
            .map_err(|error| match answered_404(&error, path) {
                true => NotProven::Gone(unknown()),
                false => NotProven::from(error),
            })
    }

    /// Every URL's body, in the order given, `WINDOW` requests in flight at a time. One failure fails
    /// the whole batch, so a caller never works from a partial picture of the wallet.
    fn get_all(&self, urls: &[String]) -> Result<Vec<String>, MobileSignerError> {
        let mut bodies = Vec::with_capacity(urls.len());
        for window in urls.chunks(WINDOW) {
            let answers = std::thread::scope(|scope| {
                let handles: Vec<_> = window
                    .iter()
                    .map(|url| scope.spawn(move || self.0.get(url.as_str())))
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
}

impl ChainSource for EsploraSource<'_> {
    fn summaries(&self, addresses: &[&Address]) -> Result<Vec<Summary>, MobileSignerError> {
        let urls: Vec<String> = addresses
            .iter()
            .map(|address| format!("{ESPLORA_TESTNET4}/address/{address}"))
            .collect();
        self.get_all(&urls)?
            .iter()
            .map(|body| parse_address_stats(body))
            .collect()
    }

    fn history(
        &self,
        addresses: &[&Address],
    ) -> Result<(Vec<Vec<TxView>>, u64), MobileSignerError> {
        // shortcut: first page only per address, upgrade when a full History screen exists
        let urls: Vec<String> = addresses
            .iter()
            .map(|address| format!("{ESPLORA_TESTNET4}/address/{address}/txs"))
            .collect();
        let pages = self.get_all(&urls)?;
        let tip = self
            .0
            .get(&format!("{ESPLORA_TESTNET4}/blocks/tip/height"))?;
        read_history(&pages, &tip)
    }

    fn tx(&self, txid: &Txid) -> Result<Option<(TxView, u64)>, MobileSignerError> {
        let body = match self.0.get(&format!("{ESPLORA_TESTNET4}/tx/{txid}")) {
            Err(e) if is_not_found(&e, txid) => return Ok(None),
            answer => answer?,
        };
        let tip = self
            .0
            .get(&format!("{ESPLORA_TESTNET4}/blocks/tip/height"))?;
        read_tx(&body, &tip, txid).map(Some)
    }

    fn spends_from(&self, txids: &[Txid]) -> Result<Vec<Vec<String>>, MobileSignerError> {
        let urls: Vec<String> = txids
            .iter()
            .map(|txid| format!("{ESPLORA_TESTNET4}/tx/{txid}"))
            .collect();
        let answers = self.get_all(&urls)?;
        txids
            .iter()
            .zip(&answers)
            .map(|(txid, body)| spent_from(body, txid))
            .collect()
    }

    fn utxos(&self, addresses: &[&Address]) -> Result<Vec<Vec<Utxo>>, MobileSignerError> {
        let urls: Vec<String> = addresses
            .iter()
            .map(|address| format!("{ESPLORA_TESTNET4}/address/{address}/utxo"))
            .collect();
        let mut seen = HashSet::new();
        let mut lists = Vec::with_capacity(urls.len());
        for list in self.get_all(&urls)? {
            let utxos = parse_utxos(&list)?;
            for utxo in &utxos {
                if !seen.insert((utxo.txid, utxo.vout)) {
                    return Err(MobileSignerError::InvalidPsbtJson(format!(
                        "esplora utxo: {}:{} is listed twice",
                        utxo.txid, utxo.vout
                    )));
                }
            }
            lists.push(utxos);
        }
        Ok(lists)
    }

    fn broadcast(&self, hex: &str, txid: &str) -> Result<String, MobileSignerError> {
        self.0.broadcast(hex, txid)
    }

    /// mempool's `/v1/fees/recommended` first, since the own node is one; a host without it
    /// (blockstream.info answers 404) is asked Esplora's `/fee-estimates` instead. `None` when neither
    /// gives a usable rate.
    fn fee_tiers(&self) -> Option<FeeTiers> {
        let get = |path: &str, estimates: fn(&Value) -> Vec<(u32, &Value)>| {
            let body = self.0.get(&format!("{ESPLORA_TESTNET4}{path}")).ok()?;
            let json: Value = serde_json::from_str(&body).ok()?;
            tiers_from(&estimates(&json))
        };
        get("/v1/fees/recommended", recommended_estimates)
            .or_else(|| get("/fee-estimates", esplora_estimates))
    }

    fn raw_tx(&self, txid: &Txid) -> Result<Transaction, NotProven> {
        let hex = self.proof_read(&format!("/tx/{txid}/hex"), || {
            format!("the source does not know transaction {txid}")
        })?;
        deserialize_hex(hex.trim()).map_err(|e| {
            NotProven::Lie(format!(
                "transaction {txid}: the raw answer is not a transaction: {e}"
            ))
        })
    }

    fn merkle_branch(&self, txid: &Txid) -> Result<MerkleBranch, NotProven> {
        // Esplora answers 404 for a transaction no block holds.
        let json = self.proof_read(&format!("/tx/{txid}/merkle-proof"), || {
            format!("the source knows no block that holds transaction {txid}")
        })?;
        read_merkle_branch(&json)
            .map_err(|what| NotProven::Lie(format!("transaction {txid}: merkle proof {what}")))
    }

    fn header(&self, hash: &BlockHash) -> Result<Header, NotProven> {
        let hex = self.proof_read(&format!("/block/{hash}/header"), || {
            format!("the source does not know block {hash}")
        })?;
        deserialize_hex(hex.trim()).map_err(|e| {
            NotProven::Lie(format!(
                "block {hash}: the header answer is not a header: {e}"
            ))
        })
    }

    fn skip_host(&self) -> bool {
        self.0.skip_host()
    }

    fn operator(&self) -> Result<Operator, MobileSignerError> {
        self.0.operator()
    }
}

/// Esplora's `/tx/{txid}/merkle-proof` answer, `{"block_height", "merkle", "pos"}`. The height is not
/// read, because the block is the one the history names and the header check ties the branch to it.
fn read_merkle_branch(json: &str) -> Result<MerkleBranch, String> {
    let body: Value = serde_json::from_str(json).map_err(|e| format!("is not JSON: {e}"))?;
    let pos = body["pos"].as_u64().ok_or("has no unsigned integer pos")?;
    let hashes = body["merkle"]
        .as_array()
        .ok_or("has no merkle array")?
        .iter()
        .enumerate()
        .map(|(i, hash)| {
            hash.as_str()
                .and_then(|hash| hash.parse().ok())
                .ok_or_else(|| format!("hash {i} is not 64 hex characters"))
        })
        .collect::<Result<_, _>>()?;
    Ok(MerkleBranch { pos, hashes })
}

// Defaulting a missing field to 0 would show a Wi-Fi sign-in page as an empty wallet.
fn parse_address_stats(json: &str) -> Result<Summary, MobileSignerError> {
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
    let confirmed = sum("chain_stats", "tx_count")?;
    let pending = sum("mempool_stats", "tx_count")?;
    let txs = confirmed + pending;
    if txs == 0 && (funded > 0 || spent > 0) {
        return Err(bad(format!(
            "tx_count is 0 but funded {funded} and spent {spent}, the answer contradicts itself"
        )));
    }
    Ok(Summary {
        used: txs > 0,
        confirmed: confirmed > 0,
        pending: pending > 0,
        balance,
    })
}

/// The pages as read at the tip `tip_text`. An answer it cannot read fails the whole call, never one
/// transaction.
fn read_history(
    pages: &[String],
    tip_text: &str,
) -> Result<(Vec<Vec<TxView>>, u64), MobileSignerError> {
    let tip = tip_height(tip_text).map_err(history_error)?;
    let mut read = Vec::with_capacity(pages.len());
    for page in pages {
        let body: Value =
            serde_json::from_str(page).map_err(|e| history_error(format!("not JSON: {e}")))?;
        let txs = body
            .as_array()
            .ok_or_else(|| history_error("not an array".into()))?;
        // A bad transaction fails the list. Skipping it would show a history that is quietly wrong.
        read.push(
            txs.iter()
                .enumerate()
                .map(|(i, tx)| tx_view(tx).map_err(|e| history_error(format!("tx {i}: {e}"))))
                .collect::<Result<Vec<_>, _>>()?,
        );
    }
    Ok((read, tip))
}

/// The parts of a transaction every reader needs. A part it cannot read fails the transaction.
fn tx_view(tx: &Value) -> Result<TxView, String> {
    let txid = tx["txid"].as_str().ok_or("no string txid")?;
    let vin = tx["vin"].as_array().ok_or("vin is not an array")?;
    let vout = tx["vout"].as_array().ok_or("vout is not an array")?;
    let txo = |part: &Value, value| Txo {
        address: part["scriptpubkey_address"].as_str().map(str::to_string),
        value,
    };
    let outputs = vout
        .iter()
        .enumerate()
        .map(|(j, output)| {
            let value = sats(&output["value"])
                .ok_or_else(|| format!("output {j} has no unsigned integer value"))?;
            Ok(txo(output, value))
        })
        .collect::<Result<Vec<_>, String>>()?;
    let inputs = vin
        .iter()
        .enumerate()
        .map(|(j, input)| {
            let prevout = &input["prevout"];
            let prevout = if prevout.is_null() && input["is_coinbase"] == true {
                None
            } else {
                let value = sats(&prevout["value"]).ok_or_else(|| {
                    format!("input {j} has no prevout with an unsigned integer value")
                })?;
                Some(txo(prevout, value))
            };
            Ok(Input {
                prevout,
                prev_txid: input["txid"].as_str().and_then(|txid| txid.parse().ok()),
                sequence: input["sequence"].as_u64(),
            })
        })
        .collect::<Result<Vec<_>, String>>()?;

    let status = &tx["status"];
    let confirmed = if status["confirmed"]
        .as_bool()
        .ok_or("status.confirmed is not a boolean")?
    {
        let height = status["block_height"]
            .as_u64()
            .ok_or("confirmed without an integer block_height")?;
        Some(Confirmed {
            height,
            time: status["block_time"].as_u64(),
            block_hash: status["block_hash"].as_str().and_then(|h| h.parse().ok()),
        })
    } else {
        None
    };
    Ok(TxView {
        txid: txid.to_string(),
        inputs,
        outputs,
        confirmed,
        size: tx["size"].as_u64(),
        weight: tx["weight"].as_u64(),
    })
}

// At most i64::MAX, because a net is signed and a value above it is no real amount.
fn sats(value: &Value) -> Option<u64> {
    value.as_u64().filter(|n| i64::try_from(*n).is_ok())
}

/// The `/tx/{txid}` answer as read at the tip `tip_text`. An answer for another transaction is an error.
fn read_tx(tx_json: &str, tip_text: &str, txid: &Txid) -> Result<(TxView, u64), MobileSignerError> {
    let bad = |what: String| tx_error(format!("transaction {txid}: {what}"));
    let tip = tip_height(tip_text).map_err(bad)?;
    let tx: Value = serde_json::from_str(tx_json).map_err(|e| bad(format!("not JSON: {e}")))?;
    if tx["txid"] != txid.to_string().as_str() {
        return Err(bad("the answer is another transaction".into()));
    }
    Ok((tx_view(&tx).map_err(bad)?, tip))
}

/// `classify`'s text for an HTTP 404 of `/tx/{txid}` on any host (ureq 2.12.1 prints "{url}: status code 404", error.rs:214,
/// then maybe " (redirected from ...)"). The status is gone past the `Transport` boundary, so a test pins this to `classify`.
/// A probe's 404 that `pick_host` quotes in its own error names `/block-height/0`, so it is a failure.
pub(crate) fn is_not_found(error: &MobileSignerError, txid: &Txid) -> bool {
    answered_404(error, &format!("/tx/{txid}"))
}

/// Whether `error` is `classify`'s text for an HTTP 404 of `path` past the base, as `is_not_found` reads it.
fn answered_404(error: &MobileSignerError, path: &str) -> bool {
    matches!(error, MobileSignerError::InvalidPsbtJson(t) if t.contains(&format!("{path}: status code 404")))
}

/// The text of a read refused with HTTP 429, a host's request limit, or not made because its host is
/// under one, or of a round in which a host was, or None for any other failure. `limited_text` writes
/// every such text, and a test pins each producer's against it.
pub(crate) fn throttled(error: &MobileSignerError) -> Option<&str> {
    match error {
        MobileSignerError::InvalidPsbtJson(text)
            if text.starts_with("esplora get: ") && text.contains("HTTP status 429") =>
        {
            Some(text)
        }
        _ => None,
    }
}

fn tip_height(text: &str) -> Result<u64, String> {
    text.trim()
        .parse()
        .map_err(|e| format!("tip height is not an integer: {e}"))
}

/// The addresses the inputs of the `/tx/{txid}` answer `json` spend from. An answer that is not that
/// transaction, or whose inputs cannot be read, is an error, because a guess either way would spend a
/// stranger's coin or hide the wallet's own.
fn spent_from(json: &str, txid: &Txid) -> Result<Vec<String>, MobileSignerError> {
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
    Ok(vin
        .iter()
        .filter_map(|input| input["prevout"]["scriptpubkey_address"].as_str())
        .map(str::to_string)
        .collect())
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

/// Confirmation targets in blocks for slow, normal and fast. mempool's `hourFee`, `halfHourFee` and
/// `fastestFee` are read as these same targets, so both answer formats go through one rule.
const FEE_TARGETS: [u32; 3] = [6, 3, 1];

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

/// The highest tier, 1% under `MAX_FEE_RATE`, so a payment at the fast tier leaves room for the fee
/// bump or cancel the app asks at the fast tier too: BIP-125 rule 6 asks a rate above the earlier one,
/// and a payment at the limit itself could never be replaced.
const MAX_TIER_RATE: u64 = MAX_FEE_RATE - MAX_FEE_RATE / 100;

/// A rate as the Send screen can use it: a finite positive number rounded up to whole sat/vB and
/// capped at `MAX_TIER_RATE`, under the network's limit that `hot_prepare_send_at_rate` refuses above.
/// Anything else (zero, negative, NaN, a string, a missing field) is no estimate.
fn whole_sat_vb(rate: &Value) -> Option<u64> {
    let rate = rate.as_f64().filter(|r| r.is_finite() && *r > 0.0)?;
    Some((rate.ceil() as u64).min(MAX_TIER_RATE))
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

pub(crate) type Answer = Result<ureq::Response, ureq::Error>;

/// Posts the signed transaction once and returns `txid`, the id computed from it here, when the
/// explorer shows it took it. A txid the server sends back is never trusted.
///
/// A 200 naming `txid`, or a 4xx whose reason says the node already holds it, means the payment is
/// out. Any other 4xx is a refusal, and a retry after a refusal is safe. Anything else (no answer, a
/// 5xx, a 200 naming something else, a body that cannot be read) leaves it unclear: it looks `txid`
/// up to `LOOKUPS` times with `pause` between asks and never posts again. Still not found is
/// `BroadcastUnknown`, because a retry could pay twice.
pub(crate) fn broadcast_signed(
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

/// `broadcast_matching` on `host`. When `host` is the error from `pick_host`, nothing is posted.
#[expect(
    clippy::result_large_err,
    reason = "Answer is ureq's own call() result, so its error stays ureq's"
)]
pub(crate) fn broadcast_on(
    host: Result<&str, MobileSignerError>,
    post: &dyn Fn(&str, &str) -> Answer,
    lookup: &dyn Fn(&str, &str) -> Answer,
    pause: &dyn Fn(),
    hex: &str,
    txid: &str,
) -> Result<String, MobileSignerError> {
    let base = host?;
    broadcast_matching(
        &|hex| post(base, hex),
        &|txid| lookup(base, txid),
        pause,
        hex,
        txid,
    )
}

/// `broadcast_signed`, after checking that `hex` is a transaction whose id is `txid`.
pub(crate) fn broadcast_matching(
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

/// What an `EsploraSource` sends. `Http` is the real one, and a test scripts its own.
pub(crate) trait Transport: Sync {
    /// One GET of a URL built on `ESPLORA_TESTNET4`, the whole body as text.
    fn get(&self, url: &str) -> Result<String, MobileSignerError>;
    /// Runs `broadcast_on` against the host a payment goes to.
    fn broadcast(&self, hex: &str, txid: &str) -> Result<String, MobileSignerError>;
    /// `ChainSource::skip_host`. A transport with one host has none to go to.
    fn skip_host(&self) -> bool {
        false
    }
    /// `ChainSource::operator`. A test's transport stands for the first public host unless it says otherwise.
    fn operator(&self) -> Result<Operator, MobileSignerError> {
        Ok(Operator::Blockstream)
    }
}

/// The real transport over the hosts this build knows, with the genesis probe that picks one, the
/// retry, and the limit a host's 429 sets. `lost` runs each time a GET leaves the host in use forgotten. A host it skipped after a lie is
/// never read again through it, so a scan makes one `Http` per pass.
pub(crate) struct Http {
    lost: fn(),
    hosts: Mutex<Skips>,
}

/// The hosts an `Http` skipped, as indices into the host list, and the one its last read went to.
struct Skips {
    skipped: Vec<usize>,
    last: Option<usize>,
}

impl Http {
    pub(crate) const fn new(lost: fn()) -> Self {
        Http {
            lost,
            hosts: Mutex::new(Skips {
                skipped: Vec::new(),
                last: None,
            }),
        }
    }

    fn skips(&self) -> std::sync::MutexGuard<'_, Skips> {
        self.hosts
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    /// The host to read from, probing the list when none is cached, never one this source skipped.
    fn host(&self) -> Result<&'static HostSpec, MobileSignerError> {
        let skipped = self.skips().skipped.clone();
        let hosts = &*ESPLORA_TESTNET4_HOSTS;
        let index = pick_host(
            hosts,
            &probe_genesis,
            Instant::now(),
            &CHOSEN_HOST,
            &skipped,
            &LIMITS,
        )?;
        self.skips().last = Some(index);
        Ok(&hosts[index])
    }

    fn base(&self) -> Result<&'static str, MobileSignerError> {
        Ok(self.host()?.base)
    }
}

impl Transport for Http {
    fn get(&self, url: &str) -> Result<String, MobileSignerError> {
        let base = self.base()?;
        let url = on_host(url, base);
        get_forgetting(
            &|| {
                attempt_unless_limited(
                    base,
                    &|| classify(AGENT.get(&url).call()),
                    &LIMITS,
                    &Instant::now,
                )
            },
            &std::thread::sleep,
            &CHOSEN_HOST,
            &self.lost,
        )
    }

    #[expect(
        clippy::result_large_err,
        reason = "Answer is ureq's own call() result, so its error stays ureq's"
    )]
    fn broadcast(&self, hex: &str, txid: &str) -> Result<String, MobileSignerError> {
        let post = |base: &str, hex: &str| {
            AGENT
                .post(&format!("{base}/tx"))
                .set("Content-Type", "text/plain")
                .send_string(hex)
        };
        // Not /tx/{txid}/status: Esplora answers that one 200 for a txid it never saw (blockstream.info, 2026-11-02).
        let lookup = |base: &str, txid: &str| AGENT.get(&format!("{base}/tx/{txid}")).call();
        let pause = || std::thread::sleep(LOOKUP_PAUSE);
        broadcast_on(self.base(), &post, &lookup, &pause, hex, txid)
    }

    fn skip_host(&self) -> bool {
        let skipped = {
            let mut skips = self.skips();
            if let Some(last) = skips.last.take() {
                skips.skipped.push(last);
            }
            skips.skipped.clone()
        };
        let hosts = &*ESPLORA_TESTNET4_HOSTS;
        pick_host(
            hosts,
            &probe_genesis,
            Instant::now(),
            &CHOSEN_HOST,
            &skipped,
            &LIMITS,
        )
        .is_ok()
    }

    fn operator(&self) -> Result<Operator, MobileSignerError> {
        Ok(self.host()?.operator)
    }
}

/// Who runs a host.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Operator {
    /// This project's own node, from `OWN_NODE`.
    Mebit,
    Blockstream,
    MempoolSpace,
}

/// One testnet4 Esplora host and who runs it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct HostSpec {
    pub(crate) base: &'static str,
    pub(crate) operator: Operator,
}

impl HostSpec {
    /// How long its genesis probe may take, DNS to the last body byte.
    pub(crate) fn probe_timeout(&self) -> Duration {
        match self.operator {
            Operator::Mebit => OWN_NODE_PROBE_TIMEOUT,
            Operator::Blockstream | Operator::MempoolSpace => PROBE_TIMEOUT,
        }
    }
}

/// A private Esplora or mempool instance tried before the public hosts, read at build time from
/// `MEBIT_ESPLORA_HOST`. `scripts/ffi.sh` passes the one line of `esplora-host.local`, which git
/// ignores: a tailnet address is the developer's own network, not this repository's. A build without
/// it tries only the public hosts.
pub(crate) const OWN_NODE: Option<&str> = option_env!("MEBIT_ESPLORA_HOST");
/// The public testnet4 Esplora hosts in the order they are tried. Each limits requests per IP.
/// blockstream.info's own throttle reply on 2026-11-03 allowed 700 an hour and 500,000 a month without
/// an account (docs/04-open-items.md item 49), and a phone network shares one IP among many phones.
/// mempool.space is last because its TLS handshake hung from a phone on 5G and from the user's
/// networks on 2026-11-03.
pub(crate) const PUBLIC_TESTNET4_HOSTS: [HostSpec; 2] = [
    HostSpec {
        base: ESPLORA_TESTNET4,
        operator: Operator::Blockstream,
    },
    HostSpec {
        base: "https://mempool.space/testnet4/api",
        operator: Operator::MempoolSpace,
    },
];
/// Testnet4 hosts in the order they are tried: this build's private node first, because it has no
/// request limit, then the public hosts. Passing the genesis check does not make a host honest about
/// balances, which every host here is trusted with alike. Only a proof moves a scan, see `crate::scan`.
pub(crate) static ESPLORA_TESTNET4_HOSTS: LazyLock<Vec<HostSpec>> =
    LazyLock::new(|| hosts_with(OWN_NODE));

/// The hosts of a build whose private node is `own`. Cleartext `http://` is acceptable for a private
/// host, which Tailscale already encrypts, and for no other.
pub(crate) fn hosts_with(own: Option<&'static str>) -> Vec<HostSpec> {
    own.map(|base| HostSpec {
        base,
        operator: Operator::Mebit,
    })
    .into_iter()
    .chain(PUBLIC_TESTNET4_HOSTS)
    .collect()
}
/// The base every URL is built on, and the first public host. `Http` rewrites it to the host in
/// use.
pub(crate) const ESPLORA_TESTNET4: &str = "https://blockstream.info/testnet4/api";
/// Hash of testnet4 block 0. A host whose `/block-height/0` says anything else serves another
/// chain, and a balance or a broadcast there would be wrong, so it is never used.
pub(crate) const TESTNET4_GENESIS: &str =
    "00000000da84f2bafbbc53dee25a72ae507ff4914b867c565be350b0da8bf043";
/// How long a host that passed the genesis check is used before it is probed again.
pub(crate) const HOST_TRUST: Duration = Duration::from_secs(10 * 60);
/// The whole probe of a public host, DNS to the last body byte. The per-read limit alone let a host
/// that sent one byte every 3 s hold the host lock for 30 s.
const PROBE_TIMEOUT: Duration = Duration::from_secs(8);
/// The own node answers in under 100 ms when Tailscale is up and not at all when it is down, so a
/// phone off the tailnet loses at most this once per `HOST_TRUST` before the public hosts.
const OWN_NODE_PROBE_TIMEOUT: Duration = Duration::from_secs(3);
/// How long a round in which no host passed is answered from memory, so callers that queued on the
/// host lock during that round do not each probe every host again.
pub(crate) const PROBE_FAILURE_TTL: Duration = Duration::from_secs(5);

// Esplora connects in well under a second. Past 10 s on a weak phone network the host is not answering.
pub(crate) const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
// Per read and per write. ureq 2 has neither by default, so a server that went silent held the call for good.
pub(crate) const IO_TIMEOUT: Duration = Duration::from_secs(20);
// The whole request, DNS to the last body byte. The per-read limit alone lets a body that trickles
// hold a call for as long as the host likes.
const REQUEST_TIMEOUT: Duration = Duration::from_secs(45);

/// `whole` is ureq's deadline over the request. A body read that runs past it fails with an io
/// `TimedOut`, which `classify` reports as the host being unreachable.
pub(crate) fn agent(connect: Duration, io: Duration, whole: Duration) -> ureq::Agent {
    ureq::AgentBuilder::new()
        .timeout_connect(connect)
        .timeout_read(io)
        .timeout_write(io)
        .timeout(whole)
        .build()
}

pub(crate) static AGENT: LazyLock<ureq::Agent> =
    LazyLock::new(|| agent(CONNECT_TIMEOUT, IO_TIMEOUT, REQUEST_TIMEOUT));

/// What the last probe round decided: the index into the host list of the host in use and when it
/// passed the genesis check, or that no host passed, when, and the error that says so.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum HostVerdict {
    Chosen(usize, Instant),
    NoneReachable(Instant, String),
}
pub(crate) type HostChoice = Mutex<Option<HostVerdict>>;
static CHOSEN_HOST: HostChoice = Mutex::new(None);

/// The hosts that answered 429, each with when its latest limit ends. One entry per host, so it never
/// grows past the host list. An entry stays after its limit ends, because `pick_host` reads it as the
/// evidence that a host came back. In memory only, so a restart asks a limited host once more.
pub(crate) type Limits = Mutex<Vec<(&'static str, Instant)>>;
static LIMITS: Limits = Mutex::new(Vec::new());
/// How long a 429 without a usable Retry-After limits its host. It is also the wait before Home's first
/// ask after a sync that stopped short, so that ask finds the host free.
pub(crate) const LIMIT_DEFAULT: Duration = Duration::from_secs(60);
/// The longest Retry-After believed, so one header cannot shut a host out for good.
pub(crate) const LIMIT_MAX: Duration = Duration::from_secs(10 * 60);

// Retries are for a GET only. A retried broadcast could pay twice, so `broadcast_signed` has none.
const RETRIES: usize = 2;
const BACKOFF: [Duration; RETRIES] = [Duration::from_secs(1), Duration::from_secs(2)];
const MAX_RETRY_AFTER: Duration = Duration::from_secs(5);
const MAX_EXTRA_WAIT: Duration = Duration::from_secs(8);

/// Why one attempt at a GET failed, which decides whether it is asked again, with the text the caller
/// gets when it is the last attempt.
pub(crate) enum GetError {
    /// HTTP 429, the host's request limit. Never asked again. `attempt_unless_limited` limits the host
    /// and puts the limit in `what`.
    Throttled {
        retry_after: Option<Duration>,
        what: String,
    },
    /// A brief server error, 502, 503 or 504, answered quickly.
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
            GetError::Throttled { what, .. } | GetError::Retryable { what, .. } => what,
            GetError::Unreachable(text) | GetError::TimedOut(text) | GetError::Fatal(text) => text,
        }
    }
}

impl From<GetError> for MobileSignerError {
    fn from(mut failure: GetError) -> Self {
        MobileSignerError::InvalidPsbtJson(std::mem::take(failure.text()))
    }
}

/// Asks until an attempt gets a 200, or the attempts run out: `RETRIES + 1`, or two once one timed
/// out, because a timeout has already held the call up to `REQUEST_TIMEOUT`. A 429 is never asked
/// again, and neither is a `Fatal` answer. A GET is a read, so asking again after a lost connection is
/// safe. The waits add up to at most `MAX_EXTRA_WAIT`. The error is the last attempt's, so the caller
/// sees whether the host answered.
/// shortcut: Retry-After in HTTP-date form is ignored, upgrade if the host is seen sending it.
pub(crate) fn get_with_retry(
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
            GetError::Fatal(_) | GetError::Throttled { .. } => return Err(failure),
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
pub(crate) fn classify(answer: Result<ureq::Response, ureq::Error>) -> Result<String, GetError> {
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
            let what = format!("esplora get: HTTP status {code}");
            Err(match code {
                429 => GetError::Throttled { retry_after, what },
                _ => GetError::Retryable { retry_after, what },
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
/// host that answers another hash is skipped like one that does not answer. A host in `skipped` (a
/// scan caught it lying) is neither used nor probed. No host passing fails with `esplora get: no
/// testnet4 host is reachable`, naming each host's failure, and that round is remembered for
/// `PROBE_FAILURE_TTL`: a caller inside it gets the same error with no probe.
///
/// Three rules for `limits`, the hosts that answered 429. A host under a limit is never used and never
/// probed, the cached host included. An earlier host that came out of a limit after the cached verdict
/// was made wins back: the verdict is dropped and the list is probed from the top, so the own node
/// returns at the cost of one probe. A probe's 429 limits its host like a GET's, from when it answered.
pub(crate) fn pick_host(
    hosts: &[HostSpec],
    probe: &dyn Fn(&HostSpec) -> Result<String, GetError>,
    now: Instant,
    cache: &HostChoice,
    skipped: &[usize],
    limits: &Limits,
) -> Result<usize, MobileSignerError> {
    let mut verdict = cache
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    // One copy of the table per pick, so a pick never sees two states of it.
    let ends: Vec<Option<Instant>> = {
        let table = limits.lock().unwrap_or_else(|p| p.into_inner());
        hosts
            .iter()
            .map(|host| {
                table
                    .iter()
                    .find(|(base, _)| *base == host.base)
                    .map(|(_, end)| *end)
            })
            .collect()
    };
    let age = |at: &Instant| now.saturating_duration_since(*at);
    let limited = |index: usize| ends[index].filter(|end| now < *end);
    // A verdict made at `at` stands only while no host before `before` came out of a limit since then.
    let freed_since = |at: Instant, before: usize| {
        ends[..before]
            .iter()
            .any(|end| end.is_some_and(|end| at < end && end <= now))
    };
    match &*verdict {
        Some(HostVerdict::Chosen(index, verified))
            if age(verified) < HOST_TRUST
                && !skipped.contains(index)
                && limited(*index).is_none()
                && !freed_since(*verified, *index) =>
        {
            return Ok(*index);
        }
        Some(HostVerdict::NoneReachable(at, error))
            if age(at) < PROBE_FAILURE_TTL && !freed_since(*at, hosts.len()) =>
        {
            return Err(MobileSignerError::InvalidPsbtJson(error.clone()));
        }
        _ => {}
    }
    let mut failures = Vec::with_capacity(hosts.len());
    let began = Instant::now();
    for (index, host) in hosts.iter().enumerate() {
        if skipped.contains(&index) {
            failures.push(format!("{}: skipped, it lied", host.base));
            continue;
        }
        if let Some(end) = limited(index) {
            failures.push(limited_text(host.base, end, now));
            continue;
        }
        match probe(host) {
            Ok(body) if body.trim() == TESTNET4_GENESIS => {
                *verdict = Some(HostVerdict::Chosen(index, now));
                return Ok(index);
            }
            Ok(body) => {
                let shown: String = body.trim().chars().take(24).collect();
                failures.push(format!("{}: block 0 is {shown:?}, not testnet4", host.base));
            }
            Err(GetError::Throttled { retry_after, .. }) => {
                // This probe and the ones before it may have taken seconds.
                let answered = now + began.elapsed();
                let end = limit_host(limits, host.base, retry_after, answered);
                failures.push(limited_text(host.base, end, answered));
            }
            Err(mut failure) => failures.push(format!("{}: {}", host.base, failure.text())),
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
/// cut short, a timeout), so the next call probes again, and calls `lost`. A status or an unreadable
/// body is the host answering and changes nothing.
pub(crate) fn forget_host_if_unreachable(
    got: &Result<String, GetError>,
    cache: &HostChoice,
    lost: &dyn Fn(),
) {
    if matches!(got, Err(GetError::Unreachable(_) | GetError::TimedOut(_))) {
        *cache
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner()) = None;
        lost();
    }
}

/// `url` with the canonical base swapped for `base`. Any other URL is returned as it is.
pub(crate) fn on_host(url: &str, base: &str) -> String {
    match url.strip_prefix(ESPLORA_TESTNET4) {
        Some(rest) => format!("{base}{rest}"),
        None => url.to_string(),
    }
}

// The connect and each read get the host's whole-probe limit too: a host that cannot say its block 0
// hash in time is skipped for the next one instead of holding the scan. The answer goes through
// `classify`, so a probe's 429 arrives as `Throttled` with its Retry-After.
pub(crate) fn probe_genesis(host: &HostSpec) -> Result<String, GetError> {
    let limit = host.probe_timeout();
    classify(
        agent(limit, limit, limit)
            .get(&format!("{}/block-height/0", host.base))
            .call(),
    )
}

/// One attempt at a GET on `base`, made only while `base` is under no limit. A 429 limits `base` and
/// fails `Throttled`, and so does an attempt not made, each with the limit's text. `get_with_retry`
/// never asks a `Throttled` again, so once one lookup of a batch records a 429 its siblings make no
/// further attempt on that host. An attempt not made records nothing. Nothing here forgets the host or
/// calls `lost`, because a 429 is the host answering.
pub(crate) fn attempt_unless_limited(
    base: &'static str,
    call: &dyn Fn() -> Result<String, GetError>,
    limits: &Limits,
    clock: &dyn Fn() -> Instant,
) -> Result<String, GetError> {
    let now = clock();
    if let Some(end) = limit_end(limits, base).filter(|end| now < *end) {
        return Err(GetError::Throttled {
            retry_after: None,
            what: format!("esplora get: {}", limited_text(base, end, now)),
        });
    }
    match call() {
        Err(GetError::Throttled { retry_after, .. }) => {
            let now = clock(); // the call may have taken seconds
            let end = limit_host(limits, base, retry_after, now);
            Err(GetError::Throttled {
                retry_after,
                what: format!("esplora get: {}", limited_text(base, end, now)),
            })
        }
        got => got,
    }
}

/// Limits `base` until `now` plus its Retry-After, or `LIMIT_DEFAULT` without one or with 0, at most
/// `LIMIT_MAX`, and returns when the limit ends. A limit already running past that end stands, so
/// siblings' 429s end the same whatever their order. The only writer of a `Limits`.
fn limit_host(
    limits: &Limits,
    base: &'static str,
    retry_after: Option<Duration>,
    now: Instant,
) -> Instant {
    let end = now
        + retry_after
            .filter(|wait| !wait.is_zero())
            .unwrap_or(LIMIT_DEFAULT)
            .min(LIMIT_MAX);
    let mut table = limits.lock().unwrap_or_else(|p| p.into_inner());
    match table.iter_mut().find(|(host, _)| *host == base) {
        Some((_, stored)) => {
            *stored = (*stored).max(end);
            *stored
        }
        None => {
            table.push((base, end));
            end
        }
    }
}

/// When the latest limit `base` set ends, or ended, or None if it never answered 429.
fn limit_end(limits: &Limits, base: &str) -> Option<Instant> {
    limits
        .lock()
        .unwrap_or_else(|p| p.into_inner())
        .iter()
        .find(|(host, _)| *host == base)
        .map(|(_, end)| *end)
}

/// `{base}: HTTP status 429, limited for {n} s more`, with n rounded up so the last second reads 1.
/// Every 429 text the wallet gives is built here, so `throttled` matches each by construction.
fn limited_text(base: &str, end: Instant, now: Instant) -> String {
    let left = end.saturating_duration_since(now);
    let n = left.as_secs() + u64::from(left.subsec_nanos() > 0);
    format!("{base}: HTTP status 429, limited for {n} s more")
}

/// `call` with the retry, forgetting the host in use and calling `lost` when the last attempt got no
/// whole answer, since a loss the retry recovered says the host answers. Every GET of the wallet goes
/// through here, the fee tiers included.
pub(crate) fn get_forgetting(
    call: &dyn Fn() -> Result<String, GetError>,
    sleep: &dyn Fn(Duration),
    hosts: &HostChoice,
    lost: &dyn Fn(),
) -> Result<String, MobileSignerError> {
    let got = get_with_retry(call, sleep);
    forget_host_if_unreachable(&got, hosts, lost);
    Ok(got?)
}
