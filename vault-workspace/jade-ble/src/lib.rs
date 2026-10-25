//! Talks to a Blockstream Jade over Bluetooth LE.
//!
//! This crate only moves bytes. Every protocol decision — request ids,
//! reassembly, the pieces of a signed PSBT, the pinserver hand-off, and
//! checking what the device returns — is made by
//! [`vault_core::hw::jade::JadeSession`]. What this crate adds is what needs a
//! radio and a clock: finding the Jade, the GATT plumbing, timeouts, noticing
//! a dropped link, and relaying the pinserver request over HTTPS.
//!
//! Mobile uses this crate through UniFFI (mobile-signer-ffi, Phase 1); for
//! now `examples/jade-hw-test` drives it from a desktop.
//!
//! Two things about the link that callers see:
//!
//! - **Pairing.** The Jade only answers over an encrypted, authenticated link.
//!   The first connection pairs by numeric comparison: the same six digits on
//!   the Jade and on the host, and the user confirms both.
//! - **Unlock is per connection.** A PIN wallet locks again whenever the link
//!   drops, so after any reconnect, call [`JadeBle::unlock`] again.

use std::future::Future;
use std::pin::Pin;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use bitcoin::bip32::{DerivationPath, Fingerprint, Xpub};
use bitcoin::{Address, Network, Psbt};
use btleplug::api::{
    Central, CentralEvent, CharPropFlags, Characteristic, Manager as _, Peripheral as _,
    ScanFilter, ValueNotification, WriteType,
};
use btleplug::platform::{Adapter, Manager, Peripheral};
use futures_util::{Stream, StreamExt};
use vault_core::error::VaultCoreError;
use vault_core::hw::AddressRequest;
use vault_core::hw::jade::{
    JadeError, JadeEvent, JadeRegistrationPolicy, JadeSession, VersionInfo,
};

// The Jade's GATT interface, from `main/ble/ble.c` in Jade firmware 1.0.41.
// Both characteristics require an encrypted, authenticated link.
const SERVICE_UUID: &str = "6e400001-b5a3-f393-e0a9-e50e24dcca9e";
/// Host → Jade, written with response.
const WRITE_CHARACTERISTIC_UUID: &str = "6e400002-b5a3-f393-e0a9-e50e24dcca9e";
/// Jade → host, as indications of at most `min(MTU - 3, 512)` bytes each.
const INDICATE_CHARACTERISTIC_UUID: &str = "6e400003-b5a3-f393-e0a9-e50e24dcca9e";
/// What Blockstream's own client writes per chunk (`jadepy/jade_ble.py`),
/// under the 512-byte ATT limit. The Jade reassembles a request however it
/// is split.
const MAX_WRITE_LEN: usize = 509;
/// The Jade advertises itself as `Jade` plus six hex characters, e.g. `Jade BE0184`.
const NAME_PREFIX: &str = "Jade";
/// What btleplug's CoreBluetooth backend hands every operation still pending when the link drops
/// (`drain_pending_operations`). A declined Jade pairing ended this way on macOS and on iOS.
const DROPPED_LINK: &str = "Device disconnected";

/// Connecting, discovering services, disconnecting.
const LINK_TIMEOUT: Duration = Duration::from_secs(15);
/// A single write or the subscription. Generous because the first ones on a
/// new link wait until the user has confirmed pairing on both devices.
const WRITE_TIMEOUT: Duration = Duration::from_secs(60);
/// Replies that come without the user: version info, xpubs, the next piece
/// of a signed PSBT.
const REPLY_TIMEOUT: Duration = Duration::from_secs(15);
/// Replies that wait for the user: entering a PIN (and, once the pinserver has
/// answered, any passphrase or PIN change), confirming a transaction.
const USER_TIMEOUT: Duration = Duration::from_secs(300);
const PINSERVER_TIMEOUT: Duration = Duration::from_secs(20);
const CONNECT_ATTEMPTS: u32 = 5;

#[derive(Debug, thiserror::Error)]
pub enum JadeBleError {
    #[error("no Bluetooth adapter on this host")]
    NoAdapter,

    /// `seen` counts every named device the scan found. Zero usually means
    /// this process may not use Bluetooth, not that no Jade is around: macOS
    /// hands an app without Bluetooth permission an empty scan, not an error.
    #[error("{}", not_found_message(*.seen))]
    NotFound { seen: usize },

    #[error("several Jades in range ({}); say which one by name", .0.join(", "))]
    MultipleFound(Vec<String>),

    #[error("{0} does not offer the Jade RPC service")]
    NotAJade(String),

    /// The Jade dropped this host's Bluetooth pairing (CoreBluetooth's
    /// "Peer removed pairing information"). The Jade keeps few pairings, so
    /// pairing another host can evict this one.
    #[error(
        "the Jade no longer knows this host: forget it in the Bluetooth settings, then connect \
         again and confirm the pairing on the Jade"
    )]
    BondRemoved,

    /// The OS asked this host to pair and the request was declined or not
    /// confirmed in time (CoreBluetooth's "Encryption is insufficient").
    #[error(
        "the Bluetooth pairing was not completed: confirm the pairing request on this device and \
         on the Jade within about 30 seconds, then connect again"
    )]
    PairingNotConfirmed,

    #[error("Bluetooth: {0}")]
    Ble(btleplug::Error),

    #[error("timed out {0}")]
    Timeout(&'static str),

    /// Service discovery did not finish. iOS holds it behind its pairing request, so a request
    /// that was cancelled or left unanswered ends as this timeout and nothing more.
    #[error(
        "timed out discovering services. If a pairing request was waiting, it was not confirmed \
         in time. Confirm it on this device and on the Jade, then connect again"
    )]
    DiscoveryTimedOut,

    #[error("the Jade disconnected")]
    Disconnected,

    /// The link dropped before the connection was ready, or on Android in the first write of a new
    /// link, where the Jade holds the pairing request. iOS and macOS report a rejected pairing this
    /// way, in the connect steps. A Jade that lost power in the same window drops the link the same
    /// way, so the text only says what may have been waiting.
    #[error(
        "the Jade dropped the link while connecting. If a pairing request was waiting, it was \
         not confirmed in time. Confirm it on this device and on the Jade, then connect again"
    )]
    DisconnectedWhileConnecting,

    /// The first write on a new Android link did not finish. Android holds it behind the pairing
    /// request. It fires only if the link stays up past the write limit.
    #[error(
        "timed out writing to the Jade. If a pairing request was waiting, it was not confirmed \
         in time. Confirm it on this device and on the Jade, then connect again"
    )]
    FirstWriteTimedOut,

    /// The pinserver could not be reached, or its response was no use. The
    /// Jade was sent `cancel` and the connection still works: call `unlock()`
    /// again.
    #[error("pinserver relay failed: {0}")]
    Pinserver(String),

    #[error(transparent)]
    Jade(#[from] VaultCoreError),
}

