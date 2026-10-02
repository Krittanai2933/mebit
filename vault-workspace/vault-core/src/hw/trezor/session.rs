//! [`TrezorSession`]: the protocol state of one connection to one Safe 7.
//!
//! Like `JadeSession`, it never touches the radio. Whoever owns the BLE link
//! writes every packet it returns, feeds every packet that arrives into
//! [`TrezorSession::receive`], and acts on the [`TrezorEvent`]s. What a
//! connection goes through, from the THP specification and trezorlib
//! (`thp/client.py`, `thp/pairing.py`):
//!
//! 1. Channel allocation, and identity gate 1 on its device properties.
//! 2. The Noise handshake. A locked device refuses it; the session then
//!    starts over and lets the device ask for its PIN, as trezorlib does.
//! 3. Code-entry pairing, or, with a credential from an earlier pairing, the
//!    user's confirmation on the device. A credential is requested after
//!    pairing, never an "autoconnect" one. The device's word that it knows
//!    this host is taken only if this host presented a credential it issued
//!    (the specification's host state HH3).
//! 4. `GetFeatures` on the seedless session 0, and identity gate 2.
//! 5. `ThpCreateNewSession` for the wallet on session 1, where every later
//!    request runs.
//!
//! One request at a time. A request that fails on the device's side
//! (declined, cancelled) ends in [`TrezorEvent::Failed`] and leaves the
//! connection usable. A broken protocol, or any failure while connecting,
//! is an `Err`, after which the session refuses further calls: reconnect.

use std::time::Duration;

use bitcoin::bip32::{DerivationPath, Fingerprint, Xpub};
use bitcoin::{Network, Psbt};
use protobuf::{EnumOrUnknown, Message};
use trezor_thp::channel::PairingState;
use zeroize::Zeroizing;

use super::bitcoin::{Answer, Signing, XpubOptions, XpubRequest};
use super::cpace::{self, CHALLENGE_LEN};
use super::identity::{Safe7Identity, check_features};
use super::protos::MessageType;
use super::protos::bitcoin::{PublicKey, TxRequest};
use super::protos::common::Failure;
use super::protos::common::failure::FailureType;
use super::protos::management::{Features, GetFeatures};
use super::protos::thp::{
    ThpCodeEntryChallenge, ThpCodeEntryCommitment, ThpCodeEntryCpaceHostTag,
    ThpCodeEntryCpaceTrezor, ThpCodeEntrySecret, ThpCreateNewSession, ThpCredentialRequest,
    ThpCredentialResponse, ThpEndRequest, ThpMessageType, ThpPairingMethod, ThpPairingRequest,
    ThpSelectMethod,
};
use super::thp::{Arrival, HostCredentials, Link, broken};
use super::{PairingCredential, SessionPassphrase, TrezorError, decode, encode};
use crate::error::VaultCoreError;

/// Pairing, credentials and `GetFeatures` (trezorlib's pairing session and
/// `_get_session(passphrase=None)`).
const SEEDLESS: u8 = 0;
/// The wallet's session, opened by `ThpCreateNewSession`.
const WALLET: u8 = 1;
/// Resends of one unacknowledged message before giving up. THP allows 50;
/// over BLE a lost packet is rare, and the user is waiting.
const MAX_RETRANSMISSIONS: u8 = 10;
/// Channel allocation requests get no acknowledgement; a device that was
/// asleep may miss the first. Ask again after this long, a few times.
const ALLOCATION_RETRY: Duration = Duration::from_secs(3);
const MAX_ALLOCATION_RETRIES: u8 = 3;
/// Host and app names are shown on the device and stored in the
/// credential, which the handshake carries in 128 bytes. Trezor keeps them
/// to 32 bytes each (`ThpPairedCacheEntry`).
const MAX_NAME_LEN: usize = 32;

