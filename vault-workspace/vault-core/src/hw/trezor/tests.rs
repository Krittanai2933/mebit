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
use bitcoin::psbt::Psbt;
use bitcoin::secp256k1::{Message, Secp256k1};
use bitcoin::sighash::{EcdsaSighashType, SighashCache};
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
    GetPublicKey, MultisigRedeemScriptType, OutputScriptType, PrevInput, PrevOutput, PrevTx,
    PublicKey, SignTx, TxAckInput, TxAckOutput, TxAckPrevInput, TxAckPrevMeta, TxAckPrevOutput,
    TxInput, TxOutput, TxRequest, tx_request,
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
    PACKET_LEN, PairingCredential, Safe7Identity, SessionConfig, SessionPassphrase, TrezorError,
    TrezorEvent, TrezorSession, UserPrompt, XpubOptions, cpace,
};
use crate::error::VaultCoreError;
use crate::hw::psbt_check;
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
            repeat_first_signature: false,
            ask_beyond_prev_inputs: false,
            hold_prompts: false,
            confirm_connection: true,
            busy: false,
            duplicate_messages: false,
            miss_allocations: 0,
            claimed_pairing: None,
            xpub_answer: XpubAnswer::Honest,
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
/// outputs (confirming external ones), the total, every previous transaction
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
                let external = output.script_type() == OutputScriptType::PAYTOADDRESS;
                self.outputs.push(output);
                if external {
                    SignStep::Prompt(PromptFor::Output)
                } else {
                    self.after_output()
                }
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
                    if let Err(why) =
                        check_previous(&self.inputs[input as usize], &meta_ref, &inputs, &outputs)
                    {
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
                match self.sign(i as usize, signed_index, master) {
                    Ok(signature) => self.signature = Some((reported_index, signature)),
                    Err(why) => return SignStep::Fail(why),
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
            let hash = self.inputs[input as usize].prev_hash().to_vec();
            self.stage = Stage::Prev {
                input,
                meta: None,
                inputs: Vec::new(),
                outputs: Vec::new(),
            };
            self.request(tx_request::RequestType::TXMETA, 0, Some(hash))
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
        let tx = self.transaction()?;
        let input = &self.inputs[message_of];
        let script = multisig_script(input.multisig.as_ref().ok_or("no multisig")?);
        let sighash = SighashCache::new(&tx)
            .p2wsh_signature_hash(
                message_of,
                &script,
                Amount::from_sat(input.amount()),
                EcdsaSighashType::All,
            )
            .map_err(|_| "sighash")?;
        let path: DerivationPath = self.inputs[index]
            .address_n
            .iter()
            .map(|&n| ChildNumber::from(n))
            .collect();
        let key = master.derive_priv(&secp, &path).unwrap().private_key;
        let ours = key.public_key(&secp).serialize();
        let multisig = self.inputs[index].multisig.as_ref().unwrap();
        if !multisig_keys(multisig)
            .iter()
            .any(|k| k.serialize() == ours)
        {
            return Err("Pubkey not found in multisig script");
        }
        let message = Message::from_digest(sighash.to_byte_array());
        Ok(secp.sign_ecdsa(&message, &key).serialize_der().to_vec())
    }

    fn transaction(&self) -> Result<Transaction, &'static str> {
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
                let script_pubkey = match output.script_type() {
                    OutputScriptType::PAYTOADDRESS => Address::from_str(output.address())
                        .map_err(|_| "bad address")?
                        .require_network(NETWORK)
                        .map_err(|_| "wrong network")?
                        .script_pubkey(),
                    OutputScriptType::PAYTOWITNESS => {
                        let script = multisig_script(
                            output.multisig.as_ref().ok_or("change without multisig")?,
                        );
                        ScriptBuf::new_p2wsh(&script.wscript_hash())
                    }
                    _ => return Err("unsupported output"),
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
    let script = multisig_script(input.multisig.as_ref().ok_or("no multisig")?);
    if spent.script_pubkey != ScriptBuf::new_p2wsh(&script.wscript_hash()) {
        return Err("Input does not match scriptPubKey");
    }
    Ok(())
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
        "m/44'/1'/0'",
        "m/86'/1'/0'",
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
    match trezor_error(sign(&mut wire, &vault.psbt, vault.device_fingerprint)) {
        TrezorError::Signatures(psbt_check::SignatureCheckError::InvalidSignature { input: 1 }) => {
        }
        other => panic!("{other}"),
    }
    assert!(wire.host.is_ready(), "the exchange ended in step");
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
