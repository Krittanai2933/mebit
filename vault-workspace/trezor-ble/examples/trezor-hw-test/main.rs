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
use bitcoin::secp256k1::Secp256k1;
use bitcoin::{Network, Psbt};
use clap::{Parser, Subcommand};
use tokio::io::AsyncBufReadExt;
use trezor_ble::{ConnectOptions, PairingCodeSource, TrezorBle};
use vault_core::hw::trezor::{PairingCredential, SessionPassphrase, XpubOptions};
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

    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
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

async fn run(args: Args) -> Result<(), Box<dyn Error>> {
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

async fn command(
    trezor: &mut TrezorBle,
    network: Network,
    command: Command,
) -> Result<(), Box<dyn Error>> {
    match command {
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
