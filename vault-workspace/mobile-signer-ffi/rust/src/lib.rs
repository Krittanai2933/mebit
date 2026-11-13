//! UniFFI exports over `vault-core` for the Kotlin Multiplatform app in `../app/mobileSignerFfi`.
//! Every exported signature and every `///` doc comment on an export feeds a UniFFI checksum.
//! No exported error, record or enum does, and Kotlin reads their variants and fields by
//! position, so after changing one run `scripts/ffi.sh build`. Only `scripts/ffi.sh check` sees
//! that drift.

uniffi::setup_scaffolding!();

mod address_type;
pub mod ble;
mod chain;
pub mod hot_wallet;
pub mod price;
mod proof;
mod scan;
mod scan_store;
pub mod script;
pub mod spend;

use std::str::FromStr;

use bip39::{Language, Mnemonic};
use bitcoin::secp256k1::Secp256k1;
use bitcoin::{Network, NetworkKind, bip32::Xpub};
use vault_core::derivation;
use vault_core::descriptor::{self, PartyPubkeys};
use vault_core::error::VaultCoreError;
use vault_core::keys::{
    self, ScriptType, account_multisig_xpub_from_mnemonic, generate_master_xpriv, generate_seed,
};
use vault_core::psbt::UnsignedPsbt;

/// Every failure an export returns.
///
/// Flat on purpose: Kotlin gets `sealed class MobileSignerException` with one
/// subclass per variant, and `message` is exactly this type's `Display` text.
/// Callers branch on the subclass; fields are not exposed, so anything a UI
/// needs (the list index) is in the message.
///
/// A message about a key names its list position, never the key's text.
#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum MobileSignerError {
    /// `sign_psbt` input is not a JSON `vault_core::psbt::UnsignedPsbt`.
    #[error("invalid PSBT json: {0}")]
    InvalidPsbtJson(String),

    /// The string at `index` is not a base58 `xpub` or `tpub`, or is too long or too
    /// deep to derive addresses from. SLIP-132 forms (`zpub`, `Zpub`, `vpub`, ...)
    /// land here as "unknown version magic bytes".
    #[error("key at index {index} {reason}")]
    // `reason` completes the sentence, so it carries its own predicate.
    InvalidXpub { index: usize, reason: String },

    /// Key `index` and key 0 belong to different networks (an `xpub` mixed with
    /// a `tpub`).
    #[error(
        "key at index {index} is for a different network than key at index 0 (xpub is mainnet, tpub is testnet)"
    )]
    NetworkMismatch { index: usize },

    /// Key `index` has the same public key as key `first`.
    #[error("key at index {index} is the same as key at index {first}")]
    DuplicateXpub { first: usize, index: usize },

    /// The threshold does not fit the key list (k = 0, k > n, n = 0 or n > 20).
    /// The text is vault-core's own, e.g. "invalid multisig threshold: invalid threshold 4-of-3; cannot have k > n".
    #[error("{0}")]
    InvalidThreshold(String),

    /// Any other vault-core failure. Not expected for valid keys: report it as a bug.
    /// The text is `VaultCoreError`'s own `Display`, which already names its source.
    #[error("{0}")]
    VaultCore(String),

    /// The words are not a BIP-39 mnemonic. The text names a word index or a count,
    /// never the words.
    #[error("invalid mnemonic: {0}")]
    InvalidMnemonic(String),

    /// A network call failed or answered something unusable. The text names the cause. Kotlin
    /// reads variants by position, so any new one goes below this.
    #[error("network error: {0}")]
    Network(String),

    /// `hot_broadcast` posted a payment but cannot tell whether the explorer took it: the answer was
    /// lost or unclear, and the transaction was still not found after the last lookup. The text
    /// carries the txid. The payment may be out, so repeating it may pay twice. Kotlin reads
    /// variants by position, so any new one goes below this.
    #[error(
        "it is not known whether transaction {0} was sent, so do not repeat the payment until the wallet and this transaction id have been checked"
    )]
    BroadcastUnknown(String),
}

impl From<VaultCoreError> for MobileSignerError {
    fn from(e: VaultCoreError) -> Self {
        let text = e.to_string();
        match e {
            VaultCoreError::Threshold(_) => Self::InvalidThreshold(text),
            VaultCoreError::Mnemonic(_) => Self::InvalidMnemonic(text),
            _ => Self::VaultCore(text),
        }
    }
}

/// Bitcoin mainnet, or testnet4. Testnet4 matches the network the Bluetooth
/// link already uses.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum KeyNetwork {
    Main,
    Test,
}

impl KeyNetwork {
    fn bitcoin(self) -> Network {
        match self {
            Self::Main => Network::Bitcoin,
            Self::Test => Network::Testnet4,
        }
    }
}

/// A 24-word BIP-39 mnemonic and the BIP-48 P2WSH account key derived from it.
///
/// `mnemonic_words` is the one copy of the secret that crosses into Kotlin.
/// Store it, then drop this record.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct SoftwareAccountKey {
    pub mnemonic_words: Vec<String>,
    /// Master key fingerprint, 8 lowercase hex characters.
    pub master_fingerprint: String,
    pub xpub: String,
    /// `m/48'/coin'/account'/2'`, coin 0 on `Main` and coin 1 on `Test`.
    pub derivation_path: String,
}

/// MOCK. vault-core's `derive_child_pubkey` is a format string: this returns
/// `"{account_xpub}/m/48'/0'/0'/2'/{index}"`, not a public key.
#[uniffi::export]
pub fn derive_borrower_pubkey(account_xpub: &str, index: u32) -> String {
    derivation::derive_child_pubkey(account_xpub, index)
}

