//! Talks to a Trezor Safe 7 over Bluetooth LE, and to nothing else.
//!
//! This crate only moves bytes. Every protocol decision is made by
//! [`vault_core::hw::trezor::TrezorSession`]: the Trezor-Host Protocol,
//! pairing, both checks that the device is a Safe 7, what may be signed and
//! what is accepted back. What this crate adds is what needs a radio and a
//! clock: finding the Safe 7, the GATT plumbing, timeouts, retransmission
//! timing, noticing a dropped link, and asking the user for the pairing code.
//!
//! Mobile uses this crate through UniFFI (mobile-signer-ffi, Phase 1); for
//! now `examples/trezor-hw-test` drives it from a desktop.
//!
//! What callers see:
//!
//! - **Two pairings, the first time.** The OS pairs the Bluetooth link by
//!   numeric comparison (the same six digits on the Safe 7 and the host;
//!   confirm both). Then the Safe 7 pairs the app: the user approves it on
//!   the device and types the six-digit code it shows, through
//!   [`PairingCodeSource`]. Keep the returned [`PairingCredential`] (it is a
//!   secret) to skip the code next time. The Safe 7 still asks the user to
//!   confirm every connection.
//! - **Exactly a Safe 7.** Any other model is refused, before any pairing
//!   prompt when it says what it is at once (see `vault_core::hw::trezor::identity`).
//! - **Bluetooth only.** There is no USB, UDP or emulator path here.

use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use bitcoin::bip32::{DerivationPath, Fingerprint, Xpub};
use bitcoin::{Network, Psbt};
use btleplug::api::{
    Central, CentralEvent, CharPropFlags, Characteristic, Manager as _, Peripheral as _,
    PeripheralProperties, ScanFilter, ValueNotification, WriteType,
};
use btleplug::platform::{Adapter, Manager, Peripheral};
use futures_util::{Stream, StreamExt};
use tokio::sync::Notify;
use tokio::time::Instant;
use vault_core::error::VaultCoreError;
use vault_core::hw::trezor::{
    PACKET_LEN, PairingCredential, Safe7Identity, SessionConfig, SessionPassphrase, TrezorError,
    TrezorEvent, TrezorSession, XpubOptions,
};

// The Safe 7's GATT interface: `ble_internal.h` and `service.c` in
// trezor-firmware's `nordic/trezor/trezor-ble/src/ble/`; the same UUIDs in
// trezorlib's `transport/ble.py` and Trezor Suite's BLE transports.
// Both characteristics need an encrypted link (`BT_GATT_PERM_*_ENCRYPT`); what
// authenticates the device to us is THP, not the Bluetooth bond.
const SERVICE_UUID: &str = "8c000001-a59b-4d58-a9ad-073df69fa1b1";
/// Host → Safe 7: write, or write without response.
const WRITE_CHARACTERISTIC_UUID: &str = "8c000002-a59b-4d58-a9ad-073df69fa1b1";
/// Safe 7 → host: one THP packet per notification.
const NOTIFY_CHARACTERISTIC_UUID: &str = "8c000003-a59b-4d58-a9ad-073df69fa1b1";
/// Trezor's company id in its advertisement (`CONFIG_BT_COMPANY_ID`). The
/// data after it is `[flags, color, model code, …]` (`advertising.c`).
const TREZOR_COMPANY_ID: u16 = 0x0F29;
/// `MODEL_BLE_CODE` of the Safe 7 (`core/embed/models/T3W1/model_T3W1.h`).
const SAFE7_BLE_CODE: u8 = 6;
/// A 244-byte packet plus ATT's 3-byte header: the device notifies whole
/// packets, so a smaller MTU can't carry them.
const MIN_MTU: u16 = PACKET_LEN as u16 + 3;

/// Connecting, discovering services, disconnecting.
const LINK_TIMEOUT: Duration = Duration::from_secs(15);
/// One write, or the subscription. Generous because the first ones on a new
/// link wait for the user to confirm the OS pairing dialog.
const WRITE_TIMEOUT: Duration = Duration::from_secs(60);
/// Replies that don't wait for the user.
const REPLY_TIMEOUT: Duration = Duration::from_secs(15);
/// Anything the user does: PIN, approving, the code, confirming.
const USER_TIMEOUT: Duration = Duration::from_secs(300);
const CONNECT_ATTEMPTS: u32 = 3;

#[derive(Debug, thiserror::Error)]
pub enum TrezorBleError {
    #[error("no Bluetooth adapter on this host")]
    NoAdapter,

    #[error("this app may not use Bluetooth; allow it in the system settings")]
    PermissionDenied,

    /// `seen` counts the named devices the scan found. Zero usually means
    /// the app may not use Bluetooth: macOS hands it an empty scan, not an error.
    #[error("{}", not_found_message(*.seen))]
    NotFound { seen: usize },

    #[error("several Trezors in range ({}); say which one", .0.join(", "))]
    MultipleFound(Vec<String>),

    #[error("{0} does not offer the Trezor service")]
    MissingService(String),

