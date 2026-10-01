//! The transport and secure-channel layers, on Trezor's own `trezor-thp`:
//! channel allocation, the Noise XX handshake, then an encrypted channel that
//! carries `(session id, message type, protobuf)` messages. `trezor-thp` does
//! the fragmenting, CRCs, acknowledgements and retransmission; this module
//! only holds its phases together and supplies the host's credentials.

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use sha2::{Digest, Sha256};
use trezor_thp::channel::buffered::{Buffered, ChannelExt};
use trezor_thp::channel::host;
use trezor_thp::channel::{PacketInResult, PairingState, Phase, retransmit_after_ms};
use trezor_thp::credential::{CredentialStore, FoundCredential};
use trezor_thp::error::TransportError;
use trezor_thp::{Backend, ChannelIO};
use x25519_dalek::{X25519_BASEPOINT_BYTES, x25519};
use zeroize::Zeroizing;

use super::identity::{DeviceProperties, check_device_properties};
use super::protos::common::Failure;
use super::protos::thp::ThpHandshakeCompletionReqNoisePayload;
use super::{PACKET_LEN, PairingCredential, TrezorError, decode, encode};

/// RustCrypto through Trezor's own Noise backend, as in `trezor-thp`'s
/// examples and tests.
pub(super) struct Crypto;

impl Backend for Crypto {
    type DH = trezor_noise_rust_crypto::X25519;
    type Cipher = trezor_noise_rust_crypto::Aes256Gcm;
    type Hash = trezor_noise_rust_crypto::Sha256;

    /// Nonces and THP's ephemeral keys. Without the OS's randomness no key
    /// can be made safely, so this panics rather than make a weak one.
    fn random_bytes(dest: &mut [u8]) {
        getrandom::fill(dest).expect("the OS random number generator failed");
    }
}

/// The host's side of the handshake's credential lookup (`CredentialStore`).
///
/// `trezor-thp` asks it for the host's static key. With no matching credential
/// it would make one up internally and never reveal it, and then the host
/// couldn't ask for a credential at all (`ThpCredentialRequest` names the
/// host's static public key). So this always supplies the key itself: the
/// stored one, or a fresh one. When no stored credential matches the device,
/// the payload is empty, exactly as on `trezor-thp`'s own not-found path.
#[derive(Clone)]
pub(super) struct HostCredentials {
    static_key: Zeroizing<[u8; 32]>,
    /// The device's static public key, and the handshake payload carrying the
    /// credential it issued (`ThpHandshakeCompletionReqNoisePayload`).
    stored: Option<([u8; 32], Zeroizing<Vec<u8>>)>,
    /// Set by the handshake's lookup: whether the stored credential matched
    /// the device and went into the handshake. [`Link::allocate`] gives each
    /// channel a fresh one.
    presented: Arc<AtomicBool>,
}

/// `MAX_CREDENTIAL_LEN` in `trezor-thp`.
const MAX_HANDSHAKE_PAYLOAD_LEN: usize = 128;

impl HostCredentials {
    pub(super) fn new(credential: Option<&PairingCredential>) -> Result<Self, TrezorError> {
        let Some(credential) = credential else {
            let mut static_key = Zeroizing::new([0u8; 32]);
            getrandom::fill(static_key.as_mut()).map_err(|_| {
                TrezorError::InvalidRequest("no randomness for the host's key".into())
            })?;
            return Ok(Self {
                static_key,
                stored: None,
                presented: Arc::default(),
            });
        };
        let mut payload = ThpHandshakeCompletionReqNoisePayload::new();
        payload.host_pairing_credential = Some(credential.credential.to_vec());
        let encoded = Zeroizing::new(encode(&payload)?);
        if let Some(bytes) = payload.host_pairing_credential.as_mut() {
            super::wipe(bytes);
        }
        if encoded.len() > MAX_HANDSHAKE_PAYLOAD_LEN {
            return Err(TrezorError::InvalidRequest(
                "the stored credential is too long for the handshake".into(),
            ));
        }
        Ok(Self {
            static_key: credential.host_static_key.clone(),
            stored: Some((credential.trezor_static_public_key, encoded)),
            presented: Arc::default(),
        })
    }

    pub(super) fn static_key(&self) -> &Zeroizing<[u8; 32]> {
        &self.static_key
    }

    pub(super) fn static_public_key(&self) -> [u8; 32] {
        x25519(*self.static_key, X25519_BASEPOINT_BYTES)
    }
}

