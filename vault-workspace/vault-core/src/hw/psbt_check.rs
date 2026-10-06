//! Checks a PSBT that a hardware wallet signed against the PSBT we asked it
//! to sign. Shared by every device client in [`crate::hw`]: a device is
//! trusted to hold its keys, not to return the transaction we built.

use bitcoin::bip32::{ChildNumber, Fingerprint};
use bitcoin::key::TapTweak;
use bitcoin::secp256k1::{Message, Secp256k1, XOnlyPublicKey};
use bitcoin::sighash::{Prevouts, SighashCache};
use bitcoin::{EcdsaSighashType, Psbt, ScriptBuf, TapSighashType, TxOut};

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

    #[error("only ECDSA ALL and BIP86 key-path DEFAULT signing policies are supported")]
    UnsupportedPolicy,

    #[error("input {input} has missing or inconsistent previous transaction data")]
    InvalidPrevout { input: usize },

    #[error("input {input} does not bind one BIP86 origin to its tweaked Taproot output key")]
    InvalidTaproot { input: usize },
}

enum InputPolicy {
    Ecdsa,
    Bip86 {
        output_key: XOnlyPublicKey,
        signer: Fingerprint,
    },
}

struct SigningPolicy<'a> {
    inputs: Vec<InputPolicy>,
    prevouts: Vec<&'a TxOut>,
}

/// Refuses unsupported sighashes and Taproot script paths before contacting
/// a signer. Prior signatures are allowed under the same signing policy.
/// Taproot DEFAULT commits to every prevout, so every input must carry the
/// full previous transaction authenticated by its outpoint.
pub fn validate_signing_policy(psbt: &Psbt) -> Result<(), SignatureCheckError> {
    signing_policy(psbt).map(|_| ())
}