/// MOCK, legacy three-party (borrower/platform/lender) 2-of-3 lending vault.
/// Returns `"tb1q"` + hex of a Rust `DefaultHasher` digest: not a Bitcoin
/// address, and not stable across Rust releases. For a real address use
/// `compute_vault_address_multisig`.
#[uniffi::export]
pub fn compute_vault_address(
    borrower_pubkey: &str,
    platform_pubkey: &str,
    lender_pubkey: &str,
) -> String {
    let parties = PartyPubkeys {
        borrower: borrower_pubkey.to_string(),
        platform: platform_pubkey.to_string(),
        lender: lender_pubkey.to_string(),
    };
    descriptor::build_descriptor(&parties).address
}

/// MOCK. Returns `"MOCK_SIG[{loan_id}:{output count}:{borrower_pubkey}]"`. Signs
/// nothing and runs no policy check (`PolicyEngine::authorize` is not called).
#[uniffi::export]
pub fn sign_psbt(psbt_json: &str, borrower_pubkey: &str) -> Result<String, MobileSignerError> {
    let psbt: UnsignedPsbt = serde_json::from_str(psbt_json)
        .map_err(|e| MobileSignerError::InvalidPsbtJson(e.to_string()))?;
    Ok(format!(
        "MOCK_SIG[{}:{}:{}]",
        psbt.loan_id,
        psbt.outputs.len(),
        borrower_pubkey
    ))
}

/// First receive address of a `threshold`-of-`xpubs.len()` P2WSH multisig: address
/// 0 of vault-core's sorted-multisig descriptor over `<xpub>/0/<index>` for every key.
///
/// - `xpubs`: base58 `xpub` (mainnet) or `tpub` (test networks), any order
///   (sortedmulti), all from one network, no public key twice.
/// - Network is inferred from the version bytes, because the fixed two-argument
///   signature has no network parameter: `xpub` gives `bc1...`; `tpub` gives
///   `tb1...` (built as `Network::Testnet4`; the string is identical for testnet3
///   and signet). Regtest (`bcrt1...`) cannot be produced by this function.
/// - Index is fixed at 0 for the same reason. Other indexes and the change
///   chain (derivation path `/1/<index>`) need a new export, not a new argument here.
/// - `threshold`: 1 <= threshold <= xpubs.len() <= 20.
#[uniffi::export]
pub fn compute_vault_address_multisig(
    xpubs: Vec<String>,
    threshold: u8,
) -> Result<String, MobileSignerError> {
    let keys = parse_and_check_xpubs(&xpubs)?;
    let descriptor = descriptor::build_multisig_descriptor(&keys, threshold.into())?;
    let network = match keys.first().map(|key| key.network) {
        Some(NetworkKind::Main) => Network::Bitcoin,
        Some(NetworkKind::Test) => Network::Testnet4,
        None => unreachable!("build_multisig_descriptor rejects an empty key list"),
    };
    Ok(descriptor::derive_address_at(&descriptor, 0, network)?.to_string())
}

/// A new 24-word BIP-39 mnemonic and its BIP-48 P2WSH account key.
///
/// `passphrase` may be empty. `Main` derives an `xpub` at `m/48'/0'/account'/2'`.
/// `Test` derives a `tpub` at `m/48'/1'/account'/2'` on testnet4.
/// The words are returned once, in `mnemonic_words`.
#[uniffi::export]
pub fn generate_software_multisig_key(
    passphrase: String,
    network: KeyNetwork,
    account: u32,
) -> Result<SoftwareAccountKey, MobileSignerError> {
    let entropy = keys::generate_entropy()?;
    let mnemonic = keys::generate_mnemonic(&entropy)?;
    drop(entropy);
    software_account_key(&mnemonic, &passphrase, network, account)
}

/// The BIP-48 P2WSH account key for an existing BIP-39 mnemonic.
///
/// `mnemonic_words` is the 12, 15, 18, 21 or 24 words, or one string that
/// already joins them with spaces. The record echoes the normalized words.
/// `passphrase`, `network` and `account` mean the same as in
/// `generate_software_multisig_key`.
#[uniffi::export]
pub fn restore_software_multisig_key(
    mnemonic_words: Vec<String>,
    passphrase: String,
    network: KeyNetwork,
    account: u32,
) -> Result<SoftwareAccountKey, MobileSignerError> {
    let mnemonic = Mnemonic::parse_normalized(&mnemonic_words.join(" "))
        .map_err(|error| MobileSignerError::InvalidMnemonic(error.to_string()))?;
    software_account_key(&mnemonic, &passphrase, network, account)
}

/// What `check_mnemonic` found. `index` and `count` are positions and sizes of the
/// list the caller passed, never the words themselves.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum MnemonicCheck {
    Valid,
    /// The word at `index` (0-based) is not in the BIP-39 English list.
    UnknownWord {
        index: u32,
    },
    /// BIP-39 mnemonics have 12, 15, 18, 21 or 24 words.
    BadWordCount {
        count: u32,
    },
    /// Every word is in the list, but the checksum does not match.
    InvalidChecksum,
}

/// Is `words` a valid BIP-39 English mnemonic, and if not, why? Nothing is retained.
#[uniffi::export]
pub fn check_mnemonic(words: Vec<String>) -> Result<MnemonicCheck, MobileSignerError> {
    match Mnemonic::parse_normalized(&words.join(" ")) {
        Ok(_) => Ok(MnemonicCheck::Valid),
        Err(bip39::Error::UnknownWord(index)) => Ok(MnemonicCheck::UnknownWord {
            index: index as u32,
        }),
        Err(bip39::Error::BadWordCount(count)) => Ok(MnemonicCheck::BadWordCount {
            count: count as u32,
        }),
        Err(bip39::Error::InvalidChecksum) => Ok(MnemonicCheck::InvalidChecksum),
        // Unreachable with the English-only list: report it as a bug.
        Err(other) => Err(MobileSignerError::InvalidMnemonic(other.to_string())),
    }
}

