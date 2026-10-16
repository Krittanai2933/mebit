//! Phase-0 hardware harness: drives a real Trezor Safe 7 over BLE through
//! `trezor-ble`. A test tool, not product code; `trezor-ble/README.md` has
//! the checklist.
//!
//! ```text
//! cargo run -p trezor-ble --example trezor-hw-test -- scan
//! cargo run -p trezor-ble --example trezor-hw-test -- --credential-file target/safe7.cred info
//! cargo run -p trezor-ble --example trezor-hw-test -- xpub --verify-against target/phrase.txt
//! cargo run -p trezor-ble --example trezor-hw-test -- roundtrip --inputs 2
//! ```

mod coordinator;
#[path = "../../../wallet-e2e/mod.rs"]
pub mod e2e;
mod roundtrip;

use std::error::Error;
use std::future::Future;
use std::path::{Path, PathBuf};
use std::pin::Pin;
use std::process::ExitCode;
use std::str::FromStr;
use std::time::{Duration, Instant};

use bip39::Mnemonic;
use bitcoin::bip32::{ChildNumber, DerivationPath, Xpub};
use bitcoin::hashes::{Hash, HashEngine, sha256};
use bitcoin::hex::DisplayHex;
use bitcoin::secp256k1::Secp256k1;
use bitcoin::{Network, Psbt};
use clap::{Parser, Subcommand};
use tokio::io::AsyncBufReadExt;
use trezor_ble::{ConnectOptions, PairingCodeSource, TrezorBle, TrezorBleError};
use vault_core::error::VaultCoreError;
use vault_core::hw::trezor::{
    EcdsaSignatureRejection, PairingCredential, Safe7Identity, SessionPassphrase, SigningTrace,
    TrezorError, XpubOptions,
};
use vault_core::keys::{
    HwVendor, KeySourceType, Purpose, ScriptType, VaultKey, account_multisig_xpub_from_mnemonic,
    account_xpub_from_mnemonic,
};

#[derive(Parser)]
struct Args {
    /// bitcoin, testnet, testnet4, signet or regtest
    #[arg(long, default_value = "testnet")]
    network: Network,

    /// Which Safe 7, when several are in range: an id from `scan`, or its
    /// name or the end of it
    #[arg(long)]
    device: Option<String>,

    #[arg(long, default_value_t = 5)]
    scan_secs: u64,

    /// Keep the pairing credential here and reuse it next time. It is a
    /// secret (it holds the host's key): keep the file under target/.
    #[arg(long)]
    credential_file: Option<PathBuf>,

    /// Read the pairing code from this file instead of the terminal: it is
    /// polled until it exists, then deleted. For driving the harness from
    /// another process.
    #[arg(long)]
    code_file: Option<PathBuf>,

    /// Have the Safe 7 ask for a BIP-39 passphrase on its own screen.
    #[arg(long)]
    passphrase_on_device: bool,

    /// Jade selector for enrollment through the serial coordinator.
    #[arg(long)]
    jade_device: Option<String>,

    #[arg(long, hide = true)]
    signing_trace_worker: bool,

    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Deterministic offline checks and checkpointed real testnet4 spends.
    E2e {
        #[command(subcommand)]
        command: E2eCommand,
    },
    /// List devices advertising the Trezor service. No connection.
    Scan,
    /// Connect (pairing the first time) and print what the Safe 7 reports.
    Info,
    /// Print the master fingerprint and the xpub at --path.
    Xpub {
        /// Default: the BIP-48 P2WSH multisig account, m/48'/<coin>'/0'/2'.
        /// Also allowed: a BIP-84 account, to compare with Trezor Suite.
        #[arg(long)]
        path: Option<DerivationPath>,
        /// Show the xpub on the Safe 7 too.
        #[arg(long)]
        show: bool,
        /// Ask the Safe 7 for the encoding Trezor Suite shows (e.g. vpub…).
        #[arg(long)]
        slip132: bool,
        /// A file holding the Safe 7's (test!) recovery phrase: re-derive the
        /// xpub with vault-core and require it to match.
        #[arg(long)]
        verify_against: Option<PathBuf>,
    },
    /// Sign a PSBT file (base64 or binary), write the result as base64.
    /// Test networks only.
    Sign {
        #[arg(long)]
        psbt: PathBuf,
        #[arg(long)]
        out: Option<PathBuf>,
    },
    /// Build a 2-of-3 vault spend with synthetic inputs, have the Safe 7
    /// sign it, co-sign with a software key, and finalize it. Test networks
    /// only; nothing is broadcast.
    Roundtrip {
        #[arg(long, default_value_t = 2)]
        inputs: u32,
        /// Where to write the unsigned and signed PSBTs
        #[arg(long)]
        out_dir: Option<PathBuf>,
    },
}

#[derive(Subcommand)]
enum E2eCommand {
    /// Ask the next enrolled Trezor to sign the saved PSBT without saving or broadcasting it.
    DiagnoseSign {
        #[arg(long)]
        case: String,
        #[arg(long)]
        trace_signing: bool,
    },
    /// Execute one testnet4 remote Trezor worker request and write its bound response.
    RemoteWorker {
        #[arg(long)]
        request: PathBuf,
        #[arg(long)]
        response: PathBuf,
    },
    #[command(flatten)]
    Shared(e2e::model::Command),
}

