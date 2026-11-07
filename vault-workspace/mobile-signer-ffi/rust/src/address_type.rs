//! How the hot wallet's single key locks and spends its coins. `AddressType`'s methods are the only
//! place in the crate that matches on it: `hot_wallet` asks which path, which address, how heavy a
//! spend is, whether its signature commits to the coin's value, and how to sign and check one.

use bitcoin::bip32::{ChildNumber, DerivationPath};
use bitcoin::hashes::Hash;
use bitcoin::key::{Keypair, TapTweak};
use bitcoin::script::{Instruction, PushBytesBuf};
use bitcoin::secp256k1::{All, Message, Secp256k1, Verification, schnorr};
use bitcoin::sighash::{EcdsaSighashType, Prevouts, SighashCache, TapSighashType};
use bitcoin::transaction::InputWeightPrediction;
use bitcoin::{
    Address, Amount, CompressedPublicKey, Network, ScriptBuf, Transaction, TxOut, Witness,
};

use crate::MobileSignerError;
use crate::hot_wallet::{OwnKey, full_size};

/// How the hot wallet's addresses look. One wallet has one type for life: every key of an account
/// carries the account's type, so no transaction mixes two. The default is the type every wallet
/// saved before this setting had.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum AddressType {
    /// BIP-44 P2PKH. The signature and the key go in the script_sig, over the legacy sighash.
    Legacy,
    /// BIP-49 P2SH-P2WPKH. The script_sig pushes the P2WPKH script; the witness is as Native SegWit's.
    NestedSegwit,
    /// BIP-84 P2WPKH.
    #[default]
    NativeSegwit,
    /// BIP-86 P2TR key path with no script tree: one 64-byte Schnorr signature, SIGHASH_DEFAULT.
    Taproot,
}

/// What `AddressType::spend` puts in one input.
pub(crate) struct Spend {
    pub(crate) script_sig: ScriptBuf,
    pub(crate) witness: Witness,
}

impl AddressType {
    #[cfg(test)]
    pub(crate) const ALL: [AddressType; 4] = [
        Self::Legacy,
        Self::NestedSegwit,
        Self::NativeSegwit,
        Self::Taproot,
    ];

    /// The BIP-43 purpose. The coin type stays 1' on both networks.
    pub(crate) const fn purpose(self) -> u32 {
        match self {
            Self::Legacy => 44,
            Self::NestedSegwit => 49,
            Self::NativeSegwit => 84,
            Self::Taproot => 86,
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
            // `<sig||01> <pubkey>`: 1 + 72 + 1 + 33 bytes.
            Self::Legacy => InputWeightPrediction::P2PKH_COMPRESSED_MAX,
            // A 23-byte script_sig: one push of the 22-byte P2WPKH script.
            Self::NestedSegwit => InputWeightPrediction::from_slice(23, &[72, 33]),
            Self::NativeSegwit => InputWeightPrediction::P2WPKH_MAX,
            Self::Taproot => InputWeightPrediction::P2TR_KEY_DEFAULT_SIGHASH,
        }
    }

    /// How many bytes the output script that pays one of this type's addresses takes.
    pub(crate) const fn script_len(self) -> usize {
        match self {
            // OP_DUP OP_HASH160 <20> OP_EQUALVERIFY OP_CHECKSIG
            Self::Legacy => 25,
            // OP_HASH160 <20> OP_EQUAL
            Self::NestedSegwit => 23,
            // OP_0 <20>
            Self::NativeSegwit => 22,
            // OP_1 <32>
            Self::Taproot => 34,
        }
    }

    /// Whether a signature of this type commits to the value of the coin it spends. The legacy sighash
    /// does not, so a coin listed at another value still signs a valid payment at another fee; in the
    /// other types a wrong value only makes the signature invalid.
    pub(crate) const fn commits_to_value(self) -> bool {
        match self {
            Self::Legacy => false,
            Self::NestedSegwit | Self::NativeSegwit | Self::Taproot => true,
        }
    }

