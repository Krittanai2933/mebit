//! A several-key wallet's payment as a PSBT (BIP 174, version 0 as rust-bitcoin 0.32 reads and writes it), from the
//! unsigned one `vault_prepare_send` builds to the transaction `vault_finalize_psbt` hands to `hot_broadcast`. Nothing here
//! reads the chain or posts. Every entry point parses the PSBT it is given and holds it to the wallet's keys and threshold
//! and to what the user confirmed on Penpot 5.2 (`checked`) before it signs or assembles, and only a `Checked` is signed
//! or assembled.
//!
//! The wallet-send counterpart of vault-core's `policy`, which is lending-only: it needs a loan id and refuses change.
//! docs/04-open-items.md item 94 asks the vault-core owners whether `checked` moves there. Review it as `policy` is reviewed.

use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::str::FromStr;

use bitcoin::absolute::LockTime;
use bitcoin::bip32::{ChildNumber, DerivationPath, KeySource, Xpriv};
use bitcoin::consensus::encode::serialize_hex;
use bitcoin::hashes::Hash;
use bitcoin::opcodes::all::OP_CHECKMULTISIG;
use bitcoin::psbt::{Input, Psbt};
use bitcoin::script::Builder;
use bitcoin::secp256k1::{All, Message, PublicKey, Secp256k1, SecretKey};
use bitcoin::sighash::SighashCache;
use bitcoin::transaction::{InputWeightPrediction, Version, predict_weight};
use bitcoin::{
    Amount, EcdsaSighashType, Network, OutPoint, ScriptBuf, Sequence, Transaction, TxIn, TxOut,
    Txid, Witness, ecdsa,
};
use vault_core::hw::psbt_check::validate_signing_policy;
use vault_core::keys::{ScriptType, account_multisig_xpub_from_mnemonic};

use super::{
    Addresses, Chain, MAX_FEE_RATE, MAX_STANDARD_VSIZE, MIN_FEE_RATE, Multisig, PreparedSend,
    check_node_rate, destination_on, dust_floor, full_size, mnemonic_from, vsize,
};
use crate::{KeyNetwork, MobileSignerError};

/// One payment from a several-key wallet, from `vault_prepare_send` to `vault_finalize_psbt`. The app holds the latest one
/// and passes it back whole. Every field but `psbt` is what the user confirmed or what the core read from `psbt` once it
/// passed every check, so the record never says anything its PSBT does not.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct VaultPsbt {
    /// The PSBT, base64.
    pub psbt: String,
    /// The id of the finished transaction. A segwit id leaves the signatures out, so it is fixed from the unsigned PSBT on,
    /// and the app's pay-twice guard names the payment by it.
    pub txid: String,
    /// What the first output pays, as the user typed it.
    pub destination: String,
    pub amount_sats: u64,
    /// Inputs less outputs. Every input's value is read from its parent transaction, which the PSBT carries.
    pub fee_sats: u64,
    /// The one output back to the wallet's change chain, 1/i. 0 when there is none.
    pub change_sats: u64,
    /// The highest change index the payment may pay its change to: the one the wallet handed out next when the payment was
    /// prepared, so no PSBT parks change past the addresses a scan reads.
    pub max_change_index: u32,
    /// The finished transaction's virtual size: exact for the phone's signatures, an upper bound for shorter ones.
    pub vsize: u64,
    /// Positions in `xpubs` of the keys with a valid SIGHASH_ALL signature on every input, ascending, each once.
    pub signed_keys: Vec<u32>,
}

/// `held` with key `key_index`'s signature on every input, made with the words and passphrase of a phone key, whose BIP-48
/// account key at m/48'/1'/0'/2' must be key `key_index` of the `threshold`-of-`xpubs.len()` wallet on public key and chain
/// code. Before it signs it refuses, naming the rule, a PSBT that is not this wallet's payment of `held.amount_sats` to
/// `held.destination` for `held.fee_sats` with at most one change output back to the wallet's change chain at or below
/// `held.max_change_index`, a txid other than `held.txid`, any sighash but ALL, and a signature that does not verify. The
/// same words sign the same PSBT the same way, so signing again changes nothing. Testnet4 only. No network call.
#[uniffi::export]
pub fn vault_sign_psbt(
    xpubs: Vec<String>,
    threshold: u8,
    held: VaultPsbt,
    key_index: u32,
    mnemonic_words: Vec<String>,
    passphrase: String,
) -> Result<VaultPsbt, MobileSignerError> {
    let vault = Multisig::new(&xpubs, threshold)?;
    let confirmed = Confirmed::from(&held);
    let checked = checked(&vault, parse(&held.psbt)?, &confirmed)?;
    let signed = signed_by(
        checked,
        &vault,
        key_index as usize,
        &mnemonic_words,
        &passphrase,
    )?;
    described(&vault, signed, &confirmed)
}

/// The signed transaction of `held` once at least `threshold` keys of the wallet signed every input, for `hot_broadcast`.
/// Runs every check `vault_sign_psbt` runs, refuses fewer signers than `threshold`, puts `threshold` of their signatures in
/// each input's witness in the order its script names their keys, and refuses bytes heavier than the size the fee was
/// priced at or a fee past the network's 10,000 sat/vB. Testnet4 only. No network call.
#[uniffi::export]
pub fn vault_finalize_psbt(
    xpubs: Vec<String>,
    threshold: u8,
    held: VaultPsbt,
) -> Result<PreparedSend, MobileSignerError> {
    let vault = Multisig::new(&xpubs, threshold)?;
    let checked = checked(&vault, parse(&held.psbt)?, &Confirmed::from(&held))?;
    let tx = assembled(checked, &vault, held.fee_sats)?;
    Ok(PreparedSend {
        txid: tx.compute_txid().to_string(),
        hex: serialize_hex(&tx),
        fee_sats: held.fee_sats,
        amount_sats: tx.output[0].value.to_sat(),
        dust_change_sats: 0,
        amount_cut_sats: 0,
    })
}

/// One address of the wallet as a spend sees it: where it sits, its witness script, and which wallet key each key of the
/// script is.
pub(super) struct VaultScript {
    chain: Chain,
    index: u32,
    witness_script: ScriptBuf,
    /// In script order, sorted by serialized key as sortedmulti sorts them: each child key and its position in `xpubs`.
    keys: Vec<(PublicKey, usize)>,
}

impl VaultScript {
    fn script_pubkey(&self) -> ScriptBuf {
        ScriptBuf::new_p2wsh(&self.witness_script.wscript_hash())
    }

    /// A derivation entry per wallet key: its child key here, the wallet key's own fingerprint and chain/index below it. A
    /// phone finds its key by its xpub, so no entry needs a master fingerprint until a hardware key signs.
    fn derivation(&self, vault: &Multisig) -> BTreeMap<PublicKey, KeySource> {
        let path = DerivationPath::from(vec![
            ChildNumber::Normal {
                index: self.chain as u32,
            },
            ChildNumber::Normal { index: self.index },
        ]);
        self.keys
            .iter()
            .map(|(key, position)| (*key, (vault.keys[*position].fingerprint(), path.clone())))
            .collect()
    }
}

/// `OP_m <every wallet key's child at chain/index, sorted> OP_n OP_CHECKMULTISIG`, refused unless its P2WSH is the address
/// vault-core derives at the same place, so the scan, the Receive QR and every spend name one script.
pub(super) fn vault_script(
    vault: &Multisig,
    chain: Chain,
    index: u32,
) -> Result<VaultScript, MobileSignerError> {
    let secp = Secp256k1::verification_only();
    let path = [
        ChildNumber::Normal {
            index: chain as u32,
        },
        ChildNumber::Normal { index },
    ];
    let mut keys = Vec::with_capacity(vault.keys.len());
    for (position, xpub) in vault.keys.iter().enumerate() {
        let child = xpub
            .derive_pub(&secp, &path)
            .map_err(|e| MobileSignerError::InvalidPsbtJson(e.to_string()))?;
        keys.push((child.public_key, position));
    }
    keys.sort_by_key(|(key, _)| key.serialize());
    let witness_script = keys
        .iter()
        .fold(
            Builder::new().push_int(vault.threshold as i64),
            |script, (key, _)| script.push_key(&bitcoin::PublicKey::new(*key)),
        )
        .push_int(keys.len() as i64)
        .push_opcode(OP_CHECKMULTISIG)
        .into_script();
    let script = VaultScript {
        chain,
        index,
        witness_script,
        keys,
    };
    if script.script_pubkey() != vault.address(chain, index)?.script_pubkey() {
        return Err(refusal(
            "coin",
            format!(
                "the script at {} {index} is not the address vault-core derives",
                chain.name()
            ),
        ));
    }
    Ok(script)
}

/// How one input of the wallet is spent: an empty element for CHECKMULTISIG's extra pop, `threshold` signatures of 72 bytes
/// with their sighash byte, which `full_size` makes every phone signature, and the witness script, measured on receive
/// address 0, since every address of the wallet has a script of one length.
pub(super) fn input_weight(vault: &Multisig) -> Result<InputWeightPrediction, MobileSignerError> {
    let script_len = vault_script(vault, Chain::External, 0)?.witness_script.len();
    let mut elements = vec![0];
    elements.extend(std::iter::repeat_n(72, vault.threshold));
    elements.push(script_len);
    Ok(InputWeightPrediction::new(0, elements))
}