#[tokio::main]
async fn main() -> ExitCode {
    match run(Args::parse()).await {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("error: {error}");
            ExitCode::FAILURE
        }
    }
}

async fn run(mut args: Args) -> Result<(), Box<dyn Error>> {
    let selected = std::mem::replace(&mut args.command, Command::Info);
    if args.signing_trace_worker
        && !matches!(
            selected,
            Command::E2e {
                command: E2eCommand::Shared(e2e::model::Command::Device { .. })
            }
        )
    {
        return Err("private trace flag requires a signing worker".into());
    }
    if let Command::E2e { command } = selected {
        if args.network != Network::Testnet4 {
            return Err("e2e requires --network testnet4".into());
        }
        let store = e2e::records::Store::new()?;
        let result = dispatch_e2e(&args, command, &store).await;
        e2e::records::log_command(&store, &result)?;
        return result;
    }
    args.command = selected;
    let scan = Duration::from_secs(args.scan_secs);
    if let Command::Scan = args.command {
        eprintln!("Scanning {} s…", args.scan_secs);
        for device in TrezorBle::scan(scan).await? {
            println!(
                "{}  {}  rssi {}",
                device.id,
                device.name.as_deref().unwrap_or("(no name)"),
                device.rssi.map_or("?".into(), |rssi| rssi.to_string())
            );
        }
        return Ok(());
    }
    if matches!(
        args.command,
        Command::Sign { .. } | Command::Roundtrip { .. }
    ) && args.network == Network::Bitcoin
    {
        return Err("this harness signs on test networks only".into());
    }

    let credential = match &args.credential_file {
        Some(file) if file.exists() => Some(PairingCredential::from_bytes(&std::fs::read(file)?)?),
        _ => None,
    };
    let options = ConnectOptions {
        network: args.network,
        host_name: host_name(),
        app_name: "mebit trezor-hw-test".into(),
        passphrase: if args.passphrase_on_device {
            SessionPassphrase::OnDevice
        } else {
            SessionPassphrase::None
        },
        credential,
        device: args.device.clone(),
        scan,
    };
    eprintln!("Scanning {} s for a Safe 7…", args.scan_secs);
    eprintln!(
        "Wake the Safe 7 first: asleep, it turns Bluetooth off. On first use: on the Safe 7, open \
         Bluetooth and choose Pair new device; confirm the host's pairing dialog if the digits \
         match; approve on the Safe 7; then type its code."
    );
    let codes = CodeSource {
        file: args.code_file.clone(),
    };
    let started = Instant::now();
    let connection = TrezorBle::connect(options, &codes)
        .await
        .map_err(|error| format!("{error} (after {:.0} s)", started.elapsed().as_secs_f64()))?;
    let mut trezor = connection.device;
    eprintln!(
        "Connected to {} in {:.1} s (MTU {})",
        trezor.name(),
        started.elapsed().as_secs_f64(),
        trezor.mtu()
    );
    if let (Some(credential), Some(file)) = (&connection.new_credential, &args.credential_file) {
        write_secret(file, &credential.to_bytes())?;
        eprintln!("Paired; credential kept in {} (a secret)", file.display());
    }

    // From here Ctrl-C does what an app's Cancel button would: it asks the
    // Safe 7 to drop its prompt. A second Ctrl-C quits.
    let canceller = trezor.canceller();
    tokio::spawn(async move {
        if tokio::signal::ctrl_c().await.is_ok() {
            eprintln!("Cancelling on the Safe 7… (Ctrl-C again quits)");
            canceller.cancel();
            if tokio::signal::ctrl_c().await.is_ok() {
                std::process::exit(130);
            }
        }
    });

    let outcome = command(&mut trezor, args.network, args.command).await;
    let closed = trezor.disconnect().await;
    outcome?;
    Ok(closed?)
}

async fn dispatch_e2e(
    args: &Args,
    command: E2eCommand,
    store: &e2e::records::Store,
) -> e2e::Result<()> {
    let settings = coordinator::Settings {
        trezor_selector: args.device.clone(),
        jade_selector: args.jade_device.clone(),
        scan_secs: args.scan_secs,
        passphrase_on_device: args.passphrase_on_device,
    };
    match command {
        E2eCommand::DiagnoseSign {
            case,
            trace_signing,
        } => coordinator::diagnose_sign(&case, store, settings, trace_signing),
        E2eCommand::RemoteWorker { request, response } => {
            if args.network != Network::Testnet4 {
                return Err("remote workers accept testnet4 E2E requests only".into());
            }
            let request: e2e::records::RemoteRequest =
                e2e::records::read_json_limited(&request, e2e::records::MAX_REMOTE_FILE_BYTES)?;
            e2e::records::validate_remote_request(&request)?;
            let _hardware_lease = e2e::records::Lease::acquire_hardware(store)?;
            let default_credential = store.secrets.join(format!("{}.credential", request.slot));
            run_remote_device(args, request, response, &default_credential).await
        }
        E2eCommand::Shared(command) => {
            if e2e::offline_command(&command, store)? {
                return Ok(());
            }
            if let e2e::model::Command::Device { request, response } = command {
                let request: e2e::records::WorkerRequest = e2e::records::read_json(&request)?;
                e2e::records::validate_worker_request(&request)?;
                if args.signing_trace_worker
                    && !matches!(request.action, e2e::records::DeviceAction::Sign { .. })
                {
                    return Err("private trace flag requires a signing worker".into());
                }
                let _hardware_lease = e2e::records::Lease::acquire_hardware(store)?;
                return run_device(args, request, response, None).await;
            }
            coordinator::run(command, store, settings).await
        }
    }
}