const SUCCESS: u16 = MessageType::MessageType_Success as u16;
const FAILURE: u16 = MessageType::MessageType_Failure as u16;
const FEATURES: u16 = MessageType::MessageType_Features as u16;
const GET_FEATURES: u16 = MessageType::MessageType_GetFeatures as u16;
const CANCEL: u16 = MessageType::MessageType_Cancel as u16;
const BUTTON_REQUEST: u16 = MessageType::MessageType_ButtonRequest as u16;
const BUTTON_ACK: u16 = MessageType::MessageType_ButtonAck as u16;
const GET_PUBLIC_KEY: u16 = MessageType::MessageType_GetPublicKey as u16;
const PUBLIC_KEY: u16 = MessageType::MessageType_PublicKey as u16;
const SIGN_TX: u16 = MessageType::MessageType_SignTx as u16;
const TX_REQUEST: u16 = MessageType::MessageType_TxRequest as u16;
const TX_ACK: u16 = MessageType::MessageType_TxAck as u16;
const CREATE_NEW_SESSION: u16 = MessageType::MessageType_ThpCreateNewSession as u16;
const PAIRING_REQUEST: u16 = ThpMessageType::ThpMessageType_ThpPairingRequest as u16;
const PAIRING_REQUEST_APPROVED: u16 =
    ThpMessageType::ThpMessageType_ThpPairingRequestApproved as u16;
const SELECT_METHOD: u16 = ThpMessageType::ThpMessageType_ThpSelectMethod as u16;
const CODE_ENTRY_COMMITMENT: u16 = ThpMessageType::ThpMessageType_ThpCodeEntryCommitment as u16;
const CODE_ENTRY_CHALLENGE: u16 = ThpMessageType::ThpMessageType_ThpCodeEntryChallenge as u16;
const CODE_ENTRY_CPACE_TREZOR: u16 = ThpMessageType::ThpMessageType_ThpCodeEntryCpaceTrezor as u16;
const CODE_ENTRY_CPACE_HOST_TAG: u16 =
    ThpMessageType::ThpMessageType_ThpCodeEntryCpaceHostTag as u16;
const CODE_ENTRY_SECRET: u16 = ThpMessageType::ThpMessageType_ThpCodeEntrySecret as u16;
const CREDENTIAL_REQUEST: u16 = ThpMessageType::ThpMessageType_ThpCredentialRequest as u16;
const CREDENTIAL_RESPONSE: u16 = ThpMessageType::ThpMessageType_ThpCredentialResponse as u16;
const END_REQUEST: u16 = ThpMessageType::ThpMessageType_ThpEndRequest as u16;
const END_RESPONSE: u16 = ThpMessageType::ThpMessageType_ThpEndResponse as u16;

#[derive(Debug, Clone)]
pub struct SessionConfig {
    /// Sets the coin the device signs for, and the xpubs' network.
    pub network: Network,
    /// The device asks "Allow {app_name} on {host_name} to pair?". At most
    /// 32 bytes each.
    pub host_name: String,
    pub app_name: String,
    pub passphrase: SessionPassphrase,
}

/// What the device is waiting for the user to do.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UserPrompt {
    /// Enter the PIN, or a passphrase, on the device.
    Unlock,
    /// Approve pairing with this host.
    ApprovePairing,
    /// Confirm a connection from a host paired before.
    ConfirmConnection,
    /// Confirm what is on the screen: an xpub, or a transaction.
    Confirm,
}

/// What the host does next, beyond writing [`Step::send`].
#[derive(Debug)]
pub enum TrezorEvent {
    /// Ask the user for the six digits on the Safe 7's screen (not the PIN),
    /// then call [`TrezorSession::pairing_code`].
    PairingCodeNeeded,
    /// The device waits on the user, for a UI to say so. Time replies by
    /// [`TrezorSession::awaiting_user`] instead: the device's acknowledgement
    /// arrives after this event, while the user still has minutes.
    AwaitingUser(UserPrompt),
    /// Connected to a Safe 7 that passed both identity checks. After a fresh
    /// pairing, `new_credential` lets the next connection skip the code.
    Connected {
        identity: Safe7Identity,
        new_credential: Option<PairingCredential>,
    },
    Xpub {
        xpub: Xpub,
        /// The fingerprint PSBTs name the device's keys by.
        master_fingerprint: Fingerprint,
        /// The device's own encoding of the key (`tpub…`, or with
        /// [`XpubOptions::slip132`] what Trezor Suite shows).
        as_shown: String,
    },
    /// Only ever the PSBT that was sent plus valid signatures from the
    /// signer's keys (see [`crate::hw::psbt_check`]).
    SignedPsbt(Psbt),
    /// The request ended without a result, and the connection stays usable:
    /// declined or cancelled on the device, refused by the device, or refused
    /// here (the signatures didn't check out). Write [`Step::send`] anyway:
    /// it acknowledges the device's last message, and without it the device
    /// would ignore the next request.
    Failed(TrezorError),
}

