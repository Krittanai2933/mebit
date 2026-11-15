//! End-to-end tests of [`TrezorSession`] against a fake Safe 7.
//!
//! The fake runs Trezor's own `trezor-thp` **device** role, so the transport,
//! the handshake and the encryption under test are the real thing. On top it
//! plays the device's side the way trezor-firmware does: code-entry pairing
//! (its own CPace half), credentials (an HMAC over
//! `ThpAuthenticatedCredentialData`, as the specification says), `Features`,
//! `GetPublicKey`, and `SignTx`, where it rebuilds the transaction from our
//! answers, re-derives every multisig script, and signs with a software key.
//! A wrong answer thus yields a wrong signature, which the session must refuse.
//!
//! It shares `cpace::generator` with the host, so the pairing tests here can't
//! catch a mistake common to both sides; `cpace`'s own tests pin it to
//! Trezor's vectors, and the hardware checklist runs it against a real Safe 7.

use std::collections::VecDeque;
use std::str::FromStr;

use bitcoin::bip32::{ChildNumber, DerivationPath, Fingerprint, Xpriv, Xpub};
use bitcoin::hashes::{Hash, HashEngine, hmac, sha256};
use bitcoin::key::TapTweak;
use bitcoin::psbt::Psbt;
use bitcoin::secp256k1::{Message, Secp256k1};
use bitcoin::sighash::{EcdsaSighashType, Prevouts, SighashCache};
use bitcoin::{
    Address, Amount, Network, NetworkKind, OutPoint, ScriptBuf, Sequence, Transaction, TxIn, TxOut,
    Txid, Witness, absolute, transaction,
};
use miniscript::psbt::PsbtExt;
use miniscript::{Descriptor, DescriptorPublicKey};
use protobuf::{EnumOrUnknown, Message as _, MessageField};
use sha2::{Digest, Sha256};
use trezor_thp::ChannelIO;
use trezor_thp::channel::buffered::{Buffered, ChannelExt};
use trezor_thp::channel::{PacketInResult, PairingState, Phase, device};
use trezor_thp::credential::CredentialVerifier;
use x25519_dalek::{X25519_BASEPOINT_BYTES, x25519};

use super::identity::tests::{properties, safe7_features, safe7_properties};
use super::protos::MessageType;
use super::protos::bitcoin::{
    Address as AddressResponse, GetAddress, GetPublicKey, InputScriptType,
    MultisigRedeemScriptType, OutputScriptType, PrevInput, PrevOutput, PrevTx, PublicKey, SignTx,
    TxAckInput, TxAckOutput, TxAckPrevInput, TxAckPrevMeta, TxAckPrevOutput, TxInput, TxOutput,
    TxRequest, tx_request,
};
use super::protos::common::failure::FailureType;
use super::protos::common::{ButtonRequest, Failure, HDNodeType, Success};
use super::protos::management::Features;
use super::protos::thp::{
    ThpAuthenticatedCredentialData, ThpCodeEntryChallenge, ThpCodeEntryCommitment,
    ThpCodeEntryCpaceHostTag, ThpCodeEntryCpaceTrezor, ThpCodeEntrySecret, ThpCreateNewSession,
    ThpCredentialMetadata, ThpCredentialRequest, ThpCredentialResponse, ThpEndResponse,
    ThpHandshakeCompletionReqNoisePayload, ThpMessageType, ThpPairingCredential, ThpPairingMethod,
    ThpPairingRequest, ThpPairingRequestApproved, ThpSelectMethod,
};
use super::thp::Crypto;
use super::{
    EcdsaSignatureRejectionReason, PACKET_LEN, PairingCredential, Safe7Identity, SessionConfig,
    SessionPassphrase, TrezorError, TrezorEvent, TrezorSession, UserPrompt, XpubOptions, cpace,
};
use crate::error::VaultCoreError;
use crate::hw::{AddressRequest, psbt_check};
use crate::keys::{generate_master_xpriv, generate_mnemonic, generate_seed};

const NETWORK: Network = Network::Testnet;

// ---------------------------------------------------------------- the device

/// How the fake behaves. `Default` is a healthy, unlocked Safe 7 whose user
/// approves everything.
#[derive(Clone)]
struct Behaviour {
    properties: Vec<u8>,
    features: Features,
    /// Refuses a handshake without `try_to_unlock`, like a locked device.
    locked: bool,
    /// The user declines the pairing request on the device.
    decline_pairing: bool,
    /// A cheating device: reveals a secret it didn't commit to.
    reveal_other_secret: bool,
    /// A cheating device: commits to one secret, then shows the code of and
    /// reveals another, so only the commitment gives it away.
    commit_to_other_secret: bool,
    /// The user declines the transaction on the device.
    decline_signing: bool,
    /// Signs input 0's sighash for every input.
    sign_wrong_message: bool,
    /// Returns bytes that are not a DER signature.
    malformed_signature: bool,
    /// Sends input 0's signature a second time instead of input 1's.
    repeat_first_signature: bool,
    /// Asks for an extra previous-transaction input that doesn't exist.
    ask_beyond_prev_inputs: bool,
    /// Prompts wait for `FakeSafe7::user_confirms` instead of passing at once.
    hold_prompts: bool,
    /// Asks the user to confirm a connection made with a credential.
    confirm_connection: bool,
    /// The BLE chip answers everything with its codec-v1 "busy" packet.
    busy: bool,
    /// Sends every message twice, as after a lost acknowledgement.
    duplicate_messages: bool,
    /// Misses this many channel allocation requests, as a device waking up.
    miss_allocations: u8,
    /// A cheating device: claims this pairing state whatever credential the
    /// host presented, if any.
    claimed_pairing: Option<PairingState>,
    /// How `GetPublicKey` is answered.
    xpub_answer: XpubAnswer,
    wrong_address: bool,
    decline_address: bool,
}

/// A cheating device's answers to `GetPublicKey`.
#[derive(Clone, Copy, PartialEq, Eq, Debug, Default)]
enum XpubAnswer {
    #[default]
    Honest,
    /// The key one level above the path asked for.
    Parent,
    /// The key next to the one asked for, on the path's last level.
    Sibling,
    /// The right key, encoded for mainnet.
    MainnetString,
    /// The right node, with the encoding of another key.
    StringOfAnotherKey,
    /// The right key, without the master fingerprint.
    NoRootFingerprint,
}

impl Default for Behaviour {
    fn default() -> Self {
        Self {
            properties: safe7_properties(),
            features: safe7_features(),
            locked: false,
            decline_pairing: false,
            reveal_other_secret: false,
            commit_to_other_secret: false,
            decline_signing: false,
            sign_wrong_message: false,
            malformed_signature: false,
            repeat_first_signature: false,
            ask_beyond_prev_inputs: false,
            hold_prompts: false,
            confirm_connection: true,
            busy: false,
            duplicate_messages: false,
            miss_allocations: 0,
            claimed_pairing: None,
            xpub_answer: XpubAnswer::Honest,
            wrong_address: false,
            decline_address: false,
        }
    }
}

/// What the device does once the user acts on the prompt it shows.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum PromptFor {
    Pairing,
    Connection,
    Unlock,
    Xpub,
    Address,
    Output,
    Total,
}

struct FakeSafe7 {
    behaviour: Behaviour,
    mux: Buffered<device::Mux<Crypto>>,
    open: Option<Buffered<device::ChannelOpen<Verifier, Crypto>>>,
    channel: Option<Buffered<device::Channel<Crypto>>>,
    channel_id: u16,
    static_key: [u8; 32],
    credential_key: [u8; 32],
    unlocked: bool,
    paired_by_credential: bool,
    host_names: (String, String),
    secret: [u8; 16],
    cpace_key: [u8; 32],
    /// The six digits on the device's screen.
    screen_code: Option<String>,
    master: Xpriv,
    /// A prompt waiting for `ButtonAck` (`false`) or for the user (`true`).
    prompt: Option<(u8, PromptFor, bool)>,
    xpub_request: Option<GetPublicKey>,
    address_request: Option<GetAddress>,
    signing: Option<DeviceSigning>,
    /// Message types the host sent, in order.
    received: Vec<u16>,
    /// Credentials issued, for the autoconnect check.
    issued_autoconnect: Vec<bool>,
}

#[derive(Clone)]
struct Verifier {
    key: [u8; 32],
    /// See [`Behaviour::claimed_pairing`].
    claimed: Option<PairingState>,
}

impl CredentialVerifier for Verifier {
    fn verify(&self, remote_static_pubkey: &[u8], payload: &[u8]) -> PairingState {
        if let Some(claimed) = self.claimed {
            return claimed;
        }
        let Ok(payload) = ThpHandshakeCompletionReqNoisePayload::parse_from_bytes(payload) else {
            return PairingState::Unpaired;
        };
        let Some(credential) = payload.host_pairing_credential else {
            return PairingState::Unpaired;
        };
        let Ok(credential) = ThpPairingCredential::parse_from_bytes(&credential) else {
            return PairingState::Unpaired;
        };
        let metadata = credential.cred_metadata.clone().unwrap_or_default();
        if credential.mac.as_deref() == Some(&mac(&self.key, remote_static_pubkey, &metadata)[..]) {
            if metadata.autoconnect == Some(true) {
                PairingState::PairedAutoconnect
            } else {
                PairingState::Paired
            }
        } else {
            PairingState::Unpaired
        }
    }
}

/// The specification's `IssueCredential` MAC.
fn mac(
    key: &[u8; 32],
    host_static_public_key: &[u8],
    metadata: &ThpCredentialMetadata,
) -> [u8; 32] {
    let mut data = ThpAuthenticatedCredentialData::new();
    data.host_static_public_key = Some(host_static_public_key.to_vec());
    data.cred_metadata = MessageField::some(metadata.clone());
    let mut engine = hmac::HmacEngine::<sha256::Hash>::new(key);
    engine.input(&data.write_to_bytes().unwrap());
    hmac::Hmac::from_engine(engine).to_byte_array()
}

impl FakeSafe7 {
    fn new(behaviour: Behaviour) -> Self {
        let mut mux = device::Mux::<Crypto>::new(&behaviour.properties)
            .unwrap()
            .into_buffered();
        mux.set_packet_len(PACKET_LEN);
        Self {
            behaviour,
            mux,
            open: None,
            channel: None,
            channel_id: 0x1234,
            static_key: [0x77; 32],
            credential_key: [0x55; 32],
            unlocked: false,
            paired_by_credential: false,
            host_names: Default::default(),
            secret: [0x11; 16],
            cpace_key: [0x22; 32],
            screen_code: None,
            master: master_key(1),
            prompt: None,
            xpub_request: None,
            address_request: None,
            signing: None,
            received: Vec::new(),
            issued_autoconnect: Vec::new(),
        }
    }

    /// Takes one packet from the host; returns the packets the device sends.
    fn deliver(&mut self, packet: &[u8]) -> Vec<Vec<u8>> {
        assert_eq!(packet.len(), PACKET_LEN, "every packet is a BLE packet");
        if self.behaviour.busy {
            return vec![busy_packet()];
        }
        if self.behaviour.miss_allocations > 0 && packet[0] == 0x40 {
            // A channel allocation request (control byte 0x40), unheard.
            self.behaviour.miss_allocations -= 1;
            return Vec::new();
        }
        match self.mux.packet_in(packet) {
            PacketInResult::ChannelAllocation => {
                // A new channel replaces the old one, as on the device.
                self.channel = None;
                let mut open = self
                    .mux
                    .channel_alloc(
                        self.channel_id,
                        Verifier {
                            key: self.credential_key,
                            claimed: self.behaviour.claimed_pairing,
                        },
                    )
                    .unwrap()
                    .into_buffered();
                open.set_packet_len(PACKET_LEN);
                self.open = Some(open);
            }
            PacketInResult::Route { channel_id, .. } if channel_id == self.channel_id => {
                if let Some(open) = self.open.as_mut() {
                    match open.packet_in(packet) {
                        PacketInResult::HandshakeKeyRequired { try_to_unlock } => {
                            if self.behaviour.locked && !self.unlocked && !try_to_unlock {
                                open.send_device_locked().unwrap();
                            } else {
                                self.unlocked |= try_to_unlock;
                                open.set_static_key(&self.static_key).unwrap();
                            }
                        }
                        result => assert!(result.check_failed().is_ok()),
                    }
                    if open.handshake_done() {
                        let channel = self
                            .open
                            .take()
                            .unwrap()
                            .map(|open| open.complete())
                            .unwrap();
                        let Phase::PairingCredential {
                            handshake_pairing_state,
                        } = channel.phase()
                        else {
                            panic!("a fresh channel is in its pairing phase");
                        };
                        self.paired_by_credential = handshake_pairing_state.is_paired();
                        self.channel = Some(channel);
                    }
                } else if let Some(channel) = self.channel.as_mut()
                    && channel.packet_in(packet).got_message()
                {
                    let (session, message_type, payload) = channel.message_out().unwrap();
                    self.received.push(message_type);
                    return self.answer(session, message_type, &payload);
                }
            }
            _ => {}
        }
        self.drain()
    }

    /// The user acts on a held prompt.
    fn user_confirms(&mut self) -> Vec<Vec<u8>> {
        let Some((session, prompt, true)) = self.prompt.take() else {
            panic!("no prompt is waiting for the user");
        };
        self.after_prompt(session, prompt)
    }

    fn drain(&mut self) -> Vec<Vec<u8>> {
        fn from<C: ChannelIO>(link: &mut Buffered<C>) -> Vec<Vec<u8>> {
            let mut packets = Vec::new();
            while link.packet_out_ready() {
                packets.push(link.packet_out().unwrap());
            }
            packets
        }
        let mut packets = from(&mut self.mux);
        if let Some(open) = self.open.as_mut() {
            packets.extend(from(open));
        }
        if let Some(channel) = self.channel.as_mut() {
            packets.extend(from(channel));
        }
        packets
    }

    fn send(
        &mut self,
        session: u8,
        message_type: u16,
        message: &impl protobuf::Message,
    ) -> Vec<Vec<u8>> {
        let mut packets = self.drain();
        let channel = self.channel.as_mut().unwrap();
        channel
            .message_in(session, message_type, &message.write_to_bytes().unwrap())
            .unwrap();
        packets.extend(self.drain());
        if self.behaviour.duplicate_messages {
            let copy = packets.last().cloned().unwrap();
            packets.push(copy);
        }
        packets
    }

    fn fail(&mut self, session: u8, code: FailureType, message: &str) -> Vec<Vec<u8>> {
        let mut failure = Failure::new();
        failure.code = Some(EnumOrUnknown::new(code));
        failure.message = Some(message.into());
        self.prompt = None;
        self.signing = None;
        self.send(session, mt(MessageType::MessageType_Failure), &failure)
    }

    fn ask(&mut self, session: u8, prompt: PromptFor) -> Vec<Vec<u8>> {
        self.prompt = Some((session, prompt, false));
        self.send(
            session,
            mt(MessageType::MessageType_ButtonRequest),
            &ButtonRequest::new(),
        )
    }