    #[error(
        "{0} lacks the Trezor characteristics; if it was paired before, forget it in the \
         Bluetooth settings and pair again"
    )]
    MissingCharacteristic(String),

    /// The link can't carry the Safe 7's 244-byte packets.
    #[error("the Bluetooth link's MTU is {mtu}, below the {MIN_MTU} the Safe 7 needs")]
    LinkUnsuitable { mtu: u16 },

    /// The device dropped this host's Bluetooth pairing (CoreBluetooth's
    /// "Peer removed pairing information").
    #[error(
        "the Safe 7 no longer knows this host: forget it in the Bluetooth settings, then choose \
         Pair new device on the Safe 7"
    )]
    BondRemoved,

    #[error("Bluetooth: {0}")]
    Ble(btleplug::Error),

    #[error("timed out {0}")]
    Timeout(&'static str),

    #[error("the Trezor disconnected")]
    Disconnected,

    #[error("pairing cancelled: no code was entered")]
    PairingCancelled,

    /// A previous call was abandoned midway: reconnect.
    #[error("the connection is out of step after an abandoned call; reconnect")]
    Abandoned,

    #[error(transparent)]
    Trezor(#[from] VaultCoreError),
}

impl From<btleplug::Error> for TrezorBleError {
    fn from(error: btleplug::Error) -> Self {
        match error {
            btleplug::Error::PermissionDenied => Self::PermissionDenied,
            btleplug::Error::NoAdapterAvailable => Self::NoAdapter,
            btleplug::Error::NotConnected => Self::Disconnected,
            other
                if other
                    .to_string()
                    .contains("Peer removed pairing information") =>
            {
                Self::BondRemoved
            }
            other => Self::Ble(other),
        }
    }
}

impl From<TrezorError> for TrezorBleError {
    fn from(error: TrezorError) -> Self {
        Self::Trezor(error.into())
    }
}

/// A stable, flat category of [`TrezorBleError`], for a UI or an FFI layer
/// that can't carry the full error.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ErrorKind {
    NoBluetoothAdapter,
    BluetoothPermissionDenied,
    NoDeviceFound,
    MultipleDevicesFound,
    UnsupportedModel,
    UnsupportedFirmware,
    /// In the bootloader, without a wallet, or still locked.
    DeviceNotReady,
    DeviceBusy,
    MissingService,
    MissingCharacteristic,
    LinkUnsuitable,
    BondRemoved,
    ConnectionFailed,
    Timeout,
    Disconnected,
    PairingFailed,
    PairingCancelled,
    UserCancelled,
    DeviceError,
    InvalidRequest,
    SignatureCheckFailed,
    ProtocolError,
}

impl TrezorBleError {
    pub fn kind(&self) -> ErrorKind {
        match self {
            Self::NoAdapter => ErrorKind::NoBluetoothAdapter,
            Self::PermissionDenied => ErrorKind::BluetoothPermissionDenied,
            Self::NotFound { .. } => ErrorKind::NoDeviceFound,
            Self::MultipleFound(_) => ErrorKind::MultipleDevicesFound,
            Self::MissingService(_) => ErrorKind::MissingService,
            Self::MissingCharacteristic(_) => ErrorKind::MissingCharacteristic,
            Self::LinkUnsuitable { .. } => ErrorKind::LinkUnsuitable,
            Self::BondRemoved => ErrorKind::BondRemoved,
            Self::Ble(_) => ErrorKind::ConnectionFailed,
            Self::Timeout(_) => ErrorKind::Timeout,
            Self::Disconnected => ErrorKind::Disconnected,
            Self::PairingCancelled => ErrorKind::PairingCancelled,
            Self::Abandoned => ErrorKind::ProtocolError,
            Self::Trezor(VaultCoreError::Trezor(error)) => trezor_kind(error),
            Self::Trezor(_) => ErrorKind::ProtocolError,
        }
    }
}

fn trezor_kind(error: &TrezorError) -> ErrorKind {
    match error {
        TrezorError::UnsupportedModel(_) => ErrorKind::UnsupportedModel,
        TrezorError::UnsupportedFirmware(_) => ErrorKind::UnsupportedFirmware,
        TrezorError::Bootloader | TrezorError::NotInitialised | TrezorError::Locked => {
            ErrorKind::DeviceNotReady
        }
        TrezorError::Busy(_) => ErrorKind::DeviceBusy,
        TrezorError::PairingUnavailable
        | TrezorError::PairingCodeInvalid
        | TrezorError::PairingFailed(_) => ErrorKind::PairingFailed,
        TrezorError::UserCancelled => ErrorKind::UserCancelled,
        TrezorError::Failure { .. } | TrezorError::NetworkMismatch(_) => ErrorKind::DeviceError,
        TrezorError::InvalidRequest(_) => ErrorKind::InvalidRequest,
        TrezorError::Signatures(_) => ErrorKind::SignatureCheckFailed,
        TrezorError::Protocol(_) | TrezorError::OutOfOrder => ErrorKind::ProtocolError,
    }
}

/// Supplies the six digits the Safe 7 shows while pairing (not its PIN), or
/// `None` if the user gave up.
pub trait PairingCodeSource: Send + Sync {
    fn pairing_code(&self) -> Pin<Box<dyn Future<Output = Option<String>> + Send + '_>>;
}

impl<F, Fut> PairingCodeSource for F
where
    F: Fn() -> Fut + Send + Sync,
    Fut: Future<Output = Option<String>> + Send + 'static,
{
    fn pairing_code(&self) -> Pin<Box<dyn Future<Output = Option<String>> + Send + '_>> {
        Box::pin(self())
    }
}

