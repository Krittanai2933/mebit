//! How the hot wallet's single key locks and spends its coins. `AddressType`'s methods are the only
//! place in the crate that matches on it: `hot_wallet` asks which path, which address, how heavy a
//! spend is, and how to sign and check one.

use bitcoin::bip32::{ChildNumber, DerivationPath};
use bitcoin::hashes::Hash;
use bitcoin::secp256k1::{All, Message, Secp256k1, Verification};
use bitcoin::sighash::{EcdsaSighashType, SighashCache};
use bitcoin::transaction::InputWeightPrediction;
use bitcoin::{Address, CompressedPublicKey, Network, ScriptBuf, Transaction, TxOut, Witness};

use crate::MobileSignerError;
use crate::hot_wallet::{OwnKey, full_size};

/// One wallet has one type for life: every key of an account carries the account's type, so no
/// transaction mixes two. The default is the type every wallet saved before this setting had.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub enum AddressType {
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
    /// The BIP-43 purpose. The coin type stays 1' on both networks.
    pub(crate) const fn purpose(self) -> u32 {
        match self {
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
        let bad = |e: String| MobileSignerError::InvalidPsbtJson(e);
        let value = prevouts
            .get(i)
            .ok_or_else(|| bad("missing prevout".into()))?
            .value;
        match self {
            Self::NativeSegwit => {
                let sighash = cache
                    .p2wpkh_signature_hash(i, &wpkh_script(key), value, EcdsaSighashType::All)
                    .map_err(|e| bad(e.to_string()))?;
                let sig = ecdsa_all(secp, sighash.to_byte_array(), key)?;
                Ok(Spend {
                    script_sig: ScriptBuf::new(),
                    witness: Witness::from_slice(&[sig.as_slice(), &key.pubkey.to_bytes()]),
                })
            }
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
            Self::NativeSegwit => {
                let sighash = cache.p2wpkh_signature_hash(
                    i,
                    &wpkh_script(key),
                    prevout.value,
                    EcdsaSighashType::All,
                );
                script_sig.is_empty()
                    && sighash.is_ok_and(|h| wpkh_witness_signed(&witness, h.to_byte_array(), key))
            }
        }
    }
}

/// The P2WPKH script of `key`: Native SegWit's script_pubkey and its BIP-143 script code.
fn wpkh_script(key: &OwnKey) -> ScriptBuf {
    ScriptBuf::new_p2wpkh(&key.pubkey.wpubkey_hash())
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

/// Whether `witness` is exactly a SIGHASH_ALL signature of `digest` by `key` and then `key`'s
/// compressed public key.
fn wpkh_witness_signed(witness: &Witness, digest: [u8; 32], key: &OwnKey) -> bool {
    witness.len() == 2
        && witness.nth(1) == Some(key.pubkey.to_bytes().as_slice())
        && witness
            .nth(0)
            .is_some_and(|sig| ecdsa_all_signed(sig, digest, key))
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