    fn answer(&mut self, session: u8, message_type: u16, payload: &[u8]) -> Vec<Vec<u8>> {
        if message_type == mt(MessageType::MessageType_Cancel) {
            return if self.prompt.is_some() || self.signing.is_some() {
                self.fail(session, FailureType::Failure_ActionCancelled, "Cancelled")
            } else {
                self.drain()
            };
        }
        if let Some((prompt_session, prompt, false)) = self.prompt {
            assert_eq!(
                message_type,
                mt(MessageType::MessageType_ButtonAck),
                "a prompt waits for ButtonAck"
            );
            assert_eq!(session, prompt_session);
            if self.behaviour.hold_prompts {
                self.prompt = Some((session, prompt, true));
                return self.drain();
            }
            self.prompt = None;
            return self.after_prompt(session, prompt);
        }
        if let Some(signing) = self.signing.as_mut() {
            assert_eq!(message_type, mt(MessageType::MessageType_TxAck));
            let next = signing.on_ack(payload, &self.master, &self.behaviour);
            return self.next_signing_step(session, next);
        }

        match message_type {
            t if t == thp(ThpMessageType::ThpMessageType_ThpPairingRequest) => {
                let request = ThpPairingRequest::parse_from_bytes(payload).unwrap();
                self.host_names = (request.host_name().into(), request.app_name().into());
                self.ask(session, PromptFor::Pairing)
            }
            t if t == thp(ThpMessageType::ThpMessageType_ThpSelectMethod) => {
                let select = ThpSelectMethod::parse_from_bytes(payload).unwrap();
                assert_eq!(
                    select.selected_pairing_method(),
                    ThpPairingMethod::CodeEntry
                );
                let mut commitment = ThpCodeEntryCommitment::new();
                let committed = if self.behaviour.commit_to_other_secret {
                    [0x99; 16]
                } else {
                    self.secret
                };
                commitment.commitment = Some(Sha256::digest(committed).to_vec());
                self.send(
                    session,
                    thp(ThpMessageType::ThpMessageType_ThpCodeEntryCommitment),
                    &commitment,
                )
            }
            t if t == thp(ThpMessageType::ThpMessageType_ThpCodeEntryChallenge) => {
                let challenge = ThpCodeEntryChallenge::parse_from_bytes(payload).unwrap();
                assert_eq!(
                    challenge.challenge().len(),
                    16,
                    "the specification's challenge is 16 bytes"
                );
                let handshake_hash = *self.channel.as_ref().unwrap().handshake_hash();
                let hash = Sha256::new()
                    .chain_update([ThpPairingMethod::CodeEntry as u8])
                    .chain_update(handshake_hash)
                    .chain_update(self.secret)
                    .chain_update(challenge.challenge())
                    .finalize();
                let value = hash
                    .iter()
                    .fold(0u64, |acc, &b| (acc * 256 + u64::from(b)) % 1_000_000);
                let code = format!("{value:06}");
                let generator = cpace::generator(code.as_bytes(), &handshake_hash, b"");
                self.screen_code = Some(code);
                let mut message = ThpCodeEntryCpaceTrezor::new();
                message.cpace_trezor_public_key = Some(x25519(self.cpace_key, generator).to_vec());
                self.send(
                    session,
                    thp(ThpMessageType::ThpMessageType_ThpCodeEntryCpaceTrezor),
                    &message,
                )
            }
            t if t == thp(ThpMessageType::ThpMessageType_ThpCodeEntryCpaceHostTag) => {
                let tag = ThpCodeEntryCpaceHostTag::parse_from_bytes(payload).unwrap();
                let host_key: [u8; 32] = tag.cpace_host_public_key().try_into().unwrap();
                let shared = x25519(self.cpace_key, host_key);
                if tag.tag() != Sha256::digest(shared).as_slice() {
                    return self.fail(
                        session,
                        FailureType::Failure_DataError,
                        "Unexpected Code Entry Tag",
                    );
                }
                let mut secret = ThpCodeEntrySecret::new();
                secret.secret = Some(if self.behaviour.reveal_other_secret {
                    vec![0x99; 16]
                } else {
                    self.secret.to_vec()
                });
                self.send(
                    session,
                    thp(ThpMessageType::ThpMessageType_ThpCodeEntrySecret),
                    &secret,
                )
            }
            t if t == thp(ThpMessageType::ThpMessageType_ThpCredentialRequest) => {
                let request = ThpCredentialRequest::parse_from_bytes(payload).unwrap();
                let autoconnect = request.autoconnect();
                self.issued_autoconnect.push(autoconnect);
                let mut metadata = ThpCredentialMetadata::new();
                metadata.host_name = Some(self.host_names.0.clone());
                metadata.app_name = Some(self.host_names.1.clone());
                metadata.autoconnect = Some(autoconnect);
                let mut credential = ThpPairingCredential::new();
                credential.mac = Some(
                    mac(
                        &self.credential_key,
                        request.host_static_public_key(),
                        &metadata,
                    )
                    .to_vec(),
                );
                credential.cred_metadata = MessageField::some(metadata);
                let mut response = ThpCredentialResponse::new();
                response.trezor_static_public_key =
                    Some(x25519(self.static_key, X25519_BASEPOINT_BYTES).to_vec());
                response.credential = Some(credential.write_to_bytes().unwrap());
                self.send(
                    session,
                    thp(ThpMessageType::ThpMessageType_ThpCredentialResponse),
                    &response,
                )
            }
            t if t == thp(ThpMessageType::ThpMessageType_ThpEndRequest) => {
                if self.paired_by_credential && self.behaviour.confirm_connection {
                    self.ask(session, PromptFor::Connection)
                } else {
                    self.after_prompt(session, PromptFor::Connection)
                }
            }
            t if t == mt(MessageType::MessageType_GetFeatures) => {
                assert_eq!(session, 0, "Features come from the seedless session");
                let features = self.behaviour.features.clone();
                self.send(session, mt(MessageType::MessageType_Features), &features)
            }
            t if t == mt(MessageType::MessageType_ThpCreateNewSession) => {
                let create = ThpCreateNewSession::parse_from_bytes(payload).unwrap();
                assert_eq!(
                    create.passphrase(),
                    "",
                    "no passphrase crosses from the host"
                );
                if self.behaviour.locked && !self.unlocked {
                    self.ask(session, PromptFor::Unlock)
                } else {
                    self.send(
                        session,
                        mt(MessageType::MessageType_Success),
                        &Success::new(),
                    )
                }
            }
            t if t == mt(MessageType::MessageType_GetPublicKey) => {
                let request = GetPublicKey::parse_from_bytes(payload).unwrap();
                let show = request.show_display();
                self.xpub_request = Some(request);
                if show {
                    self.ask(session, PromptFor::Xpub)
                } else {
                    self.after_prompt(session, PromptFor::Xpub)
                }
            }
            t if t == mt(MessageType::MessageType_GetAddress) => {
                let request = GetAddress::parse_from_bytes(payload).unwrap();
                assert_eq!(request.show_display, Some(true));
                assert_eq!(request.coin_name(), "Testnet");
                self.address_request = Some(request);
                self.ask(session, PromptFor::Address)
            }
            t if t == mt(MessageType::MessageType_SignTx) => {
                let sign_tx = SignTx::parse_from_bytes(payload).unwrap();
                assert_eq!(sign_tx.serialize, Some(false));
                self.signing = Some(DeviceSigning::new(sign_tx));
                let next = self.signing.as_mut().unwrap().first();
                self.next_signing_step(session, next)
            }
            other => panic!("the fake doesn't expect message type {other}"),
        }
    }

    fn after_prompt(&mut self, session: u8, prompt: PromptFor) -> Vec<Vec<u8>> {
        match prompt {
            PromptFor::Pairing if self.behaviour.decline_pairing => {
                self.fail(session, FailureType::Failure_ActionCancelled, "Cancelled")
            }
            PromptFor::Pairing => self.send(
                session,
                thp(ThpMessageType::ThpMessageType_ThpPairingRequestApproved),
                &ThpPairingRequestApproved::new(),
            ),
            PromptFor::Connection => self.send(
                session,
                thp(ThpMessageType::ThpMessageType_ThpEndResponse),
                &ThpEndResponse::new(),
            ),
            PromptFor::Unlock => {
                self.unlocked = true;
                self.send(
                    session,
                    mt(MessageType::MessageType_Success),
                    &Success::new(),
                )
            }
            PromptFor::Xpub => {
                let request = self.xpub_request.take().unwrap();
                let answer = public_key(&self.master, &request, self.behaviour.xpub_answer);
                self.send(session, mt(MessageType::MessageType_PublicKey), &answer)
            }
            PromptFor::Address if self.behaviour.decline_address => {
                self.address_request = None;
                self.fail(session, FailureType::Failure_ActionCancelled, "Cancelled")
            }
            PromptFor::Address => {
                let mut request = self.address_request.take().unwrap();
                if self.behaviour.wrong_address {
                    *request.address_n.last_mut().unwrap() += 1;
                }
                let script = device_script(
                    &self.master,
                    request.script_type(),
                    &request.address_n,
                    request.multisig.as_ref(),
                );
                let mut response = AddressResponse::new();
                response.address =
                    Some(Address::from_script(&script, NETWORK).unwrap().to_string());
                self.send(session, mt(MessageType::MessageType_Address), &response)
            }
            PromptFor::Output | PromptFor::Total if self.behaviour.decline_signing => {
                self.fail(session, FailureType::Failure_ActionCancelled, "Cancelled")
            }
            PromptFor::Output | PromptFor::Total => {
                let next = self.signing.as_mut().unwrap().after_prompt();
                self.next_signing_step(session, next)
            }
        }
    }

    fn next_signing_step(&mut self, session: u8, step: SignStep) -> Vec<Vec<u8>> {
        match step {
            SignStep::Request(request) => {
                if request.request_type() == tx_request::RequestType::TXFINISHED {
                    self.signing = None;
                }
                self.send(session, mt(MessageType::MessageType_TxRequest), &request)
            }
            SignStep::Prompt(prompt) => self.ask(session, prompt),
            SignStep::Fail(why) => self.fail(session, FailureType::Failure_DataError, why),
        }
    }
}

fn public_key(master: &Xpriv, request: &GetPublicKey, how: XpubAnswer) -> PublicKey {
    let secp = Secp256k1::new();
    let asked: Vec<ChildNumber> = request
        .address_n
        .iter()
        .map(|&n| ChildNumber::from(n))
        .collect();
    let at =
        |path: &[ChildNumber]| Xpub::from_priv(&secp, &master.derive_priv(&secp, &path).unwrap());
    let sibling = || {
        let (last, parent) = asked.split_last().unwrap();
        let next = match *last {
            ChildNumber::Hardened { index } => ChildNumber::Hardened { index: index + 1 },
            ChildNumber::Normal { index } => ChildNumber::Normal { index: index + 1 },
        };
        at(&[parent, &[next][..]].concat())
    };
    let xpub = match how {
        XpubAnswer::Parent => at(&asked[..asked.len() - 1]),
        XpubAnswer::Sibling => sibling(),
        _ => at(&asked),
    };
    let shown = match how {
        XpubAnswer::MainnetString => Xpub {
            network: NetworkKind::Main,
            ..xpub
        },
        XpubAnswer::StringOfAnotherKey => sibling(),
        _ => xpub,
    };
    let mut node = HDNodeType::new();
    node.depth = Some(u32::from(xpub.depth));
    node.fingerprint = Some(u32::from_be_bytes(xpub.parent_fingerprint.to_bytes()));
    node.child_num = Some(u32::from(xpub.child_number));
    node.chain_code = Some(xpub.chain_code.to_bytes().to_vec());
    node.public_key = Some(xpub.public_key.serialize().to_vec());
    let mut answer = PublicKey::new();
    answer.node = MessageField::some(node);
    answer.xpub = Some(if request.ignore_xpub_magic() {
        shown.to_string()
    } else {
        format!("slip132:{shown}")
    });
    answer.root_fingerprint = (how != XpubAnswer::NoRootFingerprint)
        .then(|| u32::from_be_bytes(master.fingerprint(&secp).to_bytes()));
    answer
}

enum SignStep {
    Request(TxRequest),
    Prompt(PromptFor),
    Fail(&'static str),
}

/// The device's side of `SignTx`, in trezor-firmware's order: inputs,
/// outputs (confirming external ones), the total, each input and its previous transaction
/// (hashed and checked), then each input again to sign it.
struct DeviceSigning {
    sign_tx: SignTx,
    inputs: Vec<TxInput>,
    outputs: Vec<TxOutput>,
    stage: Stage,
    signature: Option<(u32, Vec<u8>)>,
}

#[derive(Clone)]
enum Stage {
    Input(u32),
    Output(u32),
    Verify(u32),
    Prev {
        input: u32,
        meta: Option<PrevTx>,
        inputs: Vec<PrevInput>,
        outputs: Vec<PrevOutput>,
    },
    Sign(u32),
}

impl DeviceSigning {
    fn new(sign_tx: SignTx) -> Self {
        Self {
            sign_tx,
            inputs: Vec::new(),
            outputs: Vec::new(),
            stage: Stage::Input(0),
            signature: None,
        }
    }

    fn first(&mut self) -> SignStep {
        self.request(tx_request::RequestType::TXINPUT, 0, None)
    }

    fn request(
        &mut self,
        kind: tx_request::RequestType,
        index: u32,
        tx_hash: Option<Vec<u8>>,
    ) -> SignStep {
        let mut request = TxRequest::new();
        request.request_type = Some(EnumOrUnknown::new(kind));
        let mut details = tx_request::TxRequestDetailsType::new();
        details.request_index = Some(index);
        details.tx_hash = tx_hash;
        request.details = MessageField::some(details);
        if let Some((index, signature)) = self.signature.take() {
            let mut serialized = tx_request::TxRequestSerializedType::new();
            serialized.signature_index = Some(index);
            serialized.signature = Some(signature);
            request.serialized = MessageField::some(serialized);
        }
        SignStep::Request(request)
    }

    fn on_ack(&mut self, payload: &[u8], master: &Xpriv, behaviour: &Behaviour) -> SignStep {
        use tx_request::RequestType::*;
        match self.stage.clone() {
            Stage::Input(i) => {
                let ack = TxAckInput::parse_from_bytes(payload).unwrap();
                self.inputs.push(ack.tx.input.clone().unwrap());
                if i + 1 < self.sign_tx.inputs_count() {
                    self.stage = Stage::Input(i + 1);
                    self.request(TXINPUT, i + 1, None)
                } else {
                    self.stage = Stage::Output(0);
                    self.request(TXOUTPUT, 0, None)
                }
            }
            Stage::Output(_) => {
                let ack = TxAckOutput::parse_from_bytes(payload).unwrap();
                let output = ack.tx.output.clone().unwrap();
                let external = output.address_n.is_empty();
                self.outputs.push(output);
                if external {
                    SignStep::Prompt(PromptFor::Output)
                } else {
                    self.after_output()
                }
            }
            Stage::Verify(input) => {
                let ack = TxAckInput::parse_from_bytes(payload).unwrap();
                if ack.tx.input.as_ref() != Some(&self.inputs[input as usize]) {
                    return SignStep::Fail("Transaction has changed during verification");
                }
                let hash = self.inputs[input as usize].prev_hash().to_vec();
                self.stage = Stage::Prev {
                    input,
                    meta: None,
                    inputs: Vec::new(),
                    outputs: Vec::new(),
                };
                self.request(TXMETA, 0, Some(hash))
            }
            Stage::Prev {
                input,
                mut meta,
                mut inputs,
                mut outputs,
            } => {
                let hash = self.inputs[input as usize].prev_hash().to_vec();
                if let Some(meta) = &meta {
                    if inputs.len() < meta.inputs_count() as usize {
                        inputs.push(
                            TxAckPrevInput::parse_from_bytes(payload)
                                .unwrap()
                                .tx
                                .input
                                .clone()
                                .unwrap(),
                        );
                    } else {
                        outputs.push(
                            TxAckPrevOutput::parse_from_bytes(payload)
                                .unwrap()
                                .tx
                                .output
                                .clone()
                                .unwrap(),
                        );
                    }
                } else {
                    meta = Some(
                        TxAckPrevMeta::parse_from_bytes(payload)
                            .unwrap()
                            .tx
                            .unwrap(),
                    );
                }
                let meta_ref = meta.clone().unwrap();
                let want_inputs =
                    meta_ref.inputs_count() + u32::from(behaviour.ask_beyond_prev_inputs);
                let next = if (inputs.len() as u32) < want_inputs {
                    self.request(TXINPUT, inputs.len() as u32, Some(hash))
                } else if (outputs.len() as u32) < meta_ref.outputs_count() {
                    self.request(TXOUTPUT, outputs.len() as u32, Some(hash))
                } else {
                    if let Err(why) = check_previous(
                        &self.inputs[input as usize],
                        &meta_ref,
                        &inputs,
                        &outputs,
                        master,
                    ) {
                        return SignStep::Fail(why);
                    }
                    return self.next_previous(input + 1);
                };
                self.stage = Stage::Prev {
                    input,
                    meta,
                    inputs,
                    outputs,
                };
                next
            }
            Stage::Sign(i) => {
                let ack = TxAckInput::parse_from_bytes(payload).unwrap();
                if ack.tx.input.as_ref() != Some(&self.inputs[i as usize]) {
                    return SignStep::Fail("Transaction has changed during signing");
                }
                let signed_index = if behaviour.sign_wrong_message {
                    0
                } else {
                    i as usize
                };
                let reported_index = if behaviour.repeat_first_signature && i == 1 {
                    0
                } else {
                    i
                };
                if behaviour.malformed_signature {
                    self.signature = Some((reported_index, vec![0x30, 0x01, 0x00]));
                } else {
                    match self.sign(i as usize, signed_index, master) {
                        Ok(signature) => self.signature = Some((reported_index, signature)),
                        Err(why) => return SignStep::Fail(why),
                    }
                }
                if i + 1 < self.sign_tx.inputs_count() {
                    self.stage = Stage::Sign(i + 1);
                    self.request(TXINPUT, i + 1, None)
                } else {
                    self.request(TXFINISHED, 0, None)
                }
            }
        }
    }