fn signing_policy(psbt: &Psbt) -> Result<SigningPolicy<'_>, SignatureCheckError> {
    if psbt.inputs.len() != psbt.unsigned_tx.input.len()
        || psbt.outputs.len() != psbt.unsigned_tx.output.len()
    {
        return Err(SignatureCheckError::PsbtModified);
    }

    if psbt.outputs.iter().any(|output| {
        output.tap_tree.is_some()
            || output
                .tap_key_origins
                .values()
                .any(|(leaves, _)| !leaves.is_empty())
    }) {
        return Err(SignatureCheckError::UnsupportedPolicy);
    }

    let prevouts: Vec<_> = psbt
        .inputs
        .iter()
        .zip(&psbt.unsigned_tx.input)
        .enumerate()
        .map(|(index, (input, txin))| {
            let invalid = || SignatureCheckError::InvalidPrevout { input: index };
            let previous = match input.non_witness_utxo.as_ref() {
                Some(tx) => {
                    if tx.compute_txid() != txin.previous_output.txid {
                        return Err(invalid());
                    }
                    Some(
                        tx.output
                            .get(txin.previous_output.vout as usize)
                            .ok_or_else(invalid)?,
                    )
                }
                None => None,
            };
            match (input.witness_utxo.as_ref(), previous) {
                (Some(witness), Some(previous)) if witness != previous => Err(invalid()),
                (_, Some(previous)) => Ok(previous),
                (Some(witness), None) => Ok(witness),
                (None, None) => Err(invalid()),
            }
        })
        .collect::<Result<_, _>>()?;
    if prevouts.iter().any(|utxo| utxo.script_pubkey.is_p2tr()) {
        for (index, input) in psbt.inputs.iter().enumerate() {
            if input.non_witness_utxo.is_none() {
                return Err(SignatureCheckError::InvalidPrevout { input: index });
            }
        }
    }

    let secp = Secp256k1::verification_only();
    let mut policies = Vec::with_capacity(psbt.inputs.len());
    for (index, (input, prevout)) in psbt.inputs.iter().zip(&prevouts).enumerate() {
        if !input.tap_script_sigs.is_empty()
            || !input.tap_scripts.is_empty()
            || input.tap_merkle_root.is_some()
            || input
                .tap_key_origins
                .values()
                .any(|(leaves, _)| !leaves.is_empty())
        {
            return Err(SignatureCheckError::UnsupportedPolicy);
        }
        if prevout.script_pubkey.is_p2tr() {
            if input.taproot_hash_ty() != Ok(TapSighashType::Default)
                || input
                    .tap_key_sig
                    .is_some_and(|sig| sig.sighash_type != TapSighashType::Default)
            {
                return Err(SignatureCheckError::UnsupportedPolicy);
            }
            let invalid = || SignatureCheckError::InvalidTaproot { input: index };
            let internal_key = input.tap_internal_key.ok_or_else(invalid)?;
            if input.tap_key_origins.len() != 1
                || !input.bip32_derivation.is_empty()
                || !input.partial_sigs.is_empty()
                || input.redeem_script.is_some()
                || input.witness_script.is_some()
            {
                return Err(invalid());
            }
            let (_, (fingerprint, path)) = input
                .tap_key_origins
                .get(&internal_key)
                .ok_or_else(invalid)?;
            let path = path.as_ref();
            if !matches!(
                path,
                [
                    ChildNumber::Hardened { index: 86 },
                    ChildNumber::Hardened { index: 0 | 1 },
                    ChildNumber::Hardened { .. },
                    ChildNumber::Normal { index: 0 | 1 },
                    ChildNumber::Normal { .. }
                ]
            ) {
                return Err(invalid());
            }
            let (output_key, _) = internal_key.tap_tweak(&secp, None);
            if prevout.script_pubkey != ScriptBuf::new_p2tr_tweaked(output_key) {
                return Err(invalid());
            }
            policies.push(InputPolicy::Bip86 {
                output_key: output_key.to_x_only_public_key(),
                signer: *fingerprint,
            });
        } else {
            if input.ecdsa_hash_ty() != Ok(EcdsaSighashType::All)
                || input
                    .partial_sigs
                    .values()
                    .any(|sig| sig.sighash_type != EcdsaSighashType::All)
                || input.tap_key_sig.is_some()
                || input.tap_internal_key.is_some()
                || !input.tap_key_origins.is_empty()
            {
                return Err(SignatureCheckError::UnsupportedPolicy);
            }
            policies.push(InputPolicy::Ecdsa);
        }
    }
    Ok(SigningPolicy {
        inputs: policies,
        prevouts,
    })
}