async fn command(
    trezor: &mut TrezorBle,
    network: Network,
    command: Command,
) -> Result<(), Box<dyn Error>> {
    match command {
        Command::E2e { .. } => return Err("e2e commands must dispatch before opening BLE".into()),
        Command::Scan => unreachable!("handled before connecting"),
        Command::Info => {
            let identity = trezor.identity();
            println!("model           {}", identity.model);
            println!("internal model  {}", identity.internal_model);
            println!("firmware        {}", identity.firmware_version());
            println!(
                "device id       {}",
                identity.device_id.as_deref().unwrap_or("-")
            );
            println!(
                "label           {}",
                identity.label.as_deref().unwrap_or("-")
            );
            println!("name            {}", trezor.name());
            println!("MTU             {}", trezor.mtu());
        }
        Command::Xpub {
            path,
            show,
            slip132,
            verify_against,
        } => {
            let path = path.unwrap_or_else(|| multisig_account_path(network));
            if show {
                eprintln!("Compare the xpub on the Safe 7, then confirm…");
            }
            let account = trezor
                .xpub(
                    &path,
                    XpubOptions {
                        show_on_device: show,
                        slip132,
                    },
                )
                .await?;
            let key = VaultKey {
                label: trezor.name().to_owned(),
                source_type: KeySourceType::HardwareWallet(HwVendor::TrezorSafe7),
                fingerprint: account.master_fingerprint,
                xpub: account.xpub,
                // `VaultKey` writes paths with the `m/` that rust-bitcoin's Display leaves out.
                derivation_path: format!("m/{path}"),
            };
            println!("fingerprint  {}", key.fingerprint);
            println!("path         {}", key.derivation_path);
            println!("xpub         {}", account.xpub);
            println!("as shown     {}", account.as_shown);
            println!("descriptor   [{}/{path}]{}", key.fingerprint, account.xpub);
            println!("{key:#?}");
            if let Some(phrase_file) = verify_against {
                verify_xpub(&phrase_file, network, &path, &account.xpub, key.fingerprint)?;
            }
        }
        Command::Sign { psbt, out } => {
            let psbt = read_psbt(&psbt)?;
            let signer = trezor
                .xpub(&multisig_account_path(network), XpubOptions::default())
                .await?
                .master_fingerprint;
            eprintln!("Check the outputs and fee on the Safe 7, then confirm…");
            let signed = trezor.sign_psbt(&psbt, signer).await?;
            eprintln!("Signed and verified.");
            match out {
                Some(out) => std::fs::write(out, signed.to_string())?,
                None => println!("{signed}"),
            }
        }
        Command::Roundtrip { inputs, out_dir } => {
            let account = trezor
                .xpub(&multisig_account_path(network), XpubOptions::default())
                .await?;
            let fixture = roundtrip::Fixture::build(
                network,
                account.master_fingerprint,
                account.xpub,
                inputs,
            )?;
            fixture.describe();
            if let Some(dir) = &out_dir {
                std::fs::write(dir.join("unsigned.psbt"), fixture.psbt.to_string())?;
            }
            eprintln!("Check the outputs and fee on the Safe 7 against the above, then confirm…");
            let started = Instant::now();
            let signed = trezor
                .sign_psbt(&fixture.psbt, account.master_fingerprint)
                .await?;
            println!(
                "Safe 7 signed {inputs}/{inputs} inputs in {:.1} s (incl. confirming); signatures verified",
                started.elapsed().as_secs_f64()
            );
            if let Some(dir) = &out_dir {
                std::fs::write(dir.join("signed.psbt"), signed.to_string())?;
            }
            let tx = fixture.cosign_and_finalize(signed)?;
            println!(
                "Finalized 2-of-3: txid {}, {} vB (synthetic inputs: not broadcastable)",
                tx.compute_txid(),
                tx.vsize()
            );
        }
    }
    Ok(())
}

/// Reads the code from the terminal, or polls `file` for it.
struct CodeSource {
    file: Option<PathBuf>,
}

impl PairingCodeSource for CodeSource {
    fn pairing_code(&self) -> Pin<Box<dyn Future<Output = Option<String>> + Send + '_>> {
        Box::pin(async move {
            match &self.file {
                Some(file) => {
                    eprintln!(
                        "Waiting for the 6 digits shown on the Safe 7 in {}…",
                        file.display()
                    );
                    loop {
                        if let Ok(code) = std::fs::read_to_string(file)
                            && !code.trim().is_empty()
                        {
                            let _ = std::fs::remove_file(file);
                            return Some(code.trim().to_owned());
                        }
                        tokio::time::sleep(Duration::from_millis(250)).await;
                    }
                }
                None => {
                    eprint!("Type the 6 digits shown on the Safe 7 (not the PIN; empty cancels): ");
                    let mut line = String::new();
                    let mut stdin = tokio::io::BufReader::new(tokio::io::stdin());
                    stdin.read_line(&mut line).await.ok()?;
                    let code = line.trim().to_owned();
                    (!code.is_empty()).then_some(code)
                }
            }
        })
    }
}

