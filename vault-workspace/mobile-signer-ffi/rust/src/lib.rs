//! UniFFI-exported bindings over `vault-core` for the borrower's mobile app
//! (Kotlin/Swift). See `docs/00-capstone-brief.md` §3.3 and
//! `docs/design-notes.md` for the screens this backs.
//!
//! Deliberately **not** using the `uniffi` crate/macros yet — CLAUDE.md's
//! convention for this project is "don't build UniFFI/mobile app scaffolding
//! speculatively ahead of vault-core's interface stabilizing." These are
//! plain Rust functions with FFI-friendly signatures (primitive types in and
//! out, `Result<_, String>` for errors) so the shape is already right;
//! annotate them with `#[uniffi::export]` and uncomment the `uniffi`
//! dependency in `Cargo.toml` once `vault-core`'s real interface lands.

use vault_core::descriptor::{self, PartyPubkeys};
use vault_core::derivation;
use vault_core::psbt::UnsignedPsbt;

/// Derives the borrower's own child pubkey for a given loan index. Backs the
/// "onboarding" and "open a new loan" screens.
pub fn derive_borrower_pubkey(account_xpub: &str, index: u32) -> String {
    derivation::derive_child_pubkey(account_xpub, index)
}

/// Recomputes the vault address from the three parties' pubkeys, so the
/// borrower can independently verify the vault custody-service reports
/// actually matches what the three keys imply — the "verify the vault
/// on-chain" responsibility from docs/00-capstone-brief.md §3.3.
pub fn compute_vault_address(borrower_pubkey: &str, platform_pubkey: &str, lender_pubkey: &str) -> String {
    let parties = PartyPubkeys {
        borrower: borrower_pubkey.to_string(),
        platform: platform_pubkey.to_string(),
        lender: lender_pubkey.to_string(),
    };
    descriptor::build_descriptor(&parties).address
}

/// Signs a PSBT (JSON-encoded `vault_core::psbt::UnsignedPsbt`) received
/// from custody-service. Backs "sign a collateral return."
///
/// TODO: replace with a real signature over the real PSBT once vault-core
/// exposes one — this mock exists so the app's sign-and-submit flow can be
/// built and tested end-to-end before that lands.
pub fn sign_psbt(psbt_json: &str, borrower_pubkey: &str) -> Result<String, String> {
    let psbt: UnsignedPsbt = serde_json::from_str(psbt_json).map_err(|e| format!("invalid PSBT json: {e}"))?;
    Ok(format!("MOCK_SIG[{}:{}:{}]", psbt.loan_id, psbt.outputs.len(), borrower_pubkey))
}

#[cfg(test)]
mod tests {
    use super::*;

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
}
