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
use serde_json::Value;

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

/// One transaction of the hot wallet's address, seen from the wallet's side.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct HotTx {
    pub txid: String,
    /// Sats the wallet gained (positive) or lost (negative), fee included: the outputs paying the
    /// address minus the inputs that spent from it. A payment to yourself reads as minus the fee.
    pub net_sats: i64,
    /// 0 while in the mempool, 1 in the newest block, and so on. Never 0 once confirmed.
    pub confirmations: u32,
    /// Unix seconds of the block that holds it. Null while unconfirmed, and when the server gives
    /// 0 or a time after the start of 2100, which the app could not print.
    pub block_time: Option<u64>,
}

// 2100-01-01T00:00:00Z. The app prints a block time with a date formatter that wraps on absurd
// values, so a server must not be able to hand it one.
const LATEST_BLOCK_TIME: u64 = 4_102_444_800;

/// The latest transactions of the wallet's address, each as the wallet saw it. The order is
/// Esplora's: the mempool first, then confirmed transactions newest first. Only the first page
/// comes back (about 25 confirmed transactions plus the mempool), so older history is not here.
///
/// Makes two network calls, so call it off the main thread. An answer it cannot read fails the
/// whole call, never one transaction.
#[uniffi::export]
pub fn hot_wallet_history(
    mnemonic_words: Vec<String>,
    passphrase: String,
    network: KeyNetwork,
) -> Result<Vec<HotTx>, MobileSignerError> {
    let mnemonic = mnemonic_from(&mnemonic_words)?;
    let (_pk, address) = receive_privkey(&mnemonic, &passphrase, network_of(network), 0)?;
    let base = esplora_base(network)?;
    // shortcut: first page only, upgrade when a full History screen exists
    let txs = http_get(&format!("{base}/address/{address}/txs"))?;
    // Second, so a block landing between the two calls only raises the tip above every listed tx.
    let tip = http_get(&format!("{base}/blocks/tip/height"))?;
    history_from(&txs, &tip, &address.to_string())
}

fn history_from(
    txs_json: &str,
    tip_text: &str,
    address: &str,
) -> Result<Vec<HotTx>, MobileSignerError> {
    let bad = |what: String| MobileSignerError::InvalidPsbtJson(format!("esplora history: {what}"));
    let tip: u64 = tip_text
        .trim()
        .parse()
        .map_err(|e| bad(format!("tip height is not an integer: {e}")))?;
    let body: Value = serde_json::from_str(txs_json).map_err(|e| bad(format!("not JSON: {e}")))?;
    let txs = body.as_array().ok_or_else(|| bad("not an array".into()))?;
    // A bad transaction fails the list. Skipping it would show a history that is quietly wrong.
    txs.iter()
        .enumerate()
        .map(|(i, tx)| hot_tx(tx, tip, address).map_err(|e| bad(format!("tx {i}: {e}"))))
        .collect()
}

fn hot_tx(tx: &Value, tip: u64, address: &str) -> Result<HotTx, String> {
    let txid = tx["txid"].as_str().ok_or("no string txid")?;
    let vin = tx["vin"].as_array().ok_or("vin is not an array")?;
    let vout = tx["vout"].as_array().ok_or("vout is not an array")?;

    let mut net = 0i64;
    for (j, output) in vout.iter().enumerate() {
        let value = sats(&output["value"])
            .ok_or_else(|| format!("output {j} has no unsigned integer value"))?;
        if output["scriptpubkey_address"].as_str() == Some(address) {
            net = net.checked_add(value).ok_or("net sats overflow")?;
        }
    }
    for (j, input) in vin.iter().enumerate() {
        let prevout = &input["prevout"];
        if prevout.is_null() && input["is_coinbase"] == true {
            continue;
        }
        let value = sats(&prevout["value"])
            .ok_or_else(|| format!("input {j} has no prevout with an unsigned integer value"))?;
        if prevout["scriptpubkey_address"].as_str() == Some(address) {
            net = net.checked_sub(value).ok_or("net sats overflow")?;
        }
    }

    let status = &tx["status"];
    let (confirmations, block_time) = if status["confirmed"]
        .as_bool()
        .ok_or("status.confirmed is not a boolean")?
    {
        let height = status["block_height"]
            .as_u64()
            .ok_or("confirmed without an integer block_height")?;
        // A lagging backend or a reorg can leave the tip below the height. That still reads 1.
        let depth = tip.saturating_sub(height).saturating_add(1);
        let time = status["block_time"]
            .as_u64()
            .filter(|t| (1..=LATEST_BLOCK_TIME).contains(t));
        (u32::try_from(depth).unwrap_or(u32::MAX), time)
    } else {
        (0, None)
    };
    Ok(HotTx {
        txid: txid.to_string(),
        net_sats: net,
        confirmations,
        block_time,
    })
}

