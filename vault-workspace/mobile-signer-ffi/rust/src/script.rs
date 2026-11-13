//! A wallet the book names by its descriptor text and whose keys are not on this phone: one hardware key
//! (`pkh`, `sh(wpkh)`, `wpkh` or `tr` over one account key, account 0) or several keys (`wsh(sortedmulti)`).
//! Read once at the FFI edge, testnet4 only. Receive is `0/i` and change `1/i` by rule, so the text carries
//! no derivation suffix. Public data only: no seed enters here.

use std::collections::BTreeMap;
use std::str::FromStr;
use std::time::SystemTime;

use bitcoin::bip32::{ChildNumber, DerivationPath, Fingerprint, KeySource, Xpub};
use bitcoin::hashes::{Hash, sha256};
use bitcoin::psbt;
use bitcoin::secp256k1::Secp256k1;
use bitcoin::sighash::{EcdsaSighashType, TapSighashType};
use bitcoin::transaction::InputWeightPrediction;
use bitcoin::{Address, NetworkKind, Transaction};
use miniscript::descriptor::{
    DefiniteDescriptorKey, DescriptorPublicKey, DescriptorXKey, ShInner, Wildcard, WshInner,
};
use miniscript::psbt::{PsbtInputExt, PsbtOutputExt};
use miniscript::{Descriptor, Threshold};

use crate::address_type::AddressType;
use crate::chain::{ChainSource, check_network};
use crate::hot_wallet::{AddressSource, Chain, Owner, Wallet, synced_by, vsize};
use crate::scan_store::{ScanStore, WalletId};
use crate::{KeyNetwork, MobileSignerError};

/// At most this many keys, so the script names its key count in one opcode.
const MAX_KEYS: usize = 15;
/// Far above any wallet of these shapes. Base58 decoding is quadratic (lib.rs `MAX_XPUB_CHARS`).
const MAX_DESCRIPTOR_BYTES: usize = 4_096;

/// How a described wallet locks its coins.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Shape {
    /// One key, locked as the phone wallet of that address type locks its coins.
    One(AddressType),
    /// `m` of the keys, sorted, in P2WSH.
    Multi { m: usize },
}

/// One account key of a described wallet, with the origin a device knows it by.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct AccountKey {
    pub(crate) fingerprint: Fingerprint,
    /// `84'/1'/0'` for one Native SegWit key, `48'/1'/a'/2'` for a key of several.
    pub(crate) path: DerivationPath,
    pub(crate) xpub: Xpub,
}

/// A wallet the book describes, read from its descriptor text.
pub(crate) struct Described {
    shape: Shape,
    /// In the descriptor's order.
    keys: Vec<AccountKey>,
    /// The receive and the change descriptor, every key with its origin.
    chains: [Descriptor<DescriptorPublicKey>; 2],
    id: WalletId,
}

/// One address of a described wallet, and where it sits.
pub(crate) struct Derived {
    pub(crate) chain: Chain,
    pub(crate) index: u32,
    pub(crate) address: Address,
}

impl Owner for Derived {
    fn address(&self) -> &Address {
        &self.address
    }
}

fn refuse(why: impl std::fmt::Display) -> MobileSignerError {
    MobileSignerError::InvalidPsbtJson(format!("descriptor: {why}"))
}

impl Described {
    /// Reads exactly `pkh([fp/44'/1'/0']tpub)`, `sh(wpkh([fp/49'/1'/0']tpub))`, `wpkh([fp/84'/1'/0']tpub)`,
    /// `tr([fp/86'/1'/0']tpub)` and `wsh(sortedmulti(m,[fp/48'/1'/a'/2']tpub,...))` with at most 15 keys, a
    /// checksum allowed. Every key a tpub with its origin, its depth and child number those of the origin's
    /// path, no derivation suffix, no public key twice and no master fingerprint twice, since whoever holds a
    /// phrase signs for every key it derives. Anything else is refused with a line that says why.
    pub(crate) fn parse(text: &str) -> Result<Self, MobileSignerError> {
        if text.len() > MAX_DESCRIPTOR_BYTES {
            return Err(refuse(format!(
                "{} bytes, longer than any wallet this app reads",
                text.len()
            )));
        }
        let parsed = Descriptor::<DescriptorPublicKey>::from_str(text)
            .map_err(|e| refuse(format!("not a descriptor: {e}")))?;
        let (shape, given): (Shape, Vec<&DescriptorPublicKey>) = match &parsed {
            Descriptor::Pkh(pkh) => (Shape::One(AddressType::Legacy), vec![pkh.as_inner()]),
            Descriptor::Sh(sh) => match sh.as_inner() {
                ShInner::Wpkh(wpkh) => {
                    (Shape::One(AddressType::NestedSegwit), vec![wpkh.as_inner()])
                }
                _ => return Err(refuse("sh() is read only around one wpkh() key")),
            },
            Descriptor::Wpkh(wpkh) => {
                (Shape::One(AddressType::NativeSegwit), vec![wpkh.as_inner()])
            }
            Descriptor::Tr(tr) if tr.tap_tree().is_none() => {
                (Shape::One(AddressType::Taproot), vec![tr.internal_key()])
            }
            Descriptor::Tr(_) => return Err(refuse("tr() is read only with no script tree")),
            Descriptor::Wsh(wsh) => match wsh.as_inner() {
                WshInner::SortedMulti(multi) => {
                    (Shape::Multi { m: multi.k() }, multi.pks().iter().collect())
                }
                WshInner::Ms(_) => return Err(refuse("wsh() is read only around sortedmulti()")),
            },
            Descriptor::Bare(_) => {
                return Err(refuse(
                    "only pkh, sh(wpkh), wpkh and tr over one key, or wsh(sortedmulti) over several, are read",
                ));
            }
        };
        if given.len() > MAX_KEYS {
            return Err(refuse(format!(
                "{} keys, and a wallet takes at most {MAX_KEYS}",
                given.len()
            )));
        }
        let mut keys: Vec<AccountKey> = Vec::with_capacity(given.len());
        for (i, key) in given.into_iter().enumerate() {
            let key = account_key(i, key, shape)?;
            // As `parse_and_check_xpubs`: whoever holds one private key signs for every xpub that carries its public key.
            if let Some(first) = keys
                .iter()
                .position(|k| k.xpub.public_key == key.xpub.public_key)
            {
                return Err(refuse(format!("key {i} is the same key as key {first}")));
            }
            // The user's rule of 12 Nov 2026. It also leaves each fingerprint naming one key, which device signing finds its
            // key by, and n >= m fingerprints, which `finished` counts.
            if let Some(first) = keys.iter().position(|k| k.fingerprint == key.fingerprint) {
                return Err(refuse(format!(
                    "key {i} comes from the phrase of key {first} (master fingerprint {}), and two keys from one phrase are one key",
                    key.fingerprint
                )));
            }
            keys.push(key);
        }
        let chains = [
            chain_descriptor(shape, &keys, 0)?,
            chain_descriptor(shape, &keys, 1)?,
        ];
        Ok(Self {
            id: id_of(shape, &keys)?,
            shape,
            keys,
            chains,
        })
    }

