//! Blockstream Jade's client protocol, with no transport inside.
//!
//! [`JadeSession`] turns each operation into request bytes, and turns whatever
//! bytes the Jade sends back into a [`JadeEvent`]. Whoever owns the radio (the
//! `jade-ble` crate) only moves bytes: write what a `request_*` method returns,
//! feed every BLE indication into [`JadeSession::receive`], and act on the
//! event. Every protocol rule therefore lives here, once, where byte fixtures
//! can test it — see the fake device at the bottom of this file.
//!
//! Checked against Jade firmware tag 1.0.41 (github.com/Blockstream/Jade,
//! `dd45a7dc71`); the paths below are relative to that repo.
//!
//! - **Wire format.** One CBOR map per message: `{id, method, params}` out,
//!   `{id, result}` or `{id, error}` back. BLE and USB input both go through
//!   `handle_data()` in `main/wire.c`, so this is the same protocol either way.
//!   There is no framing beyond CBOR itself: the Jade buffers writes until one
//!   complete map parses, and throws a partial message away if the next chunk
//!   is more than 2 s late — so a host writes a request's chunks back to back.
//! - **Reply size.** A reply is at most 3 KB (`MAX_OUTPUT_MSG_SIZE` in
//!   `main/process.h`), so a signed PSBT comes back in 3008-byte pieces that
//!   are fetched one by one with `get_extended_data`
//!   (`main/process/sign_psbt.c`). The session runs that exchange itself by
//!   answering a piece with [`JadeEvent::Send`].
//! - **Unlock is per connection.** A request from any connection other than the
//!   one that ran `auth_user` fails as locked, and a PIN wallet locks again when
//!   BLE drops (`main/process/dashboard.c`). Hence one session per connection.
//! - **Replies outlive the link.** The Jade queues replies per transport, not
//!   per connection, so one it finishes after a link drops (the user confirming
//!   after the host gave up) goes out on the next link; observed on a Jade Core.
//!   Each session therefore starts its request ids at random, as jadepy does,
//!   and refuses a reply for any other id.

use std::io;
use std::str::FromStr;
use std::time::{SystemTime, UNIX_EPOCH};

use bitcoin::bip32::{ChildNumber, DerivationPath, Fingerprint, Xpub};
use bitcoin::secp256k1::Secp256k1;
use bitcoin::sighash::SighashCache;
use bitcoin::{Network, NetworkKind, Psbt};
use ciborium::Value;

use crate::error::VaultCoreError;

/// `MAX_INPUT_MSG_SIZE` on boards with PSRAM, which includes Jade Core and Jade
/// Plus (`main/process.h`). The device rejects anything bigger.
const MAX_REQUEST_LEN: usize = 401 * 1024;

/// A reply is at most 3 KB; 4x that is headroom for a future firmware, not a
/// size we expect. A reply that grows past it is garbage, not a slow reply.
const MAX_REPLY_LEN: usize = 16 * 1024;

/// A signed PSBT is bounded by the largest request plus signatures: about 140
/// pieces of 3008 bytes. Anything above this is a corrupt `seqlen`.
const MAX_SIGNED_PSBT_PIECES: u64 = 256;

#[derive(Debug, thiserror::Error)]
pub enum JadeError {
    /// -32000: the user declined on the device.
    #[error("declined on the device")]
    UserCancelled,

    /// -32002: the Jade is locked, or was unlocked over a different connection.
    #[error("locked, or unlocked over a different connection — unlock over this one")]
    Locked,

    /// -32003, or a mismatch caught before asking: a Jade pins its wallet to
    /// mainnet or to the test networks the first time it is unlocked.
    #[error("network mismatch: {0}")]
    NetworkMismatch(String),

    #[error("no wallet on the device — restore or create one on the Jade first")]
    NotInitialised,

    /// `auth_user` returned `false`: wrong PIN, or the PIN step was abandoned.
    #[error("unlock failed (wrong PIN, or PIN entry abandoned)")]
    UnlockFailed,

    /// Any other error reply, as the device worded it.
    #[error("device error {code}: {message}")]
    Rpc { code: i64, message: String },

    /// The bytes on the wire broke the protocol. The session is out of step
    /// with the device after this, and refuses further calls: reconnect.
    #[error("protocol error: {0}")]
    Protocol(String),

    /// A request while another is pending, or a pinserver response nobody asked for.
    #[error("call made out of order")]
    OutOfOrder,

    #[error("request is {0} bytes; the Jade accepts at most {MAX_REQUEST_LEN}")]
    RequestTooLarge(usize),

    /// The Jade signs only inputs whose `bip32_derivation` names its master
    /// fingerprint, and returns the PSBT unchanged — without an error — when
    /// there were none.
    #[error("the PSBT came back without any new signature (is this the vault's Jade?)")]
    NoSignatures,

    #[error("the PSBT came back changed beyond the device's own signatures")]
    PsbtModified,

    #[error("input {input} has no signature for the device's key")]
    MissingSignature { input: usize },

    #[error("the signature on input {input} does not verify")]
    InvalidSignature { input: usize },
}

/// `JADE_STATE` from `get_version_info` (`docs/index.rst`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum JadeState {
    /// No wallet on the device.
    Uninit,
    /// A recovery phrase was entered on the device but not yet saved behind a
    /// PIN. The next unlock asks for a new PIN.
    Unsaved,
    /// A saved wallet, not unlocked over this connection yet.
    Locked,
    /// Unlocked over this connection.
    Ready,
    /// A Temporary Signer wallet, forgotten on reboot.
    Temp,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VersionInfo {
    /// `JADE_VERSION`, e.g. `1.0.41`.
    pub firmware: String,
    /// `BOARD_TYPE`: `JADE_V2C` is a Jade Core.
    pub board: String,
    /// `JADE_CONFIG`: `BLE` when the firmware has Bluetooth.
    pub config: String,
    pub state: JadeState,
    /// `JADE_NETWORKS`: `MAIN` or `TEST` once the wallet is pinned to a network
    /// type, `ALL` before that.
    pub networks: String,
}

impl VersionInfo {
    /// Whether unlocking for `network` can work, checked before anyone is asked
    /// for a PIN.
    ///
    /// The first unlock pins a Jade's wallet to mainnet or to the test networks
    /// (`main/process/auth_user.c`), and a Jade Core has no setting to undo it:
    /// the only toggle is in QR mode, which needs a camera. Refuses an empty
    /// Jade too: `auth_user` would start recovery-phrase entry on the device
    /// while the host sat waiting.
    pub fn check_usable(&self, network: Network) -> Result<(), VaultCoreError> {
        if self.state == JadeState::Uninit {
            return Err(JadeError::NotInitialised.into());
        }
        let pinned_to_mainnet = match self.networks.as_str() {
            "ALL" => return Ok(()),
            "MAIN" => true,
            "TEST" => false,
            other => return Err(protocol(format!("unknown JADE_NETWORKS {other:?}")).into()),
        };
        if pinned_to_mainnet != (network == Network::Bitcoin) {
            return Err(JadeError::NetworkMismatch(format!(
                "this Jade's wallet is pinned to {} networks; asked for {network}",
                if pinned_to_mainnet { "main" } else { "test" }
            ))
            .into());
        }
        Ok(())
    }

    fn parse(result: Value) -> Result<Self, JadeError> {
        let Value::Map(fields) = result else {
            return Err(protocol("get_version_info result is not a map"));
        };
        let text = |key: &str| {
            field(&fields, key)
                .and_then(Value::as_text)
                .map(str::to_owned)
                .ok_or_else(|| protocol(format!("version info has no text {key}")))
        };
        let state = match text("JADE_STATE")?.as_str() {
            "UNINIT" => JadeState::Uninit,
            "UNSAVED" => JadeState::Unsaved,
            "LOCKED" => JadeState::Locked,
            "READY" => JadeState::Ready,
            "TEMP" => JadeState::Temp,
            other => return Err(protocol(format!("unknown JADE_STATE {other:?}"))),
        };
        Ok(Self {
            firmware: text("JADE_VERSION")?,
            board: text("BOARD_TYPE")?,
            config: text("JADE_CONFIG")?,
            state,
            networks: text("JADE_NETWORKS")?,
        })
    }
}

/// What the host does next, as returned by [`JadeSession::receive`].
#[derive(Debug)]
pub enum JadeEvent {
    /// The reply isn't complete yet: feed the next indication.
    NeedMore,
    /// Write these bytes to the Jade. This is the next request of an exchange
    /// that is still running — today, the next `get_extended_data` of a signing.
    Send(Vec<u8>),
    /// POST `body` (JSON) to `url`, hand the response body to
    /// [`JadeSession::pinserver_response`], and write the bytes it returns. If
    /// the POST fails, write what [`JadeSession::pinserver_failed`] returns.
    Pinserver {
        url: String,
        body: String,
    },
    VersionInfo(VersionInfo),
    Unlocked,
    Xpub(Xpub),
    /// Only ever the PSBT that was sent plus valid signatures from the signer's
    /// keys: at least one, one for every signer key its inputs list, and no
    /// other change.
    SignedPsbt(Psbt),
}