/// The name the Safe 7 shows for this host when pairing; at most 32 bytes.
fn host_name() -> String {
    let name = std::env::var("HOSTNAME")
        .or_else(|_| std::env::var("COMPUTERNAME"))
        .unwrap_or_else(|_| "desktop".into());
    name.chars()
        .scan(0, |len, c| {
            *len += c.len_utf8();
            (*len <= 32).then_some(c)
        })
        .collect()
}

fn write_secret(file: &Path, bytes: &[u8]) -> std::io::Result<()> {
    use std::io::Write;
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create(true).truncate(true);
    #[cfg(unix)]
    std::os::unix::fs::OpenOptionsExt::mode(&mut options, 0o600);
    options.open(file)?.write_all(bytes)
}

/// `m/48'/<coin>'/0'/2'`, coin 0 on mainnet and 1 elsewhere, as `vault_core::keys` derives it.
fn multisig_account_path(network: Network) -> DerivationPath {
    let coin = if network == Network::Bitcoin { 0 } else { 1 };
    DerivationPath::from_str(&format!("m/48'/{coin}'/0'/2'")).expect("valid path")
}

/// The independent check: vault-core derives the same keys from the phrase
/// the Safe 7 holds, without the Safe 7.
fn verify_xpub(
    phrase_file: &Path,
    network: Network,
    path: &DerivationPath,
    account: &Xpub,
    fingerprint: bitcoin::bip32::Fingerprint,
) -> Result<(), Box<dyn Error>> {
    let phrase = std::fs::read_to_string(phrase_file)?;
    let mnemonic = Mnemonic::parse_normalized(phrase.trim())?;
    let account_index = match path.as_ref().get(2) {
        Some(ChildNumber::Hardened { index }) => *index,
        _ => return Err(format!("{path} has no hardened account").into()),
    };
    let (mut account_key, expected) =
        if path.as_ref().first() == Some(&ChildNumber::Hardened { index: 48 }) {
            account_multisig_xpub_from_mnemonic(
                &mnemonic,
                "",
                network,
                account_index,
                ScriptType::P2wsh,
            )?
        } else {
            account_xpub_from_mnemonic(&mnemonic, "", Purpose::Bip84, network, account_index)?
        };
    account_key.private_key.non_secure_erase();
    let secp = Secp256k1::new();
    let mut master = vault_core::keys::generate_master_xpriv(
        network,
        &vault_core::keys::generate_seed(&mnemonic, ""),
    )?;
    let expected_fingerprint = master.fingerprint(&secp);
    master.private_key.non_secure_erase();

    if fingerprint != expected_fingerprint || *account != expected {
        return Err(format!(
            "MISMATCH: the Safe 7 says {fingerprint} / {account}, the phrase gives {expected_fingerprint} / {expected}"
        )
        .into());
    }
    println!("✓ fingerprint and xpub match what vault-core derives from the phrase");
    Ok(())
}

fn read_psbt(file: &Path) -> Result<Psbt, Box<dyn Error>> {
    let bytes = std::fs::read(file)?;
    // Binary PSBTs start with the magic bytes "psbt\xff"; anything else is taken as base64.
    if bytes.starts_with(b"psbt\xff") {
        return Ok(Psbt::deserialize(&bytes)?);
    }
    Ok(Psbt::from_str(std::str::from_utf8(&bytes)?.trim())?)
}

fn trezor_worker_identity(
    features: &Safe7Identity,
    fingerprint: bitcoin::bip32::Fingerprint,
    accounts: Vec<(e2e::model::Family, bitcoin::bip32::Xpub)>,
) -> e2e::Result<e2e::records::Identity> {
    let physical_id = features
        .device_id
        .as_deref()
        .filter(|id| !id.trim().is_empty())
        .ok_or("Trezor GetFeatures device_id is missing or blank")?;
    Ok(e2e::identity_from_accounts(
        physical_id.to_owned(),
        features.firmware_version(),
        fingerprint,
        accounts,
    ))
}

#[derive(serde::Serialize)]
struct SignatureRejectionArtifact {
    schema: u32,
    transaction_id: String,
    firmware: String,
    fingerprint: String,
    input_index: usize,
    reason: String,
    signature_der: String,
    candidates: Vec<SignatureRejectionCandidate>,
}

#[derive(serde::Serialize)]
struct SignatureRejectionCandidate {
    input_index: usize,
    expected_public_key: String,
    expected_sighash: Option<String>,
    signature_verifies: bool,
}

fn signature_rejection_artifact(
    transaction_id: String,
    identity: &e2e::records::Identity,
    rejection: &EcdsaSignatureRejection,
) -> SignatureRejectionArtifact {
    SignatureRejectionArtifact {
        schema: 1,
        transaction_id,
        firmware: identity.firmware.clone(),
        fingerprint: identity.fingerprint.clone(),
        input_index: rejection.input_index(),
        reason: rejection.reason().to_string(),
        signature_der: rejection.signature_der().to_lower_hex_string(),
        candidates: rejection
            .candidates()
            .iter()
            .map(|candidate| SignatureRejectionCandidate {
                input_index: candidate.input_index(),
                expected_public_key: candidate.public_key().as_slice().to_lower_hex_string(),
                expected_sighash: candidate
                    .sighash()
                    .map(|sighash| sighash.as_slice().to_lower_hex_string()),
                signature_verifies: candidate.signature_verifies(),
            })
            .collect(),
    }
}

