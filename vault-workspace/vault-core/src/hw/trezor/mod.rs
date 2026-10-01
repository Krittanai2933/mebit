//! Trezor Safe 7's host protocol, with no transport inside.
//!
//! mebit supports exactly one Trezor model: the **Safe 7** (internal model
//! `T3W1`), over Bluetooth LE, through the Trezor-Host Protocol (THP). Every
//! other model is refused, by two checks (see [`identity`]); there is no USB,
//! UDP or emulator path, and the packet size is fixed to the Safe 7's BLE one.
//!
//! The layers, bottom up:
//!
//! - **Transport and secure channel.** Trezor's own I/O-free `trezor-thp`
//!   crate: channel allocation, the Noise XX handshake, fragmentation, CRC,
//!   acknowledgements and retransmission.
//! - **Pairing and credentials** ([`cpace`]). Code-entry pairing and the
//!   credential that lets a later connection skip it. `trezor-thp` leaves both
//!   to the application.
//! - **Messages.** Trezor's own generated protobuf bindings (`protos/`).
//!
//! Sources: the THP specification (`docs/common/thp/specification.md`) and
//! trezorlib (`python/src/trezorlib/thp/`) in trezor-firmware at
//! `c33f81554a51` (2026-09-30), whose outputs pin the tests here.

mod bitcoin;
pub mod cpace;
pub mod identity;
mod protos;
mod session;
#[cfg(test)]
mod tests;
mod thp;

use std::fmt;

use zeroize::{Zeroize, Zeroizing};

use super::psbt_check::SignatureCheckError;
pub use bitcoin::XpubOptions;
pub use identity::Safe7Identity;
pub use session::{SessionConfig, Step, TrezorEvent, TrezorSession, UserPrompt};

/// THP's packet size on Bluetooth LE (`BLE_TX_PACKET_SIZE` and
/// `BLE_RX_PACKET_SIZE` in the Safe 7's nRF firmware, `CHUNK_SIZE` in
/// trezorlib's BLE transport). USB's 64-byte packets are deliberately absent.
pub const PACKET_LEN: usize = 244;

#[derive(Debug, thiserror::Error)]
pub enum TrezorError {
    /// Something other than a Trezor Safe 7 answered: another Trezor model
    /// (including the BLE-capable `T3T2`), a fork, or a wrong vendor.
    #[error("not a Trezor Safe 7 ({0}); mebit supports only the Trezor Safe 7")]
    UnsupportedModel(String),

    #[error("unsupported Trezor firmware: {0}")]
    UnsupportedFirmware(String),

    #[error("the Trezor is in bootloader mode; restart it into its firmware")]
    Bootloader,

    #[error("no wallet on the Trezor; set one up on the device first")]
    NotInitialised,

    /// The device didn't offer code-entry pairing, the only method mebit uses.
    #[error("the Trezor does not offer code-entry pairing")]
    PairingUnavailable,

    #[error("the pairing code is the 6 digits shown on the Trezor (not the PIN)")]
    PairingCodeInvalid,

