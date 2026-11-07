//! How the hot wallet's single key locks and spends its coins. `AddressType`'s methods are the only
//! place in the crate that matches on it: `hot_wallet` asks which path, which address, how heavy a
//! spend is, and how to sign and check one.

use bitcoin::bip32::{ChildNumber, DerivationPath};
use bitcoin::hashes::Hash;
use bitcoin::script::PushBytesBuf;
use bitcoin::secp256k1::{All, Message, Secp256k1, Verification};
use bitcoin::sighash::{EcdsaSighashType, SighashCache};
use bitcoin::transaction::InputWeightPrediction;
use bitcoin::{
    Address, Amount, CompressedPublicKey, Network, ScriptBuf, Transaction, TxOut, Witness,
};

use crate::MobileSignerError;
use crate::hot_wallet::{OwnKey, full_size};

/// One wallet has one type for life: every key of an account carries the account's type, so no
/// transaction mixes two. The default is the type every wallet saved before this setting had.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub enum AddressType {
    /// BIP-49 P2SH-P2WPKH. The script_sig pushes the P2WPKH script; the witness is as Native SegWit's.
    NestedSegwit,
    /// BIP-84 P2WPKH.
    #[default]
    NativeSegwit,
}

/// What `AddressType::spend` puts in one input.
pub(crate) struct Spend {
    pub(crate) script_sig: ScriptBuf,
    pub(crate) witness: Witness,
}

impl AddressType {
    #[cfg(test)]
    pub(crate) const ALL: [AddressType; 2] = [Self::NestedSegwit, Self::NativeSegwit];

    /// The BIP-43 purpose. The coin type stays 1' on both networks.
    pub(crate) const fn purpose(self) -> u32 {
        match self {
            Self::NestedSegwit => 49,
            Self::NativeSegwit => 84,
        }
    }

    /// `m/{purpose}'/1'/0'`.
    pub(crate) fn account_path(self) -> DerivationPath {
        [self.purpose(), 1, 0]
            .map(|index| ChildNumber::Hardened { index })
            .as_ref()
            .into()
    }

    /// What one input of this type adds to a transaction, exact for what `spend` writes: ECDSA is
    /// ground to 72 bytes with the sighash byte by `full_size`.
    pub(crate) const fn input_weight(self) -> InputWeightPrediction {
        match self {
            // A 23-byte script_sig: one push of the 22-byte P2WPKH script.
            Self::NestedSegwit => InputWeightPrediction::from_slice(23, &[72, 33]),
            Self::NativeSegwit => InputWeightPrediction::P2WPKH_MAX,
        }
    }

    /// The address `pubkey` receives at.
    pub(crate) fn address<C: Verification>(
        self,
        _secp: &Secp256k1<C>,
        pubkey: &CompressedPublicKey,
        network: Network,
    ) -> Address {
        match self {
            Self::NestedSegwit => Address::p2shwpkh(pubkey, network),
            Self::NativeSegwit => Address::p2wpkh(pubkey, network),
        }
    }

    /// Input `i` of `cache`'s unsigned transaction signed with `key`. Every input is signed on the
    /// unsigned transaction before any is written, which is sound for every type: no sighash here
    /// commits to another input's script_sig or witness. `prevouts` holds every input's coin. The
    /// same payment always signs the same bytes.
    pub(crate) fn spend(
        self,
        secp: &Secp256k1<All>,
        cache: &mut SighashCache<&Transaction>,
        i: usize,
        prevouts: &[TxOut],
        key: &OwnKey,
    ) -> Result<Spend, MobileSignerError> {
        let value = prevouts
            .get(i)
            .ok_or_else(|| bad("missing prevout".into()))?
            .value;
        match self {
            Self::NestedSegwit => Ok(Spend {
                script_sig: nested_script_sig(key)?,
                witness: wpkh_witness(secp, cache, i, value, key)?,
            }),
            Self::NativeSegwit => Ok(Spend {
                script_sig: ScriptBuf::new(),
                witness: wpkh_witness(secp, cache, i, value, key)?,
            }),
        }
    }

