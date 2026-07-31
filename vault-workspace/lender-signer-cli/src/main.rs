//! Offline (air-gapped) CLI for the lender/fund representative: fetch a
//! pending PSBT, inspect its contents, sign it offline. See
//! `docs/00-capstone-brief.md` §3.4.

use clap::{Parser, Subcommand};
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::{Path, PathBuf};
use vault_core::psbt::UnsignedPsbt;

#[derive(Parser)]
#[command(name = "lender-signer-cli", about = "Offline signing CLI for the lender/fund representative")]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Fetch a signing request from a running custody-service instance
    Fetch {
        /// Signing request id
        id: u64,
        /// Base URL of custody-service
        #[arg(long, default_value = "http://127.0.0.1:8080")]
        url: String,
    },
    /// Inspect a PSBT file before signing it — always do this before `sign`
    Inspect { file: PathBuf },
    /// Sign a PSBT file offline and write the result (stdout if --out omitted)
    Sign {
        file: PathBuf,
        #[arg(long)]
        out: Option<PathBuf>,
    },
}

/// Mock signed-PSBT envelope. Real offline signing belongs in vault-core
/// once its PSBT-signing interface exists — this exists so the
/// fetch/inspect/sign workflow can be exercised end-to-end before that lands.
#[derive(Debug, Serialize, Deserialize)]
struct SignedPsbt {
    psbt: UnsignedPsbt,
    signature: String,
}

fn main() {
    let cli = Cli::parse();
    match cli.command {
        Command::Fetch { url, id } => fetch(&url, id),
        Command::Inspect { file } => inspect(&file),
        Command::Sign { file, out } => sign(&file, out.as_deref()),
    }
}

fn fetch(base_url: &str, id: u64) {
    let url = format!("{base_url}/signing-requests/{id}");
    match ureq::get(&url).call() {
        Ok(mut response) => match response.body_mut().read_json::<serde_json::Value>() {
            Ok(value) => println!("{}", serde_json::to_string_pretty(&value).unwrap()),
            Err(e) => eprintln!("error: failed to parse response from {url}: {e}"),
        },
        Err(e) => eprintln!("error: failed to fetch {url}: {e}"),
    }
}

fn inspect(file: &Path) {
    let psbt = match load_psbt(file) {
        Ok(p) => p,
        Err(e) => return eprintln!("error: {e}"),
    };
    println!("loan_id:  {}", psbt.loan_id);
    println!("inputs:   {}", psbt.inputs.len());
    for input in &psbt.inputs {
        println!("  - {input}");
    }
    println!("outputs:  {}", psbt.outputs.len());
    for output in &psbt.outputs {
        println!("  - {} sats -> {}", output.amount_sats, output.address);
    }
}

fn sign(file: &Path, out: Option<&Path>) {
    let psbt = match load_psbt(file) {
        Ok(p) => p,
        Err(e) => return eprintln!("error: {e}"),
    };
    let signed = SignedPsbt {
        signature: format!("MOCK_SIG[{}:{}]", psbt.loan_id, psbt.outputs.len()),
        psbt,
    };
    let json = serde_json::to_string_pretty(&signed).expect("SignedPsbt always serializes");
    match out {
        Some(path) => match fs::write(path, &json) {
            Ok(()) => println!("wrote signed PSBT to {}", path.display()),
            Err(e) => eprintln!("error: failed to write {}: {e}", path.display()),
        },
        None => println!("{json}"),
    }
}

fn load_psbt(file: &Path) -> Result<UnsignedPsbt, String> {
    let contents = fs::read_to_string(file).map_err(|e| format!("failed to read {}: {e}", file.display()))?;
    serde_json::from_str(&contents).map_err(|e| format!("failed to parse {}: {e}", file.display()))
}
