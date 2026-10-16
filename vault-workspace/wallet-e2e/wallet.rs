use std::collections::{BTreeMap, BTreeSet};

use std::path::Path;

use bip39::Mnemonic;
use bitcoin::bip32::{ChildNumber, DerivationPath, Fingerprint, Xpriv, Xpub};
use bitcoin::hex::FromHex;
use bitcoin::psbt::{GetKey, KeyRequest};
use bitcoin::secp256k1::{Secp256k1, Signing as SecpSigning};
use bitcoin::{
    Amount, EcdsaSighashType, Network, NetworkKind, OutPoint, PrivateKey, Psbt, ScriptBuf,
    Sequence, Transaction, TxIn, TxOut, Witness, absolute, transaction,
};
use miniscript::descriptor::{DescriptorXKey, Wildcard};
use miniscript::psbt::PsbtExt;
use miniscript::{Descriptor, DescriptorPublicKey, Threshold};
use vault_core::descriptor::{build_multisig_descriptor, derive_address_at};
use vault_core::hw::psbt_check::{validate_signing_policy, verify_device_signatures};
use vault_core::keys::{
    HwVendor, KeySourceType, Purpose, ScriptType, VaultKey, account_multisig_xpub_from_mnemonic,
    account_xpub_from_mnemonic, generate_entropy, generate_master_xpriv, generate_mnemonic,
    generate_seed,
};
use zeroize::Zeroizing;

use super::Result;
use super::model::{Case, FEE_CAP, FEE_RATE, Family, PAYMENT_SATS, RATE_CAP, Slot, Source};
use super::records::{
    Account, Checkpoint, Enrollment, Finalized, Identity, Progress, Signing, Store, atomic_write,
};

fn account_from_mnemonic(mnemonic: &Mnemonic, family: Family) -> Result<(Xpriv, Xpub)> {
    Ok(match family {
        Family::Bip44 => {
            account_xpub_from_mnemonic(mnemonic, "", Purpose::Bip44, Network::Testnet4, 0)?
        }
        Family::Bip49 => {
            account_xpub_from_mnemonic(mnemonic, "", Purpose::Bip49, Network::Testnet4, 0)?
        }
        Family::Bip84 => {
            account_xpub_from_mnemonic(mnemonic, "", Purpose::Bip84, Network::Testnet4, 0)?
        }
        Family::Bip86 => {
            account_xpub_from_mnemonic(mnemonic, "", Purpose::Bip86, Network::Testnet4, 0)?
        }
        Family::P2wsh => account_multisig_xpub_from_mnemonic(
            mnemonic,
            "",
            Network::Testnet4,
            0,
            ScriptType::P2wsh,
        )?,
    })
}

pub fn mnemonic_identity(mnemonic: &Mnemonic) -> Result<Identity> {
    let secp = Secp256k1::new();
    let mut master = generate_master_xpriv(Network::Testnet4, &generate_seed(mnemonic, ""))?;
    let fingerprint = master.fingerprint(&secp).to_string();
    master.private_key.non_secure_erase();
    let mut accounts = BTreeMap::new();
    for family in Family::ALL {
        let (mut private, public) = account_from_mnemonic(mnemonic, family)?;
        private.private_key.non_secure_erase();
        accounts.insert(
            family,
            Account {
                origin: format!("m/{}", family.path()),
                xpub: public.to_string(),
            },
        );
    }
    Ok(Identity {
        physical_id: format!("software-{fingerprint}"),
        firmware: "vault-core software signer".into(),
        fingerprint,
        accounts,
    })
}

pub fn hot_mnemonic(store: &Store, slot: Slot, create: bool) -> Result<Mnemonic> {
    if slot.source != Source::Hot {
        return Err("software secret requested for hardware slot".into());
    }
    let path = store.secrets.join(format!("{slot}.mnemonic"));
    if path.exists() {
        require_private(&path)?;
        let phrase = Zeroizing::new(std::fs::read_to_string(path)?);
        return Ok(Mnemonic::parse_normalized(phrase.trim())?);
    }
    if !create {
        return Err(format!("missing software secret for {slot}").into());
    }
    let mnemonic = generate_mnemonic(&*generate_entropy()?)?;
    let phrase = Zeroizing::new(mnemonic.to_string());
    atomic_write(&path, phrase.as_bytes())?;
    Ok(mnemonic)
}

pub fn require_private(path: &Path) -> Result<()> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if std::fs::metadata(path)?.permissions().mode() & 0o077 != 0 {
            return Err(format!("{} must have private permissions", path.display()).into());
        }
    }
    Ok(())
}

pub fn destination(store: &Store, create: bool) -> Result<bitcoin::Address> {
    let path = store.secrets.join("destination.mnemonic");
    let mnemonic = if path.exists() {
        require_private(&path)?;
        let phrase = Zeroizing::new(std::fs::read_to_string(&path)?);
        Mnemonic::parse_normalized(phrase.trim())?
    } else {
        if !create {
            return Err("missing test-owned destination secret".into());
        }
        let mnemonic = generate_mnemonic(&*generate_entropy()?)?;
        let phrase = Zeroizing::new(mnemonic.to_string());
        atomic_write(&path, phrase.as_bytes())?;
        mnemonic
    };
    let (mut key, xpub) = account_from_mnemonic(&mnemonic, Family::Bip84)?;
    key.private_key.non_secure_erase();
    let child = xpub.derive_pub(
        &Secp256k1::verification_only(),
        &DerivationPath::from(vec![
            ChildNumber::Normal { index: 0 },
            ChildNumber::Normal { index: 0 },
        ]),
    )?;
    let pubkey = bitcoin::CompressedPublicKey(child.public_key);
    Ok(bitcoin::Address::p2wpkh(&pubkey, Network::Testnet4))
}

