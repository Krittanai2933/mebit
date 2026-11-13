//! A payment from a wallet the book describes, as one PSBT: drafted here, signed elsewhere (a device, or later
//! a key on this phone), read again at every step, and finished here into the bytes `hot_broadcast` posts. The
//! PSBT is the whole state of the payment, so every call reads it again against the descriptor and trusts
//! nothing it claims. Its shape is the one wallet-e2e/wallet.rs `spend_psbt` proved on the Jade and the Safe 7.

use std::collections::{BTreeSet, HashSet};
use std::str::FromStr;
use std::time::SystemTime;

use bitcoin::absolute::LockTime;
use bitcoin::bip32::{Fingerprint, Xpriv, Xpub};
use bitcoin::key::TapTweak;
use bitcoin::psbt::{self, Psbt};
use bitcoin::secp256k1::{Message, Secp256k1};
use bitcoin::sighash::{EcdsaSighashType, Prevouts, SighashCache, TapSighashType};
use bitcoin::transaction::{Version, predict_weight};
use bitcoin::{Address, Network, OutPoint, ScriptBuf, Sequence, Transaction, TxIn, TxOut, Witness};
use miniscript::psbt::PsbtExt;

use crate::chain::{ChainSource, EsploraSource, check_network};
use crate::hot_wallet::{
    AddressSource, Chain, Plan, PreparedSend, SCAN_STORE, SendAmount, Signed, check_node_rate,
    checked, complete, mnemonic_from, plan_from, prepared, proven_parents, testnet4,
};
use crate::scan::GAP;
use crate::scan_store::ScanStore;
use crate::script::{Derived, Described};
use crate::{KeyNetwork, MobileSignerError};

/// A payment of a described wallet as its PSBT says: what it pays and who has signed it.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct SpendReview {
    /// Base64 BIP-174, the whole state of the payment: the next signer and `wallet_finish` take it as it is.
    pub psbt: String,
    /// The id of the unsigned transaction. Every signature commits to it, and `wallet_finish` refuses a PSBT
    /// whose id is another. It is also the sent transaction's id when every input is native SegWit or
    /// Taproot; a Legacy or Nested SegWit signature goes into the transaction itself, so that id differs.
    pub unsigned_txid: String,
    /// Every output that is not this wallet's change, in order. A draft's first is the destination.
    pub payments: Vec<SpendOutput>,
    /// This wallet's change, 0 for none.
    pub change_sats: u64,
    /// The inputs, as their parent transactions prove them, less the outputs.
    pub fee_sats: u64,
    /// The master fingerprints, 8 lowercase hex, of the wallet's keys with a valid signature on every input,
    /// each once, in the descriptor's order.
    pub signed: Vec<String>,
    /// How many keys must sign: 1 for one key.
    pub needed: u8,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct SpendOutput {
    pub address: String,
    pub sats: u64,
}

/// Scans the wallet `descriptor` describes, plans a payment of `amount_sats`, or of the whole balance when it
/// is null, to `destination` at `fee_rate_sat_vb` as `hot_quote_send` plans one, and returns the unsigned PSBT
/// with what it pays. Signs nothing and posts nothing.
#[uniffi::export]
pub fn wallet_draft(
    descriptor: String,
    network: KeyNetwork,
    destination: String,
    amount_sats: Option<u64>,
    fee_rate_sat_vb: u64,
) -> Result<SpendReview, MobileSignerError> {
    draft(
        &EsploraSource(&testnet4()),
        &descriptor,
        network,
        &destination,
        amount_sats.map_or(SendAmount::All, SendAmount::Exact),
        fee_rate_sat_vb,
        &SystemTime::now,
        &SCAN_STORE,
    )
}

/// What `psbt` pays and which keys of the wallet `descriptor` describes have signed it, once it is a payment
/// of that wallet. Its change must sit where this phone's scan of the wallet looks, at most 20 indices past the
/// last change proven used, or below 20 while none is. Asks nothing of the network.
#[uniffi::export]
pub fn wallet_review(
    descriptor: String,
    network: KeyNetwork,
    psbt: String,
) -> Result<SpendReview, MobileSignerError> {
    check_network(network)?;
    review(&Described::parse(&descriptor)?, &parse(&psbt)?, &SCAN_STORE)
}

/// The signed transaction for `hot_broadcast`, once `psbt` still pays what `unsigned_txid` named and at least
/// the needed number of distinct keys of the wallet `descriptor` describes have signed every input. Its
/// `amount_sats` is what every output but this wallet's change pays.
#[uniffi::export]
pub fn wallet_finish(
    descriptor: String,
    network: KeyNetwork,
    psbt: String,
    unsigned_txid: String,
) -> Result<PreparedSend, MobileSignerError> {
    check_network(network)?;
    let wallet = Described::parse(&descriptor)?;
    let shown = as_shown(&wallet, parse(&psbt)?, &unsigned_txid, &SCAN_STORE)?;
    let signed = finished(&wallet, &shown)?;
    Ok(PreparedSend {
        // `prepared` reads output 0, and a reviewed payment may put its change there.
        amount_sats: shown.review.payments.iter().map(|p| p.sats).sum(),
        ..prepared(signed)
    })
}

/// Signs `psbt`, a payment of the wallet `descriptor` describes that the user was shown as `expected_txid`, with the
/// key of that wallet the phrase `mnemonic_words` and `passphrase` make, as a device signs one
/// (`WalletLink::sign_wallet_psbt`): refused before any signature when it is not that payment or the phrase makes no
/// key of the wallet, back as it is when that key already signed, and refused unless that key then signed every
/// input. A key of several kept on this phone signs through it, and so does a debug build's stand-in for a device.
#[uniffi::export]
pub fn wallet_sign_with_words(
    descriptor: String,
    network: KeyNetwork,
    psbt: String,
    expected_txid: String,
    mnemonic_words: Vec<String>,
    passphrase: String,
) -> Result<SpendReview, MobileSignerError> {
    check_network(network)?;
    let wallet = Described::parse(&descriptor)?;
    let shown = as_shown(&wallet, parse(&psbt)?, &expected_txid, &SCAN_STORE)?;
    signed_with_words(&wallet, shown, &mnemonic_words, &passphrase, &SCAN_STORE)
        .map(Shown::into_review)
}

pub(crate) fn signed_with_words(
    wallet: &Described,
    shown: Shown,
    words: &[String],
    passphrase: &str,
    store: &ScanStore,
) -> Result<Shown, MobileSignerError> {
    let secp = Secp256k1::new();
    let master = Xpriv::new_master(
        Network::Testnet4,
        &mnemonic_from(words)?.to_seed(passphrase),
    )
    .map_err(|e| MobileSignerError::InvalidPsbtJson(e.to_string()))?;
    let fingerprint = master.fingerprint(&secp);
    // As a device's key is checked: the fingerprint, and the account key at the wallet key's own path.
    let held = wallet.keys().iter().any(|key| {
        key.fingerprint == fingerprint
            && master.derive_priv(&secp, &key.path).is_ok_and(|account| {
                let xpub = Xpub::from_priv(&secp, &account);
                (xpub.public_key, xpub.chain_code) == (key.xpub.public_key, key.xpub.chain_code)
            })
    });
    if !held {
        return Err(MobileSignerError::InvalidPsbtJson(format!(
            "not a key of this wallet: the phrase's master fingerprint {fingerprint} holds none of its keys"
        )));
    }
    let fingerprint = fingerprint.to_string();
    if shown.review.signed.contains(&fingerprint) {
        return Ok(shown);
    }
    let Shown { mut psbt, review } = shown;
    psbt.sign(&master, &secp).map_err(|(_, errors)| {
        MobileSignerError::InvalidPsbtJson(format!(
            "signatures: the phrase's key did not sign: {errors:?}"
        ))
    })?;
    let after = as_shown(wallet, psbt, &review.unsigned_txid, store)?;
    if !after.review.signed.contains(&fingerprint) {
        return Err(MobileSignerError::InvalidPsbtJson(format!(
            "signatures: key {fingerprint} has not signed every input"
        )));
    }
    Ok(after)
}

pub(crate) fn parse(psbt: &str) -> Result<Psbt, MobileSignerError> {
    Psbt::from_str(psbt).map_err(|e| not_ours(format!("it is not a PSBT: {e}")))
}

fn not_ours(why: String) -> MobileSignerError {
    MobileSignerError::InvalidPsbtJson(format!("not this wallet's payment: {why}"))
}

/// The refusals of a phone payment before any lookup, the scan, the coin lists and the plan, all as the phone
/// wallet makes them, then the PSBT of that plan, read back by `review` as the answer.
#[allow(clippy::too_many_arguments)]
pub(crate) fn draft(
    source: &dyn ChainSource,
    descriptor: &str,
    network: KeyNetwork,
    destination: &str,
    amount: SendAmount,
    rate_sat_vb: u64,
    clock: &dyn Fn() -> SystemTime,
    store: &ScanStore,
) -> Result<SpendReview, MobileSignerError> {
    let wallet = Described::parse(descriptor)?;
    let own = wallet.spend_weight();
    let dest = checked(destination, network, own, amount, rate_sat_vb)?;
    check_network(network)?;
    let scanned = complete(wallet.synced(source, network, clock, store)?)?;
    let change = scanned.next_unused(Chain::Internal).index;
    plan_from(source, &scanned, own, &dest, amount, rate_sat_vb, |plan| {
        let read = review(&wallet, &drafted(source, &wallet, &plan, change)?, store)?;
        let planned = SpendOutput {
            address: dest.to_string(),
            sats: plan.outputs[0].value.to_sat(),
        };
        let change_sats = plan.outputs.get(1).map_or(0, |o| o.value.to_sat());
        if read.payments != [planned]
            || read.change_sats != change_sats
            || read.fee_sats != plan.fee_sats
        {
            return Err(MobileSignerError::InvalidPsbtJson(format!(
                "draft: the PSBT pays {:?} with change {} and fee {}, not the plan",
                read.payments, read.change_sats, read.fee_sats
            )));
        }
        Ok(read)
    })
}