// i64 because a net is signed, and a value above i64::MAX is no real amount.
fn sats(value: &Value) -> Option<i64> {
    value.as_u64().and_then(|n| i64::try_from(n).ok())
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

    macro_rules! history_fixture {
        ($name:literal) => {
            include_str!(concat!("../fixtures/history/", $name))
        };
    }

    // abandon-about at BIP-84 testnet index 0: the address the hand-made fixtures call the wallet.
    const WALLET: &str = "tb1q6rz28mcfaxtmd6v789l9rrlrusdprr9pqcpvkl";
    // The tip height the hand-made fixtures were written against.
    const TIP: &str = "105";

    fn id(digit: char) -> String {
        digit.to_string().repeat(64)
    }

    fn hot(txid: &str, net_sats: i64, confirmations: u32, block_time: Option<u64>) -> HotTx {
        HotTx {
            txid: txid.to_string(),
            net_sats,
            confirmations,
            block_time,
        }
    }

    fn history(fixture: &str) -> Vec<HotTx> {
        history_from(fixture, TIP, WALLET).unwrap()
    }

    const REAL_TXS: &str = history_fixture!("blockstream_testnet_txs_real.json");
    const REAL_TIP: &str = history_fixture!("blockstream_testnet_tip_height.txt");
    // Four transactions of one testnet block, the first a coinbase, in the order Esplora gave them.
    const REAL_TXIDS: [&str; 4] = [
        "aa0dc317065d2c7ddc9ec03b9f1eb50eff40d2f9bee2bc8334d2cf8a7e164b71",
        "10af14dd16432fe677db864e718a015ec5d4da62ee21f5a596e5fdb8a55be2a7",
        "91b1f75e2f85a1856c1fcb879a69fc404581c93481eba62610d2f2b5556779ba",
        "64eaaf3bdfa169ab2a7077fbd4078eed1db86b7ce96177cd76ba35464d4f0ee4",
    ];
    const REAL_COINBASE_PAYEE: &str = "tb1qnjvttgnl0gkxn99gmsd8g8zzpe7nckssmy56hu";

    // These nets were tallied outside this crate, by a script that read the same four transactions
    // and added every output and every prevout per address. The script also checked the recording
    // against its own fee fields. The block is 5259564 and the tip 5259565: two confirmations.
    #[test]
    fn the_real_recording_gives_each_address_its_net_in_the_order_given() {
        let cases: [(&str, [i64; 4]); 4] = [
            // The coinbase pays it 132. The coinbase input has no prevout and counts as nothing.
            (REAL_COINBASE_PAYEE, [132, 0, 0, 0]),
            // Four inputs of 100000 in the third transaction and no output back.
            (
                "tb1q0l93tn8dyewj602wpe7vj24hfm7jdyv68q2vnj",
                [0, 0, -400_000, 0],
            ),
            // Spends 30000 and is paid 39998, then spends 30000 and is paid 39999.
            (
                "tb1qx54dlmfp0qnqjsqhy8585f0sa0udwdzhm2wwng",
                [0, 0, 9_998, 9_999],
            ),
            // Paid 389796 by the third transaction and spends all of it in the fourth.
            (
                "tb1qwczmuu3h6ch0jxyrp2fdvuprvrw4dcnyde0nj9",
                [0, 0, 389_796, -389_796],
            ),
        ];
        for (address, nets) in cases {
            let expected: Vec<HotTx> = REAL_TXIDS
                .iter()
                .zip(nets)
                .map(|(txid, net)| hot(txid, net, 2, Some(1_793_560_954)))
                .collect();
            assert_eq!(
                history_from(REAL_TXS, REAL_TIP, address).unwrap(),
                expected,
                "{address}"
            );
        }
    }

    #[test]
    fn whitespace_around_the_tip_is_ignored() {
        for tip in ["5259565\n", " 5259565 "] {
            let found = history_from(REAL_TXS, tip, REAL_COINBASE_PAYEE).unwrap();
            assert!(found.iter().all(|t| t.confirmations == 2), "{tip:?}");
        }
    }

    #[test]
    fn a_tip_beyond_u32_saturates_the_confirmations() {
        let found = history_from(REAL_TXS, "99999999999", REAL_COINBASE_PAYEE).unwrap();
        assert_eq!(found.len(), 4);
        assert!(found.iter().all(|t| t.confirmations == u32::MAX));
    }

    #[test]
    fn an_address_with_no_transactions_has_an_empty_history() {
        assert!(history("[]").is_empty());
    }

    // 100000 in, 30000 to someone else, 69000 back, 1000 fee: the wallet is 31000 lighter.
    #[test]
    fn a_spend_with_change_back_is_minus_the_amount_and_the_fee() {
        assert_eq!(
            history(history_fixture!("synthetic_spend_with_change.json")),
            [hot(&id('1'), -31_000, 6, Some(1_790_000_000))]
        );
    }

    // 50000 in and 49000 back to itself: only the fee leaves.
    #[test]
    fn a_self_send_is_minus_the_fee() {
        assert_eq!(
            history(history_fixture!("synthetic_self_send.json")),
            [hot(&id('2'), -1_000, 3, Some(1_790_000_100))]
        );
    }

    // 20000 in, 15000 to someone else, 4000 back, 1000 fee.
    #[test]
    fn an_unconfirmed_transaction_has_no_confirmations_and_no_block_time() {
        assert_eq!(
            history(history_fixture!("synthetic_unconfirmed.json")),
            [hot(&id('3'), -16_000, 0, None)]
        );
    }

    #[test]
    fn block_fields_on_an_unconfirmed_transaction_are_not_believed() {
        assert_eq!(
            history(history_fixture!(
                "synthetic_unconfirmed_with_stray_block_fields.json"
            )),
            [hot(&id('4'), 7_000, 0, None)]
        );
    }

    // 40000 + 30000 + 20000 in, 89000 to someone else, nothing back.
    #[test]
    fn three_wallet_inputs_and_one_outside_output_are_all_spent() {
        assert_eq!(
            history(history_fixture!(
                "synthetic_three_wallet_inputs_one_outside_output.json"
            )),
            [hot(&id('5'), -90_000, 5, Some(1_790_000_200))]
        );
    }

    // Out 14000, in 10000. The 5000 input and the 700 output have no address and are nobody's.
    #[test]
    fn an_input_or_output_without_an_address_counts_as_nothing() {
        assert_eq!(
            history(history_fixture!(
                "synthetic_input_without_prevout_address.json"
            )),
            [hot(&id('6'), 4_000, 2, Some(1_790_000_300))]
        );
    }

    #[test]
    fn a_block_time_the_app_could_not_print_is_dropped() {
        let at_6 = |digit, time| hot(&id(digit), 1_000, 6, time);
        assert_eq!(
            history(history_fixture!("synthetic_block_times.json")),
            [
                at_6('1', None),                // 99999999999
                at_6('2', None),                // 4102444801, one second past the limit
                at_6('3', Some(4_102_444_800)), // the limit itself
                at_6('4', Some(1)),
                at_6('5', None), // 0
                at_6('6', None), // no block_time
                at_6('7', None), // a string
                at_6('8', None), // negative
            ]
        );
    }

    // Tip 105. A block at 105 or above it (a lagging backend, a reorg) still reads 1.
    #[test]
    fn a_transaction_at_or_above_the_tip_reads_one_confirmation() {
        let at = |digit, confirmations| hot(&id(digit), 1_000, confirmations, Some(1_790_000_400));
        assert_eq!(
            history(history_fixture!("synthetic_blocks_around_the_tip.json")),
            [at('1', 2), at('2', 1), at('3', 1), at('4', 1)]
        );
    }

    fn assert_rejected(txs: &str, tip: &str, rule: &str) {
        match history_from(txs, tip, WALLET) {
            Err(MobileSignerError::InvalidPsbtJson(m)) => {
                assert!(m.starts_with("esplora history:"), "{rule}: {m}");
                assert!(m.contains(rule), "{rule}: {m}");
            }
            Err(e) => panic!("{rule}: wrong error {e:?}"),
            Ok(found) => panic!("{rule}: accepted as {} transactions", found.len()),
        }
    }

    // These files go wrong in a second transaction. The good first one must not survive alone.
    #[test]
    fn an_answer_it_cannot_read_is_an_error_not_a_shorter_list() {
        let good = history_fixture!("synthetic_spend_with_change.json");
        assert_rejected(
            history_fixture!("synthetic_malformed_html_page.html"),
            TIP,
            "not JSON",
        );
        assert_rejected(
            history_fixture!("synthetic_malformed_object_not_array.json"),
            TIP,
            "not an array",
        );
        assert_rejected(
            history_fixture!("synthetic_malformed_tx_without_vout.json"),
            TIP,
            "tx 1: vout",
        );
        assert_rejected(
            history_fixture!("synthetic_malformed_output_value_string.json"),
            TIP,
            "tx 1: output 1",
        );
        let tips = [
            history_fixture!("synthetic_malformed_tip_abc.txt"),
            "",
            "12.5",
            "-1",
            "18446744073709551616",
        ];
        for tip in tips {
            assert_rejected(good, tip, "tip height");
        }
        assert_rejected("[]", "abc", "tip height");
    }

    fn one_tx(vin: &str, vout: &str, status: &str) -> String {
        format!(
            r#"[{{"txid":"{}","vin":{vin},"vout":{vout},"status":{status}}}]"#,
            id('9')
        )
    }

    const MEMPOOL: &str = r#"{"confirmed":false}"#;

    fn wallet_outputs(values: &[&str]) -> String {
        let items: Vec<String> = values
            .iter()
            .map(|v| format!(r#"{{"scriptpubkey_address":"{WALLET}","value":{v}}}"#))
            .collect();
        format!("[{}]", items.join(","))
    }

    fn wallet_inputs(values: &[&str]) -> String {
        let items: Vec<String> = values
            .iter()
            .map(|v| {
                let prevout = format!(r#"{{"scriptpubkey_address":"{WALLET}","value":{v}}}"#);
                format!(r#"{{"is_coinbase":false,"prevout":{prevout}}}"#)
            })
            .collect();
        format!("[{}]", items.join(","))
    }

    #[test]
    fn a_transaction_missing_a_part_fails_the_whole_call() {
        let confirmed = |height: &str| format!(r#"{{"confirmed":true,"block_height":{height}}}"#);
        let other_value = |value: &str| {
            format!(r#"[{{"scriptpubkey_address":"tb1qsomeoneelse","value":{value}}}]"#)
        };
        let no_value = format!(r#"[{{"scriptpubkey_address":"{WALLET}"}}]"#);
        let huge = ["5000000000000000000", "5000000000000000000"];
        let cases = [
            (
                r#"[{"vin":[],"vout":[],"status":{"confirmed":false}}]"#.to_string(),
                "no string txid",
            ),
            (one_tx("{}", "[]", MEMPOOL), "vin is not an array"),
            (one_tx("[]", "null", MEMPOOL), "vout is not an array"),
            (one_tx("[]", &wallet_outputs(&["-1"]), MEMPOOL), "output 0"),
            (one_tx("[]", &wallet_outputs(&["1.5"]), MEMPOOL), "output 0"),
            (
                one_tx("[]", &wallet_outputs(&["9223372036854775808"]), MEMPOOL),
                "output 0",
            ),
            (one_tx("[]", &no_value, MEMPOOL), "output 0"),
            // A bad value fails even when the output is not the wallet's.
            (one_tx("[]", &other_value(r#""5""#), MEMPOOL), "output 0"),
            (
                one_tx(r#"[{"is_coinbase":false}]"#, "[]", MEMPOOL),
                "input 0",
            ),
            (
                one_tx(r#"[{"is_coinbase":false,"prevout":null}]"#, "[]", MEMPOOL),
                "input 0",
            ),
            (one_tx("[{}]", "[]", MEMPOOL), "input 0"),
            (
                one_tx(&wallet_inputs(&[r#""5""#]), "[]", MEMPOOL),
                "input 0",
            ),
            (one_tx("[]", "[]", "null"), "status.confirmed"),
            (
                one_tx("[]", "[]", r#"{"confirmed":"true"}"#),
                "status.confirmed",
            ),
            (one_tx("[]", "[]", r#"{"confirmed":true}"#), "block_height"),
            (one_tx("[]", "[]", &confirmed(r#""100""#)), "block_height"),
            (one_tx("[]", &wallet_outputs(&huge), MEMPOOL), "overflow"),
            (one_tx(&wallet_inputs(&huge), "[]", MEMPOOL), "overflow"),
        ];
        for (body, rule) in cases {
            assert_rejected(&body, TIP, rule);
        }
    }

    #[test]
    fn a_coinbase_input_counts_as_nothing_with_or_without_a_prevout_key() {
        for vin in [
            r#"[{"is_coinbase":true,"prevout":null}]"#,
            r#"[{"is_coinbase":true}]"#,
        ] {
            let body = one_tx(vin, &wallet_outputs(&["5000"]), MEMPOOL);
            assert_eq!(history(&body), [hot(&id('9'), 5_000, 0, None)], "{vin}");
        }
    }
}