#[derive(Debug, Clone)]
pub struct ConnectOptions {
    pub network: Network,
    /// Shown on the Safe 7: "Allow {app_name} on {host_name} to pair?".
    /// At most 32 bytes each.
    pub host_name: String,
    pub app_name: String,
    pub passphrase: SessionPassphrase,
    /// From an earlier connection to this Safe 7, if kept.
    pub credential: Option<PairingCredential>,
    /// Which device, when several are in range: a [`FoundDevice::id`], or
    /// its name or the end of it. `None` takes the only one in range.
    pub device: Option<String>,
    pub scan: Duration,
}

/// A device seen advertising the Trezor service. Nothing here proves the
/// model: the connection checks that.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FoundDevice {
    /// The platform's handle for this device (on iOS a per-app UUID, not
    /// the Bluetooth address, which a Safe 7 rotates anyway).
    pub id: String,
    pub name: Option<String>,
    pub rssi: Option<i16>,
}

/// A connected Safe 7, and the credential to keep if this was a fresh pairing.
pub struct Connection {
    pub device: TrezorBle,
    pub new_credential: Option<PairingCredential>,
}

/// One live BLE connection to one Safe 7, with its protocol session.
///
/// A request that fails on the device's side (declined, cancelled, refused)
/// leaves the connection usable. A timeout, a dropped link, or a call whose
/// future was dropped midway doesn't: reconnect before the next call.
pub struct TrezorBle {
    radio: Radio,
    name: String,
    session: TrezorSession,
    identity: Safe7Identity,
    cancel: Canceller,
    /// Set while a call is in progress; still set at the next call means the
    /// last one was dropped midway.
    busy: bool,
}

/// Asks the device to drop the prompt of the request in progress. The
/// request then fails with [`ErrorKind::UserCancelled`] and the connection
/// stays usable. Cheap to clone, and usable from any thread.
#[derive(Clone, Default)]
pub struct Canceller(Arc<CancelState>);

#[derive(Default)]
struct CancelState {
    requested: AtomicBool,
    notify: Notify,
}

impl Canceller {
    pub fn cancel(&self) {
        self.0.requested.store(true, Ordering::SeqCst);
        self.0.notify.notify_waiters();
    }

    fn reset(&self) {
        self.0.requested.store(false, Ordering::SeqCst);
    }

    async fn requested(&self) {
        loop {
            let notified = self.0.notify.notified();
            if self.0.requested.load(Ordering::SeqCst) {
                return;
            }
            notified.await;
        }
    }
}

/// The account xpub at a path, with the master fingerprint PSBTs name the
/// device's keys by.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AccountXpub {
    pub xpub: Xpub,
    pub master_fingerprint: Fingerprint,
    /// The device's own encoding (see [`XpubOptions::slip132`]).
    pub as_shown: String,
}

impl TrezorBle {
    /// Lists devices advertising the Trezor service, to choose one by
    /// [`FoundDevice::id`]. Devices that advertise another Trezor model are
    /// left out. Finding none is [`TrezorBleError::NotFound`], which says
    /// whether anything was seen at all.
    pub async fn scan(scan: Duration) -> Result<Vec<FoundDevice>, TrezorBleError> {
        let adapter = adapter().await?;
        let (found, seen) = scan_for_trezors(&adapter, scan).await?;
        if found.is_empty() {
            return Err(TrezorBleError::NotFound { seen });
        }
        Ok(found.into_iter().map(|(device, _)| device).collect())
    }

    /// Finds the Safe 7, connects, pairs or reconnects, and checks it is a
    /// Safe 7. `codes` is asked for the pairing code if the device needs one.
    pub async fn connect(
        options: ConnectOptions,
        codes: &dyn PairingCodeSource,
    ) -> Result<Connection, TrezorBleError> {
        let mut session = TrezorSession::new(
            SessionConfig {
                network: options.network,
                host_name: options.host_name.clone(),
                app_name: options.app_name.clone(),
                passphrase: options.passphrase,
            },
            options.credential.as_ref(),
        )?;
        let adapter = adapter().await?;
        let events = adapter.events().await?;
        let (found, seen) = scan_for_trezors(&adapter, options.scan).await?;
        let (device, peripheral) = select(found, seen, options.device.as_deref())?;
        let name = device.name.clone().unwrap_or_else(|| device.id.clone());

        let mut radio = match Radio::open(peripheral, events, &name).await {
            Ok(radio) => radio,
            Err(error) => return Err(error),
        };
        let cancel = Canceller::default();
        let outcome = async {
            let first = session.start()?;
            drive(&mut radio, &mut session, first, Some(codes), &cancel).await
        }
        .await;
        match outcome {
            Ok(TrezorEvent::Connected {
                identity,
                new_credential,
            }) => Ok(Connection {
                device: Self {
                    radio,
                    name,
                    session,
                    identity,
                    cancel,
                    busy: false,
                },
                new_credential,
            }),
            Ok(other) => {
                radio.close().await;
                Err(unexpected(other))
            }
            Err(error) => {
                radio.close().await;
                Err(error)
            }
        }
    }