fn rejected_ecdsa_signature(error: &TrezorBleError) -> Option<&EcdsaSignatureRejection> {
    match error {
        TrezorBleError::Trezor(VaultCoreError::Trezor(TrezorError::RejectedSignature(
            rejection,
        ))) => Some(rejection),
        _ => None,
    }
}

fn build_source_sha256() -> String {
    let sources: &[(&str, &[u8])] = &[
        (
            "session.rs",
            include_bytes!("../../../vault-core/src/hw/trezor/session.rs"),
        ),
        (
            "signing_trace.rs",
            include_bytes!("../../../vault-core/src/hw/trezor/signing_trace.rs"),
        ),
        (
            "bitcoin.rs",
            include_bytes!("../../../vault-core/src/hw/trezor/bitcoin.rs"),
        ),
        (
            "thp.rs",
            include_bytes!("../../../vault-core/src/hw/trezor/thp.rs"),
        ),
        (
            "psbt_check.rs",
            include_bytes!("../../../vault-core/src/hw/psbt_check.rs"),
        ),
        ("trezor-ble/lib.rs", include_bytes!("../../src/lib.rs")),
        ("main.rs", include_bytes!("main.rs")),
        ("coordinator.rs", include_bytes!("coordinator.rs")),
    ];
    let mut engine = sha256::Hash::engine();
    for (name, bytes) in sources {
        engine.input(&(name.len() as u64).to_le_bytes());
        engine.input(name.as_bytes());
        engine.input(&(bytes.len() as u64).to_le_bytes());
        engine.input(bytes);
    }
    sha256::Hash::from_engine(engine).to_string()
}

fn running_executable_sha256() -> e2e::Result<String> {
    use std::io::Read;
    #[cfg(target_os = "linux")]
    let mut executable = std::fs::File::open("/proc/self/exe")?;
    #[cfg(not(target_os = "linux"))]
    let mut executable = std::fs::File::open(std::env::current_exe()?)?;
    let mut engine = sha256::Hash::engine();
    let mut buffer = [0u8; 65536];
    loop {
        let count = executable.read(&mut buffer)?;
        if count == 0 {
            break;
        }
        engine.input(&buffer[..count]);
    }
    Ok(sha256::Hash::from_engine(engine).to_string())
}

fn persist_signing_trace(
    response: &Path,
    psbt: &Psbt,
    identity: &e2e::records::Identity,
    trace: &SigningTrace,
    executable_sha256: &str,
) -> e2e::Result<()> {
    let runtime_git_revision = std::process::Command::new("git")
        .args(["rev-parse", "HEAD"])
        .output()
        .ok()
        .filter(|output| output.status.success())
        .and_then(|output| String::from_utf8(output.stdout).ok())
        .map(|revision| revision.trim().to_owned());
    let frames: Vec<_> = trace.frames().iter().enumerate().map(|(sequence, frame)| {
        serde_json::json!({
            "sequence": sequence, "outbound": frame.outbound(), "session": 1,
            "message_type": frame.message_type(), "payload": frame.payload().to_lower_hex_string(),
        })
    }).collect();
    let artifact = serde_json::json!({
        "schema": 1, "psbt_sha256": sha256::Hash::hash(&psbt.serialize()).to_string(),
        "transaction_id": psbt.unsigned_tx.compute_txid().to_string(),
        "firmware": identity.firmware, "fingerprint": identity.fingerprint,
        "physical_id": identity.physical_id,
        "build_source_sha256": build_source_sha256(),
        "build_source_scope": "length-framed names and bytes: session.rs,signing_trace.rs,bitcoin.rs,thp.rs,psbt_check.rs,trezor-ble/lib.rs,main.rs,coordinator.rs",
        "executable_sha256": executable_sha256,
        "runtime_git_revision": runtime_git_revision,
        "handoff": "THP plaintext acceptance, not radio delivery",
        "incomplete": trace.incomplete(), "end": format!("{:?}", trace.end()),
        "frames": frames,
    });
    let bytes = zeroize::Zeroizing::new(serde_json::to_vec_pretty(&artifact)?);
    if bytes.len() > 16 * 1024 * 1024 {
        return Err("private signing trace exceeds artifact limit".into());
    }
    let path = response.with_extension("signing-trace.json");
    e2e::records::atomic_write(&path, &bytes)?;
    eprintln!("Private signing trace saved to {}", path.display());
    Ok(())
}

