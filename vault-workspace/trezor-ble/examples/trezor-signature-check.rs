use std::error::Error;
use std::fs;
use std::str::FromStr;

use bitcoin::hashes::Hash;
use bitcoin::hex::FromHex;
use bitcoin::secp256k1::{Message, Secp256k1, ecdsa};
use bitcoin::sighash::{EcdsaSighashType, SighashCache};
use bitcoin::{Psbt, PublicKey, ScriptBuf};
use miniscript::{ForEachKey, Miniscript, Segwitv0};
use serde::Deserialize;

#[derive(Deserialize)]
struct Rejection {
    transaction_id: String,
    input_index: usize,
    signature_der: String,
    candidates: Vec<Candidate>,
}

#[derive(Deserialize)]
struct Candidate {
    input_index: usize,
    expected_public_key: String,
}

fn checked_public_key(
    input: &bitcoin::psbt::Input,
    candidate: &Candidate,
) -> Result<PublicKey, Box<dyn Error>> {
    let public_key = PublicKey::from_str(&candidate.expected_public_key)?;
    if !input.bip32_derivation.contains_key(&public_key.inner) {
        return Err("diagnostic key does not belong to the saved input".into());
    }
    let script = input
        .witness_script
        .as_ref()
        .ok_or("missing P2WSH witness script")?;
    let witness = Miniscript::<PublicKey, Segwitv0>::decode(script)?;
    if !witness.for_any_key(|key| *key == public_key) {
        return Err("diagnostic key does not belong to the funded witness script".into());
    }
    Ok(public_key)
}

fn main() -> Result<(), Box<dyn Error>> {
    let arguments: Vec<_> = std::env::args().skip(1).collect();
    if arguments.len() != 2 {
        return Err("usage: trezor-signature-check <checkpoint.json> <rejection.json>".into());
    }
    let checkpoint: serde_json::Value = serde_json::from_slice(&fs::read(&arguments[0])?)?;
    let psbt_text = checkpoint["progress"]["Signing"]["signed_psbt"]
        .as_str()
        .ok_or("checkpoint is not in Signing")?;
    let psbt = Psbt::from_str(psbt_text)?;
    let rejection: Rejection = serde_json::from_slice(&fs::read(&arguments[1])?)?;
    if rejection.transaction_id != psbt.unsigned_tx.compute_txid().to_string() {
        return Err("signature diagnostic belongs to a different transaction".into());
    }
    let index = rejection.input_index;
    let input = psbt
        .inputs
        .get(index)
        .ok_or("input index is out of range")?;
    let candidate = rejection
        .candidates
        .iter()
        .find(|candidate| candidate.input_index == index)
        .ok_or("no expected public key for the rejected input")?;
    let public_key = checked_public_key(input, candidate)?;
    let signature = ecdsa::Signature::from_der(&Vec::<u8>::from_hex(&rejection.signature_der)?)?;
    let prevout = psbt.unsigned_tx.input[index].previous_output;
    let previous = input
        .non_witness_utxo
        .as_ref()
        .ok_or("missing previous transaction")?;
    if previous.compute_txid() != prevout.txid {
        return Err("previous transaction does not match the outpoint".into());
    }
    let spent = previous
        .output
        .get(prevout.vout as usize)
        .ok_or("missing spent output")?;
    let witness_script = input
        .witness_script
        .as_ref()
        .ok_or("missing P2WSH witness script")?;
    if witness_script.to_p2wsh() != spent.script_pubkey {
        return Err("witness script does not match the authenticated spent output".into());
    }
    if input
        .witness_utxo
        .as_ref()
        .is_some_and(|utxo| utxo != spent)
    {
        return Err("witness and non-witness UTXOs disagree".into());
    }
    let secp = Secp256k1::verification_only();
    let mut cache = SighashCache::new(&psbt.unsigned_tx);
    let (expected, hash_type) = psbt.sighash_ecdsa(index, &mut cache)?;
    if hash_type != EcdsaSighashType::All {
        return Err("input must use SIGHASH_ALL".into());
    }
    let alternative_script = ScriptBuf::new_p2wpkh(&public_key.wpubkey_hash()?);
    let alternative =
        cache.p2wpkh_signature_hash(index, &alternative_script, spent.value, hash_type)?;
    let alternative = Message::from_digest(alternative.to_byte_array());
    let expected_verifies = secp
        .verify_ecdsa(&expected, &signature, &public_key.inner)
        .is_ok();
    let alternative_verifies = secp
        .verify_ecdsa(&alternative, &signature, &public_key.inner)
        .is_ok();
    println!("Saved signature verifies for funded P2WSH: {expected_verifies}");
    println!("Saved signature verifies for alternate P2WPKH script code: {alternative_verifies}");
    if !expected_verifies && alternative_verifies {
        println!(
            "Confirmed wrong script code. The signature cannot spend the funded P2WSH output."
        );
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use bitcoin::bip32::{DerivationPath, Fingerprint};
    use miniscript::{Miniscript, Segwitv0};

    #[test]
    fn metadata_key_must_also_belong_to_witness_script() {
        let funded = PublicKey::from_str(
            "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
        )
        .unwrap();
        let metadata_only = PublicKey::from_str(
            "02c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5",
        )
        .unwrap();
        let script = Miniscript::<PublicKey, Segwitv0>::from_str(&format!("multi(1,{funded})"))
            .unwrap()
            .encode();
        let mut input = bitcoin::psbt::Input {
            witness_script: Some(script),
            ..Default::default()
        };
        for key in [funded, metadata_only] {
            input.bip32_derivation.insert(
                key.inner,
                (Fingerprint::default(), DerivationPath::default()),
            );
        }
        let candidate = |key: PublicKey| Candidate {
            input_index: 0,
            expected_public_key: key.to_string(),
        };
        assert_eq!(
            checked_public_key(&input, &candidate(funded)).unwrap(),
            funded
        );
        let result = checked_public_key(&input, &candidate(metadata_only));
        assert!(
            result.is_err(),
            "metadata-only key was accepted: {result:?}"
        );
    }
}