impl From<btleplug::Error> for JadeBleError {
    fn from(error: btleplug::Error) -> Self {
        match error {
            btleplug::Error::NotConnected => Self::Disconnected,
            // The whole text, not a fragment: anything else stays the raw Bluetooth error.
            btleplug::Error::RuntimeError(ref text) if text == DROPPED_LINK => Self::Disconnected,
            // CoreBluetooth hands btleplug only the localized text, so these match English.
            other => match other.to_string() {
                text if text.contains("Peer removed pairing information") => Self::BondRemoved,
                text if text.contains("Encryption is insufficient") => Self::PairingNotConfirmed,
                _ => Self::Ble(other),
            },
        }
    }
}

/// A link that drops before the connection is ready is most likely a pairing nobody confirmed.
fn while_connecting(error: JadeBleError) -> JadeBleError {
    match error {
        JadeBleError::Disconnected => JadeBleError::DisconnectedWhileConnecting,
        other => other,
    }
}

/// iOS holds service discovery until the user answers its pairing request, so a timeout here is
/// most likely a request nobody confirmed. A timeout in any other step keeps its stage name: a
/// Jade that never connects is out of range, not unpaired.
async fn discover(
    limit: Duration,
    operation: impl Future<Output = Result<(), btleplug::Error>>,
) -> Result<(), JadeBleError> {
    within(limit, "discovering services", operation)
        .await
        .map_err(|error| match error {
            JadeBleError::Timeout(_) => JadeBleError::DiscoveryTimedOut,
            other => while_connecting(other),
        })
}

/// Whether a pairing request may still be waiting inside the next write. iOS and macOS hold it in
/// service discovery, which is over once `connect` returns. Android lets `connect` return while
/// its bonding dialog is open, and the Jade holds the first write behind it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum PairingWait {
    Possible,
    Over,
}

impl PairingWait {
    fn on_new_link() -> Self {
        if cfg!(target_os = "android") {
            Self::Possible
        } else {
            Self::Over
        }
    }

    /// A first write that loses the link or stalls is most likely a pairing nobody confirmed. Any
    /// other error keeps its own meaning.
    fn explain_write(self, error: JadeBleError) -> JadeBleError {
        match (self, error) {
            (Self::Possible, JadeBleError::Disconnected) => {
                JadeBleError::DisconnectedWhileConnecting
            }
            (Self::Possible, JadeBleError::Timeout(_)) => JadeBleError::FirstWriteTimedOut,
            (_, other) => other,
        }
    }
}

/// Writes chunks back to back: the Jade throws a partial request away if the next chunk is more
/// than 2 s late. The first chunk the Jade acknowledges ends the pairing wait.
async fn write_chunks<'a, Fut>(
    limit: Duration,
    bytes: &'a [u8],
    pairing: &mut PairingWait,
    mut send: impl FnMut(&'a [u8]) -> Fut,
) -> Result<(), JadeBleError>
where
    Fut: Future<Output = Result<(), btleplug::Error>>,
{
    for chunk in bytes.chunks(MAX_WRITE_LEN) {
        within(limit, "writing to the Jade", send(chunk))
            .await
            .map_err(|error| pairing.explain_write(error))?;
        *pairing = PairingWait::Over;
    }
    Ok(())
}

/// One live BLE connection to one Jade, with its protocol session.
///
/// A device error (declined, wrong PIN, locked) or a failed pinserver relay
/// leaves the connection usable. A call that stops midway (a timeout, a
/// dropped link, its future dropped) leaves the Jade out of step with the
/// host: reconnect before the next call.
pub struct JadeBle {
    peripheral: Peripheral,
    name: String,
    network: Network,
    write_characteristic: Characteristic,
    notifications: Pin<Box<dyn Stream<Item = ValueNotification> + Send>>,
    /// Adapter events, watched for this peripheral's disconnect: btleplug keeps
    /// the notification stream open after the link is gone.
    events: Pin<Box<dyn Stream<Item = CentralEvent> + Send>>,
    session: JadeSession,
    pairing: PairingWait,
}

/// A Jade seen advertising. Connect to it by passing `name` to [`JadeBle::connect`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FoundJade {
    /// The advertised name, e.g. `Jade 1a2b3c`: the Jade's identity.
    pub name: String,
    /// The platform's handle for this device.
    pub id: String,
}

impl JadeBle {
    /// Lists the Jades in range. Finding none is [`JadeBleError::NotFound`],
    /// which says whether anything was seen at all.
    pub async fn scan(scan: Duration) -> Result<Vec<FoundJade>, JadeBleError> {
        let adapter = adapter().await?;
        let seen = scan_named(&adapter, scan).await?;
        found_jades(
            seen.into_iter()
                .map(|(name, peripheral)| FoundJade {
                    name,
                    id: peripheral.id().to_string(),
                })
                .collect(),
        )
    }