    /// The device's advertised name (a user may have changed it).
    pub fn name(&self) -> &str {
        &self.name
    }

    /// Who answered: always a Safe 7, with its firmware version.
    pub fn identity(&self) -> &Safe7Identity {
        &self.identity
    }

    /// The link's negotiated ATT MTU, for diagnostics.
    pub fn mtu(&self) -> u16 {
        self.radio.peripheral.mtu()
    }

    /// The xpub at an account path (BIP-48 P2WSH, or BIP-84 to compare with
    /// Trezor Suite).
    pub async fn xpub(
        &mut self,
        path: &DerivationPath,
        options: XpubOptions,
    ) -> Result<AccountXpub, TrezorBleError> {
        self.begin()?;
        let first = self.session.request_xpub(path, options)?;
        match self.run(first).await? {
            TrezorEvent::Xpub {
                xpub,
                master_fingerprint,
                as_shown,
            } => Ok(AccountXpub {
                xpub,
                master_fingerprint,
                as_shown,
            }),
            other => Err(unexpected(other)),
        }
    }

    /// Has the Safe 7 co-sign a vault PSBT once the user confirms it on the
    /// device. `signer` is the master fingerprint the vault expects this
    /// device to sign with. The result is only returned after it checks out:
    /// `psbt` plus valid signatures from `signer`'s keys, nothing else.
    pub async fn sign_psbt(
        &mut self,
        psbt: &Psbt,
        signer: Fingerprint,
    ) -> Result<Psbt, TrezorBleError> {
        self.begin()?;
        let first = self.session.request_sign_psbt(psbt, signer)?;
        match self.run(first).await? {
            TrezorEvent::SignedPsbt(signed) => Ok(signed),
            other => Err(unexpected(other)),
        }
    }

    pub fn canceller(&self) -> Canceller {
        self.cancel.clone()
    }

    pub async fn disconnect(self) -> Result<(), TrezorBleError> {
        let peripheral = &self.radio.peripheral;
        if within(LINK_TIMEOUT, "checking the link", peripheral.is_connected()).await? {
            within(LINK_TIMEOUT, "disconnecting", peripheral.disconnect()).await?;
        }
        Ok(())
    }

    fn begin(&mut self) -> Result<(), TrezorBleError> {
        if self.busy {
            self.session.abandon();
            return Err(TrezorBleError::Abandoned);
        }
        self.cancel.reset();
        Ok(())
    }

    async fn run(&mut self, first: Vec<Vec<u8>>) -> Result<TrezorEvent, TrezorBleError> {
        self.busy = true;
        let outcome = drive(
            &mut self.radio,
            &mut self.session,
            first,
            None,
            &self.cancel,
        )
        .await;
        self.busy = false;
        outcome
    }
}

/// Writes `first`, then pumps packets between the link and the session until
/// the exchange ends. `codes` answers [`TrezorEvent::PairingCodeNeeded`].
async fn drive(
    radio: &mut Radio,
    session: &mut TrezorSession,
    first: Vec<Vec<u8>>,
    codes: Option<&dyn PairingCodeSource>,
    cancel: &Canceller,
) -> Result<TrezorEvent, TrezorBleError> {
    let outcome = exchange(radio, session, first, codes, cancel).await;
    if let Err(error) = &outcome {
        let device_side = matches!(error, TrezorBleError::Trezor(VaultCoreError::Trezor(_)));
        if !device_side || !session.is_ready() {
            // A link failure, or the session itself broke: either way the
            // device is out of step with us.
            session.abandon();
        }
    }
    outcome
}

async fn exchange(
    radio: &mut Radio,
    session: &mut TrezorSession,
    first: Vec<Vec<u8>>,
    codes: Option<&dyn PairingCodeSource>,
    cancel: &Canceller,
) -> Result<TrezorEvent, TrezorBleError> {
    radio.write(&first).await?;
    let mut deadline = reply_deadline(session);
    let mut cancel_sent = false;
    loop {
        let ack_wait = session.ack_wait();
        tokio::select! {
            packet = radio.next_packet() => {
                let packet = packet?;
                let step = session.receive(&packet)?;
                radio.write(&step.send).await?;
                match step.event {
                    None | Some(TrezorEvent::AwaitingUser(_)) => {}
                    Some(TrezorEvent::PairingCodeNeeded) => {
                        let codes = codes.ok_or_else(|| unexpected(TrezorEvent::PairingCodeNeeded))?;
                        let code = code_while_listening(radio, session, codes).await?;
                        let reply = session.pairing_code(code.trim())?;
                        radio.write(&reply).await?;
                    }
                    Some(TrezorEvent::Failed(error)) => return Err(error.into()),
                    Some(done) => return Ok(done),
                }
                deadline = reply_deadline(session);
            }
            () = sleep_or_never(ack_wait) => {
                let resend = session.retransmit()?;
                radio.write(&resend).await?;
            }
            () = cancel.requested(), if !cancel_sent => {
                cancel_sent = true;
                let packets = session.request_cancel()?;
                radio.write(&packets).await?;
                deadline = reply_deadline(session);
            }
            () = tokio::time::sleep_until(deadline) => {
                return Err(TrezorBleError::Timeout(if session.awaiting_user() {
                    "waiting for the user on the Trezor"
                } else {
                    "waiting for the Trezor"
                }));
            }
        }
    }
}

