//! The `roundtrip` fixture: a 2-of-3 vault with the Safe 7 as one key, and a
//! spend of synthetic outputs for it to sign. Throwaway test data that is
//! never funded; the software cosigners come from fixed, public entropy.
//!
//! Adapted from jade-ble's fixture. A Trezor also needs the vault's account
//! xpubs, so all three go in as global xpubs (BIP-174 `PSBT_GLOBAL_XPUB`);
//! and the change carries its key origins, so the Safe 7 can recognize it as
//! the vault's own.

use std::error::Error;
use std::str::FromStr;

use bitcoin::bip32::{DerivationPath, Fingerprint, Xpriv, Xpub};
use bitcoin::hashes::Hash;
use bitcoin::secp256k1::Secp256k1;
use bitcoin::{
    Address, Amount, Network, OutPoint, Psbt, ScriptBuf, Sequence, Transaction, TxIn, TxOut, Txid,
    Witness, absolute, transaction,
};
use miniscript::psbt::PsbtExt;
use miniscript::{Descriptor, DescriptorPublicKey};
use vault_core::descriptor::{build_multisig_descriptor, derive_address_at};
use vault_core::keys::{
    ScriptType, account_multisig_xpub_from_mnemonic, generate_master_xpriv, generate_mnemonic,
    generate_seed,
};

const INPUT_SATS: u64 = 50_000;
const PAYMENT_SATS: u64 = 20_000;

pub struct Fixture {
    pub psbt: Psbt,
    /// Master key of the cosigner that signs after the Safe 7.
    cosigner: Xpriv,
    payment: Address,
    change: Address,
    change_sats: u64,
    fee_sats: u64,
}

impl Fixture {
    pub fn build(
        network: Network,
        trezor_fingerprint: Fingerprint,
        trezor_account: Xpub,
        inputs: u32,
    ) -> Result<Self, Box<dyn Error>> {
        let secp = Secp256k1::new();
        let mut keys = vec![(trezor_fingerprint, trezor_account)];
        let mut cosigners = Vec::new();
        for entropy in [1u8, 2] {
            let mnemonic = generate_mnemonic(&[entropy; 32])?;
            let (_, account) =
                account_multisig_xpub_from_mnemonic(&mnemonic, "", network, 0, ScriptType::P2wsh)?;
            let master = generate_master_xpriv(network, &generate_seed(&mnemonic, ""))?;
            keys.push((master.fingerprint(&secp), account));
            cosigners.push(master);
        }

        let coin = if network == Network::Bitcoin { 0 } else { 1 };
        let account_path = DerivationPath::from_str(&format!("m/48'/{coin}'/0'/2'"))?;
        let descriptor = |chain: u32| -> Result<Descriptor<DescriptorPublicKey>, Box<dyn Error>> {
            let keys: Vec<String> = keys
                .iter()
                .map(|(fingerprint, xpub)| {
                    format!("[{fingerprint}/48'/{coin}'/0'/2']{xpub}/{chain}/*")
                })
                .collect();
            Ok(Descriptor::from_str(&format!(
                "wsh(sortedmulti(2,{}))",
                keys.join(",")
            ))?)
        };
        let (receive, change) = (descriptor(0)?, descriptor(1)?);

        // vault-core's descriptor drops the origins, but must still describe
        // the same vault, address for address.
        let xpubs: Vec<Xpub> = keys.iter().map(|(_, xpub)| *xpub).collect();
        let vault_core_descriptor = build_multisig_descriptor(&xpubs, 2)?;
        for index in 0..inputs {
            let ours = receive.at_derivation_index(index)?.address(network)?;
            let vault_core = derive_address_at(&vault_core_descriptor, index, network)?;
            if ours != vault_core {
                return Err(format!(
                    "address {index}: vault-core gives {vault_core}, we sign for {ours}"
                )
                .into());
            }
        }

        let fee_sats = 200 + 150 * u64::from(inputs);
        let change_sats = (INPUT_SATS * u64::from(inputs))
            .checked_sub(PAYMENT_SATS + fee_sats)
            .ok_or("need at least one input")?;
        let payment = receive.at_derivation_index(1000)?;
        let change_at = change.at_derivation_index(0)?;

        let mut previous = Vec::new();
        for index in 0..inputs {
            previous.push(Transaction {
                version: transaction::Version::TWO,
                lock_time: absolute::LockTime::ZERO,
                input: vec![TxIn {
                    previous_output: OutPoint {
                        txid: Txid::from_byte_array([0x5a; 32]),
                        vout: index,
                    },
                    script_sig: ScriptBuf::new(),
                    sequence: Sequence::MAX,
                    witness: Witness::new(),
                }],
                output: vec![TxOut {
                    value: Amount::from_sat(INPUT_SATS),
                    script_pubkey: receive.at_derivation_index(index)?.script_pubkey(),
                }],
            });
        }
        let unsigned_tx = Transaction {
            version: transaction::Version::TWO,
            lock_time: absolute::LockTime::ZERO,
            input: previous
                .iter()
                .map(|prev| TxIn {
                    previous_output: OutPoint {
                        txid: prev.compute_txid(),
                        vout: 0,
                    },
                    script_sig: ScriptBuf::new(),
                    sequence: Sequence::ENABLE_RBF_NO_LOCKTIME,
                    witness: Witness::new(),
                })
                .collect(),
            output: vec![
                TxOut {
                    value: Amount::from_sat(PAYMENT_SATS),
                    script_pubkey: payment.script_pubkey(),
                },
                TxOut {
                    value: Amount::from_sat(change_sats),
                    script_pubkey: change_at.script_pubkey(),
                },
            ],
        };

        let mut psbt = Psbt::from_unsigned_tx(unsigned_tx)?;
        for (index, prev) in previous.into_iter().enumerate() {
            psbt.inputs[index].witness_utxo = Some(prev.output[0].clone());
            psbt.inputs[index].non_witness_utxo = Some(prev);
            let definite = receive.at_derivation_index(u32::try_from(index)?)?;
            psbt.update_input_with_descriptor(index, &definite)?;
        }
        psbt.update_output_with_descriptor(1, &change_at)?;
        for (fingerprint, xpub) in &keys {
            psbt.xpub
                .insert(*xpub, (*fingerprint, account_path.clone()));
        }

        let [cosigner, mut unused] = <[Xpriv; 2]>::try_from(cosigners).expect("two cosigners");
        unused.private_key.non_secure_erase();
        Ok(Self {
            psbt,
            cosigner,
            payment: payment.address(network)?,
            change: change_at.address(network)?,
            change_sats,
            fee_sats,
        })
    }