    /// Scans for `scan`, picks the Jade whose name ends with `name` (or the
    /// only Jade in range), connects, and subscribes to its replies.
    pub async fn connect(
        network: Network,
        name: Option<&str>,
        scan: Duration,
    ) -> Result<Self, JadeBleError> {
        let adapter = adapter().await?;
        let events = adapter.events().await?;
        let seen = scan_named(&adapter, scan).await?;
        let (name, peripheral) = select_jade(seen, name)?;

        // Armed before connecting: CoreBluetooth keeps a failed or dropped
        // connect queued with no timeout of its own, so every exit but
        // success disconnects, exhausted retries included.
        let link = Cleanup::link(&peripheral);
        connect_with_retries(&peripheral)
            .await
            .map_err(while_connecting)?;
        discover(LINK_TIMEOUT, peripheral.discover_services()).await?;
        let (write_characteristic, indicate_characteristic) =
            jade_characteristics(&peripheral.characteristics())
                .ok_or_else(|| JadeBleError::NotAJade(name.clone()))?;
        let notifications = within(WRITE_TIMEOUT, "subscribing", peripheral.notifications())
            .await
            .map_err(while_connecting)?;
        within(
            WRITE_TIMEOUT,
            "subscribing",
            peripheral.subscribe(&indicate_characteristic),
        )
        .await
        .map_err(while_connecting)?;
        link.disarm();

        Ok(Self {
            peripheral,
            name,
            network,
            write_characteristic,
            notifications,
            events,
            session: JadeSession::new(network),
            pairing: PairingWait::on_new_link(),
        })
    }

    /// The Jade's advertised name, e.g. `Jade 1a2b3c`.
    pub fn name(&self) -> &str {
        &self.name
    }

    /// The platform's physical BLE identifier, independent of the wallet seed.
    pub fn device_id(&self) -> String {
        self.peripheral.id().to_string()
    }

    /// Firmware, board, state and network pin. Needs no unlock.
    pub async fn version_info(&mut self) -> Result<VersionInfo, JadeBleError> {
        let request = self.session.request_version_info()?;
        match self.drive(request, REPLY_TIMEOUT).await? {
            JadeEvent::VersionInfo(info) => Ok(info),
            other => Err(unexpected(other)),
        }
    }

    /// Unlocks the Jade for this connection.
    ///
    /// A PIN wallet asks for its PIN on the device. A freshly restored one asks
    /// for a new PIN, and is pinned to this network from then on. A Temporary
    /// Signer wallet returns straight away. Checks the network pin first, so
    /// nobody types a PIN into a Jade that is going to refuse the network.
    pub async fn unlock(&mut self) -> Result<(), JadeBleError> {
        self.version_info().await?.check_usable(self.network)?;
        let unix_time = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_or(0, |elapsed| elapsed.as_secs());
        let request = self.session.request_unlock(unix_time)?;
        match self.drive(request, USER_TIMEOUT).await? {
            JadeEvent::Unlocked => Ok(()),
            other => Err(unexpected(other)),
        }
    }

    /// The xpub at `path`; the empty path gives the master key, whose
    /// fingerprint is the one PSBTs name. Needs [`JadeBle::unlock`] first.
    pub async fn xpub(&mut self, path: &DerivationPath) -> Result<Xpub, JadeBleError> {
        let request = self.session.request_xpub(path)?;
        match self.drive(request, REPLY_TIMEOUT).await? {
            JadeEvent::Xpub(xpub) => Ok(xpub),
            other => Err(unexpected(other)),
        }
    }

    /// Displays the descriptor address on the device and checks its reply.
    pub async fn verify_address(
        &mut self,
        request: &AddressRequest<'_>,
    ) -> Result<Address, JadeBleError> {
        self.verify_address_with_registration(request, JadeRegistrationPolicy::ExistingOnly)
            .await
    }

    /// Uses the dedicated test registration slot under the caller's device lease.
    pub async fn verify_address_with_registration(
        &mut self,
        request: &AddressRequest<'_>,
        policy: JadeRegistrationPolicy<'_>,
    ) -> Result<Address, JadeBleError> {
        let request = self
            .session
            .request_verify_address_with_registration(request, policy)?;
        match self.drive(request, USER_TIMEOUT).await? {
            JadeEvent::Address(address) => Ok(address),
            other => Err(unexpected(other)),
        }
    }

    /// Has the Jade sign `psbt` once the user confirms it on the device.
    ///
    /// `signer` is the master fingerprint the vault expects to sign with. The
    /// result is only returned after it checks out: it must be `psbt` plus
    /// valid signatures from `signer`'s keys and nothing else (see
    /// `vault_core::hw::jade`). Needs [`JadeBle::unlock`] first.
    pub async fn sign_psbt(
        &mut self,
        psbt: &Psbt,
        signer: Fingerprint,
    ) -> Result<Psbt, JadeBleError> {
        let request = self.session.request_sign_psbt(psbt, signer)?;
        match self.drive(request, USER_TIMEOUT).await? {
            JadeEvent::SignedPsbt(signed) => Ok(signed),
            other => Err(unexpected(other)),
        }
    }

    pub async fn disconnect(self) -> Result<(), JadeBleError> {
        if within(
            LINK_TIMEOUT,
            "checking the link",
            self.peripheral.is_connected(),
        )
        .await?
        {
            within(LINK_TIMEOUT, "disconnecting", self.peripheral.disconnect()).await?;
        }
        Ok(())
    }