    #[error("pairing failed: {0}")]
    PairingFailed(&'static str),

    /// The Trezor refused a new connection, typically because it is busy
    /// with another host or a menu.
    #[error("the Trezor is busy: {0}")]
    Busy(String),

    #[error("the Trezor stayed locked: unlock it on the device")]
    Locked,

    /// Declined or cancelled on the device, or cancelled from the host.
    #[error("cancelled on the Trezor")]
    UserCancelled,

    /// Any other `Failure` the device answered with, in its words.
    #[error("Trezor error {code}: {message}")]
    Failure { code: i32, message: String },

    #[error("network mismatch: {0}")]
    NetworkMismatch(String),

    /// The request was refused before anything was sent: an unsupported path,
    /// a PSBT this client won't ask the device to sign, an over-long name.
    #[error("not sent to the Trezor: {0}")]
    InvalidRequest(String),

    #[error("the Trezor's signatures were refused: {0}")]
    Signatures(#[from] SignatureCheckError),

    /// The bytes on the wire broke the protocol. The session is out of step
    /// with the device after this and refuses further calls: reconnect.
    #[error("protocol error: {0}")]
    Protocol(String),

    #[error("call made out of order")]
    OutOfOrder,
}

/// Where the user enters a BIP-39 passphrase, if the wallet uses one. Never
/// on the host: no passphrase text crosses this API.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum SessionPassphrase {
    /// The standard wallet, with no passphrase.
    #[default]
    None,
    /// The Safe 7 asks for it on its own screen.
    OnDevice,
}

/// What lets a host reconnect to a Safe 7 it paired with before, without the
/// user typing a code again. mebit never requests an "autoconnect" credential,
/// so the device asks the user to confirm the connection. The exception is
/// when it still holds an open channel from this host's last connection: it
/// then replaces that channel without asking (firmware "channel replacement").
///
/// It holds the host's static private key, so treat it as a secret: keep it in
/// the platform's secure storage (Keychain, Keystore), never in logs. It is
/// wiped from memory on drop, and `Debug` shows only the device's public key.
#[derive(Clone)]
pub struct PairingCredential {
    host_static_key: Zeroizing<[u8; 32]>,
    trezor_static_public_key: [u8; 32],
    credential: Zeroizing<Vec<u8>>,
}

const CREDENTIAL_FORMAT: u8 = 1;
/// `MAX_CREDENTIAL_LEN` in trezor-thp: the most the handshake can carry.
const MAX_CREDENTIAL_LEN: usize = 128;

impl PairingCredential {
    /// For the caller's secure storage. Format: `0x01`, the host key (32
    /// bytes), the Trezor's static public key (32), the credential (rest).
    pub fn to_bytes(&self) -> Zeroizing<Vec<u8>> {
        let mut bytes = Zeroizing::new(Vec::with_capacity(65 + self.credential.len()));
        bytes.push(CREDENTIAL_FORMAT);
        bytes.extend_from_slice(self.host_static_key.as_ref());
        bytes.extend_from_slice(&self.trezor_static_public_key);
        bytes.extend_from_slice(&self.credential);
        bytes
    }

    pub fn from_bytes(bytes: &[u8]) -> Result<Self, TrezorError> {
        let invalid =
            || TrezorError::InvalidRequest("not a stored Trezor pairing credential".into());
        let (&format, rest) = bytes.split_first().ok_or_else(invalid)?;
        if format != CREDENTIAL_FORMAT || rest.len() <= 64 || rest.len() - 64 > MAX_CREDENTIAL_LEN {
            return Err(invalid());
        }
        let mut host_static_key = Zeroizing::new([0u8; 32]);
        host_static_key.copy_from_slice(&rest[..32]);
        let mut trezor_static_public_key = [0u8; 32];
        trezor_static_public_key.copy_from_slice(&rest[32..64]);
        Ok(Self {
            host_static_key,
            trezor_static_public_key,
            credential: Zeroizing::new(rest[64..].to_vec()),
        })
    }

    /// The Safe 7's static public key this credential was issued by.
    pub fn trezor_static_public_key(&self) -> [u8; 32] {
        self.trezor_static_public_key
    }
}

impl fmt::Debug for PairingCredential {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("PairingCredential")
            .field(
                "trezor_static_public_key",
                &Hex(&self.trezor_static_public_key),
            )
            .finish_non_exhaustive()
    }
}

/// Lowercase hex, for `Debug` output of public values only.
struct Hex<'a>(&'a [u8]);

impl fmt::Debug for Hex<'_> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        self.0.iter().try_for_each(|byte| write!(f, "{byte:02x}"))
    }
}

/// Wipes a buffer that held secret material, for the cases `Zeroizing`
/// can't wrap (protobuf messages own their `Vec`s).
fn wipe(bytes: &mut Vec<u8>) {
    bytes.zeroize();
}

/// Parses a protobuf message without enforcing proto2 `required` fields:
/// callers check the fields they rely on, and word the refusal themselves.
fn decode<M: protobuf::Message>(bytes: &[u8]) -> Result<M, TrezorError> {
    let mut message = M::new();
    message
        .merge_from_bytes(bytes)
        .map_err(|e| TrezorError::Protocol(format!("unparseable {}: {e}", M::NAME)))?;
    Ok(message)
}

fn encode<M: protobuf::Message>(message: &M) -> Result<Vec<u8>, TrezorError> {
    message
        .write_to_bytes()
        .map_err(|e| TrezorError::Protocol(format!("cannot encode {}: {e}", M::NAME)))
}
