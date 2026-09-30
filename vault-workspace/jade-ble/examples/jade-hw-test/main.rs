//! Phase-0 hardware harness: drives a real Jade over BLE through `jade-ble`.
//! A test tool, not product code; `jade-ble/README.md` has the checklist.
//!
//! ```text
//! cargo run -p jade-ble --example jade-hw-test -- info
//! cargo run -p jade-ble --example jade-hw-test -- xpub --verify-against phrase.txt
//! cargo run -p jade-ble --example jade-hw-test -- roundtrip --inputs 16
//! ```

mod roundtrip;

use std::error::Error;
use std::path::{Path, PathBuf};
use std::process::ExitCode;
use std::str::FromStr;
use std::time::{Duration, Instant};

use bip39::Mnemonic;
use bitcoin::bip32::{DerivationPath, Xpub};
use bitcoin::secp256k1::Secp256k1;
use bitcoin::{Network, Psbt};
use clap::{Parser, Subcommand};
use jade_ble::JadeBle;
use vault_core::keys::{
    HwVendor, KeySourceType, ScriptType, VaultKey, account_multisig_xpub_from_mnemonic,
    generate_master_xpriv, generate_seed,
};

#[derive(Parser)]
struct Args {
    /// bitcoin, testnet, testnet4, signet or regtest
    #[arg(long, default_value = "testnet")]
    network: Network,

    /// Which Jade, when several are in range: its name or the end of it
    #[arg(long)]
    name: Option<String>,

    #[arg(long, default_value_t = 5)]
    scan_secs: u64,

    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Firmware, board, state and network pin. No unlock.
    Info,
    /// Unlock, then print the master fingerprint and the xpub at --path.
    Xpub {
        /// Default: the BIP-48 P2WSH multisig account, m/48'/<coin>'/0'/2'
        #[arg(long)]
        path: Option<DerivationPath>,
        /// A file holding the Jade's (test!) recovery phrase: re-derive the
        /// xpub with vault-core and require it to match.
        #[arg(long)]
        verify_against: Option<PathBuf>,
    },
    /// Unlock, sign a PSBT file (base64 or binary), write the result as base64.
    Sign {
        #[arg(long)]
        psbt: PathBuf,
        #[arg(long)]
        out: Option<PathBuf>,
    },
    /// Build a 2-of-3 vault spend with synthetic inputs, have the Jade sign
    /// it, co-sign with a software key, and finalize it. Test networks only.
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
    eprintln!("Scanning {} s for a Jade…", args.scan_secs);
    let scan = Duration::from_secs(args.scan_secs);
    let mut jade = JadeBle::connect(args.network, args.name.as_deref(), scan).await?;
    eprintln!("Connected to {}", jade.name());
    let outcome = command(&mut jade, args.network, args.command).await;
    let closed = jade.disconnect().await;
    outcome?;
    Ok(closed?)
}