    fn after_prompt(&mut self) -> SignStep {
        match self.stage {
            Stage::Output(j) if (j as usize) < self.outputs.len() => self.after_output(),
            // The total was confirmed: stream the previous transactions.
            _ => self.next_previous(0),
        }
    }

    fn after_output(&mut self) -> SignStep {
        let Stage::Output(j) = self.stage else {
            unreachable!()
        };
        if j + 1 < self.sign_tx.outputs_count() {
            self.stage = Stage::Output(j + 1);
            self.request(tx_request::RequestType::TXOUTPUT, j + 1, None)
        } else {
            self.stage = Stage::Output(u32::MAX);
            SignStep::Prompt(PromptFor::Total)
        }
    }

    fn next_previous(&mut self, input: u32) -> SignStep {
        if input < self.sign_tx.inputs_count() {
            self.stage = Stage::Verify(input);
            self.request(tx_request::RequestType::TXINPUT, input, None)
        } else {
            self.stage = Stage::Sign(0);
            self.request(tx_request::RequestType::TXINPUT, 0, None)
        }
    }

    /// Rebuilds the transaction from the answers and signs input `index`
    /// over the sighash of input `message_of`.
    fn sign(
        &self,
        index: usize,
        message_of: usize,
        master: &Xpriv,
    ) -> Result<Vec<u8>, &'static str> {
        let secp = Secp256k1::new();
        let tx = self.transaction(master)?;
        let input = &self.inputs[message_of];
        let path: DerivationPath = self.inputs[index]
            .address_n
            .iter()
            .map(|&n| ChildNumber::from(n))
            .collect();
        let key = master.derive_priv(&secp, &path).unwrap().private_key;
        if let Some(multisig) = self.inputs[index].multisig.as_ref()
            && !multisig_keys(multisig)
                .iter()
                .any(|k| *k == key.public_key(&secp))
        {
            return Err("Pubkey not found in multisig script");
        }
        let prevouts: Vec<TxOut> = self
            .inputs
            .iter()
            .map(|input| TxOut {
                value: Amount::from_sat(input.amount()),
                script_pubkey: device_script(
                    master,
                    input.script_type(),
                    &input.address_n,
                    input.multisig.as_ref(),
                ),
            })
            .collect();
        let script = &prevouts[message_of].script_pubkey;
        let mut cache = SighashCache::new(&tx);
        if input.script_type() == InputScriptType::SPENDTAPROOT {
            let sighash = cache
                .taproot_key_spend_signature_hash(
                    message_of,
                    &Prevouts::All(&prevouts),
                    bitcoin::TapSighashType::Default,
                )
                .map_err(|_| "sighash")?;
            let tweaked =
                bitcoin::secp256k1::Keypair::from_secret_key(&secp, &key).tap_tweak(&secp, None);
            let message = Message::from_digest(sighash.to_byte_array());
            return Ok(secp
                .sign_schnorr_no_aux_rand(&message, &tweaked.to_keypair())
                .serialize()
                .to_vec());
        }
        let message = if let Some(multisig) = input.multisig.as_ref() {
            let script = multisig_script(multisig);
            Message::from_digest(
                cache
                    .p2wsh_signature_hash(
                        message_of,
                        &script,
                        Amount::from_sat(input.amount()),
                        EcdsaSighashType::All,
                    )
                    .map_err(|_| "sighash")?
                    .to_byte_array(),
            )
        } else if input.script_type() == InputScriptType::SPENDADDRESS {
            Message::from_digest(
                cache
                    .legacy_signature_hash(message_of, script, EcdsaSighashType::All.to_u32())
                    .map_err(|_| "sighash")?
                    .to_byte_array(),
            )
        } else {
            let script = device_script(
                master,
                InputScriptType::SPENDWITNESS,
                &input.address_n,
                None,
            );
            Message::from_digest(
                cache
                    .p2wpkh_signature_hash(
                        message_of,
                        &script,
                        Amount::from_sat(input.amount()),
                        EcdsaSighashType::All,
                    )
                    .map_err(|_| "sighash")?
                    .to_byte_array(),
            )
        };
        Ok(secp.sign_ecdsa(&message, &key).serialize_der().to_vec())
    }

    fn transaction(&self, master: &Xpriv) -> Result<Transaction, &'static str> {
        let input = self
            .inputs
            .iter()
            .map(|input| {
                let mut txid = <[u8; 32]>::try_from(input.prev_hash()).unwrap();
                txid.reverse();
                TxIn {
                    previous_output: OutPoint {
                        txid: Txid::from_byte_array(txid),
                        vout: input.prev_index(),
                    },
                    script_sig: ScriptBuf::new(),
                    sequence: Sequence(input.sequence()),
                    witness: Witness::new(),
                }
            })
            .collect();
        let output = self
            .outputs
            .iter()
            .map(|output| {
                let script_pubkey = if output.address_n.is_empty() {
                    Address::from_str(output.address())
                        .map_err(|_| "bad address")?
                        .require_network(NETWORK)
                        .map_err(|_| "wrong network")?
                        .script_pubkey()
                } else {
                    let script_type = match output.script_type() {
                        OutputScriptType::PAYTOADDRESS => InputScriptType::SPENDADDRESS,
                        OutputScriptType::PAYTOWITNESS => InputScriptType::SPENDWITNESS,
                        OutputScriptType::PAYTOP2SHWITNESS => InputScriptType::SPENDP2SHWITNESS,
                        OutputScriptType::PAYTOTAPROOT => InputScriptType::SPENDTAPROOT,
                        _ => return Err("unsupported output"),
                    };
                    device_script(
                        master,
                        script_type,
                        &output.address_n,
                        output.multisig.as_ref(),
                    )
                };
                Ok(TxOut {
                    value: Amount::from_sat(output.amount()),
                    script_pubkey,
                })
            })
            .collect::<Result<_, _>>()?;
        Ok(Transaction {
            version: transaction::Version(self.sign_tx.version() as i32),
            lock_time: absolute::LockTime::from_consensus(self.sign_tx.lock_time()),
            input,
            output,
        })
    }
}

/// The firmware hashes the streamed previous transaction and compares it
/// with the input's `prev_hash`, and checks the amount it spends.
fn check_previous(
    input: &TxInput,
    meta: &PrevTx,
    inputs: &[PrevInput],
    outputs: &[PrevOutput],
    master: &Xpriv,
) -> Result<(), &'static str> {
    let tx = Transaction {
        version: transaction::Version(meta.version() as i32),
        lock_time: absolute::LockTime::from_consensus(meta.lock_time()),
        input: inputs
            .iter()
            .map(|input| {
                let mut txid = <[u8; 32]>::try_from(input.prev_hash()).unwrap();
                txid.reverse();
                TxIn {
                    previous_output: OutPoint {
                        txid: Txid::from_byte_array(txid),
                        vout: input.prev_index(),
                    },
                    script_sig: ScriptBuf::from_bytes(input.script_sig().to_vec()),
                    sequence: Sequence(input.sequence()),
                    witness: Witness::new(),
                }
            })
            .collect(),
        output: outputs
            .iter()
            .map(|output| TxOut {
                value: Amount::from_sat(output.amount()),
                script_pubkey: ScriptBuf::from_bytes(output.script_pubkey().to_vec()),
            })
            .collect(),
    };
    let mut txid = tx.compute_txid().to_byte_array();
    txid.reverse();
    if txid.as_slice() != input.prev_hash() {
        return Err("Encountered invalid prev_hash");
    }
    let spent = tx
        .output
        .get(input.prev_index() as usize)
        .ok_or("no such previous output")?;
    if spent.value.to_sat() != input.amount() {
        return Err("Invalid amount specified");
    }
    if spent.script_pubkey
        != device_script(
            master,
            input.script_type(),
            &input.address_n,
            input.multisig.as_ref(),
        )
    {
        return Err("Input does not match scriptPubKey");
    }
    Ok(())
}

fn device_script(
    master: &Xpriv,
    script_type: InputScriptType,
    path: &[u32],
    multisig: Option<&MultisigRedeemScriptType>,
) -> ScriptBuf {
    if let Some(multisig) = multisig {
        assert_eq!(script_type, InputScriptType::SPENDWITNESS);
        return multisig_script(multisig).to_p2wsh();
    }
    let secp = Secp256k1::new();
    let path: DerivationPath = path.iter().map(|child| ChildNumber::from(*child)).collect();
    let public = master
        .derive_priv(&secp, &path)
        .unwrap()
        .private_key
        .public_key(&secp);
    let public = bitcoin::CompressedPublicKey(public);
    let address = match script_type {
        InputScriptType::SPENDADDRESS => Address::p2pkh(public, NETWORK),
        InputScriptType::SPENDP2SHWITNESS => Address::p2shwpkh(&public, NETWORK),
        InputScriptType::SPENDWITNESS => Address::p2wpkh(&public, NETWORK),
        InputScriptType::SPENDTAPROOT => {
            Address::p2tr(&secp, public.0.x_only_public_key().0, None, NETWORK)
        }
        other => panic!("unsupported device script {other:?}"),
    };
    address.script_pubkey()
}

/// What the firmware derives from `MultisigRedeemScriptType`: each node at
/// `address_n`, sorted (LEXICOGRAPHIC), in `OP_m … OP_n OP_CHECKMULTISIG`.
fn multisig_keys(multisig: &MultisigRedeemScriptType) -> Vec<bitcoin::secp256k1::PublicKey> {
    let secp = Secp256k1::verification_only();
    let suffix: DerivationPath = multisig
        .address_n
        .iter()
        .map(|&n| ChildNumber::from(n))
        .collect();
    let mut keys: Vec<_> = multisig
        .nodes
        .iter()
        .map(|node| {
            let xpub = Xpub {
                network: NETWORK.into(),
                depth: node.depth() as u8,
                parent_fingerprint: Fingerprint::from(node.fingerprint().to_be_bytes()),
                child_number: ChildNumber::from(node.child_num()),
                public_key: bitcoin::secp256k1::PublicKey::from_slice(node.public_key()).unwrap(),
                chain_code: <[u8; 32]>::try_from(node.chain_code()).unwrap().into(),
            };
            xpub.derive_pub(&secp, &suffix).unwrap().public_key
        })
        .collect();
    keys.sort_by_key(|key| key.serialize());
    keys
}

fn multisig_script(multisig: &MultisigRedeemScriptType) -> ScriptBuf {
    let keys = multisig_keys(multisig);
    let builder = keys.iter().fold(
        bitcoin::script::Builder::new().push_int(i64::from(multisig.m())),
        |builder, key| builder.push_key(&bitcoin::PublicKey::new(*key)),
    );
    builder
        .push_int(keys.len() as i64)
        .push_opcode(bitcoin::opcodes::all::OP_CHECKMULTISIG)
        .into_script()
}

/// `service_send_busy()` in the Safe 7's nRF firmware, padded to a packet.
fn busy_packet() -> Vec<u8> {
    let mut packet = vec![
        0x3f, 0x23, 0x23, 0x00, 0x03, 0x00, 0x00, 0x00, 0x19, 0x08, 0x0f, 0x12, 0x15, 0x44, 0x65,
        0x76, 0x69, 0x63, 0x65, 0x20, 0x6c, 0x6f, 0x63, 0x6b, 0x65, 0x64, 0x20, 0x6f, 0x72, 0x20,
        0x62, 0x75, 0x73, 0x79,
    ];
    packet.resize(PACKET_LEN, 0);
    packet
}

fn mt(message_type: MessageType) -> u16 {
    message_type as u16
}

fn thp(message_type: ThpMessageType) -> u16 {
    message_type as u16
}

// ---------------------------------------------------------------- the wiring

fn master_key(entropy: u8) -> Xpriv {
    let mnemonic = generate_mnemonic(&[entropy; 32]).unwrap();
    generate_master_xpriv(NETWORK, &generate_seed(&mnemonic, "")).unwrap()
}

fn config() -> SessionConfig {
    SessionConfig {
        network: NETWORK,
        host_name: "test host".into(),
        app_name: "mebit".into(),
        passphrase: SessionPassphrase::None,
    }
}

/// Moves packets both ways until nothing is in flight. Types the code from
/// the device's screen when asked, as a user would.
struct Wire {
    host: TrezorSession,
    device: FakeSafe7,
    to_device: VecDeque<Vec<u8>>,
    events: Vec<TrezorEvent>,
    /// Packets the host took since the last event, such as acknowledgements.
    packets_since_event: usize,
    type_code: bool,
}

impl Wire {
    fn new(behaviour: Behaviour, credential: Option<&PairingCredential>) -> Self {
        Self::with_config(behaviour, config(), credential)
    }

    fn with_config(
        behaviour: Behaviour,
        config: SessionConfig,
        credential: Option<&PairingCredential>,
    ) -> Self {
        Self {
            host: TrezorSession::new(config, credential).unwrap(),
            device: FakeSafe7::new(behaviour),
            to_device: VecDeque::new(),
            events: Vec::new(),
            packets_since_event: 0,
            type_code: true,
        }
    }

    /// Moves packets until the host reports an event matching `wanted`.
    fn run_until(&mut self, wanted: impl Fn(&TrezorEvent) -> bool) -> Result<(), VaultCoreError> {
        while !self.events.iter().any(&wanted) {
            let packet = self.to_device.pop_front().expect("the event never came");
            for reply in self.device.deliver(&packet) {
                self.take(reply)?;
            }
        }
        Ok(())
    }

    fn run(&mut self) -> Result<(), VaultCoreError> {
        while let Some(packet) = self.to_device.pop_front() {
            for reply in self.device.deliver(&packet) {
                self.take(reply)?;
            }
        }
        Ok(())
    }

    fn take(&mut self, packet: Vec<u8>) -> Result<(), VaultCoreError> {
        assert_eq!(packet.len(), PACKET_LEN);
        self.packets_since_event += 1;
        let step = self.host.receive(&packet)?;
        // As a driver must: write the packets even when the request failed.
        self.to_device.extend(step.send);
        match step.event {
            Some(TrezorEvent::Failed(error)) => return Err(error.into()),
            Some(event) => {
                if matches!(event, TrezorEvent::PairingCodeNeeded) && self.type_code {
                    let code = self
                        .device
                        .screen_code
                        .clone()
                        .expect("the device shows a code");
                    self.to_device.extend(self.host.pairing_code(&code)?);
                }
                self.events.push(event);
                self.packets_since_event = 0;
            }
            None => {}
        }
        Ok(())
    }

    fn connect(&mut self) -> Result<(Safe7Identity, Option<PairingCredential>), VaultCoreError> {
        let start = self.host.start()?;
        self.to_device.extend(start);
        self.run()?;
        match self
            .events
            .iter()
            .rev()
            .find(|e| matches!(e, TrezorEvent::Connected { .. }))
        {
            Some(TrezorEvent::Connected {
                identity,
                new_credential,
            }) => Ok((identity.clone(), new_credential.clone())),
            _ => panic!("not connected; events: {:?}", self.events),
        }
    }

    fn user_confirms(&mut self) -> Result<(), VaultCoreError> {
        for packet in self.device.user_confirms() {
            self.take(packet)?;
        }
        self.run()
    }
}

/// Connected, with the user approving the pairing at once; `behaviour`
/// applies from then on.
fn connected(behaviour: Behaviour) -> Wire {
    let hold_prompts = behaviour.hold_prompts;
    let mut wire = Wire::new(
        Behaviour {
            hold_prompts: false,
            ..behaviour
        },
        None,
    );
    wire.connect().unwrap();
    wire.device.behaviour.hold_prompts = hold_prompts;
    wire.events.clear();
    wire
}

