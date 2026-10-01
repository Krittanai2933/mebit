//! Checks a PSBT that a hardware wallet signed against the PSBT we asked it
//! to sign. Shared by every device client in [`crate::hw`]: a device is
//! trusted to hold its keys, not to return the transaction we built.

use bitcoin::Psbt;
use bitcoin::bip32::Fingerprint;
use bitcoin::secp256k1::Secp256k1;
use bitcoin::sighash::SighashCache;

/// Why a device's PSBT was refused. Each client maps these onto its own
/// error type, in its own words.
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
pub enum SignatureCheckError {
    #[error("the PSBT came back changed beyond the device's own signatures")]
    PsbtModified,

    #[error("the PSBT came back without any new signature")]
    NoSignatures,

    #[error("input {input} has no signature for the device's key")]
    MissingSignature { input: usize },

    #[error("the signature on input {input} does not verify")]
    InvalidSignature { input: usize },
}

/// Accepts `signed` only if it is `unsigned` plus valid signatures from
/// `signer`'s keys. Four things are checked:
///
/// 1. Nothing but `partial_sigs` changed. Otherwise a faulty device could hand
///    the finaliser a witness script or derivation we never built.
/// 2. Every new signature is for one of `signer`'s keys, and verifies against
///    the sighash computed here, from our own `unsigned` copy.
/// 3. At least one signature was added.
/// 4. Every one of `signer`'s keys got signed.
pub fn verify_device_signatures(
    unsigned: &Psbt,
    signed: Psbt,
    signer: Fingerprint,
) -> Result<Psbt, SignatureCheckError> {
    if signed.inputs.len() != unsigned.inputs.len() {
        return Err(SignatureCheckError::PsbtModified);
    }

    let secp = Secp256k1::verification_only();
    let mut sighashes = SighashCache::new(&unsigned.unsigned_tx);
    let mut stripped = signed.clone();
    let mut added = 0;
    let mut first_unsigned_input = None;
    for (index, (before, after)) in unsigned.inputs.iter().zip(&signed.inputs).enumerate() {
        let ours: Vec<_> = before
            .bip32_derivation
            .iter()
            .filter(|(_, (fingerprint, _))| *fingerprint == signer)
            .map(|(key, _)| *key)
            .collect();

        let kept_earlier_signatures = before
            .partial_sigs
            .iter()
            .all(|(key, signature)| after.partial_sigs.get(key) == Some(signature));
        if !kept_earlier_signatures {
            return Err(SignatureCheckError::PsbtModified);
        }

        for (key, signature) in &after.partial_sigs {
            if before.partial_sigs.contains_key(key) {
                continue;
            }
            if !key.compressed || !ours.contains(&key.inner) {
                return Err(SignatureCheckError::PsbtModified);
            }
            let (message, sighash_type) = unsigned
                .sighash_ecdsa(index, &mut sighashes)
                .map_err(|_| SignatureCheckError::InvalidSignature { input: index })?;
            if signature.sighash_type != sighash_type
                || secp
                    .verify_ecdsa(&message, &signature.signature, &key.inner)
                    .is_err()
            {
                return Err(SignatureCheckError::InvalidSignature { input: index });
            }
            added += 1;
        }

        let all_ours_signed = ours
            .iter()
            .all(|ours| after.partial_sigs.keys().any(|key| key.inner == *ours));
        if !all_ours_signed && first_unsigned_input.is_none() {
            first_unsigned_input = Some(index);
        }
        stripped.inputs[index].partial_sigs = before.partial_sigs.clone();
    }

    if stripped != *unsigned {
        return Err(SignatureCheckError::PsbtModified);
    }
    if added == 0 {
        return Err(SignatureCheckError::NoSignatures);
    }
    if let Some(input) = first_unsigned_input {
        return Err(SignatureCheckError::MissingSignature { input });
    }
    Ok(signed)
}

/// Every device client trusts these checks, so they are tested here and not
/// only through one client's tests.
#[cfg(test)]
mod tests {
    use std::str::FromStr;