/// Minutes while the device waits on its user, seconds otherwise. The
/// session knows which: the device acknowledges a prompt after showing it,
/// so the last packet's kind doesn't tell.
fn reply_deadline(session: &TrezorSession) -> Instant {
    Instant::now()
        + if session.awaiting_user() {
            USER_TIMEOUT
        } else {
            REPLY_TIMEOUT
        }
}

/// Waits for the user's pairing code while still reading the link: the
/// Safe 7 may end pairing meanwhile (the only button on its code screen
/// cancels it), and the user shouldn't have to type a code to find out.
async fn code_while_listening(
    radio: &mut Radio,
    session: &mut TrezorSession,
    codes: &dyn PairingCodeSource,
) -> Result<String, TrezorBleError> {
    let code = codes.pairing_code();
    tokio::pin!(code);
    let deadline = tokio::time::sleep(USER_TIMEOUT);
    tokio::pin!(deadline);
    loop {
        tokio::select! {
            code = &mut code => return code.ok_or(TrezorBleError::PairingCancelled),
            packet = radio.next_packet() => {
                let step = session.receive(&packet?)?;
                radio.write(&step.send).await?;
                match step.event {
                    None | Some(TrezorEvent::AwaitingUser(_)) => {}
                    Some(TrezorEvent::Failed(error)) => return Err(error.into()),
                    Some(other) => return Err(unexpected(other)),
                }
            }
            () = &mut deadline => return Err(TrezorBleError::Timeout("waiting for the pairing code")),
        }
    }
}

async fn sleep_or_never(wait: Option<Duration>) {
    match wait {
        Some(wait) => tokio::time::sleep(wait).await,
        None => std::future::pending().await,
    }
}

/// The open GATT link: writes go to one characteristic, packets arrive as
/// notifications on another.
struct Radio {
    peripheral: Peripheral,
    write_characteristic: Characteristic,
    notifications: Pin<Box<dyn Stream<Item = ValueNotification> + Send>>,
    /// Adapter events, watched for this peripheral's disconnect: btleplug
    /// keeps the notification stream open after the link is gone.
    events: Pin<Box<dyn Stream<Item = CentralEvent> + Send>>,
}

impl Radio {
    async fn open(
        peripheral: Peripheral,
        events: Pin<Box<dyn Stream<Item = CentralEvent> + Send>>,
        name: &str,
    ) -> Result<Self, TrezorBleError> {
        let opened = async {
            connect_with_retries(&peripheral).await?;
            within(
                LINK_TIMEOUT,
                "discovering services",
                peripheral.discover_services(),
            )
            .await?;
            let characteristics = peripheral.characteristics();
            if !characteristics
                .iter()
                .any(|c| c.service_uuid.to_string() == SERVICE_UUID)
            {
                return Err(TrezorBleError::MissingService(name.to_owned()));
            }
            let (write_characteristic, notify_characteristic) =
                trezor_characteristics(&characteristics)
                    .ok_or_else(|| TrezorBleError::MissingCharacteristic(name.to_owned()))?;
            let mtu = peripheral.mtu();
            if mtu < MIN_MTU {
                return Err(TrezorBleError::LinkUnsuitable { mtu });
            }
            let notifications =
                within(WRITE_TIMEOUT, "subscribing", peripheral.notifications()).await?;
            within(
                WRITE_TIMEOUT,
                "subscribing",
                peripheral.subscribe(&notify_characteristic),
            )
            .await?;
            Ok((write_characteristic, notifications))
        }
        .await;
        match opened {
            Ok((write_characteristic, notifications)) => Ok(Self {
                peripheral,
                write_characteristic,
                notifications,
                events,
            }),
            Err(error) => {
                let _ = tokio::time::timeout(LINK_TIMEOUT, peripheral.disconnect()).await;
                Err(error)
            }
        }
    }

    /// Writes with response: CoreBluetooth gives no flow control for writes
    /// without response in btleplug (Trezor ships a fork for that), and
    /// trezorlib writes with response on Apple platforms too.
    async fn write(&mut self, packets: &[Vec<u8>]) -> Result<(), TrezorBleError> {
        for packet in packets {
            let write =
                self.peripheral
                    .write(&self.write_characteristic, packet, WriteType::WithResponse);
            within(WRITE_TIMEOUT, "writing to the Trezor", write).await?;
        }
        Ok(())
    }

    /// The next packet from the device, waiting as long as it takes: the
    /// caller bounds the wait.
    async fn next_packet(&mut self) -> Result<Vec<u8>, TrezorBleError> {
        let id = self.peripheral.id();
        loop {
            tokio::select! {
                notification = self.notifications.next() => match notification {
                    Some(n) if n.uuid.to_string() == NOTIFY_CHARACTERISTIC_UUID => return Ok(n.value),
                    Some(_) => {}
                    None => return Err(TrezorBleError::Disconnected),
                },
                event = self.events.next() => match event {
                    Some(CentralEvent::DeviceDisconnected(gone)) if gone == id => {
                        return Err(TrezorBleError::Disconnected);
                    }
                    Some(_) => {}
                    None => return Err(TrezorBleError::Disconnected),
                },
            }
        }
    }