/// The unsigned PSBT of `plan`, its change, when it has any, at change index `change`.
fn drafted(
    source: &dyn ChainSource,
    wallet: &Described,
    plan: &Plan<'_, Derived>,
    change: u32,
) -> Result<Psbt, MobileSignerError> {
    let parents = proven_parents(
        source,
        plan.selected.iter().map(|(utxo, at)| (*utxo, &at.address)),
    )?;
    let tx = Transaction {
        version: Version::TWO,
        lock_time: LockTime::ZERO,
        input: plan
            .selected
            .iter()
            .map(|(utxo, _)| TxIn {
                previous_output: OutPoint::new(utxo.txid, utxo.vout),
                script_sig: ScriptBuf::new(),
                sequence: Sequence::ENABLE_RBF_NO_LOCKTIME,
                witness: Witness::new(),
            })
            .collect(),
        output: plan.outputs.clone(),
    };
    let mut psbt = Psbt::from_unsigned_tx(tx)
        .map_err(|e| MobileSignerError::InvalidPsbtJson(e.to_string()))?;
    for (input, (utxo, at)) in psbt.inputs.iter_mut().zip(&plan.selected) {
        // `proven_parents` read every selected coin's transaction.
        *input = wallet.input(&parents[&utxo.txid], utxo.vout, at.chain, at.index)?;
    }
    if let Some(output) = psbt.outputs.get_mut(1) {
        *output = wallet.change(change)?;
    }
    psbt.xpub = wallet.global_xpubs();
    Ok(psbt)
}

/// Refuses a PSBT that is not a payment of `wallet` exactly as a draft writes one, signatures aside, or that
/// carries any signature but a valid one by a wallet key, else reads it. Ownership is derived from the descriptor
/// again, never read from the PSBT: each input spends the script of the wallet's own key at the chain and index its
/// origins name, its value comes from its parent transaction checked by txid, and an output with key origins is
/// change only when it pays the wallet's change script at that index, at an index the wallet's scan in `store`
/// looks at. Asks nothing of the network.
fn review(
    wallet: &Described,
    psbt: &Psbt,
    store: &ScanStore,
) -> Result<SpendReview, MobileSignerError> {
    let tx = &psbt.unsigned_tx;
    if tx.version != Version::TWO || tx.lock_time != LockTime::ZERO {
        return Err(not_ours("it is not version 2 with lock time 0".into()));
    }
    if tx.input.is_empty() || tx.output.is_empty() {
        return Err(not_ours("it has no input or no output".into()));
    }
    if psbt.version != 0
        || !psbt.proprietary.is_empty()
        || !psbt.unknown.is_empty()
        || psbt.xpub != wallet.global_xpubs()
    {
        return Err(not_ours(
            "its global fields are not exactly this wallet's account keys".into(),
        ));
    }
    let mut prevouts = Vec::with_capacity(tx.input.len());
    let mut spent = HashSet::new();
    for (i, (txin, input)) in tx.input.iter().zip(&psbt.inputs).enumerate() {
        let why = |what: &str| not_ours(format!("input {i} {what}"));
        if txin.sequence != Sequence::ENABLE_RBF_NO_LOCKTIME
            || !txin.script_sig.is_empty()
            || !txin.witness.is_empty()
        {
            return Err(why("is not an unsigned input that signals RBF"));
        }
        if !spent.insert(txin.previous_output) {
            return Err(why("spends a coin an earlier input spends"));
        }
        if input.final_script_sig.is_some() || input.final_script_witness.is_some() {
            return Err(why("is already finalized"));
        }
        let parent = input
            .non_witness_utxo
            .as_ref()
            .filter(|parent| parent.compute_txid() == txin.previous_output.txid)
            .ok_or_else(|| why("does not carry the transaction whose coin it spends"))?;
        let vout = txin.previous_output.vout;
        let prevout = parent
            .output
            .get(vout as usize)
            .ok_or_else(|| why("spends an output its parent does not have"))?;
        let origins = input
            .bip32_derivation
            .values()
            .chain(input.tap_key_origins.values().map(|(_, origin)| origin));
        let (chain, index) = wallet
            .claim(origins)
            .ok_or_else(|| why("names no key of this wallet at a receive or change index"))?;
        if wallet.at(chain, index)?.script_pubkey() != prevout.script_pubkey {
            return Err(why("spends a coin the wallet's keys do not lock there"));
        }
        let expected = wallet.input(parent, vout, chain, index)?;
        let mut unsigned = input.clone();
        unsigned.partial_sigs.clear();
        unsigned.tap_key_sig = None;
        if unsigned != expected {
            return Err(why(if unsigned.sighash_type != expected.sighash_type {
                "asks for another sighash than ALL"
            } else if unsigned.witness_utxo != expected.witness_utxo {
                "carries a witness UTXO that is not its parent's output"
            } else if unsigned.bip32_derivation != expected.bip32_derivation
                || unsigned.tap_key_origins != expected.tap_key_origins
            {
                "names keys other than exactly this wallet's"
            } else {
                "carries fields this wallet does not write"
            }));
        }
        prevouts.push(prevout.clone());
    }

    let mut payments = Vec::new();
    let mut change_sats = None;
    for (o, (txout, output)) in tx.output.iter().zip(&psbt.outputs).enumerate() {
        let why = |what: &str| not_ours(format!("output {o} {what}"));
        if *output == psbt::Output::default() {
            let address = Address::from_script(&txout.script_pubkey, Network::Testnet4)
                .map_err(|_| why("pays a script with no address"))?;
            payments.push(SpendOutput {
                address: address.to_string(),
                sats: txout.value.to_sat(),
            });
            continue;
        }
        let origins = output
            .bip32_derivation
            .values()
            .chain(output.tap_key_origins.values().map(|(_, origin)| origin));
        let Some((Chain::Internal, index)) = wallet.claim(origins) else {
            return Err(why(
                "carries key origins but is not this wallet's change on chain 1",
            ));
        };
        if wallet.at(Chain::Internal, index)?.script_pubkey() != txout.script_pubkey
            || *output != wallet.change(index)?
        {
            return Err(why(
                "claims to be this wallet's change but pays another script",
            ));
        }
        let reach = change_reach(wallet, store);
        if index >= reach {
            return Err(why(&format!(
                "is change at index {index}, past {}, the last change index a scan of this wallet looks at",
                reach - 1
            )));
        }
        if change_sats.replace(txout.value.to_sat()).is_some() {
            return Err(why("is a second change output"));
        }
    }

    let paid_in = prevouts
        .iter()
        .try_fold(0u64, |sum, o| sum.checked_add(o.value.to_sat()));
    let paid_out = tx
        .output
        .iter()
        .try_fold(0u64, |sum, o| sum.checked_add(o.value.to_sat()));
    let fee_sats = match (paid_in, paid_out) {
        (Some(paid_in), Some(paid_out)) if paid_in >= paid_out => paid_in - paid_out,
        _ => return Err(not_ours("its outputs pay more than its inputs hold".into())),
    };
    let signed = signers(wallet, psbt, &prevouts)?;
    Ok(SpendReview {
        psbt: psbt.to_string(),
        unsigned_txid: tx.compute_txid().to_string(),
        payments,
        change_sats: change_sats.unwrap_or(0),
        fee_sats,
        signed: signed.iter().map(Fingerprint::to_string).collect(),
        needed: wallet.needed() as u8,
    })
}

/// The first change index a scan of `wallet` does not ask: `GAP` past the change chain's frontier in `store`, which is
/// one past the change index proven used (a file's index too, which the next scan proves again first), or 0 before
/// any proof. Change there or further shows in no balance or history.
fn change_reach(wallet: &Described, store: &ScanStore) -> u32 {
    store
        .change_proof(&wallet.id())
        .map_or(0, |index| index + 1)
        + GAP
}

/// The fingerprints of the wallet keys with a valid signature on every input, each once, in descriptor order.
/// Refuses any signature that is not a SIGHASH_ALL one (Taproot: the default sighash) by the wallet key derived
/// for its input, verified against the input's own sighash. `review` has made every input the wallet's.
fn signers(
    wallet: &Described,
    psbt: &Psbt,
    prevouts: &[TxOut],
) -> Result<Vec<Fingerprint>, MobileSignerError> {
    let secp = Secp256k1::verification_only();
    let mut cache = SighashCache::new(&psbt.unsigned_tx);
    let mut every: Option<BTreeSet<Fingerprint>> = None;
    for (i, input) in psbt.inputs.iter().enumerate() {
        let why = |what: &str| not_ours(format!("input {i} has a signature {what}"));
        let mut here = BTreeSet::new();
        if let Some(sig) = input.tap_key_sig {
            let (Some(internal), Some((_, (fingerprint, _)))) = (
                input.tap_internal_key,
                input.tap_key_origins.values().next(),
            ) else {
                return Err(why("on an input with no Taproot key"));
            };
            if sig.sighash_type != TapSighashType::Default {
                return Err(why("that is not the default sighash"));
            }
            let sighash = cache
                .taproot_key_spend_signature_hash(
                    i,
                    &Prevouts::All(prevouts),
                    TapSighashType::Default,
                )
                .map_err(|e| why(&e.to_string()))?;
            let (output_key, _) = internal.tap_tweak(&secp, None);
            secp.verify_schnorr(
                &sig.signature,
                &Message::from(sighash),
                &output_key.to_x_only_public_key(),
            )
            .map_err(|_| why("that does not verify"))?;
            here.insert(*fingerprint);
        }
        if !input.partial_sigs.is_empty() {
            let (sighash, _) = psbt
                .sighash_ecdsa(i, &mut cache)
                .map_err(|e| why(&e.to_string()))?;
            for (key, sig) in &input.partial_sigs {
                let origin = input
                    .bip32_derivation
                    .get(&key.inner)
                    .filter(|_| key.compressed);
                let Some((fingerprint, _)) = origin else {
                    return Err(why("by a key outside this wallet"));
                };
                if sig.sighash_type != EcdsaSighashType::All {
                    return Err(why("that is not SIGHASH_ALL"));
                }
                secp.verify_ecdsa(&sighash, &sig.signature, &key.inner)
                    .map_err(|_| why("that does not verify"))?;
                here.insert(*fingerprint);
            }
        }
        every = Some(match every {
            None => here,
            Some(before) => before.intersection(&here).copied().collect(),
        });
    }
    let every = every.unwrap_or_default();
    let mut signed: Vec<Fingerprint> = Vec::new();
    for key in wallet.keys() {
        if every.contains(&key.fingerprint) && !signed.contains(&key.fingerprint) {
            signed.push(key.fingerprint);
        }
    }
    Ok(signed)
}