    use bitcoin::bip32::{DerivationPath, Xpriv, Xpub};
    use bitcoin::hashes::Hash;
    use bitcoin::psbt::raw;
    use bitcoin::secp256k1::{Message, SecretKey};
    use bitcoin::{
        Amount, EcdsaSighashType, Network, OutPoint, PublicKey, ScriptBuf, Sequence, Transaction,
        TxIn, TxOut, Txid, Witness, absolute, ecdsa, transaction,
    };
    use miniscript::psbt::PsbtExt;
    use miniscript::{Descriptor, DescriptorPublicKey};

    use super::*;
    use crate::keys::{generate_master_xpriv, generate_mnemonic, generate_seed};

    /// A key holder at `m/48'/1'/0'/2'`, from fixed entropy. Holder 0 plays
    /// the device.
    struct Holder {
        master: Xpriv,
        fingerprint: Fingerprint,
        account: Xpub,
    }

    fn holder(entropy: u8) -> Holder {
        let secp = Secp256k1::new();
        let mnemonic = generate_mnemonic(&[entropy; 32]).unwrap();
        let master =
            generate_master_xpriv(Network::Testnet, &generate_seed(&mnemonic, "")).unwrap();
        let path = DerivationPath::from_str("m/48'/1'/0'/2'").unwrap();
        Holder {
            fingerprint: master.fingerprint(&secp),
            account: Xpub::from_priv(&secp, &master.derive_priv(&secp, &path).unwrap()),
            master,
        }
    }

    /// A two-input spend from the 2-of-3 `wsh(sortedmulti)` of `holders`.
    fn vault_psbt(holders: &[Holder; 3]) -> Psbt {
        let keys: Vec<String> = holders
            .iter()
            .map(|h| format!("[{}/48'/1'/0'/2']{}/0/*", h.fingerprint, h.account))
            .collect();
        let descriptor = Descriptor::<DescriptorPublicKey>::from_str(&format!(
            "wsh(sortedmulti(2,{}))",
            keys.join(",")
        ))
        .unwrap();
        let at = |index| descriptor.at_derivation_index(index).unwrap();
        let unsigned_tx = Transaction {
            version: transaction::Version::TWO,
            lock_time: absolute::LockTime::ZERO,
            input: (0..2)
                .map(|vout| TxIn {
                    previous_output: OutPoint {
                        txid: Txid::from_byte_array([7; 32]),
                        vout,
                    },
                    script_sig: ScriptBuf::new(),
                    sequence: Sequence::ENABLE_RBF_NO_LOCKTIME,
                    witness: Witness::new(),
                })
                .collect(),
            output: vec![TxOut {
                value: Amount::from_sat(30_000),
                script_pubkey: at(1000).script_pubkey(),
            }],
        };
        let mut psbt = Psbt::from_unsigned_tx(unsigned_tx).unwrap();
        for index in 0..2 {
            psbt.inputs[index as usize].witness_utxo = Some(TxOut {
                value: Amount::from_sat(20_000),
                script_pubkey: at(index).script_pubkey(),
            });
            psbt.update_input_with_descriptor(index as usize, &at(index))
                .unwrap();
        }
        psbt
    }

    fn signed_by(holder: &Holder, psbt: &Psbt) -> Psbt {
        let mut signed = psbt.clone();
        signed.sign(&holder.master, &Secp256k1::new()).unwrap();
        signed
    }

    /// `holder`'s key on `input`, with its private half.
    fn key_on(holder: &Holder, psbt: &Psbt, input: usize) -> (SecretKey, PublicKey) {
        let (key, (_, path)) = psbt.inputs[input]
            .bip32_derivation
            .iter()
            .find(|(_, (fingerprint, _))| *fingerprint == holder.fingerprint)
            .unwrap();
        let secret = holder
            .master
            .derive_priv(&Secp256k1::new(), path)
            .unwrap()
            .private_key;
        (secret, PublicKey::new(*key))
    }