    /// The wallet at `chain`/`index`, every key derived there and named by its full origin.
    pub(crate) fn at(
        &self,
        chain: Chain,
        index: u32,
    ) -> Result<Descriptor<DefiniteDescriptorKey>, MobileSignerError> {
        self.chains[chain as usize]
            .at_derivation_index(index)
            .map_err(|e| refuse(format!("{} index {index}: {e}", chain.name())))
    }

    /// How many keys must sign: 1 for one key.
    pub(crate) fn needed(&self) -> usize {
        match self.shape {
            Shape::One(_) => 1,
            Shape::Multi { m } => m,
        }
    }

    /// In the descriptor's order.
    pub(crate) fn keys(&self) -> &[AccountKey] {
        &self.keys
    }

    /// The input that spends output `vout` of `parent`, the wallet's coin at `chain`/`index`, as a draft writes it
    /// before any signature, in the shape the harness proved on both devices: the parent on every input, its
    /// output as the witness UTXO on all but Legacy, SIGHASH_ALL (Taproot: the default sighash), and what the
    /// descriptor fills in, which is every key's origin, a Nested SegWit redeem script, the witness script of
    /// several keys and a Taproot internal key.
    pub(crate) fn input(
        &self,
        parent: &Transaction,
        vout: u32,
        chain: Chain,
        index: u32,
    ) -> Result<psbt::Input, MobileSignerError> {
        let spent = parent
            .output
            .get(vout as usize)
            .ok_or_else(|| refuse(format!("{} has no output {vout}", parent.compute_txid())))?;
        let mut input = psbt::Input {
            non_witness_utxo: Some(parent.clone()),
            witness_utxo: (self.shape != Shape::One(AddressType::Legacy)).then(|| spent.clone()),
            sighash_type: Some(match self.shape {
                Shape::One(AddressType::Taproot) => TapSighashType::Default.into(),
                _ => EcdsaSighashType::All.into(),
            }),
            ..psbt::Input::default()
        };
        input
            .update_with_descriptor_unchecked(&self.at(chain, index)?)
            .map_err(refuse)?;
        Ok(input)
    }

    /// The change output at change index `index`, with what a device needs to know it as this wallet's.
    pub(crate) fn change(&self, index: u32) -> Result<psbt::Output, MobileSignerError> {
        let mut output = psbt::Output::default();
        output
            .update_with_descriptor_unchecked(&self.at(Chain::Internal, index)?)
            .map_err(refuse)?;
        Ok(output)
    }

    /// Every account key with its origin, for PSBT_GLOBAL_XPUB, without which the Safe 7 signs nothing.
    pub(crate) fn global_xpubs(&self) -> BTreeMap<Xpub, KeySource> {
        self.keys
            .iter()
            .map(|key| (key.xpub, (key.fingerprint, key.path.clone())))
            .collect()
    }

    /// The chain and index named by the first of `origins` that is one of this wallet's keys at `0/i` or `1/i`.
    pub(crate) fn claim<'a>(
        &self,
        origins: impl IntoIterator<Item = &'a KeySource>,
    ) -> Option<(Chain, u32)> {
        origins.into_iter().find_map(|(fingerprint, path)| {
            self.keys.iter().find_map(|key| {
                let below = path.as_ref().strip_prefix(key.path.as_ref())?;
                match below {
                    [
                        ChildNumber::Normal { index: chain },
                        ChildNumber::Normal { index },
                    ] if *fingerprint == key.fingerprint => match chain {
                        0 => Some((Chain::External, *index)),
                        1 => Some((Chain::Internal, *index)),
                        _ => None,
                    },
                    _ => None,
                }
            })
        })
    }

    /// What one of its coins adds to a transaction once spent, at the most a device's signatures take: ECDSA
    /// 72 bytes with the sighash byte, Schnorr 64. A device's ECDSA signature may be a byte shorter, so a fee
    /// sized on this never pays under the rate asked. Several keys: the empty dummy, m signatures and the
    /// `3 + 34n` byte witness script.
    pub(crate) fn spend_weight(&self) -> InputWeightPrediction {
        match self.shape {
            Shape::One(address_type) => address_type.input_weight(),
            Shape::Multi { m } => InputWeightPrediction::new(
                0,
                std::iter::once(0)
                    .chain(std::iter::repeat_n(72, m))
                    .chain(std::iter::once(3 + 34 * self.keys.len())),
            ),
        }
    }

    /// The length of the output script that pays one of its addresses.
    pub(crate) fn script_len(&self) -> usize {
        match self.shape {
            Shape::One(address_type) => address_type.script_len(),
            Shape::Multi { .. } => 34,
        }
    }

    /// The wallet as a sync of `source` leaves it, by the phone wallet's own rules (`synced_by`).
    pub(crate) fn synced(
        &self,
        source: &dyn ChainSource,
        network: KeyNetwork,
        clock: &dyn Fn() -> SystemTime,
        store: &ScanStore,
    ) -> Result<Wallet<Derived>, MobileSignerError> {
        synced_by(source, self, network, clock, store, |chain, index| {
            Ok(Derived {
                chain,
                index,
                address: self.address(chain, index)?,
            })
        })
    }
}