pub fn validate_enrollment(enrollment: &Enrollment) -> Result<()> {
    let identity = &enrollment.identity;
    let _: Fingerprint = identity.fingerprint.parse()?;
    if identity.physical_id.is_empty() || identity.accounts.len() != Family::ALL.len() {
        return Err("incomplete key holder identity".into());
    }
    for family in Family::ALL {
        let account = identity.accounts.get(&family).ok_or("missing account")?;
        let origin: DerivationPath = account.origin.parse()?;
        let xpub: Xpub = account.xpub.parse()?;
        if origin != family.path()
            || xpub.network != NetworkKind::Test
            || usize::from(xpub.depth) != origin.len()
            || origin.as_ref().last() != Some(&xpub.child_number)
        {
            return Err(format!("invalid {} account origin or xpub", family.name()).into());
        }
    }
    Ok(())
}

pub fn independent_holders(enrolled: &[Enrollment]) -> Result<()> {
    let mut physical = BTreeSet::new();
    let mut fingerprints = BTreeSet::new();
    let mut keys = BTreeSet::new();
    let mut slots = BTreeSet::new();
    for enrollment in enrolled {
        validate_enrollment(enrollment)?;
        if !slots.insert(enrollment.slot)
            || !physical.insert(&enrollment.identity.physical_id)
            || !fingerprints.insert(&enrollment.identity.fingerprint)
        {
            return Err(
                "one physical device or key identity cannot count as multiple independent holders"
                    .into(),
            );
        }
        for account in enrollment.identity.accounts.values() {
            let xpub: Xpub = account.xpub.parse()?;
            if !keys.insert((xpub.public_key, xpub.chain_code)) {
                return Err("duplicate account key".into());
            }
        }
    }
    Ok(())
}

pub fn vault_key(enrollment: &Enrollment, family: Family) -> Result<VaultKey> {
    let account = enrollment
        .identity
        .accounts
        .get(&family)
        .ok_or("account missing")?;
    Ok(VaultKey {
        label: enrollment.slot.to_string(),
        source_type: match enrollment.slot.source {
            Source::Hot => KeySourceType::MebitThisDevice,
            Source::Jade => KeySourceType::HardwareWallet(HwVendor::Jade),
            Source::Trezor => KeySourceType::HardwareWallet(HwVendor::TrezorSafe7),
        },
        fingerprint: enrollment.identity.fingerprint.parse()?,
        xpub: account.xpub.parse()?,
        derivation_path: account.origin.clone(),
    })
}

pub fn descriptor(
    case: &Case,
    enrolled: &[Enrollment],
    branch: u32,
) -> Result<Descriptor<DescriptorPublicKey>> {
    independent_holders(enrolled)?;
    if branch > 1
        || enrolled.iter().map(|e| e.slot).collect::<BTreeSet<_>>()
            != case.holders.iter().copied().collect()
    {
        return Err("descriptor branch or enrolled holder set differs from manifest".into());
    }
    let mut keys = Vec::new();
    for slot in &case.holders {
        let enrollment = enrolled
            .iter()
            .find(|e| e.slot == *slot)
            .ok_or("holder missing")?;
        let key = vault_key(enrollment, case.family)?;
        keys.push(DescriptorPublicKey::XPub(DescriptorXKey {
            origin: Some((key.fingerprint, key.derivation_path.parse()?)),
            xkey: key.xpub,
            derivation_path: DerivationPath::from(vec![ChildNumber::Normal { index: branch }]),
            wildcard: Wildcard::Unhardened,
        }));
    }
    let result = match case.family {
        Family::Bip44 => Descriptor::new_pkh(keys.remove(0))?,
        Family::Bip49 => Descriptor::new_sh_wpkh(keys.remove(0))?,
        Family::Bip84 => Descriptor::new_wpkh(keys.remove(0))?,
        Family::Bip86 => Descriptor::new_tr(keys.remove(0), None)?,
        Family::P2wsh => Descriptor::new_wsh_sortedmulti(Threshold::new(case.threshold, keys)?)?,
    };
    if case.family == Family::P2wsh && branch == 0 {
        let xpubs = enrolled
            .iter()
            .map(|e| Ok(vault_key(e, case.family)?.xpub))
            .collect::<Result<Vec<_>>>()?;
        let core = build_multisig_descriptor(&xpubs, case.threshold)?;
        for index in [0, 1, 7, case.receive.index] {
            if derive_address_at(&core, index, Network::Testnet4)?
                != derive_address_at(&result, index, Network::Testnet4)?
            {
                return Err(
                    "origin-bearing receive descriptor differs from vault-core descriptor".into(),
                );
            }
        }
    }
    result.sanity_check()?;
    Ok(result)
}