impl CredentialStore for HostCredentials {
    fn lookup<'a>(
        &self,
        ephemeral_pubkey: &[u8],
        masked_static_pubkey: &[u8],
        dest: &'a mut [u8],
    ) -> Option<FoundCredential<'a>> {
        let matched = self
            .stored
            .as_ref()
            .filter(|(trezor_key, _)| masks_to(trezor_key, ephemeral_pubkey, masked_static_pubkey));
        let payload: &[u8] = matched.map_or(&[][..], |(_, payload)| payload.as_slice());
        if dest.len() < 32 + payload.len() {
            return None;
        }
        let (key, rest) = dest.split_at_mut(32);
        key.copy_from_slice(self.static_key.as_ref());
        rest[..payload.len()].copy_from_slice(payload);
        let key: &'a [u8] = key;
        let rest: &'a [u8] = rest;
        let local_static_privkey = key.try_into().ok()?;
        self.presented.store(matched.is_some(), Ordering::SeqCst);
        Some(FoundCredential {
            local_static_privkey,
            auth_credential: &rest[..payload.len()],
        })
    }
}

/// Whether the device's masked static key is `trezor_key`, as trezorlib's
/// `credentials.matches`: `X25519(SHA-256(T ‖ e), T) == masked`.
fn masks_to(trezor_key: &[u8; 32], ephemeral: &[u8], masked: &[u8]) -> bool {
    let mask: [u8; 32] = Sha256::new()
        .chain_update(trezor_key)
        .chain_update(ephemeral)
        .finalize()
        .into();
    x25519(mask, *trezor_key).as_slice() == masked
}

/// What a packet did.
pub(super) enum Arrival {
    /// Nothing for the application: an ACK, a fragment, a duplicate, or a
    /// packet for another channel.
    Nothing,
    /// Channel allocated; the device passed identity gate 1.
    Allocated,
    /// The device refused the handshake because it is locked.
    DeviceLocked,
    /// The handshake finished; the channel is open, in its pairing phase.
    /// `presented` is whether our stored credential went into the handshake.
    HandshakeDone {
        state: PairingState,
        presented: bool,
    },
    /// A whole message.
    Message {
        session: u8,
        message_type: u16,
        payload: Vec<u8>,
    },
}

/// One THP channel through its phases. Every method returns the packets to
/// write, in order, each [`PACKET_LEN`] bytes.
pub(super) enum Link {
    Allocating {
        mux: Box<Buffered<host::Mux<Crypto>>>,
        credentials: HostCredentials,
    },
    Handshaking {
        open: Box<Buffered<host::ChannelOpen<HostCredentials, Crypto>>>,
        properties: DeviceProperties,
        /// The flag the credentials, now inside `open`, report through.
        presented: Arc<AtomicBool>,
    },
    Open {
        channel: Box<Buffered<host::Channel<Crypto>>>,
        properties: DeviceProperties,
    },
    Closed,
}

impl Link {
    /// Starts channel allocation. `try_to_unlock` lets the device ask for its
    /// PIN during the handshake; trezorlib first asks without it.
    pub(super) fn allocate(
        credentials: HostCredentials,
        try_to_unlock: bool,
    ) -> (Self, Vec<Vec<u8>>) {
        // A fresh flag, so no earlier handshake's lookup answers for this one.
        let credentials = HostCredentials {
            presented: Arc::default(),
            ..credentials
        };
        let mut mux = Box::new(host::Mux::<Crypto>::new().into_buffered());
        mux.set_packet_len(PACKET_LEN);
        mux.request_channel(try_to_unlock);
        let mut link = Self::Allocating { mux, credentials };
        let packets = link.drain();
        (link, packets)
    }