/// The protocol state of one connection to one Jade. See the module docs.
pub struct JadeSession {
    network: Network,
    next_id: u32,
    inbox: Vec<u8>,
    state: State,
}

enum State {
    Idle,
    /// Waiting for the reply to request `id`.
    Awaiting {
        id: String,
        op: Op,
    },
    /// Waiting for the host to relay a pinserver request.
    Pinserver {
        on_reply: String,
    },
    /// A protocol error left us out of step with the device.
    Broken,
}

enum Op {
    VersionInfo,
    Unlock,
    Xpub(DerivationPath),
    Sign(Box<Signing>),
}

/// A signed PSBT arriving in pieces.
struct Signing {
    unsigned: Psbt,
    signer: Fingerprint,
    /// Id of the `sign_psbt` request, which every `get_extended_data` names.
    origid: String,
    seqlen: Option<u64>,
    received: u64,
    bytes: Vec<u8>,
}

impl JadeSession {
    pub fn new(network: Network) -> Self {
        // Not secret, only different from the last connection's ids: the clock
        // will do if the OS has no randomness to give.
        let first = getrandom::u32().unwrap_or_else(|_| {
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map_or(0, |since| since.subsec_nanos())
        });
        Self::with_ids_after(network, first)
    }

    /// Request ids count up from `last_id + 1`.
    fn with_ids_after(network: Network, last_id: u32) -> Self {
        Self {
            network,
            next_id: last_id,
            inbox: Vec::new(),
            state: State::Idle,
        }
    }

    /// Asked as `nonblocking`, so the Jade's transport task answers at once,
    /// even while a screen on the device waits for the user
    /// (`handle_immediate_message()` in `main/wire.c`).
    pub fn request_version_info(&mut self) -> Result<Vec<u8>, VaultCoreError> {
        let id = self.next_request_id()?;
        let params = map([("nonblocking", Value::Bool(true))]);
        self.request(id, "get_version_info", Some(params), Op::VersionInfo)
    }

    /// `unix_time` sets the device clock, as `auth_user` expects.
    pub fn request_unlock(&mut self, unix_time: u64) -> Result<Vec<u8>, VaultCoreError> {
        let id = self.next_request_id()?;
        let params = map([
            ("network", self.network_param()),
            ("epoch", uint(unix_time)),
        ]);
        self.request(id, "auth_user", Some(params), Op::Unlock)
    }

    /// The device answers without asking the user. An empty path is the master key.
    pub fn request_xpub(&mut self, path: &DerivationPath) -> Result<Vec<u8>, VaultCoreError> {
        let id = self.next_request_id()?;
        let path_param = Value::Array(
            path.into_iter()
                .map(|&c| uint(u32::from(c).into()))
                .collect(),
        );
        let params = map([("network", self.network_param()), ("path", path_param)]);
        self.request(id, "get_xpub", Some(params), Op::Xpub(path.clone()))
    }

    /// `signer` is the master fingerprint the vault expects to sign with; the
    /// returned PSBT is only accepted if the new signatures come from its keys.
    /// The device replies only after the user has confirmed the outputs and fee.
    pub fn request_sign_psbt(
        &mut self,
        psbt: &Psbt,
        signer: Fingerprint,
    ) -> Result<Vec<u8>, VaultCoreError> {
        let id = self.next_request_id()?;
        let params = map([
            ("network", self.network_param()),
            ("psbt", Value::Bytes(psbt.serialize())),
        ]);
        let signing = Signing {
            unsigned: psbt.clone(),
            signer,
            origid: id.clone(),
            seqlen: None,
            received: 0,
            bytes: Vec::new(),
        };
        self.request(id, "sign_psbt", Some(params), Op::Sign(Box::new(signing)))
    }

    /// The pinserver's response body for the last [`JadeEvent::Pinserver`],
    /// turned into the request that hands it to the device.
    pub fn pinserver_response(&mut self, body: &str) -> Result<Vec<u8>, VaultCoreError> {
        let State::Pinserver { on_reply } = &self.state else {
            return Err(JadeError::OutOfOrder.into());
        };
        let on_reply = on_reply.clone();
        let params = serde_json::from_str::<serde_json::Value>(body)
            .map_err(|e| e.to_string())
            .and_then(|json| Value::serialized(&json).map_err(|e| e.to_string()));
        let params = match params {
            Ok(params) => params,
            Err(e) => {
                // The device is waiting on this reply; nothing brings it back but a reconnect.
                self.state = State::Broken;
                return Err(protocol(format!("pinserver reply is not JSON: {e}")).into());
            }
        };
        self.state = State::Idle;
        let id = self.next_request_id()?;
        self.request(id, &on_reply, Some(params), Op::Unlock)
    }

    /// The request to write instead of a pinserver response when the POST
    /// failed. The device waits for one or the other; `cancel` sends it back to
    /// its home screen without a reply (`handle_pin()` in
    /// `main/process/pinclient.c`), so the session is idle again at once and
    /// the unlock can be retried over the same connection.
    pub fn pinserver_failed(&mut self) -> Result<Vec<u8>, VaultCoreError> {
        if !matches!(self.state, State::Pinserver { .. }) {
            return Err(JadeError::OutOfOrder.into());
        }
        self.state = State::Idle;
        let id = self.next_request_id()?;
        Ok(encode_request(&id, "cancel", None)?)
    }

    /// For when the host gives up on the exchange in flight — a timeout, a
    /// dropped link. The device may still answer it, so every later call fails
    /// until the host reconnects. Does nothing between exchanges.
    pub fn abandon(&mut self) {
        if !matches!(self.state, State::Idle) {
            self.state = State::Broken;
            self.inbox.clear();
        }
    }

    /// Feeds the bytes of one indication (any split is fine). Device error
    /// replies leave the session usable; [`JadeError::Protocol`] leaves it
    /// broken, and every later call fails until the host reconnects.
    pub fn receive(&mut self, bytes: &[u8]) -> Result<JadeEvent, VaultCoreError> {
        let outcome = self.receive_inner(bytes);
        if let Err(VaultCoreError::Jade(JadeError::Protocol(_))) = &outcome {
            self.state = State::Broken;
            self.inbox.clear();
        }
        outcome
    }

    fn receive_inner(&mut self, bytes: &[u8]) -> Result<JadeEvent, VaultCoreError> {
        match self.state {
            State::Awaiting { .. } => {}
            State::Broken => return Err(broken().into()),
            _ if bytes.is_empty() => return Ok(JadeEvent::NeedMore),
            _ => return Err(protocol("unsolicited bytes from the device").into()),
        }
        self.inbox.extend_from_slice(bytes);
        if self.inbox.len() > MAX_REPLY_LEN {
            return Err(protocol(format!("reply grew past {MAX_REPLY_LEN} bytes")).into());
        }
        let Some(reply) = self.take_reply()? else {
            return Ok(JadeEvent::NeedMore);
        };
        // Strictly one reply per request: anything behind it is stale or garbage.
        if !self.inbox.is_empty() {
            return Err(protocol("unexpected bytes after a reply").into());
        }
        let State::Awaiting { id, op } = std::mem::replace(&mut self.state, State::Idle) else {
            unreachable!("checked on entry");
        };
        reply.check_id(&id)?;
        let Reply {
            seqnum,
            seqlen,
            outcome,
            ..
        } = reply;
        let result = outcome?;
        match op {
            Op::VersionInfo => Ok(JadeEvent::VersionInfo(VersionInfo::parse(result)?)),
            Op::Unlock => self.on_unlock(result),
            Op::Xpub(path) => Ok(JadeEvent::Xpub(parse_xpub(result, self.network, &path)?)),
            Op::Sign(signing) => self.on_signed_piece(*signing, seqnum, seqlen, result),
        }
    }

    /// Pops the next complete reply off the inbox, skipping device log lines.
    fn take_reply(&mut self) -> Result<Option<Reply>, JadeError> {
        loop {
            let mut rest = self.inbox.as_slice();
            let value: Value = match ciborium::from_reader(&mut rest) {
                Ok(value) => value,
                Err(ciborium::de::Error::Io(e)) if e.kind() == io::ErrorKind::UnexpectedEof => {
                    return Ok(None);
                }
                Err(e) => return Err(protocol(format!("malformed CBOR: {e}"))),
            };
            let used = self.inbox.len() - rest.len();
            self.inbox.drain(..used);
            let Value::Map(fields) = value else {
                return Err(protocol("reply is not a CBOR map"));
            };
            // Debug firmware interleaves `{"log": ...}` lines (`read_cbor_message`
            // in `jadepy/jade.py`); production firmware logs nothing.
            if field(&fields, "id").is_none() && field(&fields, "log").is_some() {
                continue;
            }
            return Reply::parse(fields).map(Some);
        }
    }