pub fn independent_script(
    case: &Case,
    enrolled: &[Enrollment],
    branch: u32,
    index: u32,
) -> Result<ScriptBuf> {
    let secp = Secp256k1::verification_only();
    let path = DerivationPath::from(vec![
        ChildNumber::Normal { index: branch },
        ChildNumber::Normal { index },
    ]);
    let mut keys = enrolled
        .iter()
        .map(|e| {
            let xpub = vault_key(e, case.family)?.xpub;
            Ok(bitcoin::PublicKey::new(
                xpub.derive_pub(&secp, &path)?.public_key,
            ))
        })
        .collect::<Result<Vec<_>>>()?;
    Ok(match case.family {
        Family::Bip44 => ScriptBuf::new_p2pkh(&keys[0].pubkey_hash()),
        Family::Bip49 => ScriptBuf::new_p2wpkh(&keys[0].wpubkey_hash()?).to_p2sh(),
        Family::Bip84 => ScriptBuf::new_p2wpkh(&keys[0].wpubkey_hash()?),
        Family::Bip86 => ScriptBuf::new_p2tr(&secp, keys[0].inner.x_only_public_key().0, None),
        Family::P2wsh => {
            keys.sort_by_key(|k| k.to_bytes());
            let mut builder = bitcoin::script::Builder::new().push_int(case.threshold as i64);
            for key in keys {
                builder = builder.push_key(&key);
            }
            builder
                .push_int(enrolled.len() as i64)
                .push_opcode(bitcoin::opcodes::all::OP_CHECKMULTISIG)
                .into_script()
                .to_p2wsh()
        }
    })
}

pub fn check_derivation(case: &Case, enrolled: &[Enrollment]) -> Result<()> {
    for branch in [0, 1] {
        let descriptor = descriptor(case, enrolled, branch)?;
        for index in [0, 1, 7, case.receive.index] {
            if derive_address_at(&descriptor, index, Network::Testnet4)?.script_pubkey()
                != independent_script(case, enrolled, branch, index)?
            {
                return Err(
                    "address derivation differs from independent script construction".into(),
                );
            }
        }
    }
    Ok(())
}

struct AccountResolver {
    key: Xpriv,
    fingerprint: Fingerprint,
    origin: DerivationPath,
}

impl Drop for AccountResolver {
    fn drop(&mut self) {
        self.key.private_key.non_secure_erase();
    }
}

impl GetKey for AccountResolver {
    type Error = bitcoin::bip32::Error;

    fn get_key<C: SecpSigning>(
        &self,
        request: KeyRequest,
        secp: &Secp256k1<C>,
    ) -> std::result::Result<Option<PrivateKey>, Self::Error> {
        let KeyRequest::Bip32((fingerprint, path)) = request else {
            return Ok(None);
        };
        if fingerprint != self.fingerprint || !path.as_ref().starts_with(self.origin.as_ref()) {
            return Ok(None);
        }
        let suffix = &path.as_ref()[self.origin.len()..];
        if suffix.len() != 2
            || !matches!(suffix[0], ChildNumber::Normal { index: 0 | 1 })
            || suffix[1].is_hardened()
        {
            return Ok(None);
        }
        let mut derived = self
            .key
            .derive_priv(secp, &DerivationPath::from(suffix.to_vec()))?;
        let result = derived.to_priv();
        derived.private_key.non_secure_erase();
        Ok(Some(result))
    }
}

pub fn sign_hot(
    psbt: &Psbt,
    mnemonic: &Mnemonic,
    enrollment: &Enrollment,
    family: Family,
) -> Result<Psbt> {
    validate_signing_policy(psbt)?;
    super::records::check_identity(&enrollment.identity, &mnemonic_identity(mnemonic)?)?;
    sign_mnemonic(psbt, mnemonic, enrollment, family)
}

fn sign_mnemonic(
    psbt: &Psbt,
    mnemonic: &Mnemonic,
    enrollment: &Enrollment,
    family: Family,
) -> Result<Psbt> {
    sign_resolved(psbt, &account_resolver(mnemonic, enrollment, family)?)
}

fn account_resolver(
    mnemonic: &Mnemonic,
    enrollment: &Enrollment,
    family: Family,
) -> Result<AccountResolver> {
    let (key, xpub) = account_from_mnemonic(mnemonic, family)?;
    if xpub.to_string()
        != enrollment
            .identity
            .accounts
            .get(&family)
            .ok_or("missing account")?
            .xpub
    {
        return Err("account key differs from enrolled origin".into());
    }
    Ok(AccountResolver {
        key,
        fingerprint: enrollment.identity.fingerprint.parse()?,
        origin: family.path(),
    })
}

fn sign_resolved(psbt: &Psbt, resolver: &AccountResolver) -> Result<Psbt> {
    let mut signed = psbt.clone();
    signed
        .sign(resolver, &Secp256k1::new())
        .map_err(|(_, errors)| format!("software signing failed: {errors:?}"))?;
    Ok(verify_device_signatures(
        psbt,
        signed,
        resolver.fingerprint,
    )?)
}

