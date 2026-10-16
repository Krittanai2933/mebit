use std::process::{Command as Process, Stdio};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use bitcoin::consensus::deserialize;
use bitcoin::consensus::encode::serialize_hex;
use bitcoin::hex::FromHex;
use bitcoin::{Network, OutPoint, Psbt, Transaction};
use vault_core::descriptor::derive_address_at;
use vault_core::hw::psbt_check::verify_device_signatures;

use crate::e2e::chain::{Chain, Esplora, Outspend, Reconciled, reconcile};
use crate::e2e::model::{Command, Family, Slot, Source, find_case};
use crate::e2e::records::{
    AddressProof, Checkpoint, DeviceAction, DeviceResult, Enrollment, Execution, Finalized,
    Funding, Lease, MAX_PUBLIC_ENROLLMENT_BYTES, MAX_REMOTE_FILE_BYTES, Progress, PublicEnrollment,
    Registration, RemoteRequest, RemoteResponse, Signing, Store, WorkerRequest, WorkerResponse,
    check_identity, check_identity_for_source, now, read_json, read_json_limited,
    validate_remote_request, validate_remote_response, write_json,
};
use crate::e2e::{Result, wallet};

pub struct Settings {
    pub trezor_selector: Option<String>,
    pub jade_selector: Option<String>,
    pub scan_secs: u64,
    pub passphrase_on_device: bool,
}

struct Workers<'a> {
    store: &'a Store,
    settings: &'a Settings,
}

impl Workers<'_> {
    fn call(
        &self,
        enrollment: &Enrollment,
        action: DeviceAction,
        enrolling: bool,
    ) -> Result<(WorkerResponse, Execution)> {
        self.call_with_trace(enrollment, action, enrolling, false)
    }

    fn call_with_trace(
        &self,
        enrollment: &Enrollment,
        action: DeviceAction,
        enrolling: bool,
        trace_signing: bool,
    ) -> Result<(WorkerResponse, Execution)> {
        if trace_signing
            && (enrolling
                || enrollment.slot.source != Source::Trezor
                || !matches!(action, DeviceAction::Sign { .. }))
        {
            return Err("private trace requires a Trezor diagnostic signing worker".into());
        }
        let operation = match &action {
            DeviceAction::Enroll => "enroll",
            DeviceAction::Verify { .. } => "verify_address",
            DeviceAction::Sign { .. } => "sign_psbt",
        };
        let timestamp = SystemTime::now().duration_since(UNIX_EPOCH)?.as_nanos();
        let stem = format!(
            "{}-{operation}-{timestamp}-{}",
            enrollment.slot,
            std::process::id()
        );
        let directory = self.store.root.join("private/workers");
        let request_path = directory.join(format!("{stem}-request.json"));
        let response_path = directory.join(format!("{stem}-response.json"));
        if response_path.exists() {
            std::fs::remove_file(&response_path)?;
        }
        write_json(
            &request_path,
            &WorkerRequest {
                action,
                expected: (!enrolling).then(|| enrollment.identity.clone()),
            },
        )?;
        let current = std::env::current_exe()?;
        let executable = match enrollment.slot.source {
            Source::Trezor => current,
            Source::Jade => {
                current.with_file_name(format!("jade-hw-test{}", std::env::consts::EXE_SUFFIX))
            }
            Source::Hot => return Err("hardware worker requested for software signer".into()),
        };
        let mut arguments = vec![
            "--network".to_owned(),
            "testnet4".to_owned(),
            "--scan-secs".to_owned(),
            self.settings.scan_secs.to_string(),
        ];
        if let Some(selector) = &enrollment.selector {
            arguments.extend([
                if enrollment.slot.source == Source::Jade {
                    "--name"
                } else {
                    "--device"
                }
                .into(),
                selector.clone(),
            ]);
        }
        if enrollment.slot.source == Source::Trezor {
            arguments.extend([
                "--credential-file".into(),
                self.store
                    .secrets
                    .join(format!("{}.credential", enrollment.slot))
                    .to_string_lossy()
                    .into_owned(),
            ]);
            if self.settings.passphrase_on_device {
                arguments.push("--passphrase-on-device".into());
            }
        }
        if trace_signing {
            arguments.push("--signing-trace-worker".into());
        }
        arguments.extend([
            "e2e".into(),
            "device".into(),
            "--request".into(),
            request_path.to_string_lossy().into_owned(),
            "--response".into(),
            response_path.to_string_lossy().into_owned(),
        ]);
        let mut command = vec![executable.to_string_lossy().into_owned()];
        command.extend(arguments.clone());
        let status = Process::new(&executable)
            .args(&arguments)
            .stdin(Stdio::inherit())
            .stdout(Stdio::null())
            .stderr(Stdio::inherit())
            .status();
        let execution = Execution {
            timestamp: now(),
            slot: enrollment.slot,
            operation: operation.into(),
            command,
            exit_status: status.as_ref().ok().and_then(|s| s.code()),
            success: status.as_ref().is_ok_and(|s| s.success()),
            log: format!(
                "{} {operation} {}",
                enrollment.slot,
                if status.as_ref().is_ok_and(|s| s.success()) {
                    "completed"
                } else {
                    "failed; private payloads omitted"
                }
            ),
        };
        write_json(
            &self.store.root.join("logs").join(format!("{stem}.json")),
            &execution,
        )?;
        if !execution.success {
            return Err(format!(
                "{} BLE {operation} worker failed with exit {:?}; see its terminal message and {}",
                enrollment.slot,
                execution.exit_status,
                self.store.root.join("logs").display()
            )
            .into());
        }
        let response: WorkerResponse = read_json(&response_path)?;
        if !enrolling {
            check_identity_for_source(
                &enrollment.identity,
                &response.identity,
                enrollment.slot.source,
            )?;
        }
        Ok((response, execution))
    }
}

fn placeholder(slot: Slot, selector: Option<String>) -> Enrollment {
    Enrollment {
        slot,
        selector,
        identity: crate::e2e::records::Identity {
            physical_id: String::new(),
            firmware: String::new(),
            fingerprint: String::new(),
            accounts: Default::default(),
        },
        jade_slot_declared_empty: false,
        jade_owned_descriptor: None,
    }
}