/// Up to `limit` BIP-39 English words that start with `prefix`, in list order.
/// An empty prefix gives none.
#[uniffi::export]
pub fn bip39_suggest(prefix: String, limit: u32) -> Vec<String> {
    let prefix = prefix.trim().to_lowercase();
    if prefix.is_empty() {
        return Vec::new();
    }
    Language::English
        .words_by_prefix(&prefix)
        .iter()
        .take(limit as usize)
        .map(|word| (*word).to_string())
        .collect()
}

/// The words that turn `first_words` into a valid mnemonic, in list order. For 23
/// words that is 8: the last word carries 3 entropy bits and 8 checksum bits.
/// Empty when a word is not in the list or the count cannot become 12, 15, 18, 21
/// or 24 words.
#[uniffi::export]
pub fn bip39_last_word_candidates(first_words: Vec<String>) -> Vec<String> {
    if ![11, 14, 17, 20, 23].contains(&first_words.len())
        || first_words
            .iter()
            .any(|word| Language::English.find_word(word).is_none())
    {
        return Vec::new();
    }
    let prefix = first_words.join(" ");
    Language::English
        .word_list()
        .iter()
        .filter(|word| Mnemonic::parse_normalized(&format!("{prefix} {word}")).is_ok())
        .map(|word| (*word).to_string())
        .collect()
}

fn software_account_key(
    mnemonic: &Mnemonic,
    passphrase: &str,
    network: KeyNetwork,
    account: u32,
) -> Result<SoftwareAccountKey, MobileSignerError> {
    let network = network.bitcoin();
    let (mut account_xpriv, xpub) = account_multisig_xpub_from_mnemonic(
        mnemonic,
        passphrase,
        network,
        account,
        ScriptType::P2wsh,
    )?;
    // `Xpriv` is `Copy`. This is the one account-key copy this function owns.
    account_xpriv.private_key.non_secure_erase();

    let secp = Secp256k1::new();
    let mut master = generate_master_xpriv(network, &generate_seed(mnemonic, passphrase))?;
    let master_fingerprint = master.fingerprint(&secp).to_string();
    master.private_key.non_secure_erase();

    Ok(SoftwareAccountKey {
        mnemonic_words: mnemonic.words().map(str::to_owned).collect(),
        master_fingerprint,
        xpub: xpub.to_string(),
        derivation_path: personal_multisig_path(network, account),
    })
}

// Same path `account_multisig_xpub_from_mnemonic` derives for `ScriptType::P2wsh`.
// Coin 0 is Bitcoin and coin 1 is every other network, matching keys.rs.
fn personal_multisig_path(network: Network, account: u32) -> String {
    let coin = match network {
        Network::Bitcoin => 0,
        Network::Testnet | Network::Testnet4 | Network::Signet | Network::Regtest => 1,
    };
    format!("m/48'/{coin}'/{account}'/2'")
}

// Base58 decoding is quadratic. Measured in a release build: 0.09 s at 10,000 characters and 7.3 s at 100,000.
const MAX_XPUB_CHARS: usize = 120;
// `derive_address_at` derives two levels below the key, so depth + 2 must stay within 255. Past that,
// miniscript 13.1.0 panics and 13.2.0 returns an error. Rejecting here gives both the same InvalidXpub.
const MAX_XPUB_DEPTH: u8 = 253;

// TODO(vault-core descriptor owners): move the network and duplicate checks into
// `build_multisig_descriptor`, which the jade-ble/trezor-ble harnesses and
// wallet-e2e also call without them, and raise vault-core's `miniscript = "13"` to "13.2"
// so that a key deeper than MAX_XPUB_DEPTH is an error and not a panic in those callers.
// Delete the network and duplicate checks here then. The depth check can stay for its precise error.
fn parse_and_check_xpubs(xpubs: &[String]) -> Result<Vec<Xpub>, MobileSignerError> {
    let mut keys = Vec::<Xpub>::with_capacity(xpubs.len());
    for (index, text) in xpubs.iter().enumerate() {
        let invalid = |reason: String| MobileSignerError::InvalidXpub { index, reason };
        let not_valid = |detail: String| invalid(format!("is not a valid xpub or tpub: {detail}"));
        let chars = text.chars().count();
        if chars > MAX_XPUB_CHARS {
            return Err(not_valid(format!(
                "{chars} characters, longer than any extended key"
            )));
        }
        let key = Xpub::from_str(text).map_err(|e| not_valid(e.to_string()))?;
        if key.depth > MAX_XPUB_DEPTH {
            return Err(invalid(format!(
                "is too deep to derive addresses from: depth {}, the maximum is {MAX_XPUB_DEPTH}",
                key.depth
            )));
        }
        if keys
            .first()
            .is_some_and(|first| first.network != key.network)
        {
            return Err(MobileSignerError::NetworkMismatch { index });
        }
        // Compare public keys, not whole xpubs. Whoever holds the private key of a
        // public key can sign for every unhardened child of any xpub carrying it,
        // whatever its chain code. That catches one key entered twice, even
        // re-encoded. It cannot prove the keys belong to different signers. A child
        // of a listed key, or a second account from one seed, has a different
        // public key and passes.
        if let Some(first) = keys.iter().position(|k| k.public_key == key.public_key) {
            return Err(MobileSignerError::DuplicateXpub { first, index });
        }
        keys.push(key);
    }
    Ok(keys)
}

