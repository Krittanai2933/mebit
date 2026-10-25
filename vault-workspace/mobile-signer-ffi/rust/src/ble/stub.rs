#![cfg_attr(
    any(target_os = "macos", target_os = "ios", target_os = "android"),
    allow(dead_code)
)]

use std::sync::Arc;

use super::{BleError, FoundWallet, PairingCodes, Running, WalletKind, platform};

// shortcut: no Linux backend. Build one if a Linux app appears.
pub(super) async fn scan(_: WalletKind) -> Result<Vec<FoundWallet>, BleError> {
    Err(platform::no_backend())
}

pub(super) async fn connect(
    _: &FoundWallet,
    _: Arc<dyn PairingCodes>,
) -> Result<Running, BleError> {
    Err(platform::no_backend())
}

#[cfg(test)]
mod tests {
    use super::*;

    struct NoCodes;

    #[async_trait::async_trait]
    impl PairingCodes for NoCodes {
        async fn pairing_code(&self) -> Option<String> {
            None
        }
    }

    #[tokio::test]
    async fn the_stub_answers_unsupported() {
        assert!(matches!(
            scan(WalletKind::Jade).await,
            Err(BleError::Unsupported(_))
        ));
        let wallet = FoundWallet {
            kind: WalletKind::TrezorSafe7,
            id: "id".into(),
            name: "Safe 7".into(),
        };
        assert!(matches!(
            connect(&wallet, Arc::new(NoCodes)).await,
            Err(BleError::Unsupported(_))
        ));
    }
}