fn trezor_error(result: Result<impl std::fmt::Debug, VaultCoreError>) -> TrezorError {
    match result {
        Err(VaultCoreError::Trezor(error)) => error,
        other => panic!("expected a Trezor error, got {other:?}"),
    }
}

// ---------------------------------------------------------------- connecting

#[test]
fn pairs_then_reconnects_with_the_credential() {
    let mut wire = Wire::new(Behaviour::default(), None);
    let (identity, credential) = wire.connect().unwrap();
    assert_eq!(identity.internal_model, "T3W1");
    assert_eq!(identity.model, "Safe 7");
    assert!(
        wire.events
            .iter()
            .any(|e| matches!(e, TrezorEvent::PairingCodeNeeded))
    );
    assert!(
        wire.events
            .iter()
            .any(|e| matches!(e, TrezorEvent::AwaitingUser(UserPrompt::ApprovePairing)))
    );
    assert_eq!(
        wire.device.issued_autoconnect,
        [false],
        "never an autoconnect credential"
    );
    assert!(wire.host.is_ready());

    // The credential survives storage, and the next connection skips the code
    // but still has the user confirm on the device.
    let credential = PairingCredential::from_bytes(&credential.unwrap().to_bytes()).unwrap();
    let mut again = Wire::new(Behaviour::default(), Some(&credential));
    again.device.credential_key = wire.device.credential_key;
    again.device.static_key = wire.device.static_key;
    let (_, none) = again.connect().unwrap();
    assert!(none.is_none());
    assert!(
        !again
            .events
            .iter()
            .any(|e| matches!(e, TrezorEvent::PairingCodeNeeded))
    );
    assert!(
        again
            .events
            .iter()
            .any(|e| matches!(e, TrezorEvent::AwaitingUser(UserPrompt::ConfirmConnection)))
    );
    assert!(
        !again
            .device
            .received
            .contains(&thp(ThpMessageType::ThpMessageType_ThpPairingRequest))
    );
}

#[test]
fn a_credential_another_device_issued_falls_back_to_pairing() {
    let mut wire = Wire::new(Behaviour::default(), None);
    let (_, credential) = wire.connect().unwrap();

    let mut other = Wire::new(Behaviour::default(), credential.as_ref());
    other.device.static_key = [0x78; 32]; // another Safe 7
    let (_, fresh) = other.connect().unwrap();
    assert!(fresh.is_some(), "paired afresh, with a new credential");
    assert!(
        other
            .events
            .iter()
            .any(|e| matches!(e, TrezorEvent::PairingCodeNeeded))
    );
}

/// A Safe 7 that no longer accepts the credential we present (it was wiped,
/// or dropped this host) says "unpaired", and pairing starts afresh.
#[test]
fn a_credential_the_safe7_no_longer_accepts_leads_to_pairing() {
    let mut first = Wire::new(Behaviour::default(), None);
    let (_, credential) = first.connect().unwrap();

    let mut again = Wire::new(Behaviour::default(), credential.as_ref());
    again.device.static_key = first.device.static_key;
    again.device.credential_key = [0x56; 32]; // forgot what it issued
    let (_, fresh) = again.connect().unwrap();
    assert!(fresh.is_some());
    assert!(
        again
            .events
            .iter()
            .any(|e| matches!(e, TrezorEvent::PairingCodeNeeded))
    );
}

/// The specification's host state HH3: a device may only say it knows this
/// host if the handshake carried a credential it issued. A peer claiming a
/// pairing anyway, either kind, is refused before anything reaches the user.
#[test]
fn a_claimed_pairing_without_our_credential_is_refused() {
    for claimed in [PairingState::Paired, PairingState::PairedAutoconnect] {
        let state = u8::from(claimed);
        let mut wire = Wire::new(
            Behaviour {
                claimed_pairing: Some(claimed),
                confirm_connection: false,
                ..Behaviour::default()
            },
            None,
        );
        assert!(
            matches!(trezor_error(wire.connect()), TrezorError::Protocol(_)),
            "state {state}"
        );
        assert!(
            wire.events.is_empty(),
            "state {state}: nothing for the user"
        );
        assert!(
            wire.device.received.is_empty(),
            "state {state}: no message after the handshake"
        );
        assert!(matches!(
            trezor_error(wire.host.request_xpub(&account(), XpubOptions::default())),
            TrezorError::Protocol(_)
        ));
    }
}

/// A peer without the paired Safe 7's key can't pass for it by claiming the
/// pairing: our credential never matched it, so it never went out.
#[test]
fn an_impostor_cannot_pass_for_the_paired_safe7() {
    let mut first = Wire::new(Behaviour::default(), None);
    let (_, credential) = first.connect().unwrap();

    let mut impostor = Wire::new(
        Behaviour {
            claimed_pairing: Some(PairingState::Paired),
            confirm_connection: false,
            ..Behaviour::default()
        },
        credential.as_ref(),
    );
    impostor.device.static_key = [0x78; 32];
    assert!(matches!(
        trezor_error(impostor.connect()),
        TrezorError::Protocol(_)
    ));
    assert!(impostor.device.received.is_empty());
}

#[test]
fn gate1_refuses_other_models_before_any_prompt() {
    for internal_model in ["T3T2", "T3T1", "T2B1", "T3B1", "T2T1", "T1B1", "D003"] {
        let behaviour = Behaviour {
            properties: properties(internal_model, 2, 0, &[ThpPairingMethod::CodeEntry]),
            ..Behaviour::default()
        };
        let mut wire = Wire::new(behaviour, None);
        let error = trezor_error(wire.connect());
        assert!(
            matches!(error, TrezorError::UnsupportedModel(_)),
            "{internal_model}: {error}"
        );
        assert!(
            wire.device.received.is_empty(),
            "{internal_model}: no message after allocation"
        );
        assert!(
            wire.events.is_empty(),
            "{internal_model}: nothing asked of the user"
        );
        assert!(
            wire.device
                .open
                .as_ref()
                .is_none_or(|open| !open.handshake_done())
        );
    }
}

#[test]
fn gate2_refuses_features_of_another_model() {
    let mut features = safe7_features();
    features.internal_model = Some("T3T2".into());
    features.model = Some("T3T2".into());
    let mut wire = Wire::new(
        Behaviour {
            features,
            ..Behaviour::default()
        },
        None,
    );
    assert!(matches!(
        trezor_error(wire.connect()),
        TrezorError::UnsupportedModel(_)
    ));
    assert!(
        !wire
            .device
            .received
            .contains(&mt(MessageType::MessageType_ThpCreateNewSession))
    );
    assert!(matches!(
        trezor_error(wire.host.request_xpub(&account(), XpubOptions::default())),
        TrezorError::Protocol(_)
    ));
}

#[test]
fn a_locked_device_is_retried_with_unlock() {
    let mut wire = Wire::new(
        Behaviour {
            locked: true,
            ..Behaviour::default()
        },
        None,
    );
    let start = wire.host.start().unwrap();
    wire.to_device.extend(start);
    wire.run_until(|e| matches!(e, TrezorEvent::AwaitingUser(UserPrompt::Unlock)))
        .unwrap();
    // The device may now wait minutes for its PIN, whatever it acknowledges.
    assert!(wire.host.awaiting_user());
    wire.run().unwrap();
    assert!(
        wire.events
            .iter()
            .any(|e| matches!(e, TrezorEvent::Connected { .. }))
    );
    assert!(!wire.host.awaiting_user());
}

#[test]
fn declined_pairing_ends_the_connection() {
    let mut wire = Wire::new(
        Behaviour {
            decline_pairing: true,
            ..Behaviour::default()
        },
        None,
    );
    assert!(matches!(
        trezor_error(wire.connect()),
        TrezorError::UserCancelled
    ));
    assert!(matches!(
        trezor_error(wire.host.request_xpub(&account(), XpubOptions::default())),
        TrezorError::Protocol(_)
    ));
}

#[test]
fn a_device_committing_to_another_secret_is_refused() {
    let mut wire = Wire::new(
        Behaviour {
            commit_to_other_secret: true,
            ..Behaviour::default()
        },
        None,
    );
    match trezor_error(wire.connect()) {
        TrezorError::PairingFailed(why) => assert!(why.contains("commitment"), "{why}"),
        other => panic!("{other}"),
    }
}

#[test]
fn a_device_revealing_another_secret_is_refused() {
    let mut wire = Wire::new(
        Behaviour {
            reveal_other_secret: true,
            ..Behaviour::default()
        },
        None,
    );
    assert!(matches!(
        trezor_error(wire.connect()),
        TrezorError::PairingFailed(_)
    ));
    assert!(
        !wire
            .device
            .received
            .contains(&thp(ThpMessageType::ThpMessageType_ThpCredentialRequest))
    );
}

#[test]
fn a_wrong_code_is_refused_by_the_device() {
    let mut wire = Wire::new(Behaviour::default(), None);
    wire.type_code = false;
    let start = wire.host.start().unwrap();
    wire.to_device.extend(start);
    wire.run().unwrap();
    assert!(matches!(
        wire.events.last(),
        Some(TrezorEvent::PairingCodeNeeded)
    ));

    // Malformed codes are refused locally, and the user may retype.
    assert!(matches!(
        trezor_error(wire.host.pairing_code("12345")),
        TrezorError::PairingCodeInvalid
    ));
    assert!(matches!(
        trezor_error(wire.host.pairing_code("1234567")),
        TrezorError::PairingCodeInvalid
    ));

    let shown = wire.device.screen_code.clone().unwrap();
    let wrong = if shown == "000000" {
        "000001"
    } else {
        "000000"
    };
    let packets = wire.host.pairing_code(wrong).unwrap();
    wire.to_device.extend(packets);
    assert!(matches!(
        trezor_error(wire.run()),
        TrezorError::PairingFailed(_)
    ));
}

#[test]
fn a_malformed_code_leaves_the_pairing_waiting_for_the_right_one() {
    let mut wire = Wire::new(Behaviour::default(), None);
    wire.type_code = false;
    let start = wire.host.start().unwrap();
    wire.to_device.extend(start);
    wire.run().unwrap();
    assert!(matches!(
        wire.events.last(),
        Some(TrezorEvent::PairingCodeNeeded)
    ));

    for malformed in ["", "12345", "12345a", "1234567", "123 456"] {
        assert!(
            matches!(
                trezor_error(wire.host.pairing_code(malformed)),
                TrezorError::PairingCodeInvalid
            ),
            "{malformed:?}"
        );
        assert!(
            wire.to_device.is_empty(),
            "nothing is sent for {malformed:?}"
        );
        assert!(!wire.host.is_ready(), "still pairing after {malformed:?}");
    }

    let shown = wire.device.screen_code.clone().unwrap();
    let packets = wire.host.pairing_code(&shown).unwrap();
    wire.to_device.extend(packets);
    wire.run().unwrap();
    assert!(
        wire.events
            .iter()
            .any(|event| matches!(event, TrezorEvent::Connected { .. })),
        "connected after the right code: {:?}",
        wire.events
    );
}

#[test]
fn the_busy_packet_is_reported_as_busy() {
    let mut wire = Wire::new(
        Behaviour {
            busy: true,
            ..Behaviour::default()
        },
        None,
    );
    match trezor_error(wire.connect()) {
        TrezorError::Busy(message) => assert_eq!(message, "Device locked or busy"),
        other => panic!("{other}"),
    }
}

#[test]
fn duplicated_messages_are_acknowledged_once_more_and_ignored() {
    let mut wire = Wire::new(
        Behaviour {
            duplicate_messages: true,
            ..Behaviour::default()
        },
        None,
    );
    wire.connect().unwrap();
    wire.events.clear();
    let packets = wire
        .host
        .request_xpub(&account(), XpubOptions::default())
        .unwrap();
    wire.to_device.extend(packets);
    wire.run().unwrap();
    assert!(matches!(wire.events.as_slice(), [TrezorEvent::Xpub { .. }]));
}

#[test]
fn an_unanswered_allocation_request_is_sent_again() {
    let mut wire = Wire::new(
        Behaviour {
            miss_allocations: 2,
            ..Behaviour::default()
        },
        None,
    );
    let start = wire.host.start().unwrap();
    wire.to_device.extend(start);
    wire.run().unwrap();
    assert!(wire.events.is_empty(), "the device heard nothing yet");
    for _ in 0..2 {
        assert!(wire.host.ack_wait().is_some(), "a retry is scheduled");
        let again = wire.host.retransmit().unwrap();
        assert_eq!(again.len(), 1);
        wire.to_device.extend(again);
        wire.run().unwrap();
    }
    assert!(
        wire.events
            .iter()
            .any(|e| matches!(e, TrezorEvent::Connected { .. }))
    );

    // Without any answer the retries stop, and the caller's own deadline ends it.
    let mut silent = TrezorSession::new(config(), None).unwrap();
    silent.start().unwrap();
    for _ in 0..3 {
        assert_eq!(silent.retransmit().unwrap().len(), 1);
    }
    assert!(silent.ack_wait().is_none());
    assert!(silent.retransmit().unwrap().is_empty());
}

#[test]
fn requests_wait_for_the_connection() {
    let mut session = TrezorSession::new(config(), None).unwrap();
    assert!(matches!(
        trezor_error(session.request_xpub(&account(), XpubOptions::default())),
        TrezorError::OutOfOrder
    ));
    assert!(matches!(
        trezor_error(session.receive(&[0; PACKET_LEN])),
        TrezorError::OutOfOrder
    ));
    assert!(matches!(
        trezor_error(session.pairing_code("123456")),
        TrezorError::OutOfOrder
    ));
    for (host, app) in [("", "mebit"), ("h", ""), (&"x".repeat(33)[..], "mebit")] {
        let config = SessionConfig {
            host_name: host.into(),
            app_name: app.into(),
            ..config()
        };
        assert!(matches!(
            trezor_error(TrezorSession::new(config, None).map(|_| ())),
            TrezorError::InvalidRequest(_)
        ));
    }
}

#[test]
fn every_packet_is_a_ble_packet() {
    let mut session = TrezorSession::new(config(), None).unwrap();
    let packets = session.start().unwrap();
    assert!(!packets.is_empty());
    assert!(packets.iter().all(|packet| packet.len() == PACKET_LEN));
}

// ---------------------------------------------------------------- xpubs

fn account() -> DerivationPath {
    DerivationPath::from_str("m/48'/1'/0'/2'").unwrap()
}

#[test]
fn xpubs_come_from_the_device_key() {
    let mut wire = connected(Behaviour::default());
    let packets = wire
        .host
        .request_xpub(&account(), XpubOptions::default())
        .unwrap();
    wire.to_device.extend(packets);
    wire.run().unwrap();
    let secp = Secp256k1::new();
    let expected = Xpub::from_priv(
        &secp,
        &wire.device.master.derive_priv(&secp, &account()).unwrap(),
    );
    match wire.events.as_slice() {
        [
            TrezorEvent::Xpub {
                xpub,
                master_fingerprint,
                as_shown,
            },
        ] => {
            assert_eq!(*xpub, expected);
            assert_eq!(*master_fingerprint, wire.device.master.fingerprint(&secp));
            assert_eq!(*as_shown, expected.to_string());
        }
        other => panic!("{other:?}"),
    }

    // Shown on the device: waits for the user, then answers.
    let options = XpubOptions {
        show_on_device: true,
        slip132: true,
    };
    let bip84 = DerivationPath::from_str("m/84'/1'/0'").unwrap();
    wire.events.clear();
    wire.to_device
        .extend(wire.host.request_xpub(&bip84, options).unwrap());
    wire.run().unwrap();
    assert!(matches!(
        wire.events.first(),
        Some(TrezorEvent::AwaitingUser(UserPrompt::Confirm))
    ));
    assert!(
        matches!(wire.events.last(), Some(TrezorEvent::Xpub { as_shown, .. }) if as_shown.starts_with("slip132:"))
    );
}