    async fn close(&mut self) {
        let _ = tokio::time::timeout(LINK_TIMEOUT, self.peripheral.disconnect()).await;
    }
}

async fn adapter() -> Result<Adapter, TrezorBleError> {
    let manager = Manager::new().await?;
    manager
        .adapters()
        .await?
        .into_iter()
        .next()
        .ok_or(TrezorBleError::NoAdapter)
}

/// Scans for `scan` and returns the devices advertising the Trezor service
/// (or Trezor's company id), less those advertising another model's code;
/// and how many named devices were seen in all.
async fn scan_for_trezors(
    adapter: &Adapter,
    scan: Duration,
) -> Result<(Vec<(FoundDevice, Peripheral)>, usize), TrezorBleError> {
    // Unfiltered, as jade-ble: the service UUID is in the scan response
    // only, which not every platform's filter looks at.
    adapter.start_scan(ScanFilter::default()).await?;
    tokio::time::sleep(scan).await;
    let mut found = Vec::new();
    let mut seen = 0;
    for peripheral in adapter.peripherals().await? {
        let Some(properties) = peripheral.properties().await? else {
            continue;
        };
        seen += usize::from(properties.local_name.is_some());
        if is_candidate(&properties) {
            let device = FoundDevice {
                id: peripheral.id().to_string(),
                name: properties.local_name.clone(),
                rssi: properties.rssi,
            };
            found.push((device, peripheral));
        }
    }
    adapter.stop_scan().await?;
    Ok((found, seen))
}

fn is_candidate(properties: &PeripheralProperties) -> bool {
    let advertises_service = properties
        .services
        .iter()
        .any(|uuid| uuid.to_string() == SERVICE_UUID);
    let trezor_data = properties.manufacturer_data.get(&TREZOR_COMPANY_ID);
    // A hint, never proof: a device saying it is another model is left out;
    // one saying nothing, or that it is a Safe 7, is checked after connecting.
    let another_model = trezor_data
        .and_then(|data| data.get(2))
        .is_some_and(|&code| code != SAFE7_BLE_CODE);
    (advertises_service || trezor_data.is_some()) && !another_model
}

/// Picks the device whose id or name matches `wanted` (a name may be given
/// by its end), or, without `wanted`, the only one.
fn select<T>(
    found: Vec<(FoundDevice, T)>,
    seen: usize,
    wanted: Option<&str>,
) -> Result<(FoundDevice, T), TrezorBleError> {
    let mut matching: Vec<_> = found
        .into_iter()
        .filter(|(device, _)| {
            wanted.is_none_or(|wanted| {
                device.id == wanted
                    || device
                        .name
                        .as_deref()
                        .is_some_and(|name| name.ends_with(wanted))
            })
        })
        .collect();
    match matching.len() {
        0 => Err(TrezorBleError::NotFound { seen }),
        1 => Ok(matching.remove(0)),
        _ => Err(TrezorBleError::MultipleFound(
            matching
                .into_iter()
                .map(|(device, _)| match device.name {
                    Some(name) => format!("{name} ({})", device.id),
                    None => device.id,
                })
                .collect(),
        )),
    }
}

fn not_found_message(seen: usize) -> String {
    if seen == 0 {
        return "no Bluetooth devices seen at all. Does this app have Bluetooth permission?"
            .to_owned();
    }
    format!(
        "no Trezor Safe 7 among the {seen} devices seen. Wake it and keep it in range: asleep, \
         it turns Bluetooth off. To pair a new host, choose Pair new device in its Bluetooth menu"
    )
}

/// The write and notify characteristics of the Trezor service, if this
/// peripheral has them with the properties THP relies on.
fn trezor_characteristics<'a>(
    characteristics: impl IntoIterator<Item = &'a Characteristic>,
) -> Option<(Characteristic, Characteristic)> {
    let (mut write, mut notify) = (None, None);
    for characteristic in characteristics {
        if characteristic.service_uuid.to_string() != SERVICE_UUID {
            continue;
        }
        let uuid = characteristic.uuid.to_string();
        let properties = characteristic.properties;
        if uuid == WRITE_CHARACTERISTIC_UUID && properties.contains(CharPropFlags::WRITE) {
            write = Some(characteristic.clone());
        } else if uuid == NOTIFY_CHARACTERISTIC_UUID && properties.contains(CharPropFlags::NOTIFY) {
            notify = Some(characteristic.clone());
        }
    }
    Some((write?, notify?))
}

async fn connect_with_retries(peripheral: &Peripheral) -> Result<(), TrezorBleError> {
    if within(LINK_TIMEOUT, "checking the link", peripheral.is_connected()).await? {
        return Ok(());
    }
    let mut attempt = 1;
    loop {
        match within(LINK_TIMEOUT, "connecting", peripheral.connect()).await {
            Ok(()) => return Ok(()),
            Err(error) if attempt == CONNECT_ATTEMPTS => return Err(error),
            Err(TrezorBleError::BondRemoved) => return Err(TrezorBleError::BondRemoved),
            Err(_) => {
                attempt += 1;
                tokio::time::sleep(Duration::from_millis(500)).await;
            }
        }
    }
}