pub async fn run(command: Command, store: &Store, settings: Settings) -> Result<()> {
    let _lease = Lease::acquire(store)?;
    let chain = Esplora::default();
    let workers = Workers {
        store,
        settings: &settings,
    };
    let case_id = match &command {
        Command::Prepare { case, .. }
        | Command::ImportRemote { case, .. }
        | Command::Run { case, .. }
        | Command::Resume { case, .. } => Some(case.clone()),
        _ => None,
    };
    let outcome = async {
        match command {
            Command::Enroll {
                slot,
                jade_slot_empty,
            } => enroll(store, &workers, &chain, slot, jade_slot_empty),
            Command::ExportEnrollment { slot, out } => export_enrollment(store, slot, &out),
            Command::ImportEnrollment { slot, file } => import_enrollment(store, slot, &file),
            Command::Prepare { case, remote_slot } => {
                prepare(store, &workers, &chain, &case, remote_slot)
            }
            Command::ImportRemote { case, response } => {
                import_remote(store, &workers, &chain, &case, &response).await
            }
            Command::Run {
                case,
                funding_outpoint,
                broadcast,
            } => {
                let mut checkpoint = store.load(&case)?;
                checkpoint.broadcast_authorized |= broadcast;
                execute(
                    store,
                    &workers,
                    &chain,
                    &mut checkpoint,
                    Some(funding_outpoint),
                )
                .await
            }
            Command::Resume { case, broadcast } => {
                let mut checkpoint = store.load(&case)?;
                checkpoint.broadcast_authorized |= broadcast;
                execute(store, &workers, &chain, &mut checkpoint, None).await
            }
            _ => Err("offline and worker commands must dispatch before coordinator startup".into()),
        }
    }
    .await;
    if let Err(error) = &outcome
        && let Some(case_id) = case_id
    {
        let reason = crate::e2e::records::sanitized_error(&error.to_string());
        if let Ok(mut checkpoint) = store.load(&case_id) {
            checkpoint.blockers = vec![reason];
            store.save(&checkpoint)?;
        } else if let Ok(case) = find_case(&case_id) {
            write_json(
                &store.root.join("results").join(format!("{case_id}.json")),
                &serde_json::json!({"case":case,"status":"BLOCKED","blockers":[reason],"updated_at":now()}),
            )?;
        }
    }
    outcome
}

pub fn diagnose_sign(
    case_id: &str,
    store: &Store,
    settings: Settings,
    trace_signing: bool,
) -> Result<()> {
    let _lease = Lease::acquire(store)?;
    let mut checkpoint = store.load(case_id)?;
    validate_checkpoint(store, &mut checkpoint)?;
    let Progress::Signing(signing) = &checkpoint.progress else {
        return Err("signature diagnosis requires a saved SIGNING checkpoint".into());
    };
    wallet::validate_signing(&checkpoint, signing)?;
    let (enrollment, action) = diagnostic_sign_action(&checkpoint)?;
    let before: Psbt = signing.signed_psbt.parse()?;
    let workers = Workers {
        store,
        settings: &settings,
    };
    let (response, _) = workers.call_with_trace(&enrollment, action, false, trace_signing)?;
    let DeviceResult::Signed(signed) = response.result else {
        return Err("signature diagnostic worker returned a different operation".into());
    };
    verify_device_signatures(
        &before,
        signed.parse()?,
        enrollment.identity.fingerprint.parse()?,
    )?;
    println!("Signature verified. The checkpoint was not changed or broadcast.");
    Ok(())
}

fn diagnostic_sign_action(checkpoint: &Checkpoint) -> Result<(Enrollment, DeviceAction)> {
    let Progress::Signing(signing) = &checkpoint.progress else {
        return Err("signature diagnosis requires a saved SIGNING checkpoint".into());
    };
    let slot = *checkpoint
        .case
        .signing_subset
        .get(signing.completed.len())
        .ok_or("the saved signing case has no incomplete signer")?;
    if slot.source != Source::Trezor {
        return Err(format!("the next incomplete signer {slot} is not a Trezor").into());
    }
    let enrollment = checkpoint
        .enrolled
        .iter()
        .find(|enrollment| enrollment.slot == slot)
        .cloned()
        .ok_or("the next Trezor signer is missing from the checkpoint")?;
    let action = DeviceAction::Sign {
        psbt: signing.signed_psbt.clone(),
        signer: enrollment.identity.fingerprint.clone(),
    };
    Ok((enrollment, action))
}

fn enroll(
    store: &Store,
    workers: &Workers<'_>,
    chain: &impl Chain,
    slot: Slot,
    declared_empty: bool,
) -> Result<()> {
    if declared_empty && slot.source != Source::Jade {
        return Err("--jade-slot-empty applies only to a Jade slot".into());
    }
    let selector = match slot.source {
        Source::Jade => workers.settings.jade_selector.clone(),
        Source::Trezor => workers.settings.trezor_selector.clone(),
        Source::Hot => None,
    };
    let mut enrollment = placeholder(slot, selector);
    let existing = store.enrollments()?;
    let prior = existing.iter().find(|e| e.slot == slot);
    if let Some(prior) = prior {
        enrollment = prior.clone();
    }
    if slot.source == Source::Hot {
        enrollment.identity = wallet::mnemonic_identity(&wallet::hot_mnemonic(store, slot, true)?)?;
    } else {
        chain.verify_network()?;
        let (response, _) = workers.call(&enrollment, DeviceAction::Enroll, prior.is_none())?;
        if !matches!(response.result, DeviceResult::Enrolled) {
            return Err("enrollment worker returned a different operation".into());
        }
        enrollment.identity = response.identity;
    }
    if let Some(prior) = prior {
        check_identity_for_source(&prior.identity, &enrollment.identity, slot.source)?;
    }
    if declared_empty {
        enrollment.jade_slot_declared_empty = true;
        enrollment.jade_owned_descriptor = None;
    }
    let mut all = existing
        .into_iter()
        .filter(|e| e.slot != slot)
        .collect::<Vec<_>>();
    all.push(enrollment.clone());
    wallet::independent_holders(&all)?;
    write_json(&store.enrollment_path(slot), &enrollment)?;
    println!(
        "Enrolled {slot}, fingerprint {}, with five origin-bound test-family accounts.",
        enrollment.identity.fingerprint
    );
    if declared_empty {
        println!(
            "Recorded the operator declaration that mebit_e2e was manually verified empty. Jade RPC cannot prove this."
        );
    }
    Ok(())
}

fn export_enrollment(store: &Store, slot: Slot, out: &std::path::Path) -> Result<()> {
    if slot.source != Source::Trezor {
        return Err("public enrollment export is for a Trezor slot".into());
    }
    let enrollment: Enrollment = read_json(&store.enrollment_path(slot))?;
    wallet::independent_holders(std::slice::from_ref(&enrollment))?;
    write_json(
        out,
        &PublicEnrollment {
            schema: 1,
            slot,
            identity: enrollment.identity,
        },
    )?;
    println!("Exported public identity for {slot} to {}.", out.display());
    println!(
        "The file contains account xpubs and device identity only; it excludes pairing credentials."
    );
    Ok(())
}

fn import_enrollment(store: &Store, slot: Slot, file: &std::path::Path) -> Result<()> {
    if slot.source != Source::Trezor {
        return Err("public enrollment import is for a Trezor slot".into());
    }
    let profile: PublicEnrollment = read_json_limited(file, MAX_PUBLIC_ENROLLMENT_BYTES)?;
    if profile.schema != 1 || profile.slot != slot {
        return Err("public enrollment schema or slot does not match".into());
    }
    let imported = Enrollment {
        slot,
        identity: profile.identity,
        selector: None,
        jade_slot_declared_empty: false,
        jade_owned_descriptor: None,
    };
    wallet::independent_holders(std::slice::from_ref(&imported))?;
    let mut all = store.enrollments()?;
    if let Some(existing) = all.iter().find(|enrollment| enrollment.slot == slot) {
        check_identity(&existing.identity, &imported.identity).map_err(|_| {
            format!(
                "{slot} already has a different identity; keep its existing enrollment and use an unused Trezor slot"
            )
        })?;
        println!("{slot} already has this public identity.");
        return Ok(());
    }
    all.push(imported.clone());
    wallet::independent_holders(&all)?;
    write_json(&store.enrollment_path(slot), &imported)?;
    println!(
        "Imported public Trezor identity for {slot}, fingerprint {}.",
        imported.identity.fingerprint
    );
    Ok(())
}