impl AddressSource for Described {
    fn address(&self, chain: Chain, index: u32) -> Result<Address, MobileSignerError> {
        self.at(chain, index)?
            .address(bitcoin::Network::Testnet4)
            .map_err(|e| refuse(format!("{} index {index}: {e}", chain.name())))
    }

    fn id(&self) -> WalletId {
        self.id
    }
}

/// Key `i` of a `shape` wallet once it is an account key there, else why not.
fn account_key(
    i: usize,
    key: &DescriptorPublicKey,
    shape: Shape,
) -> Result<AccountKey, MobileSignerError> {
    let refuse = |why: String| refuse(format!("key {i} {why}"));
    let suffix = || {
        refuse("has a derivation suffix, and receive 0/i and change 1/i are added by rule".into())
    };
    let x = match key {
        DescriptorPublicKey::XPub(x) => x,
        DescriptorPublicKey::MultiXPub(_) => return Err(suffix()),
        DescriptorPublicKey::Single(_) => {
            return Err(refuse("is a single public key, not an account xpub".into()));
        }
    };
    let Some((fingerprint, path)) = &x.origin else {
        return Err(refuse("has no origin [fingerprint/path]".into()));
    };
    if !x.derivation_path.is_empty() || x.wildcard != Wildcard::None {
        return Err(suffix());
    }
    if x.xkey.network != NetworkKind::Test {
        return Err(refuse(
            "is a mainnet key, and this build reads testnet4 wallets only".into(),
        ));
    }
    if usize::from(x.xkey.depth) != path.len() || path.as_ref().last() != Some(&x.xkey.child_number)
    {
        return Err(refuse(format!(
            "does not sit at its origin m/{path}: the xpub's depth or child number is another"
        )));
    }
    let (fits, wanted) = match shape {
        Shape::One(address_type) => (
            *path == address_type.account_path(),
            format!("m/{}", address_type.account_path()),
        ),
        Shape::Multi { .. } => (
            matches!(
                path.as_ref(),
                [
                    ChildNumber::Hardened { index: 48 },
                    ChildNumber::Hardened { index: 1 },
                    ChildNumber::Hardened { .. },
                    ChildNumber::Hardened { index: 2 },
                ]
            ),
            "m/48'/1'/<account>'/2'".into(),
        ),
    };
    if !fits {
        return Err(refuse(format!(
            "is at m/{path}, and this wallet's keys are at {wanted}"
        )));
    }
    Ok(AccountKey {
        fingerprint: *fingerprint,
        path: path.clone(),
        xpub: x.xkey,
    })
}

/// The `shape` wallet over `keys` on chain `chain`, as wallet-e2e/wallet.rs `descriptor` builds it (proven on
/// both devices).
fn chain_descriptor(
    shape: Shape,
    keys: &[AccountKey],
    chain: u32,
) -> Result<Descriptor<DescriptorPublicKey>, MobileSignerError> {
    let key = |key: &AccountKey| {
        DescriptorPublicKey::XPub(DescriptorXKey {
            origin: Some((key.fingerprint, key.path.clone())),
            xkey: key.xpub,
            derivation_path: DerivationPath::from(vec![ChildNumber::Normal { index: chain }]),
            wildcard: Wildcard::Unhardened,
        })
    };
    let one = || key(&keys[0]);
    match shape {
        Shape::One(AddressType::Legacy) => Descriptor::new_pkh(one()),
        Shape::One(AddressType::NestedSegwit) => Descriptor::new_sh_wpkh(one()),
        Shape::One(AddressType::NativeSegwit) => Descriptor::new_wpkh(one()),
        Shape::One(AddressType::Taproot) => Descriptor::new_tr(one(), None),
        Shape::Multi { m } => Descriptor::new_wsh_sortedmulti(
            Threshold::new(m, keys.iter().map(key).collect()).map_err(refuse)?,
        ),
    }
    .map_err(refuse)
}

/// The name of the wallet's scan file: SHA-256 of a tag, the shape and the keys' chain xpubs. One key: the tag, the
/// address type's purpose and its two chain xpubs, since an xpub does not say which path it came from and one listed
/// under two types is two wallets. No phone wallet's `Account::id`, which hashes the xpubs alone, is ever one of
/// these. Several keys: the tag, m and each key's two chain xpubs in sorted order, so the order the keys are listed in
/// never changes it.
fn id_of(shape: Shape, keys: &[AccountKey]) -> Result<WalletId, MobileSignerError> {
    let secp = Secp256k1::verification_only();
    let chains = |key: &AccountKey| -> Result<Vec<u8>, MobileSignerError> {
        let mut bytes = Vec::with_capacity(2 * 78);
        for index in [0, 1] {
            let chain = key
                .xpub
                .ckd_pub(&secp, ChildNumber::Normal { index })
                .map_err(refuse)?;
            bytes.extend_from_slice(&chain.encode());
        }
        Ok(bytes)
    };
    let bytes = match shape {
        Shape::One(address_type) => {
            let mut bytes = b"mebit one key".to_vec();
            bytes.push(address_type.purpose() as u8);
            bytes.extend(chains(&keys[0])?);
            bytes
        }
        Shape::Multi { m } => {
            let mut each = keys.iter().map(chains).collect::<Result<Vec<_>, _>>()?;
            each.sort();
            let mut bytes = b"mebit sortedmulti".to_vec();
            bytes.push(m as u8);
            bytes.extend(each.concat());
            bytes
        }
    };
    Ok(sha256::Hash::hash(&bytes).to_byte_array())
}

/// What `wallet_describe` tells of a wallet.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct WalletShape {
    /// How many keys must sign a payment: 1 for one key.
    pub needed: u8,
    /// In the descriptor's order.
    pub keys: Vec<WalletKey>,
    /// Receive address 0.
    pub first_address: String,
    /// The virtual size of one of its coins paying an address of its own type, with change: what the Send
    /// screen prices a payment at before Review reads the real one.
    pub typical_vsize: u64,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct WalletKey {
    /// The master key's fingerprint, 8 lowercase hex characters.
    pub fingerprint: String,
    /// The account's path, as `m/84'/1'/0'`.
    pub path: String,
    pub xpub: String,
}