async fn run_device(
    args: &Args,
    request: e2e::records::WorkerRequest,
    response: PathBuf,
    default_credential: Option<&Path>,
) -> e2e::Result<()> {
    use e2e::records::{DeviceAction, DeviceResult, WorkerResponse};
    let credential_file = args.credential_file.as_deref().or(default_credential);
    if let Some(file) = credential_file
        && file.exists()
    {
        e2e::wallet::require_private(file)?;
    }
    let credential = match credential_file {
        Some(file) if file.exists() => Some(PairingCredential::from_bytes(&std::fs::read(file)?)?),
        _ => None,
    };
    let options = ConnectOptions {
        network: Network::Testnet4,
        host_name: host_name(),
        app_name: "mebit testnet4 e2e".into(),
        passphrase: if args.passphrase_on_device {
            SessionPassphrase::OnDevice
        } else {
            SessionPassphrase::None
        },
        credential,
        device: args.device.clone(),
        scan: Duration::from_secs(args.scan_secs),
    };
    eprintln!("Wake the enrolled Safe 7 and approve the testnet4 request on its screen.");
    let connected = TrezorBle::connect(
        options,
        &CodeSource {
            file: args.code_file.clone(),
        },
    )
    .await?;
    if let Some(credential) = connected.new_credential
        && let Some(file) = credential_file
    {
        e2e::records::atomic_write(file, &credential.to_bytes())?;
    }
    let mut device = connected.device;
    let outcome = async {
        let mut fingerprint = None;
        let mut accounts = Vec::new();
        for family in e2e::model::Family::ALL {
            let key = device.xpub(&family.path(), XpubOptions::default()).await?;
            if fingerprint.is_some_and(|fp| fp != key.master_fingerprint) {
                return Err("device returned inconsistent master fingerprints".into());
            }
            fingerprint = Some(key.master_fingerprint);
            accounts.push((family, key.xpub));
        }
        let identity = trezor_worker_identity(
            device.identity(),
            fingerprint.ok_or("missing fingerprint")?,
            accounts,
        )?;
        if let Some(expected) = &request.expected {
            e2e::records::check_identity(expected, &identity)?;
        }
        let result = match request.action {
            DeviceAction::Enroll => DeviceResult::Enrolled,
            DeviceAction::Verify {
                descriptor,
                index,
                signer,
                ..
            } => {
                if signer != identity.fingerprint {
                    return Err("address signer differs from enrolled device".into());
                }
                let descriptor =
                    miniscript::Descriptor::<miniscript::DescriptorPublicKey>::from_str(
                        &descriptor,
                    )?;
                let address = device
                    .verify_address(&vault_core::hw::AddressRequest {
                        descriptor: &descriptor,
                        index,
                        signer: signer.parse()?,
                    })
                    .await?;
                DeviceResult::Address(address.to_string())
            }
            DeviceAction::Sign { psbt, signer } => {
                if signer != identity.fingerprint {
                    return Err("PSBT signer differs from enrolled device".into());
                }
                let psbt: Psbt = psbt.parse()?;
                let transaction_id = psbt.unsigned_tx.compute_txid().to_string();
                let outcome = if args.signing_trace_worker {
                    let executable_sha256 = running_executable_sha256()?;
                    let (outcome, trace) = device.sign_psbt_traced(&psbt, signer.parse()?).await;
                    if let Some(trace) = trace {
                        persist_signing_trace(
                            &response,
                            &psbt,
                            &identity,
                            &trace,
                            &executable_sha256,
                        )?;
                    }
                    outcome
                } else {
                    device.sign_psbt(&psbt, signer.parse()?).await
                };
                match outcome {
                    Ok(signed) => DeviceResult::Signed(signed.to_string()),
                    Err(error) => {
                        if let Some(rejection) = rejected_ecdsa_signature(&error) {
                            let artifact = signature_rejection_artifact(
                                transaction_id,
                                &identity,
                                rejection,
                            );
                            let path = response.with_extension("signature-rejection.json");
                            match e2e::records::write_json(&path, &artifact) {
                                Ok(()) => eprintln!(
                                    "Private signature diagnostic saved to {}",
                                    path.display()
                                ),
                                Err(write_error) => eprintln!(
                                    "Could not save the private signature diagnostic to {}: {write_error}",
                                    path.display()
                                ),
                            }
                        }
                        return Err(error.into());
                    }
                }
            }
        };
        Ok::<_, e2e::Error>(WorkerResponse { identity, result })
    }
    .await;
    let closed = device.disconnect().await;
    let result = outcome?;
    closed?;
    e2e::records::write_json(&response, &result)
}

async fn run_remote_device(
    args: &Args,
    request: e2e::records::RemoteRequest,
    response_path: PathBuf,
    default_credential: &Path,
) -> e2e::Result<()> {
    use e2e::records::{RemoteResponse, WorkerRequest, WorkerResponse};
    e2e::records::validate_remote_request(&request)?;
    let worker_response_path = response_path.with_extension("worker-response.json");
    if worker_response_path.exists() {
        std::fs::remove_file(&worker_response_path)?;
    }
    let worker_request = WorkerRequest {
        action: request.action.clone(),
        expected: Some(request.expected.clone()),
    };
    run_device(
        args,
        worker_request,
        worker_response_path.clone(),
        Some(default_credential),
    )
    .await?;
    let worker_response: WorkerResponse = e2e::records::read_json(&worker_response_path)?;
    std::fs::remove_file(&worker_response_path)?;
    let remote_response = RemoteResponse {
        schema: 1,
        request_id: request.request_id,
        case_id: request.case_id,
        slot: request.slot,
        response: worker_response,
    };
    e2e::records::write_json(&response_path, &remote_response)?;
    println!("Remote response written to {}.", response_path.display());
    Ok(())
}

#[cfg(test)]
mod trezor_identity {
    use bitcoin::bip32::{Fingerprint, Xpriv};

    use super::*;
    use e2e::model::Family;