/// A PSBT that `review` read as a payment of its wallet, whose unsigned id is the one the user was shown. Only
/// `as_shown` makes one, and only a `Shown` is signed (`signed_with_words`, `ble.rs` `sign_wallet`) or finished
/// (`finished`), so no payment the review did not read reaches a key or the network.
pub(crate) struct Shown {
    psbt: Psbt,
    review: SpendReview,
}

impl Shown {
    pub(crate) fn review(&self) -> &SpendReview {
        &self.review
    }

    pub(crate) fn into_review(self) -> SpendReview {
        self.review
    }
}

/// `review` of `psbt`, once its unsigned id is `unsigned_txid`, the payment the user was shown.
pub(crate) fn as_shown(
    wallet: &Described,
    psbt: Psbt,
    unsigned_txid: &str,
    store: &ScanStore,
) -> Result<Shown, MobileSignerError> {
    let read = review(wallet, &psbt, store)?;
    if read.unsigned_txid != unsigned_txid {
        return Err(MobileSignerError::InvalidPsbtJson(format!(
            "changed since shown: this payment is {}, not {unsigned_txid}",
            read.unsigned_txid
        )));
    }
    Ok(Shown { psbt, review: read })
}

/// The transaction to post of `shown`. Refuses unless at least `needed` distinct fingerprints signed every input.
/// Then finalizes, extracts and checks the bytes: the inputs, sequences and outputs reviewed, a weight at most the
/// one its fee was sized for, and a fee the node takes.
pub(crate) fn finished(wallet: &Described, shown: &Shown) -> Result<Signed, MobileSignerError> {
    let (psbt, read) = (&shown.psbt, &shown.review);
    if read.signed.len() < wallet.needed() {
        return Err(MobileSignerError::InvalidPsbtJson(format!(
            "signatures: {} of the {} keys needed signed every input",
            read.signed.len(),
            wallet.needed()
        )));
    }
    let secp = Secp256k1::verification_only();
    let mut done = psbt.clone();
    done.finalize_mut(&secp).map_err(|errors| {
        MobileSignerError::InvalidPsbtJson(format!("signatures: it does not finalize: {errors:?}"))
    })?;
    let tx = done
        .extract(&secp)
        .map_err(|e| MobileSignerError::InvalidPsbtJson(format!("signatures: {e}")))?;
    let unsigned = &psbt.unsigned_tx;
    let same_payment = tx.version == unsigned.version
        && tx.lock_time == unsigned.lock_time
        && tx.output == unsigned.output
        && tx.input.len() == unsigned.input.len()
        && tx
            .input
            .iter()
            .zip(&unsigned.input)
            .all(|(a, b)| a.previous_output == b.previous_output && a.sequence == b.sequence);
    if !same_payment {
        return Err(MobileSignerError::InvalidPsbtJson(
            "finish: the finalized transaction is not the reviewed one".into(),
        ));
    }
    let sized_for = predict_weight(
        vec![wallet.spend_weight(); tx.input.len()],
        tx.output.iter().map(|o| o.script_pubkey.len()),
    );
    if tx.weight() > sized_for {
        return Err(MobileSignerError::InvalidPsbtJson(format!(
            "finish: signed weight {} is above the {sized_for} its fee was sized for",
            tx.weight()
        )));
    }
    check_node_rate(read.fee_sats, tx.vsize() as u64)
        .map_err(MobileSignerError::InvalidPsbtJson)?;
    Ok(Signed {
        tx,
        fee_sats: read.fee_sats,
        dust_change_sats: 0,
        amount_cut_sats: 0,
    })
}

#[cfg(test)]
pub(crate) mod tests {
    use std::collections::{BTreeMap, HashSet};
    use std::sync::{Mutex, mpsc};
    use std::time::{Duration, Instant};

    use bitcoin::bip32::{ChildNumber, DerivationPath, KeySource, Xpriv, Xpub};
    use bitcoin::script::Instruction;
    use bitcoin::secp256k1::{self, SecretKey};
    use bitcoin::{Amount, PublicKey, Script};
    use miniscript::psbt::PsbtOutputExt;
    use vault_core::hw::psbt_check::validate_signing_policy;

    use super::*;
    use crate::address_type::AddressType;
    use crate::chain::Transport;
    use crate::hot_wallet::tests::{Fake, SOMEONE};
    use crate::hot_wallet::vsize;
    use crate::scan_store::tests::Folder;
    use crate::script::tests::{
        WithMulti, abandon_master, at, fund_multi, multisig, one_key, one_of_two, soft_master,
        three_of_five, two_of_three,
    };

    pub(crate) const RATE: u64 = 3;

    fn fingerprint(master: &Xpriv) -> Fingerprint {
        master.fingerprint(&Secp256k1::new())
    }

    /// A Fake on which the wallet holds 50,000 sats at receive 0 and, but for the 1-of-2 and the 3-of-5 (whose
    /// receive 0 is all `MULTI` pays of theirs), 20,000 at receive 1, each coin's parent served raw.
    pub(crate) fn funded(descriptor: &str) -> Fake {
        let mut fake = Fake::new();
        let (first, second) = (
            at(descriptor, Chain::External, 0),
            at(descriptor, Chain::External, 1),
        );
        if descriptor.starts_with("wsh(") {
            fund_multi(&mut fake, &first, 50_000, '1');
            if descriptor == two_of_three() {
                fund_multi(&mut fake, &second, 20_000, '2');
            }
        } else {
            fake.fund_raw(&first.to_string(), 50_000, 50_000, '1');
            fake.fund_raw(&second.to_string(), 20_000, 20_000, '2');
        }
        fake
    }

    pub(crate) fn drafted_on(
        fake: &Fake,
        descriptor: &str,
        amount: SendAmount,
        rate: u64,
    ) -> Result<SpendReview, MobileSignerError> {
        draft(
            &EsploraSource(&WithMulti(fake)),
            descriptor,
            KeyNetwork::Test,
            SOMEONE,
            amount,
            rate,
            &|| fake.now(),
            &fake.store,
        )
    }

    /// `psbt` signed by each of `masters` wherever it holds a key, as a software signer of those words would.
    fn signed_by(psbt: &str, masters: &[Xpriv]) -> Psbt {
        let mut psbt = Psbt::from_str(psbt).unwrap();
        for master in masters {
            psbt.sign(master, &Secp256k1::new())
                .expect("a software key signs");
        }
        psbt
    }

    /// `wallet_sign_with_words` on a parsed PSBT and `store`.
    fn sign_as_shown(
        wallet: &Described,
        psbt: Psbt,
        shown_txid: &str,
        words: &[String],
        passphrase: &str,
        store: &ScanStore,
    ) -> Result<SpendReview, MobileSignerError> {
        let shown = as_shown(wallet, psbt, shown_txid, store)?;
        signed_with_words(wallet, shown, words, passphrase, store).map(Shown::into_review)
    }

    /// `wallet_finish` on a parsed PSBT and `store`: the transaction to post and the review it passed.
    fn finish_as_shown(
        wallet: &Described,
        psbt: &Psbt,
        shown_txid: &str,
        store: &ScanStore,
    ) -> Result<(Signed, SpendReview), MobileSignerError> {
        let shown = as_shown(wallet, psbt.clone(), shown_txid, store)?;
        Ok((finished(wallet, &shown)?, shown.into_review()))
    }

    /// `review` refuses `psbt` for `why`, its own words, on a phone that has not scanned the wallet.
    #[track_caller]
    fn refused(wallet: &Described, psbt: &Psbt, why: &str) {
        match review(wallet, psbt, &ScanStore::default()) {
            Err(MobileSignerError::InvalidPsbtJson(said)) => assert!(
                said.starts_with("not this wallet's payment: ") && said.contains(why),
                "refused with {said:?}, not {why:?}"
            ),
            other => panic!("expected a refusal for {why:?}, got {other:?}"),
        }
    }

    /// A BIP-44/49/84/86 account 0 path on testnet, or a BIP-48 P2WSH account on testnet.
    fn valid_account(xpub: &Xpub, path: &DerivationPath) -> bool {
        xpub.network == bitcoin::NetworkKind::Test
            && usize::from(xpub.depth) == path.len()
            && path.as_ref().last() == Some(&xpub.child_number)
    }

    fn bip48(path: &[ChildNumber]) -> bool {
        matches!(path, [ChildNumber::Hardened { index: 48 }, ChildNumber::Hardened { index: 1 }, account, ChildNumber::Hardened { index: 2 }] if account.is_hardened())
    }