/// The keys, how many must sign, receive address 0 and the typical payment size of the wallet `descriptor`
/// describes. Asks nothing of the network.
#[uniffi::export]
pub fn wallet_describe(
    descriptor: String,
    network: KeyNetwork,
) -> Result<WalletShape, MobileSignerError> {
    check_network(network)?;
    let wallet = Described::parse(&descriptor)?;
    let own = wallet.script_len();
    Ok(WalletShape {
        needed: wallet.needed() as u8,
        keys: wallet
            .keys
            .iter()
            .map(|key| WalletKey {
                fingerprint: key.fingerprint.to_string(),
                path: format!("m/{}", key.path),
                xpub: key.xpub.to_string(),
            })
            .collect(),
        first_address: wallet.address(Chain::External, 0)?.to_string(),
        typical_vsize: vsize(&[wallet.spend_weight()], &[own, own]),
    })
}

#[cfg(test)]
pub(crate) mod tests {
    use std::collections::{HashMap, HashSet};
    use std::sync::LazyLock;

    use bitcoin::bip32::{ChainCode, Xpriv};
    use bitcoin::consensus::encode::serialize_hex;
    use bitcoin::{Amount, Network, OutPoint, Transaction, TxIn, TxOut, Txid};

    use super::*;
    use crate::chain::{ESPLORA_TESTNET4, EsploraSource, Transport};
    use crate::hot_wallet::tests::{
        Fake, OTHER_TYPES_PAID, PAID_SATS, SOMEONE, abandon_about, in_proof_block, merkle,
        proof_header, spent, tx_of, txo,
    };
    use crate::hot_wallet::{
        Account, HotScan, TxoOwner, described_detail, described_history, described_snapshot,
        hot_receive_address, hot_typical_vsize,
    };

    /// Receive address 0 of `lib.rs`'s 2-of-3 fixture, which `scripts/address_oracle.py` derives on its own.
    const TESTNET_2_OF_3: &str = "tb1q693w8s33wzc0pmrpu9yda3ngm2eu9hkzvkwagw4q69643my3xt0q9lcl0f";

    /// A master key for the test wallets of several keys, from the seed `[i; 32]`.
    pub(crate) fn soft_master(i: u8) -> Xpriv {
        Xpriv::new_master(Network::Testnet4, &[i; 32]).unwrap()
    }

    /// `master`'s account at `path` as a descriptor key, `[fingerprint/path]xpub`, and the xpub alone.
    fn account(master: &Xpriv, path: &str) -> (String, Xpub) {
        let secp = Secp256k1::new();
        let path = DerivationPath::from_str(path).unwrap();
        let xpub = Xpub::from_priv(&secp, &master.derive_priv(&secp, &path).unwrap());
        (
            format!("[{}/{path}]{xpub}", master.fingerprint(&secp)),
            xpub,
        )
    }

    /// `wsh(sortedmulti(m, ...))` over the soft keys `among`, each at `m/48'/1'/0'/2'`.
    pub(crate) fn multisig(m: usize, among: &[u8]) -> String {
        let keys: Vec<String> = among
            .iter()
            .map(|&i| account(&soft_master(i), "m/48'/1'/0'/2'").0)
            .collect();
        format!("wsh(sortedmulti({m},{}))", keys.join(","))
    }

    pub(crate) fn abandon_master(network: Network) -> Xpriv {
        let words = abandon_about().join(" ");
        let seed = bip39::Mnemonic::parse_normalized(&words)
            .unwrap()
            .to_seed("");
        Xpriv::new_master(network, &seed).unwrap()
    }

    /// The abandon-about account of `address_type`, as a hardware wallet of those words exports it.
    pub(crate) fn one_key(address_type: AddressType) -> String {
        let path = format!("m/{}", address_type.account_path());
        let key = account(&abandon_master(Network::Testnet4), &path).0;
        match address_type {
            AddressType::Legacy => format!("pkh({key})"),
            AddressType::NestedSegwit => format!("sh(wpkh({key}))"),
            AddressType::NativeSegwit => format!("wpkh({key})"),
            AddressType::Taproot => format!("tr({key})"),
        }
    }

    pub(crate) fn one_of_two() -> String {
        multisig(1, &[1, 2])
    }

    pub(crate) fn two_of_three() -> String {
        multisig(2, &[1, 2, 3])
    }

    pub(crate) fn three_of_five() -> String {
        multisig(3, &[1, 2, 3, 4, 5])
    }

    pub(crate) fn at(descriptor: &str, chain: Chain, index: u32) -> Address {
        Described::parse(descriptor)
            .unwrap()
            .address(chain, index)
            .unwrap()
    }

    /// Where `MULTI` and `mine_the_multisig_block` start, and what that test found.
    const MULTI_TIME: u32 = 1_762_900_013;
    const MULTI_NONCE: u32 = 0x10cf_02c6;
    const MULTI_HEIGHT: u64 = 101;

    pub(crate) struct Multi {
        txs: Vec<Transaction>,
        txids: Vec<Txid>,
        header: bitcoin::block::Header,
        pages: HashMap<String, String>,
    }

    /// A block that pays `PAID_SATS` to the 2-of-3's receive 0 and 1 and change 0, and to receive 0 of the 1-of-2
    /// and of the 3-of-5, with a header mined at testnet4's minimum difficulty. Through it the Fake proves those
    /// addresses used the way a real source would, as `hot_wallet`'s `PROOF` block does for one-key wallets.
    pub(crate) static MULTI: LazyLock<Multi> = LazyLock::new(|| {
        let paid = [
            at(&two_of_three(), Chain::External, 0),
            at(&two_of_three(), Chain::External, 1),
            at(&two_of_three(), Chain::Internal, 0),
            at(&one_of_two(), Chain::External, 0),
            at(&three_of_five(), Chain::External, 0),
        ];
        let txs: Vec<Transaction> = paid
            .iter()
            .enumerate()
            .map(|(i, to)| Transaction {
                version: bitcoin::transaction::Version::TWO,
                lock_time: bitcoin::absolute::LockTime::ZERO,
                input: vec![TxIn {
                    previous_output: OutPoint {
                        txid: Txid::from_byte_array([0x44; 32]),
                        vout: i as u32,
                    },
                    ..TxIn::default()
                }],
                output: vec![TxOut {
                    value: Amount::from_sat(PAID_SATS),
                    script_pubkey: to.script_pubkey(),
                }],
            })
            .collect();
        let txids: Vec<Txid> = txs.iter().map(Transaction::compute_txid).collect();
        let header = proof_header(merkle(&txids, 0).0, MULTI_TIME, MULTI_NONCE);
        let status = in_proof_block(&header, MULTI_HEIGHT);
        let pages = paid
            .iter()
            .zip(&txids)
            .map(|(to, txid)| {
                let payment = tx_of(
                    &txid.to_string(),
                    &[spent(SOMEONE, PAID_SATS + 1_000)],
                    &[txo(&to.to_string(), PAID_SATS)],
                    &status,
                );
                (to.to_string(), format!("[{payment}]"))
            })
            .collect();
        Multi {
            txs,
            txids,
            header,
            pages,
        }
    });