/// Accepts only new, valid ECDSA partial signatures or BIP86 key-path
/// signatures from `signer`. Every other field and prior signature must stay
/// identical to `unsigned`. Every input belonging to `signer` must be signed.
pub fn verify_device_signatures(
    unsigned: &Psbt,
    signed: Psbt,
    signer: Fingerprint,
) -> Result<Psbt, SignatureCheckError> {
    if signed.inputs.len() != unsigned.inputs.len() {
        return Err(SignatureCheckError::PsbtModified);
    }

    let mut stripped = signed.clone();
    for (index, (before, after)) in unsigned.inputs.iter().zip(&signed.inputs).enumerate() {
        if !before
            .partial_sigs
            .iter()
            .all(|(key, signature)| after.partial_sigs.get(key) == Some(signature))
            || before
                .tap_key_sig
                .is_some_and(|signature| after.tap_key_sig != Some(signature))
        {
            return Err(SignatureCheckError::PsbtModified);
        }
        stripped.inputs[index].partial_sigs = before.partial_sigs.clone();
        stripped.inputs[index].tap_key_sig = before.tap_key_sig;
    }
    if stripped != *unsigned {
        return Err(SignatureCheckError::PsbtModified);
    }

    let policy = signing_policy(unsigned)?;
    let secp = Secp256k1::verification_only();
    let mut sighashes = SighashCache::new(&unsigned.unsigned_tx);
    let mut added = 0;
    let mut first_unsigned_input = None;
    for (index, (before, after)) in unsigned.inputs.iter().zip(&signed.inputs).enumerate() {
        if let InputPolicy::Bip86 {
            output_key,
            signer: owner,
        } = &policy.inputs[index]
        {
            if after.partial_sigs != before.partial_sigs {
                return Err(SignatureCheckError::PsbtModified);
            }
            if let (None, Some(signature)) = (before.tap_key_sig, after.tap_key_sig) {
                if *owner != signer {
                    return Err(SignatureCheckError::PsbtModified);
                }
                let invalid = || SignatureCheckError::InvalidSignature { input: index };
                let sighash = sighashes
                    .taproot_key_spend_signature_hash(
                        index,
                        &Prevouts::All(&policy.prevouts),
                        TapSighashType::Default,
                    )
                    .map_err(|_| invalid())?;
                if signature.sighash_type != TapSighashType::Default
                    || secp
                        .verify_schnorr(&signature.signature, &Message::from(sighash), output_key)
                        .is_err()
                {
                    return Err(invalid());
                }
                added += 1;
            }
            if *owner == signer && after.tap_key_sig.is_none() && first_unsigned_input.is_none() {
                first_unsigned_input = Some(index);
            }
            continue;
        }
        if after.tap_key_sig != before.tap_key_sig {
            return Err(SignatureCheckError::PsbtModified);
        }
        let ours: Vec<_> = before
            .bip32_derivation
            .iter()
            .filter(|(_, (fingerprint, _))| *fingerprint == signer)
            .map(|(key, _)| *key)
            .collect();

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
            if signature.sighash_type != EcdsaSighashType::All
                || signature.sighash_type != sighash_type
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
    use bitcoin::secp256k1::{Keypair, Message, SecretKey};
    use bitcoin::taproot::{ControlBlock, LeafVersion, TapLeafHash, TapNodeHash};
    use bitcoin::{
        Amount, EcdsaSighashType, Network, OutPoint, PublicKey, ScriptBuf, Sequence, Transaction,
        TxIn, TxOut, Txid, Witness, absolute, ecdsa, taproot, transaction,
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

    fn bip86_psbt(master: Xpriv, coin: u32) -> (Holder, Psbt) {
        let secp = Secp256k1::new();
        let account_path = DerivationPath::from_str(&format!("m/86'/{coin}'/0'")).unwrap();
        let holder = Holder {
            fingerprint: master.fingerprint(&secp),
            account: Xpub::from_priv(&secp, &master.derive_priv(&secp, &account_path).unwrap()),
            master,
        };
        let descriptors: Vec<_> = [(0, 0), (0, 1), (1, 0)]
            .into_iter()
            .map(|(branch, index)| {
                Descriptor::<DescriptorPublicKey>::from_str(&format!(
                    "tr([{}/86'/{coin}'/0']{}/{branch}/*)",
                    holder.fingerprint, holder.account
                ))
                .unwrap()
                .at_derivation_index(index)
                .unwrap()
            })
            .collect();
        let funding = Transaction {
            version: transaction::Version::TWO,
            lock_time: absolute::LockTime::ZERO,
            input: vec![TxIn {
                previous_output: OutPoint {
                    txid: Txid::from_byte_array([9; 32]),
                    vout: 0,
                },
                script_sig: ScriptBuf::new(),
                sequence: Sequence::MAX,
                witness: Witness::new(),
            }],
            output: descriptors
                .iter()
                .map(|descriptor| TxOut {
                    value: Amount::from_sat(20_000),
                    script_pubkey: descriptor.script_pubkey(),
                })
                .collect(),
        };
        let mut psbt = Psbt::from_unsigned_tx(Transaction {
            version: transaction::Version::TWO,
            lock_time: absolute::LockTime::ZERO,
            input: (0..3)
                .map(|vout| TxIn {
                    previous_output: OutPoint {
                        txid: funding.compute_txid(),
                        vout,
                    },
                    script_sig: ScriptBuf::new(),
                    sequence: Sequence::ENABLE_RBF_NO_LOCKTIME,
                    witness: Witness::new(),
                })
                .collect(),
            output: vec![TxOut {
                value: Amount::from_sat(59_000),
                script_pubkey: descriptors[0].script_pubkey(),
            }],
        })
        .unwrap();
        for (index, descriptor) in descriptors.iter().enumerate() {
            psbt.inputs[index].non_witness_utxo = Some(funding.clone());
            psbt.inputs[index].witness_utxo = Some(funding.output[index].clone());
            psbt.update_input_with_descriptor(index, descriptor)
                .unwrap();
        }
        (holder, psbt)
    }

    fn taproot_fixture() -> (Holder, Psbt) {
        bip86_psbt(holder(0).master, 1)
    }

    fn official_bip86_fixture() -> (Holder, Psbt) {
        bip86_psbt(
            Xpriv::from_str(
                "xprv9s21ZrQH143K3GJpoapnV8SFfukcVBSfeCficPSGfubmSFDxo1kuHnLisriDvSnRRuL2Qrg5ggqHKNVpxR86QEC8w35uxmGoggxtQTPvfUu",
            )
            .unwrap(),
            0,
        )
    }

    fn taproot_secret(holder: &Holder, psbt: &Psbt, input: usize) -> SecretKey {
        let internal_key = psbt.inputs[input].tap_internal_key.unwrap();
        let (_, (_, path)) = &psbt.inputs[input].tap_key_origins[&internal_key];
        holder
            .master
            .derive_priv(&Secp256k1::new(), path)
            .unwrap()
            .private_key
    }

    fn rebind_funding_txid(psbt: &mut Psbt) {
        for (input, txin) in psbt.inputs.iter().zip(&mut psbt.unsigned_tx.input) {
            txin.previous_output.txid = input.non_witness_utxo.as_ref().unwrap().compute_txid();
        }
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
    fn accepts_bip86_official_receive_and_change_vectors_and_finalizes() {
        let (device, psbt) = official_bip86_fixture();
        let internal_keys = [
            "cc8a4bc64d897bddc5fbc2f670f7a8ba0b386779106cf1223c6fc5d7cd6fc115",
            "83dfe85a3151d2517290da461fe2815591ef69f2b18a2ce63f01697a8b313145",
            "399f1b2f4393f29a18c937859c5dd8a77350103157eb880f02e8c08214277cef",
        ];
        let scripts = [
            "5120a60869f0dbcf1dc659c9cecbaf8050135ea9e8cdc487053f1dc6880949dc684c",
            "5120a82f29944d65b86ae6b5e5cc75e294ead6c59391a1edc5e016e3498c67fc7bbb",
            "5120882d74e5d0572d5a816cef0041a96b6c1de832f6f9676d9605c44d5e9a97d3dc",
        ];
        for (index, (key, script)) in internal_keys.iter().zip(scripts).enumerate() {
            assert_eq!(
                psbt.inputs[index].tap_internal_key.unwrap().to_string(),
                *key
            );
            assert_eq!(
                psbt.inputs[index]
                    .witness_utxo
                    .as_ref()
                    .unwrap()
                    .script_pubkey,
                ScriptBuf::from_hex(script).unwrap()
            );
        }
        let signed = signed_by(&device, &psbt);
        assert_eq!(validate_signing_policy(&signed), Ok(()));
        let mut accepted =
            verify_device_signatures(&psbt, signed.clone(), device.fingerprint).unwrap();
        assert_eq!(accepted, signed);
        accepted.finalize_mut(&Secp256k1::new()).unwrap();
        let tx = accepted.extract(&Secp256k1::new()).unwrap();
        assert_eq!(tx.output, psbt.unsigned_tx.output);
        for (index, input) in tx.input.iter().enumerate() {
            assert!(input.script_sig.is_empty());
            assert_eq!(input.witness.len(), 1);
            assert_eq!(
                input.witness[0],
                signed.inputs[index].tap_key_sig.unwrap().to_vec()
            );
            assert_eq!(input.witness[0].len(), 64);
        }
    }

    #[test]
    fn preserves_prior_taproot_signatures_and_accepts_testnet_coin_origins() {
        let (device, psbt) = taproot_fixture();
        let signed = signed_by(&device, &psbt);
        let mut incremental = psbt.clone();
        incremental.inputs[0].tap_key_sig = signed.inputs[0].tap_key_sig;
        assert_eq!(validate_signing_policy(&incremental), Ok(()));
        assert_eq!(
            verify_device_signatures(&incremental, signed.clone(), device.fingerprint),
            Ok(signed.clone())
        );
        assert_eq!(
            verify_device_signatures(&signed, signed.clone(), device.fingerprint),
            Err(SignatureCheckError::NoSignatures)
        );

        for changed in [None, signed.inputs[1].tap_key_sig] {
            let mut returned = signed.clone();
            returned.inputs[0].tap_key_sig = changed;
            assert_eq!(
                verify_device_signatures(&incremental, returned, device.fingerprint),
                Err(SignatureCheckError::PsbtModified)
            );
        }
    }

    #[test]
    fn verifies_mixed_algorithms_and_cosigners_without_crossing_signature_fields() {
        let (device, mut psbt) = taproot_fixture();
        let holders = [holder(0), holder(1), holder(2)];
        assert_eq!(device.fingerprint, holders[0].fingerprint);
        let ecdsa = vault_psbt(&holders);
        let mut funding = psbt.inputs[0].non_witness_utxo.clone().unwrap();
        funding.output[1] = ecdsa.inputs[0].witness_utxo.clone().unwrap();
        psbt.inputs[1] = ecdsa.inputs[0].clone();
        for input in &mut psbt.inputs {
            input.non_witness_utxo = Some(funding.clone());
        }
        rebind_funding_txid(&mut psbt);
        let cosigned = signed_by(&holders[1], &psbt);
        let both = signed_by(&device, &cosigned);
        let mut accepted =
            verify_device_signatures(&cosigned, both.clone(), device.fingerprint).unwrap();
        assert_eq!(accepted, both);
        accepted.finalize_mut(&Secp256k1::new()).unwrap();
        let tx = accepted.extract(&Secp256k1::new()).unwrap();
        assert_eq!(tx.input[0].witness.len(), 1);
        assert_eq!(tx.input[1].witness.len(), 4);
        assert_eq!(tx.input[2].witness.len(), 1);
        assert_eq!(tx.output, psbt.unsigned_tx.output);

        let mut missing_full = psbt.clone();
        missing_full.inputs[1].non_witness_utxo = None;
        assert_eq!(
            validate_signing_policy(&missing_full),
            Err(SignatureCheckError::InvalidPrevout { input: 1 })
        );
        let (ecdsa_key, ecdsa_signature) = both.inputs[1].partial_sigs.first_key_value().unwrap();
        let mut ecdsa_on_taproot = both.clone();
        ecdsa_on_taproot.inputs[0]
            .partial_sigs
            .insert(*ecdsa_key, *ecdsa_signature);
        let mut taproot_on_ecdsa = both.clone();
        taproot_on_ecdsa.inputs[1].tap_key_sig = both.inputs[0].tap_key_sig;
        for wrong in [ecdsa_on_taproot, taproot_on_ecdsa] {
            assert_eq!(
                verify_device_signatures(&cosigned, wrong, device.fingerprint),
                Err(SignatureCheckError::PsbtModified)
            );
        }
    }

    #[test]
    fn refuses_taproot_signature_and_metadata_mutations() {
        use SignatureCheckError::*;
        let (device, psbt) = taproot_fixture();
        let signed = signed_by(&device, &psbt);
        let secp = Secp256k1::new();
        let keypair = Keypair::from_secret_key(&secp, &taproot_secret(&device, &psbt, 0));
        let wrong_message = taproot::Signature {
            signature: secp.sign_schnorr_no_aux_rand(&Message::from_digest([7; 32]), &keypair),
            sighash_type: TapSighashType::Default,
        };
        let tampered = |change: &dyn Fn(&mut Psbt)| {
            let mut returned = signed.clone();
            change(&mut returned);
            returned
        };
        let key = psbt.inputs[0].tap_internal_key.unwrap();
        let cases = [
            ("nothing signed", psbt.clone(), NoSignatures),
            (
                "one input left unsigned",
                tampered(&|p| p.inputs[1].tap_key_sig = None),
                MissingSignature { input: 1 },
            ),
            (
                "wrong Schnorr message",
                tampered(&|p| p.inputs[0].tap_key_sig = Some(wrong_message)),
                InvalidSignature { input: 0 },
            ),
            (
                "non-DEFAULT Schnorr signature",
                tampered(&|p| {
                    p.inputs[0].tap_key_sig.as_mut().unwrap().sighash_type = TapSighashType::All;
                }),
                InvalidSignature { input: 0 },
            ),
            (
                "output amount",
                tampered(&|p| p.unsigned_tx.output[0].value = Amount::from_sat(1)),
                PsbtModified,
            ),
            (
                "output destination",
                tampered(&|p| p.unsigned_tx.output[0].script_pubkey = ScriptBuf::new()),
                PsbtModified,
            ),
            (
                "input outpoint",
                tampered(&|p| p.unsigned_tx.input[0].previous_output.vout = 1),
                PsbtModified,
            ),
            (
                "input amount",
                tampered(&|p| {
                    p.inputs[0].witness_utxo.as_mut().unwrap().value = Amount::from_sat(1);
                }),
                PsbtModified,
            ),
            (
                "internal key",
                tampered(&|p| p.inputs[0].tap_internal_key = p.inputs[1].tap_internal_key),
                PsbtModified,
            ),
            (
                "origin fingerprint",
                tampered(&|p| {
                    p.inputs[0].tap_key_origins.get_mut(&key).unwrap().1.0 =
                        Fingerprint::from([4; 4]);
                }),
                PsbtModified,
            ),
            (
                "script-path root",
                tampered(&|p| {
                    p.inputs[0].tap_merkle_root = Some(TapNodeHash::from_byte_array([7; 32]));
                }),
                PsbtModified,
            ),
            (
                "final witness",
                tampered(&|p| p.inputs[0].final_script_witness = Some(Witness::new())),
                PsbtModified,
            ),
            (
                "extra output map",
                tampered(&|p| p.outputs.push(Default::default())),
                PsbtModified,
            ),
        ];
        for (case, returned, expected) in cases {
            assert_eq!(
                verify_device_signatures(&psbt, returned, device.fingerprint),
                Err(expected),
                "{case}"
            );
        }
        assert_eq!(
            verify_device_signatures(&psbt, signed, Fingerprint::from([4; 4])),
            Err(PsbtModified)
        );
    }

    #[test]
    fn verifies_taproot_output_tweak_and_all_original_prevouts() {
        let (device, psbt) = taproot_fixture();
        let secp = Secp256k1::new();
        let mut no_tweak = signed_by(&device, &psbt);
        let prevouts: Vec<_> = psbt
            .inputs
            .iter()
            .map(|i| i.witness_utxo.as_ref().unwrap())
            .collect();
        let sighash = SighashCache::new(&psbt.unsigned_tx)
            .taproot_key_spend_signature_hash(0, &Prevouts::All(&prevouts), TapSighashType::Default)
            .unwrap();
        let keypair = Keypair::from_secret_key(&secp, &taproot_secret(&device, &psbt, 0));
        no_tweak.inputs[0].tap_key_sig = Some(taproot::Signature {
            signature: secp.sign_schnorr_no_aux_rand(&Message::from(sighash), &keypair),
            sighash_type: TapSighashType::Default,
        });
        assert_eq!(
            verify_device_signatures(&psbt, no_tweak, device.fingerprint),
            Err(SignatureCheckError::InvalidSignature { input: 0 })
        );

        let mut wrong_prevouts = psbt.clone();
        wrong_prevouts.inputs[2]
            .witness_utxo
            .as_mut()
            .unwrap()
            .value = Amount::from_sat(10_000);
        let wrong_signatures = signed_by(&device, &wrong_prevouts);
        let mut returned = psbt.clone();
        for (input, wrong) in returned.inputs.iter_mut().zip(wrong_signatures.inputs) {
            input.tap_key_sig = wrong.tap_key_sig;
        }
        assert_eq!(
            verify_device_signatures(&psbt, returned, device.fingerprint),
            Err(SignatureCheckError::InvalidSignature { input: 0 })
        );
    }

    #[test]
    fn signing_policy_rejects_bad_origins_and_internal_keys() {
        let (_, psbt) = taproot_fixture();
        let key = psbt.inputs[0].tap_internal_key.unwrap();
        let wrong_paths = [
            "m/84'/1'/0'/0/0",
            "m/86/1'/0'/0/0",
            "m/86'/2'/0'/0/0",
            "m/86'/1/0'/0/0",
            "m/86'/1'/0/0/0",
            "m/86'/1'/0'/2/0",
            "m/86'/1'/0'/0'/0",
            "m/86'/1'/0'/0/0'",
            "m/86'/1'/0'/0",
            "m/86'/1'/0'/0/0/0",
        ];
        for path in wrong_paths {
            let mut wrong = psbt.clone();
            wrong.inputs[0].tap_key_origins.get_mut(&key).unwrap().1.1 =
                DerivationPath::from_str(path).unwrap();
            assert_eq!(
                validate_signing_policy(&wrong),
                Err(SignatureCheckError::InvalidTaproot { input: 0 }),
                "{path}"
            );
        }
        for change in [
            |p: &mut Psbt| p.inputs[0].tap_internal_key = None,
            |p: &mut Psbt| p.inputs[0].tap_internal_key = p.inputs[1].tap_internal_key,
            |p: &mut Psbt| p.inputs[0].tap_key_origins.clear(),
            |p: &mut Psbt| {
                let second = p.inputs[1].tap_key_origins.clone();
                p.inputs[0].tap_key_origins.extend(second);
            },
        ] {
            let mut wrong = psbt.clone();
            change(&mut wrong);
            assert_eq!(
                validate_signing_policy(&wrong),
                Err(SignatureCheckError::InvalidTaproot { input: 0 })
            );
        }

        let mut wrong = psbt.clone();
        let other_script = wrong.inputs[1]
            .witness_utxo
            .as_ref()
            .unwrap()
            .script_pubkey
            .clone();
        wrong.inputs[0].witness_utxo.as_mut().unwrap().script_pubkey = other_script.clone();
        wrong.inputs[0].non_witness_utxo.as_mut().unwrap().output[0].script_pubkey = other_script;
        rebind_funding_txid(&mut wrong);
        assert_eq!(
            validate_signing_policy(&wrong),
            Err(SignatureCheckError::InvalidTaproot { input: 0 })
        );
    }

    #[test]
    fn signing_policy_rejects_unsupported_sighashes_and_script_paths() {
        let (device, psbt) = taproot_fixture();
        let signed = signed_by(&device, &psbt);
        for sighash in [
            TapSighashType::All,
            TapSighashType::None,
            TapSighashType::Single,
            TapSighashType::AllPlusAnyoneCanPay,
            TapSighashType::NonePlusAnyoneCanPay,
            TapSighashType::SinglePlusAnyoneCanPay,
        ] {
            let mut unsupported = psbt.clone();
            unsupported.inputs[0].sighash_type = Some(sighash.into());
            assert_eq!(
                validate_signing_policy(&unsupported),
                Err(SignatureCheckError::UnsupportedPolicy),
                "{sighash}"
            );
        }
        let mut explicit_default = psbt.clone();
        explicit_default.inputs[0].sighash_type = Some(TapSighashType::Default.into());
        assert_eq!(validate_signing_policy(&explicit_default), Ok(()));

        let key = psbt.inputs[0].tap_internal_key.unwrap();
        let leaf = TapLeafHash::from_script(&ScriptBuf::new(), LeafVersion::TapScript);
        let mut leaf_origins = psbt.clone();
        leaf_origins.inputs[0]
            .tap_key_origins
            .get_mut(&key)
            .unwrap()
            .0
            .push(leaf);
        let mut script_sig = psbt.clone();
        script_sig.inputs[0]
            .tap_script_sigs
            .insert((key, leaf), signed.inputs[0].tap_key_sig.unwrap());
        let mut scripts = psbt.clone();
        let mut control = vec![0xc0];
        control.extend(key.serialize());
        scripts.inputs[0].tap_scripts.insert(
            ControlBlock::decode(&control).unwrap(),
            (ScriptBuf::new(), LeafVersion::TapScript),
        );
        let mut merkle = psbt.clone();
        merkle.inputs[0].tap_merkle_root = Some(TapNodeHash::from_byte_array([7; 32]));
        let mut output_leaves = psbt.clone();
        output_leaves.outputs[0].tap_key_origins = leaf_origins.inputs[0].tap_key_origins.clone();
        for unsupported in [leaf_origins, script_sig, scripts, merkle, output_leaves] {
            assert_eq!(
                validate_signing_policy(&unsupported),
                Err(SignatureCheckError::UnsupportedPolicy)
            );
        }

        let holders = [holder(0), holder(1), holder(2)];
        let ecdsa = vault_psbt(&holders);
        for sighash in [
            EcdsaSighashType::None,
            EcdsaSighashType::Single,
            EcdsaSighashType::AllPlusAnyoneCanPay,
            EcdsaSighashType::NonePlusAnyoneCanPay,
            EcdsaSighashType::SinglePlusAnyoneCanPay,
        ] {
            let mut unsupported = ecdsa.clone();
            unsupported.inputs[0].sighash_type = Some(sighash.into());
            assert_eq!(
                validate_signing_policy(&unsupported),
                Err(SignatureCheckError::UnsupportedPolicy),
                "{sighash}"
            );
        }
        let mut explicit_all = ecdsa.clone();
        explicit_all.inputs[0].sighash_type = Some(EcdsaSighashType::All.into());
        assert_eq!(validate_signing_policy(&explicit_all), Ok(()));
    }

    #[test]
    fn signing_policy_authenticates_taproot_prevouts_and_utxo_agreement() {
        let (_, psbt) = taproot_fixture();
        let changes: [fn(&mut Psbt); 6] = [
            |p| p.inputs[0].non_witness_utxo = None,
            |p| {
                p.inputs[0].non_witness_utxo.as_mut().unwrap().lock_time =
                    absolute::LockTime::from_height(1).unwrap()
            },
            |p| p.unsigned_tx.input[0].previous_output.vout = 100,
            |p| p.inputs[0].witness_utxo.as_mut().unwrap().value = Amount::from_sat(1),
            |p| p.inputs[0].witness_utxo.as_mut().unwrap().script_pubkey = ScriptBuf::new(),
            |p| {
                p.inputs[0].witness_utxo = None;
                p.inputs[0].non_witness_utxo = None;
            },
        ];
        for change in changes {
            let mut wrong = psbt.clone();
            change(&mut wrong);
            assert_eq!(
                validate_signing_policy(&wrong),
                Err(SignatureCheckError::InvalidPrevout { input: 0 })
            );
        }
        let mut only_full = psbt;
        for input in &mut only_full.inputs {
            input.witness_utxo = None;
        }
        assert_eq!(validate_signing_policy(&only_full), Ok(()));
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