async fn within<T>(
    limit: Duration,
    what: &'static str,
    operation: impl Future<Output = Result<T, btleplug::Error>>,
) -> Result<T, TrezorBleError> {
    tokio::time::timeout(limit, operation)
        .await
        .map_err(|_| TrezorBleError::Timeout(what))?
        .map_err(TrezorBleError::from)
}

/// The session answers each request with its own kind of event, so this
/// only fires on a bug in the session itself.
fn unexpected(event: TrezorEvent) -> TrezorBleError {
    TrezorError::Protocol(format!("unexpected {event:?}")).into()
}

#[cfg(test)]
mod tests {
    use std::collections::HashMap;

    use super::*;

    fn device(id: &str, name: Option<&str>) -> (FoundDevice, ()) {
        (
            FoundDevice {
                id: id.into(),
                name: name.map(Into::into),
                rssi: None,
            },
            (),
        )
    }

    #[test]
    fn picks_the_only_trezor_or_the_one_asked_for() {
        let one = vec![device("A1", Some("Trezor Safe 7"))];
        assert_eq!(select(one, 5, None).unwrap().0.id, "A1");

        let two = || {
            vec![
                device("A1", Some("Trezor Safe 7")),
                device("B2", Some("My Trezor")),
            ]
        };
        assert!(
            matches!(select(two(), 5, None), Err(TrezorBleError::MultipleFound(names)) if names.len() == 2)
        );
        assert_eq!(select(two(), 5, Some("B2")).unwrap().0.id, "B2");
        assert_eq!(select(two(), 5, Some("My Trezor")).unwrap().0.id, "B2");
        assert_eq!(select(two(), 5, Some("Safe 7")).unwrap().0.id, "A1");

        // Two devices with the same name can still be told apart by id.
        let twins = vec![
            device("A1", Some("Trezor Safe 7")),
            device("B2", Some("Trezor Safe 7")),
        ];
        assert!(matches!(
            select(twins, 5, Some("Trezor Safe 7")),
            Err(TrezorBleError::MultipleFound(_))
        ));
    }

    #[test]
    fn nothing_found_says_why() {
        assert!(matches!(
            select::<()>(Vec::new(), 4, None),
            Err(TrezorBleError::NotFound { seen: 4 })
        ));
        let none = select::<()>(Vec::new(), 0, None).unwrap_err().to_string();
        assert!(none.contains("Bluetooth permission"), "{none}");
        let wrong = select(vec![device("A1", Some("Trezor Safe 7"))], 3, Some("ffff"));
        assert!(matches!(wrong, Err(TrezorBleError::NotFound { seen: 3 })));
    }

    fn properties(services: &[&str], data: Option<Vec<u8>>) -> PeripheralProperties {
        let mut manufacturer_data = HashMap::new();
        if let Some(data) = data {
            manufacturer_data.insert(TREZOR_COMPANY_ID, data);
        }
        PeripheralProperties {
            services: services.iter().map(|uuid| uuid.parse().unwrap()).collect(),
            manufacturer_data,
            ..Default::default()
        }
    }

    #[test]
    fn candidates_advertise_trezor_and_not_another_model() {
        // Service UUID, with or without the model code.
        assert!(is_candidate(&properties(&[SERVICE_UUID], None)));
        assert!(is_candidate(&properties(
            &[SERVICE_UUID],
            Some(vec![0, 1, 6, 0, 0, 0])
        )));
        // Company id alone (the UUID sits in a scan response a platform may miss).
        assert!(is_candidate(&properties(&[], Some(vec![0, 1, 6, 0, 0, 0]))));
        // Another model's code (7 is the T3T2's), or no Trezor at all.
        assert!(!is_candidate(&properties(
            &[SERVICE_UUID],
            Some(vec![0, 1, 7, 0, 0, 0])
        )));
        assert!(!is_candidate(&properties(
            &["0000180f-0000-1000-8000-00805f9b34fb"],
            None
        )));
    }

    fn characteristic(uuid: &str, properties: CharPropFlags) -> Characteristic {
        Characteristic {
            uuid: uuid.parse().unwrap(),
            service_uuid: SERVICE_UUID.parse().unwrap(),
            properties,
            descriptors: Default::default(),
        }
    }

    #[test]
    fn finds_the_characteristics_only_with_the_right_properties() {
        let write = characteristic(
            WRITE_CHARACTERISTIC_UUID,
            CharPropFlags::WRITE | CharPropFlags::WRITE_WITHOUT_RESPONSE,
        );
        let notify = characteristic(NOTIFY_CHARACTERISTIC_UUID, CharPropFlags::NOTIFY);
        let (w, n) = trezor_characteristics([&write, &notify]).unwrap();
        assert_eq!((w.uuid, n.uuid), (write.uuid, notify.uuid));

        let read_only = characteristic(WRITE_CHARACTERISTIC_UUID, CharPropFlags::READ);
        assert!(trezor_characteristics([&read_only, &notify]).is_none());
        let indicate_only = characteristic(NOTIFY_CHARACTERISTIC_UUID, CharPropFlags::INDICATE);
        assert!(trezor_characteristics([&write, &indicate_only]).is_none());
        assert!(trezor_characteristics([&write]).is_none());
        let mut elsewhere = write.clone();
        elsewhere.service_uuid = "0000180f-0000-1000-8000-00805f9b34fb".parse().unwrap();
        assert!(trezor_characteristics([&elsewhere, &notify]).is_none());
    }