/// The packets to write, in order, and what happened.
#[derive(Debug, Default)]
pub struct Step {
    pub send: Vec<Vec<u8>>,
    pub event: Option<TrezorEvent>,
}

/// The protocol state of one connection. See the module docs.
pub struct TrezorSession {
    config: SessionConfig,
    credentials: HostCredentials,
    link: Link,
    flow: Flow,
    try_to_unlock: bool,
    /// The session id of a prompt we acknowledged: the device waits on the user.
    on_screen: Option<u8>,
    /// Cancel asked for with no prompt open: answer the next one with `Cancel`.
    cancel_pending: bool,
    /// A message waiting for the previous one's acknowledgement.
    outbox: Option<(u8, u16, Vec<u8>)>,
    retransmissions: u8,
}

enum Flow {
    Idle,
    Connecting(Connect),
    Ready,
    Xpub(XpubRequest),
    Signing(Box<Signing>),
    Broken,
}

enum Connect {
    Allocating,
    Handshaking,
    PairingRequested,
    MethodSelected,
    ChallengeSent {
        challenge: [u8; CHALLENGE_LEN],
        commitment: Vec<u8>,
    },
    CodeNeeded {
        challenge: [u8; CHALLENGE_LEN],
        commitment: Vec<u8>,
        trezor_key: [u8; 32],
    },
    TagSent {
        challenge: [u8; CHALLENGE_LEN],
        commitment: Vec<u8>,
        code: Zeroizing<String>,
    },
    CredentialRequested,
    Ending {
        new_credential: Option<PairingCredential>,
    },
    GettingFeatures {
        new_credential: Option<PairingCredential>,
    },
    CreatingSession {
        identity: Safe7Identity,
        new_credential: Option<PairingCredential>,
    },
}

impl TrezorSession {
    /// `credential` is one issued by this Safe 7 before, if the host kept it.
    pub fn new(
        config: SessionConfig,
        credential: Option<&PairingCredential>,
    ) -> Result<Self, VaultCoreError> {
        for (what, name) in [
            ("host name", &config.host_name),
            ("app name", &config.app_name),
        ] {
            if name.is_empty() || name.len() > MAX_NAME_LEN {
                return Err(TrezorError::InvalidRequest(format!(
                    "the {what} must be 1 to {MAX_NAME_LEN} bytes"
                ))
                .into());
            }
        }
        Ok(Self {
            credentials: HostCredentials::new(credential)?,
            config,
            link: Link::Closed,
            flow: Flow::Idle,
            try_to_unlock: false,
            on_screen: None,
            cancel_pending: false,
            outbox: None,
            retransmissions: 0,
        })
    }

    /// Starts connecting. Write what it returns, then feed replies to
    /// [`TrezorSession::receive`] until [`TrezorEvent::Connected`].
    pub fn start(&mut self) -> Result<Vec<Vec<u8>>, VaultCoreError> {
        if !matches!(self.flow, Flow::Idle) {
            return Err(TrezorError::OutOfOrder.into());
        }
        Ok(self.allocate(false))
    }

    /// Feeds one packet the device sent. An `Err` means the connection is
    /// lost (see the module docs); a request that merely failed ends in
    /// [`TrezorEvent::Failed`] instead, so its packets still go out.
    pub fn receive(&mut self, packet: &[u8]) -> Result<Step, VaultCoreError> {
        match self.flow {
            Flow::Idle => return Err(TrezorError::OutOfOrder.into()),
            Flow::Broken => return Err(broken().into()),
            _ => {}
        }
        let (send, outcome) = self.receive_inner(packet);
        match outcome {
            Ok(event) => Ok(Step { send, event }),
            Err(error) => {
                self.after_error(&error);
                if matches!(self.flow, Flow::Broken) {
                    Err(error.into())
                } else {
                    Ok(Step {
                        send,
                        event: Some(TrezorEvent::Failed(error)),
                    })
                }
            }
        }
    }

    /// The six digits the user read off the Safe 7, after
    /// [`TrezorEvent::PairingCodeNeeded`]. A malformed code is refused
    /// without ending the pairing, so the user can retype it.
    pub fn pairing_code(&mut self, code: &str) -> Result<Vec<Vec<u8>>, VaultCoreError> {
        let Flow::Connecting(Connect::CodeNeeded { .. }) = &self.flow else {
            return Err(TrezorError::OutOfOrder.into());
        };
        cpace::check_code(code)?;
        let result = self.send_host_tag(code);
        if let Err(error) = &result {
            self.after_error(error);
        }
        result.map_err(Into::into)
    }

