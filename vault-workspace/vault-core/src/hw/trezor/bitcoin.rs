//! Bitcoin on the Safe 7: account xpubs, and co-signing a vault's PSBT
//! through Trezor's `SignTx` exchange.
//!
//! Trezor doesn't take a PSBT. It asks for the transaction piece by piece
//! (`TxRequest`), and for every input's previous transaction, which it hashes
//! to check amounts (`core/src/apps/bitcoin/sign_tx/` in trezor-firmware). So
//! everything it asks for is prepared here from the PSBT, up front, and the
//! PSBT is refused before anything is sent unless it is exactly the shape
//! mebit builds: a P2WSH `sortedmulti` vault spend where this Safe 7 holds one
//! key of every input. Whatever comes back is checked by
//! [`crate::hw::psbt_check`] against sighashes computed here.
//!
//! The vault's other keys reach Trezor as `MultisigRedeemScriptType`:
//! account xpubs (`nodes`), the shared non-hardened suffix (`address_n`), `m`,
//! and `pubkeys_order = LEXICOGRAPHIC`, which is BIP-67, i.e. `sortedmulti`
//! (`core/src/apps/bitcoin/multisig.py`). The account xpubs come from the
//! PSBT's global xpubs (BIP-174 `PSBT_GLOBAL_XPUB`).

use std::collections::BTreeMap;

use bitcoin::bip32::{ChainCode, ChildNumber, DerivationPath, Fingerprint, KeySource, Xpub};
use bitcoin::hashes::Hash;
use bitcoin::opcodes::all::OP_CHECKMULTISIG;
use bitcoin::psbt::Output as PsbtOutput;
use bitcoin::script::{Builder, Instruction, Script, ScriptBuf};
use bitcoin::secp256k1::{self, Secp256k1, Verification};
use bitcoin::{Address, EcdsaSighashType, Network, NetworkKind, Psbt, Transaction, TxOut, Txid};
use protobuf::{EnumOrUnknown, MessageField};

use super::protos::bitcoin::{
    GetPublicKey, InputScriptType, MultisigPubkeysOrder, MultisigRedeemScriptType,
    OutputScriptType, PrevInput, PrevOutput, PrevTx, PublicKey, SignTx, TxAckInput, TxAckOutput,
    TxAckPrevInput, TxAckPrevMeta, TxAckPrevOutput, TxInput, TxOutput, TxRequest, tx_ack_input,
    tx_ack_output, tx_ack_prev_input, tx_ack_prev_output, tx_request::RequestType,
};
use super::protos::common::HDNodeType;
use super::{TrezorError, encode};
use crate::hw::psbt_check;

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
    /// Only account paths mebit uses: BIP-48 P2WSH multisig, and BIP-84 (to
    /// compare against what Trezor Suite shows). Anything else is refused.
    pub(super) fn new(
        path: &DerivationPath,
        network: Network,
        options: XpubOptions,
    ) -> Result<(Self, GetPublicKey), TrezorError> {
        let coin = u32::from(network != Network::Bitcoin);
        let hardened = |index| ChildNumber::Hardened { index };
        let allowed = match path.as_ref() {
            [purpose, coin_type, account, script] => {
                *purpose == hardened(48)
                    && *coin_type == hardened(coin)
                    && account.is_hardened()
                    && *script == hardened(2)
            }
            [purpose, coin_type, account] => {
                *purpose == hardened(84) && *coin_type == hardened(coin) && account.is_hardened()
            }
            _ => false,
        };
        if !allowed {
            return Err(TrezorError::InvalidRequest(format!(
                "{path} is not a BIP-48 P2WSH or BIP-84 account path on {network}"
            )));
        }

        let mut request = GetPublicKey::new();
        request.address_n = path_u32(path);
        request.coin_name = Some(coin_name(network).into());
        request.script_type = Some(EnumOrUnknown::new(InputScriptType::SPENDWITNESS));
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
    signer_keys: Vec<secp256k1::PublicKey>,
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

        let secp = Secp256k1::verification_only();
        let mut vault: Option<Multisig> = None;
        let mut inputs = Vec::with_capacity(tx.input.len());
        let mut signer_keys = Vec::with_capacity(tx.input.len());
        let mut previous = BTreeMap::new();
        for (index, (txin, input)) in tx.input.iter().zip(&psbt.inputs).enumerate() {
            let refuse_input = |why: &str| refuse(format!("input {index}: {why}"));
            let prevout = txin.previous_output;
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
            if input
                .sighash_type
                .is_some_and(|sighash| sighash.ecdsa_hash_ty() != Ok(EcdsaSighashType::All))
            {
                return Err(refuse_input("only SIGHASH_ALL is signed"));
            }
            let witness_script = input
                .witness_script
                .as_ref()
                .ok_or_else(|| refuse_input("no witness script"))?;
            if spent.script_pubkey != ScriptBuf::new_p2wsh(&witness_script.wscript_hash()) {
                return Err(refuse_input("the witness script is not what it spends"));
            }
            let multisig =
                Multisig::resolve(witness_script, &input.bip32_derivation, &psbt.xpub, &secp)
                    .map_err(|why| refuse_input(&why))?;
            let (signer_key, signer_path) =
                multisig.signer(signer).map_err(|why| refuse_input(&why))?;
            match &vault {
                None => vault = Some(multisig.clone()),
                Some(first) if first.same_vault(&multisig) => {}
                Some(_) => return Err(refuse_input("spends from a different vault than input 0")),
            }

            let mut proto = TxInput::new();
            proto.address_n = path_u32(&signer_path);
            proto.prev_hash = Some(trezor_hash(&prevout.txid).to_vec());
            proto.prev_index = Some(prevout.vout);
            proto.sequence = Some(txin.sequence.0);
            proto.script_type = Some(EnumOrUnknown::new(InputScriptType::SPENDWITNESS));
            proto.multisig = MessageField::some(multisig.proto());
            proto.amount = Some(spent.value.to_sat());
            inputs.push(proto);
            signer_keys.push(signer_key);
            previous.insert(trezor_hash(&prevout.txid), prev_tx.clone());
        }
        let vault = vault.expect("at least one input");

        let mut outputs = Vec::with_capacity(tx.output.len());
        for (index, (txout, output)) in tx.output.iter().zip(&psbt.outputs).enumerate() {
            let proto = match change_output(txout, output, &vault, signer, &psbt.xpub, &secp) {
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
        for (index, der) in self.signatures.iter().enumerate() {
            let Some(der) = der else { continue };
            // Trezor returns DER without the sighash byte; it signs SIGHASH_ALL.
            let signature = secp256k1::ecdsa::Signature::from_der(der)
                .map_err(|_| psbt_check::SignatureCheckError::InvalidSignature { input: index })?;
            signed.inputs[index].partial_sigs.insert(
                bitcoin::PublicKey::new(self.signer_keys[index]),
                bitcoin::ecdsa::Signature::sighash_all(signature),
            );
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

/// An output goes to Trezor as change only if it is this same vault, at the
/// signer's own path. Trezor then re-derives it and leaves it off the screen.
/// Anything else is an external output, which the user confirms on the device.
fn change_output<C: Verification>(
    txout: &TxOut,
    output: &PsbtOutput,
    vault: &Multisig,
    signer: Fingerprint,
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
