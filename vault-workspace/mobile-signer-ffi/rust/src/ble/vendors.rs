use std::sync::Arc;
use std::time::Duration;

use bitcoin::Network;
use bitcoin::bip32::DerivationPath;
use jade_ble::{JadeBle, JadeBleError};
use trezor_ble::{ConnectOptions, ErrorKind, TrezorBle, TrezorBleError};
use vault_core::error::VaultCoreError;
use vault_core::hw::jade::{self as jade, JadeError};
use vault_core::hw::trezor::XpubOptions;

use super::{
    BleError, FoundWallet, HwAccountKey, JadeState, PairingCodes, Running, Wallet, WalletDetails,
    WalletInfo, WalletKind, derivation_path_string, parse_derivation_path, platform,
};

// shortcut: testnet only. Connect pins the Jade and the Safe 7 to testnet4, the same
// network as `KeyNetwork::Test`. A mainnet wallet needs a network argument on connect.
const NETWORK: Network = Network::Testnet4;
// shortcut: one window for every scan, untuned on phones (Android scans in its low-power mode).
// Tune it on hardware. Make it a parameter only if a screen needs to choose.
const SCAN_WINDOW: Duration = Duration::from_secs(4);
/// The Safe 7 asks "Allow mebit on {HOST_NAME} to pair?" and lists the host by this name, so each
/// platform has its own and a user can tell two phones apart (at most 32 bytes).
const APP_NAME: &str = "mebit";
const HOST_NAME: &str = if cfg!(target_os = "ios") {
    "mebit iPhone"
} else if cfg!(target_os = "android") {
    "mebit Android"
} else {
    "mebit mobile"
};

pub(super) async fn scan(kind: WalletKind) -> Result<Vec<FoundWallet>, BleError> {
    Ok(match kind {
        WalletKind::Jade => JadeBle::scan(SCAN_WINDOW)
            .await?
            .into_iter()
            .map(|jade| FoundWallet {
                kind,
                id: jade.id,
                name: jade.name,
            })
            .collect(),
        WalletKind::TrezorSafe7 => TrezorBle::scan(SCAN_WINDOW)
            .await?
            .into_iter()
            .map(|device| FoundWallet {
                kind,
                name: device.name.unwrap_or_else(|| device.id.clone()),
                id: device.id,
            })
            .collect(),
    })
}

/// Each crate scans again, on a manager of its own.
// shortcut: every scan and connect opens a new CoreBluetooth manager so it lists only what it saw,
// and btleplug never frees one (a parked thread each). Share one adapter with a per-scan filter
// once a session scans often, and prove it on hardware first.
pub(super) async fn connect(
    wallet: &FoundWallet,
    codes: Arc<dyn PairingCodes>,
) -> Result<Running, BleError> {
    // Both crates match by suffix, so an empty selector would pick any wallet in range.
    let selector = match wallet.kind {
        WalletKind::Jade => &wallet.name,
        WalletKind::TrezorSafe7 => &wallet.id,
    };
    if selector.is_empty() {
        return Err(BleError::NotFound(
            "a FoundWallet needs the name or id that a scan returned".into(),
        ));
    }
    Ok(match wallet.kind {
        WalletKind::Jade => {
            Running::spawn(JadeBle::connect(NETWORK, Some(&wallet.name), SCAN_WINDOW).await?)
        }
        WalletKind::TrezorSafe7 => {
            let ask = move || {
                let codes = codes.clone();
                async move { codes.pairing_code().await }
            };
            // `new_credential` is dropped: no secret leaves Rust, so every connect asks for
            // the code (docs/04 item 20).
            Running::spawn(
                TrezorBle::connect(trezor_options(wallet), &ask)
                    .await?
                    .device,
            )
        }
    })
}

fn trezor_options(wallet: &FoundWallet) -> ConnectOptions {
    ConnectOptions {
        network: NETWORK,
        host_name: HOST_NAME.into(),
        app_name: APP_NAME.into(),
        passphrase: Default::default(),
        credential: None,
        device: Some(wallet.id.clone()),
        // The id came from a scan a moment ago, and a Safe 7 changes it when its pairing screen
        // is reopened.
        device_is_hint: true,
        scan: SCAN_WINDOW,
    }
}

impl Wallet for JadeBle {
    async fn info(&mut self) -> Result<WalletInfo, BleError> {
        let info = self.version_info().await?;
        Ok(WalletInfo {
            name: self.name().to_owned(),
            firmware: info.firmware,
            details: WalletDetails::Jade {
                board: info.board,
                state: info.state.into(),
                networks: info.networks,
            },
        })
    }

    async fn unlock(&mut self) -> Result<(), BleError> {
        Ok(JadeBle::unlock(self).await?)
    }

