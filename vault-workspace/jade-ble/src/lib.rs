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
use bitcoin::{Network, Psbt};
use btleplug::api::{
    Central, CentralEvent, CharPropFlags, Characteristic, Manager as _, Peripheral as _,
    ScanFilter, ValueNotification, WriteType,
};
use btleplug::platform::{Manager, Peripheral};
use futures_util::{Stream, StreamExt};
use vault_core::error::VaultCoreError;
use vault_core::hw::jade::{JadeError, JadeEvent, JadeSession, VersionInfo};

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

    #[error("Bluetooth: {0}")]
    Ble(#[from] btleplug::Error),

    #[error("timed out {0}")]
    Timeout(&'static str),

    #[error("the Jade disconnected")]
    Disconnected,

    #[error("pinserver relay failed: {0}")]
    Pinserver(String),

    #[error(transparent)]
    Jade(#[from] VaultCoreError),
}

/// One live BLE connection to one Jade, with its protocol session.
///
/// A device error (declined, wrong PIN, locked) leaves the connection usable.
/// A call that stops midway (a timeout, a dropped link, its future dropped)
/// leaves the Jade out of step with the host: reconnect before the next call.
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
}

impl JadeBle {
    /// Scans for `scan`, picks the Jade whose name ends with `name` (or the
    /// only Jade in range), connects, and subscribes to its replies.
    pub async fn connect(
        network: Network,
        name: Option<&str>,
        scan: Duration,
    ) -> Result<Self, JadeBleError> {
        let manager = Manager::new().await?;
        let adapter = manager
            .adapters()
            .await?
            .into_iter()
            .next()
            .ok_or(JadeBleError::NoAdapter)?;
        let events = adapter.events().await?;

        adapter.start_scan(ScanFilter::default()).await?;
        tokio::time::sleep(scan).await;
        let mut seen = Vec::new();
        for peripheral in adapter.peripherals().await? {
            if let Some(local_name) = peripheral.properties().await?.and_then(|p| p.local_name) {
                seen.push((local_name, peripheral));
            }
        }
        adapter.stop_scan().await?;
        let (name, peripheral) = select_jade(seen, name)?;

        connect_with_retries(&peripheral).await?;
        within(
            LINK_TIMEOUT,
            "discovering services",
            peripheral.discover_services(),
        )
        .await?;
        let (write_characteristic, indicate_characteristic) =
            jade_characteristics(&peripheral.characteristics())
                .ok_or_else(|| JadeBleError::NotAJade(name.clone()))?;
        let notifications =
            within(WRITE_TIMEOUT, "subscribing", peripheral.notifications()).await?;
        within(
            WRITE_TIMEOUT,
            "subscribing",
            peripheral.subscribe(&indicate_characteristic),
        )
        .await?;

        Ok(Self {
            peripheral,
            name,
            network,
            write_characteristic,
            notifications,
            events,
            session: JadeSession::new(network),
        })
    }

    /// The Jade's advertised name, e.g. `Jade 1a2b3c`.
    pub fn name(&self) -> &str {
        &self.name
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
            // A no-op after a device error, which leaves the session idle. Any
            // other failure stopped the exchange midway.
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
                JadeEvent::Pinserver { url, body } => {
                    let response = match post_to_pinserver(url, body).await {
                        Ok(response) => response,
                        Err(error) => {
                            // Otherwise the Jade waits on "Checking…" for as long as the link lasts.
                            let cancel = self.session.pinserver_failed()?;
                            self.write(&cancel).await?;
                            return Err(error);
                        }
                    };
                    let reply = self.session.pinserver_response(&response)?;
                    // The Jade may still ask for a passphrase or a new PIN before it
                    // answers (`get_pin_load_keys()` in `main/process/auth_user.c`).
                    wait = first_wait;
                    self.write(&reply).await?;
                }
                done => return Ok(done),
            }
        }
    }

    /// Writes chunks back to back: the Jade throws a partial request away if
    /// the next chunk is more than 2 s late.
    async fn write(&mut self, bytes: &[u8]) -> Result<(), JadeBleError> {
        for chunk in bytes.chunks(MAX_WRITE_LEN) {
            let write =
                self.peripheral
                    .write(&self.write_characteristic, chunk, WriteType::WithResponse);
            within(WRITE_TIMEOUT, "writing to the Jade", write).await?;
        }
        Ok(())
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
        return "no Bluetooth devices seen at all. Does this app have Bluetooth permission \
                (System Settings → Privacy & Security → Bluetooth)?"
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