    /// Asks for the xpub at an account `path` (BIP-48 P2WSH or BIP-84).
    pub fn request_xpub(
        &mut self,
        path: &DerivationPath,
        options: XpubOptions,
    ) -> Result<Vec<Vec<u8>>, VaultCoreError> {
        self.ensure_ready()?;
        let (request, message) = XpubRequest::new(path, self.config.network, options)?;
        let send = self.queue(WALLET, GET_PUBLIC_KEY, encode(&message)?)?;
        self.flow = Flow::Xpub(request);
        Ok(send)
    }

    /// Has the device sign `psbt` once the user confirms it on the device.
    /// `signer` is the master fingerprint the vault expects to sign with.
    pub fn request_sign_psbt(
        &mut self,
        psbt: &Psbt,
        signer: Fingerprint,
    ) -> Result<Vec<Vec<u8>>, VaultCoreError> {
        self.ensure_ready()?;
        let (signing, message) = Signing::new(psbt, signer, self.config.network)?;
        let send = self.queue(WALLET, SIGN_TX, encode(&message)?)?;
        self.flow = Flow::Signing(Box::new(signing));
        Ok(send)
    }

    /// Asks the device to drop the prompt it shows, or the next one: the
    /// request then ends in [`TrezorError::UserCancelled`] and the connection
    /// stays usable. If the request ends before showing another prompt (say,
    /// it was past its last confirmation), it ends normally and the cancel
    /// lapses. Does nothing when no request can be waiting on the user.
    pub fn request_cancel(&mut self) -> Result<Vec<Vec<u8>>, VaultCoreError> {
        let cancellable = matches!(
            self.flow,
            Flow::Xpub(_)
                | Flow::Signing(_)
                | Flow::Connecting(
                    Connect::PairingRequested
                        | Connect::Ending { .. }
                        | Connect::CreatingSession { .. }
                )
        );
        if !cancellable {
            return Ok(Vec::new());
        }
        if let Some(session) = self.on_screen.take() {
            return Ok(self.queue(session, CANCEL, Vec::new())?);
        }
        self.cancel_pending = true;
        Ok(Vec::new())
    }

    /// How long to wait for the device to acknowledge what was last sent,
    /// before calling [`TrezorSession::retransmit`]. `None` when nothing is
    /// waiting for an acknowledgement.
    pub fn ack_wait(&self) -> Option<Duration> {
        if self.link.is_allocating() {
            return (self.retransmissions < MAX_ALLOCATION_RETRIES).then_some(ALLOCATION_RETRY);
        }
        self.link
            .ack_wait_ms()
            .map(|ms| Duration::from_millis(u64::from(ms)))
    }

    /// Resends an unacknowledged message, up to a limit; while allocating a
    /// channel, asks for one again.
    pub fn retransmit(&mut self) -> Result<Vec<Vec<u8>>, VaultCoreError> {
        if self.link.is_allocating() {
            if self.retransmissions >= MAX_ALLOCATION_RETRIES {
                return Ok(Vec::new());
            }
            self.retransmissions += 1;
            return Ok(self.link.reallocate(self.try_to_unlock));
        }
        if self.link.sending_retry().is_none() {
            return Ok(Vec::new());
        }
        self.retransmissions += 1;
        if self.retransmissions > MAX_RETRANSMISSIONS {
            self.break_session();
            return Err(TrezorError::Protocol("the Trezor stopped acknowledging".into()).into());
        }
        let result = self.link.retransmit();
        if let Err(error) = &result {
            self.after_error(error);
        }
        result.map_err(Into::into)
    }

    /// Whether connected and idle, so a request can be made.
    pub fn is_ready(&self) -> bool {
        matches!(self.flow, Flow::Ready)
    }

    /// Whether the device's next reply waits on its user, and so may take
    /// minutes rather than seconds. That is, a prompt we acknowledged, or a
    /// handshake that asked the device to unlock, which may wait for its PIN.
    /// Acknowledgements don't change it; only the device's next message does.
    pub fn awaiting_user(&self) -> bool {
        self.on_screen.is_some()
            || (self.try_to_unlock && matches!(self.flow, Flow::Connecting(Connect::Handshaking)))
    }