    impl Multi {
        /// What a source answers about this block, or None for what it does not hold.
        fn serve(&self, path: &str) -> Option<String> {
            let at = |txid: &str| self.txids.iter().position(|t| t.to_string() == txid);
            if let Some(rest) = path.strip_prefix("/tx/") {
                if let Some(pos) = rest.strip_suffix("/hex").and_then(at) {
                    return Some(serialize_hex(&self.txs[pos]));
                }
                let pos = rest.strip_suffix("/merkle-proof").and_then(at)?;
                let hashes: Vec<String> = merkle(&self.txids, pos)
                    .1
                    .iter()
                    .map(|hash| format!("\"{hash}\""))
                    .collect();
                return Some(format!(
                    r#"{{"block_height":{MULTI_HEIGHT},"merkle":[{}],"pos":{pos}}}"#,
                    hashes.join(",")
                ));
            }
            let hash = path.strip_prefix("/block/")?.strip_suffix("/header")?;
            (hash == self.header.block_hash().to_string()).then(|| serialize_hex(&self.header))
        }
    }

    /// The Fake's answers, and `MULTI`'s.
    pub(crate) struct WithMulti<'a>(pub(crate) &'a Fake);

    impl Transport for WithMulti<'_> {
        fn get(&self, url: &str) -> Result<String, MobileSignerError> {
            let path = url.strip_prefix(ESPLORA_TESTNET4).expect("a testnet4 URL");
            MULTI.serve(path).map_or_else(|| self.0.get(url), Ok)
        }

        fn broadcast(&self, _: &str, _: &str) -> Result<String, MobileSignerError> {
            unreachable!("a test that reads posts nothing")
        }
    }

    /// `address`, which `MULTI` pays, holds one confirmed coin of `value` sats whose parent the Fake serves raw.
    pub(crate) fn fund_multi(fake: &mut Fake, address: &Address, value: u64, digit: char) -> Txid {
        let address = address.to_string();
        let page = MULTI.pages.get(&address).expect("an address MULTI pays");
        fake.txs.insert(address.clone(), page.clone());
        fake.fund_raw(&address, value, value, digit)
    }

    #[test]
    #[ignore = "mines MULTI_NONCE, minutes of CPU: cargo test --release -p mobile-signer-ffi mine_the_multisig_block -- --ignored --nocapture"]
    fn mine_the_multisig_block() {
        use std::sync::atomic::{AtomicBool, Ordering};
        let found = AtomicBool::new(false);
        let start = proof_header(MULTI.header.merkle_root, MULTI_TIME, 0);
        let target = start.target();
        std::thread::scope(|scope| {
            for thread in 0..16 {
                let found = &found;
                scope.spawn(move || {
                    let mut header = start;
                    header.time += thread;
                    for nonce in 0..=u32::MAX {
                        if nonce % (1 << 20) == 0 && found.load(Ordering::Relaxed) {
                            return;
                        }
                        header.nonce = nonce;
                        if target.is_met_by(header.block_hash()) {
                            found.store(true, Ordering::Relaxed);
                            println!("MULTI_TIME = {}, MULTI_NONCE = {nonce:#010x}", header.time);
                            return;
                        }
                    }
                });
            }
        });
        assert!(found.load(Ordering::Relaxed), "no nonce at these 16 times");
    }

    // The sameness the one-key hardware wallet rests on: the device's account xpub at the phone wallet's path
    // gives the phone wallet's own addresses on both chains.
    #[test]
    fn one_key_descriptors_give_the_phone_wallets_addresses() {
        for address_type in AddressType::ALL {
            let wallet = Described::parse(&one_key(address_type)).unwrap();
            let phone =
                Account::new(&abandon_about(), "", Network::Testnet4, address_type).unwrap();
            for chain in [Chain::External, Chain::Internal] {
                for index in 0..=40 {
                    assert_eq!(
                        wallet.address(chain, index).unwrap(),
                        phone.key(chain, index).unwrap().address,
                        "{address_type:?} {chain:?} {index}"
                    );
                }
            }
        }
    }

    // `lib.rs`'s fixtures: the BIP-39 entropies 32 x 0x00, 0x7f and 0x80 at m/48'/1'/0'/2'.
    fn fixture_keys() -> Vec<(String, Xpub)> {
        [0x00u8, 0x7f, 0x80]
            .iter()
            .map(|&byte| {
                let mnemonic = bip39::Mnemonic::from_entropy(&[byte; 32]).unwrap();
                let master = Xpriv::new_master(Network::Testnet4, &mnemonic.to_seed("")).unwrap();
                account(&master, "m/48'/1'/0'/2'")
            })
            .collect()
    }

    #[test]
    fn sortedmulti_receive_zero_is_the_vault_address_in_any_key_order() {
        let keys = fixture_keys();
        let xpubs: Vec<String> = keys.iter().map(|(_, xpub)| xpub.to_string()).collect();
        assert_eq!(
            crate::compute_vault_address_multisig(xpubs, 2).unwrap(),
            TESTNET_2_OF_3
        );
        let mut ids = Vec::new();
        for order in [
            [0, 1, 2],
            [0, 2, 1],
            [1, 0, 2],
            [1, 2, 0],
            [2, 0, 1],
            [2, 1, 0],
        ] {
            let listed: Vec<&str> = order.iter().map(|&i| keys[i].0.as_str()).collect();
            let wallet =
                Described::parse(&format!("wsh(sortedmulti(2,{}))", listed.join(","))).unwrap();
            assert_eq!(
                wallet.address(Chain::External, 0).unwrap().to_string(),
                TESTNET_2_OF_3,
                "{order:?}"
            );
            ids.push(wallet.id());
        }
        assert!(
            ids.iter().all(|id| *id == ids[0]),
            "key order never moves the id"
        );
        let listed: Vec<&str> = keys.iter().map(|(key, _)| key.as_str()).collect();
        let one_of_three =
            Described::parse(&format!("wsh(sortedmulti(1,{}))", listed.join(","))).unwrap();
        assert_ne!(one_of_three.id(), ids[0], "m is part of the id");
        assert_ne!(
            one_of_three.address(Chain::Internal, 0).unwrap(),
            one_of_three.address(Chain::External, 0).unwrap()
        );
    }

    // Every several-key wallet the app holds was made, paid and scanned at the addresses vault-core derives from its bare
    // xpubs. A described wallet of the same keys pays exactly those, receive and change, in any key order.
    #[test]
    fn a_several_key_wallet_pays_the_addresses_vault_core_derives_on_both_chains() {
        use vault_core::descriptor::{Branch, build_multisig_descriptor_on, derive_address_at};
        let wallets: [(usize, &[u8]); 7] = [
            (2, &[1, 2, 3]),
            (2, &[1, 3, 2]),
            (2, &[2, 1, 3]),
            (2, &[2, 3, 1]),
            (2, &[3, 1, 2]),
            (2, &[3, 2, 1]),
            (3, &[1, 2, 3, 4, 5]),
        ];
        for (m, among) in wallets {
            let wallet = Described::parse(&multisig(m, among)).unwrap();
            let xpubs: Vec<Xpub> = wallet.keys().iter().map(|key| key.xpub).collect();
            for (chain, branch) in [
                (Chain::External, Branch::Receive),
                (Chain::Internal, Branch::Change),
            ] {
                let vault = build_multisig_descriptor_on(&xpubs, m, branch).unwrap();
                for index in 0..=40 {
                    assert_eq!(
                        wallet.address(chain, index).unwrap(),
                        derive_address_at(&vault, index, Network::Testnet4).unwrap(),
                        "{m} of {among:?}, {chain:?} {index}"
                    );
                }
            }
        }
    }

    #[test]
    fn a_descriptor_of_another_shape_or_with_a_bad_key_is_refused_with_why() {
        let tpub = |path: &str| account(&abandon_master(Network::Testnet4), path);
        let (bip84, bip84_xpub) = tpub("m/84'/1'/0'");
        let fp = abandon_master(Network::Testnet4).fingerprint(&Secp256k1::new());
        let k = |i: u8| account(&soft_master(i), "m/48'/1'/0'/2'").0;
        let mainnet = account(&abandon_master(Network::Bitcoin), "m/84'/1'/0'").1;
        let same_key_new_chain_code = Xpub {
            chain_code: ChainCode::from([7u8; 32]),
            ..account(&soft_master(1), "m/48'/1'/0'/2'").1
        };
        let sixteen: Vec<String> = (1..=16).map(k).collect();
        let checked = Descriptor::<DescriptorPublicKey>::from_str(&format!("wpkh({bip84})"))
            .unwrap()
            .to_string();
        let cases: Vec<(String, &str)> = vec![
            (
                format!("wpkh([{fp}/84'/1'/0']{mainnet})"),
                "key 0 is a mainnet key",
            ),
            (format!("wpkh({bip84_xpub})"), "key 0 has no origin"),
            (
                format!("wpkh([{fp}/44'/1'/0']{bip84_xpub})"),
                "key 0 is at m/44'/1'/0', and this wallet's keys are at m/84'/1'/0'",
            ),
            (
                format!("wpkh({})", tpub("m/84'/1'/1'").0),
                "key 0 is at m/84'/1'/1'",
            ),
            (
                format!("pkh({bip84})"),
                "key 0 is at m/84'/1'/0', and this wallet's keys are at m/44'/1'/0'",
            ),
            (
                format!("wpkh([{fp}/84'/1']{bip84_xpub})"),
                "key 0 does not sit at its origin m/84'/1'",
            ),
            (
                format!("wsh(sortedmulti(2,{},{}))", tpub("m/48'/1'/0'/1'").0, k(2)),
                "key 0 is at m/48'/1'/0'/1', and this wallet's keys are at m/48'/1'/<account>'/2'",
            ),
            (
                format!("wsh(sortedmulti(3,{},{}))", k(1), k(2)),
                "not a descriptor",
            ),
            (
                format!("wsh(sortedmulti(0,{},{}))", k(1), k(2)),
                "not a descriptor",
            ),
            (
                format!("wsh(sortedmulti(2,{}))", sixteen.join(",")),
                "16 keys, and a wallet takes at most 15",
            ),
            (
                format!("wsh(sortedmulti(2,{},{},{}))", k(1), k(2), k(1)),
                "key 2 is the same key as key 0",
            ),
            (
                format!(
                    "wsh(sortedmulti(1,{},[{}/48'/1'/0'/2']{same_key_new_chain_code}))",
                    k(1),
                    soft_master(9).fingerprint(&Secp256k1::new())
                ),
                "key 1 is the same key as key 0",
            ),
            (
                format!("wpkh({bip84}/0/*)"),
                "key 0 has a derivation suffix",
            ),
            (
                format!("wpkh({bip84}/<0;1>/*)"),
                "key 0 has a derivation suffix",
            ),
            (
                format!("wpkh([{fp}/84'/1'/0']{})", bip84_xpub.public_key),
                "key 0 is a single public key",
            ),
            (
                format!("sh(wsh(sortedmulti(2,{},{})))", k(1), k(2)),
                "sh() is read only around one wpkh() key",
            ),
            (
                format!("sh(sortedmulti(2,{},{}))", k(1), k(2)),
                "sh() is read only around one wpkh() key",
            ),
            (
                format!("wsh(multi(2,{},{}))", k(1), k(2)),
                "wsh() is read only around sortedmulti()",
            ),
            (
                format!("tr({},pk({}))", tpub("m/86'/1'/0'").0, k(2)),
                "tr() is read only with no script tree",
            ),
            (
                format!("pk({bip84})"),
                "only pkh, sh(wpkh), wpkh and tr over one key",
            ),
            (format!("{checked}xyz"), "not a descriptor"),
            (
                format!("{}aaaaaaaa", &checked[..checked.len() - 8]),
                "not a descriptor",
            ),
            (
                "x".repeat(5_000),
                "5000 bytes, longer than any wallet this app reads",
            ),
        ];
        for (text, why) in cases {
            match Described::parse(&text) {
                Err(MobileSignerError::InvalidPsbtJson(said)) => assert!(
                    said.starts_with("descriptor: ") && said.contains(why),
                    "{text}\n refused with {said:?}, not {why:?}"
                ),
                Err(other) => panic!("{text}: {other:?}"),
                Ok(_) => panic!("{text} was read, not refused with {why:?}"),
            }
        }
        assert_eq!(
            Described::parse(&checked).unwrap().id(),
            Described::parse(&format!("wpkh({bip84})")).unwrap().id(),
            "a checksum that holds is read"
        );
        assert!(
            Described::parse(&format!("wsh(sortedmulti(2,{}))", sixteen[..15].join(","))).is_ok(),
            "15 keys is the most"
        );
    }

    #[test]
    fn describe_names_the_keys_the_first_address_and_the_typical_size() {
        let keys = fixture_keys();
        let listed: Vec<&str> = keys.iter().map(|(key, _)| key.as_str()).collect();
        let shape = wallet_describe(
            format!("wsh(sortedmulti(2,{}))", listed.join(",")),
            KeyNetwork::Test,
        )
        .unwrap();
        assert_eq!(shape.needed, 2);
        assert_eq!(shape.first_address, TESTNET_2_OF_3);
        assert_eq!(
            shape
                .keys
                .iter()
                .map(|k| k.xpub.clone())
                .collect::<Vec<_>>(),
            keys.iter().map(|(_, x)| x.to_string()).collect::<Vec<_>>()
        );
        assert!(shape.keys.iter().all(|k| {
            k.path == "m/48'/1'/0'/2'"
                && k.fingerprint.len() == 8
                && k.fingerprint
                    .chars()
                    .all(|c| c.is_ascii_hexdigit() && !c.is_ascii_uppercase())
        }));
        // One input of 41 bytes and a 4-item witness (count 1, dummy 1, two signatures 73 each, the 105-byte
        // script with its length 106), two 43-byte P2WSH outputs, 10 bytes of version, counts and lock time,
        // and the 2-byte segwit marker: 4 x (41 + 86 + 10) + 254 + 2 = 804 WU, 201 vB.
        assert_eq!(shape.typical_vsize, 201);
        // A 3-of-5's witness: count 1, dummy 1, three signatures 73 each, the 173-byte script with its length 174:
        // 4 x 137 + 395 + 2 = 945 WU, 237 vB.
        let five = wallet_describe(three_of_five(), KeyNetwork::Test).unwrap();
        assert_eq!(five.typical_vsize, 237);

        let one = wallet_describe(one_key(AddressType::NativeSegwit), KeyNetwork::Test).unwrap();
        assert_eq!(
            (
                one.needed,
                one.keys[0].fingerprint.as_str(),
                one.keys[0].path.as_str()
            ),
            (1, "73c5da0a", "m/84'/1'/0'")
        );
        assert_eq!(
            one.first_address,
            hot_receive_address(
                abandon_about(),
                String::new(),
                KeyNetwork::Test,
                AddressType::NativeSegwit,
                0
            )
            .unwrap()
        );
        assert_eq!(
            one.typical_vsize,
            hot_typical_vsize(AddressType::NativeSegwit)
        );
        let main = wallet_describe(one_key(AddressType::NativeSegwit), KeyNetwork::Main);
        assert!(main.unwrap_err().to_string().contains("mainnet"));
    }

    // The scan is script-agnostic: a P2WSH and a P2TR wallet are proven used, block by block, through the real
    // `prove_use` and `scan::step`, as the phone wallet is.
    #[test]
    fn a_p2wsh_and_a_p2tr_wallet_scan_through_proven_use() {
        let wallet = two_of_three();
        let mut fake = Fake::new();
        fund_multi(&mut fake, &at(&wallet, Chain::External, 0), 40_000, '1');
        fund_multi(&mut fake, &at(&wallet, Chain::External, 1), 25_000, '2');
        fund_multi(&mut fake, &at(&wallet, Chain::Internal, 0), 9_000, '3');
        let source = EsploraSource(&WithMulti(&fake));
        let snapshot = described_snapshot(
            &source,
            &wallet,
            KeyNetwork::Test,
            &|| fake.now(),
            &fake.store,
        )
        .unwrap();
        assert_eq!(
            (
                snapshot.scan,
                snapshot.balance_sats,
                snapshot.spendable_sats
            ),
            (HotScan::Complete, 74_000, 74_000)
        );
        assert_eq!(
            snapshot.receive_address,
            at(&wallet, Chain::External, 2).to_string()
        );
        let history = described_history(
            &source,
            &wallet,
            KeyNetwork::Test,
            &|| fake.now(),
            &fake.store,
        )
        .unwrap();
        let proven: Vec<String> = MULTI.txids[..3].iter().map(Txid::to_string).collect();
        let mut listed: Vec<String> = history.iter().map(|tx| tx.txid.clone()).collect();
        listed.sort();
        let mut expected = proven.clone();
        expected.sort();
        assert_eq!(listed, expected, "each proven payment once");
        assert!(history.iter().all(|tx| tx.net_sats == PAID_SATS as i64));

        let taproot = one_key(AddressType::Taproot);
        let paid = [
            at(&taproot, Chain::External, 0),
            at(&taproot, Chain::Internal, 1),
        ];
        assert!(
            paid.iter()
                .all(|a| OTHER_TYPES_PAID.contains(&a.to_string().as_str()))
        );
        let mut fake = Fake::new();
        fake.fund_raw(&paid[0].to_string(), 12_000, 12_000, '4');
        fake.fund_raw(&paid[1].to_string(), 3_000, 3_000, '5');
        let snapshot = described_snapshot(
            &EsploraSource(&WithMulti(&fake)),
            &taproot,
            KeyNetwork::Test,
            &|| fake.now(),
            &fake.store,
        )
        .unwrap();
        assert_eq!(
            (
                snapshot.scan,
                snapshot.balance_sats,
                snapshot.receive_address
            ),
            (
                HotScan::Complete,
                15_000,
                at(&taproot, Chain::External, 1).to_string()
            )
        );
    }

    #[test]
    fn a_detail_names_the_described_wallets_own_outputs() {
        let wallet = two_of_three();
        let mut fake = Fake::new();
        let (receive, change) = (
            at(&wallet, Chain::External, 0),
            at(&wallet, Chain::Internal, 1),
        );
        let txid = "c".repeat(64);
        fake.parents.insert(
            txid.clone(),
            format!(
                r#"{{"txid":"{txid}","vin":[{{"txid":"{}","vout":0,"is_coinbase":false,"sequence":4294967293,"prevout":{}}}],"vout":[{},{},{}],"size":300,"weight":800,"status":{{"confirmed":false}}}}"#,
                "d".repeat(64),
                txo(SOMEONE, 50_000),
                txo(&receive.to_string(), 20_000),
                txo(&change.to_string(), 9_000),
                txo(SOMEONE, 20_000),
            ),
        );
        let detail = described_detail(
            &EsploraSource(&WithMulti(&fake)),
            &wallet,
            KeyNetwork::Test,
            &txid,
            &fake.store,
        )
        .unwrap()
        .unwrap();
        let owners: Vec<TxoOwner> = detail.outputs.iter().map(|o| o.owner).collect();
        assert_eq!(
            owners,
            [TxoOwner::Receive, TxoOwner::Change, TxoOwner::Other]
        );
        assert_eq!((detail.tx.net_sats, detail.fee_sats), (29_000, 1_000));
    }

    // The scan id names a wallet's scan file and its cached balance. An account xpub does not say which path it came
    // from, so the 84' account listed under 44', 49' or 86' parses, and each listing is another wallet.
    #[test]
    fn one_xpub_listed_under_each_one_key_shape_is_its_own_wallet() {
        let master = abandon_master(Network::Testnet4);
        let fp = master.fingerprint(&Secp256k1::new());
        let xpub = account(&master, "m/84'/1'/0'").1;
        let listed = [
            format!("pkh([{fp}/44'/1'/0']{xpub})"),
            format!("sh(wpkh([{fp}/49'/1'/0']{xpub}))"),
            format!("wpkh([{fp}/84'/1'/0']{xpub})"),
            format!("tr([{fp}/86'/1'/0']{xpub})"),
        ];
        let (pkh, wpkh) = (&listed[0], &listed[2]);
        let mut fake = Fake::new();
        fake.fund_raw(
            &at(wpkh, Chain::External, 0).to_string(),
            50_000,
            50_000,
            '1',
        );
        fake.fund_raw(
            &at(wpkh, Chain::External, 1).to_string(),
            20_000,
            20_000,
            '2',
        );
        let source = EsploraSource(&WithMulti(&fake));
        let balance = |text: &str| {
            described_snapshot(&source, text, KeyNetwork::Test, &|| fake.now(), &fake.store)
                .unwrap()
                .balance_sats
        };
        assert_eq!(
            (balance(wpkh), balance(pkh)),
            (70_000, 0),
            "the pkh wallet shows its own coins, not the wpkh wallet's"
        );

        let ids: HashSet<WalletId> = listed
            .iter()
            .map(|text| Described::parse(text).unwrap().id())
            .collect();
        assert_eq!(ids.len(), listed.len(), "one id per shape");
        let phone: HashSet<WalletId> = AddressType::ALL
            .iter()
            .map(|&t| {
                Account::new(&abandon_about(), "", Network::Testnet4, t)
                    .unwrap()
                    .id()
            })
            .collect();
        assert!(
            ids.is_disjoint(&phone),
            "no described wallet reads a phone wallet's scan file"
        );
    }

    // Whoever holds a phrase signs for every key it derives, so a wallet listing two of them has one signer fewer
    // than it shows. The user's rule of 12 Nov 2026 refuses it whatever m is, also when m keys could still sign.
    #[test]
    fn a_several_key_wallet_with_two_keys_of_one_phrase_is_refused_with_why() {
        let key =
            |seed: u8, index: u32| account(&soft_master(seed), &format!("m/48'/1'/{index}'/2'"));
        let seven = soft_master(7).fingerprint(&Secp256k1::new());
        let relabelled = format!("[{seven}/48'/1'/0'/2']{}", key(8, 0).1);
        let why = |i: usize| {
            format!(
                "key {i} comes from the phrase of key 0 (master fingerprint {seven}), and two keys from one phrase are one key"
            )
        };
        for (text, why) in [
            (
                format!("wsh(sortedmulti(2,{},{}))", key(7, 0).0, key(7, 1).0),
                why(1),
            ),
            (
                format!(
                    "wsh(sortedmulti(2,{},{},{}))",
                    key(7, 0).0,
                    key(7, 1).0,
                    key(8, 0).0
                ),
                why(1),
            ),
            (
                format!(
                    "wsh(sortedmulti(1,{},{},{}))",
                    key(7, 0).0,
                    key(8, 0).0,
                    key(7, 3).0
                ),
                why(2),
            ),
            (
                format!("wsh(sortedmulti(1,{},{relabelled}))", key(7, 0).0),
                why(1),
            ),
        ] {
            match Described::parse(&text) {
                Err(MobileSignerError::InvalidPsbtJson(said)) => assert!(
                    said.starts_with("descriptor: ") && said.contains(&why),
                    "{text}\n refused with {said:?}, not {why:?}"
                ),
                Err(other) => panic!("{text}: {other:?}"),
                Ok(_) => panic!("{text} was read, not refused with {why:?}"),
            }
        }
        let three_phrases = format!(
            "wsh(sortedmulti(2,{},{},{}))",
            key(7, 0).0,
            key(8, 0).0,
            key(9, 1).0
        );
        assert_eq!(
            Described::parse(&three_phrases).unwrap().keys().len(),
            3,
            "keys of three phrases still parse, one of them at account 1"
        );
    }
}