/// The unsigned PSBT that spends `coins`, each an outpoint and the place of the wallet's address it pays, in their order, to
/// `outputs`, the destination first and any change second at change index `change`: version 2, lock time 0 and the RBF
/// sequence, as a one-key payment is built. Each input carries its parent from `parents`, the output it spends, its witness
/// script and its derivation entries, and the change output its witness script and entries. Nothing else, so `checked`
/// can hold any PSBT to exactly this. Pure.
pub(super) fn unsigned(
    vault: &Multisig,
    coins: &[(OutPoint, Chain, u32)],
    parents: &HashMap<Txid, Transaction>,
    outputs: Vec<TxOut>,
    change: Option<u32>,
) -> Result<Psbt, MobileSignerError> {
    let tx = Transaction {
        version: Version::TWO,
        lock_time: LockTime::ZERO,
        input: coins
            .iter()
            .map(|(outpoint, _, _)| TxIn {
                previous_output: *outpoint,
                script_sig: ScriptBuf::new(),
                sequence: Sequence::ENABLE_RBF_NO_LOCKTIME,
                witness: Witness::new(),
            })
            .collect(),
        output: outputs,
    };
    let mut psbt =
        Psbt::from_unsigned_tx(tx).map_err(|e| MobileSignerError::InvalidPsbtJson(e.to_string()))?;
    for (input, (outpoint, chain, index)) in psbt.inputs.iter_mut().zip(coins) {
        let parent = parents
            .get(&outpoint.txid)
            .ok_or_else(|| refusal("parent", format!("no parent for {outpoint}")))?;
        let script = vault_script(vault, *chain, *index)?;
        input.witness_utxo = parent.output.get(outpoint.vout as usize).cloned();
        input.non_witness_utxo = Some(parent.clone());
        input.bip32_derivation = script.derivation(vault);
        input.witness_script = Some(script.witness_script);
    }
    if let Some(index) = change {
        let script = vault_script(vault, Chain::Internal, index)?;
        let output = psbt
            .outputs
            .get_mut(1)
            .ok_or_else(|| refusal("outputs", "change with no second output".into()))?;
        output.bip32_derivation = script.derivation(vault);
        output.witness_script = Some(script.witness_script);
    }
    Ok(psbt)
}

/// The record of `psbt` once it passed `checked` against `confirmed`.
pub(super) fn described(
    vault: &Multisig,
    psbt: Psbt,
    confirmed: &Confirmed,
) -> Result<VaultPsbt, MobileSignerError> {
    let checked = checked(vault, psbt, confirmed)?;
    Ok(VaultPsbt {
        psbt: checked.psbt.to_string(),
        txid: checked.psbt.unsigned_tx.compute_txid().to_string(),
        destination: confirmed.destination.to_string(),
        amount_sats: confirmed.amount_sats,
        fee_sats: confirmed.fee_sats,
        change_sats: checked.change_sats,
        max_change_index: confirmed.max_change_index,
        vsize: checked.vsize,
        signed_keys: checked.signed.iter().map(|p| *p as u32).collect(),
    })
}

/// What the user confirmed on Penpot 5.2, which every check holds the PSBT to. `txid` is absent only on the check of the PSBT
/// `vault_prepare_send` just built, whose id it is.
pub(super) struct Confirmed<'a> {
    pub(super) destination: &'a str,
    pub(super) amount_sats: u64,
    pub(super) fee_sats: u64,
    pub(super) max_change_index: u32,
    pub(super) txid: Option<&'a str>,
}

impl<'a> From<&'a VaultPsbt> for Confirmed<'a> {
    fn from(held: &'a VaultPsbt) -> Self {
        Confirmed {
            destination: &held.destination,
            amount_sats: held.amount_sats,
            fee_sats: held.fee_sats,
            max_change_index: held.max_change_index,
            txid: Some(&held.txid),
        }
    }
}

/// A PSBT that passed `checked`, and what the check learned. Only `checked` makes one, and only a `Checked` is signed or
/// assembled.
pub(super) struct Checked {
    psbt: Psbt,
    /// Each input's script and the value its coin holds.
    coins: Vec<(VaultScript, Amount)>,
    change_sats: u64,
    vsize: u64,
    /// Positions in `xpubs` with a valid signature on every input.
    signed: BTreeSet<usize>,
}

/// Holds `psbt` to the wallet and to `confirmed`. Each rule refuses with `vault psbt: <rule>: ...`, in this order:
/// 1. `policy`: vault-core's `validate_signing_policy`. Input and output maps match the transaction's, no Taproot, ECDSA
///    SIGHASH_ALL only on each input and on every partial signature, and a parent the PSBT carries hashes to its outpoint
///    and pays exactly the `witness_utxo` beside it.
/// 2. `shape`: version 2, lock time 0, at least one input, every sequence the RBF one, no outpoint twice, and no final
///    script or redeem script on an input.
/// 3. `parent`: every input carries its parent transaction. A segwit v0 signature commits to its own input's value only,
///    so two PSBTs of one payment that lie about different inputs would otherwise combine into a valid transaction whose
///    fee no signer saw.
/// 4. `coin`: every input spends a coin of the wallet. Its derivation entries name one place chain/index, and its witness
///    script and entries are the ones `vault_script` makes there, whose P2WSH its parent pays.
/// 5. `outputs`: output 0 pays `confirmed.destination` (testnet4) exactly `confirmed.amount_sats`. At most one more
///    output, the wallet's change chain 1/i at an index up to `confirmed.max_change_index`, proven as an input is. A
///    receive address is not change. No third output.
/// 6. `dust`: no output under its script's dust floor, which the node refuses.
/// 7. `fee`: inputs less outputs is `confirmed.fee_sats`, the size priced with every input at `input_weight` is at most
///    100,000 vB, and the fee is 1 to 10,000 sat/vB of it.
/// 8. `txid`: `confirmed.txid`, when given, is the transaction's id.
/// 9. `signature`: every partial signature is by a wallet key's child for its input and verifies over that input's P2WSH
///    SIGHASH_ALL digest.
/// 10. `canonical`: the PSBT is exactly what `unsigned` builds for its own coins and outputs, plus partial signatures, so a
///     field no rule names is refused too. The named rules run first, so a refusal names the rule a PSBT broke.
pub(super) fn checked(
    vault: &Multisig,
    psbt: Psbt,
    confirmed: &Confirmed,
) -> Result<Checked, MobileSignerError> {
    policy(&psbt)?;
    shape(&psbt)?;
    parent(&psbt)?;
    let coins = coins_of(vault, &psbt)?;
    coin(vault, &psbt, &coins)?;
    outputs(vault, &psbt, confirmed)?;
    dust(&psbt)?;
    let vsize = priced(vault, psbt.inputs.len(), &psbt.unsigned_tx.output)?;
    fee(&psbt, &coins, vsize, confirmed)?;
    txid(&psbt, confirmed)?;
    signature(&psbt, &coins)?;
    canonical(vault, &psbt, &coins)?;
    let change_sats = psbt
        .unsigned_tx
        .output
        .get(1)
        .map_or(0, |out| out.value.to_sat());
    let signed = signers(&psbt, &coins);
    Ok(Checked {
        psbt,
        coins,
        change_sats,
        vsize,
        signed,
    })
}

fn policy(psbt: &Psbt) -> Result<(), MobileSignerError> {
    validate_signing_policy(psbt).map_err(|e| refusal("policy", e.to_string()))
}

fn shape(psbt: &Psbt) -> Result<(), MobileSignerError> {
    let tx = &psbt.unsigned_tx;
    if tx.version != Version::TWO || tx.lock_time != LockTime::ZERO || tx.input.is_empty() {
        return Err(refusal(
            "shape",
            format!(
                "version {}, lock time {}, {} inputs",
                tx.version.0,
                tx.lock_time,
                tx.input.len()
            ),
        ));
    }
    let mut spent = HashSet::new();
    for (i, (txin, input)) in tx.input.iter().zip(&psbt.inputs).enumerate() {
        if txin.sequence != Sequence::ENABLE_RBF_NO_LOCKTIME
            || !spent.insert(txin.previous_output)
            || input.final_script_sig.is_some()
            || input.final_script_witness.is_some()
            || input.redeem_script.is_some()
        {
            return Err(refusal(
                "shape",
                format!("input {i} is not one this wallet builds"),
            ));
        }
    }
    Ok(())
}

fn parent(psbt: &Psbt) -> Result<(), MobileSignerError> {
    match psbt.inputs.iter().position(|input| input.non_witness_utxo.is_none()) {
        Some(i) => Err(refusal(
            "parent",
            format!("input {i} does not carry the transaction that made its coin"),
        )),
        None => Ok(()),
    }
}

/// Each input's place in the wallet by its derivation entries, that place's script, and the value its coin holds by its
/// parent or by the `witness_utxo` beside it, which `policy` holds to the parent. `coin` then proves the place.
fn coins_of(
    vault: &Multisig,
    psbt: &Psbt,
) -> Result<Vec<(VaultScript, Amount)>, MobileSignerError> {
    let mut coins = Vec::with_capacity(psbt.inputs.len());
    for (i, (input, txin)) in psbt.inputs.iter().zip(&psbt.unsigned_tx.input).enumerate() {
        let (chain, index) = place_of(&input.bip32_derivation)
            .ok_or_else(|| refusal("coin", format!("input {i} names no address of the wallet")))?;
        let value = prevout(input, txin)
            .ok_or_else(|| refusal("policy", format!("input {i} names no coin")))?
            .value;
        coins.push((vault_script(vault, chain, index)?, value));
    }
    Ok(coins)
}

