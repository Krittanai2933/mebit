//! What iOS and Android do differently under btleplug, in one place.
//!
//! | | iOS | Android |
//! |---|---|---|
//! | init | none | `JNI_OnLoad` runs `btleplug::platform::init`, kept in `INIT` |
//! | permission refused | `Error::PermissionDenied` from `adapters()` | `RuntimeError("Need android.permission...")` from `start_scan`; `PermissionDenied` only from `connectGatt` |
//! | Bluetooth off | an empty scan (`NotFound`, whose text names permission; read from the source, not run) | `NoAdapterAvailable` when the adapter is fully off (read from the Android sources, not run); an empty scan and `NotFound` when it stays in `BLE_ON` (measured on a Galaxy S25 FE) |
//! | adapters | one per `adapters()` call | one for the process, which remembers every device it saw |
//! | `adapter_state()` | not used | not used: always `Unknown` |

use super::BleError;

pub(super) fn ble_ready() -> Result<(), BleError> {
    #[cfg(target_os = "android")]
    return init_outcome(android::INIT.get());
    #[cfg(not(target_os = "android"))]
    Ok(())
}

/// Android has one btleplug adapter for the whole process, and it keeps every device it has
/// seen, so a scan would list wallets that are gone and a connect could pick one. Everywhere
/// else each call makes an adapter of its own. On one phone a Jade unplugged between two scans
/// was not listed by the second. No run left this call out, so it is not proven to be why.
#[cfg(target_os = "android")]
pub(super) async fn forget_seen_devices() -> Result<(), BleError> {
    use btleplug::api::{Central, Manager as _};

    let manager = btleplug::platform::Manager::new()
        .await
        .map_err(from_btleplug)?;
    for adapter in manager.adapters().await.map_err(from_btleplug)? {
        adapter.clear_peripherals().await.map_err(from_btleplug)?;
    }
    Ok(())
}

#[cfg(not(target_os = "android"))]
pub(super) async fn forget_seen_devices() -> Result<(), BleError> {
    Ok(())
}

pub(super) fn no_backend() -> BleError {
    BleError::Unsupported("this build has no Bluetooth backend".into())
}

#[cfg(any(target_os = "android", test))]
fn init_outcome(init: Option<&Result<(), String>>) -> Result<(), BleError> {
    match init {
        Some(Ok(())) => Ok(()),
        Some(Err(error)) => Err(BleError::Unsupported(format!(
            "Bluetooth failed to start: {error}"
        ))),
        None => Err(BleError::Unsupported(
            "Bluetooth is not started: call System.loadLibrary(\"mobile_signer_ffi\") before any Bluetooth call"
                .into(),
        )),
    }
}

/// How Android's scanner says a runtime permission is missing. btleplug passes the
/// `SecurityException` text on as a `RuntimeError`.
#[cfg(any(target_os = "macos", target_os = "ios", target_os = "android"))]
const ANDROID_PERMISSION_REFUSAL: &str = "Need android.permission.";

#[cfg(any(target_os = "macos", target_os = "ios", target_os = "android"))]
pub(super) fn from_btleplug(error: btleplug::Error) -> BleError {
    use btleplug::Error as E;
    let text = error.to_string();
    match error {
        E::PermissionDenied => BleError::PermissionDenied(text),
        E::RuntimeError(message) if message.starts_with(ANDROID_PERMISSION_REFUSAL) => {
            BleError::PermissionDenied(text)
        }
        E::NoAdapterAvailable => BleError::BluetoothOff(text),
        E::NotConnected => BleError::Disconnected(text),
        E::TimedOut(_) => BleError::Timeout(text),
        E::DeviceNotFound => BleError::NotFound(text),
        E::UnexpectedCallback
        | E::UnexpectedCharacteristic
        | E::NoSuchCharacteristic
        | E::NotSupported(_)
        | E::Uuid(_)
        | E::InvalidBDAddr(_)
        | E::RuntimeError(_)
        | E::Other(_) => BleError::Link(text),
    }
}

#[cfg(target_os = "android")]
mod android {
    use std::ffi::c_void;
    use std::sync::OnceLock;

    use jni::JavaVM;
    use jni::sys::{JNI_VERSION_1_6, jint};

    /// btleplug's init outcome. Empty means `JNI_OnLoad` never ran: JNA's own load of this
    /// library does not call it, only `System.loadLibrary` does.
    pub(super) static INIT: OnceLock<Result<(), String>> = OnceLock::new();

    /// Runs on the Java thread that called `System.loadLibrary`, whose class loader can see
    /// btleplug's vendored Java classes. A native thread's could not.
    #[unsafe(no_mangle)]
    pub extern "system" fn JNI_OnLoad(vm: *mut jni::sys::JavaVM, _: *mut c_void) -> jint {
        // SAFETY: ART passes its own valid JavaVM pointer.
        let vm = unsafe { JavaVM::from_raw(vm) };
        let outcome = vm
            .attach_current_thread(|env| -> Result<Result<(), String>, jni::errors::Error> {
                Ok(btleplug::platform::init(env).map_err(|error| error.to_string()))
            })
            .unwrap_or_else(|error| Err(format!("attach: {error}")));
        let _ = INIT.set(outcome);
        JNI_VERSION_1_6 as jint
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn missing_init_is_unsupported_not_a_panic() {
        assert_eq!(init_outcome(Some(&Ok(()))), Ok(()));
        assert!(matches!(
            init_outcome(None),
            Err(BleError::Unsupported(text)) if text.contains("System.loadLibrary")
        ));
        assert!(matches!(
            init_outcome(Some(&Err("no Adapter class".into()))),
            Err(BleError::Unsupported(_))
        ));
    }

    #[cfg(any(target_os = "macos", target_os = "ios", target_os = "android"))]
    #[test]
    fn os_permission_refusals_are_permission_denied() {
        // Verbatim from the API 36 emulator with BLUETOOTH_SCAN denied.
        let android = btleplug::Error::RuntimeError(
            "Need android.permission.BLUETOOTH_SCAN permission for android.content.AttributionSource@21daa099: ScanController.registerScanner()"
                .into(),
        );
        assert!(matches!(
            from_btleplug(android),
            BleError::PermissionDenied(_)
        ));
        assert!(matches!(
            from_btleplug(btleplug::Error::PermissionDenied),
            BleError::PermissionDenied(_)
        ));
        assert!(matches!(
            from_btleplug(btleplug::Error::RuntimeError("GATT 133".into())),
            BleError::Link(_)
        ));
        assert!(matches!(
            from_btleplug(btleplug::Error::NoAdapterAvailable),
            BleError::BluetoothOff(_)
        ));
    }
}