    /// Sends `request` and pumps bytes between the link and the session until
    /// the exchange ends. `first_wait` bounds each wait that may be on the
    /// user: for the first reply, and for the one after a pinserver relay.
    async fn drive(
        &mut self,
        request: Vec<u8>,
        first_wait: Duration,
    ) -> Result<JadeEvent, JadeBleError> {
        let outcome = self.exchange(request, first_wait).await;
        if outcome.is_err() {
            self.session.abandon();
        }
        outcome
    }

    async fn exchange(
        &mut self,
        request: Vec<u8>,
        first_wait: Duration,
    ) -> Result<JadeEvent, JadeBleError> {
        self.write(&request).await?;
        let mut wait = first_wait;
        loop {
            let chunk = self.next_indication(wait).await?;
            match self.session.receive(&chunk)? {
                JadeEvent::NeedMore => {}
                JadeEvent::Send(bytes) => {
                    wait = REPLY_TIMEOUT;
                    self.write(&bytes).await?;
                }
                JadeEvent::SendForConfirmation(bytes) => {
                    wait = USER_TIMEOUT;
                    self.write(&bytes).await?;
                }
                JadeEvent::Pinserver { url, body } => {
                    let relayed = relay_pinserver(url, body).await.and_then(|response| {
                        self.session
                            .pinserver_response(&response)
                            .map_err(|refused| JadeBleError::Pinserver(refused.to_string()))
                    });
                    let reply = match relayed {
                        Ok(reply) => reply,
                        Err(error) => {
                            // The Jade takes `pin` or `cancel` and nothing else. Without
                            // `cancel` it waits on "Checking…" for as long as the link lasts.
                            let cancel = self.session.pinserver_failed()?;
                            self.write(&cancel).await?;
                            return Err(error);
                        }
                    };
                    // The Jade may still ask for a passphrase or a new PIN before it
                    // answers (`get_pin_load_keys()` in `main/process/auth_user.c`).
                    wait = first_wait;
                    self.write(&reply).await?;
                }
                done => return Ok(done),
            }
        }
    }

    async fn write(&mut self, bytes: &[u8]) -> Result<(), JadeBleError> {
        let (peripheral, characteristic) = (&self.peripheral, &self.write_characteristic);
        write_chunks(WRITE_TIMEOUT, bytes, &mut self.pairing, |chunk| {
            peripheral.write(characteristic, chunk, WriteType::WithResponse)
        })
        .await
    }

    async fn next_indication(&mut self, wait: Duration) -> Result<Vec<u8>, JadeBleError> {
        let id = self.peripheral.id();
        let deadline = tokio::time::sleep(wait);
        tokio::pin!(deadline);
        loop {
            tokio::select! {
                notification = self.notifications.next() => match notification {
                    Some(n) if n.uuid.to_string() == INDICATE_CHARACTERISTIC_UUID => {
                        return Ok(n.value);
                    }
                    Some(_) => {}
                    None => return Err(JadeBleError::Disconnected),
                },
                event = self.events.next() => match event {
                    Some(CentralEvent::DeviceDisconnected(gone)) if gone == id => {
                        return Err(JadeBleError::Disconnected);
                    }
                    Some(_) => {}
                    None => return Err(JadeBleError::Disconnected),
                },
                () = &mut deadline => return Err(JadeBleError::Timeout("waiting for the Jade")),
            }
        }
    }
}

/// Picks the Jade named `wanted` (a full name, or its end) or, without a
/// name, the only Jade in range.
fn select_jade<T>(
    seen: Vec<(String, T)>,
    wanted: Option<&str>,
) -> Result<(String, T), JadeBleError> {
    let seen_count = seen.len();
    let mut jades: Vec<_> = seen
        .into_iter()
        .filter(|(name, _)| name.starts_with(NAME_PREFIX))
        .filter(|(name, _)| wanted.is_none_or(|wanted| name.ends_with(wanted)))
        .collect();
    match jades.len() {
        0 => Err(JadeBleError::NotFound { seen: seen_count }),
        1 => Ok(jades.remove(0)),
        _ => Err(JadeBleError::MultipleFound(
            jades.into_iter().map(|(name, _)| name).collect(),
        )),
    }
}

fn not_found_message(seen: usize) -> String {
    if seen == 0 {
        return "no Bluetooth devices seen at all. Does this app have Bluetooth permission?"
            .to_owned();
    }
    format!(
        "no Jade among the {seen} devices seen. Is the Jade powered (a Jade Core has no \
         battery), and is its Bluetooth on (Options → Device → Settings → Bluetooth)?"
    )
}

/// The write and indicate characteristics of the Jade RPC service, if this
/// peripheral has them with the properties the protocol relies on.
fn jade_characteristics<'a>(
    characteristics: impl IntoIterator<Item = &'a Characteristic>,
) -> Option<(Characteristic, Characteristic)> {
    let (mut write, mut indicate) = (None, None);
    for characteristic in characteristics {
        if characteristic.service_uuid.to_string() != SERVICE_UUID {
            continue;
        }
        let uuid = characteristic.uuid.to_string();
        let properties = characteristic.properties;
        if uuid == WRITE_CHARACTERISTIC_UUID && properties.contains(CharPropFlags::WRITE) {
            write = Some(characteristic.clone());
        } else if uuid == INDICATE_CHARACTERISTIC_UUID
            && properties.intersects(CharPropFlags::INDICATE | CharPropFlags::NOTIFY)
        {
            indicate = Some(characteristic.clone());
        }
    }
    Some((write?, indicate?))
}

fn found_jades(seen: Vec<FoundJade>) -> Result<Vec<FoundJade>, JadeBleError> {
    let seen_count = seen.len();
    let jades: Vec<_> = seen
        .into_iter()
        .filter(|device| device.name.starts_with(NAME_PREFIX))
        .collect();
    if jades.is_empty() {
        return Err(JadeBleError::NotFound { seen: seen_count });
    }
    Ok(jades)
}

