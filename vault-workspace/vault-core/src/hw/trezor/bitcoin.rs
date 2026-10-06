//! Trezor doesn't take a PSBT. It asks for the transaction piece by piece
//! (`TxRequest`), and for every input's previous transaction, which it hashes
//! to check amounts (`core/src/apps/bitcoin/sign_tx/` in trezor-firmware). So
//! everything it asks for is prepared here from the PSBT, up front, and the
//! PSBT is refused before anything is sent unless each input is from one
//! supported account or P2WSH `sortedmulti` vault owned by this Safe 7.
//! Whatever comes back is checked by
//! [`crate::hw::psbt_check`] against sighashes computed here.
//!
//! The vault's other keys reach Trezor as `MultisigRedeemScriptType`:
//! account xpubs (`nodes`), the shared non-hardened suffix (`address_n`), `m`,
//! and `pubkeys_order = LEXICOGRAPHIC`, which is BIP-67, i.e. `sortedmulti`
//! (`core/src/apps/bitcoin/multisig.py`). The account xpubs come from the
//! PSBT's global xpubs (BIP-174 `PSBT_GLOBAL_XPUB`).

use std::collections::{BTreeMap, BTreeSet};

use bitcoin::bip32::{ChainCode, ChildNumber, DerivationPath, Fingerprint, KeySource, Xpub};
use bitcoin::hashes::Hash;
use bitcoin::opcodes::all::OP_CHECKMULTISIG;
use bitcoin::psbt::{Input as PsbtInput, Output as PsbtOutput};
use bitcoin::script::{Builder, Instruction, Script, ScriptBuf};
use bitcoin::secp256k1::{self, Secp256k1, Verification};
use bitcoin::{Address, Network, NetworkKind, Psbt, Transaction, TxOut, Txid};
use miniscript::psbt::PsbtExt;
use miniscript::{DescriptorPublicKey, ForEachKey};
use protobuf::{EnumOrUnknown, MessageField};

use super::protos::bitcoin::{
    Address as AddressResponse, GetAddress, GetPublicKey, InputScriptType, MultisigPubkeysOrder,
    MultisigRedeemScriptType, OutputScriptType, PrevInput, PrevOutput, PrevTx, PublicKey, SignTx,
    TxAckInput, TxAckOutput, TxAckPrevInput, TxAckPrevMeta, TxAckPrevOutput, TxInput, TxOutput,
    TxRequest, tx_ack_input, tx_ack_output, tx_ack_prev_input, tx_ack_prev_output,
    tx_request::RequestType,
};
use super::protos::common::HDNodeType;
use super::{TrezorError, encode};
use crate::hw::{AddressRequest, psbt_check};