    fn on_unlock(&mut self, result: Value) -> Result<JadeEvent, VaultCoreError> {
        match result {
            Value::Bool(true) => Ok(JadeEvent::Unlocked),
            Value::Bool(false) => Err(JadeError::UnlockFailed.into()),
            Value::Map(fields) => {
                let (url, body, on_reply) = parse_pinserver_request(&fields)?;
                self.state = State::Pinserver { on_reply };
                Ok(JadeEvent::Pinserver { url, body })
            }
            other => Err(protocol(format!("unexpected auth_user result {other:?}")).into()),
        }
    }

    fn on_signed_piece(
        &mut self,
        mut signing: Signing,
        seqnum: Option<u64>,
        seqlen: Option<u64>,
        result: Value,
    ) -> Result<JadeEvent, VaultCoreError> {
        let piece = result
            .into_bytes()
            .map_err(|_| protocol("sign_psbt reply is not bytes"))?;
        // Without sequence fields the reply is the whole PSBT.
        let (seqnum, seqlen) = (seqnum.unwrap_or(1), seqlen.unwrap_or(1));
        if seqlen != *signing.seqlen.get_or_insert(seqlen) {
            return Err(protocol("seqlen changed in the middle of a signed PSBT").into());
        }
        if seqlen == 0 || seqlen > MAX_SIGNED_PSBT_PIECES {
            return Err(protocol(format!("implausible seqlen {seqlen}")).into());
        }
        if seqnum != signing.received + 1 {
            let expected = signing.received + 1;
            return Err(protocol(format!("got piece {seqnum}, expected {expected}")).into());
        }
        signing.received = seqnum;
        signing.bytes.extend_from_slice(&piece);

        if seqnum < seqlen {
            // `check_extended_data_fields()` in `main/process/process_utils.c`
            // insists on exactly these values: the original id and method, the
            // next piece, and the same seqlen.
            let params = map([
                ("origid", text(&signing.origid)),
                ("orig", text("sign_psbt")),
                ("seqnum", uint(seqnum + 1)),
                ("seqlen", uint(seqlen)),
            ]);
            let id = self.next_request_id()?;
            let request = self.request(
                id,
                "get_extended_data",
                Some(params),
                Op::Sign(Box::new(signing)),
            )?;
            return Ok(JadeEvent::Send(request));
        }
        let signed = verify_signed_psbt(&signing.unsigned, &signing.bytes, signing.signer)?;
        Ok(JadeEvent::SignedPsbt(signed))
    }

    fn next_request_id(&mut self) -> Result<String, JadeError> {
        match self.state {
            State::Idle => {}
            State::Broken => return Err(broken()),
            _ => return Err(JadeError::OutOfOrder),
        }
        // Decimal u32: at most 10 characters, inside the firmware's 16 (`MAXLEN_ID`).
        self.next_id = self.next_id.wrapping_add(1);
        Ok(self.next_id.to_string())
    }

    fn request(
        &mut self,
        id: String,
        method: &str,
        params: Option<Value>,
        op: Op,
    ) -> Result<Vec<u8>, VaultCoreError> {
        let bytes = encode_request(&id, method, params)?;
        self.state = State::Awaiting { id, op };
        Ok(bytes)
    }

    fn network_param(&self) -> Value {
        // The only Bitcoin names the firmware knows (`main/utils/network.c`).
        // Testnet4 and signet have none of their own: they share testnet's xpub
        // and address encodings.
        text(match self.network {
            Network::Bitcoin => "mainnet",
            Network::Regtest => "localtest",
            _ => "testnet",
        })
    }
}

/// One reply off the wire: exactly one of `result` or `error`
/// (`validate_reply` in `jadepy/jade.py`).
struct Reply {
    id: String,
    seqnum: Option<u64>,
    seqlen: Option<u64>,
    outcome: Result<Value, JadeError>,
}

impl Reply {
    fn parse(fields: Vec<(Value, Value)>) -> Result<Self, JadeError> {
        let (mut id, mut result, mut error, mut seqnum, mut seqlen) =
            (None, None, None, None, None);
        for (key, value) in fields {
            match key.as_text() {
                Some("id") => {
                    id = Some(
                        value
                            .into_text()
                            .map_err(|_| protocol("reply id is not text"))?,
                    )
                }
                Some("result") => result = Some(value),
                Some("error") => error = Some(value),
                Some("seqnum") => seqnum = Some(unsigned(&value, "seqnum")?),
                Some("seqlen") => seqlen = Some(unsigned(&value, "seqlen")?),
                _ => {}
            }
        }
        let outcome = match (result, error) {
            (Some(result), None) => Ok(result),
            (None, Some(error)) => Err(rpc_error(error)?),
            _ => return Err(protocol("reply must carry exactly one of result and error")),
        };
        Ok(Self {
            id: id.ok_or_else(|| protocol("reply has no id"))?,
            seqnum,
            seqlen,
            outcome,
        })
    }

    /// A request the device could not even read an id from is answered with
    /// id "00" (the reject path in `main/wire.c`); that one is ours as well.
    fn check_id(&self, expected: &str) -> Result<(), JadeError> {
        if self.id == expected || (self.id == "00" && self.outcome.is_err()) {
            Ok(())
        } else {
            Err(protocol(format!(
                "reply id {:?} does not answer request {expected:?}",
                self.id
            )))
        }
    }
}

/// Error codes from `main/utils/cbor_rpc.h`.
fn rpc_error(error: Value) -> Result<JadeError, JadeError> {
    let Value::Map(fields) = error else {
        return Err(protocol("error is not a map"));
    };
    let code = field(&fields, "code")
        .and_then(Value::as_integer)
        .and_then(|code| i64::try_from(code).ok())
        .ok_or_else(|| protocol("error has no integer code"))?;
    let message = field(&fields, "message")
        .and_then(Value::as_text)
        .unwrap_or_default()
        .to_owned();
    Ok(match code {
        -32000 => JadeError::UserCancelled,
        -32002 => JadeError::Locked,
        -32003 => JadeError::NetworkMismatch(message),
        _ => JadeError::Rpc { code, message },
    })
}

/// `{"http_request": {"params": {"urls": [..], "data": {..}}, "on-reply": <method>}}`
/// (`main/process/auth_user.c`). Only a clearnet https URL is used: the data
/// is end-to-end encrypted between device and pinserver either way, but we
/// have no Tor, and see no reason to relay it in plaintext.
fn parse_pinserver_request(
    fields: &[(Value, Value)],
) -> Result<(String, String, String), JadeError> {
    let malformed = || protocol("malformed pinserver request");
    let request = field(fields, "http_request")
        .and_then(Value::as_map)
        .ok_or_else(malformed)?;
    let on_reply = field(request, "on-reply")
        .and_then(Value::as_text)
        .ok_or_else(malformed)?;
    let params = field(request, "params")
        .and_then(Value::as_map)
        .ok_or_else(malformed)?;
    let url = field(params, "urls")
        .and_then(Value::as_array)
        .ok_or_else(malformed)?
        .iter()
        .filter_map(Value::as_text)
        .find(|url| is_clearnet_https(url))
        .ok_or_else(|| protocol("the device offered no clearnet https pinserver URL"))?;
    let data = field(params, "data").ok_or_else(malformed)?;
    let body = serde_json::to_string(data)
        .map_err(|e| protocol(format!("pinserver data is not JSON-compatible: {e}")))?;
    Ok((url.to_owned(), body, on_reply.to_owned()))
}

fn is_clearnet_https(url: &str) -> bool {
    url.strip_prefix("https://")
        .and_then(|rest| rest.split(['/', ':']).next())
        .is_some_and(|host| !host.is_empty() && !host.ends_with(".onion"))
}

/// Checks that the device answered for the path and network we asked about.
fn parse_xpub(result: Value, network: Network, path: &DerivationPath) -> Result<Xpub, JadeError> {
    let encoded = result
        .into_text()
        .map_err(|_| protocol("get_xpub result is not text"))?;
    let xpub = Xpub::from_str(&encoded)
        .map_err(|e| protocol(format!("get_xpub result is not an xpub: {e}")))?;
    if xpub.network != NetworkKind::from(network) {
        return Err(JadeError::NetworkMismatch(format!(
            "asked for a {network} xpub, got a {:?} one",
            xpub.network
        )));
    }
    let last = path.into_iter().last().copied();
    if usize::from(xpub.depth) != path.as_ref().len()
        || xpub.child_number != last.unwrap_or(ChildNumber::Normal { index: 0 })
    {
        return Err(protocol(format!(
            "xpub is not at the requested path {path}"
        )));
    }
    Ok(xpub)
}