async fn adapter() -> Result<Adapter, JadeBleError> {
    Manager::new()
        .await?
        .adapters()
        .await?
        .into_iter()
        .next()
        .ok_or(JadeBleError::NoAdapter)
}

async fn scan_named<C: Central + 'static>(
    adapter: &C,
    scan: Duration,
) -> Result<Vec<(String, C::Peripheral)>, JadeBleError> {
    let scanning = Cleanup::scan(adapter);
    adapter.start_scan(ScanFilter::default()).await?;
    tokio::time::sleep(scan).await;
    let named = named_peripherals(adapter).await;
    adapter.stop_scan().await?;
    scanning.disarm();
    named
}

async fn named_peripherals<C: Central>(
    adapter: &C,
) -> Result<Vec<(String, C::Peripheral)>, JadeBleError> {
    let mut seen = Vec::new();
    for peripheral in adapter.peripherals().await? {
        if let Some(local_name) = peripheral.properties().await?.and_then(|p| p.local_name) {
            seen.push((local_name, peripheral));
        }
    }
    Ok(seen)
}

/// Radio work that btleplug stops neither when a future is dropped nor on an early `?`: a scan, or a
/// link still coming up. Only setup work gets a guard, so `arm` stays private to this module.
mod cleanup {
    use std::future::Future;
    use std::pin::Pin;

    use btleplug::api::{Central, Peripheral as _};
    use btleplug::platform::Peripheral;
    use tokio::runtime::Handle;

    use super::LINK_TIMEOUT;

    pub(super) struct Cleanup {
        work: Option<Pin<Box<dyn Future<Output = ()> + Send>>>,
        // Taken at arm time, inside a poll: a drop from a plain thread (a Kotlin
        // cleaner) has no runtime of its own.
        runtime: Handle,
    }

    impl Cleanup {
        pub(super) fn scan<C: Central + 'static>(adapter: &C) -> Self {
            let adapter = adapter.clone();
            Self::arm(async move {
                let _ = adapter.stop_scan().await;
            })
        }

        pub(super) fn link(peripheral: &Peripheral) -> Self {
            let peripheral = peripheral.clone();
            Self::arm(async move {
                let _ = tokio::time::timeout(LINK_TIMEOUT, peripheral.disconnect()).await;
            })
        }

        pub(super) fn disarm(mut self) {
            self.work = None;
        }

        fn arm(work: impl Future<Output = ()> + Send + 'static) -> Self {
            Self {
                work: Some(Box::pin(work)),
                runtime: Handle::current(),
            }
        }

        #[cfg(test)]
        pub(super) fn arm_for_test(work: impl Future<Output = ()> + Send + 'static) -> Self {
            Self::arm(work)
        }
    }

    impl Drop for Cleanup {
        fn drop(&mut self) {
            let Some(work) = self.work.take() else {
                return;
            };
            self.runtime.spawn(work);
        }
    }
}
use cleanup::Cleanup;

async fn connect_with_retries(peripheral: &Peripheral) -> Result<(), JadeBleError> {
    if within(LINK_TIMEOUT, "checking the link", peripheral.is_connected()).await? {
        return Ok(());
    }
    // Blockstream's own client retries too: first connects are flaky.
    let mut attempt = 1;
    loop {
        match within(LINK_TIMEOUT, "connecting", peripheral.connect()).await {
            Ok(()) => return Ok(()),
            Err(error) if attempt == CONNECT_ATTEMPTS => return Err(error),
            Err(_) => {
                attempt += 1;
                tokio::time::sleep(Duration::from_millis(500)).await;
            }
        }
    }
}

async fn post_to_pinserver(url: String, body: String) -> Result<String, JadeBleError> {
    tokio::task::spawn_blocking(move || {
        let agent: ureq::Agent = ureq::Agent::config_builder()
            .timeout_global(Some(PINSERVER_TIMEOUT))
            .build()
            .into();
        agent
            .post(&url)
            .header("Content-Type", "application/json")
            .send(&body)?
            .body_mut()
            .read_to_string()
    })
    .await
    .map_err(|join| JadeBleError::Pinserver(join.to_string()))?
    .map_err(|error| JadeBleError::Pinserver(error.to_string()))
}

/// Test-only response injection for a real Jade's pinserver event. This lets
/// the desktop hardware harness exercise Jade's cancel path without weakening
/// the HTTPS trust roots or contacting a substitute server. The production
/// relay is used unless both the Cargo feature and debug build are enabled and
/// the caller explicitly sets `JADE_TEST_PINSERVER_RESPONSE`.
async fn relay_pinserver(url: String, body: String) -> Result<String, JadeBleError> {
    #[cfg(all(feature = "test-pinserver-response", debug_assertions))]
    if let Some(response) = std::env::var_os("JADE_TEST_PINSERVER_RESPONSE") {
        eprintln!("TEST ONLY: injecting pinserver response; HTTPS relay skipped");
        return Ok(response.to_string_lossy().into_owned());
    }

    post_to_pinserver(url, body).await
}

async fn within<T>(
    limit: Duration,
    what: &'static str,
    operation: impl Future<Output = Result<T, btleplug::Error>>,
) -> Result<T, JadeBleError> {
    tokio::time::timeout(limit, operation)
        .await
        .map_err(|_| JadeBleError::Timeout(what))?
        .map_err(JadeBleError::from)
}

/// The session answers each request with its own kind of event, so this
/// only fires on a bug in the session itself.
fn unexpected(event: JadeEvent) -> JadeBleError {
    VaultCoreError::from(JadeError::Protocol(format!("unexpected {event:?}"))).into()
}

#[cfg(test)]
mod fake_btle;

#[cfg(test)]
mod tests {
    use super::*;