    /// Feeds one received packet.
    pub(super) fn receive(
        &mut self,
        packet: &[u8],
    ) -> Result<(Arrival, Vec<Vec<u8>>), TrezorError> {
        if packet.starts_with(b"?##") {
            return Err(codec_v1_refusal(packet));
        }
        let arrival = match self {
            Self::Allocating { mux, .. } => match mux.packet_in(packet) {
                PacketInResult::ChannelAllocation => {
                    self.open_channel()?;
                    Arrival::Allocated
                }
                PacketInResult::Failed { error } => return Err(thp_failure(&error)),
                _ => Arrival::Nothing,
            },
            Self::Handshaking { open, .. } => match open.packet_in(packet) {
                PacketInResult::TransportError {
                    error: TransportError::DeviceLocked,
                } => {
                    *self = Self::Closed;
                    return Ok((Arrival::DeviceLocked, Vec::new()));
                }
                PacketInResult::TransportError { error } => return Err(transport_error(error)),
                PacketInResult::Failed { error } => return Err(thp_failure(&error)),
                _ if open.handshake_failed() => {
                    return Err(TrezorError::Protocol("the handshake failed".into()));
                }
                _ if open.handshake_done() => {
                    let packets = self.drain();
                    let (state, presented) = self.complete_handshake()?;
                    return Ok((Arrival::HandshakeDone { state, presented }, packets));
                }
                _ => Arrival::Nothing,
            },
            Self::Open { channel, .. } => match channel.packet_in(packet) {
                result if result.got_message() => {
                    let (session, message_type, payload) = channel.message_out().map_err(|e| {
                        TrezorError::Protocol(format!("unreadable message: {}", describe(e)))
                    })?;
                    Arrival::Message {
                        session,
                        message_type,
                        payload,
                    }
                }
                // Busy reassembling on another channel; ours wasn't taken, so
                // the retransmission timer resends it.
                PacketInResult::TransportError {
                    error: TransportError::TransportBusy,
                } => Arrival::Nothing,
                PacketInResult::TransportError { error } => return Err(transport_error(error)),
                PacketInResult::Failed { error } => return Err(thp_failure(&error)),
                _ => Arrival::Nothing,
            },
            Self::Closed => return Err(broken()),
        };
        Ok((arrival, self.drain()))
    }

    /// Queues an application message on the open channel.
    pub(super) fn send(
        &mut self,
        session: u8,
        message_type: u16,
        payload: &[u8],
    ) -> Result<Vec<Vec<u8>>, TrezorError> {
        let Self::Open { channel, .. } = self else {
            return Err(TrezorError::OutOfOrder);
        };
        channel
            .message_in(session, message_type, payload)
            .map_err(|_| TrezorError::OutOfOrder)?;
        Ok(self.drain())
    }

    /// Whether a message of ours may be queued now: the last one was
    /// acknowledged and nothing is being received.
    pub(super) fn ready_to_send(&self) -> bool {
        matches!(self, Self::Open { channel, .. } if channel.message_in_ready())
    }

    /// How long to wait for an acknowledgement before [`Link::retransmit`],
    /// if one is outstanding.
    /// Asks again for a channel, with a fresh nonce: an allocation request
    /// gets no acknowledgement, so a lost one is only noticed by silence. An
    /// answer to the earlier nonce is then ignored by the `Mux`.
    pub(super) fn reallocate(&mut self, try_to_unlock: bool) -> Vec<Vec<u8>> {
        let Self::Allocating { mux, .. } = self else {
            return Vec::new();
        };
        mux.request_channel(try_to_unlock);
        self.drain()
    }

    pub(super) fn is_allocating(&self) -> bool {
        matches!(self, Self::Allocating { .. })
    }

    pub(super) fn ack_wait_ms(&self) -> Option<u32> {
        let retry = match self {
            Self::Handshaking { open, .. } => open.sending_retry(),
            Self::Open { channel, .. } => channel.sending_retry(),
            _ => None,
        }?;
        Some(retransmit_after_ms(retry))
    }

    pub(super) fn retransmit(&mut self) -> Result<Vec<Vec<u8>>, TrezorError> {
        let result = match self {
            Self::Handshaking { open, .. } => open.message_retransmit(),
            Self::Open { channel, .. } => channel.message_retransmit(),
            _ => return Ok(Vec::new()),
        };
        result.map_err(|e| TrezorError::Protocol(format!("cannot retransmit: {}", describe(e))))?;
        Ok(self.drain())
    }

    pub(super) fn sending_retry(&self) -> Option<u8> {
        match self {
            Self::Handshaking { open, .. } => open.sending_retry(),
            Self::Open { channel, .. } => channel.sending_retry(),
            _ => None,
        }
    }

    pub(super) fn handshake_hash(&self) -> Option<[u8; 32]> {
        match self {
            Self::Open { channel, .. } => Some(*channel.handshake_hash()),
            _ => None,
        }
    }

    pub(super) fn properties(&self) -> Option<&DeviceProperties> {
        match self {
            Self::Handshaking { properties, .. } | Self::Open { properties, .. } => {
                Some(properties)
            }
            _ => None,
        }
    }

