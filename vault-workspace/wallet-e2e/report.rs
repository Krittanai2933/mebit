use bitcoin::consensus::deserialize;
use bitcoin::hex::FromHex;
use bitcoin::{OutPoint, Transaction};
use serde_json::{Value, json};

use super::chain::{Chain, Reconciled, reconcile};
use super::model::{CASE_COUNT, Case, manifest, supplemental_cases};
use super::records::{
    Checkpoint, Confirmation, Progress, Store, now, public_result, read_json, sanitized_error,
};
use super::{Result, wallet};

fn confirmed_pass(
    chain: &impl Chain,
    checkpoint: &Checkpoint,
) -> Result<(Confirmation, Confirmation)> {
    let Progress::Pass {
        finalized,
        confirmation,
    } = &checkpoint.progress
    else {
        return Err("public result has no matching private PASS checkpoint".into());
    };
    if !checkpoint.blockers.is_empty()
        || checkpoint.genesis_verified_at == 0
        || confirmation.confirmations == 0
        || confirmation.checked_at == 0
    {
        return Err("saved PASS checkpoint has incomplete acceptance evidence".into());
    }
    wallet::validate_checkpoint(checkpoint)?;
    let expected = wallet::validate_finalized(checkpoint, finalized)?;
    let funding = &finalized.signing.funding;
    let outpoint: OutPoint = funding.outpoint.parse()?;
    let previous: Transaction = deserialize(&Vec::<u8>::from_hex(&funding.transaction_hex)?)?;
    if chain.transaction(outpoint.txid)?.as_ref() != Some(&previous) {
        return Err("funding transaction contents no longer match the PASS checkpoint".into());
    }
    let current_funding = chain
        .confirmation(outpoint.txid)?
        .filter(|c| c.confirmations >= 1 && c.checked_at > 0)
        .ok_or("funding no longer has a canonical confirmation")?;
    match reconcile(chain, &expected)? {
        Reconciled::Confirmed(current) if current.confirmations >= 1 && current.checked_at > 0 => {
            Ok((current_funding, current))
        }
        Reconciled::Confirmed(_) | Reconciled::Pending => {
            Err("saved spend no longer has a canonical confirmation".into())
        }
        Reconciled::Unspent => {
            Err("saved PASS spend is absent and its funding input is unspent".into())
        }
    }
}

pub fn collect(store: &Store, chain: &impl Chain) -> Result<Value> {
    let mut results = Vec::with_capacity(CASE_COUNT);
    let mut live_pass = 0;
    let mut recorded_pass = 0;
    let mut network: Option<std::result::Result<u64, String>> = None;
    for case in manifest() {
        let (result, recorded, live) = collect_case_result(store, chain, case, &mut network);
        recorded_pass += usize::from(recorded);
        live_pass += usize::from(live);
        results.push(result);
    }
    let mut supplemental_results = Vec::with_capacity(supplemental_cases().len());
    let mut supplemental_live_pass = 0;
    let mut supplemental_recorded_pass = 0;
    for case in supplemental_cases() {
        let (result, recorded, live) = collect_case_result(store, chain, case, &mut network);
        supplemental_recorded_pass += usize::from(recorded);
        supplemental_live_pass += usize::from(live);
        supplemental_results.push(result);
    }
    let readiness = store.root.join("offline.json");
    Ok(json!({
        "overall":if live_pass==CASE_COUNT{"PASS"}else{"BLOCKED"},
        "reported_at":now(),"required_cases":CASE_COUNT,"live_pass":live_pass,
        "recorded_live_pass":recorded_pass,"remaining":CASE_COUNT-live_pass,
        "supplemental_live_pass":supplemental_live_pass,
        "supplemental_recorded_pass":supplemental_recorded_pass,
        "supplemental_cases":supplemental_results,
        "genesis_verified_at":network.and_then(|r|r.ok()),
        "offline":if readiness.exists(){read_json::<Value>(&readiness)?}else{json!({"status":"NOT_RUN"})},
        "cases":results
    }))
}

fn collect_case_result(
    store: &Store,
    chain: &impl Chain,
    case: &Case,
    network: &mut Option<std::result::Result<u64, String>>,
) -> (Value, bool, bool) {
    let path = store.root.join("results").join(format!("{}.json", case.id));
    let mut result = if path.exists() {
        match read_json::<Value>(&path) {
            Ok(saved) => saved,
            Err(error) => {
                json!({"case":case,"status":"BLOCKED","blockers":[sanitized_error(&error.to_string())]})
            }
        }
    } else {
        json!({"case":case,"status":"BLOCKED","blockers":["addresses, confirmed funding, designated signatures, broadcast, and spend confirmation have not been verified"]})
    };
    let recorded = result["status"] == "PASS";
    if !recorded {
        return (result, false, false);
    }
    let checked = (|| -> Result<(Confirmation, Confirmation)> {
        let checkpoint = store.load(&case.id)?;
        if result["case"] != serde_json::to_value(case)?
            || !result["blockers"].as_array().is_some_and(|b| b.is_empty())
            || result["spend"]["txid"]
                != checkpoint
                    .progress
                    .finalized()
                    .map(|f| json!(f.txid))
                    .unwrap_or(Value::Null)
        {
            return Err("public result differs from its private PASS checkpoint".into());
        }
        result = public_result(&checkpoint);
        let verified = network.get_or_insert_with(|| {
            chain
                .verify_network()
                .map(|()| now())
                .map_err(|e| sanitized_error(&e.to_string()))
        });
        if let Err(error) = verified {
            return Err(error.clone().into());
        }
        confirmed_pass(chain, &checkpoint)
    })();
    match checked {
        Ok((funding, confirmation)) => {
            result["funding"]["confirmation"] = json!(funding);
            result["confirmation"] = json!(confirmation);
            result["chain_rechecked_at"] = json!(now());
            (result, true, true)
        }
        Err(error) => {
            result["status"] = json!("BLOCKED");
            result["blockers"] = json!([sanitized_error(&error.to_string())]);
            (result, true, false)
        }
    }
}