    /// The address `pubkey` receives at.
    pub(crate) fn address<C: Verification>(
        self,
        secp: &Secp256k1<C>,
        pubkey: &CompressedPublicKey,
        network: Network,
    ) -> Address {
        match self {
            Self::Legacy => Address::p2pkh(pubkey, network),
            Self::NestedSegwit => Address::p2shwpkh(pubkey, network),
            Self::NativeSegwit => Address::p2wpkh(pubkey, network),
            Self::Taproot => Address::p2tr(secp, pubkey.0.x_only_public_key().0, None, network),
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
            Self::Legacy => {
                let sighash = cache
                    .legacy_signature_hash(i, &key.address.script_pubkey(), ALL)
                    .map_err(|e| bad(e.to_string()))?;
                let sig = ecdsa_all(secp, sighash.to_byte_array(), key)?;
                Ok(Spend {
                    script_sig: legacy_script_sig(sig, key)?,
                    witness: Witness::new(),
                })
            }
            Self::NestedSegwit => Ok(Spend {
                script_sig: nested_script_sig(key)?,
                witness: wpkh_witness(secp, cache, i, value, key)?,
            }),
            Self::NativeSegwit => Ok(Spend {
                script_sig: ScriptBuf::new(),
                witness: wpkh_witness(secp, cache, i, value, key)?,
            }),
            Self::Taproot => {
                let sighash = cache
                    .taproot_key_spend_signature_hash(
                        i,
                        &Prevouts::All(prevouts),
                        TapSighashType::Default,
                    )
                    .map_err(|e| bad(e.to_string()))?;
                let keypair = Keypair::from_secret_key(secp, &key.privkey.inner)
                    .tap_tweak(secp, None)
                    .to_keypair();
                // No aux randomness, so the same payment signs the same bytes, as RFC 6979 does for ECDSA.
                let sig = secp.sign_schnorr_no_aux_rand(
                    &Message::from_digest(sighash.to_byte_array()),
                    &keypair,
                );
                Ok(Spend {
                    script_sig: ScriptBuf::new(),
                    witness: Witness::from_slice(&[sig.as_ref()]),
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
            Self::Legacy => {
                // Exactly two pushes, written back byte for byte, so no other encoding of them passes.
                let pushes: Vec<Vec<u8>> = script_sig
                    .instructions()
                    .map_while(|ins| match ins {
                        Ok(Instruction::PushBytes(data)) => Some(data.as_bytes().to_vec()),
                        _ => None,
                    })
                    .collect();
                let [sig, _] = pushes.as_slice() else {
                    return false;
                };
                let sighash = cache.legacy_signature_hash(i, &key.address.script_pubkey(), ALL);
                witness.is_empty()
                    && legacy_script_sig(sig.clone(), key)
                        .is_ok_and(|expected| script_sig == expected)
                    && sighash.is_ok_and(|h| ecdsa_all_signed(sig, h.to_byte_array(), key))
            }
            Self::NestedSegwit => {
                nested_script_sig(key).is_ok_and(|expected| script_sig == expected)
                    && wpkh_witness_signed(cache, i, prevout.value, &witness, key)
            }
            Self::NativeSegwit => {
                script_sig.is_empty() && wpkh_witness_signed(cache, i, prevout.value, &witness, key)
            }
            Self::Taproot => {
                let secp = Secp256k1::verification_only();
                let (output_key, _) = key.pubkey.0.x_only_public_key().0.tap_tweak(&secp, None);
                let sighash = cache.taproot_key_spend_signature_hash(
                    i,
                    &Prevouts::All(prevouts),
                    TapSighashType::Default,
                );
                // 64 bytes is SIGHASH_DEFAULT; a 65th byte names another sighash type.
                let sig = witness
                    .nth(0)
                    .filter(|sig| sig.len() == 64)
                    .map(schnorr::Signature::from_slice);
                script_sig.is_empty()
                    && witness.len() == 1
                    && matches!((sig, sighash), (Some(Ok(sig)), Ok(sighash)) if secp
                        .verify_schnorr(
                            &sig,
                            &Message::from_digest(sighash.to_byte_array()),
                            &output_key.to_x_only_public_key(),
                        )
                        .is_ok())
            }
        }
    }
}

/// SIGHASH_ALL as the legacy sighash takes it.
const ALL: u32 = EcdsaSighashType::All as u32;

/// Legacy's script_sig: a push of `sig`, the signature with its sighash byte, then a push of `key`'s
/// compressed public key.
fn legacy_script_sig(sig: Vec<u8>, key: &OwnKey) -> Result<ScriptBuf, MobileSignerError> {
    let sig = PushBytesBuf::try_from(sig).map_err(|e| bad(e.to_string()))?;
    let pubkey =
        PushBytesBuf::try_from(key.pubkey.to_bytes().to_vec()).map_err(|e| bad(e.to_string()))?;
    Ok(ScriptBuf::builder()
        .push_slice(sig)
        .push_slice(pubkey)
        .into_script())
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
