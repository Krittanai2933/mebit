use thiserror::Error;

#[derive(Debug, Error)]
pub enum VaultCoreError {
    #[error("entropy generation failed: {0}")]
    Entropy(#[from] getrandom::Error),

    #[error("mnemonic error: {0}")]
    Mnemonic(#[from] bip39::Error),

    /// Every `bitcoin::bip32` failure, not just derivation — the same type also
    /// carries base58/hex parse errors, unknown version bytes, and wrong key
    /// lengths, which is why this isn't called `Derivation`.
    #[error("BIP-32 key error: {0}")]
    Bip32(#[from] bitcoin::bip32::Error),

    // Wrapped explicitly via `.map_err(VaultCoreError::Policy)`, not `#[from]`,
    // so the conversion stays visible at the security boundary.
    //
    // TODO(No.2.5 follow-up): `{0:?}` renders a Rust identifier (`WrongAmount`),
    // which is not something a UI can show. The fix is to derive
    // `thiserror::Error` on `PolicyViolation` in lib.rs with one message per
    // variant, then switch this to `{0}`. That touches the policy module, so it
    // needs both vault-core owners' review (CLAUDE.md).
    #[error("policy check rejected the PSBT: {0:?}")]
    Policy(crate::policy::PolicyViolation),
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::policy::{LoanContext, PolicyEngine, PolicyViolation, SigningReason};
    use crate::psbt::{PsbtOutput, UnsignedPsbt};

    /// The whole point of wrapping rather than stringifying: callers can still
    /// pattern-match the exact violation back out.
    #[test]
    fn policy_violation_survives_the_wrap() {
        let psbt = UnsignedPsbt {
            loan_id: "loan-1".into(),
            inputs: vec!["utxo:0".into()],
            outputs: vec![PsbtOutput {
                address: "tb1qborrower".into(),
                amount_sats: 999_999,
            }],
        };
        let context = LoanContext {
            loan_id: "loan-1".into(),
            reason: SigningReason::CollateralReturn,
            expected_output_address: "tb1qborrower".into(),
            expected_amount_sats: 100_000,
        };

        let err = PolicyEngine::authorize(&psbt, &context)
            .map_err(VaultCoreError::Policy)
            .unwrap_err();

        assert!(matches!(
            err,
            VaultCoreError::Policy(PolicyViolation::WrongAmount)
        ));
    }
}