fn coin(
    vault: &Multisig,
    psbt: &Psbt,
    coins: &[(VaultScript, Amount)],
) -> Result<(), MobileSignerError> {
    for (i, ((input, txin), (script, _))) in psbt
        .inputs
        .iter()
        .zip(&psbt.unsigned_tx.input)
        .zip(coins)
        .enumerate()
    {
        let paid = prevout(input, txin).map(|out| &out.script_pubkey);
        if input.witness_script.as_ref() != Some(&script.witness_script)
            || input.bip32_derivation != script.derivation(vault)
            || paid != Some(&script.script_pubkey())
        {
            return Err(refusal(
                "coin",
                format!(
                    "input {i} is not the wallet's coin at {} {}",
                    script.chain.name(),
                    script.index
                ),
            ));
        }
    }
    Ok(())
}

fn outputs(
    vault: &Multisig,
    psbt: &Psbt,
    confirmed: &Confirmed,
) -> Result<(), MobileSignerError> {
    let refuse = |why: String| Err(refusal("outputs", why));
    let destination = destination_on(confirmed.destination, KeyNetwork::Test)
        .map_err(|e| refusal("outputs", format!("the destination: {e}")))?;
    let outs = &psbt.unsigned_tx.output;
    match outs.first() {
        Some(pay)
            if pay.script_pubkey == destination.script_pubkey()
                && pay.value.to_sat() == confirmed.amount_sats => {}
        _ => {
            return refuse(format!(
                "output 0 does not pay {} sats to {}",
                confirmed.amount_sats, confirmed.destination
            ));
        }
    }
    if outs.len() > 2 {
        return refuse(format!("{} outputs", outs.len()));
    }
    let Some(change) = outs.get(1) else {
        return Ok(());
    };
    let map = &psbt.outputs[1];
    match place_of(&map.bip32_derivation) {
        Some((Chain::Internal, index)) if index <= confirmed.max_change_index => {
            let script = vault_script(vault, Chain::Internal, index)?;
            if map.witness_script.as_ref() == Some(&script.witness_script)
                && map.bip32_derivation == script.derivation(vault)
                && change.script_pubkey == script.script_pubkey()
            {
                return Ok(());
            }
            refuse(format!("output 1 is not the wallet's change at {index}"))
        }
        _ => refuse(format!(
            "output 1 is not change at or below index {}",
            confirmed.max_change_index
        )),
    }
}

fn dust(psbt: &Psbt) -> Result<(), MobileSignerError> {
    for (i, out) in psbt.unsigned_tx.output.iter().enumerate() {
        let floor = dust_floor(&out.script_pubkey);
        if out.value.to_sat() < floor {
            return Err(refusal(
                "dust",
                format!(
                    "output {i} pays {} sats, under its {floor} sat dust floor",
                    out.value.to_sat()
                ),
            ));
        }
    }
    Ok(())
}

fn fee(
    psbt: &Psbt,
    coins: &[(VaultScript, Amount)],
    vsize: u64,
    confirmed: &Confirmed,
) -> Result<(), MobileSignerError> {
    let spent = coins
        .iter()
        .try_fold(Amount::ZERO, |sum, (_, value)| sum.checked_add(*value));
    let paid = psbt
        .unsigned_tx
        .output
        .iter()
        .try_fold(Amount::ZERO, |sum, out| sum.checked_add(out.value));
    let fee = spent
        .zip(paid)
        .and_then(|(spent, paid)| spent.checked_sub(paid))
        .map(Amount::to_sat);
    if fee != Some(confirmed.fee_sats) {
        return Err(refusal(
            "fee",
            format!(
                "inputs less outputs is {fee:?}, not the {} sats confirmed",
                confirmed.fee_sats
            ),
        ));
    }
    if vsize > MAX_STANDARD_VSIZE
        || !(MIN_FEE_RATE * vsize..=MAX_FEE_RATE * vsize).contains(&confirmed.fee_sats)
    {
        return Err(refusal(
            "fee",
            format!(
                "fee {} for {vsize} vB is outside {MIN_FEE_RATE} to {MAX_FEE_RATE} sat/vB, or the size passes {MAX_STANDARD_VSIZE} vB",
                confirmed.fee_sats
            ),
        ));
    }
    Ok(())
}

fn txid(psbt: &Psbt, confirmed: &Confirmed) -> Result<(), MobileSignerError> {
    let id = psbt.unsigned_tx.compute_txid().to_string();
    match confirmed.txid {
        Some(held) if held != id => Err(refusal(
            "txid",
            format!("the payment is {id}, not the {held} confirmed"),
        )),
        _ => Ok(()),
    }
}

fn signature(psbt: &Psbt, coins: &[(VaultScript, Amount)]) -> Result<(), MobileSignerError> {
    let secp = Secp256k1::verification_only();
    let mut cache = SighashCache::new(&psbt.unsigned_tx);
    for (i, (input, (script, value))) in psbt.inputs.iter().zip(coins).enumerate() {
        if input.partial_sigs.is_empty() {
            continue;
        }
        let digest = cache
            .p2wsh_signature_hash(i, &script.witness_script, *value, EcdsaSighashType::All)
            .map_err(|e| refusal("signature", e.to_string()))?;
        let message = Message::from_digest(digest.to_byte_array());
        for (key, sig) in &input.partial_sigs {
            let ours = key.compressed && script.keys.iter().any(|(k, _)| *k == key.inner);
            if !ours
                || sig.sighash_type != EcdsaSighashType::All
                || secp
                    .verify_ecdsa(&message, &sig.signature, &key.inner)
                    .is_err()
            {
                return Err(refusal(
                    "signature",
                    format!("input {i} holds a signature that is not a wallet key's over this payment"),
                ));
            }
        }
    }
    Ok(())
}

fn canonical(
    vault: &Multisig,
    psbt: &Psbt,
    coins: &[(VaultScript, Amount)],
) -> Result<(), MobileSignerError> {
    let mut bare = psbt.clone();
    for input in &mut bare.inputs {
        input.partial_sigs.clear();
    }
    let places: Vec<(OutPoint, Chain, u32)> = psbt
        .unsigned_tx
        .input
        .iter()
        .zip(coins)
        .map(|(txin, (script, _))| (txin.previous_output, script.chain, script.index))
        .collect();
    let parents: HashMap<Txid, Transaction> = psbt
        .inputs
        .iter()
        .filter_map(|input| input.non_witness_utxo.clone())
        .map(|tx| (tx.compute_txid(), tx))
        .collect();
    let change = psbt
        .outputs
        .get(1)
        .and_then(|map| place_of(&map.bip32_derivation))
        .map(|(_, index)| index);
    let rebuilt = unsigned(
        vault,
        &places,
        &parents,
        psbt.unsigned_tx.output.clone(),
        change,
    );
    if rebuilt.ok().as_ref() != Some(&bare) {
        return Err(refusal(
            "canonical",
            "the PSBT carries more or other than this wallet writes".into(),
        ));
    }
    Ok(())
}

/// The place chain/index the first derivation entry names, or None for no entry or another path. `coin` and `outputs` hold
/// every entry to the ones the place's script has.
fn place_of(entries: &BTreeMap<PublicKey, KeySource>) -> Option<(Chain, u32)> {
    let (_, path) = entries.values().next()?;
    match path.as_ref() {
        [ChildNumber::Normal { index: 0 }, ChildNumber::Normal { index }] => {
            Some((Chain::External, *index))
        }
        [ChildNumber::Normal { index: 1 }, ChildNumber::Normal { index }] => {
            Some((Chain::Internal, *index))
        }
        _ => None,
    }
}

/// The output `input` spends: its parent's, or the `witness_utxo`, which `policy` holds equal to it.
fn prevout<'p>(input: &'p Input, txin: &TxIn) -> Option<&'p TxOut> {
    input
        .non_witness_utxo
        .as_ref()
        .and_then(|parent| parent.output.get(txin.previous_output.vout as usize))
        .or(input.witness_utxo.as_ref())
}

/// The virtual size of the finished transaction, every input at `input_weight`.
fn priced(vault: &Multisig, inputs: usize, outputs: &[TxOut]) -> Result<u64, MobileSignerError> {
    let scripts: Vec<usize> = outputs.iter().map(|out| out.script_pubkey.len()).collect();
    Ok(vsize(&vec![input_weight(vault)?; inputs], &scripts))
}

/// The wallet keys, by position, with a signature on every input. Once `signature` holds, every one of those verifies.
fn signers(psbt: &Psbt, coins: &[(VaultScript, Amount)]) -> BTreeSet<usize> {
    psbt.inputs
        .iter()
        .zip(coins)
        .map(|(input, (script, _))| {
            script
                .keys
                .iter()
                .filter(|(key, _)| {
                    input
                        .partial_sigs
                        .contains_key(&bitcoin::PublicKey::new(*key))
                })
                .map(|(_, position)| *position)
                .collect::<BTreeSet<usize>>()
        })
        .reduce(|a, b| &a & &b)
        .unwrap_or_default()
}