    /// The pairing and credential phase is over: messages are application
    /// messages from now on.
    pub(super) fn end_pairing(&mut self) {
        if let Self::Open { channel, .. } = self {
            channel.end_pairing();
        }
    }

    pub(super) fn close(&mut self) {
        *self = Self::Closed;
    }

    /// Identity gate 1 runs here, on the allocation response, before the
    /// handshake's first packet exists.
    fn open_channel(&mut self) -> Result<(), TrezorError> {
        let Self::Allocating { mux, credentials } = std::mem::replace(self, Self::Closed) else {
            unreachable!("only called while allocating")
        };
        let presented = Arc::clone(&credentials.presented);
        let mut open = (*mux)
            .map(|mux| mux.complete(credentials))
            .map_err(|e| TrezorError::Protocol(format!("channel allocation: {}", describe(e))))?;
        let properties = check_device_properties(open.device_properties())?;
        let (major, minor) = properties.protocol;
        open.set_device_protocol_version(major, minor);
        *self = Self::Handshaking {
            open: Box::new(open),
            properties,
            presented,
        };
        Ok(())
    }

    /// The device's pairing state, and whether our credential was presented.
    fn complete_handshake(&mut self) -> Result<(PairingState, bool), TrezorError> {
        let Self::Handshaking {
            open,
            properties,
            presented,
        } = std::mem::replace(self, Self::Closed)
        else {
            unreachable!("only called while handshaking")
        };
        let channel = (*open)
            .map(|open| open.complete())
            .map_err(|e| TrezorError::Protocol(format!("handshake: {}", describe(e))))?;
        let state = match channel.phase() {
            Phase::PairingCredential {
                handshake_pairing_state,
            } => handshake_pairing_state,
            Phase::EncryptedTransport => {
                return Err(TrezorError::Protocol("channel opened past pairing".into()));
            }
        };
        *self = Self::Open {
            channel: Box::new(channel),
            properties,
        };
        Ok((state, presented.load(Ordering::SeqCst)))
    }

    fn drain(&mut self) -> Vec<Vec<u8>> {
        fn drain_from<C: ChannelIO>(link: &mut Buffered<C>) -> Vec<Vec<u8>> {
            let mut packets = Vec::new();
            while link.packet_out_ready() {
                match link.packet_out() {
                    Ok(packet) => packets.push(packet),
                    Err(_) => break,
                }
            }
            packets
        }
        match self {
            Self::Allocating { mux, .. } => drain_from(mux),
            Self::Handshaking { open, .. } => drain_from(open),
            Self::Open { channel, .. } => drain_from(channel),
            Self::Closed => Vec::new(),
        }
    }
}

/// When it won't take a connection, the Safe 7's BLE chip answers in the old
/// codec v1 (`?##`, type, length, protobuf): a `Failure` saying "Device
/// locked or busy" (`service_send_busy()` in the nRF firmware).
fn codec_v1_refusal(packet: &[u8]) -> TrezorError {
    let message_type = packet.get(3..5).map(|b| u16::from_be_bytes([b[0], b[1]]));
    let length = packet
        .get(5..9)
        .map(|b| u32::from_be_bytes([b[0], b[1], b[2], b[3]]) as usize);
    let message = match (message_type, length) {
        (Some(3), Some(length)) => packet
            .get(9..9 + length)
            .and_then(|body| decode::<Failure>(body).ok())
            .and_then(|failure| failure.message)
            .unwrap_or_else(|| "busy".into()),
        _ => "it answered in an old protocol".into(),
    };
    TrezorError::Busy(message)
}

fn transport_error(error: TransportError) -> TrezorError {
    match error {
        TransportError::DeviceLocked => TrezorError::Locked,
        other => TrezorError::Protocol(format!("the Trezor reported {}", other.as_str())),
    }
}

fn thp_failure(error: &trezor_thp::Error) -> TrezorError {
    TrezorError::Protocol(format!("the channel failed: {}", describe(*error)))
}

/// `trezor_thp::Error` implements `Debug` only in debug builds.
fn describe(error: trezor_thp::Error) -> &'static str {
    use trezor_thp::Error;
    match error {
        Error::UnexpectedInput => "unexpected input",
        Error::NotReady => "not ready",
        Error::MalformedData => "malformed data",
        Error::InvalidChecksum => "invalid checksum",
        Error::InsufficientBuffer => "message too large",
        Error::CryptoError => "decryption failed",
    }
}

pub(super) fn broken() -> TrezorError {
    TrezorError::Protocol("out of step with the Trezor; reconnect".into())
}