    /// For a caller that gave up midway (a timeout, a dropped link): the
    /// session refuses further calls. Reconnect.
    pub fn abandon(&mut self) {
        self.break_session();
    }

    /// The packets to write, which include the acknowledgement of whatever
    /// arrived, even when handling it failed, and the outcome.
    fn receive_inner(
        &mut self,
        packet: &[u8],
    ) -> (Vec<Vec<u8>>, Result<Option<TrezorEvent>, TrezorError>) {
        let (arrival, mut send) = match self.link.receive(packet) {
            Ok(received) => received,
            Err(error) => return (Vec::new(), Err(error)),
        };
        let handled = match arrival {
            Arrival::Nothing => Ok(Step::default()),
            Arrival::Allocated => {
                self.flow = Flow::Connecting(Connect::Handshaking);
                // With `try_to_unlock` the device may now ask for its PIN.
                let event = self
                    .try_to_unlock
                    .then_some(TrezorEvent::AwaitingUser(UserPrompt::Unlock));
                Ok(Step {
                    send: Vec::new(),
                    event,
                })
            }
            Arrival::DeviceLocked if !self.try_to_unlock => Ok(Step {
                send: self.allocate(true),
                event: None,
            }),
            Arrival::DeviceLocked => Err(TrezorError::Locked),
            Arrival::HandshakeDone { state, presented } => self.on_handshake_done(state, presented),
            Arrival::Message {
                session,
                message_type,
                payload,
            } => {
                self.on_screen = None;
                self.on_message(session, message_type, payload)
            }
        };
        let event = match handled {
            Ok(step) => {
                send.extend(step.send);
                step.event
            }
            Err(error) => return (send, Err(error)),
        };
        if self.link.ready_to_send()
            && let Some((session, message_type, payload)) = self.outbox.take()
        {
            match self.link.send(session, message_type, &payload) {
                Ok(packets) => send.extend(packets),
                Err(error) => return (send, Err(error)),
            }
        }
        (send, Ok(event))
    }

    fn on_handshake_done(
        &mut self,
        state: PairingState,
        presented: bool,
    ) -> Result<Step, TrezorError> {
        let send = if state.is_paired() {
            // A device can only know this host by a credential the handshake
            // carried. Without one, the specification has the host require
            // "unpaired" (host state HH3); taking the claim would let any
            // peer, or one posing as a paired Safe 7, skip pairing.
            if !presented {
                return Err(TrezorError::Protocol(
                    "the Trezor claims a pairing this host has no credential for".into(),
                ));
            }
            // Known host: finish; the device may ask the user to confirm.
            self.flow = Flow::Connecting(Connect::Ending {
                new_credential: None,
            });
            self.send(SEEDLESS, END_REQUEST, &ThpEndRequest::new())?
        } else {
            if !self.link.properties().is_some_and(|p| p.code_entry) {
                return Err(TrezorError::PairingUnavailable);
            }
            let mut request = ThpPairingRequest::new();
            request.host_name = Some(self.config.host_name.clone());
            request.app_name = Some(self.config.app_name.clone());
            self.flow = Flow::Connecting(Connect::PairingRequested);
            self.send(SEEDLESS, PAIRING_REQUEST, &request)?
        };
        Ok(Step { send, event: None })
    }

    fn on_message(
        &mut self,
        session: u8,
        message_type: u16,
        payload: Vec<u8>,
    ) -> Result<Step, TrezorError> {
        match message_type {
            BUTTON_REQUEST => return self.on_button_request(session),
            FAILURE => return Err(self.on_failure(&decode::<Failure>(&payload)?)),
            _ => {}
        }
        match std::mem::replace(&mut self.flow, Flow::Broken) {
            Flow::Connecting(state) => self.connect_step(state, session, message_type, &payload),
            Flow::Xpub(request) => {
                expect(session, WALLET, message_type, PUBLIC_KEY, "PublicKey")?;
                self.become_ready();
                let (xpub, master_fingerprint, as_shown) =
                    request.finish(decode::<PublicKey>(&payload)?)?;
                Ok(Step {
                    send: Vec::new(),
                    event: Some(TrezorEvent::Xpub {
                        xpub,
                        master_fingerprint,
                        as_shown,
                    }),
                })
            }
            Flow::Signing(mut signing) => {
                expect(session, WALLET, message_type, TX_REQUEST, "TxRequest")?;
                match signing.answer(&decode::<TxRequest>(&payload)?)? {
                    Answer::Ack(ack) => {
                        self.flow = Flow::Signing(signing);
                        Ok(Step {
                            send: self.queue(WALLET, TX_ACK, ack)?,
                            event: None,
                        })
                    }
                    Answer::Finished => {
                        // The exchange is over either way; the channel is in step.
                        self.become_ready();
                        let signed = signing.finish()?;
                        Ok(Step {
                            send: Vec::new(),
                            event: Some(TrezorEvent::SignedPsbt(signed)),
                        })
                    }
                }
            }
            Flow::Idle | Flow::Ready | Flow::Broken => Err(TrezorError::Protocol(format!(
                "unsolicited message type {message_type}"
            ))),
        }
    }