    /// `SingleSig::resolve`'s reading of a one-key input (or of change seen as one): the account xpub it is under.
    fn safe7_single(
        input: &psbt::Input,
        spent: &TxOut,
        xpubs: &BTreeMap<Xpub, KeySource>,
        signer: Fingerprint,
    ) -> Result<Xpub, String> {
        let secp = Secp256k1::verification_only();
        if input.witness_script.is_some() {
            return Err("a single-sig input has a witness script".into());
        }
        let taproot = spent.script_pubkey.is_p2tr();
        let ((fp, path), x_only) = if taproot {
            let origins: Vec<_> = input.tap_key_origins.iter().collect();
            let [(key, (leaves, origin))] = origins[..] else {
                return Err("not exactly one Taproot origin".into());
            };
            if !input.bip32_derivation.is_empty()
                || !leaves.is_empty()
                || input.tap_internal_key != Some(*key)
            {
                return Err("the Taproot origin does not match its internal key".into());
            }
            (origin.clone(), *key)
        } else {
            let origins: Vec<_> = input.bip32_derivation.iter().collect();
            let [(key, origin)] = origins[..] else {
                return Err("a single-sig input must carry exactly its one key origin".into());
            };
            if input.tap_internal_key.is_some() || !input.tap_key_origins.is_empty() {
                return Err("a single-sig input carries Taproot fields".into());
            }
            (origin.clone(), key.x_only_public_key().0)
        };
        if fp != signer {
            return Err(format!("no key of fingerprint {signer}"));
        }
        let [purpose, coin, account, chain, index] = path.as_ref() else {
            return Err("not a BIP-44/49/84/86 receive or change key path".into());
        };
        let purpose = match purpose {
            ChildNumber::Hardened {
                index: p @ (44 | 49 | 84 | 86),
            } => *p,
            _ => return Err("unsupported account purpose".into()),
        };
        if *coin != (ChildNumber::Hardened { index: 1 })
            || *account != (ChildNumber::Hardened { index: 0 })
            || !matches!(chain, ChildNumber::Normal { index: 0 | 1 })
            || !index.is_normal()
            || (purpose == 86) != taproot
        {
            return Err(format!(
                "{path} is not account 0 for its script type on testnet"
            ));
        }
        let account_path: DerivationPath = path.as_ref()[..3].to_vec().into();
        let suffix = &path.as_ref()[3..];
        let derived: Vec<(Xpub, secp256k1::PublicKey)> = xpubs
            .iter()
            .filter(|(xpub, source)| {
                **source == (signer, account_path.clone()) && valid_account(xpub, &account_path)
            })
            .filter_map(|(xpub, _)| {
                let key = xpub.derive_pub(&secp, &suffix).ok()?.public_key;
                (key.x_only_public_key().0 == x_only).then_some((*xpub, key))
            })
            .collect();
        let [(xpub, key)] = derived[..] else {
            return Err("no unique global account xpub derives the signing key".into());
        };
        if !taproot && !input.bip32_derivation.contains_key(&key) {
            return Err("the global xpub derives a different compressed key".into());
        }
        let public = PublicKey::new(key);
        let wpkh = ScriptBuf::new_p2wpkh(&public.wpubkey_hash().unwrap());
        let (redeem, script) = match purpose {
            44 => (None, ScriptBuf::new_p2pkh(&public.pubkey_hash())),
            49 => (Some(wpkh.clone()), wpkh.to_p2sh()),
            84 => (None, wpkh),
            _ => (None, ScriptBuf::new_p2tr(&secp, x_only, None)),
        };
        if input.redeem_script != redeem || spent.script_pubkey != script {
            return Err("the origin, redeem script and previous output do not agree".into());
        }
        Ok(xpub)
    }

    /// `Multisig::resolve` with `signer`'s key at a vault path: m and the account xpubs, sorted.
    fn safe7_multi(
        script: &Script,
        origins: &BTreeMap<secp256k1::PublicKey, KeySource>,
        xpubs: &BTreeMap<Xpub, KeySource>,
        signer: Fingerprint,
    ) -> Result<(usize, Vec<Xpub>), String> {
        let secp = Secp256k1::verification_only();
        let parts: Vec<Instruction> = script
            .instructions()
            .collect::<Result<_, _>>()
            .map_err(|e| e.to_string())?;
        let small = |part: Option<&Instruction>| match part {
            Some(Instruction::Op(op)) if (0x51..=0x60).contains(&op.to_u8()) => {
                Some(usize::from(op.to_u8() - 0x50))
            }
            _ => None,
        };
        let not_sorted = || "the witness script is not a sorted multisig".to_string();
        let (Some(m), Some(n)) = (small(parts.first()), small(parts.iter().rev().nth(1))) else {
            return Err(not_sorted());
        };
        let keys: Vec<secp256k1::PublicKey> = parts[1..parts.len() - 2]
            .iter()
            .map(|part| match part {
                Instruction::PushBytes(bytes) if bytes.len() == 33 => {
                    secp256k1::PublicKey::from_slice(bytes.as_bytes()).ok()
                }
                _ => None,
            })
            .collect::<Option<_>>()
            .ok_or_else(not_sorted)?;
        let checkmultisig = matches!(parts.last(), Some(Instruction::Op(op)) if *op == bitcoin::opcodes::all::OP_CHECKMULTISIG);
        if !checkmultisig
            || keys.len() != n
            || !(1..=n).contains(&m)
            || !keys.windows(2).all(|w| w[0].serialize() < w[1].serialize())
        {
            return Err(not_sorted());
        }
        if origins.len() != n {
            return Err("its key origins don't match the script's keys".into());
        }
        let (mut suffix, mut nodes, mut ours) = (None, Vec::new(), Vec::new());
        for key in &keys {
            let (fp, path) = origins
                .get(key)
                .ok_or("a key of the script has no origin")?;
            let (xpub, rest) = xpubs
                .iter()
                .filter(|(_, (xpub_fp, _))| xpub_fp == fp)
                .find_map(|(xpub, (_, account))| {
                    let rest = path.as_ref().strip_prefix(account.as_ref())?;
                    let normal = !rest.is_empty() && rest.iter().all(ChildNumber::is_normal);
                    let derives = normal && xpub.derive_pub(&secp, &rest).ok()?.public_key == *key;
                    derives.then(|| (*xpub, rest.to_vec()))
                })
                .ok_or_else(|| format!("no global xpub derives the key at {path}"))?;
            if *suffix.get_or_insert_with(|| rest.clone()) != rest {
                return Err("the keys derive at different paths below their accounts".into());
            }
            let (_, account) = &xpubs[&xpub];
            if !bip48(account.as_ref()) || !valid_account(&xpub, account) {
                return Err("a vault account xpub or origin is not BIP-48 P2WSH on testnet".into());
            }
            if *fp == signer {
                ours.push(path.clone());
            }
            nodes.push(xpub);
        }
        nodes.sort_by_key(|node| (node.public_key.serialize(), node.chain_code.to_bytes()));
        nodes.dedup();
        if nodes.len() != n {
            return Err("an account appears twice in the multisig".into());
        }
        let [path] = &ours[..] else {
            return Err(format!("fingerprint {signer} holds not exactly one key"));
        };
        let vault_path = match path.as_ref() {
            [account @ .., ChildNumber::Normal { index: 0 | 1 }, index] => {
                bip48(account) && index.is_normal()
            }
            _ => false,
        };
        if !vault_path {
            return Err(format!(
                "this device's key is at {path}, not a BIP-48 P2WSH vault path"
            ));
        }
        Ok((m, nodes))
    }

    /// What the Safe 7 client's `Signing::new` (vault-core `hw::trezor::bitcoin`) reads of `psbt` before it asks
    /// the device to sign for `signer`, rule by rule: `validate_signing_policy`, then per input the parent, the
    /// witness UTXO and `Wallet::resolve`, every input of one wallet, then per output `change_output` or
    /// `single_change_output`, else an address. That function is private and runs only inside a session paired
    /// with a device, so this reads the same fields the same way. Says which outputs the device takes as change.
    fn safe7_reads(psbt: &Psbt, signer: Fingerprint) -> Result<Vec<bool>, String> {
        let tx = &psbt.unsigned_tx;
        if tx.input.is_empty() || tx.output.is_empty() {
            return Err("the PSBT has no inputs or no outputs".into());
        }
        validate_signing_policy(psbt).map_err(|e| e.to_string())?;
        let mut spent = HashSet::new();
        let mut wallet: Option<(bool, usize, Vec<Xpub>)> = None;
        for (i, (txin, input)) in tx.input.iter().zip(&psbt.inputs).enumerate() {
            let refuse = |why: String| format!("input {i}: {why}");
            if !spent.insert(txin.previous_output) {
                return Err(refuse("spends the same output as an earlier input".into()));
            }
            let parent = input
                .non_witness_utxo
                .as_ref()
                .filter(|parent| parent.compute_txid() == txin.previous_output.txid)
                .ok_or_else(|| refuse("no previous transaction".into()))?;
            let prevout = parent
                .output
                .get(txin.previous_output.vout as usize)
                .ok_or_else(|| refuse("spends an output its parent doesn't have".into()))?;
            if input
                .witness_utxo
                .as_ref()
                .is_some_and(|utxo| utxo != prevout)
            {
                return Err(refuse(
                    "witness UTXO and previous transaction disagree".into(),
                ));
            }
            let resolved = if prevout.script_pubkey.is_p2wsh() {
                if input.redeem_script.is_some()
                    || input.tap_internal_key.is_some()
                    || input.tap_key_sig.is_some()
                    || !input.tap_key_origins.is_empty()
                {
                    return Err(refuse(
                        "a P2WSH vault input has unrelated redeem or Taproot data".into(),
                    ));
                }
                let script = input
                    .witness_script
                    .as_ref()
                    .filter(|script| script.to_p2wsh() == prevout.script_pubkey)
                    .ok_or_else(|| refuse("no witness script for what it spends".into()))?;
                let (m, nodes) = safe7_multi(script, &input.bip32_derivation, &psbt.xpub, signer)
                    .map_err(refuse)?;
                (true, m, nodes)
            } else {
                let xpub = safe7_single(input, prevout, &psbt.xpub, signer).map_err(refuse)?;
                (false, 1, vec![xpub])
            };
            match &wallet {
                None => wallet = Some(resolved),
                Some(first) if *first == resolved => {}
                Some(_) => return Err(refuse("spends from a different vault than input 0".into())),
            }
        }
        let (multi, m, nodes) = wallet.expect("an input");
        let mut change = Vec::new();
        for (o, (txout, output)) in tx.output.iter().zip(&psbt.outputs).enumerate() {
            let is_change = if multi {
                output
                    .witness_script
                    .as_ref()
                    .filter(|script| script.to_p2wsh() == txout.script_pubkey)
                    .and_then(|script| {
                        safe7_multi(script, &output.bip32_derivation, &psbt.xpub, signer).ok()
                    })
                    .is_some_and(|vault| vault == (m, nodes.clone()))
            } else {
                let ours = output
                    .bip32_derivation
                    .values()
                    .any(|(fp, _)| *fp == signer)
                    || output
                        .tap_key_origins
                        .values()
                        .any(|(_, (fp, _))| *fp == signer);
                let as_input = psbt::Input {
                    bip32_derivation: output.bip32_derivation.clone(),
                    redeem_script: output.redeem_script.clone(),
                    witness_script: output.witness_script.clone(),
                    tap_internal_key: output.tap_internal_key,
                    tap_key_origins: output.tap_key_origins.clone(),
                    ..psbt::Input::default()
                };
                ours && safe7_single(&as_input, txout, &psbt.xpub, signer)
                    .map_err(|why| format!("output {o}: {why}"))?
                    == nodes[0]
            };
            if !is_change && Address::from_script(&txout.script_pubkey, Network::Testnet4).is_err()
            {
                return Err(format!("output {o}: pays a script with no address"));
            }
            change.push(is_change);
        }
        Ok(change)
    }

