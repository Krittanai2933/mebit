//! Single-sig hot wallet (free BTC). BIP-84 P2WPKH on the account key.
//!
//! Shape matches the BDK hot-wallet half described in `mobile-signer-ffi/README.md`.
//! Address/UTXO/sign/broadcast are real against testnet Esplora; full `bdk_wallet`
//! persistence and coin selection land when Android binary size is measured.

use std::str::FromStr;

use bip39::Mnemonic;
use bitcoin::absolute::LockTime;
use bitcoin::bip32::{DerivationPath, Xpriv};
use bitcoin::consensus::encode::serialize_hex;
use bitcoin::secp256k1::{Message, Secp256k1};
use bitcoin::hashes::Hash;
use bitcoin::{
    Address, Amount, CompressedPublicKey, Network, OutPoint, PrivateKey, ScriptBuf, Sequence,
    Transaction, TxIn, TxOut, Txid, Witness, sighash::EcdsaSighashType, sighash::SighashCache,
};

use crate::{KeyNetwork, MobileSignerError};

const ESPLORA_TESTNET4: &str = "https://mempool.space/testnet4/api";
const ACCOUNT_PATH: &str = "m/84'/1'/0'";

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct HotWalletSnapshot {
    pub receive_address: String,
    pub balance_sats: u64,
    pub network: KeyNetwork,
}

fn network_of(key_network: KeyNetwork) -> Network {
    match key_network {
        KeyNetwork::Main => Network::Bitcoin,
        KeyNetwork::Test => Network::Testnet4,
    }
}

fn esplora_base(network: KeyNetwork) -> Result<&'static str, MobileSignerError> {
    match network {
        KeyNetwork::Test => Ok(ESPLORA_TESTNET4),
        KeyNetwork::Main => Err(MobileSignerError::InvalidPsbtJson(
            "mainnet Esplora is not enabled in this build".into(),
        )),
    }
}

fn mnemonic_from(words: &[String]) -> Result<Mnemonic, MobileSignerError> {
    let phrase = words.join(" ");
    Mnemonic::parse_normalized(&phrase)
        .map_err(|e| MobileSignerError::InvalidPsbtJson(e.to_string()))
}

fn receive_privkey(
    mnemonic: &Mnemonic,
    network: Network,
    index: u32,
) -> Result<(PrivateKey, Address), MobileSignerError> {
    let secp = Secp256k1::new();
    let seed = mnemonic.to_seed("");
    let master = Xpriv::new_master(network, &seed)
        .map_err(|e| MobileSignerError::InvalidPsbtJson(e.to_string()))?;
    let path = DerivationPath::from_str(&format!("{ACCOUNT_PATH}/0/{index}"))
        .map_err(|e| MobileSignerError::InvalidPsbtJson(e.to_string()))?;
    let child = master
        .derive_priv(&secp, &path)
        .map_err(|e| MobileSignerError::InvalidPsbtJson(e.to_string()))?;
    let privkey = PrivateKey::new(child.private_key, network);
    let pubkey = CompressedPublicKey::from_private_key(&secp, &privkey)
        .map_err(|e| MobileSignerError::InvalidPsbtJson(e.to_string()))?;
    let address = Address::p2wpkh(&pubkey, network);
    Ok((privkey, address))
}

fn http_get(url: &str) -> Result<String, MobileSignerError> {
    ureq::get(url)
        .call()
        .map_err(|e| MobileSignerError::InvalidPsbtJson(format!("esplora get: {e}")))?
        .into_string()
        .map_err(|e| MobileSignerError::InvalidPsbtJson(format!("esplora body: {e}")))
}

fn http_post(url: &str, body: &str) -> Result<String, MobileSignerError> {
    ureq::post(url)
        .set("Content-Type", "text/plain")
        .send_string(body)
        .map_err(|e| MobileSignerError::InvalidPsbtJson(format!("esplora post: {e}")))?
        .into_string()
        .map_err(|e| MobileSignerError::InvalidPsbtJson(format!("esplora body: {e}")))
}