#[cfg(test)]
mod tests {
    use super::*;
    use bitcoin::bip32::{ChainCode, ChildNumber};

    // xpubs: vault-core key derivation over BIP-39 entropy 32x 0x00 / 0x7f / 0x80, m/48'/coin'/0'/2'.
    // `python3 scripts/address_oracle.py` re-derives the three addresses from these constants.
    const TPUB: [&str; 3] = [
        "tpubDFkN51vYF36W4Yfn3wGv5fpmRo3ok7vZZjc1gmRJjumq33L776e6GkP4HGdCVjDqYiBahXCrXQKja8aUZ2xovQNS8WkF46MdY7TLHJLYD7H",
        "tpubDF41CiHSj6Y4iSH7syioTxCr9cvMXLXvz4sGzvg4MSYfnid68mGMStcADFnKGnYmwL5GJ6ZzqvypiZaHs3AefpGuDrzMa56PYHVELc4crdQ",
        "tpubDEFfK5S7ZsjvCNXwUWVu1Lxcdpn55Fy8nLecgU1fLP4owzS9RKQVVx9YsxyfgDps6EiLBMkeNdQ1oLzWeDcj2tLbykFmNgchhT9FcSGFAv8",
    ];
    const XPUB: [&str; 3] = [
        "xpub6E79FaRWLSJCAgA2jDHRvyrWKwT6aSmR685zptzyYPvmUd44omcxZ1NAzDtbdFBvEADjcVbV4NzTDwQeU6oiSV9KGiMSWhjANZjbfUHkm3Y",
        "xpub6EikvjdbqbuVcGmLVQj4mrwuUX4xWSEEz8iG9C4SE7F5catvdwNZp4rmPxBdgbXDLgTcY3zKEc7dp9LQcDwKT8XYSmcJjyoob7DntQSJkYz",
        "xpub6EprBeE81P64tV7rRuMvFw23zJX1smjQdEg4jEVJ9vUv4SXrVwkdQPfW5uVhTBdnzetRthnHqwUpAbU3Z9y5yzAEzAaUG1iifJZnTHBEfRU",
    ];
    const TESTNET_2_OF_3: &str = "tb1q693w8s33wzc0pmrpu9yda3ngm2eu9hkzvkwagw4q69643my3xt0q9lcl0f";
    const MAINNET_2_OF_3: &str = "bc1qvmhkp7q475h5klcqm5karudgu8nhjxkqh498nghpds754vc6uy0sa7mrh9";
    // Address 0 of the change chain (/1/*), whose construction vault-core also checks against rust-bitcoin alone.
    const TESTNET_2_OF_3_CHANGE_0: &str =
        "tb1qzdk57kxf0rg9ndqsunchavw4vcfnpqt4we065xu9t53yqh2p5jyq7kv8tr";
    const ZPUB: &str = "Zpub74fkz9VnCkwXHqheLbKfBEPKPfnFg3S1Ecn7Jx4HgBWwD1FS5VLMtFYNqLmFBVifGuXKz2nirv647gFYCinhBSdBqrrh5vq8ok8m2eaRAt7";

    fn strings(keys: &[&str]) -> Vec<String> {
        keys.iter().map(|s| s.to_string()).collect()
    }

    // Unhardened children of TPUB[0]. Distinct public keys, but not independent signers.
    fn distinct_tpubs(n: u32) -> Vec<String> {
        let secp = bitcoin::secp256k1::Secp256k1::verification_only();
        let parent = Xpub::from_str(TPUB[0]).unwrap();
        (0..n)
            .map(|i| {
                parent
                    .ckd_pub(&secp, ChildNumber::Normal { index: i })
                    .unwrap()
                    .to_string()
            })
            .collect()
    }

    #[test]
    fn derives_pubkey_deterministically() {
        assert_eq!(
            derive_borrower_pubkey("xpub_borrower", 0),
            derive_borrower_pubkey("xpub_borrower", 0)
        );
    }

    #[test]
    fn computes_vault_address_deterministically() {
        let a = compute_vault_address("b_pk", "p_pk", "l_pk");
        let b = compute_vault_address("b_pk", "p_pk", "l_pk");
        assert_eq!(a, b);
        assert!(a.starts_with("tb1q"));
    }

    #[test]
    fn different_parties_produce_different_vault_address() {
        let a = compute_vault_address("b_pk", "p_pk", "l_pk");
        let b = compute_vault_address("b_pk", "p_pk", "different_lender_pk");
        assert_ne!(a, b);
    }

    #[test]
    fn signs_psbt() {
        let psbt_json = r#"{"loan_id":"loan-1","inputs":["utxo:0"],"outputs":[{"address":"tb1qborrower","amount_sats":100000}]}"#;
        let signature = sign_psbt(psbt_json, "borrower_pk").unwrap();
        assert!(signature.contains("loan-1"));
    }

    #[test]
    fn rejects_malformed_psbt_json() {
        assert!(sign_psbt("not valid json", "borrower_pk").is_err());
    }

    #[test]
    fn sign_psbt_mock_text_is_unchanged() {
        let json = r#"{"loan_id":"loan-1","inputs":["utxo:0"],"outputs":[{"address":"tb1qborrower","amount_sats":100000}]}"#;
        assert_eq!(
            sign_psbt(json, "borrower_pk").unwrap(),
            "MOCK_SIG[loan-1:1:borrower_pk]"
        );
        assert_eq!(
            derive_borrower_pubkey("xpub_borrower", 7),
            "xpub_borrower/m/48'/0'/0'/2'/7"
        );
        let e = sign_psbt("{", "pk").unwrap_err();
        assert!(matches!(e, MobileSignerError::InvalidPsbtJson(_)));
        let legacy = serde_json::from_str::<UnsignedPsbt>("{").unwrap_err();
        assert_eq!(e.to_string(), format!("invalid PSBT json: {legacy}"));
    }