    // The fields both devices need, as the harness proved them, and the Safe 7's own reading of them: it takes the
    // change as change, so the screen shows only the payment. The Jade's host check is `validate_signing_policy`.
    #[test]
    fn every_draft_carries_what_the_jade_and_the_safe7_read() {
        let abandon = fingerprint(&abandon_master(Network::Testnet4));
        let mut cases: Vec<(String, Fingerprint)> = AddressType::ALL
            .iter()
            .map(|&address_type| (one_key(address_type), abandon))
            .collect();
        cases.push((two_of_three(), fingerprint(&soft_master(1))));
        for (descriptor, signer) in cases {
            let fake = funded(&descriptor);
            let draft = drafted_on(&fake, &descriptor, SendAmount::Exact(60_000), RATE).unwrap();
            let psbt = Psbt::from_str(&draft.psbt).unwrap();
            assert_eq!(validate_signing_policy(&psbt), Ok(()), "{descriptor}");
            assert_eq!(
                safe7_reads(&psbt, signer),
                Ok(vec![false, true]),
                "{descriptor}"
            );
            let tx = &psbt.unsigned_tx;
            assert_eq!(
                (tx.version, tx.lock_time, tx.input.len()),
                (Version::TWO, LockTime::ZERO, 2)
            );
            assert!(
                tx.input
                    .iter()
                    .all(|i| i.sequence == Sequence::ENABLE_RBF_NO_LOCKTIME)
            );
            let legacy = descriptor.starts_with("pkh(");
            let taproot = descriptor.starts_with("tr(");
            for input in &psbt.inputs {
                assert!(input.non_witness_utxo.is_some(), "{descriptor}");
                assert_eq!(input.witness_utxo.is_some(), !legacy, "{descriptor}");
                assert_eq!(input.tap_internal_key.is_some(), taproot, "{descriptor}");
                assert_eq!(input.redeem_script.is_some(), descriptor.starts_with("sh("));
                assert_eq!(
                    input.witness_script.is_some(),
                    descriptor.starts_with("wsh(")
                );
                let origins = input.bip32_derivation.len() + input.tap_key_origins.len();
                assert_eq!(origins, Described::parse(&descriptor).unwrap().keys().len());
            }
            assert_eq!(
                psbt.xpub.len(),
                Described::parse(&descriptor).unwrap().keys().len()
            );
            assert_eq!(
                draft.payments,
                [SpendOutput {
                    address: SOMEONE.into(),
                    sats: 60_000
                }]
            );
            assert!(draft.signed.is_empty());
        }
    }

    // The six confirmed testnet4 spends in wallet-e2e-audit/verification.md, read from a public Esplora on 12 Nov
    // 2026: m, n, the spend's weight and its signatures' lengths with the sighash byte. Each spent one P2WSH coin to
    // a P2WPKH payment and P2WSH change. Their vsizes are the table the design names: 162, 180, 171, 189, 207, 207.
    #[test]
    fn the_planned_weight_covers_the_six_confirmed_harness_spends() {
        let spends: [(usize, u8, u64, &[u64]); 6] = [
            (1, 2, 648, &[71]),
            (2, 2, 720, &[71, 71]),
            (1, 3, 682, &[71]),
            (2, 3, 754, &[71, 71]),
            (3, 3, 826, &[70, 72, 71]),
            (3, 3, 827, &[71, 72, 71]),
        ];
        let vsizes = [162, 180, 171, 189, 207, 207];
        for ((m, n, weight, sigs), confirmed) in spends.into_iter().zip(vsizes) {
            let among: Vec<u8> = (1..=n).collect();
            let wallet = Described::parse(&multisig(m, &among)).unwrap();
            let planned = predict_weight([wallet.spend_weight()], [22, 34]).to_wu();
            // Every signature priced at 72 bytes, the most a device's takes: the spend plus what each was shorter.
            let shorter: u64 = sigs.iter().map(|len| 72 - len).sum();
            assert_eq!(planned, weight + shorter, "{m}-of-{n}");
            assert_eq!(weight.div_ceil(4), confirmed, "{m}-of-{n}");
            assert!(vsize(&[wallet.spend_weight()], &[22, 34]) >= confirmed);
        }
    }

    #[test]
    fn the_phrase_of_a_wallets_key_signs_its_payment_as_a_device_would() {
        let words = crate::hot_wallet::tests::abandon_about();
        let abandon = fingerprint(&abandon_master(Network::Testnet4)).to_string();
        for address_type in AddressType::ALL {
            let descriptor = one_key(address_type);
            let wallet = Described::parse(&descriptor).unwrap();
            let fake = funded(&descriptor);
            let draft = drafted_on(&fake, &descriptor, SendAmount::Exact(30_000), RATE).unwrap();
            let unsigned = parse(&draft.psbt).unwrap();
            let read = sign_as_shown(
                &wallet,
                unsigned,
                &draft.unsigned_txid,
                &words,
                "",
                &fake.store,
            )
            .unwrap();
            assert_eq!(
                read.signed,
                std::slice::from_ref(&abandon),
                "{address_type:?}"
            );
            finish_as_shown(
                &wallet,
                &parse(&read.psbt).unwrap(),
                &draft.unsigned_txid,
                &fake.store,
            )
            .unwrap();
            let again = sign_as_shown(
                &wallet,
                parse(&read.psbt).unwrap(),
                &draft.unsigned_txid,
                &words,
                "",
                &fake.store,
            )
            .unwrap();
            assert_eq!(
                again, read,
                "{address_type:?}: a key that signed signs nothing more"
            );
        }
    }

    #[test]
    fn a_phrase_of_no_key_of_the_wallet_or_a_payment_not_shown_signs_nothing() {
        let words = crate::hot_wallet::tests::abandon_about();
        let descriptor = one_key(AddressType::NativeSegwit);
        let wallet = Described::parse(&descriptor).unwrap();
        let fake = funded(&descriptor);
        let draft = drafted_on(&fake, &descriptor, SendAmount::Exact(30_000), RATE).unwrap();
        let refusal = |result: Result<SpendReview, MobileSignerError>| match result {
            Err(MobileSignerError::InvalidPsbtJson(said)) => said,
            other => panic!("expected a refusal, got {other:?}"),
        };
        let other_phrase = refusal(sign_as_shown(
            &wallet,
            parse(&draft.psbt).unwrap(),
            &draft.unsigned_txid,
            &words,
            "a passphrase",
            &fake.store,
        ));
        assert!(
            other_phrase.starts_with("not a key of this wallet: "),
            "{other_phrase}"
        );
        let not_shown = refusal(sign_as_shown(
            &wallet,
            parse(&draft.psbt).unwrap(),
            &"0".repeat(64),
            &words,
            "",
            &fake.store,
        ));
        assert!(
            not_shown.starts_with("changed since shown: "),
            "{not_shown}"
        );
        let other_wallet = Described::parse(&one_key(AddressType::Taproot)).unwrap();
        assert!(
            sign_as_shown(
                &other_wallet,
                parse(&draft.psbt).unwrap(),
                &draft.unsigned_txid,
                &words,
                "",
                &fake.store
            )
            .is_err(),
            "another wallet's payment"
        );
    }