/// The account `restore_software_multisig_key(words, passphrase, Test, 0)` makes for every phone wallet.
// shortcut: account 0 only, as every phone key is made today; read the account from the wallet's BSMS path once a phone holds another.
const PHONE_ACCOUNT: u32 = 0;

/// `checked`'s PSBT signed as wallet key `position` by the words' BIP-48 account key, which must be that key on public key
/// and chain code. The account key is erased before return.
fn signed_by(
    checked: Checked,
    vault: &Multisig,
    position: usize,
    words: &[String],
    passphrase: &str,
) -> Result<Psbt, MobileSignerError> {
    let wanted = vault
        .keys
        .get(position)
        .ok_or_else(|| refuse_key(format!("the wallet has no key {position}")))?;
    let mnemonic = mnemonic_from(words)?;
    let (mut account, xpub) = account_multisig_xpub_from_mnemonic(
        &mnemonic,
        passphrase,
        Network::Testnet4,
        PHONE_ACCOUNT,
        ScriptType::P2wsh,
    )?;
    let signed = if xpub.public_key == wanted.public_key && xpub.chain_code == wanted.chain_code {
        sign_with(checked, &account, position)
    } else {
        Err(refuse_key(format!(
            "the words are not key {position} of the wallet"
        )))
    };
    account.private_key.non_secure_erase();
    signed
}

/// `checked`'s PSBT with wallet key `position`'s signature on every input, by `account`, that key's account key: per input
/// the child at its coin's chain/index signs the coin's P2WSH SIGHASH_ALL digest with `full_size`, so the same PSBT always
/// gets the same bytes, and a signature already there is replaced by the same one. Each child key is erased once used.
fn sign_with(checked: Checked, account: &Xpriv, position: usize) -> Result<Psbt, MobileSignerError> {
    let secp = Secp256k1::new();
    let Checked {
        mut psbt, coins, ..
    } = checked;
    let mut signatures = Vec::with_capacity(coins.len());
    {
        let mut cache = SighashCache::new(&psbt.unsigned_tx);
        for (i, (script, value)) in coins.iter().enumerate() {
            let path = [
                ChildNumber::Normal {
                    index: script.chain as u32,
                },
                ChildNumber::Normal {
                    index: script.index,
                },
            ];
            let mut child = account
                .derive_priv(&secp, &path)
                .map_err(|e| refuse_key(e.to_string()))?;
            let signed = signature_of(&secp, &mut cache, i, script, *value, &child.private_key, position);
            child.private_key.non_secure_erase();
            signatures.push(signed?);
        }
    }
    for (input, (key, signature)) in psbt.inputs.iter_mut().zip(signatures) {
        input.partial_sigs.insert(key, signature);
    }
    Ok(psbt)
}

/// `secret`'s signature of input `i`, refused unless `secret` is wallet key `position`'s child in `script`.
fn signature_of(
    secp: &Secp256k1<All>,
    cache: &mut SighashCache<&Transaction>,
    i: usize,
    script: &VaultScript,
    value: Amount,
    secret: &SecretKey,
    position: usize,
) -> Result<(bitcoin::PublicKey, ecdsa::Signature), MobileSignerError> {
    let key = secret.public_key(secp);
    if !script.keys.contains(&(key, position)) {
        return Err(refuse_key(format!(
            "the key is not key {position}'s child at {} {}",
            script.chain.name(),
            script.index
        )));
    }
    let digest = cache
        .p2wsh_signature_hash(i, &script.witness_script, value, EcdsaSighashType::All)
        .map_err(|e| refusal("signature", e.to_string()))?;
    let signature = full_size(secp, &Message::from_digest(digest.to_byte_array()), secret)?;
    Ok((
        bitcoin::PublicKey::new(key),
        ecdsa::Signature {
            signature,
            sighash_type: EcdsaSighashType::All,
        },
    ))
}

/// The finished transaction: refused unless `threshold` keys signed every input (`vault signatures: k of m`). It takes the
/// `threshold` lowest positions among them and puts in each input's witness an empty element, their signatures in that
/// input's script order, as CHECKMULTISIG walks keys and signatures in step, and the witness script. It refuses bytes
/// heavier than `priced` sized the fee for, since a shorter hardware signature is still a valid one, and a fee past
/// `check_node_rate` on the bytes.
fn assembled(
    checked: Checked,
    vault: &Multisig,
    fee_sats: u64,
) -> Result<Transaction, MobileSignerError> {
    if checked.signed.len() < vault.threshold {
        return Err(MobileSignerError::InvalidPsbtJson(format!(
            "vault signatures: {} of {}",
            checked.signed.len(),
            vault.threshold
        )));
    }
    let chosen: BTreeSet<usize> = checked.signed.iter().take(vault.threshold).copied().collect();
    let mut tx = checked.psbt.unsigned_tx.clone();
    for (i, (txin, (input, (script, _)))) in tx
        .input
        .iter_mut()
        .zip(checked.psbt.inputs.iter().zip(&checked.coins))
        .enumerate()
    {
        let mut stack = vec![Vec::new()];
        for (key, position) in &script.keys {
            if chosen.contains(position) {
                let signature = input
                    .partial_sigs
                    .get(&bitcoin::PublicKey::new(*key))
                    .ok_or_else(|| refusal("signature", format!("input {i} lacks key {position}")))?;
                stack.push(signature.to_vec());
            }
        }
        stack.push(script.witness_script.to_bytes());
        txin.witness = Witness::from_slice(&stack);
    }
    let priced = predict_weight(
        std::iter::repeat_n(input_weight(vault)?, tx.input.len()),
        tx.output.iter().map(|out| out.script_pubkey.len()),
    );
    if tx.weight() > priced {
        return Err(MobileSignerError::InvalidPsbtJson(format!(
            "signed weight {} is above the {} its fee was built for",
            tx.weight(),
            priced
        )));
    }
    check_node_rate(fee_sats, tx.vsize() as u64).map_err(MobileSignerError::InvalidPsbtJson)?;
    Ok(tx)
}

fn parse(base64: &str) -> Result<Psbt, MobileSignerError> {
    Psbt::from_str(base64).map_err(|e| refusal("unreadable", e.to_string()))
}

/// `vault psbt: <rule>: <why>`. The app maps the prefix to one line, and a test reads the rule.
fn refusal(rule: &str, why: String) -> MobileSignerError {
    MobileSignerError::InvalidPsbtJson(format!("vault psbt: {rule}: {why}"))
}

/// The words are not the key the row named, which is the user's pick, not a bad payment, so it has its own prefix.
fn refuse_key(why: String) -> MobileSignerError {
    MobileSignerError::InvalidPsbtJson(format!("vault key: {why}"))
}

#[cfg(test)]
mod tests {
    use std::sync::OnceLock;

    use bitcoin::bip32::{Fingerprint, Xpub};
    use bitcoin::hashes::sha256;
    use bitcoin::psbt::Output;
    use bitcoin::psbt::raw::{Key, ProprietaryKey};
    use bitcoin::script::Instruction;
    use bitcoin::{Address, secp256k1};

    use super::*;

    /// BIP-173's testnet P2WPKH vector, which the payments here pay.
    const DEST: &str = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx";
    /// BIP-173's testnet P2WSH vector, an address of no wallet here.
    const STRANGER: &str = "tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sl5k7";

    /// BIP-39's vectors: the words of the three phone keys of one wallet, then a stranger's.
    const WORDS: [&str; 4] = [
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
        "legal winner thank year wave sausage worth useful legal winner thank yellow",
        "letter advice cage absurd amount doctor acoustic avoid letter advice cage above",
        "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong",
    ];

    fn words(i: usize) -> Vec<String> {
        WORDS[i].split(' ').map(str::to_string).collect()
    }

