use std::time::Duration;

use bitcoin::consensus::deserialize;
use bitcoin::consensus::encode::serialize_hex;
use bitcoin::hex::FromHex;
use bitcoin::{OutPoint, Transaction, Txid};
use serde::Deserialize;

use crate::e2e::Result;
use crate::e2e::records::{Confirmation, now};

const API: &str = "https://mempool.space/testnet4/api";

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Outspend {
    Unspent,
    Spent { txid: Txid, vin: usize },
}

pub trait Chain {
    fn verify_network(&self) -> Result<()>;
    fn transaction(&self, txid: Txid) -> Result<Option<Transaction>>;
    fn confirmation(&self, txid: Txid) -> Result<Option<Confirmation>>;
    fn outspend(&self, outpoint: OutPoint) -> Result<Outspend>;
    fn broadcast(&self, transaction: &Transaction) -> Result<Txid>;
}

pub struct Esplora {
    agent: ureq::Agent,
}

impl Default for Esplora {
    fn default() -> Self {
        Self {
            agent: ureq::Agent::config_builder()
                .timeout_global(Some(Duration::from_secs(30)))
                .build()
                .into(),
        }
    }
}

impl Esplora {
    fn get(&self, path: &str) -> Result<Option<String>> {
        match self.agent.get(format!("{API}/{path}")).call() {
            Ok(mut response) => Ok(Some(
                response
                    .body_mut()
                    .with_config()
                    .limit(16 * 1024 * 1024)
                    .read_to_string()?,
            )),
            Err(ureq::Error::StatusCode(404)) => Ok(None),
            Err(error) => Err(format!("testnet4 API request failed: {error}").into()),
        }
    }

    fn required(&self, path: &str) -> Result<String> {
        self.get(path)?
            .ok_or_else(|| format!("testnet4 API has no {path}").into())
    }
}

impl Chain for Esplora {
    fn verify_network(&self) -> Result<()> {
        let expected = bitcoin::blockdata::constants::genesis_block(bitcoin::Network::Testnet4)
            .block_hash()
            .to_string();
        if self.required("block-height/0")?.trim() != expected {
            return Err("API genesis does not match rust-bitcoin testnet4 genesis".into());
        }
        Ok(())
    }

    fn transaction(&self, txid: Txid) -> Result<Option<Transaction>> {
        let Some(hex) = self.get(&format!("tx/{txid}/hex"))? else {
            return Ok(None);
        };
        let tx: Transaction = deserialize(&Vec::<u8>::from_hex(hex.trim())?)?;
        if tx.compute_txid() != txid {
            return Err("fetched transaction differs from requested txid".into());
        }
        Ok(Some(tx))
    }

    fn confirmation(&self, txid: Txid) -> Result<Option<Confirmation>> {
        #[derive(Deserialize)]
        struct Status {
            confirmed: bool,
            block_height: Option<u32>,
            block_hash: Option<String>,
        }
        let Some(status) = self.get(&format!("tx/{txid}/status"))? else {
            return Ok(None);
        };
        let status: Status = serde_json::from_str(&status)?;
        if !status.confirmed {
            return Ok(None);
        }
        let height = status
            .block_height
            .ok_or("confirmed status has no block height")?;
        let hash = status
            .block_hash
            .ok_or("confirmed status has no block hash")?;
        let canonical = self.required(&format!("block-height/{height}"))?;
        let tip: u32 = self.required("blocks/tip/height")?.trim().parse()?;
        if canonical.trim() != hash || height > tip {
            return Ok(None);
        }
        Ok(Some(Confirmation {
            block_height: height,
            block_hash: hash,
            tip_height: tip,
            confirmations: tip - height + 1,
            checked_at: now(),
        }))
    }

    fn outspend(&self, outpoint: OutPoint) -> Result<Outspend> {
        #[derive(Deserialize)]
        struct Spent {
            spent: bool,
            txid: Option<String>,
            vin: Option<usize>,
        }
        let state: Spent = serde_json::from_str(
            &self.required(&format!("tx/{}/outspend/{}", outpoint.txid, outpoint.vout))?,
        )?;
        if state.spent {
            Ok(Outspend::Spent {
                txid: state.txid.ok_or("outspend has no spending txid")?.parse()?,
                vin: state.vin.ok_or("outspend has no spending input index")?,
            })
        } else if state.txid.is_some() || state.vin.is_some() {
            Err("unspent response contains a spending transaction".into())
        } else {
            Ok(Outspend::Unspent)
        }
    }

