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