fn enrolled_case(store: &Store, case: &crate::e2e::model::Case) -> Result<Vec<Enrollment>> {
    let all = store.enrollments()?;
    let selected = case
        .holders
        .iter()
        .map(|slot| {
            all.iter()
                .find(|e| e.slot == *slot)
                .cloned()
                .ok_or_else(|| {
                    format!("missing enrolled holder {slot}; run e2e enroll --slot {slot}")
                })
        })
        .collect::<std::result::Result<Vec<_>, _>>()?;
    wallet::independent_holders(&selected)?;
    Ok(selected)
}

fn revision() -> String {
    Process::new("git")
        .args(["rev-parse", "HEAD"])
        .current_dir(env!("CARGO_MANIFEST_DIR"))
        .output()
        .ok()
        .filter(|o| o.status.success())
        .and_then(|o| String::from_utf8(o.stdout).ok())
        .map(|s| s.trim().to_owned())
        .filter(|s| s.len() == 40 && s.bytes().all(|b| b.is_ascii_hexdigit()))
        .unwrap_or_else(|| "unknown".into())
}

fn prepare(
    store: &Store,
    workers: &Workers<'_>,
    chain: &impl Chain,
    id: &str,
    remote_slot: Option<Slot>,
) -> Result<()> {
    let case = find_case(id)?;
    if let Some(slot) = remote_slot {
        validate_remote_slot(&case, slot)?;
    }
    let enrolled = enrolled_case(store, &case)?;
    wallet::check_derivation(&case, &enrolled)?;
    chain.verify_network()?;
    let receive = wallet::descriptor(&case, &enrolled, 0)?;
    let change = wallet::descriptor(&case, &enrolled, 1)?;
    let receive_address =
        derive_address_at(&receive, case.receive.index, Network::Testnet4)?.to_string();
    let change_address =
        derive_address_at(&change, case.change.index, Network::Testnet4)?.to_string();
    let destination = wallet::destination(store, true)?.to_string();
    let mut checkpoint = if store.checkpoint_path(id)?.exists() {
        let mut saved = store.load(id)?;
        if !matches!(saved.progress, Progress::Prepared) {
            return Err("case has already reserved funding; resume its saved checkpoint".into());
        }
        if let Some(requested) = remote_slot {
            if saved.remote_slot.is_some_and(|saved| saved != requested) {
                return Err("prepared case already uses a different remote signer".into());
            }
            saved.remote_slot = Some(requested);
        }
        for old in &saved.enrolled {
            let current = enrolled
                .iter()
                .find(|enrollment| enrollment.slot == old.slot)
                .ok_or("enrolled holder missing")?;
            check_identity_for_source(&old.identity, &current.identity, old.slot.source)?;
        }
        // Refresh legacy BLE peripheral IDs from the current enrollment while
        // preserving the prepared descriptors, destination, and address proofs.
        saved.enrolled = enrolled.clone();
        saved
    } else {
        Checkpoint {
            schema: 1,
            case: case.clone(),
            enrolled: enrolled.clone(),
            receive_descriptor: receive.to_string(),
            change_descriptor: change.to_string(),
            receive_address: receive_address.clone(),
            change_address: change_address.clone(),
            destination: destination.clone(),
            address_proofs: Vec::new(),
            executions: Vec::new(),
            progress: Progress::Prepared,
            blockers: vec!["address verification is incomplete".into()],
            created_at: now(),
            broadcast_authorized: false,
            genesis_verified_at: now(),
            runner_revision: revision(),
            remote_slot,
            pending_remote: None,
        }
    };
    if checkpoint.receive_address != receive_address
        || checkpoint.change_address != change_address
        || checkpoint.destination != destination
        || checkpoint.receive_descriptor != receive.to_string()
        || checkpoint.change_descriptor != change.to_string()
    {
        return Err(
            "prepared descriptors, enrolled accounts, or test-owned destination changed".into(),
        );
    }
    checkpoint.genesis_verified_at = now();
    store.save(&checkpoint)?;
    for slot in &case.holders {
        for (branch, descriptor, index, address) in [
            (0, &receive, case.receive.index, &receive_address),
            (1, &change, case.change.index, &change_address),
        ] {
            if checkpoint.address_proofs.iter().any(|p| {
                p.slot == *slot && p.branch == branch && p.index == index && p.address == *address
            }) {
                continue;
            }
            let mut enrollment: Enrollment = read_json(&store.enrollment_path(*slot))?;
            let displayed = slot.source != Source::Hot;
            if displayed {
                let registration = if case.family == Family::P2wsh && slot.source == Source::Jade {
                    if let Some(previous) = &enrollment.jade_owned_descriptor {
                        Registration::ReplaceOwned(previous.clone())
                    } else if enrollment.jade_slot_declared_empty {
                        Registration::DeclaredEmpty
                    } else {
                        return Err("Jade multisig needs --jade-slot-empty after manual empty-slot verification; RPC cannot prove hidden registrations absent".into());
                    }
                } else {
                    Registration::ExistingOnly
                };
                let action = DeviceAction::Verify {
                    descriptor: descriptor.to_string(),
                    index,
                    signer: enrollment.identity.fingerprint.clone(),
                    registration,
                };
                let Some((response, execution)) =
                    call_or_queue_remote(store, workers, &mut checkpoint, &enrollment, action)?
                else {
                    return Ok(());
                };
                checkpoint.executions.push(execution);
                let DeviceResult::Address(actual) = response.result else {
                    return Err("address worker returned a different operation".into());
                };
                if actual != *address {
                    return Err("hardware address differs from prepared descriptor".into());
                }
                if slot.source == Source::Jade {
                    adopt_jade_identity(
                        store,
                        &mut checkpoint,
                        &mut enrollment,
                        &response.identity,
                    )?;
                }
                if case.family == Family::P2wsh && slot.source == Source::Jade {
                    enrollment.jade_owned_descriptor = Some(descriptor.to_string());
                    enrollment.jade_slot_declared_empty = false;
                    write_json(&store.enrollment_path(*slot), &enrollment)?;
                }
            } else {
                check_identity(
                    &enrollment.identity,
                    &wallet::mnemonic_identity(&wallet::hot_mnemonic(store, *slot, false)?)?,
                )?;
                if wallet::independent_script(&case, &enrolled, branch, index)?
                    != derive_address_at(descriptor, index, Network::Testnet4)?.script_pubkey()
                {
                    return Err("software address independent derivation failed".into());
                }
            }
            checkpoint.address_proofs.push(AddressProof {
                slot: *slot,
                branch,
                index,
                address: address.clone(),
                firmware: enrollment.identity.firmware.clone(),
                fingerprint: enrollment.identity.fingerprint.clone(),
                device_displayed: displayed,
                verified_at: now(),
            });
            store.save(&checkpoint)?;
        }
    }
    checkpoint.blockers.clear();
    store.save(&checkpoint)?;
    println!(
        "Prepared {}. Fund exactly {} sats to {} on testnet4 and wait for one confirmation.",
        case.id,
        case.funding_target_sats(),
        receive_address
    );
    println!(
        "Change {}. Payment 5000 sats to {}. Fee 2 sat/vB, capped at 5000 sats and 10 sat/vB.",
        change_address, destination
    );
    Ok(())
}