    fn broadcast(&self, transaction: &Transaction) -> Result<Txid> {
        let mut response = self
            .agent
            .post(format!("{API}/tx"))
            .header("Content-Type", "text/plain")
            .send(serialize_hex(transaction))?;
        let returned: Txid = response.body_mut().read_to_string()?.trim().parse()?;
        if returned != transaction.compute_txid() {
            return Err("broadcast API returned a different txid".into());
        }
        Ok(returned)
    }
}

#[derive(Debug, PartialEq, Eq)]
pub enum Reconciled {
    Unspent,
    Pending,
    Confirmed(Confirmation),
}

pub fn reconcile(chain: &impl Chain, expected: &Transaction) -> Result<Reconciled> {
    let txid = expected.compute_txid();
    let fetched = chain.transaction(txid)?;
    let mut expected_spender = false;
    for (vin, input) in expected.input.iter().enumerate() {
        match chain.outspend(input.previous_output)? {
            Outspend::Unspent if fetched.is_none() => {}
            Outspend::Spent {
                txid: spender,
                vin: spent_vin,
            } if spender == txid && spent_vin == vin => {
                expected_spender = true;
            }
            Outspend::Unspent => {
                return Err(
                    "transaction is visible but its input spending state is inconsistent".into(),
                );
            }
            Outspend::Spent { .. } => {
                return Err("funding input is spent by another transaction or input index".into());
            }
        }
    }
    let Some(fetched) = fetched else {
        if expected_spender {
            return Err("expected spender is reported without retrievable transaction contents; resume later".into());
        }
        return Ok(Reconciled::Unspent);
    };
    if fetched != *expected {
        return Err("fetched transaction bytes differ from the saved finalized transaction".into());
    }
    match chain.confirmation(txid)? {
        Some(confirmation) => Ok(Reconciled::Confirmed(confirmation)),
        None => Ok(Reconciled::Pending),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use bitcoin::{Amount, ScriptBuf, Sequence, TxIn, TxOut, Witness, absolute, transaction};
    use std::cell::RefCell;

    struct Fake {
        tx: Option<Transaction>,
        spent: Outspend,
        confirmation: Option<Confirmation>,
        broadcasts: RefCell<Vec<String>>,
    }
    impl Chain for Fake {
        fn verify_network(&self) -> Result<()> {
            Ok(())
        }
        fn transaction(&self, _: Txid) -> Result<Option<Transaction>> {
            Ok(self.tx.clone())
        }
        fn confirmation(&self, _: Txid) -> Result<Option<Confirmation>> {
            Ok(self.confirmation.clone())
        }
        fn outspend(&self, _: OutPoint) -> Result<Outspend> {
            Ok(self.spent.clone())
        }
        fn broadcast(&self, tx: &Transaction) -> Result<Txid> {
            self.broadcasts.borrow_mut().push(serialize_hex(tx));
            Ok(tx.compute_txid())
        }
    }
    fn tx() -> Transaction {
        Transaction {
            version: transaction::Version::TWO,
            lock_time: absolute::LockTime::ZERO,
            input: vec![TxIn {
                previous_output: OutPoint::null(),
                script_sig: ScriptBuf::new(),
                sequence: Sequence::MAX,
                witness: Witness::new(),
            }],
            output: vec![TxOut {
                value: Amount::from_sat(5000),
                script_pubkey: ScriptBuf::new(),
            }],
        }
    }
    #[test]
    fn reconcile_ambiguous_broadcast_and_conflicting_spends() {
        let tx = tx();
        let mut chain = Fake {
            tx: None,
            spent: Outspend::Unspent,
            confirmation: None,
            broadcasts: RefCell::new(Vec::new()),
        };
        assert_eq!(reconcile(&chain, &tx).unwrap(), Reconciled::Unspent);
        chain.spent = Outspend::Spent {
            txid: tx.compute_txid(),
            vin: 0,
        };
        assert!(reconcile(&chain, &tx).is_err());
        chain.tx = Some(tx.clone());
        assert_eq!(reconcile(&chain, &tx).unwrap(), Reconciled::Pending);
        chain.confirmation = Some(Confirmation {
            block_height: 10,
            block_hash: "canonical".into(),
            tip_height: 10,
            confirmations: 1,
            checked_at: now(),
        });
        assert!(matches!(
            reconcile(&chain, &tx).unwrap(),
            Reconciled::Confirmed(_)
        ));
        chain.spent = Outspend::Spent {
            txid: tx.compute_txid(),
            vin: 1,
        };
        assert!(reconcile(&chain, &tx).is_err());
        chain.spent = Outspend::Spent {
            txid: tx.compute_txid(),
            vin: 0,
        };
        chain.tx.as_mut().unwrap().output[0].value = Amount::from_sat(1);
        assert!(reconcile(&chain, &tx).is_err());
        assert!(chain.broadcasts.borrow().is_empty());
    }
}