/// First receive address at BIP-84 index `index` (usually 0).
#[uniffi::export]
pub fn hot_receive_address(
    mnemonic_words: Vec<String>,
    network: KeyNetwork,
    index: u32,
) -> Result<String, MobileSignerError> {
    let mnemonic = mnemonic_from(&mnemonic_words)?;
    let (_pk, address) = receive_privkey(&mnemonic, network_of(network), index)?;
    Ok(address.to_string())
}

/// Confirmed + mempool balance for receive index 0 via Esplora.
#[uniffi::export]
pub fn hot_wallet_snapshot(
    mnemonic_words: Vec<String>,
    network: KeyNetwork,
) -> Result<HotWalletSnapshot, MobileSignerError> {
    let mnemonic = mnemonic_from(&mnemonic_words)?;
    let (_pk, address) = receive_privkey(&mnemonic, network_of(network), 0)?;
    let base = esplora_base(network)?;
    let raw = http_get(&format!("{base}/address/{address}"))?;
    let balance = parse_address_balance(&raw)?;
    Ok(HotWalletSnapshot {
        receive_address: address.to_string(),
        balance_sats: balance,
        network,
    })
}

fn parse_address_balance(json: &str) -> Result<u64, MobileSignerError> {
    let chain_funded = extract_u64(json, "\"chain_stats\"", "\"funded_txo_sum\"").unwrap_or(0);
    let chain_spent = extract_u64(json, "\"chain_stats\"", "\"spent_txo_sum\"").unwrap_or(0);
    let mem_funded = extract_u64(json, "\"mempool_stats\"", "\"funded_txo_sum\"").unwrap_or(0);
    let mem_spent = extract_u64(json, "\"mempool_stats\"", "\"spent_txo_sum\"").unwrap_or(0);
    Ok(chain_funded.saturating_sub(chain_spent) + mem_funded.saturating_sub(mem_spent))
}