fn validate_remote_slot(case: &crate::e2e::model::Case, slot: Slot) -> Result<()> {
    if slot.source != Source::Trezor || !case.holders.contains(&slot) {
        return Err("remote slot must name an enrolled Trezor holder in this case".into());
    }
    Ok(())
}

fn call_or_queue_remote(
    store: &Store,
    workers: &Workers<'_>,
    checkpoint: &mut Checkpoint,
    enrollment: &Enrollment,
    action: DeviceAction,
) -> Result<Option<(WorkerResponse, Execution)>> {
    if checkpoint.remote_slot == Some(enrollment.slot) {
        queue_remote_action(store, checkpoint, enrollment, action)?;
        Ok(None)
    } else {
        workers.call(enrollment, action, false).map(Some)
    }
}

fn queue_remote_action(
    store: &Store,
    checkpoint: &mut Checkpoint,
    enrollment: &Enrollment,
    action: DeviceAction,
) -> Result<()> {
    let request = if let Some(pending) = &checkpoint.pending_remote {
        if pending.slot != enrollment.slot
            || pending.action != action
            || pending.expected != enrollment.identity
        {
            return Err("another remote action is pending for this case".into());
        }
        pending.clone()
    } else {
        let nonce = SystemTime::now().duration_since(UNIX_EPOCH)?.as_nanos();
        RemoteRequest {
            schema: 1,
            request_id: format!("{nonce}-{}", std::process::id()),
            case_id: checkpoint.case.id.clone(),
            slot: enrollment.slot,
            action,
            expected: enrollment.identity.clone(),
        }
    };
    validate_remote_request(&request)?;
    let path = store.remote_request_path(&checkpoint.case.id)?;
    write_json(&path, &request)?;
    checkpoint.pending_remote = Some(request.clone());
    let operation = match &request.action {
        DeviceAction::Verify { .. } => "address verification",
        DeviceAction::Sign { .. } => "PSBT signing",
        DeviceAction::Enroll => return Err("remote enrollment is not supported".into()),
    };
    checkpoint.blockers = vec![format!("waiting for remote {} {operation}", request.slot)];
    store.save(checkpoint)?;
    println!("Remote {operation} required from {}.", request.slot);
    println!(
        "Send this private request file to the Trezor holder: {}",
        path.display()
    );
    println!("On their machine, run e2e remote-worker with this request and a response file.");
    println!(
        "Then import the response with e2e import-remote --case {} --response <file>.",
        checkpoint.case.id
    );
    Ok(())
}

async fn import_remote(
    store: &Store,
    workers: &Workers<'_>,
    chain: &impl Chain,
    case_id: &str,
    response_path: &std::path::Path,
) -> Result<()> {
    let mut checkpoint = store.load(case_id)?;
    let request = checkpoint
        .pending_remote
        .clone()
        .ok_or("case has no pending remote request")?;
    if request.case_id != case_id || checkpoint.remote_slot != Some(request.slot) {
        return Err("pending remote request does not belong to this case setup".into());
    }
    validate_remote_request(&request)?;
    let response: RemoteResponse = read_json_limited(response_path, MAX_REMOTE_FILE_BYTES)?;
    validate_remote_response(&request, &response)?;
    let enrollment = checkpoint
        .enrolled
        .iter()
        .find(|enrollment| enrollment.slot == request.slot)
        .ok_or("remote signer is missing from the saved case")?;
    check_identity(&enrollment.identity, &request.expected)?;
    let archive_path = store.remote_response_archive_path(case_id, &request.request_id)?;
    write_json(&archive_path, &response)?;

    match (&request.action, &response.response.result) {
        (
            DeviceAction::Verify {
                descriptor, index, ..
            },
            DeviceResult::Address(actual),
        ) => {
            let (branch, expected_address) = if descriptor == &checkpoint.receive_descriptor
                && *index == checkpoint.case.receive.index
            {
                (0, checkpoint.receive_address.clone())
            } else if descriptor == &checkpoint.change_descriptor
                && *index == checkpoint.case.change.index
            {
                (1, checkpoint.change_address.clone())
            } else {
                return Err(
                    "remote address request differs from the prepared receive/change address"
                        .into(),
                );
            };
            if actual != &expected_address {
                return Err("Trezor-displayed address differs from the prepared address".into());
            }
            if checkpoint.address_proofs.iter().any(|proof| {
                proof.slot == request.slot && proof.branch == branch && proof.index == *index
            }) {
                return Err("remote address proof was already recorded".into());
            }
            checkpoint.address_proofs.push(AddressProof {
                slot: request.slot,
                branch,
                index: *index,
                address: actual.clone(),
                firmware: response.response.identity.firmware.clone(),
                fingerprint: response.response.identity.fingerprint.clone(),
                device_displayed: true,
                verified_at: now(),
            });
            checkpoint.executions.push(remote_execution(
                request.slot,
                "verify_address",
                &request.request_id,
            ));
            checkpoint.pending_remote = None;
            checkpoint.blockers.clear();
            let remote_slot = checkpoint.remote_slot;
            store.save(&checkpoint)?;
            prepare(store, workers, chain, case_id, remote_slot)
        }
        (DeviceAction::Sign { psbt, .. }, DeviceResult::Signed(returned)) => {
            let Progress::Signing(mut signing) = checkpoint.progress.clone() else {
                return Err("remote signature arrived when the case is not signing".into());
            };
            let next = checkpoint
                .case
                .signing_subset
                .get(signing.completed.len())
                .ok_or("case has no incomplete signer")?;
            if *next != request.slot || signing.signed_psbt != *psbt {
                return Err(
                    "remote signature is stale or not for the next designated signer".into(),
                );
            }
            let before: Psbt = signing.signed_psbt.parse()?;
            let accepted = verify_device_signatures(
                &before,
                returned.parse()?,
                request.expected.fingerprint.parse()?,
            )?;
            signing.signed_psbt = accepted.to_string();
            signing.completed.push(request.slot);
            checkpoint.progress = Progress::Signing(signing);
            checkpoint.executions.push(remote_execution(
                request.slot,
                "sign_psbt",
                &request.request_id,
            ));
            checkpoint.pending_remote = None;
            checkpoint.blockers.clear();
            store.save(&checkpoint)?;
            execute(store, workers, chain, &mut checkpoint, None).await
        }
        _ => Err("remote worker response does not match its request".into()),
    }
}

fn remote_execution(slot: Slot, operation: &str, request_id: &str) -> Execution {
    Execution {
        timestamp: now(),
        slot,
        operation: format!("remote_{operation}"),
        command: vec!["e2e".into(), "import-remote".into()],
        exit_status: Some(0),
        success: true,
        log: format!("accepted remote response for request {request_id}"),
    }
}

fn validate_checkpoint(store: &Store, checkpoint: &mut Checkpoint) -> Result<()> {
    let enrolled = enrolled_case(store, &checkpoint.case)?;
    for saved in &mut checkpoint.enrolled {
        let current = enrolled
            .iter()
            .find(|e| e.slot == saved.slot)
            .ok_or("enrolled holder missing")?;
        check_identity_for_source(&saved.identity, &current.identity, saved.slot.source)?;
        if saved.slot.source == Source::Jade
            && current.identity.physical_id.starts_with("jade-name:")
        {
            saved.identity.physical_id = current.identity.physical_id.clone();
        }
    }
    if checkpoint.destination != wallet::destination(store, false)?.to_string() {
        return Err("saved test-owned destination changed".into());
    }
    wallet::validate_checkpoint(checkpoint)
}