/// An xpub defines a vault, so the answer must be the key asked for: at the
/// path, on the network, encoded as the key it is, with its master.
#[test]
fn xpub_answers_must_be_the_key_asked_for() {
    type Expect = fn(&TrezorError) -> bool;
    let cases: [(XpubAnswer, Expect); 5] = [
        (XpubAnswer::Parent, |e| {
            matches!(e, TrezorError::Protocol(_))
        }),
        (XpubAnswer::Sibling, |e| {
            matches!(e, TrezorError::Protocol(_))
        }),
        (XpubAnswer::MainnetString, |e| {
            matches!(e, TrezorError::NetworkMismatch(_))
        }),
        (XpubAnswer::StringOfAnotherKey, |e| {
            matches!(e, TrezorError::Protocol(_))
        }),
        (XpubAnswer::NoRootFingerprint, |e| {
            matches!(e, TrezorError::Protocol(_))
        }),
    ];
    for (answer, expected) in cases {
        let mut wire = connected(Behaviour {
            xpub_answer: answer,
            ..Behaviour::default()
        });
        let packets = wire
            .host
            .request_xpub(&account(), XpubOptions::default())
            .unwrap();
        wire.to_device.extend(packets);
        let error = trezor_error(wire.run());
        assert!(expected(&error), "{answer:?}: {error}");
    }
}

#[test]
fn only_account_paths_on_the_right_network_are_asked_for() {
    let mut wire = connected(Behaviour::default());
    for path in [
        "m",
        "m/48'/0'/0'/2'",   // mainnet coin type on testnet
        "m/48'/1'/0'/1'",   // P2SH-P2WSH
        "m/48'/1'/0'/2'/0", // below the account
        "m/48'/1'/0/2'",    // unhardened account
        "m/45'/1'/0'",
        "m/86'/1'/0",
        "m/84'/0'/0'",
    ] {
        let path = DerivationPath::from_str(path).unwrap();
        assert!(
            matches!(
                trezor_error(wire.host.request_xpub(&path, XpubOptions::default())),
                TrezorError::InvalidRequest(_)
            ),
            "{path}"
        );
    }
    assert!(
        wire.host.is_ready(),
        "a refused request leaves the session ready"
    );
    assert!(
        !wire
            .device
            .received
            .contains(&mt(MessageType::MessageType_GetPublicKey))
    );
}

// ---------------------------------------------------------------- signing

/// A 2-of-3 `sortedmulti` vault with the fake's key and two software keys,
/// and a spend of synthetic outputs, as mebit builds it: key origins on every
/// input and on the change, and the three account xpubs as global xpubs.
struct Vault {
    psbt: Psbt,
    device_fingerprint: Fingerprint,
    cosigner: Xpriv,
}

fn vault(device: &Xpriv, inputs: u32) -> Vault {
    vault_with(device, [master_key(2), master_key(3)], inputs)
}

fn vault_with(device: &Xpriv, others: [Xpriv; 2], inputs: u32) -> Vault {
    vault_at(device, others, &account(), 1, inputs)
}

/// [`vault_with`], with every key's account at `account_path` and the
/// change on `change_chain` rather than 1.
fn vault_at(
    device: &Xpriv,
    others: [Xpriv; 2],
    account_path: &DerivationPath,
    change_chain: u32,
    inputs: u32,
) -> Vault {
    let secp = Secp256k1::new();
    let mut keys = Vec::new();
    let mut cosigners = Vec::new();
    for master in [*device, others[0], others[1]] {
        let xpub = Xpub::from_priv(&secp, &master.derive_priv(&secp, account_path).unwrap());
        keys.push((master.fingerprint(&secp), xpub));
        cosigners.push(master);
    }
    let descriptor = |chain: u32| -> Descriptor<DescriptorPublicKey> {
        let keys: Vec<String> = keys
            .iter()
            .map(|(fingerprint, xpub)| format!("[{fingerprint}/{account_path}]{xpub}/{chain}/*"))
            .collect();
        Descriptor::from_str(&format!("wsh(sortedmulti(2,{}))", keys.join(","))).unwrap()
    };
    let (receive, change) = (descriptor(0), descriptor(change_chain));
    let previous: Vec<Transaction> = (0..inputs)
        .map(|index| Transaction {
            version: transaction::Version::TWO,
            lock_time: absolute::LockTime::ZERO,
            input: vec![TxIn {
                previous_output: OutPoint {
                    txid: Txid::from_byte_array([0x5a; 32]),
                    vout: index,
                },
                script_sig: ScriptBuf::new(),
                sequence: Sequence::MAX,
                witness: Witness::new(),
            }],
            output: vec![TxOut {
                value: Amount::from_sat(50_000),
                script_pubkey: receive.at_derivation_index(index).unwrap().script_pubkey(),
            }],
        })
        .collect();
    let change_at = change.at_derivation_index(0).unwrap();
    let fee = 1_000;
    let unsigned_tx = Transaction {
        version: transaction::Version::TWO,
        lock_time: absolute::LockTime::ZERO,
        input: previous
            .iter()
            .map(|prev| TxIn {
                previous_output: OutPoint {
                    txid: prev.compute_txid(),
                    vout: 0,
                },
                script_sig: ScriptBuf::new(),
                sequence: Sequence::ENABLE_RBF_NO_LOCKTIME,
                witness: Witness::new(),
            })
            .collect(),
        output: vec![
            TxOut {
                value: Amount::from_sat(20_000),
                script_pubkey: receive.at_derivation_index(1000).unwrap().script_pubkey(),
            },
            TxOut {
                value: Amount::from_sat(50_000 * u64::from(inputs) - 20_000 - fee),
                script_pubkey: change_at.script_pubkey(),
            },
        ],
    };
    let mut psbt = Psbt::from_unsigned_tx(unsigned_tx).unwrap();
    for (index, prev) in previous.into_iter().enumerate() {
        psbt.inputs[index].witness_utxo = Some(prev.output[0].clone());
        psbt.inputs[index].non_witness_utxo = Some(prev);
        let definite = receive.at_derivation_index(index as u32).unwrap();
        psbt.update_input_with_descriptor(index, &definite).unwrap();
    }
    psbt.update_output_with_descriptor(1, &change_at).unwrap();
    for (fingerprint, xpub) in &keys {
        psbt.xpub
            .insert(*xpub, (*fingerprint, account_path.clone()));
    }
    Vault {
        psbt,
        device_fingerprint: keys[0].0,
        cosigner: cosigners.remove(1),
    }
}

fn sign(wire: &mut Wire, psbt: &Psbt, signer: Fingerprint) -> Result<(), VaultCoreError> {
    let packets = wire.host.request_sign_psbt(psbt, signer)?;
    wire.to_device.extend(packets);
    wire.run()
}

#[test]
fn signing_trace_retains_rejection_and_prevents_old_attempt_attribution() {
    use super::SigningTraceEnd;
    let mut wire = connected(Behaviour {
        malformed_signature: true,
        ..Behaviour::default()
    });
    let (psbt, fingerprint) = one_key_bip48_psbt(&wire.device.master);
    let packets = wire
        .host
        .request_sign_psbt_traced(&psbt, fingerprint)
        .unwrap();
    wire.to_device.extend(packets);
    assert!(wire.run().is_err());
    assert!(wire.host.is_ready());
    assert!(
        wire.host
            .request_sign_psbt_traced(&psbt, fingerprint)
            .is_err()
    );
    let trace = wire.host.take_signing_trace().unwrap();
    assert_eq!(trace.end(), SigningTraceEnd::Rejected);
    assert!(!trace.incomplete());
    assert_eq!(trace.frames()[0].message_type(), 15);
    assert_eq!(
        super::decode::<SignTx>(trace.frames()[0].payload())
            .unwrap()
            .inputs_count,
        Some(1)
    );
    assert!(
        trace
            .frames()
            .iter()
            .any(|frame| frame.outbound() && frame.message_type() == 22)
    );
    assert_eq!(trace.frames().last().unwrap().message_type(), 21);
    assert_eq!(
        super::decode::<TxRequest>(trace.frames().last().unwrap().payload())
            .unwrap()
            .request_type(),
        tx_request::RequestType::TXFINISHED
    );
    assert!(wire.host.take_signing_trace().is_none());
    sign(&mut wire, &psbt, fingerprint).unwrap_err();
    assert!(wire.host.take_signing_trace().is_none());
}

#[test]
fn signing_trace_retains_abandonment_and_unfinished_take() {
    use super::SigningTraceEnd;
    for abandon in [false, true] {
        let mut wire = connected(Behaviour::default());
        let (psbt, fingerprint) = one_key_bip48_psbt(&wire.device.master);
        wire.host
            .request_sign_psbt_traced(&psbt, fingerprint)
            .unwrap();
        if abandon {
            wire.host.abandon();
        }
        let trace = wire.host.take_signing_trace().unwrap();
        assert_eq!(trace.end(), SigningTraceEnd::Interrupted);
        assert_eq!(trace.frames().len(), 1);
        assert!(trace.frames()[0].outbound());
    }
}

#[test]
fn signing_trace_records_deferred_handoffs_and_complete_exchange() {
    use super::SigningTraceEnd;
    let mut wire = connected(Behaviour::default());
    let first = wire.host.occupy_link_for_test();
    let channel = wire.device.channel.as_mut().unwrap();
    for packet in first {
        assert!(channel.packet_in(&packet).check_failed().is_ok());
    }
    let (_, message_type, _) = channel.message_out().unwrap();
    assert_eq!(message_type, mt(MessageType::MessageType_ButtonAck));
    let withheld = wire.device.drain();
    assert!(wire.host.is_ready());
    assert!(wire.host.ack_wait().is_some());
    let (mut psbt, fingerprint) = one_key_bip48_psbt(&wire.device.master);
    psbt.inputs[0].sighash_type = Some(EcdsaSighashType::All.into());
    let first = wire
        .host
        .request_sign_psbt_traced(&psbt, fingerprint)
        .unwrap();
    assert!(first.is_empty(), "the initial request awaits the prior ACK");
    for reply in withheld {
        wire.take(reply).unwrap();
    }
    wire.run().unwrap();
    let trace = wire.host.take_signing_trace().unwrap();
    assert_eq!(trace.end(), SigningTraceEnd::Completed);
    assert!(!trace.incomplete());
    assert_eq!(trace.frames()[0].message_type(), 15);
    assert_eq!(
        trace
            .frames()
            .iter()
            .filter(|frame| !frame.outbound() && frame.message_type() == 21)
            .map(|frame| super::decode::<TxRequest>(frame.payload()).unwrap())
            .filter(|request| {
                request.request_type() == tx_request::RequestType::TXINPUT
                    && request.details.tx_hash.is_none()
            })
            .count(),
        3
    );
    assert_eq!(
        trace
            .frames()
            .iter()
            .filter(|frame| frame.message_type() == 15)
            .count(),
        1
    );
    for pair in trace.frames()[1..trace.frames().len() - 1].chunks_exact(2) {
        assert!(!pair[0].outbound());
        assert_eq!(pair[0].message_type(), 21);
        assert!(pair[1].outbound());
        assert_eq!(pair[1].message_type(), 22);
    }
    assert!(wire.host.is_ready());
    if let Some(directory) = std::env::var_os("TREZOR_TRACE_FIXTURE_DIR") {
        use bitcoin::hex::DisplayHex;
        let directory = std::path::PathBuf::from(directory);
        let (xpub, _) = psbt.xpub.iter().next().unwrap();
        let previous = psbt.inputs[0].non_witness_utxo.as_ref().unwrap();
        let receive = Address::from_script(&previous.output[0].script_pubkey, NETWORK).unwrap();
        let change =
            Address::from_script(&psbt.unsigned_tx.output[1].script_pubkey, NETWORK).unwrap();
        let destination =
            Address::from_script(&psbt.unsigned_tx.output[0].script_pubkey, NETWORK).unwrap();
        let slot = serde_json::json!({"source":"trezor","number":0});
        let psbt_base64 = {
            const ALPHABET: &[u8] =
                b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
            let mut encoded = String::new();
            for chunk in psbt.serialize().chunks(3) {
                let word = (u32::from(chunk[0]) << 16)
                    | (u32::from(*chunk.get(1).unwrap_or(&0)) << 8)
                    | u32::from(*chunk.get(2).unwrap_or(&0));
                for (i, shift) in [18, 12, 6, 0].into_iter().enumerate() {
                    encoded.push(if i > chunk.len() {
                        '='
                    } else {
                        ALPHABET[((word >> shift) & 63) as usize] as char
                    });
                }
            }
            encoded
        };
        let checkpoint = serde_json::json!({
            "schema":1, "case":{"family":"p2wsh","account":0,"threshold":1,
                "holders":[slot],"signing_subset":[slot],
                "receive":{"branch":0,"index":12},"change":{"branch":1,"index":12}},
            "enrolled":[{"slot":slot,"identity":{"firmware":"2.12.5","fingerprint":fingerprint.to_string(),
                "physical_id":"synthetic","accounts":{"p2wsh":{"origin":"m/48'/1'/0'/2'","xpub":xpub.to_string()}}}}],
            "receive_address":receive.to_string(),"change_address":change.to_string(),"destination":destination.to_string(),
            "progress":{"Signing":{"completed":[],"unsigned_psbt":psbt_base64,"signed_psbt":psbt_base64,
                "funding":{"outpoint":psbt.unsigned_tx.input[0].previous_output.to_string(),
                    "transaction_hex":bitcoin::consensus::encode::serialize_hex(previous),"amount_sats":30000}}}
        });
        let frames: Vec<_> = trace.frames().iter().enumerate().map(|(sequence, frame)| serde_json::json!({
            "sequence":sequence,"outbound":frame.outbound(),"session":1,
            "message_type":frame.message_type(),"payload":frame.payload().to_lower_hex_string()
        })).collect();
        let artifact = serde_json::json!({
            "schema":1,"psbt_sha256":sha256::Hash::hash(&psbt.serialize()).to_string(),
            "transaction_id":psbt.unsigned_tx.compute_txid().to_string(),"firmware":"2.12.5",
            "fingerprint":fingerprint.to_string(),"physical_id":"synthetic",
            "build_source_sha256":"a".repeat(64),"executable_sha256":"b".repeat(64),
            "build_source_scope":"length-framed names and bytes: session.rs,signing_trace.rs,bitcoin.rs,thp.rs,psbt_check.rs,trezor-ble/lib.rs,main.rs,coordinator.rs",
            "runtime_git_revision":null,"handoff":"THP plaintext acceptance, not radio delivery",
            "incomplete":false,"end":"Completed","frames":frames
        });
        std::fs::create_dir_all(&directory).unwrap();
        for (name, artifact) in [("checkpoint.json", checkpoint), ("trace.json", artifact)] {
            std::fs::write(directory.join(name), serde_json::to_vec(&artifact).unwrap()).unwrap();
        }
    }
}

#[test]
fn signing_trace_retains_device_cancellation_and_predecode_protocol_failure() {
    use super::SigningTraceEnd;
    let mut wire = connected(Behaviour {
        hold_prompts: true,
        ..Behaviour::default()
    });
    let (psbt, fingerprint) = one_key_bip48_psbt(&wire.device.master);
    let first = wire
        .host
        .request_sign_psbt_traced(&psbt, fingerprint)
        .unwrap();
    wire.to_device.extend(first);
    wire.run().unwrap();
    let cancel = wire.host.request_cancel().unwrap();
    wire.to_device.extend(cancel);
    assert!(wire.run().is_err());
    assert_eq!(
        wire.host.take_signing_trace().unwrap().end(),
        SigningTraceEnd::Rejected
    );

    let mut wire = connected(Behaviour {
        hold_prompts: true,
        ..Behaviour::default()
    });
    let (psbt, fingerprint) = one_key_bip48_psbt(&wire.device.master);
    let first = wire
        .host
        .request_sign_psbt_traced(&psbt, fingerprint)
        .unwrap();
    wire.to_device.extend(first);
    wire.run().unwrap();
    let malformed = [0x08, 0x80];
    wire.device
        .channel
        .as_mut()
        .unwrap()
        .message_in(1, 21, &malformed)
        .unwrap();
    let replies = wire.device.drain();
    assert!(replies.into_iter().any(|reply| wire.take(reply).is_err()));
    let trace = wire.host.take_signing_trace().unwrap();
    assert_eq!(trace.end(), SigningTraceEnd::ProtocolFailure);
    assert_eq!(trace.frames().last().unwrap().payload(), &malformed);
    assert!(!wire.host.is_ready());
}