    #[tokio::test]
    async fn private_trace_flag_refuses_nonworker_before_opening_ble() {
        for command in [
            vec!["info"],
            vec![
                "e2e",
                "diagnose-sign",
                "--case",
                "synthetic",
                "--trace-signing",
            ],
        ] {
            let mut arguments = vec!["trezor-hw-test", "--signing-trace-worker"];
            arguments.extend(command);
            let args = Args::try_parse_from(arguments).unwrap();
            assert_eq!(
                run(args).await.unwrap_err().to_string(),
                "private trace flag requires a signing worker"
            );
        }
        let args = Args::try_parse_from([
            "trezor-hw-test",
            "--network",
            "testnet4",
            "e2e",
            "diagnose-sign",
            "--case",
            "synthetic",
            "--trace-signing",
        ])
        .unwrap();
        assert!(!args.signing_trace_worker);
        assert!(matches!(
            args.command,
            Command::E2e {
                command: E2eCommand::DiagnoseSign {
                    trace_signing: true,
                    ..
                }
            }
        ));
    }

    fn features(device_id: Option<&str>) -> Safe7Identity {
        Safe7Identity {
            model: "Safe 7".into(),
            internal_model: "T3W1".into(),
            firmware: (2, 9, 3),
            device_id: device_id.map(str::to_owned),
            label: Some("Synthetic test device".into()),
        }
    }

    fn account_keys(seed: u8) -> (Fingerprint, Vec<(Family, Xpub)>) {
        let secp = Secp256k1::new();
        let master = Xpriv::new_master(Network::Testnet4, &[seed; 32]).unwrap();
        let accounts = Family::ALL
            .into_iter()
            .map(|family| {
                let account = master.derive_priv(&secp, &family.path()).unwrap();
                (family, Xpub::from_priv(&secp, &account))
            })
            .collect();
        (master.fingerprint(&secp), accounts)
    }

    fn enrolled_identity() -> e2e::records::Identity {
        let (fingerprint, accounts) = account_keys(0x11);
        trezor_worker_identity(&features(Some("Synthetic-Safe7-A")), fingerprint, accounts).unwrap()
    }

    #[test]
    fn selects_exact_getfeatures_id() {
        let stable_id = " \tSynthetic-Safe7-A\u{00a0} ";
        let (fingerprint, accounts) = account_keys(0x11);
        let identity =
            trezor_worker_identity(&features(Some(stable_id)), fingerprint, accounts.clone())
                .unwrap();

        assert_eq!(identity.physical_id, stable_id);
        assert_eq!(identity.firmware, "2.9.3");
        assert_eq!(identity.fingerprint, fingerprint.to_string());
        assert_eq!(identity.accounts.len(), Family::ALL.len());
        for (family, xpub) in accounts {
            let account = &identity.accounts[&family];
            assert_eq!(account.origin, format!("m/{}", family.path()));
            assert_eq!(account.xpub, xpub.to_string());
        }
    }

    #[test]
    fn separate_connection_snapshots_match_same_stable_id_and_keys() {
        let first_features = features(Some("Synthetic-Safe7-A"));
        let mut second_features = features(Some("Synthetic-Safe7-A"));
        second_features.label = Some("Renamed synthetic test device".into());
        second_features.firmware = (2, 10, 0);
        let (first_fingerprint, first_accounts) = account_keys(0x11);
        let (second_fingerprint, second_accounts) = account_keys(0x11);

        let first =
            trezor_worker_identity(&first_features, first_fingerprint, first_accounts).unwrap();
        let second =
            trezor_worker_identity(&second_features, second_fingerprint, second_accounts).unwrap();

        assert_eq!(first.physical_id, "Synthetic-Safe7-A");
        assert_eq!(second.physical_id, "Synthetic-Safe7-A");
        assert_eq!(second.firmware, "2.10.0");
        e2e::records::check_identity(&first, &second).unwrap();
        e2e::records::check_identity(&second, &first).unwrap();
    }

    #[test]
    fn rejects_missing_or_blank_getfeatures_id() {
        let (fingerprint, accounts) = account_keys(0x11);
        for device_id in [
            None,
            Some(""),
            Some(" "),
            Some("\t\r\n"),
            Some("\u{a0}\u{2003}"),
        ] {
            let error = trezor_worker_identity(&features(device_id), fingerprint, accounts.clone())
                .unwrap_err();
            assert_eq!(
                error.to_string(),
                "Trezor GetFeatures device_id is missing or blank",
                "device_id {device_id:?}"
            );
        }
    }

    #[test]
    fn rejects_changed_stable_id() {
        let expected = enrolled_identity();
        let (fingerprint, accounts) = account_keys(0x11);
        let actual =
            trezor_worker_identity(&features(Some("Synthetic-Safe7-B")), fingerprint, accounts)
                .unwrap();

        assert!(e2e::records::check_identity(&expected, &actual).is_err());
    }

    #[test]
    fn rejects_changed_fingerprint() {
        let expected = enrolled_identity();
        let (_, accounts) = account_keys(0x11);
        let (other_fingerprint, _) = account_keys(0x22);
        let actual = trezor_worker_identity(
            &features(Some("Synthetic-Safe7-A")),
            other_fingerprint,
            accounts,
        )
        .unwrap();

        assert!(e2e::records::check_identity(&expected, &actual).is_err());
    }