    #[test]
    fn accepts_exactly_the_devices_signatures() {
        let holders = [holder(0), holder(1), holder(2)];
        let device = &holders[0];
        let psbt = vault_psbt(&holders);
        let signed = signed_by(device, &psbt);
        assert_eq!(
            verify_device_signatures(&psbt, signed.clone(), device.fingerprint),
            Ok(signed)
        );

        // A cosigner went first: its signatures pass through untouched.
        let cosigned = signed_by(&holders[1], &psbt);
        let both = signed_by(device, &cosigned);
        assert_eq!(
            verify_device_signatures(&cosigned, both.clone(), device.fingerprint),
            Ok(both)
        );
    }

    #[test]
    fn refuses_everything_else() {
        use SignatureCheckError::*;

        let holders = [holder(0), holder(1), holder(2)];
        let device = &holders[0];
        let psbt = vault_psbt(&holders);
        let signed = signed_by(device, &psbt);
        let tampered = |change: &dyn Fn(&mut Psbt)| {
            let mut copy = signed.clone();
            change(&mut copy);
            copy
        };
        let (secret, key) = key_on(device, &psbt, 0);
        let secp = Secp256k1::new();
        let wrong_message =
            ecdsa::Signature::sighash_all(secp.sign_ecdsa(&Message::from_digest([7; 32]), &secret));
        let mut sighash_none = signed.inputs[0].partial_sigs[&key];
        sighash_none.sighash_type = EcdsaSighashType::None;
        let uncompressed = PublicKey {
            compressed: false,
            inner: key.inner,
        };

        let cases: Vec<(&str, Psbt, SignatureCheckError)> = vec![
            ("nothing signed", psbt.clone(), NoSignatures),
            (
                "signed by another holder",
                signed_by(&holders[1], &psbt),
                PsbtModified,
            ),
            (
                "an output changed",
                tampered(&|p| p.unsigned_tx.output[0].value = Amount::from_sat(1)),
                PsbtModified,
            ),
            (
                "a witness script changed",
                tampered(&|p| p.inputs[0].witness_script = Some(ScriptBuf::new())),
                PsbtModified,
            ),
            (
                "a key origin dropped",
                tampered(&|p| p.inputs[1].bip32_derivation.clear()),
                PsbtModified,
            ),
            (
                "an unknown global field added",
                tampered(&|p| {
                    let key = raw::Key {
                        type_value: 0xf0,
                        key: Vec::new(),
                    };
                    p.unknown.insert(key, vec![1]);
                }),
                PsbtModified,
            ),
            (
                "an input dropped",
                tampered(&|p| {
                    p.inputs.pop();
                    p.unsigned_tx.input.pop();
                }),
                PsbtModified,
            ),
            (
                "the device's key, uncompressed",
                tampered(&|p| {
                    let signature = p.inputs[0].partial_sigs.remove(&key).unwrap();
                    p.inputs[0].partial_sigs.insert(uncompressed, signature);
                }),
                PsbtModified,
            ),
            (
                "a signature over another message",
                tampered(&|p| {
                    p.inputs[0].partial_sigs.insert(key, wrong_message);
                }),
                InvalidSignature { input: 0 },
            ),
            (
                "SIGHASH_NONE",
                tampered(&|p| {
                    p.inputs[0].partial_sigs.insert(key, sighash_none);
                }),
                InvalidSignature { input: 0 },
            ),
            (
                "one input left unsigned",
                tampered(&|p| p.inputs[1].partial_sigs.clear()),
                MissingSignature { input: 1 },
            ),
        ];
        for (case, returned, expected) in cases {
            assert_eq!(
                verify_device_signatures(&psbt, returned, device.fingerprint),
                Err(expected),
                "{case}"
            );
        }

        // A cosigner's earlier signature may be neither dropped nor replaced.
        let cosigned = signed_by(&holders[1], &psbt);
        let (_, cosigner_key) = key_on(&holders[1], &psbt, 0);
        let both = signed_by(device, &cosigned);
        let mut dropped = both.clone();
        dropped.inputs[0].partial_sigs.remove(&cosigner_key);
        let mut replaced = both;
        replaced.inputs[0]
            .partial_sigs
            .insert(cosigner_key, wrong_message);
        for (case, returned) in [("dropped", dropped), ("replaced", replaced)] {
            assert_eq!(
                verify_device_signatures(&cosigned, returned, device.fingerprint),
                Err(PsbtModified),
                "{case}"
            );
        }
    }
}