    async fn xpub(&mut self, path: &str) -> Result<HwAccountKey, BleError> {
        let path = parse_derivation_path(path)?;
        let master = JadeBle::xpub(self, &DerivationPath::master()).await?;
        let account = JadeBle::xpub(self, &path).await?;
        Ok(HwAccountKey {
            xpub: account.to_string(),
            master_fingerprint: master.fingerprint().to_string(),
            derivation_path: derivation_path_string(&path),
        })
    }

    async fn disconnect(self) -> Result<(), BleError> {
        Ok(JadeBle::disconnect(self).await?)
    }
}

impl Wallet for TrezorBle {
    async fn info(&mut self) -> Result<WalletInfo, BleError> {
        let identity = self.identity();
        Ok(WalletInfo {
            name: self.name().to_owned(),
            firmware: identity.firmware_version(),
            details: WalletDetails::TrezorSafe7 {
                internal_model: identity.internal_model.clone(),
                device_id: identity.device_id.clone(),
                label: identity.label.clone(),
                mtu: self.mtu(),
            },
        })
    }

    async fn unlock(&mut self) -> Result<(), BleError> {
        Ok(())
    }

    async fn xpub(&mut self, path: &str) -> Result<HwAccountKey, BleError> {
        let path = parse_derivation_path(path)?;
        let account = TrezorBle::xpub(self, &path, XpubOptions::default()).await?;
        Ok(HwAccountKey {
            // `as_shown` can be a SLIP-132 form such as `vpub`. Address export accepts `xpub` and `tpub` only.
            xpub: account.xpub.to_string(),
            master_fingerprint: account.master_fingerprint.to_string(),
            derivation_path: derivation_path_string(&path),
        })
    }

    async fn disconnect(self) -> Result<(), BleError> {
        Ok(TrezorBle::disconnect(self).await?)
    }
}

impl From<jade::JadeState> for JadeState {
    fn from(state: jade::JadeState) -> Self {
        match state {
            jade::JadeState::Uninit => Self::Uninit,
            jade::JadeState::Unsaved => Self::Unsaved,
            jade::JadeState::Locked => Self::Locked,
            jade::JadeState::Ready => Self::Ready,
            jade::JadeState::Temp => Self::Temp,
        }
    }
}

impl From<JadeBleError> for BleError {
    fn from(error: JadeBleError) -> Self {
        let text = error.to_string();
        match error {
            JadeBleError::Ble(ble) => platform::from_btleplug(ble),
            JadeBleError::NoAdapter => Self::BluetoothOff(text),
            JadeBleError::NotFound { .. } => Self::NotFound(text),
            JadeBleError::MultipleFound(_) => Self::Protocol(text),
            JadeBleError::NotAJade(_) => Self::WrongDevice(text),
            JadeBleError::Timeout(_)
            | JadeBleError::DiscoveryTimedOut
            | JadeBleError::FirstWriteTimedOut => Self::Timeout(text),
            JadeBleError::Disconnected | JadeBleError::DisconnectedWhileConnecting => {
                Self::Disconnected(text)
            }
            JadeBleError::BondRemoved => Self::BondRemoved(text),
            JadeBleError::PairingNotConfirmed => Self::PairingFailed(text),
            JadeBleError::Pinserver(_) => Self::Refused(text),
            JadeBleError::Jade(VaultCoreError::Jade(error)) => jade_category(&error)(text),
            JadeBleError::Jade(_) => Self::Protocol(text),
        }
    }
}

fn jade_category(error: &JadeError) -> fn(String) -> BleError {
    use JadeError as E;
    match error {
        E::UserCancelled
        | E::Locked
        | E::NetworkMismatch(_)
        | E::NotInitialised
        | E::UnlockFailed
        | E::Rpc { .. }
        | E::PinserverResponseInvalid(_)
        | E::RequestTooLarge(_)
        | E::UnsupportedSigningPolicy
        | E::InvalidAddressRequest(_)
        | E::SignerMismatch
        | E::RegistrationConflict
        | E::RegistrationMismatch
        | E::RegistrationRequired => BleError::Refused,
        E::Protocol(_)
        | E::OutOfOrder
        | E::NoSignatures
        | E::PsbtModified
        | E::MissingSignature { .. }
        | E::InvalidSignature { .. }
        | E::InvalidPrevout { .. }
        | E::InvalidTaproot { .. }
        | E::AddressMismatch => BleError::Protocol,
    }
}

impl From<TrezorBleError> for BleError {
    fn from(error: TrezorBleError) -> Self {
        match error {
            // Where Android's permission refusal arrives.
            TrezorBleError::Ble(ble) => platform::from_btleplug(ble),
            other => trezor_category(other.kind())(other.to_string()),
        }
    }
}