    #[test]
    fn multisig_golden_addresses() {
        assert_eq!(
            compute_vault_address_multisig(strings(&TPUB), 2).unwrap(),
            TESTNET_2_OF_3
        );
        assert_eq!(
            compute_vault_address_multisig(strings(&XPUB), 2).unwrap(),
            MAINNET_2_OF_3
        );
        let rotated = strings(&[TPUB[2], TPUB[0], TPUB[1]]);
        assert_eq!(
            compute_vault_address_multisig(rotated, 2).unwrap(),
            TESTNET_2_OF_3
        );
    }

    #[test]
    fn multisig_equals_direct_vault_core_call() {
        let keys: Vec<Xpub> = TPUB.iter().map(|s| Xpub::from_str(s).unwrap()).collect();
        let descriptor = descriptor::build_multisig_descriptor(&keys, 2).unwrap();
        let direct = descriptor::derive_address_at(&descriptor, 0, Network::Testnet4).unwrap();
        assert_eq!(
            compute_vault_address_multisig(strings(&TPUB), 2).unwrap(),
            direct.to_string()
        );
    }

    #[test]
    fn the_change_chain_of_a_2_of_3_is_the_one_other_wallets_derive() {
        let mut keys = parse_and_check_xpubs(&strings(&TPUB)).unwrap();
        for _ in 0..keys.len() {
            let address = |branch| {
                let descriptor =
                    descriptor::build_multisig_descriptor_on(&keys, 2, branch).unwrap();
                descriptor::derive_address_at(&descriptor, 0, Network::Testnet4)
                    .unwrap()
                    .to_string()
            };
            assert_eq!(address(descriptor::Branch::Change), TESTNET_2_OF_3_CHANGE_0);
            assert_eq!(address(descriptor::Branch::Receive), TESTNET_2_OF_3);
            keys.rotate_left(1);
        }
    }

    #[test]
    fn threshold_errors() {
        let cases = [
            (strings(&TPUB), 0u8, "k > 0"),
            (strings(&TPUB), 4, "k > n"),
            (vec![], 1, "nonempty"),
            (distinct_tpubs(21), 2, "maximum size is 20"),
        ];
        for (keys, k, fragment) in cases {
            let e = compute_vault_address_multisig(keys, k).unwrap_err();
            assert!(matches!(e, MobileSignerError::InvalidThreshold(_)), "{e:?}");
            let text = e.to_string();
            assert!(text.starts_with("invalid multisig threshold: "), "{text}");
            assert!(text.contains(fragment), "{text}");
        }
        compute_vault_address_multisig(distinct_tpubs(20), 2)
            .expect("20 keys is the largest accepted list");
    }

    #[test]
    fn bad_xpub_names_its_index_and_never_its_text() {
        let e = compute_vault_address_multisig(strings(&[TPUB[0], "nope"]), 1).unwrap_err();
        assert!(matches!(e, MobileSignerError::InvalidXpub { index: 1, .. }));
        assert!(
            e.to_string()
                .starts_with("key at index 1 is not a valid xpub or tpub: ")
        );
        assert!(!e.to_string().contains("nope"));
    }

    #[test]
    fn zpub_rejected() {
        let e = compute_vault_address_multisig(vec![ZPUB.into()], 1).unwrap_err();
        assert!(matches!(e, MobileSignerError::InvalidXpub { index: 0, .. }));
        assert!(
            e.to_string()
                .starts_with("key at index 0 is not a valid xpub or tpub: ")
        );
        assert!(!e.to_string().contains(ZPUB));
    }

    #[test]
    fn mixed_networks_rejected() {
        let e =
            compute_vault_address_multisig(strings(&[TPUB[0], TPUB[1], XPUB[2]]), 2).unwrap_err();
        assert!(matches!(e, MobileSignerError::NetworkMismatch { index: 2 }));
        assert_eq!(
            e.to_string(),
            "key at index 2 is for a different network than key at index 0 (xpub is mainnet, tpub is testnet)"
        );
    }

    #[test]
    fn repeated_key_rejected() {
        let e =
            compute_vault_address_multisig(strings(&[TPUB[0], TPUB[1], TPUB[0]]), 2).unwrap_err();
        assert!(matches!(
            e,
            MobileSignerError::DuplicateXpub { first: 0, index: 2 }
        ));
        assert_eq!(
            e.to_string(),
            "key at index 2 is the same as key at index 0"
        );
    }

    #[test]
    fn repeated_public_key_under_new_chain_code_rejected() {
        let original = Xpub::from_str(TPUB[0]).unwrap();
        let disguised = Xpub {
            chain_code: ChainCode::from([7u8; 32]),
            ..original
        }
        .to_string();
        let e = compute_vault_address_multisig(vec![TPUB[0].into(), TPUB[1].into(), disguised], 2)
            .unwrap_err();
        assert!(matches!(
            e,
            MobileSignerError::DuplicateXpub { first: 0, index: 2 }
        ));
    }

    #[test]
    fn key_too_deep_to_derive_is_an_error_not_a_panic() {
        let at_depth = |depth: u8| -> Vec<String> {
            TPUB.iter()
                .map(|text| {
                    let key = Xpub::from_str(text).unwrap();
                    Xpub { depth, ..key }.to_string()
                })
                .collect()
        };
        compute_vault_address_multisig(at_depth(253), 2).expect("depth 253 still derives");
        for depth in [254, 255] {
            let e = compute_vault_address_multisig(at_depth(depth), 2).unwrap_err();
            assert!(matches!(e, MobileSignerError::InvalidXpub { index: 0, .. }));
            assert_eq!(
                e.to_string(),
                format!(
                    "key at index 0 is too deep to derive addresses from: depth {depth}, the maximum is 253"
                )
            );
        }
    }