struct SingleWallet {
    psbt: Psbt,
    receive: Descriptor<DescriptorPublicKey>,
    change: Descriptor<DescriptorPublicKey>,
    fingerprint: Fingerprint,
}

fn single_wallet(master: &Xpriv, purpose: u32, count: u32) -> SingleWallet {
    let secp = Secp256k1::new();
    let fingerprint = master.fingerprint(&secp);
    let account = DerivationPath::from_str(&format!("m/{purpose}'/1'/0'")).unwrap();
    let xpub = Xpub::from_priv(&secp, &master.derive_priv(&secp, &account).unwrap());
    let descriptor = |chain| {
        let key = format!("[{fingerprint}/{purpose}'/1'/0']{xpub}/{chain}/*");
        let script = match purpose {
            44 => format!("pkh({key})"),
            49 => format!("sh(wpkh({key}))"),
            84 => format!("wpkh({key})"),
            86 => format!("tr({key})"),
            _ => panic!("unsupported test purpose"),
        };
        Descriptor::<DescriptorPublicKey>::from_str(&script).unwrap()
    };
    let receive = descriptor(0);
    let change = descriptor(1);
    let previous: Vec<Transaction> = (0..count)
        .map(|index| Transaction {
            version: transaction::Version::TWO,
            lock_time: absolute::LockTime::from_consensus(index),
            input: vec![TxIn::default()],
            output: vec![TxOut {
                value: Amount::from_sat(30_000),
                script_pubkey: receive.at_derivation_index(index).unwrap().script_pubkey(),
            }],
        })
        .collect();
    let change_at = change.at_derivation_index(7).unwrap();
    let destination = device_script(&master_key(9), InputScriptType::SPENDWITNESS, &[0, 2], None);
    let mut psbt = Psbt::from_unsigned_tx(Transaction {
        version: transaction::Version::TWO,
        lock_time: absolute::LockTime::ZERO,
        input: previous
            .iter()
            .map(|tx| TxIn {
                previous_output: OutPoint {
                    txid: tx.compute_txid(),
                    vout: 0,
                },
                sequence: Sequence::ENABLE_RBF_NO_LOCKTIME,
                ..TxIn::default()
            })
            .collect(),
        output: vec![
            TxOut {
                value: Amount::from_sat(5_000),
                script_pubkey: destination,
            },
            TxOut {
                value: Amount::from_sat(u64::from(count) * 30_000 - 5_400),
                script_pubkey: change_at.script_pubkey(),
            },
        ],
    })
    .unwrap();
    for (index, previous) in previous.into_iter().enumerate() {
        psbt.inputs[index].witness_utxo = Some(previous.output[0].clone());
        psbt.inputs[index].non_witness_utxo = Some(previous);
        psbt.update_input_with_descriptor(
            index,
            &receive.at_derivation_index(index as u32).unwrap(),
        )
        .unwrap();
    }
    psbt.update_output_with_descriptor(1, &change_at).unwrap();
    psbt.xpub.insert(xpub, (fingerprint, account));
    SingleWallet {
        psbt,
        receive,
        change,
        fingerprint,
    }
}

#[test]
fn single_sig_accounts_sign_and_finalize_all_four_script_families() {
    for purpose in [44, 49, 84, 86] {
        let mut wire = connected(Behaviour::default());
        let wallet = single_wallet(&wire.device.master, purpose, 2);
        sign(&mut wire, &wallet.psbt, wallet.fingerprint).unwrap();
        let Some(TrezorEvent::SignedPsbt(mut signed)) = wire.events.pop() else {
            panic!("no signed PSBT for BIP-{purpose}");
        };
        assert_eq!(signed.unsigned_tx, wallet.psbt.unsigned_tx);
        assert!(signed.inputs.iter().all(|input| if purpose == 86 {
            input.tap_key_sig.is_some() && input.partial_sigs.is_empty()
        } else {
            input.partial_sigs.len() == 1 && input.tap_key_sig.is_none()
        }));
        signed.finalize_mut(&Secp256k1::new()).unwrap();
        let transaction = signed.extract(&Secp256k1::new()).unwrap();
        assert_eq!(transaction.input.len(), 2);
        assert_eq!(transaction.output, wallet.psbt.unsigned_tx.output);
        assert_eq!(
            wire.events
                .iter()
                .filter(|event| matches!(event, TrezorEvent::AwaitingUser(UserPrompt::Confirm)))
                .count(),
            2
        );
        assert!(wire.host.is_ready());
    }
}

#[test]
fn single_sig_signatures_for_another_message_and_device_refusal_are_rejected() {
    for purpose in [44, 49, 84, 86] {
        let mut wire = connected(Behaviour {
            sign_wrong_message: true,
            ..Behaviour::default()
        });
        let wallet = single_wallet(&wire.device.master, purpose, 2);
        let error = trezor_error(sign(&mut wire, &wallet.psbt, wallet.fingerprint));
        if purpose == 86 {
            assert!(matches!(
                error,
                TrezorError::Signatures(psbt_check::SignatureCheckError::InvalidSignature {
                    input: 1
                })
            ));
        } else {
            let TrezorError::RejectedSignature(rejection) = error else {
                panic!("{error}");
            };
            assert_eq!(rejection.input_index(), 1);
            assert_eq!(
                rejection.reason(),
                EcdsaSignatureRejectionReason::HostVerificationFailed
            );
        }
        assert!(wire.host.is_ready());
        let mut wire = connected(Behaviour {
            decline_signing: true,
            ..Behaviour::default()
        });
        let wallet = single_wallet(&wire.device.master, purpose, 1);
        assert!(matches!(
            trezor_error(sign(&mut wire, &wallet.psbt, wallet.fingerprint)),
            TrezorError::UserCancelled
        ));
        assert!(
            !wire
                .events
                .iter()
                .any(|event| matches!(event, TrezorEvent::SignedPsbt(_)))
        );
        assert!(wire.host.is_ready());
    }
}

#[test]
fn all_single_sig_xpub_requests_use_the_account_script_type() {
    for (purpose, script_type) in [
        (44, InputScriptType::SPENDADDRESS),
        (49, InputScriptType::SPENDP2SHWITNESS),
        (84, InputScriptType::SPENDWITNESS),
        (86, InputScriptType::SPENDTAPROOT),
    ] {
        let path = DerivationPath::from_str(&format!("m/{purpose}'/1'/0'")).unwrap();
        let (_, message) =
            super::bitcoin::XpubRequest::new(&path, NETWORK, XpubOptions::default()).unwrap();
        assert_eq!(message.script_type(), script_type);
        let mut wire = connected(Behaviour::default());
        let packets = wire
            .host
            .request_xpub(&path, XpubOptions::default())
            .unwrap();
        wire.to_device.extend(packets);
        wire.run().unwrap();
        let expected = Xpub::from_priv(
            &Secp256k1::new(),
            &wire
                .device
                .master
                .derive_priv(&Secp256k1::new(), &path)
                .unwrap(),
        );
        assert!(
            matches!(wire.events.last(), Some(TrezorEvent::Xpub { xpub, master_fingerprint, .. }) if *xpub == expected && *master_fingerprint == wire.device.master.fingerprint(&Secp256k1::new()))
        );
    }
}

#[test]
fn descriptor_address_verification_displays_and_matches_receive_and_change() {
    let mut wire = connected(Behaviour::default());
    for purpose in [44, 49, 84, 86] {
        let wallet = single_wallet(&wire.device.master, purpose, 1);
        for descriptor in [&wallet.receive, &wallet.change] {
            for index in [0, 1, 7] {
                let request = AddressRequest {
                    descriptor,
                    index,
                    signer: wallet.fingerprint,
                };
                let packets = wire.host.request_verify_address(&request).unwrap();
                wire.to_device.extend(packets);
                wire.events.clear();
                wire.run().unwrap();
                let expected = descriptor
                    .at_derivation_index(index)
                    .unwrap()
                    .address(NETWORK)
                    .unwrap();
                assert!(
                    matches!(wire.events.last(), Some(TrezorEvent::AddressVerified(address)) if *address == expected)
                );
                assert!(matches!(
                    wire.events.first(),
                    Some(TrezorEvent::AwaitingUser(UserPrompt::Confirm))
                ));
            }
        }
    }
    let vault = vault(&wire.device.master, 1);
    let account_xpub = vault
        .psbt
        .xpub
        .keys()
        .find(|xpub| vault.psbt.xpub[xpub].0 == vault.device_fingerprint)
        .unwrap();
    let descriptor_keys = vault
        .psbt
        .xpub
        .iter()
        .map(|(xpub, (fp, _))| format!("[{fp}/48'/1'/0'/2']{xpub}/0/*"))
        .collect::<Vec<_>>();
    let descriptor = Descriptor::<DescriptorPublicKey>::from_str(&format!(
        "wsh(sortedmulti(2,{}))",
        descriptor_keys.join(",")
    ))
    .unwrap();
    let request = AddressRequest {
        descriptor: &descriptor,
        index: 7,
        signer: vault.device_fingerprint,
    };
    let (_, message) = super::bitcoin::AddressVerification::new(&request, NETWORK).unwrap();
    assert_eq!(message.multisig.m(), 2);
    assert_eq!(message.multisig.nodes.len(), 3);
    assert_eq!(
        message.multisig.pubkeys_order(),
        super::protos::bitcoin::MultisigPubkeysOrder::LEXICOGRAPHIC
    );
    assert_eq!(message.address_n[4..], [0, 7]);
    assert!(
        message
            .multisig
            .nodes
            .iter()
            .any(|node| node.public_key() == account_xpub.public_key.serialize())
    );
    let packets = wire.host.request_verify_address(&request).unwrap();
    wire.to_device.extend(packets);
    wire.events.clear();
    wire.run().unwrap();
    let expected = descriptor
        .at_derivation_index(7)
        .unwrap()
        .address(NETWORK)
        .unwrap();
    assert!(
        matches!(wire.events.last(), Some(TrezorEvent::AddressVerified(address)) if *address == expected)
    );
}

#[test]
fn address_mismatch_refusal_and_disconnection_cannot_produce_verified_address() {
    for behaviour in [
        Behaviour {
            wrong_address: true,
            ..Behaviour::default()
        },
        Behaviour {
            decline_address: true,
            ..Behaviour::default()
        },
    ] {
        let mut wire = connected(behaviour);
        let wallet = single_wallet(&wire.device.master, 84, 1);
        let request = AddressRequest {
            descriptor: &wallet.receive,
            index: 1,
            signer: wallet.fingerprint,
        };
        let packets = wire.host.request_verify_address(&request).unwrap();
        wire.to_device.extend(packets);
        let error = trezor_error(wire.run());
        assert!(matches!(
            error,
            TrezorError::NetworkMismatch(_) | TrezorError::UserCancelled
        ));
        assert!(
            !wire
                .events
                .iter()
                .any(|event| matches!(event, TrezorEvent::AddressVerified(_)))
        );
        assert!(wire.host.is_ready());
    }
    let mut wire = connected(Behaviour {
        hold_prompts: true,
        ..Behaviour::default()
    });
    let wallet = single_wallet(&wire.device.master, 84, 1);
    let request = AddressRequest {
        descriptor: &wallet.receive,
        index: 1,
        signer: wallet.fingerprint,
    };
    let packets = wire.host.request_verify_address(&request).unwrap();
    wire.to_device.extend(packets);
    wire.run().unwrap();
    assert!(wire.host.awaiting_user());
    wire.host.abandon();
    assert!(!wire.host.is_ready());
    assert!(matches!(
        trezor_error(wire.host.request_verify_address(&request)),
        TrezorError::Protocol(_)
    ));
    assert!(
        !wire
            .events
            .iter()
            .any(|event| matches!(event, TrezorEvent::AddressVerified(_)))
    );
}

#[test]
fn single_sig_rejects_inconsistent_origins_scripts_and_policies_before_device_interaction() {
    type Mutation = Box<dyn Fn(&mut Psbt)>;
    let common: Vec<(&str, Mutation)> = vec![
        (
            "missing previous transaction",
            Box::new(|psbt| psbt.inputs[0].non_witness_utxo = None),
        ),
        (
            "different previous transaction",
            Box::new(|psbt| {
                psbt.inputs[0].non_witness_utxo = psbt.inputs[1].non_witness_utxo.clone()
            }),
        ),
        (
            "amount mismatch",
            Box::new(|psbt| {
                psbt.inputs[0].witness_utxo.as_mut().unwrap().value = Amount::from_sat(1)
            }),
        ),
        ("missing account xpub", Box::new(|psbt| psbt.xpub.clear())),
        (
            "wrong account xpub network",
            Box::new(|psbt| {
                let (xpub, source) = psbt.xpub.pop_first().unwrap();
                psbt.xpub.insert(
                    Xpub {
                        network: NetworkKind::Main,
                        ..xpub
                    },
                    source,
                );
            }),
        ),
        (
            "wrong account origin",
            Box::new(|psbt| {
                psbt.xpub.values_mut().next().unwrap().1 =
                    DerivationPath::from_str("m/84'/0'/0'").unwrap();
            }),
        ),
        (
            "extraneous witness script",
            Box::new(|psbt| psbt.inputs[0].witness_script = Some(ScriptBuf::new())),
        ),
        (
            "wrong redeem script",
            Box::new(|psbt| psbt.inputs[0].redeem_script = Some(ScriptBuf::new())),
        ),
        (
            "unsupported sighash",
            Box::new(|psbt| psbt.inputs[0].sighash_type = Some(EcdsaSighashType::None.into())),
        ),
        (
            "change script differs from origins",
            Box::new(|psbt| {
                psbt.unsigned_tx.output[1].script_pubkey =
                    psbt.unsigned_tx.output[0].script_pubkey.clone()
            }),
        ),
        (
            "duplicate funding outpoint",
            Box::new(|psbt| {
                psbt.unsigned_tx.input[1] = psbt.unsigned_tx.input[0].clone();
                psbt.inputs[1] = psbt.inputs[0].clone();
            }),
        ),
    ];
    let mut wire = connected(Behaviour::default());
    for purpose in [44, 49, 84, 86] {
        let wallet = single_wallet(&wire.device.master, purpose, 2);
        for (label, mutation) in &common {
            let mut psbt = wallet.psbt.clone();
            mutation(&mut psbt);
            assert!(
                matches!(
                    trezor_error(wire.host.request_sign_psbt(&psbt, wallet.fingerprint)),
                    TrezorError::InvalidRequest(_)
                ),
                "BIP-{purpose}, {label}"
            );
            assert!(wire.host.is_ready());
        }
        let mut wrong_origin = wallet.psbt.clone();
        let source = if purpose == 86 {
            &mut wrong_origin.inputs[0]
                .tap_key_origins
                .values_mut()
                .next()
                .unwrap()
                .1
        } else {
            wrong_origin.inputs[0]
                .bip32_derivation
                .values_mut()
                .next()
                .unwrap()
        };
        source.1 = DerivationPath::from_str(&format!("m/{purpose}'/0'/0'/0/0")).unwrap();
        assert!(matches!(
            trezor_error(
                wire.host
                    .request_sign_psbt(&wrong_origin, wallet.fingerprint)
            ),
            TrezorError::InvalidRequest(_)
        ));
        let mut wrong_change = wallet.psbt.clone();
        let source = if purpose == 86 {
            &mut wrong_change.outputs[1]
                .tap_key_origins
                .values_mut()
                .next()
                .unwrap()
                .1
        } else {
            wrong_change.outputs[1]
                .bip32_derivation
                .values_mut()
                .next()
                .unwrap()
        };
        source.1 = DerivationPath::from_str(&format!("m/{purpose}'/1'/0'/1/8")).unwrap();
        assert!(matches!(
            trezor_error(
                wire.host
                    .request_sign_psbt(&wrong_change, wallet.fingerprint)
            ),
            TrezorError::InvalidRequest(_)
        ));
    }
    let taproot = single_wallet(&wire.device.master, 86, 2);
    let tap_cases: Vec<(&str, Mutation)> = vec![
        (
            "absent internal key",
            Box::new(|psbt| psbt.inputs[0].tap_internal_key = None),
        ),
        (
            "script tree root",
            Box::new(|psbt| {
                psbt.inputs[0].tap_merkle_root =
                    Some(bitcoin::taproot::TapNodeHash::from_byte_array([1; 32]))
            }),
        ),
        (
            "script-path origin",
            Box::new(|psbt| {
                psbt.inputs[0]
                    .tap_key_origins
                    .values_mut()
                    .next()
                    .unwrap()
                    .0
                    .push(bitcoin::taproot::TapLeafHash::from_byte_array([1; 32]))
            }),
        ),
        (
            "explicit Taproot ALL",
            Box::new(|psbt| {
                psbt.inputs[0].sighash_type = Some(bitcoin::TapSighashType::All.into())
            }),
        ),
    ];
    for (label, mutation) in tap_cases {
        let mut psbt = taproot.psbt.clone();
        mutation(&mut psbt);
        assert!(
            matches!(
                trezor_error(wire.host.request_sign_psbt(&psbt, taproot.fingerprint)),
                TrezorError::InvalidRequest(_)
            ),
            "{label}"
        );
    }
    let other = single_wallet(&wire.device.master, 84, 2);
    let mut mixed = taproot.psbt.clone();
    mixed.inputs[1] = other.psbt.inputs[1].clone();
    mixed.unsigned_tx.input[1] = other.psbt.unsigned_tx.input[1].clone();
    mixed.xpub.extend(other.psbt.xpub);
    assert!(matches!(
        trezor_error(wire.host.request_sign_psbt(&mixed, taproot.fingerprint)),
        TrezorError::InvalidRequest(_)
    ));
    assert!(
        !wire
            .device
            .received
            .contains(&mt(MessageType::MessageType_SignTx))
    );
}

