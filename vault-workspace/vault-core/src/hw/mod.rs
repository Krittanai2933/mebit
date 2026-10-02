//! Hardware wallet clients, each its device's protocol with no I/O inside;
//! the BLE transports that carry them are separate crates.
//!
//! - Jade (No.4): [`jade::JadeSession`], carried by `jade-ble`.
//! - Trezor Safe 7 (No.5), and only that Trezor model: `trezor::TrezorSession`,
//!   behind the `trezor` feature, carried by `trezor-ble`.
//!
//! [`psbt_check`] is shared: every client checks the PSBT a device returns
//! against the one it asked the device to sign.

pub mod jade;
pub mod psbt_check;
#[cfg(feature = "trezor")]
pub mod trezor;