/// Accepts the device's PSBT only if it is `unsigned` plus valid signatures
/// from `signer`'s keys.
///
/// The Jade signs every input whose `bip32_derivation` names its master
/// fingerprint (`sign_psbt()` in `main/process/sign_psbt.c`) and sends back
/// the whole PSBT re-serialised, so this checks four things:
///
/// 1. Nothing but `partial_sigs` changed. Otherwise a faulty device could hand
///    the finaliser a witness script or derivation we never built.
/// 2. Every new signature is for one of `signer`'s keys, and verifies against
///    the sighash computed here, from our own `unsigned` copy.
/// 3. At least one signature was added. The device returns the PSBT unchanged,
///    with no error, when it found nothing of its own to sign.
/// 4. Every one of `signer`'s keys got signed.
fn verify_signed_psbt(
    unsigned: &Psbt,
    signed_bytes: &[u8],
    signer: Fingerprint,
) -> Result<Psbt, JadeError> {
    let signed = Psbt::deserialize(signed_bytes)
        .map_err(|e| protocol(format!("the device returned an unparseable PSBT: {e}")))?;
    if signed.inputs.len() != unsigned.inputs.len() {
        return Err(JadeError::PsbtModified);
    }

    let secp = Secp256k1::verification_only();
    let mut sighashes = SighashCache::new(&unsigned.unsigned_tx);
    let mut stripped = signed.clone();
    let mut added = 0;
    let mut first_unsigned_input = None;
    for (index, (before, after)) in unsigned.inputs.iter().zip(&signed.inputs).enumerate() {
        let ours: Vec<_> = before
            .bip32_derivation
            .iter()
            .filter(|(_, (fingerprint, _))| *fingerprint == signer)
            .map(|(key, _)| *key)
            .collect();

        let kept_earlier_signatures = before
            .partial_sigs
            .iter()
            .all(|(key, signature)| after.partial_sigs.get(key) == Some(signature));
        if !kept_earlier_signatures {
            return Err(JadeError::PsbtModified);
        }

        for (key, signature) in &after.partial_sigs {
            if before.partial_sigs.contains_key(key) {
                continue;
            }
            if !key.compressed || !ours.contains(&key.inner) {
                return Err(JadeError::PsbtModified);
            }
            let (message, sighash_type) = unsigned
                .sighash_ecdsa(index, &mut sighashes)
                .map_err(|_| JadeError::InvalidSignature { input: index })?;
            if signature.sighash_type != sighash_type
                || secp
                    .verify_ecdsa(&message, &signature.signature, &key.inner)
                    .is_err()
            {
                return Err(JadeError::InvalidSignature { input: index });
            }
            added += 1;
        }

        let all_ours_signed = ours
            .iter()
            .all(|ours| after.partial_sigs.keys().any(|key| key.inner == *ours));
        if !all_ours_signed && first_unsigned_input.is_none() {
            first_unsigned_input = Some(index);
        }
        stripped.inputs[index].partial_sigs = before.partial_sigs.clone();
    }

    if stripped != *unsigned {
        return Err(JadeError::PsbtModified);
    }
    if added == 0 {
        return Err(JadeError::NoSignatures);
    }
    if let Some(input) = first_unsigned_input {
        return Err(JadeError::MissingSignature { input });
    }
    Ok(signed)
}

fn encode_request(id: &str, method: &str, params: Option<Value>) -> Result<Vec<u8>, JadeError> {
    let mut fields = vec![(text("id"), text(id)), (text("method"), text(method))];
    if let Some(params) = params {
        fields.push((text("params"), params));
    }
    let mut bytes = Vec::new();
    ciborium::into_writer(&Value::Map(fields), &mut bytes)
        .expect("writing a CBOR value to a Vec cannot fail");
    if bytes.len() > MAX_REQUEST_LEN {
        return Err(JadeError::RequestTooLarge(bytes.len()));
    }
    Ok(bytes)
}

fn protocol(message: impl Into<String>) -> JadeError {
    JadeError::Protocol(message.into())
}

fn broken() -> JadeError {
    protocol("out of step with the device — reconnect")
}

fn text(text: &str) -> Value {
    Value::Text(text.to_owned())
}

fn uint(value: u64) -> Value {
    Value::Integer(value.into())
}

fn map<const N: usize>(fields: [(&str, Value); N]) -> Value {
    Value::Map(
        fields
            .into_iter()
            .map(|(key, value)| (text(key), value))
            .collect(),
    )
}

fn field<'a>(fields: &'a [(Value, Value)], key: &str) -> Option<&'a Value> {
    fields
        .iter()
        .find(|(k, _)| k.as_text() == Some(key))
        .map(|(_, value)| value)
}

fn unsigned(value: &Value, name: &str) -> Result<u64, JadeError> {
    value
        .as_integer()
        .and_then(|value| u64::try_from(value).ok())
        .ok_or_else(|| protocol(format!("{name} is not an unsigned integer")))
}

#[cfg(test)]
mod tests {
    use std::str::FromStr;

    use bitcoin::bip32::Xpriv;
    use bitcoin::hashes::Hash;
    use bitcoin::psbt::raw;
    use bitcoin::secp256k1::{Message, SecretKey};
    use bitcoin::sighash::EcdsaSighashType;
    use bitcoin::{
        Amount, OutPoint, ScriptBuf, Sequence, Transaction, TxIn, TxOut, Txid, Witness, absolute,
        ecdsa, transaction,
    };
    use miniscript::psbt::PsbtExt;
    use miniscript::{Descriptor, DescriptorPublicKey};

    use super::*;
    use crate::keys::{generate_master_xpriv, generate_mnemonic, generate_seed};

    const NETWORK: Network = Network::Testnet;

    /// How a test table says which error it expects.
    type Expect = fn(&JadeError) -> bool;

    /// A session whose request ids start at "1", for replies written by hand.
    fn session_from_1() -> JadeSession {
        JadeSession::with_ids_after(NETWORK, 0)
    }

    /// A key holder at `m/48'/1'/0'/2'`, from fixed entropy so every run sees
    /// the same keys. `Signer::new(0)` plays the Jade.
    struct Signer {
        master: Xpriv,
        fingerprint: Fingerprint,
        account: Xpub,
    }

    impl Signer {
        fn new(entropy: u8) -> Self {
            let secp = Secp256k1::new();
            let mnemonic = generate_mnemonic(&[entropy; 32]).unwrap();
            let master = generate_master_xpriv(NETWORK, &generate_seed(&mnemonic, "")).unwrap();
            let account =
                Xpub::from_priv(&secp, &master.derive_priv(&secp, &account_path()).unwrap());
            Self {
                fingerprint: master.fingerprint(&secp),
                master,
                account,
            }
        }

        fn descriptor_key(&self) -> String {
            format!("[{}/48'/1'/0'/2']{}/0/*", self.fingerprint, self.account)
        }

        /// This signer's private key for `input`, found through its derivation entry.
        fn key_for(&self, psbt: &Psbt, input: usize) -> (SecretKey, bitcoin::PublicKey) {
            let secp = Secp256k1::new();
            let (key, (_, path)) = psbt.inputs[input]
                .bip32_derivation
                .iter()
                .find(|(_, (fingerprint, _))| *fingerprint == self.fingerprint)
                .unwrap();
            let secret = self.master.derive_priv(&secp, path).unwrap().private_key;
            (secret, bitcoin::PublicKey::new(*key))
        }
    }

    fn account_path() -> DerivationPath {
        DerivationPath::from_str("m/48'/1'/0'/2'").unwrap()
    }

    /// A 2-of-3 `wsh(sortedmulti)` vault spend with `inputs` inputs: the Jade
    /// plus two software cosigners, with key origins as a wallet would write them.
    fn vault_psbt(jade: &Signer, inputs: u32) -> Psbt {
        let cosigners = [Signer::new(1), Signer::new(2)];
        let descriptor = Descriptor::<DescriptorPublicKey>::from_str(&format!(
            "wsh(sortedmulti(2,{},{},{}))",
            jade.descriptor_key(),
            cosigners[0].descriptor_key(),
            cosigners[1].descriptor_key(),
        ))
        .unwrap();
        let at = |index| descriptor.at_derivation_index(index).unwrap();
        let unsigned_tx = Transaction {
            version: transaction::Version::TWO,
            lock_time: absolute::LockTime::ZERO,
            input: (0..inputs)
                .map(|vout| TxIn {
                    previous_output: OutPoint {
                        txid: Txid::from_byte_array([7; 32]),
                        vout,
                    },
                    script_sig: ScriptBuf::new(),
                    sequence: Sequence::ENABLE_RBF_NO_LOCKTIME,
                    witness: Witness::new(),
                })
                .collect(),
            output: vec![TxOut {
                value: Amount::from_sat(10_000),
                script_pubkey: at(1000).script_pubkey(),
            }],
        };
        let mut psbt = Psbt::from_unsigned_tx(unsigned_tx).unwrap();
        for index in 0..inputs {
            let input = index as usize;
            psbt.inputs[input].witness_utxo = Some(TxOut {
                value: Amount::from_sat(20_000),
                script_pubkey: at(index).script_pubkey(),
            });
            psbt.update_input_with_descriptor(input, &at(index))
                .unwrap();
        }
        psbt
    }