pub fn spend_psbt(
    case: &Case,
    enrolled: &[Enrollment],
    previous: &Transaction,
    outpoint: OutPoint,
    destination: &bitcoin::Address,
) -> Result<Psbt> {
    if previous.compute_txid() != outpoint.txid {
        return Err("previous transaction txid differs from funding outpoint".into());
    }
    let output = previous
        .output
        .get(outpoint.vout as usize)
        .ok_or("funding vout missing")?;
    let receive = descriptor(case, enrolled, 0)?;
    let change = descriptor(case, enrolled, 1)?;
    let receive_at = receive.at_derivation_index(case.receive.index)?;
    let change_at = change.at_derivation_index(case.change.index)?;
    if output.script_pubkey != independent_script(case, enrolled, 0, case.receive.index)?
        || output.script_pubkey != receive_at.script_pubkey()
        || output.value.to_sat() != case.funding_target_sats()
    {
        return Err("funding script or amount differs from prepared case".into());
    }
    let mut tx = Transaction {
        version: transaction::Version::TWO,
        lock_time: absolute::LockTime::ZERO,
        input: vec![TxIn {
            previous_output: outpoint,
            script_sig: ScriptBuf::new(),
            sequence: Sequence::ENABLE_RBF_NO_LOCKTIME,
            witness: Witness::new(),
        }],
        output: vec![
            TxOut {
                value: Amount::from_sat(PAYMENT_SATS),
                script_pubkey: destination.script_pubkey(),
            },
            TxOut {
                value: Amount::ZERO,
                script_pubkey: change_at.script_pubkey(),
            },
        ],
    };
    let weight = tx.weight().to_wu() + receive_at.max_weight_to_satisfy()?.to_wu() + 2;
    let fee = weight.div_ceil(4) * FEE_RATE;
    let change_sats = output
        .value
        .to_sat()
        .checked_sub(PAYMENT_SATS + fee)
        .ok_or("funding cannot cover payment and fee")?;
    if fee > FEE_CAP || change_sats < 546 {
        return Err("fee cap or change dust policy violation".into());
    }
    tx.output[1].value = Amount::from_sat(change_sats);
    let mut psbt = Psbt::from_unsigned_tx(tx)?;
    psbt.inputs[0].non_witness_utxo = Some(previous.clone());
    if case.family != Family::Bip44 {
        psbt.inputs[0].witness_utxo = Some(output.clone());
    }
    psbt.update_input_with_descriptor(0, &receive_at)?;
    psbt.update_output_with_descriptor(1, &change_at)?;
    psbt.inputs[0].sighash_type = Some(if case.family == Family::Bip86 {
        bitcoin::TapSighashType::Default.into()
    } else {
        EcdsaSighashType::All.into()
    });
    for enrollment in enrolled {
        let key = vault_key(enrollment, case.family)?;
        psbt.xpub
            .insert(key.xpub, (key.fingerprint, key.derivation_path.parse()?));
    }
    validate_signing_policy(&psbt)?;
    Ok(psbt)
}

pub fn validate_psbt(
    case: &Case,
    enrolled: &[Enrollment],
    previous: &Transaction,
    outpoint: OutPoint,
    destination: &bitcoin::Address,
    actual: &Psbt,
) -> Result<()> {
    let expected = spend_psbt(case, enrolled, previous, outpoint, destination)?;
    let mut stripped = actual.clone();
    for input in &mut stripped.inputs {
        input.partial_sigs.clear();
        input.tap_key_sig = None;
    }
    if stripped != expected {
        return Err("PSBT differs from independently reconstructed transaction, prevouts, scripts, outputs, or origins".into());
    }
    Ok(())
}

pub fn finalize(psbt: &Psbt) -> Result<Transaction> {
    let mut finalized = psbt.clone();
    let secp = Secp256k1::verification_only();
    finalized
        .finalize_mut(&secp)
        .map_err(|errors| format!("insufficient or invalid signatures: {errors:?}"))?;
    let tx = finalized.extract(&secp)?;
    let fee = psbt.fee()?.to_sat();
    if fee > FEE_CAP || fee > tx.vsize() as u64 * RATE_CAP {
        return Err("finalized fee exceeds the fixed fee or effective rate cap".into());
    }
    if tx.output.first().map(|o| o.value.to_sat()) != Some(PAYMENT_SATS) {
        return Err("payment intent changed".into());
    }
    Ok(tx)
}

pub fn validate_checkpoint(checkpoint: &Checkpoint) -> Result<()> {
    if checkpoint
        .enrolled
        .iter()
        .map(|e| e.slot)
        .collect::<Vec<_>>()
        != checkpoint.case.holders
    {
        return Err("saved enrolled holders differ from the manifest case".into());
    }
    independent_holders(&checkpoint.enrolled)?;
    if let Some(slot) = checkpoint.remote_slot
        && (slot.source != Source::Trezor || !checkpoint.case.holders.contains(&slot))
    {
        return Err("saved remote signer is not a Trezor holder in this case".into());
    }
    if let Some(request) = &checkpoint.pending_remote {
        super::records::validate_remote_request(request)?;
        if request.case_id != checkpoint.case.id || checkpoint.remote_slot != Some(request.slot) {
            return Err("saved remote request does not match the case's remote signer".into());
        }
        let enrollment = checkpoint
            .enrolled
            .iter()
            .find(|enrollment| enrollment.slot == request.slot)
            .ok_or("saved remote signer is not enrolled in this case")?;
        super::records::check_identity(&enrollment.identity, &request.expected)?;
        match (&request.action, &checkpoint.progress) {
            (
                super::records::DeviceAction::Verify {
                    descriptor, index, ..
                },
                _,
            ) => {
                let receive = descriptor == &checkpoint.receive_descriptor
                    && *index == checkpoint.case.receive.index;
                let change = descriptor == &checkpoint.change_descriptor
                    && *index == checkpoint.case.change.index;
                if !receive && !change {
                    return Err("saved remote address request is not for this case's receive or change address".into());
                }
            }
            (
                super::records::DeviceAction::Sign { psbt, .. },
                Progress::Signing(signing),
            ) => {
                let next = checkpoint.case.signing_subset.get(signing.completed.len());
                if next != Some(&request.slot) || psbt != &signing.signed_psbt {
                    return Err("saved remote PSBT request is stale or out of signer order".into());
                }
            }
            (super::records::DeviceAction::Sign { .. }, _) => {
                return Err("saved remote signing request has no signing checkpoint".into());
            }
            (super::records::DeviceAction::Enroll, _) => {
                return Err("remote enrollment is not supported".into());
            }
        }
    }
    let receive = descriptor(&checkpoint.case, &checkpoint.enrolled, 0)?;
    let change = descriptor(&checkpoint.case, &checkpoint.enrolled, 1)?;
    if checkpoint.receive_descriptor != receive.to_string()
        || checkpoint.change_descriptor != change.to_string()
        || checkpoint.receive_address
            != derive_address_at(&receive, checkpoint.case.receive.index, Network::Testnet4)?
                .to_string()
        || checkpoint.change_address
            != derive_address_at(&change, checkpoint.case.change.index, Network::Testnet4)?
                .to_string()
    {
        return Err("saved account or address changed".into());
    }
    if checkpoint.address_proofs.len() != checkpoint.case.holders.len() * 2 {
        return Err("receive and change address verification is incomplete".into());
    }
    for enrollment in &checkpoint.enrolled {
        for (branch, index, address) in [
            (
                0,
                checkpoint.case.receive.index,
                &checkpoint.receive_address,
            ),
            (1, checkpoint.case.change.index, &checkpoint.change_address),
        ] {
            if checkpoint
                .address_proofs
                .iter()
                .filter(|p| {
                    p.slot == enrollment.slot
                        && p.branch == branch
                        && p.index == index
                        && p.address == *address
                        && p.fingerprint == enrollment.identity.fingerprint
                        && p.firmware == enrollment.identity.firmware
                        && p.verified_at > 0
                        && p.device_displayed == (enrollment.slot.source != Source::Hot)
                })
                .count()
                != 1
            {
                return Err("saved address proof does not match the enrolled case".into());
            }
        }
    }
    Ok(())
}