#[test]
fn unbound_address_requests_are_rejected_before_device_interaction() {
    let mut wire = connected(Behaviour::default());
    let wallet = single_wallet(&wire.device.master, 84, 1);
    let request = AddressRequest {
        descriptor: &wallet.receive,
        index: 0,
        signer: master_key(9).fingerprint(&Secp256k1::new()),
    };
    assert!(matches!(
        trezor_error(wire.host.request_verify_address(&request)),
        TrezorError::InvalidRequest(_)
    ));
    let xpub = *wallet.psbt.xpub.keys().next().unwrap();
    let unbound: Descriptor<DescriptorPublicKey> = format!("wpkh({xpub}/0/*)").parse().unwrap();
    let wrong_coin: Descriptor<DescriptorPublicKey> =
        format!("wpkh([{}/84'/0'/0']{xpub}/0/*)", wallet.fingerprint)
            .parse()
            .unwrap();
    let fixed: Descriptor<DescriptorPublicKey> =
        format!("wpkh([{}/84'/1'/0']{xpub}/0/0)", wallet.fingerprint)
            .parse()
            .unwrap();
    for descriptor in [&unbound, &wrong_coin, &fixed] {
        let request = AddressRequest {
            descriptor,
            index: 0,
            signer: wallet.fingerprint,
        };
        assert!(matches!(
            trezor_error(wire.host.request_verify_address(&request)),
            TrezorError::InvalidRequest(_)
        ));
    }
    let account = DerivationPath::from_str("m/48'/1'/0'/2'").unwrap();
    let first = Xpub::from_priv(
        &Secp256k1::new(),
        &wire
            .device
            .master
            .derive_priv(&Secp256k1::new(), &account)
            .unwrap(),
    );
    let second = Xpub::from_priv(
        &Secp256k1::new(),
        &master_key(2)
            .derive_priv(&Secp256k1::new(), &account)
            .unwrap(),
    );
    let duplicate_holder: Descriptor<DescriptorPublicKey> = format!(
        "wsh(sortedmulti(2,[{}/48'/1'/0'/2']{first}/0/*,[{}/48'/1'/0'/2']{second}/0/*))",
        wallet.fingerprint, wallet.fingerprint
    )
    .parse()
    .unwrap();
    let request = AddressRequest {
        descriptor: &duplicate_holder,
        index: 0,
        signer: wallet.fingerprint,
    };
    assert!(matches!(
        trezor_error(wire.host.request_verify_address(&request)),
        TrezorError::InvalidRequest(_)
    ));
    assert!(wire.host.is_ready());
    assert!(
        !wire
            .device
            .received
            .contains(&mt(MessageType::MessageType_GetAddress))
    );
}

fn one_key_bip48_psbt(master: &Xpriv) -> (Psbt, Fingerprint) {
    let secp = Secp256k1::new();
    let fingerprint = master.fingerprint(&secp);
    let account = account();
    let xpub = Xpub::from_priv(&secp, &master.derive_priv(&secp, &account).unwrap());
    let descriptor = |chain| {
        Descriptor::<DescriptorPublicKey>::from_str(&format!(
            "wsh(sortedmulti(1,[{fingerprint}/{account}]{xpub}/{chain}/*))"
        ))
        .unwrap()
        .at_derivation_index(12)
        .unwrap()
    };
    let receive = descriptor(0);
    let change = descriptor(1);
    let previous = Transaction {
        version: transaction::Version::TWO,
        lock_time: absolute::LockTime::ZERO,
        input: vec![TxIn::default()],
        output: vec![TxOut {
            value: Amount::from_sat(30_000),
            script_pubkey: receive.script_pubkey(),
        }],
    };
    let mut psbt = Psbt::from_unsigned_tx(Transaction {
        version: transaction::Version::TWO,
        lock_time: absolute::LockTime::ZERO,
        input: vec![TxIn {
            previous_output: OutPoint {
                txid: previous.compute_txid(),
                vout: 0,
            },
            sequence: Sequence::ENABLE_RBF_NO_LOCKTIME,
            ..TxIn::default()
        }],
        output: vec![
            TxOut {
                value: Amount::from_sat(5_000),
                script_pubkey: device_script(
                    &master_key(9),
                    InputScriptType::SPENDWITNESS,
                    &[0, 2],
                    None,
                ),
            },
            TxOut {
                value: Amount::from_sat(24_600),
                script_pubkey: change.script_pubkey(),
            },
        ],
    })
    .unwrap();
    psbt.inputs[0].witness_utxo = Some(previous.output[0].clone());
    psbt.inputs[0].non_witness_utxo = Some(previous);
    psbt.update_input_with_descriptor(0, &receive).unwrap();
    psbt.update_output_with_descriptor(1, &change).unwrap();
    psbt.xpub.insert(xpub, (fingerprint, account));
    (psbt, fingerprint)
}

#[test]
fn one_key_bip48_p2wsh_transmits_multisig_and_finalizes_at_index_12() {
    let mut wire = connected(Behaviour {
        hold_prompts: true,
        ..Behaviour::default()
    });
    let (psbt, fingerprint) = one_key_bip48_psbt(&wire.device.master);
    sign(&mut wire, &psbt, fingerprint).unwrap();
    wire.user_confirms().unwrap();

    let signing = wire.device.signing.as_ref().unwrap();
    assert_eq!(signing.inputs.len(), 1);
    assert_eq!(signing.outputs.len(), 2);
    let input = &signing.inputs[0];
    assert_eq!(input.script_type(), InputScriptType::SPENDWITNESS);
    assert_eq!(
        input.address_n,
        [0x8000_0030, 0x8000_0001, 0x8000_0000, 0x8000_0002, 0, 12]
    );
    let multisig = input
        .multisig
        .as_ref()
        .expect("one-key P2WSH needs multisig metadata");
    assert_eq!(multisig.m(), 1);
    assert_eq!(multisig.nodes.len(), 1);
    assert_eq!(multisig.address_n, [0, 12]);
    assert_eq!(
        multisig_script(multisig),
        *psbt.inputs[0].witness_script.as_ref().unwrap()
    );
    let change = &signing.outputs[1];
    assert_eq!(change.script_type(), OutputScriptType::PAYTOWITNESS);
    assert_eq!(
        change.address_n,
        [0x8000_0030, 0x8000_0001, 0x8000_0000, 0x8000_0002, 1, 12]
    );
    let change_multisig = change
        .multisig
        .as_ref()
        .expect("one-key P2WSH change needs multisig metadata");
    assert_eq!(change_multisig.m(), 1);
    assert_eq!(change_multisig.nodes.len(), 1);
    assert_eq!(change_multisig.address_n, [1, 12]);
    assert_eq!(
        multisig_script(change_multisig).to_p2wsh(),
        psbt.unsigned_tx.output[1].script_pubkey
    );

    wire.user_confirms().unwrap();
    let Some(TrezorEvent::SignedPsbt(mut signed)) = wire.events.pop() else {
        panic!("no signed one-key P2WSH PSBT");
    };
    assert_eq!(signed.unsigned_tx, psbt.unsigned_tx);
    assert_eq!(signed.inputs[0].partial_sigs.len(), 1);
    let (key, signature) = signed.inputs[0].partial_sigs.first_key_value().unwrap();
    let witness_script = psbt.inputs[0].witness_script.as_ref().unwrap();
    let digest = SighashCache::new(&psbt.unsigned_tx)
        .p2wsh_signature_hash(
            0,
            witness_script,
            psbt.inputs[0].witness_utxo.as_ref().unwrap().value,
            EcdsaSighashType::All,
        )
        .unwrap();
    let secp = Secp256k1::new();
    secp.verify_ecdsa(&Message::from(digest), &signature.signature, &key.inner)
        .unwrap();
    signed.finalize_mut(&secp).unwrap();
    let transaction = signed.extract(&secp).unwrap();
    assert_eq!(transaction.input.len(), 1);
    assert_eq!(transaction.output, psbt.unsigned_tx.output);
    assert_eq!(transaction.input[0].witness.len(), 3);
    assert_eq!(
        transaction.input[0].witness.last().unwrap(),
        witness_script.as_bytes()
    );
    assert!(wire.host.is_ready());
}

#[test]
fn one_key_bip48_p2wsh_rejects_matching_p2wpkh_script_code_signature() {
    let master = master_key(1);
    let (psbt, fingerprint) = one_key_bip48_psbt(&master);
    let secp = Secp256k1::new();
    let (public_key, (_, path)) = psbt.inputs[0].bip32_derivation.first_key_value().unwrap();
    let key = master.derive_priv(&secp, path).unwrap().private_key;
    assert_eq!(key.public_key(&secp), *public_key);
    let public_key = bitcoin::PublicKey::new(*public_key);
    let p2wpkh = ScriptBuf::new_p2wpkh(&public_key.wpubkey_hash().unwrap());
    let digest = SighashCache::new(&psbt.unsigned_tx)
        .p2wpkh_signature_hash(
            0,
            &p2wpkh,
            psbt.inputs[0].witness_utxo.as_ref().unwrap().value,
            EcdsaSighashType::All,
        )
        .unwrap();
    let message = Message::from(digest);
    let signature = secp.sign_ecdsa(&message, &key);
    secp.verify_ecdsa(&message, &signature, &public_key.inner)
        .unwrap();
    let mut signed = psbt.clone();
    signed.inputs[0].partial_sigs.insert(
        public_key,
        bitcoin::ecdsa::Signature::sighash_all(signature),
    );
    assert_eq!(
        psbt_check::verify_device_signatures(&psbt, signed, fingerprint).map(|_| ()),
        Err(psbt_check::SignatureCheckError::InvalidSignature { input: 0 })
    );
}

#[test]
fn signs_a_vault_spend_that_finalizes_2_of_3() {
    for inputs in [1, 2, 7] {
        let mut wire = connected(Behaviour::default());
        let vault = vault(&wire.device.master, inputs);
        sign(&mut wire, &vault.psbt, vault.device_fingerprint).unwrap();
        let Some(TrezorEvent::SignedPsbt(mut signed)) = wire.events.pop() else {
            panic!("no signed PSBT: {:?}", wire.events);
        };
        let prompts = wire
            .events
            .iter()
            .filter(|e| matches!(e, TrezorEvent::AwaitingUser(UserPrompt::Confirm)))
            .count();
        assert_eq!(
            prompts, 2,
            "the payment and the total; the change is the vault's own"
        );

        let secp = Secp256k1::new();
        signed.sign(&vault.cosigner, &secp).unwrap();
        signed.finalize_mut(&secp).unwrap();
        let tx = signed.extract(&secp).unwrap();
        assert_eq!(tx.input.len(), inputs as usize);
        assert!(wire.host.is_ready());
    }
}

#[test]
fn a_wrong_signature_is_refused() {
    let mut wire = connected(Behaviour {
        sign_wrong_message: true,
        ..Behaviour::default()
    });
    let vault = vault(&wire.device.master, 2);
    let error = trezor_error(sign(&mut wire, &vault.psbt, vault.device_fingerprint));
    assert_eq!(
        error.to_string(),
        "the Trezor signature was rejected at input 1: host verification failed"
    );
    let TrezorError::RejectedSignature(rejection) = &error else {
        panic!("{error}");
    };
    assert_eq!(rejection.input_index(), 1);
    assert_eq!(rejection.candidates().len(), 2);
    assert!(
        rejection
            .candidates()
            .iter()
            .all(|candidate| { candidate.sighash().is_some() && !candidate.signature_verifies() })
    );
    let signature_hex: String = rejection
        .signature_der()
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect();
    assert!(!format!("{error:?}").contains(&signature_hex));
    assert!(!error.to_string().contains(&signature_hex));
    assert!(wire.host.is_ready(), "the exchange ended in step");
}

#[test]
fn malformed_ecdsa_signature_is_captured_without_leaking_der() {
    let mut wire = connected(Behaviour {
        malformed_signature: true,
        ..Behaviour::default()
    });
    let wallet = vault(&wire.device.master, 1);
    let error = trezor_error(sign(&mut wire, &wallet.psbt, wallet.device_fingerprint));
    let TrezorError::RejectedSignature(rejection) = &error else {
        panic!("{error}");
    };

    assert_eq!(rejection.input_index(), 0);
    assert_eq!(
        rejection.reason(),
        EcdsaSignatureRejectionReason::MalformedDer
    );
    assert_eq!(rejection.signature_der(), &[0x30, 0x01, 0x00]);
    assert_eq!(rejection.candidates().len(), 1);
    assert!(rejection.candidates()[0].sighash().is_some());
    assert!(!rejection.candidates()[0].signature_verifies());
    assert!(!format!("{error:?}").contains("300100"));
    assert!(!error.to_string().contains("300100"));
    assert!(wire.host.is_ready());
}

#[test]
fn declined_and_cancelled_signing_leave_the_connection_usable() {
    let mut wire = connected(Behaviour {
        decline_signing: true,
        ..Behaviour::default()
    });
    let vault_psbt = vault(&wire.device.master, 1);
    assert!(matches!(
        trezor_error(sign(
            &mut wire,
            &vault_psbt.psbt,
            vault_psbt.device_fingerprint
        )),
        TrezorError::UserCancelled
    ));
    assert!(wire.host.is_ready());
    wire.device.behaviour.decline_signing = false;
    wire.events.clear();
    sign(&mut wire, &vault_psbt.psbt, vault_psbt.device_fingerprint).unwrap();
    assert!(
        matches!(wire.events.last(), Some(TrezorEvent::SignedPsbt(_))),
        "{:?}",
        wire.events
    );

    // Cancel while the device waits for the user.
    let mut wire = connected(Behaviour {
        hold_prompts: true,
        ..Behaviour::default()
    });
    let vault = vault(&wire.device.master, 1);
    sign(&mut wire, &vault.psbt, vault.device_fingerprint).unwrap();
    assert!(matches!(
        wire.events.last(),
        Some(TrezorEvent::AwaitingUser(UserPrompt::Confirm))
    ));
    let cancel = wire.host.request_cancel().unwrap();
    assert_eq!(cancel.len(), 1);
    wire.to_device.extend(cancel);
    assert!(matches!(
        trezor_error(wire.run()),
        TrezorError::UserCancelled
    ));
    assert!(wire.host.is_ready());

    // Cancel asked before the prompt shows: the prompt is answered with Cancel.
    let packets = wire
        .host
        .request_sign_psbt(&vault.psbt, vault.device_fingerprint)
        .unwrap();
    assert!(wire.host.request_cancel().unwrap().is_empty());
    wire.to_device.extend(packets);
    assert!(matches!(
        trezor_error(wire.run()),
        TrezorError::UserCancelled
    ));
    assert!(wire.host.is_ready());
}

