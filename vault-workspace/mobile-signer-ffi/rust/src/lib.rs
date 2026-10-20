//! UniFFI exports over `vault-core` for the Kotlin Multiplatform app in `../app/mobileSignerFfi`.
//! Every exported signature and every `///` doc comment on an export feeds a UniFFI checksum.
//! `MobileSignerError` does not, and Kotlin reads its variants by position, so after changing it
//! run `scripts/ffi.sh build`. Only `scripts/ffi.sh check` sees that drift.

uniffi::setup_scaffolding!();

use std::str::FromStr;

use bitcoin::{Network, NetworkKind, bip32::Xpub};
use vault_core::derivation;
use vault_core::descriptor::{self, PartyPubkeys};
use vault_core::error::VaultCoreError;
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
}

impl From<VaultCoreError> for MobileSignerError {
    fn from(e: VaultCoreError) -> Self {
        let text = e.to_string();
        match e {
            VaultCoreError::Threshold(_) => Self::InvalidThreshold(text),
            _ => Self::VaultCore(text),
        }
    }
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
    // `python3 scripts/address_oracle.py` re-derives the two addresses from these constants.
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

    #[test]
    fn committed_android_bindings_match_the_exports() {
        let kotlin = include_str!(
            "../../app/mobileSignerFfi/src@android/com/mebit/ffi/signer/mobile_signer_ffi.android.kt"
        );
        let checksums = [
            (
                "compute_vault_address",
                uniffi_mobile_signer_ffi_checksum_func_compute_vault_address(),
            ),
            (
                "compute_vault_address_multisig",
                uniffi_mobile_signer_ffi_checksum_func_compute_vault_address_multisig(),
            ),
            (
                "derive_borrower_pubkey",
                uniffi_mobile_signer_ffi_checksum_func_derive_borrower_pubkey(),
            ),
            (
                "sign_psbt",
                uniffi_mobile_signer_ffi_checksum_func_sign_psbt(),
            ),
        ];
        for (name, checksum) in checksums {
            let check =
                format!("uniffi_mobile_signer_ffi_checksum_func_{name}() != {checksum}.toShort()");
            assert!(
                kotlin.contains(&check),
                "committed Kotlin is stale for {name}: run scripts/ffi.sh bindings, or build on macOS"
            );
        }
        let in_kotlin = kotlin
            .lines()
            .filter(|line| line.contains("checksum_func_") && line.contains(" != "))
            .count();
        assert_eq!(
            in_kotlin,
            checksums.len(),
            "the committed Kotlin lists a different number of checksums than this test: if an export was added or removed, update the list, then run scripts/ffi.sh bindings"
        );
    }
}