    fn names(found: &[&str]) -> Vec<(String, ())> {
        found.iter().map(|name| (name.to_string(), ())).collect()
    }

    #[test]
    fn picks_the_only_jade_among_other_devices() {
        let seen = names(&["Jade 1a2b3c", "AirPods", "Trezor Safe 7"]);
        assert_eq!(select_jade(seen, None).unwrap().0, "Jade 1a2b3c");
    }

    #[test]
    fn no_jade_in_range() {
        let seen = names(&["AirPods"]);
        assert!(matches!(
            select_jade(seen, None),
            Err(JadeBleError::NotFound { seen: 1 })
        ));
        let seen = names(&["Jade 1a2b3c"]);
        let wrong_name = select_jade(seen, Some("ffffff"));
        assert!(matches!(
            wrong_name,
            Err(JadeBleError::NotFound { seen: 1 })
        ));
        let nothing = select_jade(names(&[]), None).unwrap_err().to_string();
        assert!(nothing.contains("Bluetooth permission"), "{nothing}");
        assert!(!nothing.contains("System Settings"), "{nothing}");
    }

    #[test]
    fn a_lost_bond_and_an_unconfirmed_pairing_have_their_own_errors() {
        let ble = |text: &str| JadeBleError::from(btleplug::Error::RuntimeError(text.into()));
        assert!(matches!(
            ble("Peer removed pairing information"),
            JadeBleError::BondRemoved
        ));
        assert!(matches!(
            ble("Encryption is insufficient."),
            JadeBleError::PairingNotConfirmed
        ));
        // Anything else stays the raw Bluetooth error.
        assert!(matches!(ble("GATT 133"), JadeBleError::Ble(_)));
        assert!(matches!(
            JadeBleError::from(btleplug::Error::NotConnected),
            JadeBleError::Disconnected
        ));
        let advice = JadeBleError::BondRemoved.to_string();
        assert!(advice.contains("forget it"), "{advice}");
    }

    #[test]
    fn only_a_plain_disconnect_gains_the_pairing_hint() {
        assert!(matches!(
            while_connecting(JadeBleError::Disconnected),
            JadeBleError::DisconnectedWhileConnecting
        ));
        assert!(matches!(
            while_connecting(JadeBleError::Timeout("subscribing")),
            JadeBleError::Timeout("subscribing")
        ));
        assert!(matches!(
            while_connecting(JadeBleError::BondRemoved),
            JadeBleError::BondRemoved
        ));
        // A timeout becomes a hint only in the discovery step, and that is `discover`'s job.
        for stage in ["checking the link", "connecting", "discovering services"] {
            assert!(
                matches!(
                    while_connecting(JadeBleError::Timeout(stage)),
                    JadeBleError::Timeout(_)
                ),
                "{stage}"
            );
        }
        let hint = JadeBleError::DisconnectedWhileConnecting.to_string();
        assert!(hint.contains("pairing request"), "{hint}");
    }

    #[test]
    fn a_dropped_link_is_a_plain_disconnect_outside_the_connect_steps() {
        let ble = |text: &str| JadeBleError::from(btleplug::Error::RuntimeError(text.into()));
        assert!(matches!(
            ble("Device disconnected"),
            JadeBleError::Disconnected
        ));
        // Only btleplug's whole text counts.
        for near in [
            "Device disconnected unexpectedly",
            "device disconnected",
            "Disconnected",
            " Device disconnected",
        ] {
            assert!(matches!(ble(near), JadeBleError::Ble(_)), "{near:?}");
        }
        let text = JadeBleError::Disconnected.to_string();
        assert!(!text.contains("pairing"), "{text}");
    }

    #[tokio::test]
    async fn discovery_adds_the_pairing_hint_only_for_a_dropped_link_or_a_stall() {
        let limit = Duration::from_millis(30);
        let runtime = |text: &str| btleplug::Error::RuntimeError(text.into());
        // iOS, Jade rejected on the Jade: the link drops under the pending discovery.
        let dropped = discover(limit, async { Err(runtime("Device disconnected")) }).await;
        assert!(
            matches!(dropped, Err(JadeBleError::DisconnectedWhileConnecting)),
            "{dropped:?}"
        );
        // Android says the same with another error.
        let gone = discover(limit, async { Err(btleplug::Error::NotConnected) }).await;
        assert!(
            matches!(gone, Err(JadeBleError::DisconnectedWhileConnecting)),
            "{gone:?}"
        );
        // iOS, request cancelled on the iPhone: discovery never answers.
        let started = tokio::time::Instant::now();
        let stalled = discover(limit, std::future::pending::<Result<(), btleplug::Error>>()).await;
        assert!(
            matches!(stalled, Err(JadeBleError::DiscoveryTimedOut)),
            "{stalled:?}"
        );
        assert!(started.elapsed() >= limit);
        // Everything else keeps its own meaning.
        let lost = discover(limit, async {
            Err(runtime("Peer removed pairing information"))
        })
        .await;
        assert!(matches!(lost, Err(JadeBleError::BondRemoved)), "{lost:?}");
        let declined = discover(limit, async { Err(runtime("Encryption is insufficient.")) }).await;
        assert!(
            matches!(declined, Err(JadeBleError::PairingNotConfirmed)),
            "{declined:?}"
        );
        let other = discover(limit, async { Err(runtime("GATT 133")) }).await;
        assert!(matches!(other, Err(JadeBleError::Ble(_))), "{other:?}");
        assert!(discover(limit, async { Ok(()) }).await.is_ok());
        let hint = JadeBleError::DiscoveryTimedOut.to_string();
        assert!(
            hint.contains("discovering services") && hint.contains("pairing request"),
            "{hint}"
        );
    }

    const WRITE_LIMIT: Duration = Duration::from_millis(30);