#[test]
fn held_prompts_wait_for_the_user() {
    let mut wire = connected(Behaviour {
        hold_prompts: true,
        ..Behaviour::default()
    });
    let vault = vault(&wire.device.master, 2);
    sign(&mut wire, &vault.psbt, vault.device_fingerprint).unwrap();
    wire.user_confirms().unwrap(); // the payment
    wire.user_confirms().unwrap(); // the total
    assert!(matches!(
        wire.events.last(),
        Some(TrezorEvent::SignedPsbt(_))
    ));
}

/// Found on the Safe 7 (HW-10b): a driver that timed replies by the last
/// packet gave the user 15 seconds, because the device acknowledges our
/// ButtonAck after the prompt.
#[test]
fn the_wait_for_the_user_outlasts_the_acknowledgement_after_a_prompt() {
    let mut wire = connected(Behaviour {
        hold_prompts: true,
        ..Behaviour::default()
    });
    let vault = vault(&wire.device.master, 2);
    assert!(!wire.host.awaiting_user());
    sign(&mut wire, &vault.psbt, vault.device_fingerprint).unwrap();
    assert!(matches!(
        wire.events.last(),
        Some(TrezorEvent::AwaitingUser(UserPrompt::Confirm))
    ));
    assert!(
        wire.packets_since_event > 0,
        "the acknowledgement follows the prompt"
    );
    assert!(wire.host.awaiting_user());
    wire.user_confirms().unwrap(); // the payment; now the total
    assert!(wire.host.awaiting_user());
    wire.user_confirms().unwrap();
    assert!(matches!(
        wire.events.last(),
        Some(TrezorEvent::SignedPsbt(_))
    ));
    assert!(!wire.host.awaiting_user());

    // Cancelling hands the wait back to the device, which answers at once.
    sign(&mut wire, &vault.psbt, vault.device_fingerprint).unwrap();
    assert!(wire.host.awaiting_user());
    let cancel = wire.host.request_cancel().unwrap();
    assert!(!wire.host.awaiting_user());
    wire.to_device.extend(cancel);
    assert!(matches!(
        trezor_error(wire.run()),
        TrezorError::UserCancelled
    ));
    assert!(!wire.host.awaiting_user());
}

/// A cancel asked for when no prompt is left to answer lapses with its
/// request, which ends normally, rather than cancelling the next request.
#[test]
fn a_cancel_no_prompt_answers_lapses_with_its_request() {
    // During an xpub fetch that shows nothing on the device.
    let mut wire = connected(Behaviour::default());
    let packets = wire
        .host
        .request_xpub(&account(), XpubOptions::default())
        .unwrap();
    assert!(wire.host.request_cancel().unwrap().is_empty());
    wire.to_device.extend(packets);
    wire.run().unwrap();
    assert!(matches!(wire.events.last(), Some(TrezorEvent::Xpub { .. })));
    let spend = vault(&wire.device.master, 1);
    sign(&mut wire, &spend.psbt, spend.device_fingerprint).unwrap();
    assert!(matches!(
        wire.events.last(),
        Some(TrezorEvent::SignedPsbt(_))
    ));

    // After the user's last confirmation, while the device streams the inputs.
    let mut wire = connected(Behaviour {
        hold_prompts: true,
        ..Behaviour::default()
    });
    let spend = vault(&wire.device.master, 2);
    sign(&mut wire, &spend.psbt, spend.device_fingerprint).unwrap();
    wire.user_confirms().unwrap(); // the payment
    for packet in wire.device.user_confirms() {
        wire.take(packet).unwrap(); // the total, then the device's next request
    }
    assert!(!wire.host.awaiting_user());
    assert!(wire.host.request_cancel().unwrap().is_empty());
    wire.run().unwrap();
    assert!(matches!(
        wire.events.last(),
        Some(TrezorEvent::SignedPsbt(_))
    ));
    sign(&mut wire, &spend.psbt, spend.device_fingerprint).unwrap();
    assert!(
        matches!(
            wire.events.last(),
            Some(TrezorEvent::AwaitingUser(UserPrompt::Confirm))
        ),
        "the next signing asks the user, uncancelled"
    );
}

#[test]
fn a_second_signature_for_an_input_breaks_the_session() {
    let mut wire = connected(Behaviour {
        repeat_first_signature: true,
        ..Behaviour::default()
    });
    let vault = vault(&wire.device.master, 2);
    match trezor_error(sign(&mut wire, &vault.psbt, vault.device_fingerprint)) {
        TrezorError::Protocol(why) => assert!(why.contains("two signatures"), "{why}"),
        other => panic!("{other}"),
    }
    assert!(!wire.host.is_ready());
}

#[test]
fn an_unexpected_tx_request_breaks_the_session() {
    let mut wire = connected(Behaviour {
        ask_beyond_prev_inputs: true,
        ..Behaviour::default()
    });
    let vault = vault(&wire.device.master, 1);
    assert!(matches!(
        trezor_error(sign(&mut wire, &vault.psbt, vault.device_fingerprint)),
        TrezorError::Protocol(_)
    ));
    assert!(!wire.host.is_ready());
}

/// Every PSBT the client won't ask the device to sign, refused before
/// anything is sent.
#[test]
fn refuses_psbts_it_should_not_send() {
    let mut wire = connected(Behaviour::default());
    let base = vault(&wire.device.master, 2);
    let device = base.device_fingerprint;
    type Mutation = Box<dyn Fn(&mut Psbt)>;
    let stranger_key = bitcoin::secp256k1::PublicKey::from_secret_key(
        &Secp256k1::new(),
        &bitcoin::secp256k1::SecretKey::from_slice(&[7; 32]).unwrap(),
    );
    let cases: Vec<(&str, Mutation)> = vec![
        (
            "no previous transaction",
            Box::new(|p| p.inputs[0].non_witness_utxo = None),
        ),
        (
            // Consensus-invalid, and Trezor would count the amount twice.
            "the same output spent twice",
            Box::new(|p| {
                p.unsigned_tx.input[1] = p.unsigned_tx.input[0].clone();
                p.inputs[1] = p.inputs[0].clone();
            }),
        ),
        (
            "wrong previous transaction",
            Box::new(|p| p.inputs[0].non_witness_utxo = p.inputs[1].non_witness_utxo.clone()),
        ),
        (
            "witness UTXO disagrees",
            Box::new(|p| p.inputs[0].witness_utxo.as_mut().unwrap().value = Amount::from_sat(1)),
        ),
        (
            "no witness script",
            Box::new(|p| p.inputs[0].witness_script = None),
        ),
        (
            "witness script of another input",
            Box::new(|p| p.inputs[0].witness_script = p.inputs[1].witness_script.clone()),
        ),
        (
            // Consistent with each other, so only the spent output gives it away.
            "witness script and origins of another input",
            Box::new(|p| {
                p.inputs[0].witness_script = p.inputs[1].witness_script.clone();
                p.inputs[0].bip32_derivation = p.inputs[1].bip32_derivation.clone();
            }),
        ),
        (
            // The same outputs, so only the txid gives it away.
            "a previous transaction with another txid",
            Box::new(|p| {
                p.inputs[0].non_witness_utxo.as_mut().unwrap().lock_time =
                    absolute::LockTime::from_consensus(1);
            }),
        ),
        (
            "sighash other than ALL",
            Box::new(|p| p.inputs[0].sighash_type = Some(EcdsaSighashType::None.into())),
        ),
        ("no global xpubs", Box::new(|p| p.xpub.clear())),
        (
            "a vault xpub encoded for another network",
            Box::new(|p| {
                let (xpub, source) = p.xpub.pop_first().unwrap();
                p.xpub.insert(
                    Xpub {
                        network: NetworkKind::Main,
                        ..xpub
                    },
                    source,
                );
            }),
        ),
        (
            "a vault xpub at the wrong depth",
            Box::new(|p| {
                let (mut xpub, source) = p.xpub.pop_first().unwrap();
                xpub.depth += 1;
                p.xpub.insert(xpub, source);
            }),
        ),
        (
            "a native vault input with a redeem script",
            Box::new(|p| p.inputs[0].redeem_script = Some(ScriptBuf::new())),
        ),
        (
            "a vault input with Taproot metadata",
            Box::new(move |p| {
                p.inputs[0].tap_internal_key = Some(stranger_key.x_only_public_key().0)
            }),
        ),
        (
            "a missing key origin",
            Box::new(|p| {
                let key = *p.inputs[0].bip32_derivation.keys().next().unwrap();
                p.inputs[0].bip32_derivation.remove(&key);
            }),
        ),
        (
            "an extra key origin",
            Box::new(move |p| {
                let origin = p.inputs[0]
                    .bip32_derivation
                    .values()
                    .next()
                    .unwrap()
                    .clone();
                p.inputs[0].bip32_derivation.insert(stranger_key, origin);
            }),
        ),
        (
            "an OP_RETURN output",
            Box::new(|p| {
                p.unsigned_tx.output[0].script_pubkey = ScriptBuf::new_op_return([1u8; 4])
            }),
        ),
        (
            "a script without an address",
            Box::new(|p| {
                p.unsigned_tx.output[0].script_pubkey = ScriptBuf::from_bytes(vec![0x51, 0x52])
            }),
        ),
        (
            "no outputs",
            Box::new(|p| {
                p.unsigned_tx.output.clear();
                p.outputs.clear();
            }),
        ),
        (
            "maps out of step",
            Box::new(|p| {
                p.outputs.pop();
            }),
        ),
    ];
    for (case, mutate) in cases {
        let mut psbt = base.psbt.clone();
        mutate(&mut psbt);
        let error = trezor_error(wire.host.request_sign_psbt(&psbt, device));
        assert!(
            matches!(error, TrezorError::InvalidRequest(_)),
            "{case}: {error}"
        );
        assert!(wire.host.is_ready(), "{case}");
    }

    // A signer that isn't in the vault.
    let stranger = Fingerprint::from([1, 2, 3, 4]);
    assert!(matches!(
        trezor_error(wire.host.request_sign_psbt(&base.psbt, stranger)),
        TrezorError::InvalidRequest(_)
    ));
    // OP_RETURN is refused as such, before an address is looked for.
    let mut op_return = base.psbt.clone();
    op_return.unsigned_tx.output[0].script_pubkey = ScriptBuf::new_op_return([1u8; 4]);
    match trezor_error(wire.host.request_sign_psbt(&op_return, device)) {
        TrezorError::InvalidRequest(why) => assert!(why.contains("OP_RETURN"), "{why}"),
        other => panic!("{other}"),
    }
    // Two vaults that both hold this Safe 7's key.
    let sibling = vault_with(&wire.device.master, [master_key(4), master_key(5)], 1);
    let mut mixed = base.psbt.clone();
    mixed.inputs[1] = sibling.psbt.inputs[0].clone();
    mixed.unsigned_tx.input[1] = sibling.psbt.unsigned_tx.input[0].clone();
    mixed.xpub.extend(sibling.psbt.xpub.clone());
    match trezor_error(wire.host.request_sign_psbt(&mixed, device)) {
        TrezorError::InvalidRequest(why) => assert!(why.contains("different vault"), "{why}"),
        other => panic!("{other}"),
    }
    // Inputs from two vaults, one without this Safe 7.
    let other_vault = vault(&master_key(9), 1);
    let mut mixed = base.psbt.clone();
    mixed.inputs[1] = other_vault.psbt.inputs[0].clone();
    mixed.unsigned_tx.input[1] = other_vault.psbt.unsigned_tx.input[0].clone();
    mixed.xpub.extend(other_vault.psbt.xpub.clone());
    assert!(matches!(
        trezor_error(wire.host.request_sign_psbt(&mixed, device)),
        TrezorError::InvalidRequest(_)
    ));
    assert!(
        !wire
            .device
            .received
            .contains(&mt(MessageType::MessageType_SignTx)),
        "nothing was sent"
    );
}

/// The device's key must sit at a BIP-48 P2WSH vault path on the session's
/// network: a testnet session never signs at mainnet paths, nor the reverse.
/// The device checks paths too, but only as its safety checks are set.
#[test]
fn signs_only_at_vault_paths_on_the_sessions_network() {
    let mut wire = connected(Behaviour::default());
    let master = wire.device.master;
    for account_path in [
        "m/48'/0'/0'/2'", // mainnet coin type
        "m/48'/1'/0'/1'", // P2SH-P2WSH
        "m/48'/1'/0/2'",  // unhardened account
        "m/45'/1'/0'/2'", // not BIP-48
    ] {
        let path = DerivationPath::from_str(account_path).unwrap();
        let elsewhere = vault_at(&master, [master_key(2), master_key(3)], &path, 1, 1);
        assert!(
            matches!(
                trezor_error(
                    wire.host
                        .request_sign_psbt(&elsewhere.psbt, elsewhere.device_fingerprint)
                ),
                TrezorError::InvalidRequest(_)
            ),
            "{account_path}"
        );
    }
    assert!(wire.host.is_ready());
    assert!(
        !wire
            .device
            .received
            .contains(&mt(MessageType::MessageType_SignTx)),
        "nothing was sent"
    );

    let mut mainnet = Wire::with_config(
        Behaviour::default(),
        SessionConfig {
            network: Network::Bitcoin,
            ..config()
        },
        None,
    );
    mainnet.connect().unwrap();
    let testnet_vault = vault(&mainnet.device.master, 1);
    assert!(matches!(
        trezor_error(
            mainnet
                .host
                .request_sign_psbt(&testnet_vault.psbt, testnet_vault.device_fingerprint)
        ),
        TrezorError::InvalidRequest(_)
    ));
}

/// Change that isn't exactly this vault goes to the device as an external
/// output, which the user confirms, rather than being hidden.
#[test]
fn change_must_be_this_vault_to_be_hidden() {
    let mut wire = connected(Behaviour::default());
    let vault = vault(&wire.device.master, 1);
    let mut stripped = vault.psbt.clone();
    stripped.outputs[1].bip32_derivation.clear();
    sign(&mut wire, &stripped, vault.device_fingerprint).unwrap();
    let prompts = wire
        .events
        .iter()
        .filter(|e| matches!(e, TrezorEvent::AwaitingUser(_)))
        .count();
    assert_eq!(prompts, 3, "payment, change shown as a payment, total");

    // This vault's keys, but off its receive and change chains.
    let off_chain = vault_at(
        &wire.device.master,
        [master_key(2), master_key(3)],
        &account(),
        2,
        1,
    );
    wire.events.clear();
    sign(&mut wire, &off_chain.psbt, off_chain.device_fingerprint).unwrap();
    let prompts = wire
        .events
        .iter()
        .filter(|e| matches!(e, TrezorEvent::AwaitingUser(_)))
        .count();
    assert_eq!(prompts, 3, "payment, change on chain 2 shown, total");

    wire.events.clear();
    sign(&mut wire, &vault.psbt, vault.device_fingerprint).unwrap();
    let prompts = wire
        .events
        .iter()
        .filter(|e| matches!(e, TrezorEvent::AwaitingUser(_)))
        .count();
    assert_eq!(prompts, 2);
}

#[test]
fn session_is_send() {
    fn assert_send<T: Send>() {}
    assert_send::<TrezorSession>();
    assert_send::<TrezorEvent>();
    assert_send::<TrezorError>();
    assert_send::<PairingCredential>();
}

#[test]
fn credentials_round_trip_and_reject_garbage() {
    let mut wire = Wire::new(Behaviour::default(), None);
    let (_, credential) = wire.connect().unwrap();
    let credential = credential.unwrap();
    let bytes = credential.to_bytes();
    let back = PairingCredential::from_bytes(&bytes).unwrap();
    assert_eq!(
        back.trezor_static_public_key(),
        credential.trezor_static_public_key()
    );
    // No run of the host's private key in `Debug`, in hex or as a byte list.
    let shown = format!("{back:?}");
    for window in back.host_static_key.windows(4) {
        let hex: String = window.iter().map(|byte| format!("{byte:02x}")).collect();
        let list = window
            .iter()
            .map(u8::to_string)
            .collect::<Vec<_>>()
            .join(", ");
        assert!(!shown.contains(&hex) && !shown.contains(&list), "{shown}");
    }
    for garbage in [&[][..], &[2u8; 80][..], &bytes[..64], &[1u8; 300][..]] {
        assert!(matches!(
            PairingCredential::from_bytes(garbage),
            Err(TrezorError::InvalidRequest(_))
        ));
    }
}