fn extract_u64(json: &str, section: &str, field: &str) -> Option<u64> {
    let sec = json.find(section)?;
    let slice = &json[sec..];
    let f = slice.find(field)?;
    let after = &slice[f + field.len()..];
    let colon = after.find(':')?;
    let digits: String = after[colon + 1..]
        .chars()
        .skip_while(|c| c.is_whitespace())
        .take_while(|c| c.is_ascii_digit())
        .collect();
    digits.parse().ok()
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct HotSendResult {
    pub txid: String,
}

/// Build, sign (BIP-84 index 0 key), and broadcast a payment. Fee is absolute sats.
#[uniffi::export]
pub fn hot_send(
    mnemonic_words: Vec<String>,
    network: KeyNetwork,
    destination: String,
    amount_sats: u64,
    fee_sats: u64,
) -> Result<HotSendResult, MobileSignerError> {
    let mnemonic = mnemonic_from(&mnemonic_words)?;
    let net = network_of(network);
    let (privkey, from_address) = receive_privkey(&mnemonic, net, 0)?;
    let dest = Address::from_str(&destination)
        .map_err(|e| MobileSignerError::InvalidPsbtJson(e.to_string()))?
        .require_network(net)
        .map_err(|e| MobileSignerError::InvalidPsbtJson(e.to_string()))?;

    let base = esplora_base(network)?;
    let utxo_json = http_get(&format!("{base}/address/{from_address}/utxo"))?;
    let utxos = parse_utxos(&utxo_json)?;
    let need = amount_sats.saturating_add(fee_sats);
    let mut selected = Vec::new();
    let mut total = 0u64;
    for u in utxos {
        total += u.value;
        selected.push(u);
        if total >= need {
            break;
        }
    }
    if total < need {
        return Err(MobileSignerError::InvalidPsbtJson(format!(
            "insufficient funds: have {total} need {need}"
        )));
    }

    let secp = Secp256k1::new();
    let mut tx = Transaction {
        version: bitcoin::transaction::Version::TWO,
        lock_time: LockTime::ZERO,
        input: selected
            .iter()
            .map(|u| TxIn {
                previous_output: OutPoint {
                    txid: u.txid,
                    vout: u.vout,
                },
                script_sig: ScriptBuf::new(),
                sequence: Sequence::ENABLE_RBF_NO_LOCKTIME,
                witness: Witness::new(),
            })
            .collect(),
        output: vec![TxOut {
            value: Amount::from_sat(amount_sats),
            script_pubkey: dest.script_pubkey(),
        }],
    };
    let change = total - need;
    if change > 546 {
        tx.output.push(TxOut {
            value: Amount::from_sat(change),
            script_pubkey: from_address.script_pubkey(),
        });
    }

    let pubkey = CompressedPublicKey::from_private_key(&secp, &privkey)
        .map_err(|e| MobileSignerError::InvalidPsbtJson(e.to_string()))?;
    let mut cache = SighashCache::new(tx);
    for i in 0..selected.len() {
        let sighash = cache
            .p2wpkh_signature_hash(
                i,
                &from_address.script_pubkey(),
                Amount::from_sat(selected[i].value),
                EcdsaSighashType::All,
            )
            .map_err(|e| MobileSignerError::InvalidPsbtJson(e.to_string()))?;
        let msg = Message::from_digest(sighash.to_byte_array());
        let sig = secp.sign_ecdsa(&msg, &privkey.inner);
        let mut der = sig.serialize_der().to_vec();
        der.push(EcdsaSighashType::All as u8);
        let witness = cache
            .witness_mut(i)
            .ok_or_else(|| MobileSignerError::InvalidPsbtJson("missing witness slot".into()))?;
        *witness = Witness::from_slice(&[der.as_slice(), &pubkey.to_bytes()]);
    }
    let signed = cache.into_transaction();
    let hex = serialize_hex(&signed);
    let txid = http_post(&format!("{base}/tx"), &hex)?;
    Ok(HotSendResult {
        txid: txid.trim().to_string(),
    })
}

struct Utxo {
    txid: Txid,
    vout: u32,
    value: u64,
}

fn parse_utxos(json: &str) -> Result<Vec<Utxo>, MobileSignerError> {
    let mut out = Vec::new();
    let mut rest = json;
    while let Some(start) = rest.find('{') {
        let end = rest[start..]
            .find('}')
            .map(|i| start + i + 1)
            .unwrap_or(rest.len());
        let obj = &rest[start..end];
        if let (Some(txid_s), Some(vout), Some(value)) = (
            extract_string(obj, "\"txid\""),
            extract_u64_simple(obj, "\"vout\""),
            extract_u64_simple(obj, "\"value\""),
        ) {
            let txid = Txid::from_str(&txid_s)
                .map_err(|e| MobileSignerError::InvalidPsbtJson(e.to_string()))?;
            out.push(Utxo {
                txid,
                vout: vout as u32,
                value,
            });
        }
        rest = &rest[end..];
    }
    Ok(out)
}

fn extract_string(obj: &str, field: &str) -> Option<String> {
    let f = obj.find(field)?;
    let after = &obj[f + field.len()..];
    let q1 = after.find('"')?;
    let rest = &after[q1 + 1..];
    let q2 = rest.find('"')?;
    Some(rest[..q2].to_string())
}

fn extract_u64_simple(obj: &str, field: &str) -> Option<u64> {
    let f = obj.find(field)?;
    let after = &obj[f + field.len()..];
    let colon = after.find(':')?;
    let digits: String = after[colon + 1..]
        .chars()
        .skip_while(|c| c.is_whitespace())
        .take_while(|c| c.is_ascii_digit())
        .collect();
    digits.parse().ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn receive_address_is_stable_for_known_words() {
        let words: Vec<String> =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
                .split_whitespace()
                .map(str::to_string)
                .collect();
        let a = hot_receive_address(words.clone(), KeyNetwork::Test, 0).unwrap();
        let b = hot_receive_address(words, KeyNetwork::Test, 0).unwrap();
        assert_eq!(a, b);
        assert!(a.starts_with("tb1"));
    }

    #[test]
    fn parse_balance_reads_chain_stats() {
        let json = r#"{"chain_stats":{"funded_txo_sum":1000,"spent_txo_sum":200},"mempool_stats":{"funded_txo_sum":50,"spent_txo_sum":0}}"#;
        assert_eq!(parse_address_balance(json).unwrap(), 850);
    }
}