    /// Each of `WORDS`' account key at m/48'/1'/0'/2' and its tpub, derived once, because a seed costs 2,048 rounds of PBKDF2.
    fn phones() -> &'static [(Xpriv, String); 4] {
        static PHONES: OnceLock<[(Xpriv, String); 4]> = OnceLock::new();
        PHONES.get_or_init(|| {
            std::array::from_fn(|i| {
                let mnemonic = mnemonic_from(&words(i)).unwrap();
                let (account, xpub) = account_multisig_xpub_from_mnemonic(
                    &mnemonic,
                    "",
                    Network::Testnet4,
                    0,
                    ScriptType::P2wsh,
                )
                .unwrap();
                (account, xpub.to_string())
            })
        })
    }

    /// `n` keys from fixed seeds and no words, for the tests that sign often. Each master key stands in for an account key.
    fn bare_keys(n: usize) -> Vec<(Xpriv, String)> {
        let secp = Secp256k1::new();
        (0..n)
            .map(|i| {
                let account = Xpriv::new_master(Network::Testnet4, &[i as u8 + 1; 32]).unwrap();
                (account, Xpub::from_priv(&secp, &account).to_string())
            })
            .collect()
    }

    fn address(text: &str) -> Address {
        Address::from_str(text).unwrap().assume_checked()
    }

    struct Paid {
        vault: Multisig,
        xpubs: Vec<String>,
        held: VaultPsbt,
    }

    /// The `m`-of-`xpubs` wallet paid `coins`, each a place and a value, by one parent, and the PSBT that spends every coin to
    /// `to`, `amount`, with `change` to 1/1 when above 0 and the rest the fee, as `described` holds it for 5.2.
    fn paid_to(
        xpubs: &[String],
        m: u8,
        coins: &[(Chain, u32, u64)],
        to: &str,
        amount: u64,
        change: u64,
    ) -> Paid {
        let vault = Multisig::new(xpubs, m).unwrap();
        let parent = Transaction {
            version: Version::TWO,
            lock_time: LockTime::ZERO,
            input: vec![TxIn {
                previous_output: OutPoint {
                    txid: Txid::from_byte_array([7; 32]),
                    vout: 0,
                },
                ..TxIn::default()
            }],
            output: coins
                .iter()
                .map(|(chain, index, value)| TxOut {
                    value: Amount::from_sat(*value),
                    script_pubkey: vault.address(*chain, *index).unwrap().script_pubkey(),
                })
                .collect(),
        };
        let txid = parent.compute_txid();
        let places: Vec<(OutPoint, Chain, u32)> = coins
            .iter()
            .enumerate()
            .map(|(vout, (chain, index, _))| {
                (
                    OutPoint {
                        txid,
                        vout: vout as u32,
                    },
                    *chain,
                    *index,
                )
            })
            .collect();
        let mut outputs = vec![TxOut {
            value: Amount::from_sat(amount),
            script_pubkey: address(to).script_pubkey(),
        }];
        if change > 0 {
            outputs.push(TxOut {
                value: Amount::from_sat(change),
                script_pubkey: vault.address(Chain::Internal, 1).unwrap().script_pubkey(),
            });
        }
        let fee = coins.iter().map(|(_, _, value)| value).sum::<u64>() - amount - change;
        let parents = HashMap::from([(txid, parent)]);
        let psbt = unsigned(&vault, &places, &parents, outputs, (change > 0).then_some(1)).unwrap();
        let confirmed = Confirmed {
            destination: to,
            amount_sats: amount,
            fee_sats: fee,
            max_change_index: 1,
            txid: None,
        };
        let held = described(&vault, psbt, &confirmed).unwrap();
        Paid {
            vault,
            xpubs: xpubs.to_vec(),
            held,
        }
    }

    fn phone_xpubs() -> Vec<String> {
        phones()[..3].iter().map(|(_, xpub)| xpub.clone()).collect()
    }

    /// The payment every adversarial case starts from: 2-of-3 over the phone keys, receive 0 and change 0 spent, 0.6 BTC to
    /// DEST, change to 1/1 and a 1,000 sat fee.
    fn payment() -> Paid {
        paid_to(
            &phone_xpubs(),
            2,
            &[(Chain::External, 0, 50_000_000), (Chain::Internal, 0, 40_000_000)],
            DEST,
            60_000_000,
            29_999_000,
        )
    }

    /// `held` signed as key `position` by `account`, as `vault_sign_psbt` signs with the words of that key.
    fn sign_as(
        paid: &Paid,
        held: &VaultPsbt,
        account: &Xpriv,
        position: usize,
    ) -> Result<VaultPsbt, MobileSignerError> {
        let confirmed = Confirmed::from(held);
        let checked = checked(&paid.vault, parse(&held.psbt)?, &confirmed)?;
        described(&paid.vault, sign_with(checked, account, position)?, &confirmed)
    }

    fn sign_by_words(paid: &Paid, held: &VaultPsbt, key: u32, words_of: usize) -> Result<VaultPsbt, MobileSignerError> {
        vault_sign_psbt(paid.xpubs.clone(), paid.vault.threshold as u8, held.clone(), key, words(words_of), String::new())
    }

    fn finalize(paid: &Paid, held: &VaultPsbt) -> Result<PreparedSend, MobileSignerError> {
        vault_finalize_psbt(paid.xpubs.clone(), paid.vault.threshold as u8, held.clone())
    }

    fn decoded(held: &VaultPsbt) -> Psbt {
        Psbt::from_str(&held.psbt).unwrap()
    }

    fn tx_of(prepared: &PreparedSend) -> Transaction {
        bitcoin::consensus::encode::deserialize_hex(&prepared.hex).unwrap()
    }

    fn spent(held: &VaultPsbt, i: usize) -> TxOut {
        decoded(held).inputs[i].witness_utxo.clone().unwrap()
    }

    /// CHECKMULTISIG as consensus runs it on input `i` of `tx`, which spends `prevout`: the last witness element is a script
    /// whose P2WSH is the coin's, the first element is empty, and the signatures between, as many as the script's m, verify
    /// in witness order against the script's keys in order over the BIP-143 digest. Shares no code with signing.
    fn spends(tx: &Transaction, i: usize, prevout: &TxOut) -> bool {
        let witness: Vec<&[u8]> = tx.input[i].witness.iter().collect();
        let Some((script, rest)) = witness.split_last() else {
            return false;
        };
        let script = ScriptBuf::from_bytes(script.to_vec());
        if ScriptBuf::new_p2wsh(&script.wscript_hash()) != prevout.script_pubkey {
            return false;
        }
        let Some((empty, signatures)) = rest.split_first() else {
            return false;
        };
        let pushes: Vec<Instruction> = script.instructions().map(Result::unwrap).collect();
        let m = match pushes[0] {
            Instruction::Op(op) => (op.to_u8() - 0x50) as usize,
            Instruction::PushBytes(bytes) => bytes.as_bytes()[0] as usize,
        };
        let keys: Vec<secp256k1::PublicKey> = pushes
            .iter()
            .filter_map(|push| match push {
                Instruction::PushBytes(bytes) if bytes.len() == 33 => {
                    secp256k1::PublicKey::from_slice(bytes.as_bytes()).ok()
                }
                _ => None,
            })
            .collect();
        let digest = SighashCache::new(tx)
            .p2wsh_signature_hash(i, &script, prevout.value, EcdsaSighashType::All)
            .unwrap();
        let message = Message::from_digest(digest.to_byte_array());
        let secp = Secp256k1::verification_only();
        let mut unused = keys.iter();
        empty.is_empty()
            && signatures.len() == m
            && signatures.iter().all(|signature| {
                let Some((&0x01, der)) = signature.split_last() else {
                    return false;
                };
                let Ok(signature) = secp256k1::ecdsa::Signature::from_der(der) else {
                    return false;
                };
                unused.any(|key| secp.verify_ecdsa(&message, &signature, key).is_ok())
            })
    }

    #[test]
    fn the_script_of_every_place_is_the_address_vault_core_derives() {
        let vault = Multisig::new(&phone_xpubs(), 2).unwrap();
        for (chain, index) in [
            (Chain::External, 0),
            (Chain::External, 5),
            (Chain::Internal, 0),
            (Chain::Internal, 300),
        ] {
            let script = vault_script(&vault, chain, index).unwrap();
            let mut positions: Vec<usize> = script.keys.iter().map(|(_, p)| *p).collect();
            positions.sort();
            assert_eq!(positions, [0, 1, 2], "every key once at {chain:?} {index}");
        }
    }

    #[test]
    fn a_2_of_3_input_and_a_payment_with_change_to_a_p2wsh_size_as_counted_by_hand() {
        let vault = Multisig::new(&phone_xpubs(), 2).unwrap();
        // The input is 41 x 4 + (1 + 1 + 2 x 73 + 106) = 418 WU, the rest (4 + 1 + 1 + 2 x 43 + 4) x 4 + 2 = 386 WU.
        assert_eq!(vsize(&[input_weight(&vault).unwrap()], &[34, 34]), 201);
    }

    #[test]
    fn a_phone_key_here_is_the_key_its_wallet_lists() {
        for i in 0..3 {
            let listed = crate::restore_software_multisig_key(words(i), String::new(), KeyNetwork::Test, 0).unwrap();
            assert_eq!(phones()[i].1, listed.xpub, "key {i}");
        }
    }

    #[test]
    fn an_unsigned_payment_has_no_signer_and_the_txid_it_finishes_with() {
        let paid = payment();
        let held = &paid.held;
        assert_eq!(
            (held.signed_keys.len(), held.fee_sats, held.change_sats, held.max_change_index),
            (0, 1_000, 29_999_000, 1)
        );
        assert_eq!(held.vsize, 294, "two 418 WU inputs, a P2WPKH and a P2WSH output");
        let held = sign_as(&paid, held, &phones()[0].0, 0).unwrap();
        let held = sign_as(&paid, &held, &phones()[2].0, 2).unwrap();
        assert_eq!(held.txid, paid.held.txid, "signatures leave the id as it was");
        let prepared = finalize(&paid, &held).unwrap();
        assert_eq!(prepared.txid, paid.held.txid);
        assert_eq!((prepared.fee_sats, prepared.amount_sats), (1_000, 60_000_000));
    }

    #[test]
    fn the_words_of_key_0_then_of_key_2_sign_and_the_record_names_both() {
        let paid = payment();
        let first = sign_by_words(&paid, &paid.held, 0, 0).unwrap();
        assert_eq!(first.signed_keys, [0]);
        let both = sign_by_words(&paid, &first, 2, 2).unwrap();
        assert_eq!(both.signed_keys, [0, 2]);
        assert_eq!(
            (both.destination.as_str(), both.amount_sats, both.fee_sats, both.change_sats),
            (DEST, 60_000_000, 1_000, 29_999_000)
        );
    }

    #[test]
    fn signing_again_with_the_same_key_changes_nothing() {
        let paid = payment();
        let once = sign_as(&paid, &paid.held, &phones()[1].0, 1).unwrap();
        assert_eq!(sign_as(&paid, &once, &phones()[1].0, 1).unwrap(), once);
    }

    #[test]
    fn keys_signing_in_either_order_finish_as_the_same_bytes() {
        let paid = payment();
        let (zero, two) = (&phones()[0].0, &phones()[2].0);
        let ascending = sign_as(&paid, &sign_as(&paid, &paid.held, zero, 0).unwrap(), two, 2).unwrap();
        let descending = sign_as(&paid, &sign_as(&paid, &paid.held, two, 2).unwrap(), zero, 0).unwrap();
        assert_eq!(ascending, descending);
        assert_eq!(finalize(&paid, &ascending).unwrap(), finalize(&paid, &descending).unwrap());
    }

    #[test]
    fn every_signature_is_72_bytes_with_its_sighash_byte() {
        let paid = payment();
        let mut held = paid.held.clone();
        for (position, (account, _)) in phones()[..3].iter().enumerate() {
            held = sign_as(&paid, &held, account, position).unwrap();
        }
        let lengths: Vec<usize> = decoded(&held)
            .inputs
            .iter()
            .flat_map(|input| input.partial_sigs.values().map(|s| s.to_vec().len()))
            .collect();
        assert_eq!(lengths, [72; 6]);
    }

    #[test]
    fn finishing_2_of_3_puts_two_signatures_in_script_order_and_weighs_what_was_priced() {
        let paid = payment();
        let held = sign_as(&paid, &paid.held, &phones()[2].0, 2).unwrap();
        let held = sign_as(&paid, &held, &phones()[0].0, 0).unwrap();
        let tx = tx_of(&finalize(&paid, &held).unwrap());
        for i in 0..2 {
            assert_eq!(tx.input[i].witness.len(), 4, "empty, two signatures, the script");
            assert!(spends(&tx, i, &spent(&held, i)), "input {i}");
        }
        assert_eq!(tx.vsize() as u64, held.vsize);
    }

    #[test]
    fn the_signatures_in_a_witness_follow_its_scripts_key_order() {
        let paid = payment();
        let held = sign_as(&paid, &paid.held, &phones()[0].0, 0).unwrap();
        let held = sign_as(&paid, &held, &phones()[1].0, 1).unwrap();
        let mut tx = tx_of(&finalize(&paid, &held).unwrap());
        let mut elements: Vec<Vec<u8>> = tx.input[0].witness.iter().map(<[u8]>::to_vec).collect();
        elements.swap(1, 2);
        tx.input[0].witness = Witness::from_slice(&elements);
        assert!(!spends(&tx, 0, &spent(&held, 0)), "CHECKMULTISIG takes signatures in its keys' order only");
    }

    #[test]
    fn finishing_with_all_three_signed_takes_the_two_lowest_positions() {
        let paid = payment();
        let mut held = paid.held.clone();
        for (position, (account, _)) in phones()[..3].iter().enumerate() {
            held = sign_as(&paid, &held, account, position).unwrap();
        }
        let tx = tx_of(&finalize(&paid, &held).unwrap());
        let key_2 = decoded(&held).inputs[0]
            .partial_sigs
            .iter()
            .find(|(key, _)| vault_script(&paid.vault, Chain::External, 0).unwrap().keys.contains(&(key.inner, 2)))
            .map(|(_, signature)| signature.to_vec())
            .unwrap();
        assert_eq!(tx.input[0].witness.len(), 4);
        assert!(tx.input[0].witness.iter().all(|element| element != key_2.as_slice()), "key 2 is left out");
        assert!(spends(&tx, 0, &spent(&held, 0)));
    }

    #[test]
    fn finishing_below_the_threshold_is_refused_with_how_many_signed() {
        let paid = payment();
        assert_eq!(
            finalize(&paid, &paid.held).unwrap_err().to_string(),
            "invalid PSBT json: vault signatures: 0 of 2"
        );
        let held = sign_as(&paid, &paid.held, &phones()[1].0, 1).unwrap();
        assert_eq!(
            finalize(&paid, &held).unwrap_err().to_string(),
            "invalid PSBT json: vault signatures: 1 of 2"
        );
    }

    #[test]
    fn a_1_of_1_and_a_3_of_3_wallet_sign_and_finish() {
        let keys = bare_keys(3);
        for n in [1, 3] {
            let xpubs: Vec<String> = keys[..n].iter().map(|(_, xpub)| xpub.clone()).collect();
            let paid = paid_to(&xpubs, n as u8, &[(Chain::External, 0, 1_000_000)], DEST, 600_000, 399_000);
            let mut held = paid.held.clone();
            for (position, (account, _)) in keys[..n].iter().enumerate() {
                held = sign_as(&paid, &held, account, position).unwrap();
            }
            let tx = tx_of(&finalize(&paid, &held).unwrap());
            assert!(spends(&tx, 0, &spent(&held, 0)), "{n}-of-{n}");
        }
    }

    #[test]
    fn a_key_that_signed_one_input_only_is_not_a_signer() {
        let paid = payment();
        let held = sign_as(&paid, &paid.held, &phones()[0].0, 0).unwrap();
        let mut psbt = decoded(&held);
        psbt.inputs[1].partial_sigs.clear();
        let record = described(&paid.vault, psbt, &Confirmed::from(&held)).unwrap();
        assert_eq!(record.signed_keys, Vec::<u32>::new());
    }

    #[test]
    fn words_outside_the_wallet_are_refused_as_the_wrong_key() {
        let paid = payment();
        let refused = sign_by_words(&paid, &paid.held, 0, 3).unwrap_err().to_string();
        assert!(refused.starts_with("invalid PSBT json: vault key: "), "{refused}");
    }

    #[test]
    fn key_1s_words_signing_as_key_0_are_refused() {
        let paid = payment();
        let refused = sign_by_words(&paid, &paid.held, 0, 1).unwrap_err().to_string();
        assert!(refused.starts_with("invalid PSBT json: vault key: "), "{refused}");
    }

    #[test]
    fn a_key_index_past_the_wallet_is_refused() {
        let paid = payment();
        assert_eq!(
            sign_by_words(&paid, &paid.held, 3, 0).unwrap_err().to_string(),
            "invalid PSBT json: vault key: the wallet has no key 3"
        );
    }

    #[test]
    fn the_wallets_public_key_under_another_chain_code_is_another_key() {
        let mut key = Xpub::from_str(&phones()[0].1).unwrap();
        key.chain_code = bitcoin::bip32::ChainCode::from([9; 32]);
        let mut xpubs = phone_xpubs();
        xpubs[0] = key.to_string();
        let paid = paid_to(&xpubs, 2, &[(Chain::External, 0, 1_000_000)], DEST, 600_000, 399_000);
        let refused = sign_by_words(&paid, &paid.held, 0, 0).unwrap_err().to_string();
        assert!(refused.starts_with("invalid PSBT json: vault key: "), "{refused}");
    }

    #[test]
    fn mainnet_keys_are_refused_before_the_psbt_is_read() {
        let mainnet: Vec<String> = (0..3)
            .map(|i| crate::restore_software_multisig_key(words(i), String::new(), KeyNetwork::Main, 0).unwrap().xpub)
            .collect();
        let mut held = payment().held;
        held.psbt = "not a psbt".into();
        for refused in [
            vault_sign_psbt(mainnet.clone(), 2, held.clone(), 0, words(0), String::new()).map(drop),
            vault_finalize_psbt(mainnet, 2, held).map(drop),
        ] {
            assert_eq!(
                refused.unwrap_err().to_string(),
                "invalid PSBT json: mainnet Esplora is not enabled in this build"
            );
        }
    }

    #[test]
    fn a_psbt_that_is_not_one_is_refused_as_unreadable() {
        let paid = payment();
        let mut held = paid.held.clone();
        held.psbt = "cHNidP8=".into();
        for refused in [sign_by_words(&paid, &held, 0, 0).map(drop), finalize(&paid, &held).map(drop)] {
            let said = refused.unwrap_err().to_string();
            assert!(said.starts_with("invalid PSBT json: vault psbt: unreadable: "), "{said}");
        }
    }

    /// Input `i`'s P2WSH digest under `sighash`, by the script and value its PSBT map carries.
    fn digest(psbt: &Psbt, i: usize, sighash: EcdsaSighashType) -> Message {
        let script = psbt.inputs[i].witness_script.clone().unwrap();
        let value = psbt.inputs[i].witness_utxo.as_ref().unwrap().value;
        let hash = SighashCache::new(&psbt.unsigned_tx)
            .p2wsh_signature_hash(i, &script, value, sighash)
            .unwrap();
        Message::from_digest(hash.to_byte_array())
    }

    /// `account`'s child at receive 0 signing `message`, labelled `sighash`.
    fn partial(account: &Xpriv, message: &Message, sighash: EcdsaSighashType) -> (bitcoin::PublicKey, ecdsa::Signature) {
        let secp = Secp256k1::new();
        let path = [ChildNumber::Normal { index: 0 }, ChildNumber::Normal { index: 0 }];
        let child = account.derive_priv(&secp, &path).unwrap();
        let signature = secp.sign_ecdsa(message, &child.private_key);
        (
            bitcoin::PublicKey::new(child.private_key.public_key(&secp)),
            ecdsa::Signature { signature, sighash_type: sighash },
        )
    }

    /// Input 0 spends output 0 of a new parent paying `script` what its coin held, its outpoint, parent and witness_utxo
    /// agreeing, so the script is all that differs.
    fn repaid(psbt: &mut Psbt, script: ScriptBuf) {
        let value = psbt.inputs[0].witness_utxo.as_ref().unwrap().value;
        let parent = Transaction {
            version: Version::TWO,
            lock_time: LockTime::ZERO,
            input: vec![TxIn {
                previous_output: OutPoint {
                    txid: Txid::from_byte_array([9; 32]),
                    vout: 0,
                },
                ..TxIn::default()
            }],
            output: vec![TxOut { value, script_pubkey: script }],
        };
        psbt.unsigned_tx.input[0].previous_output = OutPoint {
            txid: parent.compute_txid(),
            vout: 0,
        };
        psbt.inputs[0].witness_utxo = Some(parent.output[0].clone());
        psbt.inputs[0].non_witness_utxo = Some(parent);
    }

    fn change_place(psbt: &mut Psbt, vault: &Multisig, chain: Chain, index: u32) {
        let script = vault_script(vault, chain, index).unwrap();
        psbt.unsigned_tx.output[1].script_pubkey = script.script_pubkey();
        psbt.outputs[1] = Output {
            bip32_derivation: script.derivation(vault),
            witness_script: Some(script.witness_script),
            ..Output::default()
        };
    }

    fn sats(amount: &mut Amount, by: i64) {
        *amount = Amount::from_sat(amount.to_sat().checked_add_signed(by).unwrap());
    }

    struct Case {
        what: &'static str,
        rule: &'static str,
        edit: fn(&Paid, &mut Psbt, &mut VaultPsbt),
    }

    /// One way each to break each rule of `checked` on the payment of `payment()`, by its PSBT or by the record of what was
    /// confirmed, and the rule that must refuse it. Every other part stays valid, so a refusal by another rule is a miss.
    fn cases() -> Vec<Case> {
        vec![
            Case { what: "an input asking SIGHASH_NONE", rule: "policy", edit: |_, p, _| p.inputs[0].sighash_type = Some(EcdsaSighashType::None.into()) },
            Case { what: "an input asking ALL|ANYONECANPAY", rule: "policy", edit: |_, p, _| p.inputs[0].sighash_type = Some(EcdsaSighashType::AllPlusAnyoneCanPay.into()) },
            Case {
                what: "a wallet key's signature labelled SIGHASH_SINGLE",
                rule: "policy",
                edit: |_, p, _| {
                    let (key, signature) = partial(&phones()[0].0, &digest(p, 0, EcdsaSighashType::Single), EcdsaSighashType::Single);
                    p.inputs[0].partial_sigs.insert(key, signature);
                },
            },
            Case { what: "a parent that is not the outpoint's transaction", rule: "policy", edit: |_, p, _| p.inputs[0].non_witness_utxo.as_mut().unwrap().lock_time = LockTime::from_consensus(1) },
            Case { what: "a witness_utxo its parent does not pay", rule: "policy", edit: |_, p, _| sats(&mut p.inputs[0].witness_utxo.as_mut().unwrap().value, 1) },
            Case { what: "version 1", rule: "shape", edit: |_, p, _| p.unsigned_tx.version = Version::ONE },
            Case { what: "a lock time", rule: "shape", edit: |_, p, _| p.unsigned_tx.lock_time = LockTime::from_consensus(1) },
            Case { what: "a final sequence", rule: "shape", edit: |_, p, _| p.unsigned_tx.input[0].sequence = Sequence::MAX },
            Case {
                what: "the same coin spent twice",
                rule: "shape",
                edit: |_, p, _| {
                    let (txin, input) = (p.unsigned_tx.input[0].clone(), p.inputs[0].clone());
                    p.unsigned_tx.input.push(txin);
                    p.inputs.push(input);
                },
            },
            Case { what: "a final script witness", rule: "shape", edit: |_, p, _| p.inputs[0].final_script_witness = Some(Witness::from_slice(&[[1u8]])) },
            Case { what: "a redeem script", rule: "shape", edit: |_, p, _| p.inputs[0].redeem_script = Some(ScriptBuf::new()) },
            Case { what: "an input without its parent", rule: "parent", edit: |_, p, _| p.inputs[0].non_witness_utxo = None },
            Case { what: "a coin paid to a stranger", rule: "coin", edit: |_, p, _| repaid(p, address(STRANGER).script_pubkey()) },
            Case {
                what: "a coin locked by 1-of-3 of the wallet's own keys",
                rule: "coin",
                edit: |_, p, _| {
                    let weaker = p.inputs[0]
                        .bip32_derivation
                        .keys()
                        .fold(Builder::new().push_int(1), |s, key| s.push_key(&bitcoin::PublicKey::new(*key)))
                        .push_int(3)
                        .push_opcode(OP_CHECKMULTISIG)
                        .into_script();
                    repaid(p, ScriptBuf::new_p2wsh(&weaker.wscript_hash()));
                    p.inputs[0].witness_script = Some(weaker);
                },
            },
            Case {
                what: "entries naming index 4 for the coin at 0",
                rule: "coin",
                edit: |_, p, _| {
                    for (_, path) in p.inputs[0].bip32_derivation.values_mut() {
                        *path = DerivationPath::from(vec![ChildNumber::Normal { index: 0 }, ChildNumber::Normal { index: 4 }]);
                    }
                },
            },
            Case {
                what: "entries naming the change chain for a receive coin",
                rule: "coin",
                edit: |_, p, _| {
                    for (_, path) in p.inputs[0].bip32_derivation.values_mut() {
                        *path = DerivationPath::from(vec![ChildNumber::Normal { index: 1 }, ChildNumber::Normal { index: 0 }]);
                    }
                },
            },
            Case {
                what: "entries short of a wallet key",
                rule: "coin",
                edit: |_, p, _| {
                    let key = *p.inputs[0].bip32_derivation.keys().last().unwrap();
                    p.inputs[0].bip32_derivation.remove(&key);
                },
            },
            Case {
                what: "an entry for a stranger's key",
                rule: "coin",
                edit: |_, p, _| {
                    let (key, _) = partial(&phones()[3].0, &Message::from_digest([1; 32]), EcdsaSighashType::All);
                    let path = DerivationPath::from(vec![ChildNumber::Normal { index: 0 }, ChildNumber::Normal { index: 0 }]);
                    p.inputs[0].bip32_derivation.insert(key.inner, (Fingerprint::default(), path));
                },
            },
            Case { what: "no witness script", rule: "coin", edit: |_, p, _| p.inputs[0].witness_script = None },
            Case { what: "no derivation entry", rule: "coin", edit: |_, p, _| p.inputs[0].bip32_derivation.clear() },
            Case { what: "the destination swapped", rule: "outputs", edit: |_, p, _| p.unsigned_tx.output[0].script_pubkey = address(STRANGER).script_pubkey() },
            Case {
                what: "a sat more to the destination",
                rule: "outputs",
                edit: |_, p, _| {
                    sats(&mut p.unsigned_tx.output[0].value, 1);
                    sats(&mut p.unsigned_tx.output[1].value, -1);
                },
            },
            Case {
                what: "a sat less to the destination",
                rule: "outputs",
                edit: |_, p, _| {
                    sats(&mut p.unsigned_tx.output[0].value, -1);
                    sats(&mut p.unsigned_tx.output[1].value, 1);
                },
            },
            Case {
                what: "a third output",
                rule: "outputs",
                edit: |_, p, _| {
                    p.unsigned_tx.output.push(TxOut { value: Amount::from_sat(1_000), script_pubkey: address(DEST).script_pubkey() });
                    p.outputs.push(Output::default());
                },
            },
            Case { what: "change to the receive chain", rule: "outputs", edit: |paid, p, _| change_place(p, &paid.vault, Chain::External, 5) },
            Case {
                what: "change to another wallet's change address",
                rule: "outputs",
                edit: |_, p, _| {
                    let other = Multisig::new(&[phones()[0].1.clone(), phones()[1].1.clone(), phones()[3].1.clone()], 2).unwrap();
                    change_place(p, &other, Chain::Internal, 1);
                },
            },
            Case {
                what: "change to a stranger",
                rule: "outputs",
                edit: |_, p, _| {
                    p.unsigned_tx.output[1].script_pubkey = address(STRANGER).script_pubkey();
                    p.outputs[1] = Output::default();
                },
            },
            Case { what: "change past the index the wallet handed out", rule: "outputs", edit: |_, _, held| held.max_change_index = 0 },
            Case { what: "change without its witness script", rule: "outputs", edit: |_, p, _| p.outputs[1].witness_script = None },
            Case { what: "the record naming another destination", rule: "outputs", edit: |_, _, held| held.destination = STRANGER.into() },
            Case { what: "the record naming another amount", rule: "outputs", edit: |_, _, held| held.amount_sats += 1 },
            Case {
                what: "change under the P2WSH dust floor",
                rule: "dust",
                edit: |_, p, held| {
                    held.fee_sats += p.unsigned_tx.output[1].value.to_sat() - 329;
                    p.unsigned_tx.output[1].value = Amount::from_sat(329);
                },
            },
            Case {
                what: "an amount under the destination's dust floor",
                rule: "dust",
                edit: |_, p, held| {
                    let moved = p.unsigned_tx.output[0].value.to_sat() - 293;
                    sats(&mut p.unsigned_tx.output[1].value, moved as i64);
                    p.unsigned_tx.output[0].value = Amount::from_sat(293);
                    held.amount_sats = 293;
                },
            },
            Case { what: "the record's fee a sat off", rule: "fee", edit: |_, _, held| held.fee_sats += 1 },
            Case { what: "a fee raised by cutting the change", rule: "fee", edit: |_, p, _| sats(&mut p.unsigned_tx.output[1].value, -1_000) },
            Case {
                what: "a fee past 10,000 sat/vB",
                rule: "fee",
                edit: |_, p, held| {
                    held.fee_sats += p.unsigned_tx.output.pop().unwrap().value.to_sat();
                    p.outputs.pop();
                },
            },
            Case {
                what: "a fee under 1 sat/vB",
                rule: "fee",
                edit: |_, p, held| {
                    sats(&mut p.unsigned_tx.output[1].value, 900);
                    held.fee_sats -= 900;
                },
            },
            Case { what: "outputs past the inputs", rule: "fee", edit: |_, p, _| sats(&mut p.unsigned_tx.output[1].value, 1_000_000_000) },
            Case { what: "the record naming another txid", rule: "txid", edit: |_, _, held| held.txid = "00".repeat(32) },
            Case {
                what: "a stranger's signature",
                rule: "signature",
                edit: |_, p, _| {
                    let (key, signature) = partial(&phones()[3].0, &digest(p, 0, EcdsaSighashType::All), EcdsaSighashType::All);
                    p.inputs[0].partial_sigs.insert(key, signature);
                },
            },
            Case {
                what: "a wallet key's signature of another input",
                rule: "signature",
                edit: |_, p, _| {
                    let (key, signature) = partial(&phones()[0].0, &digest(p, 1, EcdsaSighashType::All), EcdsaSighashType::All);
                    p.inputs[0].partial_sigs.insert(key, signature);
                },
            },
            Case {
                what: "a wallet key's signature under its uncompressed key",
                rule: "signature",
                edit: |_, p, _| {
                    let (mut key, signature) = partial(&phones()[0].0, &digest(p, 0, EcdsaSighashType::All), EcdsaSighashType::All);
                    key.compressed = false;
                    p.inputs[0].partial_sigs.insert(key, signature);
                },
            },
            Case { what: "an explicit SIGHASH_ALL field", rule: "canonical", edit: |_, p, _| p.inputs[0].sighash_type = Some(EcdsaSighashType::All.into()) },
            Case {
                what: "a proprietary key on an input",
                rule: "canonical",
                edit: |_, p, _| {
                    p.inputs[0].proprietary.insert(ProprietaryKey { prefix: b"mebit".to_vec(), subtype: 0, key: vec![] }, vec![1]);
                },
            },
            Case {
                what: "an unknown global key",
                rule: "canonical",
                edit: |_, p, _| {
                    p.unknown.insert(Key { type_value: 0xf0, key: vec![] }, vec![1]);
                },
            },
            Case {
                what: "a global xpub",
                rule: "canonical",
                edit: |_, p, _| {
                    p.xpub.insert(Xpub::from_str(&phones()[0].1).unwrap(), (Fingerprint::default(), DerivationPath::master()));
                },
            },
            Case { what: "a witness script on the destination", rule: "canonical", edit: |_, p, _| p.outputs[0].witness_script = Some(ScriptBuf::new()) },
            Case {
                what: "a hash preimage on an input",
                rule: "canonical",
                edit: |_, p, _| {
                    p.inputs[0].sha256_preimages.insert(sha256::Hash::hash(b"x"), b"x".to_vec());
                },
            },
            Case { what: "the parent without its witness_utxo", rule: "canonical", edit: |_, p, _| p.inputs[0].witness_utxo = None },
        ]
    }

    #[test]
    fn every_way_a_payment_can_differ_from_what_was_confirmed_is_refused_by_sign_and_finish_naming_its_rule() {
        let base = payment();
        let control = sign_by_words(&base, &base.held, 0, 0).map(drop);
        assert!(control.is_ok(), "the unedited payment signs: {control:?}");
        let mut wrong = Vec::new();
        let cases = cases();
        for case in &cases {
            let mut psbt = decoded(&base.held);
            let mut held = base.held.clone();
            (case.edit)(&base, &mut psbt, &mut held);
            held.psbt = psbt.to_string();
            let refusal = format!("vault psbt: {}: ", case.rule);
            for (call, outcome) in [
                ("sign", sign_by_words(&base, &held, 0, 0).map(drop)),
                ("finalize", finalize(&base, &held).map(drop)),
            ] {
                let said = outcome.err().map_or("accepted".into(), |e| e.to_string());
                if !said.contains(&refusal) {
                    wrong.push(format!("{} ({call}): expected {}, got {said}", case.what, case.rule));
                }
            }
        }
        assert!(wrong.is_empty(), "{} of {} cases missed:\n{}", wrong.len() / 2, cases.len(), wrong.join("\n"));
    }

    /// Every sequence of up to `DEPTH` more steps from `held`, each a sign as key 0, 1 or 2, or as a stranger in key 0's row:
    /// the stranger is refused, and a finish is out exactly when m distinct keys signed. Counts the sequences visited.
    fn walk(paid: &Paid, keys: &[(Xpriv, String)], held: &VaultPsbt, signed: &BTreeSet<usize>, depth: usize) -> usize {
        const DEPTH: usize = 3;
        let finished = finalize(paid, held);
        assert_eq!(
            finished.is_ok(),
            signed.len() >= paid.vault.threshold,
            "signed {signed:?} at m {}: {finished:?}",
            paid.vault.threshold
        );
        if let Ok(prepared) = &finished {
            assert!(spends(&tx_of(prepared), 0, &spent(held, 0)), "signed {signed:?}");
        }
        if depth == DEPTH {
            return 1;
        }
        let mut visited = 1;
        for step in 0..4 {
            match sign_as(paid, held, &keys[step].0, step % 3) {
                Ok(next) => {
                    assert!(step < 3, "a stranger signed");
                    let mut now = signed.clone();
                    now.insert(step);
                    assert_eq!(next.signed_keys, now.iter().map(|p| *p as u32).collect::<Vec<_>>());
                    visited += walk(paid, keys, &next, &now, depth + 1);
                }
                Err(e) => {
                    assert!(step == 3 && e.to_string().contains("vault key: "), "{e}");
                    visited += walk(paid, keys, held, signed, depth + 1);
                }
            }
        }
        visited
    }

    #[test]
    fn finishing_succeeds_exactly_when_m_distinct_keys_signed() {
        let keys = bare_keys(4);
        let xpubs: Vec<String> = keys[..3].iter().map(|(_, xpub)| xpub.clone()).collect();
        let mut visited = 0;
        for m in 1..=3 {
            let paid = paid_to(&xpubs, m, &[(Chain::External, 0, 1_000_000)], DEST, 600_000, 399_000);
            visited += walk(&paid, &keys, &paid.held, &BTreeSet::new(), 0);
        }
        assert_eq!(visited, 3 * (1 + 4 + 16 + 64), "sequence walk stopped early");
    }

    #[test]
    fn the_size_priced_for_every_m_of_n_up_to_20_is_the_size_of_its_finished_bytes() {
        let keys = bare_keys(20);
        for n in 1..=20 {
            let xpubs: Vec<String> = keys[..n].iter().map(|(_, xpub)| xpub.clone()).collect();
            for m in 1..=n {
                let vault = Multisig::new(&xpubs, m as u8).unwrap();
                let script = vault_script(&vault, Chain::External, 0).unwrap();
                let mut witness = vec![Vec::new()];
                witness.extend(std::iter::repeat_n(vec![0x30; 72], m));
                witness.push(script.witness_script.to_bytes());
                let tx = Transaction {
                    version: Version::TWO,
                    lock_time: LockTime::ZERO,
                    input: vec![TxIn {
                        witness: Witness::from_slice(&witness),
                        ..TxIn::default()
                    }],
                    output: vec![
                        TxOut {
                            value: Amount::ZERO,
                            script_pubkey: script.script_pubkey(),
                        };
                        2
                    ],
                };
                assert_eq!(
                    tx.vsize() as u64,
                    vsize(&[input_weight(&vault).unwrap()], &[34, 34]),
                    "{m}-of-{n}"
                );
            }
        }
    }

    #[test]
    fn a_17_of_20_wallet_signs_and_finishes_bytes_consensus_takes() {
        let keys = bare_keys(20);
        let xpubs: Vec<String> = keys.iter().map(|(_, xpub)| xpub.clone()).collect();
        let paid = paid_to(&xpubs, 17, &[(Chain::External, 0, 1_000_000)], DEST, 600_000, 390_000);
        let mut held = paid.held.clone();
        for (position, (account, _)) in keys.iter().enumerate().rev().take(17) {
            held = sign_as(&paid, &held, account, position).unwrap();
        }
        let tx = tx_of(&finalize(&paid, &held).unwrap());
        assert!(spends(&tx, 0, &spent(&held, 0)), "two-byte pushes of 17 and 20");
        assert_eq!(tx.vsize() as u64, held.vsize);
    }
}