    /// Whether input `i` of the signed transaction behind `cache` is spent exactly as `spend` spends
    /// it with `key`: the same script_sig and witness shape, and a valid SIGHASH_ALL signature by `key`.
    pub(crate) fn spent_by(
        self,
        cache: &mut SighashCache<&Transaction>,
        i: usize,
        prevouts: &[TxOut],
        key: &OwnKey,
    ) -> bool {
        let tx: &Transaction = cache.transaction();
        let (Some(input), Some(prevout)) = (tx.input.get(i), prevouts.get(i)) else {
            return false;
        };
        let (script_sig, witness) = (input.script_sig.clone(), input.witness.clone());
        match self {
            Self::NestedSegwit => {
                nested_script_sig(key).is_ok_and(|expected| script_sig == expected)
                    && wpkh_witness_signed(cache, i, prevout.value, &witness, key)
            }
            Self::NativeSegwit => {
                script_sig.is_empty() && wpkh_witness_signed(cache, i, prevout.value, &witness, key)
            }
        }
    }
}

fn bad(what: String) -> MobileSignerError {
    MobileSignerError::InvalidPsbtJson(what)
}

/// The P2WPKH script of `key`: Native SegWit's script_pubkey, Nested SegWit's redeem script, and
/// the BIP-143 script code of both. Nested SegWit signs over it, never over its P2SH script, which
/// `p2wpkh_signature_hash` refuses.
fn wpkh_script(key: &OwnKey) -> ScriptBuf {
    ScriptBuf::new_p2wpkh(&key.pubkey.wpubkey_hash())
}

/// Nested SegWit's script_sig: one push of the P2WPKH script.
fn nested_script_sig(key: &OwnKey) -> Result<ScriptBuf, MobileSignerError> {
    let redeem =
        PushBytesBuf::try_from(wpkh_script(key).into_bytes()).map_err(|e| bad(e.to_string()))?;
    Ok(ScriptBuf::builder().push_slice(redeem).into_script())
}

/// The BIP-143 witness of `key` for input `i`: its SIGHASH_ALL signature and its compressed key.
fn wpkh_witness(
    secp: &Secp256k1<All>,
    cache: &mut SighashCache<&Transaction>,
    i: usize,
    value: Amount,
    key: &OwnKey,
) -> Result<Witness, MobileSignerError> {
    let sighash = cache
        .p2wpkh_signature_hash(i, &wpkh_script(key), value, EcdsaSighashType::All)
        .map_err(|e| bad(e.to_string()))?;
    let sig = ecdsa_all(secp, sighash.to_byte_array(), key)?;
    Ok(Witness::from_slice(&[
        sig.as_slice(),
        &key.pubkey.to_bytes(),
    ]))
}

/// A 72-byte ECDSA signature of `digest` by `key` with SIGHASH_ALL appended.
fn ecdsa_all(
    secp: &Secp256k1<All>,
    digest: [u8; 32],
    key: &OwnKey,
) -> Result<Vec<u8>, MobileSignerError> {
    let sig = full_size(secp, &Message::from_digest(digest), &key.privkey.inner)?;
    let mut der = sig.serialize_der().to_vec();
    der.push(EcdsaSighashType::All as u8);
    Ok(der)
}

/// Whether `witness` is exactly what `wpkh_witness` writes for input `i`: a SIGHASH_ALL signature by
/// `key` over its BIP-143 sighash and then `key`'s compressed public key.
fn wpkh_witness_signed(
    cache: &mut SighashCache<&Transaction>,
    i: usize,
    value: Amount,
    witness: &Witness,
    key: &OwnKey,
) -> bool {
    let sighash = cache.p2wpkh_signature_hash(i, &wpkh_script(key), value, EcdsaSighashType::All);
    witness.len() == 2
        && witness.nth(1) == Some(key.pubkey.to_bytes().as_slice())
        && sighash.is_ok_and(|sighash| {
            witness
                .nth(0)
                .is_some_and(|sig| ecdsa_all_signed(sig, sighash.to_byte_array(), key))
        })
}

/// Whether `sig` is a DER signature of `digest` by `key` with SIGHASH_ALL appended.
fn ecdsa_all_signed(sig: &[u8], digest: [u8; 32], key: &OwnKey) -> bool {
    bitcoin::ecdsa::Signature::from_slice(sig).is_ok_and(|sig| {
        sig.sighash_type == EcdsaSighashType::All
            && Secp256k1::verification_only()
                .verify_ecdsa(&Message::from_digest(digest), &sig.signature, &key.pubkey.0)
                .is_ok()
    })
}