    #[test]
    fn oversized_text_is_rejected_by_length() {
        let giant = "z".repeat(100_000);
        let e = compute_vault_address_multisig(vec![TPUB[0].into(), giant], 1).unwrap_err();
        assert!(matches!(e, MobileSignerError::InvalidXpub { index: 1, .. }));
        assert_eq!(
            e.to_string(),
            "key at index 1 is not a valid xpub or tpub: 100000 characters, longer than any extended key"
        );
        let wide = compute_vault_address_multisig(vec!["é".repeat(121)], 1).unwrap_err();
        assert!(
            wide.to_string()
                .ends_with("121 characters, longer than any extended key"),
            "the cap counts characters, not bytes: {wide}"
        );
    }

    // BIP-39 English vector, 32 zero bytes, passphrase "TREZOR". The master xprv is
    // that vector's published key. Account xpubs are derived from it here with
    // bitcoin's CKDpriv, not by calling the functions under test.
    const ABANDON_ART: &str = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art";
    const ABANDON_MASTER_XPRV: &str = "xprv9s21ZrQH143K32qBagUJAMU2LsHg3ka7jqMcV98Y7gVeVyNStwYS3U7yVVoDZ4btbRNf4h6ibWpY22iRmXq35qgLs79f312g2kj5539ebPM";

    fn phrase_words(phrase: &str) -> Vec<String> {
        phrase.split_whitespace().map(str::to_owned).collect()
    }

    fn xpub_at(xprv: &str, path: &str) -> (String, String) {
        use bitcoin::bip32::{DerivationPath, Xpriv};
        let mut master = Xpriv::from_str(xprv).unwrap();
        let secp = Secp256k1::signing_only();
        let fingerprint = master.fingerprint(&secp).to_string();
        let account = master
            .derive_priv(&secp, &DerivationPath::from_str(path).unwrap())
            .unwrap();
        let xpub = Xpub::from_priv(&secp, &account).to_string();
        master.private_key.non_secure_erase();
        (fingerprint, xpub)
    }

    #[test]
    fn restore_matches_the_bip39_trezor_vector() {
        let words = phrase_words(ABANDON_ART);
        let (fingerprint, xpub) = xpub_at(ABANDON_MASTER_XPRV, "m/48'/0'/0'/2'");
        let key =
            restore_software_multisig_key(words.clone(), "TREZOR".into(), KeyNetwork::Main, 0)
                .unwrap();
        assert_eq!(key.mnemonic_words, words);
        assert_eq!(key.master_fingerprint, fingerprint);
        assert_eq!(key.master_fingerprint.len(), 8);
        assert!(
            key.master_fingerprint
                .chars()
                .all(|c| c.is_ascii_hexdigit() && !c.is_ascii_uppercase())
        );
        assert_eq!(key.xpub, xpub);
        assert_eq!(key.derivation_path, "m/48'/0'/0'/2'");

        let account_one =
            restore_software_multisig_key(words, "TREZOR".into(), KeyNetwork::Main, 1).unwrap();
        let (_, xpub_one) = xpub_at(ABANDON_MASTER_XPRV, "m/48'/0'/1'/2'");
        assert_eq!(account_one.derivation_path, "m/48'/0'/1'/2'");
        assert_eq!(account_one.xpub, xpub_one);
        assert_ne!(account_one.xpub, xpub);

        let test = restore_software_multisig_key(
            vec![ABANDON_ART.into()],
            "TREZOR".into(),
            KeyNetwork::Test,
            0,
        )
        .unwrap();
        assert_eq!(test.mnemonic_words.len(), 24);
        assert_eq!(
            test.mnemonic_words.first().map(String::as_str),
            Some("abandon")
        );
        assert_eq!(test.mnemonic_words.last().map(String::as_str), Some("art"));
        assert_eq!(test.master_fingerprint, fingerprint);
        assert_eq!(test.derivation_path, "m/48'/1'/0'/2'");
        assert!(test.xpub.starts_with("tpub"), "{}", test.xpub);
        assert_ne!(test.xpub, xpub);
    }

    #[test]
    fn generate_and_restore_agree_on_fingerprint_and_xpub() {
        let generated =
            generate_software_multisig_key("mebit".into(), KeyNetwork::Test, 3).unwrap();
        assert_eq!(generated.mnemonic_words.len(), 24);
        assert_eq!(generated.derivation_path, "m/48'/1'/3'/2'");
        assert!(generated.xpub.starts_with("tpub"), "{}", generated.xpub);
        assert_eq!(generated.master_fingerprint.len(), 8);
        let restored = restore_software_multisig_key(
            generated.mnemonic_words.clone(),
            "mebit".into(),
            KeyNetwork::Test,
            3,
        )
        .unwrap();
        assert_eq!(restored.master_fingerprint, generated.master_fingerprint);
        assert_eq!(restored.xpub, generated.xpub);
        assert_eq!(restored.derivation_path, generated.derivation_path);
        assert_eq!(restored.mnemonic_words, generated.mnemonic_words);

        let main = generate_software_multisig_key(String::new(), KeyNetwork::Main, 0).unwrap();
        assert_eq!(main.derivation_path, "m/48'/0'/0'/2'");
        assert!(main.xpub.starts_with("xpub"), "{}", main.xpub);
    }