fn adopt_jade_identity(
    store: &Store,
    checkpoint: &mut Checkpoint,
    enrollment: &mut Enrollment,
    actual: &crate::e2e::records::Identity,
) -> Result<()> {
    check_identity_for_source(&enrollment.identity, actual, Source::Jade)?;
    let stable_id = actual
        .physical_id
        .strip_prefix("jade-name:")
        .ok_or("Jade did not report its stable advertised name")?;
    if stable_id.is_empty() {
        return Err("Jade advertised an empty device name".into());
    }
    let saved = checkpoint
        .enrolled
        .iter_mut()
        .find(|saved| saved.slot == enrollment.slot)
        .ok_or("Jade enrollment is missing from the case checkpoint")?;
    check_identity_for_source(&saved.identity, actual, Source::Jade)?;

    enrollment.identity.physical_id = actual.physical_id.clone();
    saved.identity.physical_id = actual.physical_id.clone();
    write_json(&store.enrollment_path(enrollment.slot), enrollment)?;
    store.save(checkpoint)
}

fn parse_tx(hex: &str) -> Result<Transaction> {
    Ok(deserialize(&Vec::<u8>::from_hex(hex)?)?)
}

async fn wait_funding(
    chain: &impl Chain,
    checkpoint: &Checkpoint,
    outpoint: OutPoint,
) -> Result<Funding> {
    let started = Instant::now();
    loop {
        if let Some(previous) = chain.transaction(outpoint.txid)? {
            let destination = checkpoint
                .destination
                .parse::<bitcoin::Address<_>>()?
                .require_network(Network::Testnet4)?;
            wallet::spend_psbt(
                &checkpoint.case,
                &checkpoint.enrolled,
                &previous,
                outpoint,
                &destination,
            )?;
            if chain.outspend(outpoint)? != Outspend::Unspent {
                return Err("funding outpoint is already spent".into());
            }
            if let Some(confirmation) = chain.confirmation(outpoint.txid)? {
                return Ok(Funding {
                    outpoint: outpoint.to_string(),
                    amount_sats: previous.output[outpoint.vout as usize].value.to_sat(),
                    transaction_hex: serialize_hex(&previous),
                    confirmation,
                });
            }
        }
        if started.elapsed() >= Duration::from_secs(3600) {
            return Err("funding confirmation timed out after 60 minutes; checkpoint remains PENDING_FUNDING".into());
        }
        eprintln!("Waiting for a canonical funding confirmation; polling again in 15 seconds.");
        tokio::time::sleep(Duration::from_secs(15)).await;
    }
}

fn validate_signing(chain: &impl Chain, checkpoint: &Checkpoint, signing: &Signing) -> Result<()> {
    let outpoint: OutPoint = signing.funding.outpoint.parse()?;
    let previous = parse_tx(&signing.funding.transaction_hex)?;
    if chain.transaction(outpoint.txid)?.as_ref() != Some(&previous) {
        return Err(
            "fetched funding bytes differ from saved authenticated previous transaction".into(),
        );
    }
    if chain.confirmation(outpoint.txid)?.is_none() {
        return Err("funding no longer has a canonical confirmation".into());
    }
    wallet::validate_signing(checkpoint, signing)
}

fn restore_jade_registration(
    store: &Store,
    workers: &Workers<'_>,
    checkpoint: &mut Checkpoint,
    enrollment: &mut Enrollment,
) -> Result<()> {
    let registration = if let Some(previous) = &enrollment.jade_owned_descriptor {
        Registration::ReplaceOwned(previous.clone())
    } else if enrollment.jade_slot_declared_empty {
        Registration::DeclaredEmpty
    } else {
        return Err(
            "Jade registration ownership is missing; verify the test slot manually before reuse"
                .into(),
        );
    };
    let (response, execution) = workers.call(
        enrollment,
        DeviceAction::Verify {
            descriptor: checkpoint.receive_descriptor.clone(),
            index: checkpoint.case.receive.index,
            signer: enrollment.identity.fingerprint.clone(),
            registration,
        },
        false,
    )?;
    let DeviceResult::Address(address) = response.result else {
        return Err("registration worker returned a different operation".into());
    };
    if address != checkpoint.receive_address {
        return Err("restored Jade registration derives a different funding address".into());
    }
    adopt_jade_identity(store, checkpoint, enrollment, &response.identity)?;
    enrollment.jade_owned_descriptor = Some(checkpoint.receive_descriptor.clone());
    enrollment.jade_slot_declared_empty = false;
    write_json(&store.enrollment_path(enrollment.slot), enrollment)?;
    checkpoint.executions.push(execution);
    store.save(checkpoint)
}