async fn command(
    jade: &mut JadeBle,
    network: Network,
    command: Command,
) -> Result<(), Box<dyn Error>> {
    match command {
        Command::Info => {
            let info = jade.version_info().await?;
            println!("firmware  {}", info.firmware);
            println!("board     {}", info.board);
            println!("config    {}", info.config);
            println!("state     {:?}", info.state);
            println!("networks  {}", info.networks);
        }
        Command::Xpub {
            path,
            verify_against,
        } => {
            let path = path.unwrap_or_else(|| multisig_account_path(network));
            unlock(jade).await?;
            let master = jade.xpub(&DerivationPath::master()).await?;
            let account = jade.xpub(&path).await?;
            let key = VaultKey {
                label: jade.name().to_owned(),
                source_type: KeySourceType::HardwareWallet(HwVendor::Jade),
                fingerprint: master.fingerprint(),
                xpub: account,
                // `VaultKey` writes paths with the `m/` that rust-bitcoin's Display leaves out.
                derivation_path: format!("m/{path}"),
            };
            println!("fingerprint  {}", key.fingerprint);
            println!("path         {}", key.derivation_path);
            println!("xpub         {account}");
            println!("descriptor   [{}/{path}]{account}", key.fingerprint);
            println!("{key:#?}");
            if let Some(phrase_file) = verify_against {
                verify_xpub(&phrase_file, network, &path, &master, &account)?;
            }
        }
        Command::Sign { psbt, out } => {
            let psbt = read_psbt(&psbt)?;
            unlock(jade).await?;
            let signer = jade.xpub(&DerivationPath::master()).await?.fingerprint();
            eprintln!("Check the outputs and fee on the Jade, then confirm…");
            let signed = jade.sign_psbt(&psbt, signer).await?;
            eprintln!("Signed and verified.");
            match out {
                Some(out) => std::fs::write(out, signed.to_string())?,
                None => println!("{signed}"),
            }
        }
        Command::Roundtrip { inputs, out_dir } => {
            if network == Network::Bitcoin {
                return Err(
                    "roundtrip builds throwaway test transactions: use a test network".into(),
                );
            }
            unlock(jade).await?;
            let master = jade.xpub(&DerivationPath::master()).await?;
            let account = jade.xpub(&multisig_account_path(network)).await?;
            let fixture =
                roundtrip::Fixture::build(network, master.fingerprint(), account, inputs)?;
            fixture.describe();
            if let Some(dir) = &out_dir {
                std::fs::write(dir.join("unsigned.psbt"), fixture.psbt.to_string())?;
            }
            eprintln!("Check the outputs and fee on the Jade against the above, then confirm…");
            let started = Instant::now();
            let signed = jade.sign_psbt(&fixture.psbt, master.fingerprint()).await?;
            println!(
                "Jade signed {inputs}/{inputs} inputs in {:.1} s (incl. confirming); signatures verified",
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

async fn unlock(jade: &mut JadeBle) -> Result<(), Box<dyn Error>> {
    // Printed before the network-pin check inside `unlock()`, which may refuse
    // without the Jade ever asking: so "if", not "when".
    eprintln!(
        "Unlocking… if the Jade asks for its PIN, enter it there (a freshly restored Jade asks you to choose one)."
    );
    jade.unlock().await?;
    eprintln!("Unlocked.");
    Ok(())
}

/// `m/48'/<coin>'/0'/2'`, coin 0 on mainnet and 1 elsewhere, as `vault_core::keys` derives it.
fn multisig_account_path(network: Network) -> DerivationPath {
    let coin = if network == Network::Bitcoin { 0 } else { 1 };
    DerivationPath::from_str(&format!("m/48'/{coin}'/0'/2'")).expect("valid path")
}

/// The independent check: vault-core derives the same keys from the phrase
/// the Jade holds, without the Jade.
fn verify_xpub(
    phrase_file: &Path,
    network: Network,
    path: &DerivationPath,
    master: &Xpub,
    account: &Xpub,
) -> Result<(), Box<dyn Error>> {
    let phrase = std::fs::read_to_string(phrase_file)?;
    let mnemonic = Mnemonic::parse_normalized(phrase.trim())?;
    let secp = Secp256k1::new();
    let mut expected_master = generate_master_xpriv(network, &generate_seed(&mnemonic, ""))?;
    let expected = Xpub::from_priv(&secp, &expected_master.derive_priv(&secp, path)?);
    let expected_fingerprint = expected_master.fingerprint(&secp);
    expected_master.private_key.non_secure_erase();

    if master.fingerprint() != expected_fingerprint || *account != expected {
        return Err(format!(
            "MISMATCH: the Jade says {} / {account}, the phrase gives {expected_fingerprint} / {expected}",
            master.fingerprint()
        )
        .into());
    }
    println!("✓ fingerprint and xpub match what vault-core derives from the phrase");
    if *path == multisig_account_path(network) {
        let (_, bip48) =
            account_multisig_xpub_from_mnemonic(&mnemonic, "", network, 0, ScriptType::P2wsh)?;
        if bip48 != *account {
            return Err(format!(
                "MISMATCH with keys::account_multisig_xpub_from_mnemonic: {bip48}"
            )
            .into());
        }
        println!("✓ and what keys::account_multisig_xpub_from_mnemonic derives");
    }
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