    #[test]
    fn a_bad_mnemonic_names_the_problem_and_not_the_word() {
        let mut words = vec!["abandon".to_string(); 12];
        words[3] = "notaword".into();
        let err =
            restore_software_multisig_key(words, String::new(), KeyNetwork::Test, 0).unwrap_err();
        assert!(matches!(err, MobileSignerError::InvalidMnemonic(_)));
        let text = err.to_string();
        assert!(text.contains("unknown word"), "{text}");
        assert!(!text.contains("notaword"), "{text}");

        let range = restore_software_multisig_key(
            phrase_words(ABANDON_ART),
            "TREZOR".into(),
            KeyNetwork::Main,
            1 << 31,
        )
        .unwrap_err();
        assert!(matches!(range, MobileSignerError::VaultCore(_)));
        assert!(range.to_string().contains("BIP-32"), "{range}");
    }

    #[test]
    fn suggestions_are_the_first_matches_in_list_order() {
        assert_eq!(
            bip39_suggest("ab".into(), 8),
            [
                "abandon", "ability", "able", "about", "above", "absent", "absorb", "abstract"
            ]
        );
        assert_eq!(bip39_suggest("  ZOO ".into(), 8), ["zoo"]);
        assert!(bip39_suggest(String::new(), 8).is_empty());
        assert!(bip39_suggest("qqq".into(), 8).is_empty());
    }

    #[test]
    fn check_mnemonic_names_the_problem() {
        assert_eq!(
            check_mnemonic(phrase_words(ABANDON_ART)).unwrap(),
            MnemonicCheck::Valid
        );

        let mut unknown = phrase_words(ABANDON_ART);
        unknown[3] = "abandonn".into();
        assert_eq!(
            check_mnemonic(unknown).unwrap(),
            MnemonicCheck::UnknownWord { index: 3 }
        );

        // 24 x "abandon": every word is in the list, the checksum is wrong.
        assert_eq!(
            check_mnemonic(vec!["abandon".to_string(); 24]).unwrap(),
            MnemonicCheck::InvalidChecksum
        );
        assert_eq!(
            check_mnemonic(vec!["abandon".to_string(); 13]).unwrap(),
            MnemonicCheck::BadWordCount { count: 13 }
        );
    }

    #[test]
    fn last_word_candidates_complete_the_mnemonic() {
        let first23 = vec!["abandon".to_string(); 23];
        let candidates = bip39_last_word_candidates(first23.clone());
        assert_eq!(
            candidates.len(),
            8,
            "3 entropy bits leave 8 words: {candidates:?}"
        );
        assert!(candidates.contains(&"art".to_string()));
        let mut sorted = candidates.clone();
        sorted.sort();
        assert_eq!(candidates, sorted, "list order is alphabetical");
        for word in &candidates {
            let mut all = first23.clone();
            all.push(word.clone());
            assert_eq!(check_mnemonic(all.clone()).unwrap(), MnemonicCheck::Valid);
            restore_software_multisig_key(all, String::new(), KeyNetwork::Test, 0).unwrap();
        }

        let mut unknown = first23;
        unknown[0] = "abandonn".into();
        assert!(bip39_last_word_candidates(unknown).is_empty());
        assert!(bip39_last_word_candidates(vec!["abandon".to_string(); 22]).is_empty());
    }