    fn connect_step(
        &mut self,
        state: Connect,
        session: u8,
        message_type: u16,
        payload: &[u8],
    ) -> Result<Step, TrezorError> {
        let (flow, send, event) = match state {
            Connect::PairingRequested => {
                expect(
                    session,
                    SEEDLESS,
                    message_type,
                    PAIRING_REQUEST_APPROVED,
                    "ThpPairingRequestApproved",
                )?;
                let mut select = ThpSelectMethod::new();
                select.selected_pairing_method =
                    Some(EnumOrUnknown::new(ThpPairingMethod::CodeEntry));
                (
                    Connect::MethodSelected,
                    self.send(SEEDLESS, SELECT_METHOD, &select)?,
                    None,
                )
            }
            Connect::MethodSelected => {
                expect(
                    session,
                    SEEDLESS,
                    message_type,
                    CODE_ENTRY_COMMITMENT,
                    "ThpCodeEntryCommitment",
                )?;
                let commitment = decode::<ThpCodeEntryCommitment>(payload)?
                    .commitment
                    .filter(|c| c.len() == 32)
                    .ok_or_else(|| {
                        TrezorError::Protocol("a commitment that isn't 32 bytes".into())
                    })?;
                let mut challenge = [0u8; CHALLENGE_LEN];
                getrandom::fill(&mut challenge)
                    .map_err(|_| TrezorError::PairingFailed("no randomness for the challenge"))?;
                let mut message = ThpCodeEntryChallenge::new();
                message.challenge = Some(challenge.to_vec());
                let send = self.send(SEEDLESS, CODE_ENTRY_CHALLENGE, &message)?;
                (
                    Connect::ChallengeSent {
                        challenge,
                        commitment,
                    },
                    send,
                    None,
                )
            }
            Connect::ChallengeSent {
                challenge,
                commitment,
            } => {
                expect(
                    session,
                    SEEDLESS,
                    message_type,
                    CODE_ENTRY_CPACE_TREZOR,
                    "ThpCodeEntryCpaceTrezor",
                )?;
                let trezor_key = decode::<ThpCodeEntryCpaceTrezor>(payload)?
                    .cpace_trezor_public_key
                    .and_then(|key| <[u8; 32]>::try_from(key).ok())
                    .ok_or_else(|| {
                        TrezorError::Protocol("a CPace key that isn't 32 bytes".into())
                    })?;
                (
                    Connect::CodeNeeded {
                        challenge,
                        commitment,
                        trezor_key,
                    },
                    Vec::new(),
                    Some(TrezorEvent::PairingCodeNeeded),
                )
            }
            Connect::TagSent {
                challenge,
                commitment,
                code,
            } => {
                expect(
                    session,
                    SEEDLESS,
                    message_type,
                    CODE_ENTRY_SECRET,
                    "ThpCodeEntrySecret",
                )?;
                let mut secret = decode::<ThpCodeEntrySecret>(payload)?
                    .secret
                    .unwrap_or_default();
                let handshake_hash = self.handshake_hash()?;
                let verified = cpace::verify_code_entry(
                    &code,
                    &handshake_hash,
                    &challenge,
                    &commitment,
                    &secret,
                );
                super::wipe(&mut secret);
                verified?;
                let mut request = ThpCredentialRequest::new();
                request.host_static_public_key =
                    Some(self.credentials.static_public_key().to_vec());
                request.autoconnect = Some(false);
                (
                    Connect::CredentialRequested,
                    self.send(SEEDLESS, CREDENTIAL_REQUEST, &request)?,
                    None,
                )
            }
            Connect::CredentialRequested => {
                expect(
                    session,
                    SEEDLESS,
                    message_type,
                    CREDENTIAL_RESPONSE,
                    "ThpCredentialResponse",
                )?;
                let mut response = decode::<ThpCredentialResponse>(payload)?;
                let trezor_static_public_key = response
                    .trezor_static_public_key
                    .take()
                    .and_then(|key| <[u8; 32]>::try_from(key).ok())
                    .ok_or_else(|| {
                        TrezorError::Protocol("a static key that isn't 32 bytes".into())
                    })?;
                let credential = PairingCredential {
                    host_static_key: self.credentials.static_key().clone(),
                    trezor_static_public_key,
                    credential: Zeroizing::new(response.credential.take().unwrap_or_default()),
                };
                if credential.credential.is_empty()
                    || HostCredentials::new(Some(&credential)).is_err()
                {
                    return Err(TrezorError::Protocol(
                        "an unusable pairing credential".into(),
                    ));
                }
                let send = self.send(SEEDLESS, END_REQUEST, &ThpEndRequest::new())?;
                (
                    Connect::Ending {
                        new_credential: Some(credential),
                    },
                    send,
                    None,
                )
            }
            Connect::Ending { new_credential } => {
                expect(
                    session,
                    SEEDLESS,
                    message_type,
                    END_RESPONSE,
                    "ThpEndResponse",
                )?;
                self.link.end_pairing();
                let send = self.send(SEEDLESS, GET_FEATURES, &GetFeatures::new())?;
                (Connect::GettingFeatures { new_credential }, send, None)
            }
            Connect::GettingFeatures { new_credential } => {
                expect(session, SEEDLESS, message_type, FEATURES, "Features")?;
                let identity = check_features(&decode::<Features>(payload)?)?;
                let mut create = ThpCreateNewSession::new();
                match self.config.passphrase {
                    SessionPassphrase::None => create.passphrase = Some(String::new()),
                    SessionPassphrase::OnDevice => create.on_device = Some(true),
                }
                let send = self.send(WALLET, CREATE_NEW_SESSION, &create)?;
                (
                    Connect::CreatingSession {
                        identity,
                        new_credential,
                    },
                    send,
                    None,
                )
            }
            Connect::CreatingSession {
                identity,
                new_credential,
            } => {
                expect(session, WALLET, message_type, SUCCESS, "Success")?;
                self.become_ready();
                return Ok(Step {
                    send: Vec::new(),
                    event: Some(TrezorEvent::Connected {
                        identity,
                        new_credential,
                    }),
                });
            }
            Connect::Allocating | Connect::Handshaking | Connect::CodeNeeded { .. } => {
                return Err(TrezorError::Protocol(format!(
                    "unexpected message type {message_type} while connecting"
                )));
            }
        };
        self.flow = Flow::Connecting(flow);
        Ok(Step { send, event })
    }

