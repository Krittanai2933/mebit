use std::collections::BTreeMap;
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::process;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use serde_json::json;

use super::Result;
use super::model::{Case, Family, Slot, Source};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Account {
    pub origin: String,
    pub xpub: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Identity {
    pub physical_id: String,
    pub firmware: String,
    pub fingerprint: String,
    pub accounts: BTreeMap<Family, Account>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Enrollment {
    pub slot: Slot,
    pub identity: Identity,
    pub selector: Option<String>,
    pub jade_slot_declared_empty: bool,
    pub jade_owned_descriptor: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum Registration {
    ExistingOnly,
    DeclaredEmpty,
    ReplaceOwned(String),
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum DeviceAction {
    Enroll,
    Verify {
        descriptor: String,
        index: u32,
        signer: String,
        registration: Registration,
    },
    Sign {
        psbt: String,
        signer: String,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WorkerRequest {
    pub action: DeviceAction,
    pub expected: Option<Identity>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum DeviceResult {
    Enrolled,
    Address(String),
    Signed(String),
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WorkerResponse {
    pub identity: Identity,
    pub result: DeviceResult,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Execution {
    pub timestamp: u64,
    pub slot: Slot,
    pub operation: String,
    pub command: Vec<String>,
    pub exit_status: Option<i32>,
    pub success: bool,
    pub log: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AddressProof {
    pub slot: Slot,
    pub branch: u32,
    pub index: u32,
    pub address: String,
    pub firmware: String,
    pub fingerprint: String,
    pub device_displayed: bool,
    pub verified_at: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Confirmation {
    pub block_height: u32,
    pub block_hash: String,
    pub tip_height: u32,
    pub confirmations: u32,
    pub checked_at: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Funding {
    pub outpoint: String,
    pub transaction_hex: String,
    pub amount_sats: u64,
    pub confirmation: Confirmation,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Signing {
    pub funding: Funding,
    pub unsigned_psbt: String,
    pub signed_psbt: String,
    pub completed: Vec<Slot>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Finalized {
    pub signing: Signing,
    pub transaction_hex: String,
    pub txid: String,
    pub fee_sats: u64,
    pub vsize: usize,
    pub broadcast_authorized: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum Progress {
    Prepared,
    AwaitingFunding {
        outpoint: String,
    },
    FundingConfirmed(Funding),
    Signing(Signing),
    Finalized(Finalized),
    BroadcastUnknown(Finalized),
    PendingConfirmation(Finalized),
    Pass {
        finalized: Finalized,
        confirmation: Confirmation,
    },
}

impl Progress {
    pub fn status(&self) -> &'static str {
        match self {
            Self::Prepared => "PREPARED",
            Self::AwaitingFunding { .. } => "PENDING_FUNDING",
            Self::FundingConfirmed(_) => "FUNDING_CONFIRMED",
            Self::Signing(_) => "SIGNING",
            Self::Finalized(_) => "FINALIZED",
            Self::BroadcastUnknown(_) => "BROADCAST_UNKNOWN",
            Self::PendingConfirmation(_) => "PENDING_CONFIRMATION",
            Self::Pass { .. } => "PASS",
        }
    }
    pub fn finalized(&self) -> Option<&Finalized> {
        match self {
            Self::Finalized(f)
            | Self::BroadcastUnknown(f)
            | Self::PendingConfirmation(f)
            | Self::Pass { finalized: f, .. } => Some(f),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Checkpoint {
    pub schema: u32,
    pub case: Case,
    pub enrolled: Vec<Enrollment>,
    pub receive_descriptor: String,
    pub change_descriptor: String,
    pub receive_address: String,
    pub change_address: String,
    pub destination: String,
    pub address_proofs: Vec<AddressProof>,
    pub executions: Vec<Execution>,
    pub progress: Progress,
    pub blockers: Vec<String>,
    pub created_at: u64,
    pub broadcast_authorized: bool,
    pub genesis_verified_at: u64,
    pub runner_revision: String,
}

pub fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("clock after epoch")
        .as_secs()
}

pub fn private_dir(path: &Path) -> Result<()> {
    fs::create_dir_all(path)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(path, fs::Permissions::from_mode(0o700))?;
    }
    Ok(())
}

pub fn atomic_write(path: &Path, bytes: &[u8]) -> Result<()> {
    let parent = path.parent().ok_or("record has no parent directory")?;
    private_dir(parent)?;
    static NEXT: AtomicU64 = AtomicU64::new(0);
    let nonce = SystemTime::now().duration_since(UNIX_EPOCH)?.as_nanos();
    let tmp = parent.join(format!(
        ".{}.{}.{nonce}.{}.tmp",
        path.file_name()
            .ok_or("record has no name")?
            .to_string_lossy(),
        process::id(),
        NEXT.fetch_add(1, Ordering::Relaxed)
    ));
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let mut file = options.open(&tmp)?;
    let written = (|| -> std::io::Result<()> {
        file.write_all(bytes)?;
        file.sync_all()?;
        fs::rename(&tmp, path)?;
        std::fs::File::open(parent)?.sync_all()
    })();
    if written.is_err() {
        let _ = fs::remove_file(tmp);
    }
    Ok(written?)
}

pub fn write_json(path: &Path, value: &impl Serialize) -> Result<()> {
    atomic_write(path, &serde_json::to_vec_pretty(value)?)
}

pub fn read_json<T: for<'a> Deserialize<'a>>(path: &Path) -> Result<T> {
    Ok(serde_json::from_slice(&fs::read(path)?)?)
}

pub fn log_command(store: &Store, result: &Result<()>) -> Result<()> {
    let timestamp = SystemTime::now().duration_since(UNIX_EPOCH)?.as_nanos();
    let entry = serde_json::json!({"timestamp":now(),"command":std::env::args().collect::<Vec<_>>(),"exit_status":if result.is_ok(){0}else{1},"success":result.is_ok(),"error":result.as_ref().err().map(|e|sanitized_error(&e.to_string())),"runner_version":env!("CARGO_PKG_VERSION")});
    write_json(
        &store
            .root
            .join("logs")
            .join(format!("cli-{timestamp}-{}.json", process::id())),
        &entry,
    )
}

pub fn sanitized_error(error: &str) -> String {
    if [
        "tpub", "xpub", "xprv", "tprv", "cHNid", "mnemonic", "phrase",
    ]
    .iter()
    .any(|word| error.contains(word))
    {
        "private key or PSBT validation failed; private details omitted".into()
    } else {
        error.chars().take(512).collect()
    }
}

pub struct Store {
    pub root: PathBuf,
    pub secrets: PathBuf,
}

impl Store {
    pub fn new() -> Result<Self> {
        let workspace = Path::new(env!("CARGO_MANIFEST_DIR"))
            .parent()
            .ok_or("workspace path")?;
        let store = Self {
            root: workspace.join("target/wallet-e2e"),
            secrets: workspace.join("target/wallet-e2e-secrets"),
        };
        private_dir(&store.root)?;
        private_dir(&store.root.join("private"))?;
        private_dir(&store.secrets)?;
        Ok(store)
    }

    pub fn enrollment_path(&self, slot: Slot) -> PathBuf {
        self.root
            .join("private/enrollment")
            .join(format!("{slot}.json"))
    }

    pub fn enrollments(&self) -> Result<Vec<Enrollment>> {
        let dir = self.root.join("private/enrollment");
        if !dir.exists() {
            return Ok(Vec::new());
        }
        let mut records = fs::read_dir(dir)?
            .map(|entry| entry.map(|e| e.path()))
            .collect::<std::io::Result<Vec<_>>>()?;
        records.sort();
        records
            .into_iter()
            .filter(|path| path.extension().is_some_and(|ext| ext == "json"))
            .map(|path| read_json(&path))
            .collect()
    }

    pub fn checkpoint_path(&self, id: &str) -> Result<PathBuf> {
        super::model::find_case(id)?;
        Ok(self.root.join("private/cases").join(format!("{id}.json")))
    }

    pub fn load(&self, id: &str) -> Result<Checkpoint> {
        let saved: Checkpoint = read_json(&self.checkpoint_path(id)?)?;
        if saved.schema != 1 || saved.case != super::model::find_case(id)? {
            return Err("checkpoint schema or manifest case changed".into());
        }
        Ok(saved)
    }

    pub fn save(&self, checkpoint: &Checkpoint) -> Result<()> {
        write_json(&self.checkpoint_path(&checkpoint.case.id)?, checkpoint)?;
        write_json(
            &self
                .root
                .join("results")
                .join(format!("{}.json", checkpoint.case.id)),
            &public_result(checkpoint),
        )
    }

    pub fn reserve(&self, outpoint: bitcoin::OutPoint, case: &str) -> Result<()> {
        let path = self
            .root
            .join("private/reservations")
            .join(format!("{}-{}.json", outpoint.txid, outpoint.vout));
        if path.exists() {
            let owner: String = read_json(&path)?;
            if owner != case {
                return Err(format!("outpoint is reserved by {owner}").into());
            }
            return Ok(());
        }
        write_json(&path, &case)
    }
}

pub fn public_result(checkpoint: &Checkpoint) -> serde_json::Value {
    let funding = match &checkpoint.progress {
        Progress::FundingConfirmed(f) => Some(f),
        Progress::Signing(s) => Some(&s.funding),
        state => state.finalized().map(|f| &f.signing.funding),
    };
    let finalized = checkpoint.progress.finalized();
    json!({
        "schema": 1, "case": checkpoint.case, "network": "testnet4",
        "api": "https://mempool.space/testnet4/api", "status": checkpoint.progress.status(),
        "blockers": checkpoint.blockers, "created_at": checkpoint.created_at, "updated_at": now(),
        "genesis_verified_at":checkpoint.genesis_verified_at,"genesis":"00000000da84f2bafbbc53dee25a72ae507ff4914b867c565be350b0da8bf043",
        "receive_address": checkpoint.receive_address, "change_address": checkpoint.change_address,
        "destination": checkpoint.destination, "address_verification": checkpoint.address_proofs,
        "origins": checkpoint.enrolled.iter().map(|e| json!({"slot":e.slot,"fingerprint":e.identity.fingerprint,"origin":e.identity.accounts.get(&checkpoint.case.family).map(|a|&a.origin),"firmware":e.identity.firmware})).collect::<Vec<_>>(),
        "funding": funding.map(|f|json!({"outpoint":f.outpoint,"amount_sats":f.amount_sats,"confirmation":f.confirmation})),
        "signing_subset": checkpoint.case.signing_subset,
        "designated_signatures": match &checkpoint.progress {Progress::Signing(s)=>s.completed.as_slice(), state=>state.finalized().map_or(&[] as &[Slot],|f|f.signing.completed.as_slice())},
        "fee_policy":{"sat_per_vb":super::model::FEE_RATE,"fee_cap_sats":super::model::FEE_CAP,"effective_rate_cap":super::model::RATE_CAP},
        "spend":finalized.map(|f|json!({"txid":f.txid,"fee_sats":f.fee_sats,"vsize":f.vsize,"broadcast_authorized":f.broadcast_authorized})),
        "confirmation":match &checkpoint.progress {Progress::Pass{confirmation,..}=>Some(confirmation),_=>None},
        "executions":checkpoint.executions,
        "versions":{"runner":env!("CARGO_PKG_VERSION"),"bitcoin":"0.32","miniscript":"13","baseline":"360febfd394aef4f11f67ab079c00809da50445d","revision":checkpoint.runner_revision}
    })
}

pub struct Lease {
    _file: std::fs::File,
}

impl Lease {
    pub fn acquire(store: &Store) -> Result<Self> {
        Self::at(store.root.join("coordinator.lock"))
    }

    pub fn acquire_hardware(store: &Store) -> Result<Self> {
        Self::at(store.root.join("hardware.lock"))
    }

    fn at(path: PathBuf) -> Result<Self> {
        let mut options = OpenOptions::new();
        options.read(true).write(true).create(true).truncate(false);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        let mut file = options.open(&path)?;
        file.try_lock()
            .map_err(|e| format!("another coordinator holds the evidence lease: {e}"))?;
        file.set_len(0)?;
        write!(file, "{}", process::id())?;
        file.sync_all()?;
        Ok(Self { _file: file })
    }
}

pub fn check_identity(expected: &Identity, actual: &Identity) -> Result<()> {
    if expected.physical_id != actual.physical_id
        || expected.fingerprint != actual.fingerprint
        || expected.accounts != actual.accounts
    {
        return Err(
            "connected key holder does not match enrolled physical identity, origins, and xpubs"
                .into(),
        );
    }
    Ok(())
}

/// Compare a live device with its enrollment. Jade BLE addresses can rotate,
/// so Jade continuity is established by its wallet keys and, once both
/// records use the stable advertised-name form, that name as well. Other
/// transports retain the exact physical-ID check.
pub fn check_identity_for_source(
    expected: &Identity,
    actual: &Identity,
    source: Source,
) -> Result<()> {
    if source != Source::Jade {
        return check_identity(expected, actual);
    }

    let stable_names_conflict = match (
        expected.physical_id.strip_prefix("jade-name:"),
        actual.physical_id.strip_prefix("jade-name:"),
    ) {
        (Some(expected), Some(actual)) => expected != actual,
        _ => false,
    };
    if expected.fingerprint != actual.fingerprint
        || expected.accounts != actual.accounts
        || stable_names_conflict
    {
        return Err(
            "connected key holder does not match enrolled identity, origins, and account keys"
                .into(),
        );
    }
    Ok(())
}

pub fn jade_physical_id(advertised_name: &str) -> String {
    format!("jade-name:{advertised_name}")
}

pub fn validate_worker_request(request: &WorkerRequest) -> Result<()> {
    if !matches!(request.action, DeviceAction::Enroll) && request.expected.is_none() {
        return Err("address/signing worker request has no enrolled identity".into());
    }
    if let DeviceAction::Sign { psbt, .. } = &request.action {
        let psbt: bitcoin::Psbt = psbt.parse()?;
        vault_core::hw::psbt_check::validate_signing_policy(&psbt)?;
    }
    Ok(())
}

#[cfg(test)]
pub fn test_store() -> Store {
    static NEXT: AtomicU64 = AtomicU64::new(0);
    let base = std::env::temp_dir().join(format!(
        "wallet-e2e-test-{}-{}",
        process::id(),
        NEXT.fetch_add(1, Ordering::Relaxed)
    ));
    let store = Store {
        root: base.join("evidence"),
        secrets: base.join("secrets"),
    };
    private_dir(&store.root).unwrap();
    private_dir(&store.secrets).unwrap();
    store
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_identity(physical_id: &str) -> Identity {
        Identity {
            physical_id: physical_id.into(),
            firmware: "1.0.41".into(),
            fingerprint: "73c5da0a".into(),
            accounts: Family::ALL
                .into_iter()
                .map(|family| {
                    (
                        family,
                        Account {
                            origin: format!("m/{}", family.path()),
                            xpub: format!("xpub-{}", family.name()),
                        },
                    )
                })
                .collect(),
        }
    }

    #[test]
    fn jade_identity_survives_ble_address_rotation_when_keys_match() {
        let enrolled = sample_identity("hci0/dev_46_73_DC_CA_65_9C");
        let connected = sample_identity(&jade_physical_id("Jade BE0184"));

        assert!(check_identity_for_source(&enrolled, &connected, Source::Jade).is_ok());
    }

    #[test]
    fn jade_identity_rejects_changed_fingerprint_origins_or_xpubs() {
        let enrolled = sample_identity("hci0/dev_46_73_DC_CA_65_9C");

        let mut wrong_fingerprint = sample_identity(&jade_physical_id("Jade BE0184"));
        wrong_fingerprint.fingerprint = "deadbeef".into();
        assert!(check_identity_for_source(&enrolled, &wrong_fingerprint, Source::Jade).is_err());

        let mut wrong_origin = sample_identity(&jade_physical_id("Jade BE0184"));
        wrong_origin
            .accounts
            .get_mut(&Family::Bip84)
            .unwrap()
            .origin = "m/84'/1'/1'".into();
        assert!(check_identity_for_source(&enrolled, &wrong_origin, Source::Jade).is_err());

        let mut wrong_xpub = sample_identity(&jade_physical_id("Jade BE0184"));
        wrong_xpub.accounts.get_mut(&Family::Bip84).unwrap().xpub = "xpub-other".into();
        assert!(check_identity_for_source(&enrolled, &wrong_xpub, Source::Jade).is_err());
    }

    #[test]
    fn jade_stable_name_and_non_jade_physical_ids_remain_strict() {
        let jade_a = sample_identity(&jade_physical_id("Jade BE0184"));
        let jade_b = sample_identity(&jade_physical_id("Jade OTHER"));
        assert!(check_identity_for_source(&jade_a, &jade_b, Source::Jade).is_err());

        let trezor_a = sample_identity("trezor-serial-a");
        let trezor_b = sample_identity("trezor-serial-b");
        assert!(check_identity_for_source(&trezor_a, &trezor_b, Source::Trezor).is_err());
    }

    #[test]
    fn exclusive_leases_and_outpoint_reservations_survive_reentry() {
        let store = test_store();
        let lease = Lease::acquire(&store).unwrap();
        assert!(Lease::acquire(&store).is_err());
        let hardware = Lease::acquire_hardware(&store).unwrap();
        assert!(Lease::acquire_hardware(&store).is_err());
        let outpoint = bitcoin::OutPoint::null();
        store.reserve(outpoint, "case-a").unwrap();
        store.reserve(outpoint, "case-a").unwrap();
        assert!(store.reserve(outpoint, "case-b").is_err());
        drop(lease);
        let resumed = Lease::acquire(&store).unwrap();
        assert!(Lease::acquire_hardware(&store).is_err());
        drop(hardware);
        let reopened = Lease::acquire_hardware(&store).unwrap();
        drop(reopened);
        drop(resumed);
        std::fs::remove_dir_all(store.root.parent().unwrap()).unwrap();
    }

    #[test]
    fn atomic_private_records_ignore_an_abandoned_temporary_file() {
        let store = test_store();
        let path = store.root.join("private/record.json");
        write_json(&path, &vec![1, 2]).unwrap();
        let parent = path.parent().unwrap();
        std::fs::write(parent.join(".record.json.abandoned.tmp"), b"partial").unwrap();
        write_json(&path, &vec![3, 4]).unwrap();
        assert_eq!(read_json::<Vec<u8>>(&path).unwrap(), vec![3, 4]);
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(
                std::fs::metadata(&path).unwrap().permissions().mode() & 0o777,
                0o600
            );
            assert_eq!(
                std::fs::metadata(parent).unwrap().permissions().mode() & 0o777,
                0o700
            );
        }
        assert!(store.checkpoint_path("../escape").is_err());
        std::fs::remove_dir_all(store.root.parent().unwrap()).unwrap();
    }
}