    #[test]
    fn rejects_changed_origin() {
        let expected = enrolled_identity();
        let mut actual = enrolled_identity();
        actual.accounts.get_mut(&Family::Bip84).unwrap().origin = "m/84'/1'/1'".into();

        assert!(e2e::records::check_identity(&expected, &actual).is_err());
    }

    #[test]
    fn rejects_changed_xpub() {
        let expected = enrolled_identity();
        let (fingerprint, mut accounts) = account_keys(0x11);
        let (_, other_accounts) = account_keys(0x22);
        accounts[0].1 = other_accounts[0].1;
        let actual =
            trezor_worker_identity(&features(Some("Synthetic-Safe7-A")), fingerprint, accounts)
                .unwrap();

        assert!(e2e::records::check_identity(&expected, &actual).is_err());
    }

    #[test]
    fn rejects_missing_account() {
        let expected = enrolled_identity();
        let (fingerprint, accounts) = account_keys(0x11);
        for missing_family in Family::ALL {
            let remaining_accounts = accounts
                .iter()
                .copied()
                .filter(|(family, _)| *family != missing_family)
                .collect();
            let actual = trezor_worker_identity(
                &features(Some("Synthetic-Safe7-A")),
                fingerprint,
                remaining_accounts,
            )
            .unwrap();

            assert!(
                e2e::records::check_identity(&expected, &actual).is_err(),
                "missing {missing_family:?}"
            );
        }
    }
}

#[cfg(all(test, target_os = "linux"))]
mod trezor_executable_identity {
    use super::*;

    #[test]
    fn replacement_probe_child() {
        let Some(root) = std::env::var_os("TREZOR_EXECUTABLE_IDENTITY_PROBE") else {
            return;
        };
        let root = PathBuf::from(root);
        let executable = std::env::current_exe().unwrap();
        assert_eq!(executable, root.join("trace-worker"));
        let replacement = root.join("replacement");
        std::fs::write(&replacement, b"replacement image").unwrap();
        std::fs::rename(&replacement, &executable).unwrap();
        let digest = running_executable_sha256().unwrap();
        e2e::records::write_json(
            &root.join("proof.json"),
            &serde_json::json!({"executable_sha256": digest}),
        )
        .unwrap();
    }

    #[test]
    fn hashes_running_image_after_executable_path_is_replaced() {
        let executable = std::env::current_exe().unwrap();
        let expected = sha256::Hash::hash(&std::fs::read(&executable).unwrap()).to_string();
        let nonce = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let root = std::env::temp_dir().join(format!(
            "trezor-executable-identity-{}-{nonce}",
            std::process::id()
        ));
        std::fs::create_dir(&root).unwrap();
        let worker = root.join("trace-worker");
        std::fs::copy(executable, &worker).unwrap();
        let output = std::process::Command::new(&worker)
            .args([
                "--exact",
                "trezor_executable_identity::replacement_probe_child",
                "--nocapture",
            ])
            .env("TREZOR_EXECUTABLE_IDENTITY_PROBE", &root)
            .output()
            .unwrap();
        assert!(
            output.status.success(),
            "replacement worker failed: {}{}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        );
        let proof: serde_json::Value = e2e::records::read_json(&root.join("proof.json")).unwrap();
        assert_eq!(proof["executable_sha256"], expected);
        assert_ne!(
            proof["executable_sha256"],
            sha256::Hash::hash(&std::fs::read(&worker).unwrap()).to_string()
        );
        std::fs::remove_file(root.join("proof.json")).unwrap();
        std::fs::remove_file(worker).unwrap();
        std::fs::remove_dir(root).unwrap();
    }
}

#[cfg(test)]
mod trezor_signature_rejection {
    use super::*;

    #[test]
    fn writes_signature_diagnostics_only_to_private_json() {
        let nonce = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let root = std::env::temp_dir().join(format!(
            "trezor-signature-diagnostic-{}-{nonce}",
            std::process::id()
        ));
        let path = root
            .join("private/workers")
            .join("sign-response.signature-rejection.json");
        let artifact = SignatureRejectionArtifact {
            schema: 1,
            transaction_id: "txid".into(),
            firmware: "2.12.5".into(),
            fingerprint: "73c5da0a".into(),
            input_index: 0,
            reason: "host verification failed".into(),
            signature_der: "300100".into(),
            candidates: vec![SignatureRejectionCandidate {
                input_index: 0,
                expected_public_key: "02".to_owned() + &"11".repeat(32),
                expected_sighash: Some("22".repeat(32)),
                signature_verifies: false,
            }],
        };

        e2e::records::write_json(&path, &artifact).unwrap();
        let saved: serde_json::Value = e2e::records::read_json(&path).unwrap();
        assert_eq!(saved["signature_der"], "300100");
        assert_eq!(saved["candidates"][0]["input_index"], 0);
        assert_eq!(saved["candidates"][0]["signature_verifies"], false);
        assert!(saved.get("psbt").is_none());
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(
                std::fs::metadata(&path).unwrap().permissions().mode() & 0o777,
                0o600
            );
            assert_eq!(
                std::fs::metadata(path.parent().unwrap())
                    .unwrap()
                    .permissions()
                    .mode()
                    & 0o777,
                0o700
            );
        }
        std::fs::remove_dir_all(root).unwrap();
    }
}