pub fn validate_signing(checkpoint: &Checkpoint, signing: &Signing) -> Result<()> {
    let outpoint: OutPoint = signing.funding.outpoint.parse()?;
    let previous =
        bitcoin::consensus::deserialize(&Vec::<u8>::from_hex(&signing.funding.transaction_hex)?)?;
    let destination = checkpoint
        .destination
        .parse::<bitcoin::Address<_>>()?
        .require_network(Network::Testnet4)?;
    let unsigned: Psbt = signing.unsigned_psbt.parse()?;
    let signed: Psbt = signing.signed_psbt.parse()?;
    if signing.funding.amount_sats != checkpoint.case.funding_target_sats() {
        return Err("saved funding amount differs from the exact funding target".into());
    }
    validate_psbt(
        &checkpoint.case,
        &checkpoint.enrolled,
        &previous,
        outpoint,
        &destination,
        &unsigned,
    )?;
    validate_psbt(
        &checkpoint.case,
        &checkpoint.enrolled,
        &previous,
        outpoint,
        &destination,
        &signed,
    )?;
    verify_completed(
        &unsigned,
        &signed,
        &signing.completed,
        &checkpoint.case,
        &checkpoint.enrolled,
    )
}

pub fn validate_finalized(checkpoint: &Checkpoint, finalized: &Finalized) -> Result<Transaction> {
    validate_signing(checkpoint, &finalized.signing)?;
    let signed: Psbt = finalized.signing.signed_psbt.parse()?;
    let expected: Transaction =
        bitcoin::consensus::deserialize(&Vec::<u8>::from_hex(&finalized.transaction_hex)?)?;
    if finalize(&signed)? != expected
        || expected.compute_txid().to_string() != finalized.txid
        || expected.vsize() != finalized.vsize
        || finalized.signing.completed != checkpoint.case.signing_subset
        || finalized.fee_sats != signed.fee()?.to_sat()
    {
        return Err(
            "saved finalized bytes, fee, txid, or designated signatures differ from validated PSBT"
                .into(),
        );
    }
    Ok(expected)
}

pub fn verify_completed(
    unsigned: &Psbt,
    signed: &Psbt,
    completed: &[Slot],
    case: &Case,
    enrolled: &[Enrollment],
) -> Result<()> {
    if unsigned.inputs.iter().any(|i| {
        !i.partial_sigs.is_empty()
            || i.tap_key_sig.is_some()
            || !i.tap_script_sigs.is_empty()
            || i.final_script_sig.is_some()
            || i.final_script_witness.is_some()
    }) {
        return Err("unsigned checkpoint contains a signature or finalized input".into());
    }
    if signed.inputs.len() != unsigned.inputs.len() {
        return Err("saved signed PSBT input count changed".into());
    }
    if completed != &case.signing_subset[..completed.len().min(case.signing_subset.len())]
        || completed.len() > case.threshold
    {
        return Err("saved signatures are not the designated signing prefix".into());
    }
    let mut reconstructed = unsigned.clone();
    for slot in completed {
        let fingerprint: Fingerprint = enrolled
            .iter()
            .find(|e| e.slot == *slot)
            .ok_or("signed holder missing")?
            .identity
            .fingerprint
            .parse()?;
        let before = reconstructed.clone();
        for (index, input) in unsigned.inputs.iter().enumerate() {
            for (key, (fp, _)) in &input.bip32_derivation {
                if *fp == fingerprint {
                    let key = bitcoin::PublicKey::new(*key);
                    let signature = *signed.inputs[index]
                        .partial_sigs
                        .get(&key)
                        .ok_or("saved designated signature missing")?;
                    reconstructed.inputs[index]
                        .partial_sigs
                        .insert(key, signature);
                }
            }
            if input
                .tap_key_origins
                .values()
                .any(|(_, (fp, _))| *fp == fingerprint)
            {
                reconstructed.inputs[index].tap_key_sig = Some(
                    signed.inputs[index]
                        .tap_key_sig
                        .ok_or("saved Taproot signature missing")?,
                );
            }
        }
        reconstructed = verify_device_signatures(&before, reconstructed, fingerprint)?;
    }
    if reconstructed != *signed {
        return Err(
            "saved PSBT has signatures outside the designated completed subset or another mutation"
                .into(),
        );
    }
    Ok(())
}