    fn send_host_tag(&mut self, code: &str) -> Result<Vec<Vec<u8>>, TrezorError> {
        let Flow::Connecting(Connect::CodeNeeded {
            challenge,
            commitment,
            trezor_key,
        }) = std::mem::replace(&mut self.flow, Flow::Broken)
        else {
            unreachable!("checked by the caller");
        };
        let tag = cpace::host_tag(code, &self.handshake_hash()?, &trezor_key)?;
        let mut message = ThpCodeEntryCpaceHostTag::new();
        message.cpace_host_public_key = Some(tag.public_key.to_vec());
        message.tag = Some(tag.tag.to_vec());
        let send = self.send(SEEDLESS, CODE_ENTRY_CPACE_HOST_TAG, &message)?;
        self.flow = Flow::Connecting(Connect::TagSent {
            challenge,
            commitment,
            code: Zeroizing::new(code.to_owned()),
        });
        Ok(send)
    }

    /// A prompt on the device. Acknowledge it, which lets the device wait for
    /// the user, or answer `Cancel` if one is pending.
    ///
    /// Acknowledging authorizes nothing: the user decides on the device. So
    /// a prompt is accepted wherever a request is in progress, including ones
    /// a firmware adds where this code expects none.
    fn on_button_request(&mut self, session: u8) -> Result<Step, TrezorError> {
        let prompt = match &self.flow {
            Flow::Connecting(Connect::PairingRequested) => UserPrompt::ApprovePairing,
            Flow::Connecting(Connect::Ending { .. }) => UserPrompt::ConfirmConnection,
            Flow::Connecting(Connect::CreatingSession { .. }) => UserPrompt::Unlock,
            Flow::Connecting(_) | Flow::Xpub(_) | Flow::Signing(_) => UserPrompt::Confirm,
            Flow::Idle | Flow::Ready | Flow::Broken => {
                return Err(TrezorError::Protocol("a prompt nothing asked for".into()));
            }
        };
        if std::mem::take(&mut self.cancel_pending) {
            return Ok(Step {
                send: self.queue(session, CANCEL, Vec::new())?,
                event: None,
            });
        }
        let send = self.queue(session, BUTTON_ACK, Vec::new())?;
        self.on_screen = Some(session);
        Ok(Step {
            send,
            event: Some(TrezorEvent::AwaitingUser(prompt)),
        })
    }