    fn signed_by(signer: &Signer, psbt: &Psbt) -> Psbt {
        let mut signed = psbt.clone();
        signed.sign(&signer.master, &Secp256k1::new()).unwrap();
        signed
    }

    /// Stands in for the device, holding to the rules of firmware 1.0.41 that
    /// the host side depends on: request validity (`rpc_request_valid()`), each
    /// handler's parameters and their CBOR types, `sign_psbt` answered in
    /// pieces, and `check_extended_data_fields()`.
    struct FakeJade {
        signer: Signer,
        /// `auth_user` first asks for a pinserver round trip, as a PIN wallet does.
        pin_wallet: bool,
        piece_len: usize,
        /// (origid, signed PSBT, seqlen, pieces sent) while a signing is fetched.
        signing: Option<(String, Vec<u8>, u64, u64)>,
    }

    impl FakeJade {
        fn new() -> Self {
            Self {
                signer: Signer::new(0),
                pin_wallet: false,
                piece_len: 3008,
                signing: None,
            }
        }

        fn answer(&mut self, request: &[u8]) -> Vec<u8> {
            let mut rest = request;
            let Value::Map(fields) = ciborium::from_reader(&mut rest).unwrap() else {
                panic!("a request is one CBOR map");
            };
            assert!(rest.is_empty(), "exactly one message per request");
            let id = field(&fields, "id")
                .and_then(Value::as_text)
                .unwrap()
                .to_owned();
            assert!((1..=16).contains(&id.len()), "MAXLEN_ID is 16");
            let method = field(&fields, "method")
                .and_then(Value::as_text)
                .unwrap()
                .to_owned();
            let params = field(&fields, "params")
                .and_then(Value::as_map)
                .cloned()
                .unwrap_or_default();
            let param = |key: &str| field(&params, key).cloned();
            if ["auth_user", "get_xpub", "sign_psbt"].contains(&method.as_str()) {
                assert_eq!(param("network"), Some(text("testnet")));
            }
            match method.as_str() {
                "get_version_info" => reply(&id, version_info("LOCKED", "TEST")),
                "auth_user" => {
                    assert!(
                        param("epoch")
                            .and_then(|epoch| epoch.as_integer())
                            .is_some()
                    );
                    let result = if self.pin_wallet {
                        pinserver_request()
                    } else {
                        Value::Bool(true)
                    };
                    reply(&id, result)
                }
                "pin" => {
                    // The pinserver's JSON must reach the device unchanged.
                    let got = serde_json::to_value(Value::Map(params.clone())).unwrap();
                    assert_eq!(got, pinserver_reply());
                    reply(&id, Value::Bool(true))
                }
                "get_xpub" => {
                    let path: Vec<ChildNumber> = param("path")
                        .unwrap()
                        .into_array()
                        .unwrap()
                        .iter()
                        .map(|c| ChildNumber::from(u32::try_from(c.as_integer().unwrap()).unwrap()))
                        .collect();
                    let secp = Secp256k1::new();
                    let xpub = self.signer.master.derive_priv(&secp, &path).unwrap();
                    reply(&id, text(&Xpub::from_priv(&secp, &xpub).to_string()))
                }
                "sign_psbt" => {
                    let bytes = param("psbt")
                        .unwrap()
                        .into_bytes()
                        .expect("the psbt travels as CBOR bytes, not an array");
                    let signed =
                        signed_by(&self.signer, &Psbt::deserialize(&bytes).unwrap()).serialize();
                    // Deliberately without the firmware's extra, crashing piece
                    // for exact multiples of the piece length.
                    let seqlen = signed.len().div_ceil(self.piece_len) as u64;
                    self.signing = Some((id.clone(), signed, seqlen, 0));
                    self.next_piece(&id)
                }
                "get_extended_data" => {
                    let (origid, _, seqlen, sent) = self.signing.as_ref().expect("a signing");
                    assert_eq!(param("origid"), Some(text(origid)));
                    assert_eq!(param("orig"), Some(text("sign_psbt")));
                    assert_eq!(param("seqnum"), Some(uint(sent + 1)));
                    assert_eq!(param("seqlen"), Some(uint(*seqlen)));
                    self.next_piece(&id)
                }
                _ => error_reply(&id, -32601, "Unknown method"),
            }
        }

        fn next_piece(&mut self, id: &str) -> Vec<u8> {
            let piece_len = self.piece_len;
            let (_, signed, seqlen, sent) = self.signing.as_mut().unwrap();
            let start = *sent as usize * piece_len;
            let bytes = signed[start..(start + piece_len).min(signed.len())].to_vec();
            *sent += 1;
            piece(id, *sent, *seqlen, bytes)
        }
    }

    fn encode(value: &Value) -> Vec<u8> {
        let mut bytes = Vec::new();
        ciborium::into_writer(value, &mut bytes).unwrap();
        bytes
    }

    fn reply(id: &str, result: Value) -> Vec<u8> {
        encode(&Value::Map(vec![
            (text("id"), text(id)),
            (text("result"), result),
        ]))
    }

    fn error_reply(id: &str, code: i64, message: &str) -> Vec<u8> {
        let error = Value::Map(vec![
            (text("code"), Value::Integer(code.into())),
            (text("message"), text(message)),
        ]);
        encode(&Value::Map(vec![
            (text("id"), text(id)),
            (text("error"), error),
        ]))
    }

    fn piece(id: &str, seqnum: u64, seqlen: u64, bytes: Vec<u8>) -> Vec<u8> {
        encode(&Value::Map(vec![
            (text("id"), text(id)),
            (text("seqnum"), uint(seqnum)),
            (text("seqlen"), uint(seqlen)),
            (text("result"), Value::Bytes(bytes)),
        ]))
    }

    fn version_info(state: &str, networks: &str) -> Value {
        map([
            ("JADE_VERSION", text("1.0.41")),
            ("JADE_OTA_MAX_CHUNK", uint(4096)),
            ("JADE_CONFIG", text("BLE")),
            ("BOARD_TYPE", text("JADE_V2C")),
            ("JADE_STATE", text(state)),
            ("JADE_NETWORKS", text(networks)),
            ("JADE_HAS_PIN", Value::Bool(true)),
        ])
    }

    fn pinserver_request() -> Value {
        let urls = Value::Array(vec![
            text("https://abcdefghijklmnop.onion/get_pin"),
            text("http://pin.example/get_pin"),
            text("https://pin.example/get_pin"),
        ]);
        // One opaque string, wrapped as `client_data_request_reply()` in
        // `main/process/process_utils.c` does for `accept: json`.
        let data = map([("data", text("opaque request"))]);
        let params = map([
            ("urls", urls),
            ("method", text("POST")),
            ("accept", text("json")),
            ("data", data),
        ]);
        map([(
            "http_request",
            map([("params", params), ("on-reply", text("pin"))]),
        )])
    }

    /// `handle_pin()` in `main/process/pinclient.c` reads `data` as a string.
    fn pinserver_reply() -> serde_json::Value {
        serde_json::json!({"data": "opaque reply"})
    }

    /// The pinserver's side: it must receive the device's data unchanged.
    fn fake_pinserver(url: &str, body: &str) -> String {
        assert_eq!(url, "https://pin.example/get_pin");
        let sent: serde_json::Value = serde_json::from_str(body).unwrap();
        assert_eq!(sent, serde_json::json!({"data": "opaque request"}));
        pinserver_reply().to_string()
    }

    /// Runs one exchange to its end the way a host does, cutting every reply
    /// into fragments of `cut(n)` bytes.
    fn run(
        session: &mut JadeSession,
        jade: &mut FakeJade,
        request: Vec<u8>,
        cut: impl Fn(usize) -> usize,
    ) -> Result<JadeEvent, VaultCoreError> {
        let mut outgoing = request;
        loop {
            let incoming = jade.answer(&outgoing);
            let (mut at, mut fragment, mut event) = (0, 0, JadeEvent::NeedMore);
            while at < incoming.len() {
                let len = cut(fragment).clamp(1, incoming.len() - at);
                event = session.receive(&incoming[at..at + len])?;
                at += len;
                fragment += 1;
                if at < incoming.len() {
                    assert!(
                        matches!(event, JadeEvent::NeedMore),
                        "event before the reply ended"
                    );
                }
            }
            outgoing = match event {
                JadeEvent::Send(bytes) => bytes,
                JadeEvent::Pinserver { url, body } => {
                    session.pinserver_response(&fake_pinserver(&url, &body))?
                }
                JadeEvent::NeedMore => panic!("a complete reply left the session wanting more"),
                done => return Ok(done),
            };
        }
    }