/// Trezor's coin name for a network (`common/defs/bitcoin/*.json`). Signet
/// and testnet4 share testnet's parameters there.
pub(super) fn coin_name(network: Network) -> &'static str {
    match network {
        Network::Bitcoin => "Bitcoin",
        Network::Testnet | Network::Testnet4 | Network::Signet => "Testnet",
        Network::Regtest => "Regtest",
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct XpubOptions {
    /// Show the xpub on the Safe 7 too, for the user to compare.
    pub show_on_device: bool,
    /// Have the device encode it as Trezor Suite shows it (SLIP-132, e.g.
    /// `vpub…`) rather than as `xpub`/`tpub`. Only changes the string the
    /// device returns, not the key.
    pub slip132: bool,
}

/// A `GetPublicKey` in flight.
pub(super) struct XpubRequest {
    path: DerivationPath,
    network: Network,
    slip132: bool,
}

impl XpubRequest {
    pub(super) fn new(
        path: &DerivationPath,
        network: Network,
        options: XpubOptions,
    ) -> Result<(Self, GetPublicKey), TrezorError> {
        let script_type = match path.as_ref() {
            account @ [_, _, _, _] if is_bip48_p2wsh_account(account, network) => {
                Some(InputScriptType::SPENDWITNESS)
            }
            [purpose, coin, account]
                if *coin
                    == (ChildNumber::Hardened {
                        index: coin_type_of(network),
                    })
                    && account.is_hardened() =>
            {
                ScriptFamily::from_purpose(*purpose).map(ScriptFamily::input_type)
            }
            _ => None,
        };
        let script_type = script_type.ok_or_else(|| {
            refuse(format!(
                "{path} is not a BIP-48 P2WSH or BIP-44/49/84/86 account path on {network}"
            ))
        })?;

        let mut request = GetPublicKey::new();
        request.address_n = path_u32(path);
        request.coin_name = Some(coin_name(network).into());
        request.script_type = Some(EnumOrUnknown::new(script_type));
        request.ignore_xpub_magic = Some(!options.slip132);
        request.show_display = Some(options.show_on_device);
        let request_state = Self {
            path: path.clone(),
            network,
            slip132: options.slip132,
        };
        Ok((request_state, request))
    }

    /// Checks the answer is the key at the path and network asked for, and
    /// returns it with the master fingerprint and the device's own encoding.
    pub(super) fn finish(
        &self,
        answer: PublicKey,
    ) -> Result<(Xpub, Fingerprint, String), TrezorError> {
        let node = answer
            .node
            .as_ref()
            .ok_or_else(|| protocol("PublicKey without a node"))?;
        let xpub = xpub_of(node, self.network)?;
        let last = self.path.as_ref().last().copied();
        if usize::from(xpub.depth) != self.path.len() || Some(xpub.child_number) != last {
            return Err(protocol(format!(
                "the key is not at the requested path {}",
                self.path
            )));
        }
        let as_shown = answer
            .xpub
            .clone()
            .ok_or_else(|| protocol("PublicKey without an xpub"))?;
        if !self.slip132 {
            let parsed: Xpub = as_shown
                .parse()
                .map_err(|e| protocol(format!("unparseable xpub: {e}")))?;
            if parsed.network != NetworkKind::from(self.network) {
                return Err(TrezorError::NetworkMismatch(format!(
                    "asked for a {} xpub, got a {:?} one",
                    self.network, parsed.network
                )));
            }
            if parsed != xpub {
                return Err(protocol("the xpub and the node in PublicKey differ"));
            }
        }
        let root = answer
            .root_fingerprint
            .ok_or_else(|| protocol("PublicKey without the master fingerprint"))?;
        Ok((xpub, Fingerprint::from(root.to_be_bytes()), as_shown))
    }
}

/// A `SignTx` exchange in flight, with every answer prepared.
pub(super) struct Signing {
    unsigned: Psbt,
    signer: Fingerprint,
    inputs: Vec<TxInput>,
    /// Per input, the signer's key, under which its signature goes.
    signer_keys: Vec<SignerKey>,
    outputs: Vec<TxOutput>,
    /// Keyed by txid in Trezor's (display) byte order.
    previous: BTreeMap<[u8; 32], Transaction>,
    signatures: Vec<Option<Vec<u8>>>,
}

/// What to do after a `TxRequest`.
pub(super) enum Answer {
    /// Send this `TxAck`.
    Ack(Vec<u8>),
    /// `TXFINISHED`: every signature is in.
    Finished,
}

impl Signing {
    pub(super) fn new(
        psbt: &Psbt,
        signer: Fingerprint,
        network: Network,
    ) -> Result<(Self, SignTx), TrezorError> {
        let tx = &psbt.unsigned_tx;
        if tx.input.is_empty() || tx.output.is_empty() {
            return Err(refuse("the PSBT has no inputs or no outputs"));
        }
        if psbt.inputs.len() != tx.input.len() || psbt.outputs.len() != tx.output.len() {
            return Err(refuse("the PSBT's maps don't match its transaction"));
        }
        psbt_check::validate_signing_policy(psbt).map_err(|error| refuse(error.to_string()))?;

        let secp = Secp256k1::verification_only();
        let mut wallet: Option<Wallet> = None;
        let mut inputs = Vec::with_capacity(tx.input.len());
        let mut signer_keys = Vec::with_capacity(tx.input.len());
        let mut previous = BTreeMap::new();
        let mut spent = BTreeSet::new();
        for (index, (txin, input)) in tx.input.iter().zip(&psbt.inputs).enumerate() {
            let refuse_input = |why: &str| refuse(format!("input {index}: {why}"));
            let prevout = txin.previous_output;
            if !spent.insert(prevout) {
                return Err(refuse_input("spends the same output as an earlier input"));
            }
            let prev_tx = input.non_witness_utxo.as_ref().ok_or_else(|| {
                refuse_input("no previous transaction (Trezor checks amounts against it)")
            })?;
            if prev_tx.compute_txid() != prevout.txid {
                return Err(refuse_input(
                    "the previous transaction is not the one it spends",
                ));
            }
            let spent = prev_tx.output.get(prevout.vout as usize).ok_or_else(|| {
                refuse_input("spends an output its previous transaction doesn't have")
            })?;
            if input
                .witness_utxo
                .as_ref()
                .is_some_and(|utxo| utxo != spent)
            {
                return Err(refuse_input(
                    "witness UTXO and previous transaction disagree",
                ));
            }
            let resolved = Wallet::resolve(input, spent, signer, network, &psbt.xpub, &secp)
                .map_err(|why| refuse_input(&why))?;
            let (signer_key, signer_path) =
                resolved.signer(signer).map_err(|why| refuse_input(&why))?;
            match &wallet {
                None => wallet = Some(resolved.clone()),
                Some(first) if first.same_wallet(&resolved) => {}
                Some(_) => return Err(refuse_input("spends from a different vault than input 0")),
            }

            let mut proto = TxInput::new();
            proto.address_n = path_u32(&signer_path);
            proto.prev_hash = Some(trezor_hash(&prevout.txid).to_vec());
            proto.prev_index = Some(prevout.vout);
            proto.sequence = Some(txin.sequence.0);
            proto.script_type = Some(EnumOrUnknown::new(resolved.input_type()));
            if let Wallet::Multisig(multisig) = resolved {
                proto.multisig = MessageField::some(multisig.proto());
            }
            proto.amount = Some(spent.value.to_sat());
            inputs.push(proto);
            signer_keys.push(signer_key);
            previous.insert(trezor_hash(&prevout.txid), prev_tx.clone());
        }
        let wallet = wallet.expect("at least one input");

        let mut outputs = Vec::with_capacity(tx.output.len());
        for (index, (txout, output)) in tx.output.iter().zip(&psbt.outputs).enumerate() {
            let change = match &wallet {
                Wallet::Multisig(vault) => {
                    change_output(txout, output, vault, signer, network, &psbt.xpub, &secp)
                }
                Wallet::SingleSig(account) => {
                    single_change_output(txout, output, account, network, &psbt.xpub, &secp)
                        .map_err(|why| refuse(format!("output {index}: {why}")))?
                }
            };
            let proto = match change {
                Some(change) => change,
                None => external_output(txout, network)
                    .map_err(|why| refuse(format!("output {index}: {why}")))?,
            };
            outputs.push(proto);
        }

        let mut sign_tx = SignTx::new();
        sign_tx.inputs_count = Some(count(tx.input.len())?);
        sign_tx.outputs_count = Some(count(tx.output.len())?);
        sign_tx.coin_name = Some(coin_name(network).into());
        sign_tx.version = Some(tx.version.0 as u32);
        sign_tx.lock_time = Some(tx.lock_time.to_consensus_u32());
        // Signatures only: they come back either way (`set_serialized_signature`
        // in `sign_tx/bitcoin.py`), and we finalize from the PSBT ourselves.
        sign_tx.serialize = Some(false);

        let signing = Self {
            unsigned: psbt.clone(),
            signer,
            signatures: vec![None; inputs.len()],
            inputs,
            signer_keys,
            outputs,
            previous,
        };
        Ok((signing, sign_tx))
    }

    /// Answers one `TxRequest`: only for this transaction's own inputs and
    /// outputs and the previous transactions it spends.
    pub(super) fn answer(&mut self, request: &TxRequest) -> Result<Answer, TrezorError> {
        if let Some(serialized) = request.serialized.as_ref()
            && let (Some(index), Some(signature)) =
                (serialized.signature_index, &serialized.signature)
        {
            let slot = self.signatures.get_mut(index as usize).ok_or_else(|| {
                protocol(format!(
                    "a signature for input {index}, which doesn't exist"
                ))
            })?;
            if slot.is_some() {
                return Err(protocol(format!("two signatures for input {index}")));
            }
            *slot = Some(signature.clone());
        }

        let request_type = request
            .request_type
            .and_then(|t| t.enum_value().ok())
            .ok_or_else(|| protocol("a TxRequest of no known type"))?;
        if request_type == RequestType::TXFINISHED {
            return Ok(Answer::Finished);
        }
        let details = request
            .details
            .as_ref()
            .ok_or_else(|| protocol("a TxRequest without details"))?;
        let index = details.request_index.unwrap_or(0) as usize;
        let ack = match (request_type, details.tx_hash.as_deref()) {
            (RequestType::TXINPUT, None) => {
                let input = self
                    .inputs
                    .get(index)
                    .ok_or_else(|| out_of_range("input", index))?;
                let mut wrapper = tx_ack_input::TxAckInputWrapper::new();
                wrapper.input = MessageField::some(input.clone());
                let mut ack = TxAckInput::new();
                ack.tx = MessageField::some(wrapper);
                encode(&ack)?
            }
            (RequestType::TXOUTPUT, None) => {
                let output = self
                    .outputs
                    .get(index)
                    .ok_or_else(|| out_of_range("output", index))?;
                let mut wrapper = tx_ack_output::TxAckOutputWrapper::new();
                wrapper.output = MessageField::some(output.clone());
                let mut ack = TxAckOutput::new();
                ack.tx = MessageField::some(wrapper);
                encode(&ack)?
            }
            (RequestType::TXMETA, Some(hash)) => {
                let tx = self.previous_tx(hash)?;
                let mut meta = PrevTx::new();
                meta.version = Some(tx.version.0 as u32);
                meta.lock_time = Some(tx.lock_time.to_consensus_u32());
                meta.inputs_count = Some(count(tx.input.len())?);
                meta.outputs_count = Some(count(tx.output.len())?);
                let mut ack = TxAckPrevMeta::new();
                ack.tx = MessageField::some(meta);
                encode(&ack)?
            }
            (RequestType::TXINPUT, Some(hash)) => {
                let txin = self
                    .previous_tx(hash)?
                    .input
                    .get(index)
                    .ok_or_else(|| out_of_range("previous input", index))?;
                let mut input = PrevInput::new();
                input.prev_hash = Some(trezor_hash(&txin.previous_output.txid).to_vec());
                input.prev_index = Some(txin.previous_output.vout);
                input.script_sig = Some(txin.script_sig.to_bytes());
                input.sequence = Some(txin.sequence.0);
                let mut wrapper = tx_ack_prev_input::TxAckPrevInputWrapper::new();
                wrapper.input = MessageField::some(input);
                let mut ack = TxAckPrevInput::new();
                ack.tx = MessageField::some(wrapper);
                encode(&ack)?
            }
            (RequestType::TXOUTPUT, Some(hash)) => {
                let txout = self
                    .previous_tx(hash)?
                    .output
                    .get(index)
                    .ok_or_else(|| out_of_range("previous output", index))?;
                let mut output = PrevOutput::new();
                output.amount = Some(txout.value.to_sat());
                output.script_pubkey = Some(txout.script_pubkey.to_bytes());
                let mut wrapper = tx_ack_prev_output::TxAckPrevOutputWrapper::new();
                wrapper.output = MessageField::some(output);
                let mut ack = TxAckPrevOutput::new();
                ack.tx = MessageField::some(wrapper);
                encode(&ack)?
            }
            // Nothing here asks for extra data, replaced transactions or
            // payment requests, so a device that does is out of step.
            (other, _) => return Err(protocol(format!("unexpected TxRequest {other:?}"))),
        };
        Ok(Answer::Ack(ack))
    }

    /// The PSBT with the Safe 7's signatures, only if each one verifies.
    pub(super) fn finish(self) -> Result<Psbt, TrezorError> {
        let mut signed = self.unsigned.clone();
        for (index, signature) in self.signatures.iter().enumerate() {
            let Some(signature) = signature else { continue };
            let invalid = || psbt_check::SignatureCheckError::InvalidSignature { input: index };
            match self.signer_keys[index] {
                SignerKey::Ecdsa(key) => {
                    let signature =
                        secp256k1::ecdsa::Signature::from_der(signature).map_err(|_| invalid())?;
                    signed.inputs[index].partial_sigs.insert(
                        bitcoin::PublicKey::new(key),
                        bitcoin::ecdsa::Signature::sighash_all(signature),
                    );
                }
                SignerKey::Taproot => {
                    let signature = bitcoin::taproot::Signature::from_slice(signature)
                        .map_err(|_| invalid())?;
                    signed.inputs[index].tap_key_sig = Some(signature);
                }
            }
        }
        Ok(psbt_check::verify_device_signatures(
            &self.unsigned,
            signed,
            self.signer,
        )?)
    }

    fn previous_tx(&self, hash: &[u8]) -> Result<&Transaction, TrezorError> {
        <[u8; 32]>::try_from(hash)
            .ok()
            .and_then(|hash| self.previous.get(&hash))
            .ok_or_else(|| protocol("the Trezor asked for a transaction this one doesn't spend"))
    }
}

#[derive(Clone, Copy)]
enum SignerKey {
    Ecdsa(secp256k1::PublicKey),
    Taproot,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum ScriptFamily {
    Legacy,
    WrappedSegwit,
    Segwit,
    Taproot,
}

impl ScriptFamily {
    fn from_purpose(purpose: ChildNumber) -> Option<Self> {
        match purpose {
            ChildNumber::Hardened { index: 44 } => Some(Self::Legacy),
            ChildNumber::Hardened { index: 49 } => Some(Self::WrappedSegwit),
            ChildNumber::Hardened { index: 84 } => Some(Self::Segwit),
            ChildNumber::Hardened { index: 86 } => Some(Self::Taproot),
            _ => None,
        }
    }

    fn input_type(self) -> InputScriptType {
        match self {
            Self::Legacy => InputScriptType::SPENDADDRESS,
            Self::WrappedSegwit => InputScriptType::SPENDP2SHWITNESS,
            Self::Segwit => InputScriptType::SPENDWITNESS,
            Self::Taproot => InputScriptType::SPENDTAPROOT,
        }
    }

    fn output_type(self) -> OutputScriptType {
        match self {
            Self::Legacy => OutputScriptType::PAYTOADDRESS,
            Self::WrappedSegwit => OutputScriptType::PAYTOP2SHWITNESS,
            Self::Segwit => OutputScriptType::PAYTOWITNESS,
            Self::Taproot => OutputScriptType::PAYTOTAPROOT,
        }
    }

    fn script<C: Verification>(self, key: secp256k1::PublicKey, secp: &Secp256k1<C>) -> ScriptBuf {
        let public = bitcoin::PublicKey::new(key);
        match self {
            Self::Legacy => ScriptBuf::new_p2pkh(&public.pubkey_hash()),
            Self::WrappedSegwit => Self::Segwit.script(key, secp).to_p2sh(),
            Self::Segwit => ScriptBuf::new_p2wpkh(&public.wpubkey_hash().expect("compressed key")),
            Self::Taproot => ScriptBuf::new_p2tr(secp, key.x_only_public_key().0, None),
        }
    }
}

#[derive(Clone)]
struct SingleSig {
    family: ScriptFamily,
    xpub: Xpub,
    account: KeySource,
    key: secp256k1::PublicKey,
    path: DerivationPath,
}

impl SingleSig {
    fn resolve<C: Verification>(
        input: &PsbtInput,
        spent: &TxOut,
        signer: Fingerprint,
        network: Network,
        xpubs: &BTreeMap<Xpub, KeySource>,
        secp: &Secp256k1<C>,
    ) -> Result<Self, String> {
        if input.witness_script.is_some() {
            return Err("a single-sig input has a witness script".into());
        }
        let taproot = spent.script_pubkey.is_p2tr();
        let (key_source, expected_key) = if taproot {
            if !input.bip32_derivation.is_empty()
                || input.tap_key_origins.len() != 1
                || input.tap_merkle_root.is_some()
                || !input.tap_scripts.is_empty()
                || !input.tap_script_sigs.is_empty()
            {
                return Err("only BIP-86 Taproot key-path inputs are supported".into());
            }
            let (key, (leaves, source)) = input.tap_key_origins.iter().next().expect("one origin");
            if !leaves.is_empty() || input.tap_internal_key != Some(*key) {
                return Err("the Taproot origin does not match its internal key".into());
            }
            (source, *key)
        } else {
            if input.bip32_derivation.len() != 1
                || input.tap_internal_key.is_some()
                || input.tap_merkle_root.is_some()
                || !input.tap_key_origins.is_empty()
                || !input.tap_scripts.is_empty()
                || !input.tap_script_sigs.is_empty()
                || input.tap_key_sig.is_some()
            {
                return Err("a single-sig input must contain exactly its one key origin".into());
            }
            let (key, source) = input.bip32_derivation.iter().next().expect("one origin");
            (source, key.x_only_public_key().0)
        };
        let (fingerprint, path) = key_source;
        if *fingerprint != signer {
            return Err(format!("no key of fingerprint {signer}"));
        }
        let [purpose, coin, account, chain, index] = path.as_ref() else {
            return Err("not a BIP-44/49/84/86 receive or change key path".into());
        };
        let family = ScriptFamily::from_purpose(*purpose).ok_or("unsupported account purpose")?;
        if *coin
            != (ChildNumber::Hardened {
                index: coin_type_of(network),
            })
            || *account != (ChildNumber::Hardened { index: 0 })
            || !matches!(chain, ChildNumber::Normal { index: 0 | 1 })
            || !index.is_normal()
            || (family == ScriptFamily::Taproot) != taproot
        {
            return Err(format!(
                "{path} is not account 0 for its script type on {network}"
            ));
        }
        let account_path: DerivationPath = path.as_ref()[..3].to_vec().into();
        let candidates: Vec<_> = xpubs
            .iter()
            .filter_map(|(xpub, source)| {
                if source != &(signer, account_path.clone())
                    || !valid_account_xpub(xpub, &account_path, network)
                {
                    return None;
                }
                let suffix = &path.as_ref()[3..];
                let derived = xpub.derive_pub(secp, &suffix).ok()?;
                (derived.public_key.x_only_public_key().0 == expected_key)
                    .then_some((*xpub, derived.public_key))
            })
            .collect();
        let [(xpub, key)] = candidates.as_slice() else {
            return Err("no unique global account xpub derives the signing key".into());
        };
        if !taproot && !input.bip32_derivation.contains_key(key) {
            return Err("the global xpub derives a different compressed key".into());
        }
        let redeem = (family == ScriptFamily::WrappedSegwit)
            .then(|| ScriptFamily::Segwit.script(*key, secp));
        if input.redeem_script != redeem || spent.script_pubkey != family.script(*key, secp) {
            return Err("the origin, redeem script and previous output do not agree".into());
        }
        Ok(Self {
            family,
            xpub: *xpub,
            account: (signer, account_path),
            key: *key,
            path: path.clone(),
        })
    }
}

#[derive(Clone)]
enum Wallet {
    Multisig(Multisig),
    SingleSig(SingleSig),
}

impl Wallet {
    fn resolve<C: Verification>(
        input: &PsbtInput,
        spent: &TxOut,
        signer: Fingerprint,
        network: Network,
        xpubs: &BTreeMap<Xpub, KeySource>,
        secp: &Secp256k1<C>,
    ) -> Result<Self, String> {
        if spent.script_pubkey.is_p2wsh() {
            if input.redeem_script.is_some()
                || input.tap_internal_key.is_some()
                || input.tap_merkle_root.is_some()
                || input.tap_key_sig.is_some()
                || !input.tap_key_origins.is_empty()
                || !input.tap_scripts.is_empty()
                || !input.tap_script_sigs.is_empty()
            {
                return Err("a P2WSH vault input has unrelated redeem or Taproot data".into());
            }
            let script = input.witness_script.as_ref().ok_or("no witness script")?;
            if spent.script_pubkey != script.to_p2wsh() {
                return Err("the witness script is not what it spends".into());
            }
            let multisig = Multisig::resolve(script, &input.bip32_derivation, xpubs, secp)?;
            if multisig.nodes.iter().any(|node| {
                let (_, account) = &xpubs[node];
                !is_bip48_p2wsh_account(account.as_ref(), network)
                    || !valid_account_xpub(node, account, network)
            }) {
                return Err(
                    "a vault account xpub or origin is not BIP-48 P2WSH on this network".into(),
                );
            }
            let (_, path) = multisig.signer(signer)?;
            if !is_vault_key_path(&path, network) {
                return Err(format!(
                    "this device's key is at {path}, not a BIP-48 P2WSH vault path on {network}"
                ));
            }
            Ok(Self::Multisig(multisig))
        } else {
            SingleSig::resolve(input, spent, signer, network, xpubs, secp).map(Self::SingleSig)
        }
    }

    fn signer(&self, signer: Fingerprint) -> Result<(SignerKey, DerivationPath), String> {
        match self {
            Self::Multisig(multisig) => multisig
                .signer(signer)
                .map(|(key, path)| (SignerKey::Ecdsa(key), path)),
            Self::SingleSig(single) => Ok((
                if single.family == ScriptFamily::Taproot {
                    SignerKey::Taproot
                } else {
                    SignerKey::Ecdsa(single.key)
                },
                single.path.clone(),
            )),
        }
    }

    fn input_type(&self) -> InputScriptType {
        match self {
            Self::Multisig(_) => InputScriptType::SPENDWITNESS,
            Self::SingleSig(single) => single.family.input_type(),
        }
    }

    fn same_wallet(&self, other: &Self) -> bool {
        match (self, other) {
            (Self::Multisig(a), Self::Multisig(b)) => a.same_vault(b),
            (Self::SingleSig(a), Self::SingleSig(b)) => {
                a.family == b.family && a.xpub == b.xpub && a.account == b.account
            }
            _ => false,
        }
    }
}

fn valid_account_xpub(xpub: &Xpub, path: &DerivationPath, network: Network) -> bool {
    xpub.network == NetworkKind::from(network)
        && usize::from(xpub.depth) == path.len()
        && path.as_ref().last().copied() == Some(xpub.child_number)
}

fn single_change_output<C: Verification>(
    txout: &TxOut,
    output: &PsbtOutput,
    account: &SingleSig,
    network: Network,
    xpubs: &BTreeMap<Xpub, KeySource>,
    secp: &Secp256k1<C>,
) -> Result<Option<TxOutput>, String> {
    let ours = output
        .bip32_derivation
        .values()
        .any(|(fp, _)| *fp == account.account.0)
        || output
            .tap_key_origins
            .values()
            .any(|(_, (fp, _))| *fp == account.account.0);
    if !ours {
        return Ok(None);
    }
    let input = PsbtInput {
        bip32_derivation: output.bip32_derivation.clone(),
        redeem_script: output.redeem_script.clone(),
        witness_script: output.witness_script.clone(),
        tap_internal_key: output.tap_internal_key,
        tap_key_origins: output.tap_key_origins.clone(),
        ..PsbtInput::default()
    };
    if output.tap_tree.is_some() {
        return Err("Taproot script-path change is unsupported".into());
    }
    let change = SingleSig::resolve(&input, txout, account.account.0, network, xpubs, secp)?;
    if change.family != account.family
        || change.xpub != account.xpub
        || change.account != account.account
    {
        return Ok(None);
    }
    let mut proto = TxOutput::new();
    proto.address_n = path_u32(&change.path);
    proto.amount = Some(txout.value.to_sat());
    proto.script_type = Some(EnumOrUnknown::new(change.family.output_type()));
    Ok(Some(proto))
}

pub(super) struct AddressVerification {
    expected: Address,
}

impl AddressVerification {
    pub(super) fn new(
        request: &AddressRequest<'_>,
        network: Network,
    ) -> Result<(Self, GetAddress), TrezorError> {
        let mut xpubs = BTreeMap::new();
        let mut fingerprints = BTreeSet::new();
        let valid = request.descriptor.for_each_key(|key| {
            let DescriptorPublicKey::XPub(key) = key else {
                return false;
            };
            let Some((fingerprint, origin)) = &key.origin else {
                return false;
            };
            if key.wildcard != miniscript::descriptor::Wildcard::Unhardened
                || !matches!(
                    key.derivation_path.as_ref(),
                    [ChildNumber::Normal { index: 0 | 1 }]
                )
                || !valid_account_xpub(&key.xkey, origin, network)
                || !fingerprints.insert(*fingerprint)
            {
                return false;
            }
            let account = is_bip48_p2wsh_account(origin.as_ref(), network)
                || matches!(origin.as_ref(), [purpose, coin, ChildNumber::Hardened { index: 0 }]
                    if ScriptFamily::from_purpose(*purpose).is_some()
                        && *coin == (ChildNumber::Hardened { index: coin_type_of(network) }));
            account
                && xpubs
                    .insert(key.xkey, (*fingerprint, origin.clone()))
                    .is_none()
        });
        if !valid || !fingerprints.contains(&request.signer) {
            return Err(refuse(
                "address verification needs unique enrolled account xpubs, origins, and a receive/change wildcard",
            ));
        }
        let expected =
            crate::descriptor::derive_address_at(request.descriptor, request.index, network)
                .map_err(|error| refuse(error.to_string()))?;
        let definite = request
            .descriptor
            .at_derivation_index(request.index)
            .map_err(|error| refuse(error.to_string()))?;
        let txout = TxOut {
            value: bitcoin::Amount::ZERO,
            script_pubkey: expected.script_pubkey(),
        };
        let mut metadata = Psbt::from_unsigned_tx(Transaction {
            version: bitcoin::transaction::Version::TWO,
            lock_time: bitcoin::absolute::LockTime::ZERO,
            input: Vec::new(),
            output: vec![txout.clone()],
        })
        .map_err(|error| refuse(error.to_string()))?;
        metadata
            .update_output_with_descriptor(0, &definite)
            .map_err(|error| refuse(error.to_string()))?;
        let output = &metadata.outputs[0];
        if output.tap_tree.is_some() {
            return Err(refuse(
                "Taproot script-path address verification is unsupported",
            ));
        }
        let input = PsbtInput {
            bip32_derivation: output.bip32_derivation.clone(),
            redeem_script: output.redeem_script.clone(),
            witness_script: output.witness_script.clone(),
            tap_internal_key: output.tap_internal_key,
            tap_key_origins: output.tap_key_origins.clone(),
            ..PsbtInput::default()
        };
        let secp = Secp256k1::verification_only();
        let wallet = Wallet::resolve(&input, &txout, request.signer, network, &xpubs, &secp)
            .map_err(refuse)?;
        let (_, path) = wallet.signer(request.signer).map_err(refuse)?;
        let mut message = GetAddress::new();
        message.address_n = path_u32(&path);
        message.coin_name = Some(coin_name(network).into());
        message.show_display = Some(true);
        message.script_type = Some(EnumOrUnknown::new(wallet.input_type()));
        if let Wallet::Multisig(multisig) = wallet {
            message.multisig = MessageField::some(multisig.proto());
        }
        Ok((Self { expected }, message))
    }

    pub(super) fn finish(self, response: AddressResponse) -> Result<Address, TrezorError> {
        let shown: bitcoin::Address<bitcoin::address::NetworkUnchecked> = response
            .address
            .ok_or_else(|| protocol("Address response without an address"))?
            .parse()
            .map_err(|_| protocol("an unparseable address from the Trezor"))?;
        if shown.assume_checked() != self.expected {
            return Err(TrezorError::NetworkMismatch(
                "the displayed address differs from the enrolled descriptor".into(),
            ));
        }
        Ok(self.expected)
    }
}

/// A vault's multisig, as Trezor needs it: `m`, the account xpubs (in a
/// fixed order; with LEXICOGRAPHIC Trezor's fingerprint of them doesn't
/// depend on it), and the suffix every key shares below its account.
#[derive(Clone)]
struct Multisig {
    m: usize,
    nodes: Vec<Xpub>,
    suffix: Vec<ChildNumber>,
    /// Each key of the script with its origin.
    keys: Vec<(secp256k1::PublicKey, KeySource)>,
}

impl Multisig {
    /// Accepts `witness_script` only if it is `sortedmulti` and every key in
    /// it derives, at one common suffix, from a global xpub whose origin its
    /// own origin extends; the script is then rebuilt and compared.
    fn resolve<C: Verification>(
        witness_script: &Script,
        origins: &BTreeMap<secp256k1::PublicKey, KeySource>,
        xpubs: &BTreeMap<Xpub, KeySource>,
        secp: &Secp256k1<C>,
    ) -> Result<Self, String> {
        let (m, script_keys) = parse_sortedmulti(witness_script)
            .ok_or("the witness script is not a sorted multisig")?;
        if origins.len() != script_keys.len() {
            return Err("its key origins don't match the script's keys".into());
        }
        let mut suffix: Option<Vec<ChildNumber>> = None;
        let mut nodes = Vec::with_capacity(script_keys.len());
        let mut keys = Vec::with_capacity(script_keys.len());
        for key in &script_keys {
            let (fingerprint, path) = origins
                .get(key)
                .ok_or("a key of the script has no origin")?;
            let (xpub, rest) = xpubs
                .iter()
                .filter(|(_, (xpub_fingerprint, _))| xpub_fingerprint == fingerprint)
                .find_map(|(xpub, (_, account))| {
                    let rest = path.as_ref().strip_prefix(account.as_ref())?;
                    let normal = !rest.is_empty() && rest.iter().all(ChildNumber::is_normal);
                    let derives = normal && xpub.derive_pub(secp, &rest).ok()?.public_key == *key;
                    derives.then_some((xpub, rest))
                })
                .ok_or_else(|| {
                    format!("no global xpub derives the key at {path} (fingerprint {fingerprint})")
                })?;
            match &suffix {
                None => suffix = Some(rest.to_vec()),
                Some(shared) if shared.as_slice() == rest => {}
                Some(_) => {
                    return Err("the keys derive at different paths below their accounts".into());
                }
            }
            nodes.push(*xpub);
            keys.push((*key, (*fingerprint, path.clone())));
        }
        nodes.sort_by_key(|node| (node.public_key.serialize(), node.chain_code.to_bytes()));
        nodes.dedup();
        if nodes.len() != script_keys.len() {
            return Err("an account appears twice in the multisig".into());
        }
        if multisig_script(m, &script_keys) != *witness_script {
            return Err("the witness script doesn't rebuild from its keys".into());
        }
        Ok(Self {
            m,
            nodes,
            suffix: suffix.expect("a sorted multisig has keys"),
            keys,
        })
    }

    /// The signer's one key in this multisig, and its full path.
    fn signer(
        &self,
        signer: Fingerprint,
    ) -> Result<(secp256k1::PublicKey, DerivationPath), String> {
        let mut ours = self
            .keys
            .iter()
            .filter(|(_, (fingerprint, _))| *fingerprint == signer);
        match (ours.next(), ours.next()) {
            (Some((key, (_, path))), None) => Ok((*key, path.clone())),
            (None, _) => Err(format!("no key of fingerprint {signer}")),
            (Some(_), Some(_)) => Err(format!("fingerprint {signer} holds two keys")),
        }
    }

    fn same_vault(&self, other: &Self) -> bool {
        self.m == other.m && self.nodes == other.nodes
    }

    fn proto(&self) -> MultisigRedeemScriptType {
        let mut multisig = MultisigRedeemScriptType::new();
        multisig.m = Some(self.m as u32);
        multisig.nodes = self.nodes.iter().map(node_of).collect();
        multisig.address_n = self.suffix.iter().map(|child| u32::from(*child)).collect();
        multisig.pubkeys_order = Some(EnumOrUnknown::new(MultisigPubkeysOrder::LEXICOGRAPHIC));
        multisig
    }
}

/// An output goes to Trezor as change only if it is this same vault, at a
/// vault path of the signer's on this network. Trezor then re-derives it and
/// leaves it off the screen. Anything else is an external output, which the
/// user confirms on the device.
fn change_output<C: Verification>(
    txout: &TxOut,
    output: &PsbtOutput,
    vault: &Multisig,
    signer: Fingerprint,
    network: Network,
    xpubs: &BTreeMap<Xpub, KeySource>,
    secp: &Secp256k1<C>,
) -> Option<TxOutput> {
    let witness_script = output.witness_script.as_ref()?;
    if txout.script_pubkey != ScriptBuf::new_p2wsh(&witness_script.wscript_hash()) {
        return None;
    }
    let multisig = Multisig::resolve(witness_script, &output.bip32_derivation, xpubs, secp).ok()?;
    if !multisig.same_vault(vault) {
        return None;
    }
    let (_, signer_path) = multisig.signer(signer).ok()?;
    if !is_vault_key_path(&signer_path, network) {
        return None;
    }
    let mut proto = TxOutput::new();
    proto.address_n = path_u32(&signer_path);
    proto.amount = Some(txout.value.to_sat());
    proto.script_type = Some(EnumOrUnknown::new(OutputScriptType::PAYTOWITNESS));
    proto.multisig = MessageField::some(multisig.proto());
    Some(proto)
}

fn external_output(txout: &TxOut, network: Network) -> Result<TxOutput, String> {
    if txout.script_pubkey.is_op_return() {
        return Err("OP_RETURN outputs are not signed".into());
    }
    let address = Address::from_script(&txout.script_pubkey, network)
        .map_err(|_| "pays a script with no address".to_owned())?;
    let mut proto = TxOutput::new();
    proto.address = Some(address.to_string());
    proto.amount = Some(txout.value.to_sat());
    proto.script_type = Some(EnumOrUnknown::new(OutputScriptType::PAYTOADDRESS));
    Ok(proto)
}

/// `OP_m <key>… OP_n OP_CHECKMULTISIG` with compressed keys in strictly
/// increasing order (BIP-67), 1 ≤ m ≤ n ≤ 15.
fn parse_sortedmulti(script: &Script) -> Option<(usize, Vec<secp256k1::PublicKey>)> {
    let small_int = |instruction: Instruction| match instruction {
        Instruction::Op(op) if (0x51..=0x60).contains(&op.to_u8()) => {
            Some(usize::from(op.to_u8() - 0x50))
        }
        _ => None,
    };
    let mut instructions = script.instructions();
    let m = small_int(instructions.next()?.ok()?)?;
    let mut keys = Vec::new();
    let n = loop {
        match instructions.next()?.ok()? {
            Instruction::PushBytes(bytes) if bytes.len() == 33 => {
                keys.push(secp256k1::PublicKey::from_slice(bytes.as_bytes()).ok()?);
            }
            other => break small_int(other)?,
        }
    };
    match instructions.next()?.ok()? {
        Instruction::Op(op) if op == OP_CHECKMULTISIG => {}
        _ => return None,
    }
    let sorted = keys
        .windows(2)
        .all(|pair| pair[0].serialize() < pair[1].serialize());
    let valid = instructions.next().is_none()
        && n == keys.len()
        && (1..=n).contains(&m)
        && n <= 15
        && sorted;
    valid.then_some((m, keys))
}

fn multisig_script(m: usize, keys: &[secp256k1::PublicKey]) -> ScriptBuf {
    let builder = keys
        .iter()
        .fold(Builder::new().push_int(m as i64), |builder, key| {
            builder.push_key(&bitcoin::PublicKey::new(*key))
        });
    builder
        .push_int(keys.len() as i64)
        .push_opcode(OP_CHECKMULTISIG)
        .into_script()
}

fn node_of(xpub: &Xpub) -> HDNodeType {
    let mut node = HDNodeType::new();
    node.depth = Some(u32::from(xpub.depth));
    node.fingerprint = Some(u32::from_be_bytes(xpub.parent_fingerprint.to_bytes()));
    node.child_num = Some(u32::from(xpub.child_number));
    node.chain_code = Some(xpub.chain_code.to_bytes().to_vec());
    node.public_key = Some(xpub.public_key.serialize().to_vec());
    node
}

fn xpub_of(node: &HDNodeType, network: Network) -> Result<Xpub, TrezorError> {
    let (Some(depth), Some(parent), Some(child), Some(chain_code), Some(public_key)) = (
        node.depth,
        node.fingerprint,
        node.child_num,
        node.chain_code.as_deref(),
        node.public_key.as_deref(),
    ) else {
        return Err(protocol("an incomplete HD node"));
    };
    let chain_code: [u8; 32] = chain_code
        .try_into()
        .map_err(|_| protocol("a chain code that isn't 32 bytes"))?;
    Ok(Xpub {
        network: NetworkKind::from(network),
        depth: u8::try_from(depth).map_err(|_| protocol("an HD node deeper than 255"))?,
        parent_fingerprint: Fingerprint::from(parent.to_be_bytes()),
        child_number: ChildNumber::from(child),
        public_key: secp256k1::PublicKey::from_slice(public_key)
            .map_err(|_| protocol("an invalid public key"))?,
        chain_code: ChainCode::from(chain_code),
    })
}

/// BIP-44's coin type: 0 on mainnet, 1 on every test network.
fn coin_type_of(network: Network) -> u32 {
    u32::from(network != Network::Bitcoin)
}

/// `m/48'/coin'/account'/2'`: a BIP-48 P2WSH multisig account on `network`.
fn is_bip48_p2wsh_account(path: &[ChildNumber], network: Network) -> bool {
    let hardened = |index| ChildNumber::Hardened { index };
    matches!(path, [purpose, coin, account, script]
        if *purpose == hardened(48)
            && *coin == hardened(coin_type_of(network))
            && account.is_hardened()
            && *script == hardened(2))
}

/// A vault key's whole path: a BIP-48 P2WSH account on `network`, then
/// receive (0) or change (1), then an index. That is the shape mebit's
/// descriptors give every key, and the one Trezor's own BIP-48 check expects.
fn is_vault_key_path(path: &DerivationPath, network: Network) -> bool {
    match path.as_ref() {
        [account @ .., chain, index] => {
            is_bip48_p2wsh_account(account, network)
                && matches!(chain, ChildNumber::Normal { index: 0 | 1 })
                && index.is_normal()
        }
        _ => false,
    }
}

/// Trezor names transactions by txid in display byte order.
fn trezor_hash(txid: &Txid) -> [u8; 32] {
    let mut hash = txid.to_byte_array();
    hash.reverse();
    hash
}

fn path_u32(path: &DerivationPath) -> Vec<u32> {
    path.as_ref()
        .iter()
        .map(|child| u32::from(*child))
        .collect()
}

fn count(n: usize) -> Result<u32, TrezorError> {
    u32::try_from(n).map_err(|_| refuse("too many inputs or outputs"))
}

fn refuse(why: impl Into<String>) -> TrezorError {
    TrezorError::InvalidRequest(why.into())
}

fn protocol(why: impl Into<String>) -> TrezorError {
    TrezorError::Protocol(why.into())
}

fn out_of_range(what: &str, index: usize) -> TrezorError {
    protocol(format!(
        "the Trezor asked for {what} {index}, which doesn't exist"
    ))
}