    fn on_failure(&mut self, failure: &Failure) -> TrezorError {
        let message = failure.message.clone().unwrap_or_default();
        let error = match failure.code.and_then(|code| code.enum_value().ok()) {
            Some(FailureType::Failure_ActionCancelled | FailureType::Failure_PinCancelled) => {
                TrezorError::UserCancelled
            }
            Some(FailureType::Failure_Busy) => TrezorError::Busy(message),
            Some(FailureType::Failure_NotInitialized) => TrezorError::NotInitialised,
            _ if matches!(self.flow, Flow::Connecting(Connect::TagSent { .. })) => {
                TrezorError::PairingFailed("the Trezor refused the code; reconnect and pair again")
            }
            code => TrezorError::Failure {
                code: code.map_or(0, |code| code as i32),
                message,
            },
        };
        // The device is back at its home screen; only a connection that was
        // still being set up is lost.
        if matches!(self.flow, Flow::Xpub(_) | Flow::Signing(_)) {
            self.become_ready();
        }
        error
    }

    /// A request, or the connection, is over. A cancel still waiting for a
    /// prompt lapses with it: the next request is not the one it was for.
    fn become_ready(&mut self) {
        self.flow = Flow::Ready;
        self.cancel_pending = false;
    }

    fn after_error(&mut self, error: &TrezorError) {
        let connection_lost = matches!(self.flow, Flow::Connecting(_) | Flow::Broken)
            || matches!(
                error,
                TrezorError::Protocol(_) | TrezorError::Busy(_) | TrezorError::Locked
            );
        if connection_lost {
            self.break_session();
        }
    }

    fn allocate(&mut self, try_to_unlock: bool) -> Vec<Vec<u8>> {
        let (link, packets) = Link::allocate(self.credentials.clone(), try_to_unlock);
        self.link = link;
        self.try_to_unlock = try_to_unlock;
        self.retransmissions = 0;
        self.flow = Flow::Connecting(Connect::Allocating);
        packets
    }

    fn ensure_ready(&self) -> Result<(), TrezorError> {
        match self.flow {
            Flow::Ready => Ok(()),
            Flow::Broken => Err(broken()),
            _ => Err(TrezorError::OutOfOrder),
        }
    }

    fn handshake_hash(&self) -> Result<[u8; 32], TrezorError> {
        self.link.handshake_hash().ok_or_else(broken)
    }

    fn send<M: Message>(
        &mut self,
        session: u8,
        message_type: u16,
        message: &M,
    ) -> Result<Vec<Vec<u8>>, TrezorError> {
        self.queue(session, message_type, encode(message)?)
    }

    /// Sends now, or once the last message is acknowledged.
    fn queue(
        &mut self,
        session: u8,
        message_type: u16,
        payload: Vec<u8>,
    ) -> Result<Vec<Vec<u8>>, TrezorError> {
        if self.link.ready_to_send() {
            self.retransmissions = 0;
            return self.link.send(session, message_type, &payload);
        }
        if self
            .outbox
            .replace((session, message_type, payload))
            .is_some()
        {
            return Err(TrezorError::Protocol(
                "two messages waiting to be sent".into(),
            ));
        }
        Ok(Vec::new())
    }

    fn break_session(&mut self) {
        self.link.close();
        self.flow = Flow::Broken;
        self.outbox = None;
        self.on_screen = None;
    }
}

fn expect(
    session: u8,
    want_session: u8,
    message_type: u16,
    want_type: u16,
    name: &str,
) -> Result<(), TrezorError> {
    if session != want_session || message_type != want_type {
        return Err(TrezorError::Protocol(format!(
            "expected {name} on session {want_session}, got message type {message_type} on session {session}"
        )));
    }
    Ok(())
}