    #[test]
    fn a_software_signed_spend_of_each_one_key_type_pays_at_least_the_rate_asked() {
        let master = abandon_master(Network::Testnet4);
        for address_type in AddressType::ALL {
            let descriptor = one_key(address_type);
            let wallet = Described::parse(&descriptor).unwrap();
            let fake = funded(&descriptor);
            for amount in [SendAmount::Exact(60_000), SendAmount::All] {
                let draft = drafted_on(&fake, &descriptor, amount, RATE).unwrap();
                let psbt = signed_by(&draft.psbt, &[master]);
                let read = review(&wallet, &psbt, &fake.store).unwrap();
                assert_eq!(read.signed, [fingerprint(&master).to_string()]);
                let (sent, _) =
                    finish_as_shown(&wallet, &psbt, &draft.unsigned_txid, &fake.store).unwrap();
                let vsize = sent.tx.vsize() as u64;
                assert!(sent.fee_sats >= RATE * vsize, "{address_type:?} {amount:?}");
                assert_eq!(sent.tx.output, psbt.unsigned_tx.output);
                // Why the binding id is the unsigned one: a Legacy or Nested SegWit signature changes the txid.
                let kept = sent.tx.compute_txid().to_string() == draft.unsigned_txid;
                let segwit_only = matches!(
                    address_type,
                    AddressType::NativeSegwit | AddressType::Taproot
                );
                assert_eq!(kept, segwit_only, "{address_type:?}");
                let ready = wallet_finish(
                    descriptor.clone(),
                    KeyNetwork::Test,
                    psbt.to_string(),
                    draft.unsigned_txid.clone(),
                )
                .unwrap();
                assert_eq!(
                    (ready.amount_sats, ready.fee_sats),
                    (draft.payments[0].sats, draft.fee_sats)
                );
            }
        }
    }

    #[test]
    fn every_signer_subset_finishes_exactly_when_m_distinct_keys_signed() {
        for (descriptor, among) in [
            (one_of_two(), vec![1u8, 2]),
            (two_of_three(), vec![1, 2, 3]),
            (three_of_five(), vec![1, 2, 3, 4, 5]),
        ] {
            let wallet = Described::parse(&descriptor).unwrap();
            let fake = funded(&descriptor);
            let draft = drafted_on(&fake, &descriptor, SendAmount::Exact(30_000), RATE).unwrap();
            let sized_for = vsize(&[wallet.spend_weight()], &[22, 34]);
            for subset in 0u32..1 << among.len() {
                let masters: Vec<Xpriv> = among
                    .iter()
                    .enumerate()
                    .filter(|(i, _)| subset >> i & 1 == 1)
                    .map(|(_, &key)| soft_master(key))
                    .collect();
                let psbt = signed_by(&draft.psbt, &masters);
                let read = review(&wallet, &psbt, &fake.store).unwrap();
                let who: Vec<String> = masters.iter().map(|m| fingerprint(m).to_string()).collect();
                assert_eq!(read.signed, who, "{descriptor} {subset:b}");
                match finish_as_shown(&wallet, &psbt, &draft.unsigned_txid, &fake.store) {
                    Ok((sent, _)) => {
                        assert!(
                            masters.len() >= wallet.needed(),
                            "{subset:b} finished short"
                        );
                        assert_eq!(sent.tx.compute_txid().to_string(), draft.unsigned_txid);
                        assert!(sent.tx.vsize() as u64 <= sized_for);
                        assert!(sent.fee_sats >= RATE * sent.tx.vsize() as u64);
                    }
                    Err(e) => {
                        assert!(masters.len() < wallet.needed(), "{subset:b}: {e}");
                        let why = format!(
                            "signatures: {} of the {} keys",
                            masters.len(),
                            wallet.needed()
                        );
                        assert!(e.to_string().contains(&why), "{e}");
                    }
                }
            }
        }
    }

    // Two keys of one seed are one signer, so the wallet is refused (script.rs) and no payment of it is drafted, read
    // or finished.
    #[test]
    fn a_wallet_with_two_keys_of_one_seed_has_no_payment() {
        let key = |seed: u8, account: u32| {
            let master = soft_master(seed);
            let path = DerivationPath::from_str(&format!("m/48'/1'/{account}'/2'")).unwrap();
            let secp = Secp256k1::new();
            let xpub = Xpub::from_priv(&secp, &master.derive_priv(&secp, &path).unwrap());
            format!("[{}/{path}]{xpub}", fingerprint(&master))
        };
        let descriptor = format!(
            "wsh(sortedmulti(2,{},{},{}))",
            key(7, 0),
            key(7, 1),
            key(8, 0)
        );
        let refusals = [
            drafted_on(&Fake::new(), &descriptor, SendAmount::Exact(30_000), RATE).unwrap_err(),
            wallet_review(descriptor.clone(), KeyNetwork::Test, "cHNidP8".into()).unwrap_err(),
            wallet_finish(
                descriptor,
                KeyNetwork::Test,
                "cHNidP8".into(),
                "0".repeat(64),
            )
            .unwrap_err(),
        ];
        for refused in refusals {
            assert!(
                refused
                    .to_string()
                    .contains("two keys from one phrase are one key"),
                "{refused}"
            );
        }
    }

    #[test]
    fn finish_refuses_too_few_signers_another_id_and_a_rewritten_output() {
        let descriptor = two_of_three();
        let wallet = Described::parse(&descriptor).unwrap();
        let fake = funded(&descriptor);
        let draft = drafted_on(&fake, &descriptor, SendAmount::Exact(30_000), RATE).unwrap();
        let both = signed_by(&draft.psbt, &[soft_master(1), soft_master(3)]);
        assert!(finish_as_shown(&wallet, &both, &draft.unsigned_txid, &fake.store).is_ok());

        let one = signed_by(&draft.psbt, &[soft_master(2)]);
        let short = finish_as_shown(&wallet, &one, &draft.unsigned_txid, &fake.store)
            .err()
            .unwrap();
        assert!(
            short
                .to_string()
                .contains("signatures: 1 of the 2 keys needed signed every input")
        );

        let other = finish_as_shown(&wallet, &both, &"0".repeat(64), &fake.store)
            .err()
            .unwrap();
        assert!(other.to_string().contains("changed since shown"), "{other}");

        // Rewritten before anyone signed, then signed by two keys: what they signed is not what was shown.
        let mut rewritten = Psbt::from_str(&draft.psbt).unwrap();
        rewritten.unsigned_tx.output[0].value = Amount::from_sat(31_000);
        rewritten.unsigned_tx.output[1].value -= Amount::from_sat(1_000);
        let rewritten = signed_by(&rewritten.to_string(), &[soft_master(1), soft_master(2)]);
        assert_eq!(
            review(&wallet, &rewritten, &fake.store)
                .unwrap()
                .signed
                .len(),
            2
        );
        let changed = finish_as_shown(&wallet, &rewritten, &draft.unsigned_txid, &fake.store)
            .err()
            .unwrap();
        assert!(
            changed.to_string().contains("changed since shown"),
            "{changed}"
        );
    }