    fn runtime(text: &str) -> btleplug::Error {
        btleplug::Error::RuntimeError(text.into())
    }

    /// Writes `bytes` through a radio that answers chunk by chunk as scripted, `None` meaning it
    /// never answers. Returns the outcome and how many chunks reached the radio.
    async fn write_scripted(
        pairing: &mut PairingWait,
        bytes: &[u8],
        script: Vec<Option<Result<(), btleplug::Error>>>,
    ) -> (Result<(), JadeBleError>, usize) {
        let mut script = script.into_iter();
        let mut chunks = 0;
        let outcome = write_chunks(WRITE_LIMIT, bytes, pairing, |_| {
            chunks += 1;
            let next = script.next().expect("more chunks than the script has");
            async move {
                match next {
                    Some(result) => result,
                    None => std::future::pending().await,
                }
            }
        })
        .await;
        (outcome, chunks)
    }

    async fn first_write_failing_with(error: btleplug::Error) -> JadeBleError {
        let mut pairing = PairingWait::Possible;
        let (outcome, _) = write_scripted(&mut pairing, b"request", vec![Some(Err(error))]).await;
        outcome.unwrap_err()
    }

    #[tokio::test]
    async fn a_first_write_that_loses_the_link_gets_the_pairing_hint() {
        // Android: the Jade holds the first write behind the pairing and ends the link if it is declined.
        for lost in [
            btleplug::Error::NotConnected,
            runtime("Device disconnected"),
        ] {
            let error = first_write_failing_with(lost).await;
            assert!(
                matches!(error, JadeBleError::DisconnectedWhileConnecting),
                "{error:?}"
            );
        }
    }

    #[tokio::test]
    async fn a_first_write_that_never_answers_gets_the_pairing_hint() {
        let mut pairing = PairingWait::Possible;
        let started = tokio::time::Instant::now();
        let (outcome, _) = write_scripted(&mut pairing, b"request", vec![None]).await;
        assert!(
            matches!(outcome, Err(JadeBleError::FirstWriteTimedOut)),
            "{outcome:?}"
        );
        assert!(started.elapsed() >= WRITE_LIMIT);
        let hint = JadeBleError::FirstWriteTimedOut.to_string();
        assert!(
            hint.contains("writing to the Jade")
                && hint.contains("pairing request")
                && hint.contains("on this device and on the Jade"),
            "{hint}"
        );
    }

    #[tokio::test]
    async fn other_first_write_errors_keep_their_meaning() {
        let lost = first_write_failing_with(runtime("Peer removed pairing information")).await;
        assert!(matches!(lost, JadeBleError::BondRemoved), "{lost:?}");
        let declined = first_write_failing_with(runtime("Encryption is insufficient.")).await;
        assert!(
            matches!(declined, JadeBleError::PairingNotConfirmed),
            "{declined:?}"
        );
        let other = first_write_failing_with(runtime("GATT 133")).await;
        assert!(matches!(other, JadeBleError::Ble(_)), "{other:?}");
    }

    #[tokio::test]
    async fn a_failure_after_the_first_chunk_is_a_plain_error() {
        // Two chunks: once the first is acknowledged the link is encrypted, so the pairing is over.
        let request = vec![0u8; MAX_WRITE_LEN + 1];
        let mut pairing = PairingWait::Possible;
        let script = vec![Some(Ok(())), Some(Err(btleplug::Error::NotConnected))];
        let (dropped, chunks) = write_scripted(&mut pairing, &request, script).await;
        assert!(
            matches!(dropped, Err(JadeBleError::Disconnected)),
            "{dropped:?}"
        );
        assert_eq!(chunks, 2);
        let mut pairing = PairingWait::Possible;
        let (stalled, _) = write_scripted(&mut pairing, &request, vec![Some(Ok(())), None]).await;
        assert!(
            matches!(stalled, Err(JadeBleError::Timeout("writing to the Jade"))),
            "{stalled:?}"
        );
    }

    #[tokio::test]
    async fn a_later_request_on_the_same_link_gets_plain_errors() {
        let mut pairing = PairingWait::Possible;
        let (first, _) = write_scripted(&mut pairing, b"version_info", vec![Some(Ok(()))]).await;
        assert!(first.is_ok());
        let lost = vec![Some(Err(btleplug::Error::NotConnected))];
        let (second, _) = write_scripted(&mut pairing, b"unlock", lost).await;
        assert!(
            matches!(second, Err(JadeBleError::Disconnected)),
            "{second:?}"
        );
        let (third, _) = write_scripted(&mut pairing, b"xpub", vec![None]).await;
        assert!(
            matches!(third, Err(JadeBleError::Timeout("writing to the Jade"))),
            "{third:?}"
        );
    }

    #[tokio::test]
    async fn a_link_whose_pairing_is_over_never_gets_the_hint() {
        // iOS and macOS hold the pairing in service discovery, so no write there is the one waiting.
        let mut pairing = PairingWait::Over;
        let lost = vec![Some(Err(btleplug::Error::NotConnected))];
        let (dropped, _) = write_scripted(&mut pairing, b"request", lost).await;
        assert!(
            matches!(dropped, Err(JadeBleError::Disconnected)),
            "{dropped:?}"
        );
        let (stalled, _) = write_scripted(&mut pairing, b"request", vec![None]).await;
        assert!(
            matches!(stalled, Err(JadeBleError::Timeout("writing to the Jade"))),
            "{stalled:?}"
        );
    }

    #[cfg(not(target_os = "android"))]
    #[test]
    fn a_new_link_off_android_starts_with_the_pairing_over() {
        assert_eq!(PairingWait::on_new_link(), PairingWait::Over);
    }

