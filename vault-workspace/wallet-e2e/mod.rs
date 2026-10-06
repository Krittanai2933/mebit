pub mod chain;
pub mod model;
pub mod records;
pub mod report;
pub mod wallet;

pub type Error = Box<dyn std::error::Error>;
pub type Result<T> = std::result::Result<T, Error>;

pub fn offline_command(command: &model::Command, store: &records::Store) -> Result<bool> {
    match command {
        model::Command::Matrix => {
            let cases = model::matrix();
            let manifest = serde_json::json!({"schema":1,"network":"testnet4","required_cases":cases.len(),"single_sig":12,"multisig":7303,"live_status":"BLOCKED","cases":cases});
            let path = store.root.join("manifest.json");
            records::write_json(&path, &manifest)?;
            println!(
                "{} required cases, 12 single-sig and 7303 multisig. Live acceptance BLOCKED.",
                model::CASE_COUNT
            );
            println!("{}", path.display());
        }
        model::Command::Offline => {
            eprintln!(
                "Checking scripts, origins, software signatures, and finalization without devices or HTTP."
            );
            let result = wallet::offline_check()?;
            records::write_json(&store.root.join("offline.json"), &result)?;
            println!("{}", serde_json::to_string_pretty(&result)?);
        }
        model::Command::Report => {
            let report = report::collect(store, &chain::Esplora::default())?;
            let pass = report["live_pass"]
                .as_u64()
                .ok_or("report has no live pass count")?;
            records::write_json(&store.root.join("report.json"), &report)?;
            println!(
                "Overall {}. Confirmed live PASS {pass}/{}. Offline {}.",
                report["overall"].as_str().unwrap_or("BLOCKED"),
                model::CASE_COUNT,
                report["offline"]["status"].as_str().unwrap_or("NOT_RUN")
            );
            println!("{}", store.root.join("report.json").display());
        }
        _ => return Ok(false),
    }
    Ok(true)
}

pub fn identity_from_accounts(
    physical_id: String,
    firmware: String,
    fingerprint: bitcoin::bip32::Fingerprint,
    accounts: Vec<(model::Family, bitcoin::bip32::Xpub)>,
) -> records::Identity {
    records::Identity {
        physical_id,
        firmware,
        fingerprint: fingerprint.to_string(),
        accounts: accounts
            .into_iter()
            .map(|(family, xpub)| {
                (
                    family,
                    records::Account {
                        origin: format!("m/{}", family.path()),
                        xpub: xpub.to_string(),
                    },
                )
            })
            .collect(),
    }
}