    // The policy-engine-review checklist, each refusal with its reason: a PSBT that pays another output, another
    // amount, from another wallet's coins, under another sighash or with a signature that is not the wallet's.
    #[test]
    fn review_refuses_a_payment_that_is_not_this_wallets_as_drafted() {
        let descriptor = two_of_three();
        let wallet = Described::parse(&descriptor).unwrap();
        let fake = funded(&descriptor);
        let draft = drafted_on(&fake, &descriptor, SendAmount::Exact(60_000), RATE).unwrap();
        let unsigned = Psbt::from_str(&draft.psbt).unwrap();
        assert_eq!(unsigned.inputs.len(), 2);
        let signed = signed_by(&draft.psbt, &[soft_master(1)]);
        let someone = Address::from_str(SOMEONE)
            .unwrap()
            .assume_checked()
            .script_pubkey();
        let edit = |base: &Psbt, change: &dyn Fn(&mut Psbt)| {
            let mut psbt = base.clone();
            change(&mut psbt);
            psbt
        };

        let more = edit(&signed, &|p| {
            p.unsigned_tx.output[0].value += Amount::from_sat(300)
        });
        refused(
            &wallet,
            &more,
            "input 0 has a signature that does not verify",
        );
        let overpays = edit(&unsigned, &|p| {
            p.unsigned_tx.output[0].value += Amount::from_sat(10_000)
        });
        refused(
            &wallet,
            &overpays,
            "its outputs pay more than its inputs hold",
        );
        let stranger_change = edit(&unsigned, &|p| {
            p.unsigned_tx.output[1].script_pubkey = someone.clone()
        });
        refused(
            &wallet,
            &stranger_change,
            "output 1 claims to be this wallet's change but pays another script",
        );
        let receive_as_change = edit(&unsigned, &|p| {
            p.unsigned_tx.output[1].script_pubkey =
                wallet.at(Chain::External, 5).unwrap().script_pubkey();
            p.outputs[1] = psbt::Output::default();
            p.outputs[1]
                .update_with_descriptor_unchecked(&wallet.at(Chain::External, 5).unwrap())
                .unwrap();
        });
        refused(
            &wallet,
            &receive_as_change,
            "output 1 carries key origins but is not this wallet's change on chain 1",
        );
        let parent_pays_more = edit(&unsigned, &|p| {
            let vout = p.unsigned_tx.input[0].previous_output.vout as usize;
            p.inputs[0].non_witness_utxo.as_mut().unwrap().output[vout].value +=
                Amount::from_sat(5_000);
        });
        refused(
            &wallet,
            &parent_pays_more,
            "input 0 does not carry the transaction whose coin it spends",
        );
        let utxo_says_more = edit(&unsigned, &|p| {
            p.inputs[0].witness_utxo.as_mut().unwrap().value += Amount::from_sat(5_000);
        });
        refused(
            &wallet,
            &utxo_says_more,
            "input 0 carries a witness UTXO that is not its parent's output",
        );

        // Another wallet's coin: the 1-of-2's input as it drafts it, and a stranger's coin under this wallet's origins.
        let other_fake = funded(&one_of_two());
        let other =
            drafted_on(&other_fake, &one_of_two(), SendAmount::Exact(30_000), RATE).unwrap();
        let other = Psbt::from_str(&other.psbt).unwrap();
        let spliced = edit(&unsigned, &|p| {
            p.unsigned_tx.input[0] = other.unsigned_tx.input[0].clone();
            p.inputs[0] = other.inputs[0].clone();
        });
        refused(
            &wallet,
            &spliced,
            "input 0 spends a coin the wallet's keys do not lock there",
        );
        let one_key_fake = funded(&one_key(AddressType::NativeSegwit));
        let one_key_draft = drafted_on(
            &one_key_fake,
            &one_key(AddressType::NativeSegwit),
            SendAmount::Exact(30_000),
            RATE,
        )
        .unwrap();
        let one_key_psbt = Psbt::from_str(&one_key_draft.psbt).unwrap();
        let foreign = edit(&unsigned, &|p| {
            p.unsigned_tx.input[0] = one_key_psbt.unsigned_tx.input[0].clone();
            p.inputs[0] = one_key_psbt.inputs[0].clone();
        });
        refused(
            &wallet,
            &foreign,
            "input 0 names no key of this wallet at a receive or change index",
        );
        let strangers_coin = edit(&unsigned, &|p| {
            let mut parent = p.inputs[0].non_witness_utxo.clone().unwrap();
            let vout = p.unsigned_tx.input[0].previous_output.vout as usize;
            parent.output[vout].script_pubkey = someone.clone();
            p.unsigned_tx.input[0].previous_output.txid = parent.compute_txid();
            p.inputs[0].witness_utxo = Some(parent.output[vout].clone());
            p.inputs[0].non_witness_utxo = Some(parent);
        });
        refused(
            &wallet,
            &strangers_coin,
            "input 0 spends a coin the wallet's keys do not lock there",
        );

        let single = edit(&unsigned, &|p| {
            p.inputs[1].sighash_type = Some(EcdsaSighashType::Single.into())
        });
        refused(
            &wallet,
            &single,
            "input 1 asks for another sighash than ALL",
        );
        let none_sig = edit(&signed, &|p| {
            let sig = p.inputs[0].partial_sigs.values_mut().next().unwrap();
            sig.sighash_type = EcdsaSighashType::None;
        });
        refused(
            &wallet,
            &none_sig,
            "input 0 has a signature that is not SIGHASH_ALL",
        );

        let secp = Secp256k1::new();
        let outsider = SecretKey::from_slice(&[0x55; 32]).unwrap();
        let outsider_key = PublicKey::new(outsider.public_key(&secp));
        let outsider_sig = edit(&signed, &|p| {
            let (message, _) = p
                .sighash_ecdsa(0, &mut SighashCache::new(&p.unsigned_tx))
                .unwrap();
            let sig = bitcoin::ecdsa::Signature::sighash_all(secp.sign_ecdsa(&message, &outsider));
            p.inputs[0].partial_sigs.insert(outsider_key, sig);
        });
        refused(
            &wallet,
            &outsider_sig,
            "input 0 has a signature by a key outside this wallet",
        );
        let forged = edit(&signed, &|p| {
            let input = &mut p.inputs[0];
            let key = *input.partial_sigs.keys().next().unwrap();
            let origin = input.bip32_derivation[&key.inner].1.clone();
            let secret = soft_master(1)
                .derive_priv(&secp, &origin)
                .unwrap()
                .private_key;
            let other = secp.sign_ecdsa(&Message::from_digest([0x66; 32]), &secret);
            input.partial_sigs.get_mut(&key).unwrap().signature = other;
        });
        refused(
            &wallet,
            &forged,
            "input 0 has a signature that does not verify",
        );

        let finalized = edit(&unsigned, &|p| {
            p.inputs[1].final_script_witness = Some(Witness::new())
        });
        refused(&wallet, &finalized, "input 1 is already finalized");
        let named_twice = edit(&unsigned, &|p| {
            let origin = p.inputs[0]
                .bip32_derivation
                .values()
                .next()
                .unwrap()
                .clone();
            p.inputs[0]
                .bip32_derivation
                .insert(outsider_key.inner, origin);
        });
        refused(
            &wallet,
            &named_twice,
            "input 0 names keys other than exactly this wallet's",
        );
        let extra_xpub = edit(&unsigned, &|p| {
            let master = soft_master(9);
            let path = DerivationPath::from_str("m/48'/1'/0'/2'").unwrap();
            let xpub = Xpub::from_priv(&secp, &master.derive_priv(&secp, &path).unwrap());
            p.xpub.insert(xpub, (fingerprint(&master), path));
        });
        refused(
            &wallet,
            &extra_xpub,
            "its global fields are not exactly this wallet's account keys",
        );
        let locked = edit(&unsigned, &|p| {
            p.unsigned_tx.lock_time = LockTime::from_consensus(500_000)
        });
        refused(&wallet, &locked, "it is not version 2 with lock time 0");

        // Taproot: only the default sighash, and only a signature that verifies.
        let taproot = one_key(AddressType::Taproot);
        let tr_wallet = Described::parse(&taproot).unwrap();
        let tr_fake = funded(&taproot);
        let tr_draft = drafted_on(&tr_fake, &taproot, SendAmount::Exact(30_000), RATE).unwrap();
        let tr_signed = signed_by(&tr_draft.psbt, &[abandon_master(Network::Testnet4)]);
        let tr_all = edit(&tr_signed, &|p| {
            p.inputs[0].tap_key_sig.as_mut().unwrap().sighash_type = TapSighashType::All
        });
        refused(
            &tr_wallet,
            &tr_all,
            "input 0 has a signature that is not the default sighash",
        );
        let tr_forged = edit(&tr_signed, &|p| {
            let sig = p.inputs[0].tap_key_sig.as_mut().unwrap();
            let mut bytes = sig.signature.serialize();
            bytes[10] ^= 1;
            sig.signature = bitcoin::secp256k1::schnorr::Signature::from_slice(&bytes).unwrap();
        });
        refused(
            &tr_wallet,
            &tr_forged,
            "input 0 has a signature that does not verify",
        );
        assert_eq!(
            review(&tr_wallet, &tr_signed, &tr_fake.store)
                .unwrap()
                .signed
                .len(),
            1
        );
    }

    #[test]
    fn a_draft_refuses_what_a_phone_payment_refuses() {
        let descriptor = two_of_three();
        let fake = funded(&descriptor);
        for (amount, rate, why) in [
            (
                SendAmount::Exact(100),
                RATE,
                "amount 100 is below the network's 294 sat dust floor",
            ),
            (SendAmount::Exact(30_000), 0, "fee rate 0 is below 1 sat/vB"),
            (
                SendAmount::Exact(30_000),
                10_001,
                "fee rate 10001 is above the network's 10000 sat/vB limit",
            ),
            (
                SendAmount::Exact(1_000_000),
                RATE,
                "insufficient funds: have 70000 confirmed",
            ),
        ] {
            let refused = drafted_on(&fake, &descriptor, amount, rate).unwrap_err();
            assert!(refused.to_string().contains(why), "{refused}");
        }
        let all = drafted_on(&fake, &descriptor, SendAmount::All, RATE).unwrap();
        assert_eq!((all.payments.len(), all.change_sats), (1, 0));
        assert_eq!(all.payments[0].sats + all.fee_sats, 70_000);
        let mainnet = wallet_review(descriptor, KeyNetwork::Main, all.psbt).unwrap_err();
        assert!(mainnet.to_string().contains("mainnet"), "{mainnet}");
    }

    #[test]
    fn the_exports_review_and_finish_a_psbt_given_as_text() {
        let descriptor = two_of_three();
        let fake = funded(&descriptor);
        let draft = drafted_on(&fake, &descriptor, SendAmount::Exact(30_000), RATE).unwrap();
        let signed = signed_by(&draft.psbt, &[soft_master(2), soft_master(3)]).to_string();
        let read = wallet_review(descriptor.clone(), KeyNetwork::Test, signed.clone()).unwrap();
        assert_eq!(read.signed.len(), 2);
        assert_eq!(
            (read.payments.clone(), read.fee_sats),
            (draft.payments.clone(), draft.fee_sats)
        );
        let ready = wallet_finish(
            descriptor.clone(),
            KeyNetwork::Test,
            signed,
            draft.unsigned_txid.clone(),
        )
        .unwrap();
        assert_eq!(
            ready.txid, draft.unsigned_txid,
            "every input is SegWit, so the id stays"
        );
        let tx: Transaction = bitcoin::consensus::encode::deserialize_hex(&ready.hex).unwrap();
        assert_eq!(tx.compute_txid().to_string(), ready.txid);
        let not_psbt = wallet_review(descriptor, KeyNetwork::Test, "cHNidP8".into()).unwrap_err();
        assert!(
            not_psbt
                .to_string()
                .contains("not this wallet's payment: it is not a PSBT")
        );
    }

    // The book may list a wallet's keys in another order than the draft's descriptor did. The witness script, the
    // origins and the global xpubs do not depend on it, so the payment reviews and finishes the same.
    #[test]
    fn a_draft_reviews_and_finishes_under_its_wallet_listed_in_another_key_order() {
        let fake = funded(&two_of_three());
        let draft = drafted_on(&fake, &two_of_three(), SendAmount::Exact(30_000), RATE).unwrap();
        let reordered = Described::parse(&multisig(2, &[3, 1, 2])).unwrap();
        let signed = signed_by(&draft.psbt, &[soft_master(1), soft_master(2)]);
        let read = review(&reordered, &signed, &fake.store).unwrap();
        assert_eq!(
            (read.payments, read.fee_sats, read.unsigned_txid),
            (draft.payments, draft.fee_sats, draft.unsigned_txid.clone())
        );
        let who = [soft_master(1), soft_master(2)].map(|m| fingerprint(&m).to_string());
        assert_eq!(read.signed, who);
        assert!(finish_as_shown(&reordered, &signed, &draft.unsigned_txid, &fake.store).is_ok());
    }