    #[test]
    fn several_jades_need_a_name() {
        let seen = || names(&["Jade 1a2b3c", "Jade 4d5e6f"]);
        match select_jade(seen(), None) {
            Err(JadeBleError::MultipleFound(found)) => {
                assert_eq!(found, ["Jade 1a2b3c", "Jade 4d5e6f"]);
            }
            other => panic!("expected MultipleFound, got {other:?}"),
        }
        assert_eq!(
            select_jade(seen(), Some("4d5e6f")).unwrap().0,
            "Jade 4d5e6f"
        );
        assert_eq!(
            select_jade(seen(), Some("Jade 1a2b3c")).unwrap().0,
            "Jade 1a2b3c"
        );
    }

    #[test]
    fn lists_only_jades() {
        let seen = |names: &[&str]| -> Vec<FoundJade> {
            names
                .iter()
                .map(|name| FoundJade {
                    name: name.to_string(),
                    id: format!("id of {name}"),
                })
                .collect()
        };
        let found = found_jades(seen(&["AirPods", "Jade 1a2b3c", "Trezor Safe 7"])).unwrap();
        assert_eq!(
            found,
            [FoundJade {
                name: "Jade 1a2b3c".into(),
                id: "id of Jade 1a2b3c".into()
            }]
        );
        assert!(matches!(
            found_jades(seen(&["AirPods"])),
            Err(JadeBleError::NotFound { seen: 1 })
        ));
    }

    #[tokio::test]
    async fn cleanup_runs_only_while_armed() {
        use std::sync::Arc;
        use std::sync::atomic::{AtomicUsize, Ordering::SeqCst};

        let ran = Arc::new(AtomicUsize::new(0));
        let count = || {
            let ran = ran.clone();
            async move {
                ran.fetch_add(1, SeqCst);
            }
        };
        Cleanup::arm_for_test(count()).disarm();
        let early_return = || -> Result<(), JadeBleError> {
            let _armed = Cleanup::arm_for_test(count());
            Err(JadeBleError::Disconnected)
        };
        assert!(early_return().is_err());
        let armed = Cleanup::arm_for_test(count());
        let holder = tokio::spawn(async move {
            let _armed = armed;
            std::future::pending::<()>().await
        });
        tokio::task::yield_now().await;
        holder.abort();
        tokio::time::timeout(Duration::from_secs(1), async {
            while ran.load(SeqCst) < 2 {
                tokio::task::yield_now().await;
            }
        })
        .await
        .expect("an early return and a dropped future both ran their cleanup");
        assert_eq!(ran.load(SeqCst), 2, "the disarmed cleanup never ran");
    }

    #[tokio::test]
    async fn a_dropped_scan_stops_the_scan() {
        use crate::fake_btle::FakeCentral;

        let central = FakeCentral::default();
        let scanning = tokio::spawn({
            let central = central.clone();
            async move { scan_named(&central, Duration::from_secs(3600)).await }
        });
        wait_for(|| central.calls() == ["start_scan"]).await;
        scanning.abort();
        wait_for(|| central.calls() == ["start_scan", "stop_scan"]).await;
    }

    #[tokio::test]
    async fn a_failed_listing_stops_the_scan() {
        use crate::fake_btle::{FakeCentral, FakePeripheral};

        let central = FakeCentral {
            devices: vec![FakePeripheral(Err("the radio dropped".into()))],
            ..Default::default()
        };
        assert!(scan_named(&central, Duration::ZERO).await.is_err());
        settle().await;
        assert_eq!(central.calls(), ["start_scan", "stop_scan"]);
    }

    #[tokio::test]
    async fn a_finished_scan_stops_the_scan_once() {
        use crate::fake_btle::{FakeCentral, FakePeripheral};

        let central = FakeCentral {
            devices: vec![FakePeripheral(Ok(Some(
                btleplug::api::PeripheralProperties {
                    local_name: Some("Jade 1a2b3c".into()),
                    ..Default::default()
                },
            )))],
            ..Default::default()
        };
        assert_eq!(scan_named(&central, Duration::ZERO).await.unwrap().len(), 1);
        settle().await;
        assert_eq!(central.calls(), ["start_scan", "stop_scan"]);
    }

    /// Lets a cleanup the code under test spawned run, so a stop that should not happen shows.
    async fn settle() {
        for _ in 0..8 {
            tokio::task::yield_now().await;
        }
    }

    async fn wait_for(done: impl Fn() -> bool) {
        tokio::time::timeout(Duration::from_secs(1), async {
            while !done() {
                tokio::task::yield_now().await;
            }
        })
        .await
        .expect("never happened");
    }

    #[tokio::test]
    async fn cleanup_runs_from_a_plain_thread() {
        use std::sync::Arc;
        use std::sync::atomic::{AtomicBool, Ordering::SeqCst};

        let ran = Arc::new(AtomicBool::new(false));
        let armed = Cleanup::arm_for_test({
            let ran = ran.clone();
            async move { ran.store(true, SeqCst) }
        });
        // Gobley frees objects on the caller of `close()` or a GC thread: no runtime there.
        std::thread::spawn(move || drop(armed)).join().unwrap();
        wait_for(|| ran.load(SeqCst)).await;
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
    fn finds_the_rpc_characteristics_only_with_the_right_properties() {
        let write = characteristic(WRITE_CHARACTERISTIC_UUID, CharPropFlags::WRITE);
        let indicate = characteristic(INDICATE_CHARACTERISTIC_UUID, CharPropFlags::INDICATE);
        let (w, i) = jade_characteristics([&write, &indicate]).unwrap();
        assert_eq!((w.uuid, i.uuid), (write.uuid, indicate.uuid));

        let read_only = characteristic(WRITE_CHARACTERISTIC_UUID, CharPropFlags::READ);
        assert!(jade_characteristics([&read_only, &indicate]).is_none());
        assert!(jade_characteristics([&write]).is_none());
    }
}