fn trezor_category(kind: ErrorKind) -> fn(String) -> BleError {
    use ErrorKind as K;
    match kind {
        K::NoBluetoothAdapter => BleError::BluetoothOff,
        K::BluetoothPermissionDenied => BleError::PermissionDenied,
        K::NoDeviceFound => BleError::NotFound,
        K::UnsupportedModel | K::UnsupportedFirmware | K::MissingService => BleError::WrongDevice,
        K::BondRemoved => BleError::BondRemoved,
        K::PairingFailed => BleError::PairingFailed,
        K::PairingCancelled => BleError::PairingCancelled,
        K::DeviceNotReady
        | K::DeviceBusy
        | K::UserCancelled
        | K::DeviceError
        | K::InvalidRequest => BleError::Refused,
        K::Timeout => BleError::Timeout,
        K::Disconnected => BleError::Disconnected,
        K::MissingCharacteristic | K::LinkUnsuitable | K::ConnectionFailed => BleError::Link,
        K::MultipleDevicesFound | K::SignatureCheckFailed | K::ProtocolError => BleError::Protocol,
    }
}

#[cfg(test)]
mod tests {
    use vault_core::hw::trezor::TrezorError;

    use super::*;

    const ANDROID_SCAN_REFUSAL: &str = "Need android.permission.BLUETOOTH_SCAN permission for android.content.AttributionSource@21daa099: ScanController.registerScanner()";

    #[test]
    fn both_vendors_report_the_android_scan_refusal_as_permission_denied() {
        let refusal = || btleplug::Error::RuntimeError(ANDROID_SCAN_REFUSAL.into());
        assert!(matches!(
            BleError::from(JadeBleError::Ble(refusal())),
            BleError::PermissionDenied(_)
        ));
        assert!(matches!(
            BleError::from(TrezorBleError::from(refusal())),
            BleError::PermissionDenied(_)
        ));
    }

    #[test]
    fn a_safe_7_connect_treats_the_scan_id_as_a_hint() {
        let wallet = FoundWallet {
            kind: WalletKind::TrezorSafe7,
            id: "id-from-a-scan".into(),
            name: "Trezor Safe 7 (6T5)".into(),
        };
        let options = trezor_options(&wallet);
        assert_eq!(options.device.as_deref(), Some("id-from-a-scan"));
        assert!(options.device_is_hint);
        assert!(options.credential.is_none());
        assert!(options.host_name.len() <= 32 && options.app_name.len() <= 32);
    }

    #[test]
    fn a_lost_bond_is_bond_removed_for_both_wallets() {
        let lost = || btleplug::Error::RuntimeError("Peer removed pairing information".into());
        assert!(matches!(
            BleError::from(JadeBleError::from(lost())),
            BleError::BondRemoved(text) if text.contains("forget it")
        ));
        assert!(matches!(
            BleError::from(TrezorBleError::from(lost())),
            BleError::BondRemoved(text) if text.contains("forget it")
        ));
    }

    #[test]
    fn an_unconfirmed_pairing_is_pairing_failed_for_both_wallets() {
        let declined = || btleplug::Error::RuntimeError("Encryption is insufficient.".into());
        assert!(matches!(
            BleError::from(JadeBleError::from(declined())),
            BleError::PairingFailed(text) if text.contains("confirm the pairing request")
        ));
        assert!(matches!(
            BleError::from(TrezorBleError::from(declined())),
            BleError::PairingFailed(text) if text.contains("confirm the pairing request")
        ));
    }

    #[test]
    fn a_link_dropped_while_connecting_stays_disconnected_and_hints_at_pairing() {
        assert!(matches!(
            BleError::from(JadeBleError::DisconnectedWhileConnecting),
            BleError::Disconnected(text) if text.contains("pairing request")
        ));
        assert!(matches!(
            BleError::from(TrezorBleError::DisconnectedWhileConnecting),
            BleError::Disconnected(text) if text.contains("pairing request")
        ));
    }

    #[test]
    fn a_declined_jade_pairing_on_ios_keeps_its_kind_and_hints_at_pairing() {
        // A link that dropped under a pending operation, after the connect steps: no pairing hint.
        let dropped = btleplug::Error::RuntimeError("Device disconnected".into());
        assert!(matches!(
            BleError::from(JadeBleError::from(dropped)),
            BleError::Disconnected(text) if !text.contains("pairing")
        ));
        // A stalled service discovery stays a Timeout and says what may be waiting.
        assert!(matches!(
            BleError::from(JadeBleError::DiscoveryTimedOut),
            BleError::Timeout(text)
                if text.contains("discovering services") && text.contains("pairing request")
        ));
        // Neither case claims the pairing failed: nobody knows that here.
        for error in [
            JadeBleError::DiscoveryTimedOut,
            JadeBleError::DisconnectedWhileConnecting,
        ] {
            assert!(!matches!(BleError::from(error), BleError::PairingFailed(_)));
        }
    }