    fn whole(_: usize) -> usize {
        usize::MAX
    }

    /// What jade-ble writes per chunk; any other split must work just as well.
    const BLE_WRITE_LEN: usize = 509;

    /// Repeatable, arbitrary fragment sizes of 1..=509 bytes.
    fn scattered(fragment: usize) -> usize {
        let x = (fragment as u64)
            .wrapping_mul(6_364_136_223_846_793_005)
            .wrapping_add(1_442_695_040_888_963_407);
        1 + (x >> 33) as usize % BLE_WRITE_LEN
    }

    fn jade_error(result: Result<impl std::fmt::Debug, VaultCoreError>) -> JadeError {
        match result {
            Err(VaultCoreError::Jade(error)) => error,
            other => panic!("expected a Jade error, got {other:?}"),
        }
    }

    fn xpub_of(event: JadeEvent) -> Xpub {
        match event {
            JadeEvent::Xpub(xpub) => xpub,
            other => panic!("expected an xpub, got {other:?}"),
        }
    }

    fn params_of(request: &[u8]) -> Vec<(Value, Value)> {
        let fields = ciborium::from_reader::<Value, _>(request)
            .unwrap()
            .into_map()
            .unwrap();
        field(&fields, "params")
            .and_then(Value::as_map)
            .unwrap()
            .clone()
    }

    #[test]
    fn requests_use_the_cbor_types_the_firmware_reads() {
        let xpub_request = JadeSession::new(NETWORK)
            .request_xpub(&account_path())
            .unwrap();
        let params = params_of(&xpub_request);
        assert_eq!(field(&params, "network"), Some(&text("testnet")));
        // Hardened elements travel as plain uints with the top bit set.
        let path = [0x8000_0030, 0x8000_0001, 0x8000_0000, 0x8000_0002].map(uint);
        assert_eq!(field(&params, "path"), Some(&Value::Array(path.to_vec())));

        let psbt = vault_psbt(&Signer::new(0), 1);
        let sign_request = JadeSession::new(NETWORK)
            .request_sign_psbt(&psbt, Fingerprint::default())
            .unwrap();
        let psbt_param = field(&params_of(&sign_request), "psbt").cloned();
        assert_eq!(psbt_param, Some(Value::Bytes(psbt.serialize())));

        let unlock_request = JadeSession::new(NETWORK)
            .request_unlock(1_700_000_000)
            .unwrap();
        assert_eq!(
            field(&params_of(&unlock_request), "epoch"),
            Some(&uint(1_700_000_000))
        );

        let version_request = JadeSession::new(NETWORK).request_version_info().unwrap();
        let nonblocking = field(&params_of(&version_request), "nonblocking").cloned();
        assert_eq!(nonblocking, Some(Value::Bool(true)));

        for (network, name) in [
            (Network::Bitcoin, "mainnet"),
            (Network::Signet, "testnet"),
            (Network::Regtest, "localtest"),
        ] {
            let request = JadeSession::new(network).request_unlock(0).unwrap();
            assert_eq!(field(&params_of(&request), "network"), Some(&text(name)));
        }
    }

    #[test]
    fn one_request_at_a_time() {
        let mut session = JadeSession::new(NETWORK);
        session.request_version_info().unwrap();
        assert!(matches!(
            jade_error(session.request_version_info()),
            JadeError::OutOfOrder
        ));
        let stray_pinserver = session.pinserver_response("{}");
        assert!(matches!(jade_error(stray_pinserver), JadeError::OutOfOrder));
        let stray_cancel = session.pinserver_failed();
        assert!(matches!(jade_error(stray_cancel), JadeError::OutOfOrder));
    }

    /// A reply the Jade finished after the last link dropped arrives on the
    /// next one (seen on a Jade Core). It must not pass for a new answer.
    #[test]
    fn a_reply_left_over_from_the_last_connection_is_refused() {
        let mut last = JadeSession::new(NETWORK);
        let stale = FakeJade::new().answer(&last.request_version_info().unwrap());
        let mut next = JadeSession::new(NETWORK);
        next.request_version_info().unwrap();
        assert!(matches!(
            jade_error(next.receive(&stale)),
            JadeError::Protocol(_)
        ));
    }

    #[test]
    fn abandoning_an_exchange_breaks_the_session() {
        let mut session = session_from_1();
        session.abandon(); // nothing in flight: still usable
        session.request_version_info().unwrap();
        session.abandon(); // the reply may still come, so nothing after this is trusted
        let late = session.receive(&reply("1", version_info("READY", "TEST")));
        assert!(matches!(jade_error(late), JadeError::Protocol(_)));
        assert!(matches!(
            jade_error(session.request_version_info()),
            JadeError::Protocol(_)
        ));
    }

    #[test]
    fn oversized_request_is_refused_and_leaves_the_session_usable() {
        let mut psbt = vault_psbt(&Signer::new(0), 1);
        let key = raw::Key {
            type_value: 0xef,
            key: vec![],
        };
        psbt.unknown.insert(key, vec![0; MAX_REQUEST_LEN]);
        let mut session = JadeSession::new(NETWORK);
        let refused = session.request_sign_psbt(&psbt, Fingerprint::default());
        assert!(matches!(jade_error(refused), JadeError::RequestTooLarge(_)));
        assert!(session.request_version_info().is_ok());
    }

    #[test]
    fn a_reply_split_anywhere_parses_the_same() {
        let mut jade = FakeJade::new();
        let reply = jade.answer(&session_from_1().request_xpub(&account_path()).unwrap());
        for split in 0..=reply.len() {
            let mut session = session_from_1();
            session.request_xpub(&account_path()).unwrap();
            let mut event = session.receive(&reply[..split]).unwrap();
            if split < reply.len() {
                assert!(matches!(event, JadeEvent::NeedMore), "split at {split}");
                event = session.receive(&reply[split..]).unwrap();
            }
            assert_eq!(xpub_of(event), jade.signer.account, "split at {split}");
        }
    }

    #[test]
    fn device_log_lines_are_skipped() {
        let mut session = JadeSession::new(NETWORK);
        let request = session.request_version_info().unwrap();
        let mut bytes = encode(&map([("log", Value::Bytes(b"I jade: hi".to_vec()))]));
        bytes.extend(FakeJade::new().answer(&request));
        assert!(matches!(
            session.receive(&bytes).unwrap(),
            JadeEvent::VersionInfo(_)
        ));
    }

    #[test]
    fn protocol_violations_break_the_session() {
        let version = || version_info("READY", "TEST");
        let mut trailing = reply("1", version());
        trailing.push(0xa0);
        let mut oversized = vec![0x5a, 0x00, 0x01, 0x00, 0x00]; // bytes(65536) header
        oversized.resize(MAX_REPLY_LEN + 1, 0);
        let cases = [
            ("not CBOR", vec![0xff]),
            ("not a map", encode(&text("hello"))),
            ("no id", encode(&map([("result", version())]))),
            ("someone else's id", reply("2", version())),
            (
                "an unreadable-request id with a result",
                reply("00", version()),
            ),
            (
                "neither result nor error",
                encode(&map([("id", text("1"))])),
            ),
            (
                "both result and error",
                encode(&map([
                    ("id", text("1")),
                    ("result", version()),
                    ("error", map([("code", Value::Integer((-32603).into()))])),
                ])),
            ),
            ("bytes after the reply", trailing),
            ("a reply past the size cap", oversized),
        ];
        for (case, bytes) in cases {
            let mut session = session_from_1();
            session.request_version_info().unwrap();
            let error = jade_error(session.receive(&bytes));
            assert!(matches!(error, JadeError::Protocol(_)), "{case}: {error:?}");
            let after = session.request_version_info();
            assert!(
                matches!(jade_error(after), JadeError::Protocol(_)),
                "{case}: not broken"
            );
        }
    }

    #[test]
    fn unsolicited_bytes_are_a_protocol_error() {
        let mut session = JadeSession::new(NETWORK);
        assert!(matches!(
            jade_error(session.receive(&[0xa0])),
            JadeError::Protocol(_)
        ));
        assert!(session.request_version_info().is_err());
    }

    #[test]
    fn device_errors_map_and_leave_the_session_usable() {
        let cases: [(&str, i64, Expect); 5] = [
            ("1", -32000, |e| matches!(e, JadeError::UserCancelled)),
            ("1", -32002, |e| matches!(e, JadeError::Locked)),
            (
                "1",
                -32003,
                |e| matches!(e, JadeError::NetworkMismatch(m) if m.as_str() == "the message"),
            ),
            ("1", -32601, |e| {
                matches!(e, JadeError::Rpc { code: -32601, .. })
            }),
            // The device could not read our id at all.
            ("00", -32600, |e| {
                matches!(e, JadeError::Rpc { code: -32600, .. })
            }),
        ];
        for (id, code, expected) in cases {
            let mut session = session_from_1();
            session.request_version_info().unwrap();
            let error = jade_error(session.receive(&error_reply(id, code, "the message")));
            assert!(expected(&error), "{code}: {error:?}");
            assert!(
                session.request_version_info().is_ok(),
                "{code}: session still usable"
            );
        }
    }