    #[test]
    fn committed_android_bindings_match_the_exports() {
        let kotlin = include_str!(
            "../../app/mobileSignerFfi/src@android/com/mebit/ffi/signer/mobile_signer_ffi.android.kt"
        );
        let checksums = [
            (
                "func_compute_vault_address",
                uniffi_mobile_signer_ffi_checksum_func_compute_vault_address(),
            ),
            (
                "func_compute_vault_address_multisig",
                uniffi_mobile_signer_ffi_checksum_func_compute_vault_address_multisig(),
            ),
            (
                "func_derive_borrower_pubkey",
                uniffi_mobile_signer_ffi_checksum_func_derive_borrower_pubkey(),
            ),
            (
                "func_sign_psbt",
                uniffi_mobile_signer_ffi_checksum_func_sign_psbt(),
            ),
            (
                "func_generate_software_multisig_key",
                uniffi_mobile_signer_ffi_checksum_func_generate_software_multisig_key(),
            ),
            (
                "func_restore_software_multisig_key",
                uniffi_mobile_signer_ffi_checksum_func_restore_software_multisig_key(),
            ),
            (
                "func_check_mnemonic",
                uniffi_mobile_signer_ffi_checksum_func_check_mnemonic(),
            ),
            (
                "func_bip39_suggest",
                uniffi_mobile_signer_ffi_checksum_func_bip39_suggest(),
            ),
            (
                "func_bip39_last_word_candidates",
                uniffi_mobile_signer_ffi_checksum_func_bip39_last_word_candidates(),
            ),
            (
                "func_hot_receive_address",
                hot_wallet::uniffi_mobile_signer_ffi_checksum_func_hot_receive_address(),
            ),
            (
                "func_hot_account_path",
                hot_wallet::uniffi_mobile_signer_ffi_checksum_func_hot_account_path(),
            ),
            (
                "func_hot_typical_vsize",
                hot_wallet::uniffi_mobile_signer_ffi_checksum_func_hot_typical_vsize(),
            ),
            (
                "func_hot_address_type_enabled",
                hot_wallet::uniffi_mobile_signer_ffi_checksum_func_hot_address_type_enabled(),
            ),
            (
                "func_hot_prepare_send_at_rate",
                hot_wallet::uniffi_mobile_signer_ffi_checksum_func_hot_prepare_send_at_rate(),
            ),
            (
                "func_hot_prepare_send_all",
                hot_wallet::uniffi_mobile_signer_ffi_checksum_func_hot_prepare_send_all(),
            ),
            (
                "func_hot_quote_send",
                hot_wallet::uniffi_mobile_signer_ffi_checksum_func_hot_quote_send(),
            ),
            (
                "func_hot_prepare_fee_bump",
                hot_wallet::uniffi_mobile_signer_ffi_checksum_func_hot_prepare_fee_bump(),
            ),
            (
                "func_hot_prepare_cancel",
                hot_wallet::uniffi_mobile_signer_ffi_checksum_func_hot_prepare_cancel(),
            ),
            (
                "func_hot_broadcast",
                hot_wallet::uniffi_mobile_signer_ffi_checksum_func_hot_broadcast(),
            ),
            (
                "func_hot_wallet_history",
                hot_wallet::uniffi_mobile_signer_ffi_checksum_func_hot_wallet_history(),
            ),
            (
                "func_hot_tx_detail",
                hot_wallet::uniffi_mobile_signer_ffi_checksum_func_hot_tx_detail(),
            ),
            (
                "func_hot_wallet_snapshot",
                hot_wallet::uniffi_mobile_signer_ffi_checksum_func_hot_wallet_snapshot(),
            ),
            (
                "func_hot_fee_tiers",
                hot_wallet::uniffi_mobile_signer_ffi_checksum_func_hot_fee_tiers(),
            ),
            (
                "func_hot_use_state_dir",
                hot_wallet::uniffi_mobile_signer_ffi_checksum_func_hot_use_state_dir(),
            ),
            (
                "func_hot_forget_scan_state",
                hot_wallet::uniffi_mobile_signer_ffi_checksum_func_hot_forget_scan_state(),
            ),
            (
                "func_is_valid_address",
                hot_wallet::uniffi_mobile_signer_ffi_checksum_func_is_valid_address(),
            ),
            (
                "func_wallet_snapshot",
                hot_wallet::uniffi_mobile_signer_ffi_checksum_func_wallet_snapshot(),
            ),
            (
                "func_wallet_history",
                hot_wallet::uniffi_mobile_signer_ffi_checksum_func_wallet_history(),
            ),
            (
                "func_wallet_tx_detail",
                hot_wallet::uniffi_mobile_signer_ffi_checksum_func_wallet_tx_detail(),
            ),
            (
                "func_wallet_balance",
                hot_wallet::uniffi_mobile_signer_ffi_checksum_func_wallet_balance(),
            ),
            (
                "func_wallet_describe",
                script::uniffi_mobile_signer_ffi_checksum_func_wallet_describe(),
            ),
            (
                "func_wallet_draft",
                spend::uniffi_mobile_signer_ffi_checksum_func_wallet_draft(),
            ),
            (
                "func_wallet_review",
                spend::uniffi_mobile_signer_ffi_checksum_func_wallet_review(),
            ),
            (
                "func_wallet_finish",
                spend::uniffi_mobile_signer_ffi_checksum_func_wallet_finish(),
            ),
            (
                "func_wallet_sign_with_words",
                spend::uniffi_mobile_signer_ffi_checksum_func_wallet_sign_with_words(),
            ),
            (
                "func_scan_wallets",
                ble::uniffi_mobile_signer_ffi_checksum_func_scan_wallets(),
            ),
            (
                "func_live_wallet_links",
                ble::uniffi_mobile_signer_ffi_checksum_func_live_wallet_links(),
            ),
            (
                "constructor_walletlink_new",
                ble::uniffi_mobile_signer_ffi_checksum_constructor_walletlink_new(),
            ),
            (
                "method_walletlink_connect",
                ble::uniffi_mobile_signer_ffi_checksum_method_walletlink_connect(),
            ),
            (
                "method_walletlink_info",
                ble::uniffi_mobile_signer_ffi_checksum_method_walletlink_info(),
            ),
            (
                "method_walletlink_unlock",
                ble::uniffi_mobile_signer_ffi_checksum_method_walletlink_unlock(),
            ),
            (
                "method_walletlink_account_xpub",
                ble::uniffi_mobile_signer_ffi_checksum_method_walletlink_account_xpub(),
            ),
            (
                "method_walletlink_sign_psbt",
                ble::uniffi_mobile_signer_ffi_checksum_method_walletlink_sign_psbt(),
            ),
            (
                "method_walletlink_sign_wallet_psbt",
                ble::uniffi_mobile_signer_ffi_checksum_method_walletlink_sign_wallet_psbt(),
            ),
            (
                "method_walletlink_disconnect",
                ble::uniffi_mobile_signer_ffi_checksum_method_walletlink_disconnect(),
            ),
            (
                "method_pairingcodes_pairing_code",
                ble::uniffi_mobile_signer_ffi_checksum_method_pairingcodes_pairing_code(),
            ),
            (
                "func_fetch_btc_thb",
                price::uniffi_mobile_signer_ffi_checksum_func_fetch_btc_thb(),
            ),
        ];
        for (name, checksum) in checksums {
            let check =
                format!("uniffi_mobile_signer_ffi_checksum_{name}() != {checksum}.toShort()");
            assert!(
                kotlin.contains(&check),
                "committed Kotlin is stale for {name}: run scripts/ffi.sh bindings, or build on macOS"
            );
        }
        let in_kotlin = kotlin
            .lines()
            .filter(|line| line.contains("_checksum_") && line.contains(" != "))
            .count();
        assert_eq!(
            in_kotlin,
            checksums.len(),
            "the committed Kotlin lists a different number of checksums than this test: if an export was added or removed, update the list, then run scripts/ffi.sh bindings"
        );
    }
}
