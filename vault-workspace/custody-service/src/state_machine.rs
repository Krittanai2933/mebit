//! created -> awaiting_borrower_sig -> awaiting_lender_sig -> broadcast -> confirmed
//! Transitions must be idempotent — re-submitting a PSBT for the same
//! signing request must not create a duplicate on-chain spend. See
//! docs/00-capstone-brief.md §6.

use serde::{Deserialize, Serialize};
use vault_core::policy::SigningReason;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SigningRequestState {
    Created,
    AwaitingBorrowerSig,
    AwaitingLenderSig,
    Broadcast,
    Confirmed,
}

impl SigningRequestState {
    /// Advances one step. Idempotent at the terminal state — advancing an
    /// already-Confirmed request is a no-op, not an error, so a retried API
    /// call or a resubmitted PSBT can never trigger a second broadcast.
    pub fn advance(self) -> Self {
        use SigningRequestState::*;
        match self {
            Created => AwaitingBorrowerSig,
            AwaitingBorrowerSig => AwaitingLenderSig,
            AwaitingLenderSig => Broadcast,
            Broadcast => Confirmed,
            Confirmed => Confirmed,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SigningRequest {
    pub id: u64,
    pub loan_id: String,
    pub reason: SigningReason,
    pub state: SigningRequestState,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn advances_through_full_sequence() {
        let mut s = SigningRequestState::Created;
        for expected in [
            SigningRequestState::AwaitingBorrowerSig,
            SigningRequestState::AwaitingLenderSig,
            SigningRequestState::Broadcast,
            SigningRequestState::Confirmed,
        ] {
            s = s.advance();
            assert_eq!(s, expected);
        }
    }

    #[test]
    fn advancing_confirmed_is_idempotent() {
        let s = SigningRequestState::Confirmed;
        assert_eq!(s.advance(), SigningRequestState::Confirmed);
        assert_eq!(s.advance().advance(), SigningRequestState::Confirmed);
    }
}