    #[test]
    fn xpubs_match_the_device_keys() {
        let mut jade = FakeJade::new();
        let mut session = JadeSession::new(NETWORK);
        let request = session.request_xpub(&DerivationPath::master()).unwrap();
        let root = xpub_of(run(&mut session, &mut jade, request, whole).unwrap());
        assert_eq!(root.fingerprint(), jade.signer.fingerprint);

        let request = session.request_xpub(&account_path()).unwrap();
        let account = xpub_of(run(&mut session, &mut jade, request, whole).unwrap());
        assert_eq!(account, jade.signer.account);
    }

    #[test]
    fn xpub_answers_are_checked_against_the_request() {
        let jade = Signer::new(0);
        let mut mainnet = jade.account;
        mainnet.network = NetworkKind::Main;
        let root = Xpub::from_priv(&Secp256k1::new(), &jade.master);
        let secp = Secp256k1::new();
        let m_2h = DerivationPath::from_str("m/2'").unwrap();
        let shallow = Xpub::from_priv(&secp, &jade.master.derive_priv(&secp, &m_2h).unwrap());
        let p2sh_p2wsh = DerivationPath::from_str("m/48'/1'/0'/1'").unwrap();
        let cases = [
            (
                account_path(),
                text(&mainnet.to_string()),
                "mainnet xpub for testnet",
            ),
            (
                account_path(),
                text(&root.to_string()),
                "wrong depth and child",
            ),
            (
                account_path(),
                text(&shallow.to_string()),
                "right child at the wrong depth",
            ),
            (
                p2sh_p2wsh,
                text(&jade.account.to_string()),
                "wrong child at the right depth",
            ),
            (account_path(), Value::Bool(true), "not text"),
            (account_path(), text("xpub-ish"), "not an xpub"),
        ];
        for (path, answer, case) in cases {
            let mut session = session_from_1();
            session.request_xpub(&path).unwrap();
            let error = jade_error(session.receive(&reply("1", answer)));
            let expected = if case.starts_with("mainnet") {
                matches!(error, JadeError::NetworkMismatch(_))
            } else {
                matches!(error, JadeError::Protocol(_))
            };
            assert!(expected, "{case}: {error:?}");
        }
    }

    #[test]
    fn unlock_with_and_without_the_pinserver() {
        for pin_wallet in [false, true] {
            let mut jade = FakeJade::new();
            jade.pin_wallet = pin_wallet;
            let mut session = JadeSession::new(NETWORK);
            let request = session.request_unlock(1_700_000_000).unwrap();
            let event = run(&mut session, &mut jade, request, scattered).unwrap();
            assert!(
                matches!(event, JadeEvent::Unlocked),
                "pin wallet: {pin_wallet}"
            );
        }
    }

    #[test]
    fn unlock_failures() {
        let mut session = session_from_1();
        session.request_unlock(0).unwrap();
        let wrong_pin = session.receive(&reply("1", Value::Bool(false)));
        assert!(matches!(jade_error(wrong_pin), JadeError::UnlockFailed));

        // Plain http and onion only: nothing we will relay to.
        let mut request = pinserver_request();
        let Value::Map(fields) = &mut request else {
            unreachable!()
        };
        let urls = [
            text("http://pin.example/get_pin"),
            text("https://x.onion/get_pin"),
        ];
        fields[0].1 = map([
            (
                "params",
                map([("urls", Value::Array(urls.to_vec())), ("data", map([]))]),
            ),
            ("on-reply", text("pin")),
        ]);
        let mut session = session_from_1();
        session.request_unlock(0).unwrap();
        let no_https = session.receive(&reply("1", request));
        assert!(matches!(jade_error(no_https), JadeError::Protocol(_)));

        let mut session = session_from_1();
        session.request_unlock(0).unwrap();
        let event = session.receive(&reply("1", pinserver_request())).unwrap();
        assert!(matches!(event, JadeEvent::Pinserver { .. }));
        let not_json = session.pinserver_response("<html>502</html>");
        assert!(matches!(jade_error(not_json), JadeError::Protocol(_)));
        assert!(
            session.request_version_info().is_err(),
            "the device is still waiting: broken"
        );
    }

    #[test]
    fn an_unreachable_pinserver_cancels_the_unlock() {
        let mut session = session_from_1();
        session.request_unlock(0).unwrap();
        let event = session.receive(&reply("1", pinserver_request())).unwrap();
        assert!(matches!(event, JadeEvent::Pinserver { .. }));

        // `cancel` in place of `pin`, which the device answers with nothing at all.
        let cancel = session.pinserver_failed().unwrap();
        let fields = ciborium::from_reader::<Value, _>(cancel.as_slice())
            .unwrap()
            .into_map()
            .unwrap();
        assert_eq!(field(&fields, "id"), Some(&text("2")));
        assert_eq!(field(&fields, "method"), Some(&text("cancel")));
        assert_eq!(field(&fields, "params"), None);

        // So the session is idle at once, and the unlock can be retried.
        assert!(session.request_unlock(0).is_ok());
    }

    #[test]
    fn clearnet_https_only() {
        assert!(is_clearnet_https("https://pin.example/get_pin"));
        assert!(is_clearnet_https("https://pin.example:8443/get_pin"));
        assert!(!is_clearnet_https("http://pin.example/get_pin"));
        assert!(!is_clearnet_https("https://abc.onion/get_pin"));
        assert!(!is_clearnet_https("https:///get_pin"));
    }

    #[test]
    fn version_info_gates_the_unlock() {
        let info = |state, networks| VersionInfo::parse(version_info(state, networks)).unwrap();
        assert_eq!(info("LOCKED", "TEST").board, "JADE_V2C");
        assert_eq!(info("LOCKED", "TEST").state, JadeState::Locked);

        let usable = |state, networks, network| info(state, networks).check_usable(network);
        assert!(usable("LOCKED", "TEST", Network::Testnet).is_ok());
        assert!(usable("TEMP", "TEST", Network::Signet).is_ok());
        assert!(usable("READY", "MAIN", Network::Bitcoin).is_ok());
        // A freshly restored wallet gets its PIN, and its network pin, on this unlock.
        assert!(usable("UNSAVED", "ALL", Network::Testnet).is_ok());
        let uninit = usable("UNINIT", "ALL", Network::Testnet);
        assert!(matches!(jade_error(uninit), JadeError::NotInitialised));
        let main_wallet = usable("LOCKED", "MAIN", Network::Testnet);
        assert!(matches!(
            jade_error(main_wallet),
            JadeError::NetworkMismatch(_)
        ));
        let test_wallet = usable("LOCKED", "TEST", Network::Bitcoin);
        assert!(matches!(
            jade_error(test_wallet),
            JadeError::NetworkMismatch(_)
        ));

        assert!(VersionInfo::parse(version_info("ASLEEP", "ALL")).is_err());

        let mut session = JadeSession::new(NETWORK);
        let request = session.request_version_info().unwrap();
        let event = run(&mut session, &mut FakeJade::new(), request, scattered).unwrap();
        assert!(matches!(event, JadeEvent::VersionInfo(info) if info.firmware == "1.0.41"));
    }

    #[test]
    fn signs_in_one_piece_or_many() {
        for piece_len in [3008, 64] {
            let mut jade = FakeJade::new();
            jade.piece_len = piece_len;
            let psbt = vault_psbt(&jade.signer, 2);
            let mut session = JadeSession::new(NETWORK);
            let request = session
                .request_sign_psbt(&psbt, jade.signer.fingerprint)
                .unwrap();
            let event = run(&mut session, &mut jade, request, scattered).unwrap();
            let JadeEvent::SignedPsbt(signed) = event else {
                panic!("expected a signed PSBT, got {event:?}");
            };
            assert_eq!(
                signed,
                signed_by(&jade.signer, &psbt),
                "piece length {piece_len}"
            );
            let pieces = jade.signing.unwrap().3;
            assert_eq!(pieces > 1, piece_len == 64, "piece length {piece_len}");
        }
    }

