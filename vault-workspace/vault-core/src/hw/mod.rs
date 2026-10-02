//! Hardware wallet clients. Jade (No.4) speaks its protocol through
//! [`jade::JadeSession`]; the BLE transport that carries it lives in the
//! `jade-ble` crate. Trezor Safe 7 (No.5) is not started.

pub mod jade;