    #[test]
    fn a_stalled_first_jade_write_stays_a_timeout_and_hints_at_pairing() {
        assert!(matches!(
            BleError::from(JadeBleError::FirstWriteTimedOut),
            BleError::Timeout(text)
                if text.contains("writing to the Jade") && text.contains("pairing request")
        ));
    }

    #[test]
    fn jade_errors_by_category() {
        let jade =
            |error: JadeError| BleError::from(JadeBleError::from(VaultCoreError::from(error)));
        assert!(matches!(jade(JadeError::Locked), BleError::Refused(_)));
        assert!(matches!(jade(JadeError::OutOfOrder), BleError::Protocol(_)));
        assert!(matches!(
            BleError::from(JadeBleError::NotFound { seen: 0 }),
            BleError::NotFound(text) if text.contains("Bluetooth permission")
        ));
        assert!(matches!(
            BleError::from(JadeBleError::Timeout("waiting for the Jade")),
            BleError::Timeout(_)
        ));
    }

    #[test]
    fn trezor_errors_keep_the_crate_text_and_name_the_remedy() {
        let rejected = TrezorBleError::from(TrezorError::UserCancelled);
        let text = rejected.to_string();
        assert_eq!(BleError::from(rejected), BleError::Refused(text));
        assert!(matches!(
            BleError::from(TrezorBleError::LinkUnsuitable { mtu: 185 }),
            BleError::Link(_)
        ));
        assert!(matches!(
            BleError::from(TrezorBleError::Abandoned),
            BleError::Protocol(_)
        ));
        assert!(matches!(
            BleError::from(TrezorBleError::PermissionDenied),
            BleError::PermissionDenied(_)
        ));
    }

    #[test]
    fn every_error_kind_has_a_documented_variant() {
        use ErrorKind as K;
        let table: [(ErrorKind, fn(String) -> BleError); 22] = [
            (K::NoBluetoothAdapter, BleError::BluetoothOff),
            (K::BluetoothPermissionDenied, BleError::PermissionDenied),
            (K::NoDeviceFound, BleError::NotFound),
            (K::MultipleDevicesFound, BleError::Protocol),
            (K::UnsupportedModel, BleError::WrongDevice),
            (K::UnsupportedFirmware, BleError::WrongDevice),
            (K::DeviceNotReady, BleError::Refused),
            (K::DeviceBusy, BleError::Refused),
            (K::MissingService, BleError::WrongDevice),
            (K::MissingCharacteristic, BleError::Link),
            (K::LinkUnsuitable, BleError::Link),
            (K::BondRemoved, BleError::BondRemoved),
            (K::ConnectionFailed, BleError::Link),
            (K::Timeout, BleError::Timeout),
            (K::Disconnected, BleError::Disconnected),
            (K::PairingFailed, BleError::PairingFailed),
            (K::PairingCancelled, BleError::PairingCancelled),
            (K::UserCancelled, BleError::Refused),
            (K::DeviceError, BleError::Refused),
            (K::InvalidRequest, BleError::Refused),
            (K::SignatureCheckFailed, BleError::Protocol),
            (K::ProtocolError, BleError::Protocol),
        ];
        for (kind, expected) in table {
            assert_eq!(
                trezor_category(kind)("text".into()),
                expected("text".into()),
                "{kind:?}"
            );
        }
    }

    #[tokio::test]
    async fn an_empty_selector_is_refused_before_any_radio_work() {
        struct NoCodes;

        #[async_trait::async_trait]
        impl PairingCodes for NoCodes {
            async fn pairing_code(&self) -> Option<String> {
                None
            }
        }

        let wallet = |kind, id: &str, name: &str| FoundWallet {
            kind,
            id: id.into(),
            name: name.into(),
        };
        for empty in [
            wallet(WalletKind::Jade, "id", ""),
            wallet(WalletKind::TrezorSafe7, "", "Safe 7"),
        ] {
            let refused = connect(&empty, Arc::new(NoCodes)).await;
            assert!(
                matches!(&refused, Err(BleError::NotFound(text)) if text.contains("FoundWallet needs")),
                "{empty:?}"
            );
        }
    }

    #[test]
    fn only_a_refusal_leaves_the_link_usable() {
        assert!(
            BleError::from(TrezorBleError::from(TrezorError::UserCancelled)).leaves_link_usable()
        );
        assert!(!BleError::from(TrezorBleError::Disconnected).leaves_link_usable());
        assert!(
            !BleError::from(JadeBleError::Timeout("waiting for the Jade")).leaves_link_usable()
        );
    }
}