    #[test]
    fn bad_piece_sequencing_is_a_protocol_error() {
        let signed = signed_by(&Signer::new(0), &vault_psbt(&Signer::new(0), 2)).serialize();
        let third = signed.len() / 3;
        let first = || piece("1", 1, 3, signed[..third].to_vec());
        let cases = [
            ("wrong id", vec![first(), piece("9", 2, 3, vec![])]),
            (
                // Not the last piece, or the gap would only surface later as an unparseable PSBT.
                "skipped piece",
                vec![
                    piece("1", 1, 4, signed[..third].to_vec()),
                    piece("2", 3, 4, vec![]),
                ],
            ),
            ("seqlen changed", vec![first(), piece("2", 2, 4, vec![])]),
            ("zero seqlen", vec![piece("1", 1, 0, vec![])]),
            ("absurd seqlen", vec![piece("1", 1, 257, vec![])]),
            ("not bytes", vec![reply("1", text("cHNidP8="))]),
        ];
        for (case, replies) in cases {
            let jade = Signer::new(0);
            let mut session = session_from_1();
            session
                .request_sign_psbt(&vault_psbt(&jade, 2), jade.fingerprint)
                .unwrap();
            let mut outcome = Ok(JadeEvent::NeedMore);
            for bytes in replies {
                outcome = session.receive(&bytes);
            }
            assert!(
                matches!(jade_error(outcome), JadeError::Protocol(_)),
                "{case}"
            );
        }

        // A device error between pieces is just that.
        let jade = Signer::new(0);
        let mut session = session_from_1();
        session
            .request_sign_psbt(&vault_psbt(&jade, 2), jade.fingerprint)
            .unwrap();
        assert!(matches!(session.receive(&first()), Ok(JadeEvent::Send(_))));
        let cancelled = session.receive(&error_reply("2", -32000, "User declined"));
        assert!(matches!(jade_error(cancelled), JadeError::UserCancelled));
    }

    #[test]
    fn verify_accepts_exactly_the_devices_signatures() {
        let jade = Signer::new(0);
        let psbt = vault_psbt(&jade, 2);
        let signed = signed_by(&jade, &psbt);
        let verified = verify_signed_psbt(&psbt, &signed.serialize(), jade.fingerprint).unwrap();
        assert_eq!(verified, signed);

        // A cosigner went first; its signature has to survive untouched.
        let cosigned = signed_by(&Signer::new(1), &psbt);
        let both = signed_by(&jade, &cosigned);
        assert!(verify_signed_psbt(&cosigned, &both.serialize(), jade.fingerprint).is_ok());
    }

    #[test]
    fn verify_rejects_everything_else() {
        let jade = Signer::new(0);
        let cosigner = Signer::new(1);
        let psbt = vault_psbt(&jade, 2);
        let signed = signed_by(&jade, &psbt);
        let secp = Secp256k1::new();

        let tampered = |change: &dyn Fn(&mut Psbt)| {
            let mut copy = signed.clone();
            change(&mut copy);
            copy
        };
        let resigned = |input: usize, signature: ecdsa::Signature| {
            let (_, key) = jade.key_for(&psbt, input);
            tampered(&|p: &mut Psbt| {
                p.inputs[input].partial_sigs.insert(key, signature);
            })
        };
        let (secret, key) = jade.key_for(&psbt, 0);
        let other_message = Message::from_digest([7; 32]);
        let wrong_message = ecdsa::Signature::sighash_all(secp.sign_ecdsa(&other_message, &secret));
        let mut sighash_none = signed.inputs[0].partial_sigs[&key];
        sighash_none.sighash_type = EcdsaSighashType::None;

        let cases: Vec<(&str, Psbt, Expect)> = vec![
            ("unchanged", psbt.clone(), |e| {
                matches!(e, JadeError::NoSignatures)
            }),
            ("someone else signed", signed_by(&cosigner, &psbt), |e| {
                matches!(e, JadeError::PsbtModified)
            }),
            (
                "output changed",
                tampered(&|p| p.unsigned_tx.output[0].value = Amount::from_sat(1)),
                |e| matches!(e, JadeError::PsbtModified),
            ),
            (
                "witness script changed",
                tampered(&|p| p.inputs[0].witness_script = Some(ScriptBuf::new())),
                |e| matches!(e, JadeError::PsbtModified),
            ),
            (
                "derivation dropped",
                tampered(&|p| p.inputs[1].bip32_derivation.clear()),
                |e| matches!(e, JadeError::PsbtModified),
            ),
            (
                "signature over the wrong message",
                resigned(0, wrong_message),
                |e| matches!(e, JadeError::InvalidSignature { input: 0 }),
            ),
            ("SIGHASH_NONE", resigned(0, sighash_none), |e| {
                matches!(e, JadeError::InvalidSignature { input: 0 })
            }),
            (
                "one input left unsigned",
                tampered(&|p| p.inputs[1].partial_sigs.clear()),
                |e| matches!(e, JadeError::MissingSignature { input: 1 }),
            ),
        ];
        for (case, returned, expected) in cases {
            let error =
                verify_signed_psbt(&psbt, &returned.serialize(), jade.fingerprint).expect_err(case);
            assert!(expected(&error), "{case}: {error:?}");
        }

        let garbage = verify_signed_psbt(&psbt, b"not a psbt", jade.fingerprint);
        assert!(matches!(garbage, Err(JadeError::Protocol(_))));

        // An earlier cosigner signature may be neither dropped nor replaced.
        let cosigned = signed_by(&cosigner, &psbt);
        let (cosigner_key, _) = cosigner.key_for(&psbt, 0);
        let mut both = signed_by(&jade, &cosigned);
        let dropped = {
            let mut copy = both.clone();
            let key = bitcoin::PublicKey::new(cosigner_key.public_key(&secp));
            copy.inputs[0].partial_sigs.remove(&key);
            copy
        };
        let error = verify_signed_psbt(&cosigned, &dropped.serialize(), jade.fingerprint);
        assert!(matches!(error, Err(JadeError::PsbtModified)));
        let key = bitcoin::PublicKey::new(cosigner_key.public_key(&secp));
        let forged = ecdsa::Signature::sighash_all(secp.sign_ecdsa(&other_message, &cosigner_key));
        both.inputs[0].partial_sigs.insert(key, forged);
        let error = verify_signed_psbt(&cosigned, &both.serialize(), jade.fingerprint);
        assert!(matches!(error, Err(JadeError::PsbtModified)));
    }

    /// The whole signing exchange for a realistically large vault spend: a
    /// request spread over dozens of BLE writes, a signed PSBT over several
    /// 3008-byte pieces, every reply cut into arbitrary indications.
    #[test]
    fn a_sixty_input_vault_psbt_survives_arbitrary_fragmentation() {
        let mut jade = FakeJade::new();
        let psbt = vault_psbt(&jade.signer, 60);
        let mut session = JadeSession::new(NETWORK);
        let request = session
            .request_sign_psbt(&psbt, jade.signer.fingerprint)
            .unwrap();
        assert!(
            request.len() > 20 * BLE_WRITE_LEN,
            "request is {} bytes",
            request.len()
        );

        let event = run(&mut session, &mut jade, request, scattered).unwrap();
        let JadeEvent::SignedPsbt(signed) = event else {
            panic!("expected a signed PSBT, got {event:?}");
        };
        assert_eq!(signed, signed_by(&jade.signer, &psbt));
        let pieces = jade.signing.unwrap().3;
        assert!(pieces >= 5, "only {pieces} pieces");
        assert!(
            session.request_version_info().is_ok(),
            "ready for the next request"
        );
    }
}

/// Evidence from the real device, kept as a regression test.
#[cfg(test)]
mod device_vectors {
    use std::str::FromStr;

    use bip39::Mnemonic;
    use bitcoin::Network;
    use bitcoin::bip32::Xpub;

    use crate::keys::{ScriptType, account_multisig_xpub_from_mnemonic};

    /// What a Jade Core (board JADE_V2C, firmware 1.0.41) returned over BLE on
    /// 2026-09-30 for `get_xpub m/48'/1'/0'/2'` on testnet, restored with the
    /// public test phrase below and no passphrase (fingerprint 73c5da0a).
    ///
    /// Provenance: produced by the device, not by this crate. That makes it
    /// an independent oracle for vault-core's BIP-48 derivation, which
    /// `keys::tests` can only lock against its own earlier output.
    #[test]
    fn jade_core_bip48_testnet_xpub() {
        let mnemonic = Mnemonic::from_str(
            "abandon abandon abandon abandon abandon abandon \
             abandon abandon abandon abandon abandon about",
        )
        .unwrap();
        let (_, xpub) = account_multisig_xpub_from_mnemonic(
            &mnemonic,
            "",
            Network::Testnet,
            0,
            ScriptType::P2wsh,
        )
        .unwrap();
        let from_the_device = Xpub::from_str(
            "tpubDFH9dgzveyD8zTbPUFuLrGmCydNvxehyNdUXKJAQN8x4aZ4j6UZqGfnqFrD4NqyaTVGKbvEW54tsvPTK2UoSbCC1PJY8iCNiwTL3RWZEheQ",
        )
        .unwrap();
        assert_eq!(xpub, from_the_device);
        assert_eq!(xpub.parent_fingerprint.to_string(), "bac14839");
    }
}
