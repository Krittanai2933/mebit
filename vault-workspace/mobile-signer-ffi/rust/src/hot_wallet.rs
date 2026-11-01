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
    passphrase: &str,
    network: Network,
    index: u32,
) -> Result<(PrivateKey, Address), MobileSignerError> {
    let secp = Secp256k1::new();
    let seed = mnemonic.to_seed(passphrase);
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

/// First receive address at BIP-84 index `index` (usually 0). `passphrase` is the
/// BIP-39 passphrase the key was restored with, empty for none: a different
/// passphrase is a different wallet.
#[uniffi::export]
pub fn hot_receive_address(
    mnemonic_words: Vec<String>,
    passphrase: String,
    network: KeyNetwork,
    index: u32,
) -> Result<String, MobileSignerError> {
    let mnemonic = mnemonic_from(&mnemonic_words)?;
    let (_pk, address) = receive_privkey(&mnemonic, &passphrase, network_of(network), index)?;
    Ok(address.to_string())
}

/// Confirmed + mempool balance for receive index 0 via Esplora.
#[uniffi::export]
pub fn hot_wallet_snapshot(
    mnemonic_words: Vec<String>,
    passphrase: String,
    network: KeyNetwork,
) -> Result<HotWalletSnapshot, MobileSignerError> {
    let mnemonic = mnemonic_from(&mnemonic_words)?;
    let (_pk, address) = receive_privkey(&mnemonic, &passphrase, network_of(network), 0)?;
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

/// True when `address` parses and belongs to `network`: the same test `hot_send` applies to its destination.
#[uniffi::export]
pub fn is_valid_address(address: String, network: KeyNetwork) -> bool {
    Address::from_str(&address).is_ok_and(|a| a.require_network(network_of(network)).is_ok())
}

/// Build, sign (BIP-84 index 0 key), and broadcast a payment. Fee is absolute sats.
#[uniffi::export]
pub fn hot_send(
    mnemonic_words: Vec<String>,
    passphrase: String,
    network: KeyNetwork,
    destination: String,
    amount_sats: u64,
    fee_sats: u64,
) -> Result<HotSendResult, MobileSignerError> {
    let mnemonic = mnemonic_from(&mnemonic_words)?;
    let net = network_of(network);
    let (privkey, from_address) = receive_privkey(&mnemonic, &passphrase, net, 0)?;
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

// A bad element fails the whole list. Skipping it would hide funds behind "insufficient funds".
fn parse_utxos(json: &str) -> Result<Vec<Utxo>, MobileSignerError> {
    let bad = |what: String| MobileSignerError::InvalidPsbtJson(format!("esplora utxo: {what}"));
    let body: serde_json::Value =
        serde_json::from_str(json).map_err(|e| bad(format!("not JSON: {e}")))?;
    let items = body.as_array().ok_or_else(|| bad("not an array".into()))?;
    items
        .iter()
        .enumerate()
        .map(|(i, item)| {
            let txid = item["txid"]
                .as_str()
                .ok_or_else(|| bad(format!("item {i} has no string txid")))?;
            let txid = Txid::from_str(txid)
                .map_err(|e| bad(format!("item {i} txid is not 64 hex characters: {e}")))?;
            let vout = item["vout"]
                .as_u64()
                .and_then(|v| u32::try_from(v).ok())
                .ok_or_else(|| bad(format!("item {i} has no u32 vout")))?;
            let value = item["value"]
                .as_u64()
                .ok_or_else(|| bad(format!("item {i} has no unsigned integer value")))?;
            Ok(Utxo { txid, vout, value })
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn abandon_about() -> Vec<String> {
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
            .split_whitespace()
            .map(str::to_string)
            .collect()
    }

    // Both addresses were derived outside this crate (stdlib PBKDF2 + BIP-32 + bech32,
    // BIP-84 path m/84'/1'/0'/0/0). The empty-passphrase one is the published BIP-84
    // testnet vector, so it also pins the behaviour from before passphrases existed.
    #[test]
    fn receive_address_matches_an_independent_derivation() {
        let none = hot_receive_address(abandon_about(), String::new(), KeyNetwork::Test, 0).unwrap();
        assert_eq!(none, "tb1q6rz28mcfaxtmd6v789l9rrlrusdprr9pqcpvkl");
        let trezor =
            hot_receive_address(abandon_about(), "TREZOR".into(), KeyNetwork::Test, 0).unwrap();
        assert_eq!(trezor, "tb1qfwxzlmn42rh6wu6gt4f0yrfpe4gf0jtwgxagrr");
    }

    #[test]
    fn address_check_matches_what_hot_send_accepts() {
        let own = hot_receive_address(abandon_about(), String::new(), KeyNetwork::Test, 0).unwrap();
        assert!(is_valid_address(own.clone(), KeyNetwork::Test));
        assert!(!is_valid_address(own, KeyNetwork::Main), "a testnet address is not a mainnet one");
        assert!(is_valid_address("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq".into(), KeyNetwork::Main));
        assert!(!is_valid_address("tb1qnotanaddress".into(), KeyNetwork::Test));
        assert!(!is_valid_address(" ".into(), KeyNetwork::Test));
        assert!(!is_valid_address(String::new(), KeyNetwork::Test));
    }

    #[test]
    fn a_different_passphrase_is_a_different_wallet() {
        let a = hot_receive_address(abandon_about(), "a".into(), KeyNetwork::Test, 0).unwrap();
        let b = hot_receive_address(abandon_about(), "b".into(), KeyNetwork::Test, 0).unwrap();
        let none = hot_receive_address(abandon_about(), String::new(), KeyNetwork::Test, 0).unwrap();
        assert!(a != b && a != none && b != none);
    }

    #[test]
    fn parse_balance_reads_chain_stats() {
        let json = r#"{"chain_stats":{"funded_txo_sum":1000,"spent_txo_sum":200},"mempool_stats":{"funded_txo_sum":50,"spent_txo_sum":0}}"#;
        assert_eq!(parse_address_balance(json).unwrap(), 850);
    }

    #[test]
    fn real_address_summary_gives_its_balance() {
        let real = include_str!("../fixtures/esplora/blockstream_testnet_address_real.json");
        assert_eq!(parse_address_balance(real).unwrap(), 79_177_760);
    }

    fn outpoints(json: &str) -> Vec<String> {
        parse_utxos(json)
            .unwrap()
            .iter()
            .map(|u| format!("{}:{} {}", u.txid, u.vout, u.value))
            .collect()
    }

    // Real Esplora puts a nested "status" object between "vout" and "value".
    #[test]
    fn real_esplora_utxos_parse_in_order() {
        let real = include_str!("../fixtures/esplora/testnet4_utxo_real_extract.json");
        assert_eq!(
            outpoints(real),
            [
                "d81587ef11ffab4230dbba9bce41faed7c18dfe561111f18323aeb26da5be194:1 10000",
                "14d8cee6cf445d0bc12e8959435e7e97a5e1833d54391bdbe2a29d72917f4b5a:1 5000",
            ]
        );
    }

    #[test]
    fn an_empty_list_is_an_empty_wallet() {
        assert!(outpoints("[]").is_empty());
    }

    #[test]
    fn an_unconfirmed_utxo_is_kept() {
        let unconfirmed = include_str!("../fixtures/esplora/synthetic_utxo_unconfirmed.json");
        assert_eq!(
            outpoints(unconfirmed),
            ["0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef:0 1234"]
        );
    }

    #[test]
    fn unknown_fields_are_ignored() {
        let extra = include_str!("../fixtures/esplora/synthetic_utxo_extra_fields.json");
        assert_eq!(
            outpoints(extra),
            ["0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef:2 5000"]
        );
    }

    #[test]
    fn a_malformed_utxo_list_is_an_error_not_an_empty_wallet() {
        for body in [
            "<html>Sign in to Wi-Fi</html>",
            include_str!("../fixtures/esplora/synthetic_utxo_not_an_array.json"),
            include_str!("../fixtures/esplora/synthetic_utxo_missing_value.json"),
            include_str!("../fixtures/esplora/synthetic_utxo_vout_not_a_number.json"),
            include_str!("../fixtures/esplora/synthetic_utxo_vout_above_u32.json"),
            include_str!("../fixtures/esplora/synthetic_utxo_txid_not_64_hex.json"),
        ] {
            match parse_utxos(body) {
                Err(MobileSignerError::InvalidPsbtJson(m)) => {
                    assert!(m.starts_with("esplora utxo:"), "{body}: {m}")
                }
                Err(e) => panic!("{body}: wrong error {e:?}"),
                Ok(found) => panic!("{body}: accepted as {} utxos", found.len()),
            }
        }
    }
}