async fn execute(
    store: &Store,
    workers: &Workers<'_>,
    chain: &impl Chain,
    checkpoint: &mut Checkpoint,
    supplied: Option<OutPoint>,
) -> Result<()> {
    validate_checkpoint(store, checkpoint)?;
    chain.verify_network()?;
    checkpoint.genesis_verified_at = now();
    checkpoint.blockers.clear();
    if let Some(f) = checkpoint.progress.finalized()
        && supplied.is_some_and(|o| o.to_string() != f.signing.funding.outpoint)
    {
        return Err("new funding outpoint differs from saved spend".into());
    }
    store.save(checkpoint)?;
    if matches!(checkpoint.progress, Progress::Prepared) {
        let outpoint =
            supplied.ok_or("prepared case needs e2e run --funding-outpoint before resume")?;
        store.reserve(outpoint, &checkpoint.case.id)?;
        checkpoint.progress = Progress::AwaitingFunding {
            outpoint: outpoint.to_string(),
        };
        store.save(checkpoint)?;
    }
    if let Progress::AwaitingFunding { outpoint } = &checkpoint.progress {
        let outpoint: OutPoint = outpoint.parse()?;
        if supplied.is_some_and(|o| o != outpoint) {
            return Err("new funding outpoint differs from reserved outpoint".into());
        }
        store.reserve(outpoint, &checkpoint.case.id)?;
        let funding = wait_funding(chain, checkpoint, outpoint).await?;
        checkpoint.progress = Progress::FundingConfirmed(funding);
        store.save(checkpoint)?;
    }
    if let Progress::FundingConfirmed(funding) = &checkpoint.progress {
        let outpoint: OutPoint = funding.outpoint.parse()?;
        if supplied.is_some_and(|o| o != outpoint) {
            return Err("new funding outpoint differs from confirmed outpoint".into());
        }
        store.reserve(outpoint, &checkpoint.case.id)?;
        let previous = parse_tx(&funding.transaction_hex)?;
        let destination = checkpoint
            .destination
            .parse::<bitcoin::Address<_>>()?
            .require_network(Network::Testnet4)?;
        let psbt = wallet::spend_psbt(
            &checkpoint.case,
            &checkpoint.enrolled,
            &previous,
            outpoint,
            &destination,
        )?;
        checkpoint.progress = Progress::Signing(Signing {
            funding: funding.clone(),
            unsigned_psbt: psbt.to_string(),
            signed_psbt: psbt.to_string(),
            completed: Vec::new(),
        });
        store.save(checkpoint)?;
    }
    if let Progress::Signing(mut signing) = checkpoint.progress.clone() {
        validate_signing(chain, checkpoint, &signing)?;
        let outpoint: OutPoint = signing.funding.outpoint.parse()?;
        if supplied.is_some_and(|o| o != outpoint) {
            return Err("new funding outpoint differs from signing checkpoint".into());
        }
        store.reserve(outpoint, &checkpoint.case.id)?;
        while signing.completed.len() < checkpoint.case.signing_subset.len() {
            if chain.outspend(outpoint)? != Outspend::Unspent {
                return Err("reserved funding was spent before signing".into());
            }
            let slot = checkpoint.case.signing_subset[signing.completed.len()];
            let mut enrollment: Enrollment = read_json(&store.enrollment_path(slot))?;
            if slot.source == Source::Jade && checkpoint.case.family == Family::P2wsh {
                restore_jade_registration(store, workers, checkpoint, &mut enrollment)?;
            }
            let before: Psbt = signing.signed_psbt.parse()?;
            let signed = if slot.source == Source::Hot {
                wallet::sign_hot(
                    &before,
                    &wallet::hot_mnemonic(store, slot, false)?,
                    &enrollment,
                    checkpoint.case.family,
                )?
            } else {
                let action = DeviceAction::Sign {
                    psbt: before.to_string(),
                    signer: enrollment.identity.fingerprint.clone(),
                };
                let Some((response, execution)) =
                    call_or_queue_remote(store, workers, checkpoint, &enrollment, action)?
                else {
                    return Ok(());
                };
                checkpoint.executions.push(execution);
                let actual_identity = response.identity.clone();
                let DeviceResult::Signed(signed) = response.result else {
                    return Err("signing worker returned a different operation".into());
                };
                let verified = verify_device_signatures(
                    &before,
                    signed.parse()?,
                    enrollment.identity.fingerprint.parse()?,
                )?;
                if slot.source == Source::Jade {
                    adopt_jade_identity(store, checkpoint, &mut enrollment, &actual_identity)?;
                }
                verified
            };
            signing.signed_psbt = signed.to_string();
            signing.completed.push(slot);
            checkpoint.progress = Progress::Signing(signing.clone());
            store.save(checkpoint)?;
            validate_signing(chain, checkpoint, &signing)?;
        }
        let psbt: Psbt = signing.signed_psbt.parse()?;
        let tx = wallet::finalize(&psbt)?;
        checkpoint.progress = Progress::Finalized(Finalized {
            signing,
            transaction_hex: serialize_hex(&tx),
            txid: tx.compute_txid().to_string(),
            fee_sats: psbt.fee()?.to_sat(),
            vsize: tx.vsize(),
            broadcast_authorized: checkpoint.broadcast_authorized,
        });
        store.save(checkpoint)?;
    }
    finish(store, chain, checkpoint).await
}