pub fn offline_check() -> Result<serde_json::Value> {
    let rows = super::model::matrix();
    if rows.len() != super::model::CASE_COUNT {
        return Err("matrix count changed".into());
    }
    let mut fixtures = BTreeMap::new();
    for source in [Source::Hot, Source::Jade, Source::Trezor] {
        for number in 1..=7 {
            let slot = Slot { source, number };
            let entropy = [number
                + match source {
                    Source::Hot => 0,
                    Source::Jade => 16,
                    Source::Trezor => 32,
                }; 32];
            let mnemonic = generate_mnemonic(&entropy)?;
            let identity = mnemonic_identity(&mnemonic)?;
            fixtures.insert(
                slot,
                (
                    mnemonic,
                    Enrollment {
                        slot,
                        identity,
                        selector: None,
                        jade_slot_declared_empty: false,
                        jade_owned_descriptor: None,
                    },
                ),
            );
        }
    }
    let mut resolvers = BTreeMap::new();
    for (slot, (mnemonic, enrollment)) in &fixtures {
        for family in Family::ALL {
            resolvers.insert(
                (*slot, family),
                account_resolver(mnemonic, enrollment, family)?,
            );
        }
    }
    let destination = derive_address_at(
        &descriptor(&rows[2], &[fixtures[&rows[2].holders[0]].1.clone()], 0)?,
        0,
        Network::Testnet4,
    )?;
    let mut checked_compositions = BTreeMap::new();
    let mut signed_cases = 0;
    let mut digest = Vec::new();
    for case in &rows {
        let enrolled = case
            .holders
            .iter()
            .map(|slot| fixtures[slot].1.clone())
            .collect::<Vec<_>>();
        let composition = (case.family, case.threshold, case.holders.clone());
        if !checked_compositions.contains_key(&composition) {
            check_derivation(case, &enrolled)?;
            let mut reverse = case.clone();
            reverse.holders.reverse();
            if descriptor(case, &enrolled, 0)?
                .at_derivation_index(7)?
                .script_pubkey()
                != descriptor(&reverse, &enrolled, 0)?
                    .at_derivation_index(7)?
                    .script_pubkey()
            {
                return Err("permutation changed sortedmulti address".into());
            }
            let mut signing_case = case.clone();
            signing_case.receive.index = 7;
            signing_case.change.index = 7;
            let previous = offline_previous(
                &signing_case,
                independent_script(&signing_case, &enrolled, 0, 7)?,
            );
            let outpoint = OutPoint {
                txid: previous.compute_txid(),
                vout: 0,
            };
            let psbt = spend_psbt(&signing_case, &enrolled, &previous, outpoint, &destination)?;
            if finalize(&psbt).is_ok() {
                return Err("unsigned PSBT unexpectedly finalized".into());
            }
            validate_psbt(
                &signing_case,
                &enrolled,
                &previous,
                outpoint,
                &destination,
                &psbt,
            )?;
            checked_compositions.insert(composition.clone(), psbt);
        }
        let unsigned = &checked_compositions[&composition];
        let mut signed = unsigned.clone();
        for slot in &case.signing_subset {
            signed = sign_resolved(&signed, &resolvers[&(*slot, case.family)])?;
        }
        verify_completed(unsigned, &signed, &case.signing_subset, case, &enrolled)?;
        let tx = finalize(&signed)?;
        digest.extend_from_slice(case.id.as_bytes());
        digest.extend_from_slice(tx.compute_txid().as_byte_array());
        signed_cases += 1;
        if signed_cases % 500 == 0 {
            eprintln!(
                "Offline designated subsets finalized {signed_cases}/{}.",
                rows.len()
            );
        }
    }
    use bitcoin::hashes::{Hash, sha256};
    Ok(
        serde_json::json!({"status":"PASS","scope":"offline software fixtures only","required_live_cases":rows.len(),"live_pass":0,"derivation_compositions":checked_compositions.len(),"signed_and_finalized_cases":signed_cases,"determinism_digest":sha256::Hash::hash(&digest).to_string(),"indices":[0,1,7],"subset_signing_index":7,"hardware_behavior":"UNVERIFIED","real_network":"BLOCKED"}),
    )
}

fn offline_previous(case: &Case, script_pubkey: ScriptBuf) -> Transaction {
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
            value: Amount::from_sat(case.funding_target_sats()),
            script_pubkey,
        }],
    }
}

#[cfg(test)]
mod tests {
    use super::super::model::FUNDING_SATS;
    use super::super::records::Progress;
    use super::*;

    fn holders(case: &Case) -> Vec<(Mnemonic, Enrollment)> {
        case.holders
            .iter()
            .enumerate()
            .map(|(i, slot)| {
                let mnemonic = generate_mnemonic(&[(i + 1) as u8; 32]).unwrap();
                let identity = mnemonic_identity(&mnemonic).unwrap();
                (
                    mnemonic,
                    Enrollment {
                        slot: *slot,
                        identity,
                        selector: None,
                        jade_slot_declared_empty: false,
                        jade_owned_descriptor: None,
                    },
                )
            })
            .collect()
    }