    #[test]
    fn error_kinds_are_stable() {
        use vault_core::hw::psbt_check::SignatureCheckError;
        let cases: Vec<(TrezorBleError, ErrorKind)> = vec![
            (TrezorBleError::NoAdapter, ErrorKind::NoBluetoothAdapter),
            (
                btleplug::Error::PermissionDenied.into(),
                ErrorKind::BluetoothPermissionDenied,
            ),
            (
                btleplug::Error::NoAdapterAvailable.into(),
                ErrorKind::NoBluetoothAdapter,
            ),
            (
                btleplug::Error::NotConnected.into(),
                ErrorKind::Disconnected,
            ),
            (
                btleplug::Error::Other("Peer removed pairing information".into()).into(),
                ErrorKind::BondRemoved,
            ),
            (
                btleplug::Error::DeviceNotFound.into(),
                ErrorKind::ConnectionFailed,
            ),
            (
                TrezorBleError::NotFound { seen: 0 },
                ErrorKind::NoDeviceFound,
            ),
            (
                TrezorBleError::MultipleFound(vec![]),
                ErrorKind::MultipleDevicesFound,
            ),
            (
                TrezorBleError::MissingService("x".into()),
                ErrorKind::MissingService,
            ),
            (
                TrezorBleError::MissingCharacteristic("x".into()),
                ErrorKind::MissingCharacteristic,
            ),
            (
                TrezorBleError::LinkUnsuitable { mtu: 185 },
                ErrorKind::LinkUnsuitable,
            ),
            (TrezorBleError::Timeout("x"), ErrorKind::Timeout),
            (TrezorBleError::Disconnected, ErrorKind::Disconnected),
            (
                TrezorBleError::PairingCancelled,
                ErrorKind::PairingCancelled,
            ),
            (TrezorBleError::Abandoned, ErrorKind::ProtocolError),
            (
                TrezorError::UnsupportedModel("T3T2".into()).into(),
                ErrorKind::UnsupportedModel,
            ),
            (
                TrezorError::UnsupportedFirmware("2.8.0".into()).into(),
                ErrorKind::UnsupportedFirmware,
            ),
            (TrezorError::Bootloader.into(), ErrorKind::DeviceNotReady),
            (
                TrezorError::NotInitialised.into(),
                ErrorKind::DeviceNotReady,
            ),
            (TrezorError::Locked.into(), ErrorKind::DeviceNotReady),
            (
                TrezorError::Busy("busy".into()).into(),
                ErrorKind::DeviceBusy,
            ),
            (
                TrezorError::PairingCodeInvalid.into(),
                ErrorKind::PairingFailed,
            ),
            (
                TrezorError::PairingFailed("x").into(),
                ErrorKind::PairingFailed,
            ),
            (
                TrezorError::PairingUnavailable.into(),
                ErrorKind::PairingFailed,
            ),
            (TrezorError::UserCancelled.into(), ErrorKind::UserCancelled),
            (
                TrezorError::Failure {
                    code: 9,
                    message: "x".into(),
                }
                .into(),
                ErrorKind::DeviceError,
            ),
            (
                TrezorError::InvalidRequest("x".into()).into(),
                ErrorKind::InvalidRequest,
            ),
            (
                TrezorError::Signatures(SignatureCheckError::NoSignatures).into(),
                ErrorKind::SignatureCheckFailed,
            ),
            (
                TrezorError::Protocol("x".into()).into(),
                ErrorKind::ProtocolError,
            ),
            (TrezorError::OutOfOrder.into(), ErrorKind::ProtocolError),
        ];
        for (error, kind) in cases {
            assert_eq!(error.kind(), kind, "{error}");
        }
    }

    #[test]
    fn a_cancel_before_the_request_does_not_leak_into_it() {
        let canceller = Canceller::default();
        canceller.cancel();
        canceller.reset();
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_time()
            .build()
            .unwrap();
        let waited = runtime.block_on(async {
            tokio::time::timeout(Duration::from_millis(20), canceller.requested()).await
        });
        assert!(waited.is_err(), "a reset cancel stays reset");
        canceller.cancel();
        let fired = runtime.block_on(async {
            tokio::time::timeout(Duration::from_millis(20), canceller.requested()).await
        });
        assert!(fired.is_ok());
    }

    /// UniFFI wraps objects in `Arc` and runs their futures on a runtime it
    /// is handed, so everything public must be `Send`, and these `Sync` too.
    #[test]
    fn the_api_is_send() {
        fn send<T: Send>(_: &T) {}
        fn send_sync<T: Send + Sync>() {}
        send_sync::<Canceller>();
        send_sync::<TrezorBleError>();
        send_sync::<ConnectOptions>();
        fn connect_future(options: ConnectOptions, codes: &dyn PairingCodeSource) {
            send(&TrezorBle::connect(options, codes));
        }
        fn requests(device: &mut TrezorBle, path: &DerivationPath, psbt: &Psbt) {
            send(&device.xpub(path, XpubOptions::default()));
            send(&device.sign_psbt(psbt, Fingerprint::default()));
        }
        fn whole(device: TrezorBle) {
            send(&device);
            send(&device.disconnect());
        }
        let _ = (connect_future, requests, whole);
    }
}