    // Change a scan of the wallet never asks about drops out of every balance and history. A phone that has not
    // scanned the wallet asks change 0 to 19 (GAP 20), so a payment that sends change further is refused.
    #[test]
    fn a_payment_whose_change_no_scan_would_find_is_refused() {
        let descriptor = two_of_three();
        let wallet = Described::parse(&descriptor).unwrap();
        let fake = funded(&descriptor);
        let draft = drafted_on(&fake, &descriptor, SendAmount::Exact(30_000), RATE).unwrap();
        let change_at = |index: u32| {
            let mut psbt = Psbt::from_str(&draft.psbt).unwrap();
            psbt.unsigned_tx.output[1].script_pubkey =
                wallet.at(Chain::Internal, index).unwrap().script_pubkey();
            psbt.outputs[1] = wallet.change(index).unwrap();
            signed_by(&psbt.to_string(), &[soft_master(1), soft_master(2)]).to_string()
        };
        let finish = |psbt: String| {
            let id = Psbt::from_str(&psbt).unwrap().unsigned_tx.compute_txid();
            wallet_finish(descriptor.clone(), KeyNetwork::Test, psbt, id.to_string())
        };
        assert!(
            finish(change_at(19)).is_ok(),
            "19 is the last change a scan asks"
        );
        for index in [20, 2_000_000_000] {
            let why = format!(
                "output 1 is change at index {index}, past 19, the last change index a scan of this wallet looks at"
            );
            let reviewed = wallet_review(descriptor.clone(), KeyNetwork::Test, change_at(index));
            assert!(
                matches!(&reviewed, Err(e) if e.to_string().contains(&why)),
                "review of change at {index}: {:?}",
                reviewed.map(|read| read.change_sats)
            );
            let finished = finish(change_at(index));
            assert!(
                matches!(&finished, Err(e) if e.to_string().contains(&why)),
                "finish of change at {index}: {:?}",
                finished.map(|ready| ready.txid)
            );
        }
    }

    // The bound is this phone's scan of the wallet. Once change 0 is proven used the scan looks to change 20, and a
    // draft's change, the next unused index, sits inside it.
    #[test]
    fn the_change_bound_follows_this_phones_scan_of_the_wallet() {
        let descriptor = two_of_three();
        let wallet = Described::parse(&descriptor).unwrap();
        let mut fake = funded(&descriptor);
        fund_multi(&mut fake, &at(&descriptor, Chain::Internal, 0), 9_000, '3');
        let draft = drafted_on(&fake, &descriptor, SendAmount::Exact(30_000), RATE).unwrap();
        let drafted = Psbt::from_str(&draft.psbt).unwrap();
        assert_eq!(
            wallet.claim(drafted.outputs[1].bip32_derivation.values()),
            Some((Chain::Internal, 1)),
            "the draft's change is the next unused index"
        );
        let change_at = |index: u32| {
            let mut psbt = drafted.clone();
            psbt.unsigned_tx.output[1].script_pubkey =
                wallet.at(Chain::Internal, index).unwrap().script_pubkey();
            psbt.outputs[1] = wallet.change(index).unwrap();
            psbt
        };
        assert!(review(&wallet, &change_at(20), &fake.store).is_ok());
        let past = review(&wallet, &change_at(21), &fake.store).unwrap_err();
        assert!(
            past.to_string().contains(
                "output 1 is change at index 21, past 20, the last change index a scan of this wallet looks at"
            ),
            "{past}"
        );
        refused(
            &wallet,
            &change_at(20),
            "output 1 is change at index 20, past 19",
        );
    }

    // `amount_sats` is what the app shows as sent: every payment the review read, wherever the change sits and
    // however many payments there are.
    #[test]
    fn finish_says_what_the_payments_pay_wherever_the_change_sits() {
        let descriptor = two_of_three();
        let fake = funded(&descriptor);
        let draft = drafted_on(&fake, &descriptor, SendAmount::Exact(30_000), RATE).unwrap();
        let mut change_first = Psbt::from_str(&draft.psbt).unwrap();
        change_first.unsigned_tx.output.swap(0, 1);
        change_first.outputs.swap(0, 1);
        let mut split = change_first.clone();
        let payment = split.unsigned_tx.output[1].clone();
        split.unsigned_tx.output[1].value = Amount::from_sat(20_000);
        split.unsigned_tx.output.push(TxOut {
            value: Amount::from_sat(10_000),
            ..payment
        });
        split.outputs.push(psbt::Output::default());
        for psbt in [change_first, split] {
            let signed =
                signed_by(&psbt.to_string(), &[soft_master(1), soft_master(2)]).to_string();
            let read = wallet_review(descriptor.clone(), KeyNetwork::Test, signed.clone()).unwrap();
            let paid: u64 = read.payments.iter().map(|p| p.sats).sum();
            assert_eq!(
                (paid, read.payments.len()),
                (30_000, psbt.outputs.len() - 1)
            );
            let ready = wallet_finish(
                descriptor.clone(),
                KeyNetwork::Test,
                signed,
                read.unsigned_txid,
            )
            .unwrap();
            assert_eq!(
                (ready.amount_sats, ready.fee_sats),
                (30_000, draft.fee_sats),
                "change first, {} payments",
                read.payments.len()
            );
        }
    }

    // A sync holds its wallet through every read, as long as the HTTP timeouts allow on a slow host. The device has
    // signed by then, and review and finish read the change bound the last sync saved, waiting for no sync.
    #[test]
    fn review_and_finish_wait_for_no_sync_of_the_wallet() {
        const HOLD: Duration = Duration::from_secs(3);
        /// The Fake's answers. The first read says it began, then waits until the test releases it or `HOLD` passes.
        struct Slow<'a> {
            fake: WithMulti<'a>,
            first: Mutex<Option<(mpsc::Sender<()>, mpsc::Receiver<()>)>>,
        }
        impl Transport for Slow<'_> {
            fn get(&self, url: &str) -> Result<String, MobileSignerError> {
                let first = self.first.lock().unwrap().take();
                if let Some((inside, release)) = first {
                    inside.send(()).unwrap();
                    let _ = release.recv_timeout(HOLD);
                }
                self.fake.get(url)
            }

            fn broadcast(&self, _: &str, _: &str) -> Result<String, MobileSignerError> {
                unreachable!("a sync posts nothing")
            }
        }

        let descriptor = two_of_three();
        let wallet = Described::parse(&descriptor).unwrap();
        let fake = funded(&descriptor);
        let draft = drafted_on(&fake, &descriptor, SendAmount::Exact(30_000), RATE).unwrap();
        let signed = signed_by(&draft.psbt, &[soft_master(1), soft_master(2)]);
        fake.tick(Duration::from_secs(60));
        let (inside, reading) = mpsc::channel();
        let (release, released) = mpsc::channel();
        let slow = Slow {
            fake: WithMulti(&fake),
            first: Mutex::new(Some((inside, released))),
        };
        std::thread::scope(|scope| {
            let sync = scope.spawn(|| {
                let source = EsploraSource(&slow);
                wallet
                    .synced(&source, KeyNetwork::Test, &|| fake.now(), &fake.store)
                    .map(|_| ())
            });
            reading.recv_timeout(HOLD).expect("the sync reads the host");
            let start = Instant::now();
            let reviewed = review(&wallet, &signed, &fake.store);
            let ready = finish_as_shown(&wallet, &signed, &draft.unsigned_txid, &fake.store);
            let took = start.elapsed();
            let _ = release.send(());
            assert!(
                took < HOLD / 4,
                "review and finish took {took:?} while a sync held the wallet"
            );
            assert_eq!(reviewed.unwrap().signed.len(), 2);
            assert_eq!(ready.unwrap().0.fee_sats, draft.fee_sats);
            assert!(
                sync.join().unwrap().is_ok(),
                "the sync ends once the host answers"
            );
        });
    }

    // The app can be killed while a device signs. After the restart the change bound comes from the wallet's scan file
    // before any sync runs, or with no folder named is the first GAP, and a payment drafted before it still finishes.
    #[test]
    fn a_draft_finishes_after_a_restart_under_the_bound_its_scan_file_holds() {
        let descriptor = two_of_three();
        let wallet = Described::parse(&descriptor).unwrap();
        let mut fake = funded(&descriptor);
        fund_multi(&mut fake, &at(&descriptor, Chain::Internal, 0), 9_000, '3');
        let folder = Folder::new("spend-restart");
        fake.store.use_dir(folder.0.clone());
        let draft = drafted_on(&fake, &descriptor, SendAmount::Exact(30_000), RATE).unwrap();
        let signed = signed_by(&draft.psbt, &[soft_master(1), soft_master(2)]);
        let restarted = ScanStore::default();
        restarted.use_dir(folder.0.clone());
        for store in [&restarted, &ScanStore::default()] {
            let (_, read) = finish_as_shown(&wallet, &signed, &draft.unsigned_txid, store).unwrap();
            assert_eq!(
                (read.payments, read.change_sats),
                (draft.payments.clone(), draft.change_sats)
            );
        }
        let change_at = |index: u32| {
            let mut psbt = Psbt::from_str(&draft.psbt).unwrap();
            psbt.unsigned_tx.output[1].script_pubkey =
                wallet.at(Chain::Internal, index).unwrap().script_pubkey();
            psbt.outputs[1] = wallet.change(index).unwrap();
            psbt
        };
        assert!(review(&wallet, &change_at(20), &restarted).is_ok());
        let past = review(&wallet, &change_at(21), &restarted).unwrap_err();
        assert!(
            past.to_string()
                .contains("output 1 is change at index 21, past 20"),
            "{past}"
        );
    }
}