    #[test]
    fn scripts_signatures_finalization_and_mutation_rejection() {
        let rows = super::super::model::matrix();
        let cases = rows.iter().take(4).chain(
            rows.iter()
                .filter(|c| c.family == Family::P2wsh && c.holders.len() == 3 && c.threshold == 2)
                .take(1),
        );
        for case in cases {
            let holders = holders(case);
            let enrolled = holders.iter().map(|(_, e)| e.clone()).collect::<Vec<_>>();
            check_derivation(case, &enrolled).unwrap();
            let destination = derive_address_at(
                &descriptor(case, &enrolled, 1).unwrap(),
                7,
                Network::Testnet4,
            )
            .unwrap();
            let previous = offline_previous(
                case,
                independent_script(case, &enrolled, 0, case.receive.index).unwrap(),
            );
            let outpoint = OutPoint {
                txid: previous.compute_txid(),
                vout: 0,
            };
            let psbt = spend_psbt(case, &enrolled, &previous, outpoint, &destination).unwrap();
            assert!(finalize(&psbt).is_err());
            let mut signed = psbt.clone();
            for slot in &case.signing_subset {
                let (mnemonic, enrollment) = holders.iter().find(|(_, e)| e.slot == *slot).unwrap();
                signed = sign_mnemonic(&signed, mnemonic, enrollment, case.family).unwrap();
            }
            assert_eq!(
                finalize(&signed).unwrap().output[0].value.to_sat(),
                PAYMENT_SATS
            );
            validate_psbt(case, &enrolled, &previous, outpoint, &destination, &signed).unwrap();
            for mutation in 0..4 {
                let mut bad = psbt.clone();
                match mutation {
                    0 => bad.unsigned_tx.output[0].value = Amount::from_sat(PAYMENT_SATS + 1),
                    1 => {
                        bad.inputs[0].non_witness_utxo.as_mut().unwrap().output[0].value =
                            Amount::from_sat(FUNDING_SATS + 1)
                    }
                    2 => bad.xpub.clear(),
                    _ => bad.inputs[0].sighash_type = Some(EcdsaSighashType::None.into()),
                }
                assert!(
                    validate_psbt(case, &enrolled, &previous, outpoint, &destination, &bad)
                        .is_err(),
                    "{} mutation {mutation}",
                    case.id
                );
            }
        }
    }

    #[test]
    fn saved_unsigned_baseline_cannot_hide_an_extra_cosigner() {
        let case = super::super::model::matrix()
            .into_iter()
            .find(|c| {
                c.holders.len() == 3
                    && c.threshold == 2
                    && c.holders.iter().all(|s| s.source == Source::Hot)
            })
            .unwrap();
        let fixture = holders(&case);
        let enrolled = fixture.iter().map(|(_, e)| e.clone()).collect::<Vec<_>>();
        let destination = derive_address_at(
            &descriptor(&case, &enrolled, 1).unwrap(),
            7,
            Network::Testnet4,
        )
        .unwrap();
        let previous = offline_previous(
            &case,
            independent_script(&case, &enrolled, 0, case.receive.index).unwrap(),
        );
        let outpoint = OutPoint {
            txid: previous.compute_txid(),
            vout: 0,
        };
        let unsigned = spend_psbt(&case, &enrolled, &previous, outpoint, &destination).unwrap();
        let outsider = fixture
            .iter()
            .find(|(_, e)| !case.signing_subset.contains(&e.slot))
            .unwrap();
        let poisoned = sign_mnemonic(&unsigned, &outsider.0, &outsider.1, case.family).unwrap();
        let mut signed = poisoned.clone();
        for slot in &case.signing_subset {
            let (mnemonic, enrollment) = fixture.iter().find(|(_, e)| e.slot == *slot).unwrap();
            signed = sign_mnemonic(&signed, mnemonic, enrollment, case.family).unwrap();
        }
        let checked = verify_completed(&poisoned, &signed, &case.signing_subset, &case, &enrolled);
        assert!(
            checked.is_err(),
            "accepted unsigned outsider signature: {checked:?}"
        );
    }

    #[test]
    fn funding_target_is_exact_and_reserved_signatures_are_complete() {
        let case = super::super::model::matrix().into_iter().next().unwrap();
        let fixture = holders(&case);
        let enrolled = fixture.iter().map(|(_, e)| e.clone()).collect::<Vec<_>>();
        let destination = derive_address_at(
            &descriptor(&case, &enrolled, 1).unwrap(),
            7,
            Network::Testnet4,
        )
        .unwrap();
        for amount in [FUNDING_SATS - 1, FUNDING_SATS + 1, 5_000_000] {
            let mut previous = offline_previous(
                &case,
                independent_script(&case, &enrolled, 0, case.receive.index).unwrap(),
            );
            previous.output[0].value = Amount::from_sat(amount);
            let outpoint = OutPoint {
                txid: previous.compute_txid(),
                vout: 0,
            };
            assert!(spend_psbt(&case, &enrolled, &previous, outpoint, &destination).is_err());
        }
    }