async fn finish(store: &Store, chain: &impl Chain, checkpoint: &mut Checkpoint) -> Result<()> {
    let mut finalized = checkpoint
        .progress
        .finalized()
        .ok_or("case has no finalized transaction")?
        .clone();
    finalized.broadcast_authorized |= checkpoint.broadcast_authorized;
    validate_signing(chain, checkpoint, &finalized.signing)?;
    let expected = wallet::validate_finalized(checkpoint, &finalized)?;
    store.reserve(
        finalized.signing.funding.outpoint.parse()?,
        &checkpoint.case.id,
    )?;
    match reconcile(chain, &expected)? {
        Reconciled::Confirmed(confirmation) => {
            checkpoint.progress = Progress::Pass {
                finalized,
                confirmation,
            };
            store.save(checkpoint)?;
            println!(
                "{} PASS, confirmed spend {}.",
                checkpoint.case.id,
                expected.compute_txid()
            );
            return Ok(());
        }
        Reconciled::Pending => {
            checkpoint.progress = Progress::PendingConfirmation(finalized.clone());
            store.save(checkpoint)?;
        }
        Reconciled::Unspent => {
            if !finalized.broadcast_authorized {
                checkpoint.progress = Progress::Finalized(finalized);
                store.save(checkpoint)?;
                println!(
                    "Finalized {}. Broadcast is not authorized; use e2e resume --case {} --broadcast.",
                    expected.compute_txid(),
                    checkpoint.case.id
                );
                return Ok(());
            }
            checkpoint.progress = Progress::BroadcastUnknown(finalized.clone());
            store.save(checkpoint)?;
            chain.broadcast(&expected)?;
            checkpoint.progress = Progress::PendingConfirmation(finalized.clone());
            store.save(checkpoint)?;
        }
    }
    let started = Instant::now();
    loop {
        match reconcile(chain, &expected)? {
            Reconciled::Confirmed(confirmation) => {
                checkpoint.progress = Progress::Pass {
                    finalized,
                    confirmation,
                };
                store.save(checkpoint)?;
                println!(
                    "{} PASS, confirmed spend {}.",
                    checkpoint.case.id,
                    expected.compute_txid()
                );
                return Ok(());
            }
            Reconciled::Pending | Reconciled::Unspent => {}
        }
        if started.elapsed() >= Duration::from_secs(3600) {
            return Err("spend confirmation timed out after 60 minutes; checkpoint remains PENDING_CONFIRMATION".into());
        }
        eprintln!(
            "{} PENDING_CONFIRMATION; polling again in 15 seconds.",
            expected.compute_txid()
        );
        tokio::time::sleep(Duration::from_secs(15)).await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::e2e::records::{Confirmation, test_store};
    use bitcoin::{Amount, ScriptBuf, Sequence, TxIn, TxOut, Witness, absolute, transaction};
    use std::cell::{Cell, RefCell};

    struct InterruptedApi {
        funding: Transaction,
        submissions: RefCell<Vec<Transaction>>,
        visible: Cell<bool>,
        fail_next: Cell<bool>,
        accepted_on_failure: bool,
        network_ok: bool,
        spend_confirmed: Cell<bool>,
    }

    impl Chain for InterruptedApi {
        fn verify_network(&self) -> Result<()> {
            if self.network_ok {
                Ok(())
            } else {
                Err("wrong testnet4 genesis".into())
            }
        }
        fn transaction(&self, txid: bitcoin::Txid) -> Result<Option<Transaction>> {
            if txid == self.funding.compute_txid() {
                return Ok(Some(self.funding.clone()));
            }
            Ok(self
                .submissions
                .borrow()
                .last()
                .filter(|tx| self.visible.get() && tx.compute_txid() == txid)
                .cloned())
        }
        fn confirmation(&self, txid: bitcoin::Txid) -> Result<Option<Confirmation>> {
            if txid != self.funding.compute_txid() && !self.spend_confirmed.get() {
                return Ok(None);
            }
            Ok(self.transaction(txid)?.map(|_| Confirmation {
                block_height: 10,
                block_hash: "000000000000000000000000000000000000000000000000000000000000000a"
                    .into(),
                tip_height: 10,
                confirmations: 1,
                checked_at: now(),
            }))
        }
        fn outspend(&self, _: OutPoint) -> Result<Outspend> {
            if self.visible.get() {
                Ok(Outspend::Spent {
                    txid: self.submissions.borrow().last().unwrap().compute_txid(),
                    vin: 0,
                })
            } else {
                Ok(Outspend::Unspent)
            }
        }
        fn broadcast(&self, tx: &Transaction) -> Result<bitcoin::Txid> {
            self.submissions.borrow_mut().push(tx.clone());
            if self.fail_next.replace(false) {
                self.visible.set(self.accepted_on_failure);
                Err("connection interrupted before broadcast response".into())
            } else {
                self.visible.set(true);
                Ok(tx.compute_txid())
            }
        }
    }

    fn fixture(store: &Store, authorize: bool) -> (Checkpoint, Transaction) {
        let case = crate::e2e::model::matrix()
            .into_iter()
            .find(|c| c.wallet_class == "hot" && c.family == Family::Bip86)
            .unwrap();
        let slot = case.holders[0];
        let mnemonic = wallet::hot_mnemonic(store, slot, true).unwrap();
        let enrollment = Enrollment {
            slot,
            identity: wallet::mnemonic_identity(&mnemonic).unwrap(),
            selector: None,
            jade_slot_declared_empty: false,
            jade_owned_descriptor: None,
        };
        write_json(&store.enrollment_path(slot), &enrollment).unwrap();
        let receive = wallet::descriptor(&case, std::slice::from_ref(&enrollment), 0).unwrap();
        let change = wallet::descriptor(&case, std::slice::from_ref(&enrollment), 1).unwrap();
        let receive_address =
            derive_address_at(&receive, case.receive.index, Network::Testnet4).unwrap();
        let change_address =
            derive_address_at(&change, case.change.index, Network::Testnet4).unwrap();
        let proofs = [
            (0, case.receive.index, receive_address.to_string()),
            (1, case.change.index, change_address.to_string()),
        ]
        .into_iter()
        .map(|(branch, index, address)| AddressProof {
            slot,
            branch,
            index,
            address,
            firmware: enrollment.identity.firmware.clone(),
            fingerprint: enrollment.identity.fingerprint.clone(),
            device_displayed: false,
            verified_at: now(),
        })
        .collect();
        let funding = Transaction {
            version: transaction::Version::TWO,
            lock_time: absolute::LockTime::ZERO,
            input: vec![TxIn {
                previous_output: OutPoint {
                    txid: bitcoin::Txid::from_raw_hash(bitcoin::hashes::Hash::from_byte_array(
                        [42; 32],
                    )),
                    vout: 0,
                },
                script_sig: ScriptBuf::new(),
                sequence: Sequence::MAX,
                witness: Witness::new(),
            }],
            output: vec![TxOut {
                value: Amount::from_sat(case.funding_target_sats()),
                script_pubkey: receive_address.script_pubkey(),
            }],
        };
        let checkpoint = Checkpoint {
            schema: 1,
            case,
            enrolled: vec![enrollment],
            receive_descriptor: receive.to_string(),
            change_descriptor: change.to_string(),
            receive_address: receive_address.to_string(),
            change_address: change_address.to_string(),
            destination: wallet::destination(store, true).unwrap().to_string(),
            address_proofs: proofs,
            executions: Vec::new(),
            progress: Progress::Prepared,
            blockers: Vec::new(),
            created_at: now(),
            broadcast_authorized: authorize,
            genesis_verified_at: now(),
            runner_revision: "offline-test".into(),
            remote_slot: None,
            pending_remote: None,
        };
        (checkpoint, funding)
    }

    #[test]
    fn diagnostic_sign_action_reuses_saved_psbt_without_changing_checkpoint() {
        let case = find_case(crate::e2e::model::CONFIRMED_SAFE7_P2WSH_CASE_ID).unwrap();
        let slot = case.signing_subset[0];
        assert_eq!(slot.source, Source::Trezor);
        let checkpoint = Checkpoint {
            schema: 1,
            case,
            enrolled: vec![placeholder(slot, None)],
            receive_descriptor: String::new(),
            change_descriptor: String::new(),
            receive_address: String::new(),
            change_address: String::new(),
            destination: String::new(),
            address_proofs: Vec::new(),
            executions: Vec::new(),
            progress: Progress::Signing(Signing {
                funding: Funding {
                    outpoint: format!("{}:0", "00".repeat(32)),
                    transaction_hex: String::new(),
                    amount_sats: 0,
                    confirmation: Confirmation {
                        block_height: 1,
                        block_hash: "00".repeat(32),
                        tip_height: 1,
                        confirmations: 1,
                        checked_at: 1,
                    },
                },
                unsigned_psbt: "unsigned PSBT".into(),
                signed_psbt: "saved PSBT with prior signatures".into(),
                completed: Vec::new(),
            }),
            blockers: Vec::new(),
            created_at: 1,
            broadcast_authorized: true,
            genesis_verified_at: 1,
            runner_revision: "test".into(),
            remote_slot: None,
            pending_remote: None,
        };
        let before = serde_json::to_value(&checkpoint).unwrap();

        let (enrollment, action) = diagnostic_sign_action(&checkpoint).unwrap();

        assert_eq!(enrollment.slot, slot);
        assert!(matches!(
            action,
            DeviceAction::Sign { psbt, .. } if psbt == "saved PSBT with prior signatures"
        ));
        assert_eq!(serde_json::to_value(&checkpoint).unwrap(), before);
    }

    #[test]
    fn prepared_checkpoint_load_preserves_schema_and_manifest_case() {
        let store = test_store();
        let (checkpoint, _) = fixture(&store, false);
        store.save(&checkpoint).unwrap();

        let loaded = store.load(&checkpoint.case.id).unwrap();
        assert_eq!(loaded.schema, 1);
        assert_eq!(loaded.case, checkpoint.case);
        assert!(matches!(loaded.progress, Progress::Prepared));

        std::fs::remove_dir_all(store.root.parent().unwrap()).unwrap();
    }

    #[tokio::test]
    async fn ambiguous_broadcast_resume_uses_identical_bytes_and_avoids_known_spends() {
        for accepted in [true, false] {
            let store = test_store();
            let lease = Lease::acquire(&store).unwrap();
            let settings = Settings {
                trezor_selector: None,
                jade_selector: None,
                scan_secs: 0,
                passphrase_on_device: false,
            };
            let workers = Workers {
                store: &store,
                settings: &settings,
            };
            let (mut checkpoint, funding) = fixture(&store, true);
            let outpoint = OutPoint {
                txid: funding.compute_txid(),
                vout: 0,
            };
            let chain = InterruptedApi {
                funding,
                submissions: RefCell::new(Vec::new()),
                visible: Cell::new(false),
                fail_next: Cell::new(true),
                accepted_on_failure: accepted,
                network_ok: true,
                spend_confirmed: Cell::new(true),
            };
            let failed = execute(&store, &workers, &chain, &mut checkpoint, Some(outpoint)).await;
            assert!(
                failed
                    .unwrap_err()
                    .to_string()
                    .contains("connection interrupted")
            );
            let saved = store.load(&checkpoint.case.id).unwrap();
            assert!(matches!(saved.progress, Progress::BroadcastUnknown(_)));
            let finalized = saved.progress.finalized().unwrap();
            assert_eq!(
                serialize_hex(&chain.submissions.borrow()[0]),
                finalized.transaction_hex
            );
            checkpoint = saved;
            execute(&store, &workers, &chain, &mut checkpoint, None)
                .await
                .unwrap();
            assert!(matches!(
                store.load(&checkpoint.case.id).unwrap().progress,
                Progress::Pass { .. }
            ));
            let submissions = chain.submissions.borrow();
            assert_eq!(submissions.len(), if accepted { 1 } else { 2 });
            assert!(
                submissions.iter().all(|tx| serialize_hex(tx)
                    == checkpoint.progress.finalized().unwrap().transaction_hex)
            );
            drop(submissions);
            drop(lease);
            std::fs::remove_dir_all(store.root.parent().unwrap()).unwrap();
        }
    }

    #[tokio::test]
    async fn network_and_explicit_broadcast_gates_precede_submission() {
        let store = test_store();
        let lease = Lease::acquire(&store).unwrap();
        let settings = Settings {
            trezor_selector: None,
            jade_selector: None,
            scan_secs: 0,
            passphrase_on_device: false,
        };
        let workers = Workers {
            store: &store,
            settings: &settings,
        };
        let (mut checkpoint, funding) = fixture(&store, false);
        let outpoint = OutPoint {
            txid: funding.compute_txid(),
            vout: 0,
        };
        let mut chain = InterruptedApi {
            funding,
            submissions: RefCell::new(Vec::new()),
            visible: Cell::new(false),
            fail_next: Cell::new(false),
            accepted_on_failure: false,
            network_ok: false,
            spend_confirmed: Cell::new(true),
        };
        assert!(
            execute(&store, &workers, &chain, &mut checkpoint, Some(outpoint))
                .await
                .unwrap_err()
                .to_string()
                .contains("genesis")
        );
        assert!(matches!(checkpoint.progress, Progress::Prepared));
        assert!(chain.submissions.borrow().is_empty());
        chain.network_ok = true;
        execute(&store, &workers, &chain, &mut checkpoint, Some(outpoint))
            .await
            .unwrap();
        assert!(matches!(checkpoint.progress, Progress::Finalized(_)));
        assert!(chain.submissions.borrow().is_empty());
        checkpoint.broadcast_authorized = true;
        execute(&store, &workers, &chain, &mut checkpoint, None)
            .await
            .unwrap();
        assert_eq!(chain.submissions.borrow().len(), 1);
        assert!(matches!(checkpoint.progress, Progress::Pass { .. }));
        drop(lease);
        std::fs::remove_dir_all(store.root.parent().unwrap()).unwrap();
    }

    #[tokio::test]
    async fn report_rechecks_canonical_pass_without_resubmitting() {
        let store = test_store();
        let settings = Settings {
            trezor_selector: None,
            jade_selector: None,
            scan_secs: 0,
            passphrase_on_device: false,
        };
        let workers = Workers {
            store: &store,
            settings: &settings,
        };
        let (mut checkpoint, funding) = fixture(&store, true);
        let outpoint = OutPoint {
            txid: funding.compute_txid(),
            vout: 0,
        };
        let mut chain = InterruptedApi {
            funding,
            submissions: RefCell::new(Vec::new()),
            visible: Cell::new(false),
            fail_next: Cell::new(false),
            accepted_on_failure: false,
            network_ok: false,
            spend_confirmed: Cell::new(true),
        };
        let empty = crate::e2e::report::collect(&store, &chain).unwrap();
        assert_eq!(empty["live_pass"], 0);
        assert_eq!(empty["genesis_verified_at"], serde_json::Value::Null);
        chain.network_ok = true;
        execute(&store, &workers, &chain, &mut checkpoint, Some(outpoint))
            .await
            .unwrap();
        let saved = std::fs::read(store.checkpoint_path(&checkpoint.case.id).unwrap()).unwrap();
        let report = crate::e2e::report::collect(&store, &chain).unwrap();
        assert_eq!(report["live_pass"], 1);
        assert_eq!(report["recorded_live_pass"], 1);
        assert_eq!(report["overall"], "BLOCKED");
        let public_path = store
            .root
            .join("results")
            .join(format!("{}.json", checkpoint.case.id));
        let canonical: serde_json::Value = read_json(&public_path).unwrap();
        let mut corrupted = canonical.clone();
        for field in [
            "receive_address",
            "change_address",
            "destination",
            "address_verification",
            "origins",
            "signing_subset",
            "designated_signatures",
            "fee_policy",
            "executions",
            "versions",
        ] {
            corrupted[field] = serde_json::json!({"forged":true});
        }
        corrupted["funding"] = serde_json::json!({"forged":true});
        corrupted["spend"]["fee_sats"] = serde_json::json!(999999);
        corrupted["spend"]["vsize"] = serde_json::json!(1);
        write_json(&public_path, &corrupted).unwrap();
        let rebuilt = crate::e2e::report::collect(&store, &chain).unwrap();
        assert_eq!(rebuilt["live_pass"], 1);
        let restored = rebuilt["cases"]
            .as_array()
            .unwrap()
            .iter()
            .find(|r| r["case"]["id"] == checkpoint.case.id)
            .unwrap();
        for field in [
            "receive_address",
            "change_address",
            "destination",
            "address_verification",
            "origins",
            "signing_subset",
            "designated_signatures",
            "fee_policy",
            "spend",
            "executions",
            "versions",
        ] {
            assert_eq!(
                restored[field], canonical[field],
                "reported {field} must come from the validated checkpoint"
            );
        }
        assert_eq!(
            restored["funding"]["outpoint"],
            canonical["funding"]["outpoint"]
        );
        assert_eq!(
            restored["funding"]["amount_sats"],
            canonical["funding"]["amount_sats"]
        );
        assert!(
            restored["funding"]["confirmation"]["checked_at"]
                .as_u64()
                .unwrap()
                >= canonical["funding"]["confirmation"]["checked_at"]
                    .as_u64()
                    .unwrap()
        );
        chain.spend_confirmed.set(false);
        let reorganized = crate::e2e::report::collect(&store, &chain).unwrap();
        assert_eq!(reorganized["live_pass"], 0);
        assert_eq!(reorganized["recorded_live_pass"], 1);
        assert!(reorganized["cases"].as_array().unwrap().iter().any(|r| {
            r["blockers"].as_array().is_some_and(|b| {
                b.iter().any(|e| {
                    e.as_str()
                        .is_some_and(|s| s.contains("canonical confirmation"))
                })
            })
        }));
        chain.visible.set(false);
        assert_eq!(
            crate::e2e::report::collect(&store, &chain).unwrap()["live_pass"],
            0
        );
        chain.visible.set(true);
        chain.spend_confirmed.set(true);
        chain.network_ok = false;
        assert_eq!(
            crate::e2e::report::collect(&store, &chain).unwrap()["live_pass"],
            0
        );
        chain.network_ok = true;
        let forged = crate::e2e::model::manifest()
            .iter()
            .find(|c| c.id != checkpoint.case.id)
            .unwrap();
        write_json(
            &store
                .root
                .join("results")
                .join(format!("{}.json", forged.id)),
            &serde_json::json!({"case":forged,"status":"PASS","blockers":[]}),
        )
        .unwrap();
        let report = crate::e2e::report::collect(&store, &chain).unwrap();
        assert_eq!(report["live_pass"], 1);
        assert_eq!(report["recorded_live_pass"], 2);
        assert_eq!(
            chain.submissions.borrow().len(),
            1,
            "report must never broadcast"
        );
        assert_eq!(
            std::fs::read(store.checkpoint_path(&checkpoint.case.id).unwrap()).unwrap(),
            saved,
            "report must not mutate checkpoints"
        );
        std::fs::remove_dir_all(store.root.parent().unwrap()).unwrap();
    }
}