    /// What to compare the Safe 7's screen against before confirming.
    pub fn describe(&self) {
        let inputs = self.psbt.inputs.len();
        let bytes = self.psbt.serialize().len();
        println!(
            "2-of-3 vault spend: {inputs} synthetic inputs of {INPUT_SATS} sats, PSBT {bytes} bytes"
        );
        println!("The Safe 7 should show:");
        println!(
            "  {PAYMENT_SATS} sats to {} (the vault's own address #1000, sent as a payment)",
            self.payment
        );
        println!("  fee {} sats", self.fee_sats);
        println!(
            "and not the change: {} sats back to the vault at {}, which it checks itself",
            self.change_sats, self.change
        );
    }

    /// Adds the software cosigner's signatures to the Safe 7's and finalizes.
    /// miniscript's finalizer runs its interpreter over every input, which
    /// re-verifies the Safe 7's signatures inside a complete 2-of-3 spend.
    pub fn cosign_and_finalize(mut self, mut signed: Psbt) -> Result<Transaction, Box<dyn Error>> {
        let secp = Secp256k1::new();
        let cosigned = signed.sign(&self.cosigner, &secp);
        self.cosigner.private_key.non_secure_erase();
        cosigned.map_err(|(_, errors)| format!("the cosigner could not sign: {errors:?}"))?;
        signed
            .finalize_mut(&secp)
            .map_err(|errors| format!("finalizing failed: {errors:?}"))?;
        Ok(signed.extract(&secp)?)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// With a software key standing in for the Safe 7, the fixture must be a
    /// spend that the Safe 7's key alone half-signs and the cosigner
    /// completes, so a hardware failure can't be the fixture's fault.
    #[test]
    fn trezor_signature_plus_cosigner_finalizes() {
        let network = Network::Testnet;
        let secp = Secp256k1::new();
        let mnemonic = generate_mnemonic(&[0; 32]).unwrap();
        let trezor = generate_master_xpriv(network, &generate_seed(&mnemonic, "")).unwrap();
        let path = DerivationPath::from_str("m/48'/1'/0'/2'").unwrap();
        let account = Xpub::from_priv(&secp, &trezor.derive_priv(&secp, &path).unwrap());

        let fixture = Fixture::build(network, trezor.fingerprint(&secp), account, 3).unwrap();
        assert_eq!(fixture.psbt.xpub.len(), 3, "every account as a global xpub");
        assert!(
            !fixture.psbt.outputs[1].bip32_derivation.is_empty(),
            "the change's origins"
        );
        let mut signed = fixture.psbt.clone();
        let signed_keys = signed.sign(&trezor, &secp).unwrap();
        assert_eq!(signed_keys.len(), 3, "the Safe 7's key is in every input");

        let tx = fixture.cosign_and_finalize(signed).unwrap();
        assert_eq!(tx.input.len(), 3);
        for input in &tx.input {
            // OP_CHECKMULTISIG's dummy element, two signatures, the witness script
            assert_eq!(input.witness.len(), 4);
        }
    }

    #[test]
    fn refuses_zero_inputs() {
        let secp = Secp256k1::new();
        let mnemonic = generate_mnemonic(&[0; 32]).unwrap();
        let trezor =
            generate_master_xpriv(Network::Testnet, &generate_seed(&mnemonic, "")).unwrap();
        let account = Xpub::from_priv(&secp, &trezor);
        assert!(Fixture::build(Network::Testnet, trezor.fingerprint(&secp), account, 0).is_err());
    }
}