    #[test]
    fn confirmed_safe7_funding_is_exact_and_returns_surplus_to_case_change_script() {
        use super::super::model::{
            CONFIRMED_SAFE7_P2WSH_CASE_ID, CONFIRMED_SAFE7_P2WSH_FUNDING_SATS,
        };
        use super::super::records::{Confirmation, Funding};
        use bitcoin::consensus::encode::serialize_hex;

        let case = super::super::model::find_case(CONFIRMED_SAFE7_P2WSH_CASE_ID).unwrap();
        assert_eq!(case.receive.index, 12);
        assert_eq!(case.change.branch, 1);
        assert_eq!(case.change.index, 12);
        let fixture = holders(&case);
        let enrolled = fixture.iter().map(|(_, e)| e.clone()).collect::<Vec<_>>();
        let destination = derive_address_at(
            &descriptor(&case, &enrolled, 1).unwrap(),
            7,
            Network::Testnet4,
        )
        .unwrap();
        let previous = offline_previous(
            &case,
            independent_script(&case, &enrolled, 0, case.receive.index).unwrap(),
        );
        assert_eq!(
            previous.output[0].value.to_sat(),
            CONFIRMED_SAFE7_P2WSH_FUNDING_SATS
        );
        let outpoint = OutPoint {
            txid: previous.compute_txid(),
            vout: 0,
        };
        let unsigned = spend_psbt(&case, &enrolled, &previous, outpoint, &destination).unwrap();
        let (mnemonic, enrollment) = &fixture[0];
        let signed = sign_mnemonic(&unsigned, mnemonic, enrollment, case.family).unwrap();
        let change_script = descriptor(&case, &enrolled, 1)
            .unwrap()
            .at_derivation_index(case.change.index)
            .unwrap()
            .script_pubkey();
        let fee = unsigned.fee().unwrap().to_sat();
        assert_eq!(unsigned.unsigned_tx.input[0].previous_output, outpoint);
        assert_eq!(unsigned.unsigned_tx.output[0].value.to_sat(), PAYMENT_SATS);
        assert_eq!(unsigned.unsigned_tx.output[1].script_pubkey, change_script);
        assert_eq!(
            unsigned.unsigned_tx.output[1].value.to_sat(),
            CONFIRMED_SAFE7_P2WSH_FUNDING_SATS - PAYMENT_SATS - fee
        );

        let checkpoint = Checkpoint {
            schema: 1,
            case: case.clone(),
            enrolled: enrolled.clone(),
            receive_descriptor: descriptor(&case, &enrolled, 0).unwrap().to_string(),
            change_descriptor: descriptor(&case, &enrolled, 1).unwrap().to_string(),
            receive_address: derive_address_at(
                &descriptor(&case, &enrolled, 0).unwrap(),
                case.receive.index,
                Network::Testnet4,
            )
            .unwrap()
            .to_string(),
            change_address: derive_address_at(
                &descriptor(&case, &enrolled, 1).unwrap(),
                case.change.index,
                Network::Testnet4,
            )
            .unwrap()
            .to_string(),
            destination: destination.to_string(),
            address_proofs: Vec::new(),
            executions: Vec::new(),
            progress: Progress::Prepared,
            blockers: Vec::new(),
            created_at: 1,
            broadcast_authorized: false,
            genesis_verified_at: 1,
            runner_revision: "offline-test".into(),
            remote_slot: None,
            pending_remote: None,
        };
        let signing = Signing {
            funding: Funding {
                outpoint: outpoint.to_string(),
                transaction_hex: serialize_hex(&previous),
                amount_sats: CONFIRMED_SAFE7_P2WSH_FUNDING_SATS,
                confirmation: Confirmation {
                    block_height: 1,
                    block_hash: "00".repeat(32),
                    tip_height: 1,
                    confirmations: 1,
                    checked_at: 1,
                },
            },
            unsigned_psbt: unsigned.to_string(),
            signed_psbt: signed.to_string(),
            completed: case.signing_subset.clone(),
        };
        validate_signing(&checkpoint, &signing).unwrap();
        for amount in [
            CONFIRMED_SAFE7_P2WSH_FUNDING_SATS - 1,
            CONFIRMED_SAFE7_P2WSH_FUNDING_SATS + 1,
        ] {
            let mut incorrect = signing.clone();
            incorrect.funding.amount_sats = amount;
            assert!(validate_signing(&checkpoint, &incorrect).is_err());
        }

        for amount in [
            CONFIRMED_SAFE7_P2WSH_FUNDING_SATS - 1,
            CONFIRMED_SAFE7_P2WSH_FUNDING_SATS + 1,
        ] {
            let mut incorrect_previous = previous.clone();
            incorrect_previous.output[0].value = Amount::from_sat(amount);
            let incorrect_outpoint = OutPoint {
                txid: incorrect_previous.compute_txid(),
                vout: 0,
            };
            assert!(
                spend_psbt(
                    &case,
                    &enrolled,
                    &incorrect_previous,
                    incorrect_outpoint,
                    &destination,
                )
                .is_err()
            );
        }
    }

    #[test]
    fn duplicate_physical_and_account_keys_are_not_independent() {
        let case = super::super::model::matrix()
            .into_iter()
            .find(|c| c.holders.len() == 2)
            .unwrap();
        let fixture = holders(&case);
        let mut enrolled = fixture.iter().map(|(_, e)| e.clone()).collect::<Vec<_>>();
        enrolled[1].identity = enrolled[0].identity.clone();
        assert!(descriptor(&case, &enrolled, 0).is_err());
        enrolled[1].identity = fixture[1].1.identity.clone();
        enrolled[1].identity.physical_id = enrolled[0].identity.physical_id.clone();
        assert!(descriptor(&case, &enrolled, 0).is_err());
    }
}
