//! Proof that an address was really paid, so that a scan grows past it only on evidence. A proof is a
//! confirmed transaction that pays the address, its merkle branch and its block's header, checked
//! against each other and against proof of work. No second operator is asked. The user declined that
//! check on 5 Nov 2026, so a header stands on its own work.

use bitcoin::block::Header;
use bitcoin::consensus::encode::deserialize;
use bitcoin::consensus::params::Params;
use bitcoin::hashes::Hash;
use bitcoin::pow::{CompactTarget, Target};
use bitcoin::{Address, Transaction, TxMerkleNode, Txid};

use crate::MobileSignerError;
use crate::chain::{ChainSource, MerkleBranch};

/// What a proof's header must carry beyond meeting its own target.
#[derive(Clone, Copy, Debug)]
pub(crate) enum Anchor {
    /// Nothing more. Testnet4 accepts a minimum-difficulty block whenever 20 minutes pass without
    /// one, so no floor holds there, and a lying testnet4 source can forge a proof with about 2^32
    /// hashes. The user accepted that risk for testnet4 only.
    Testnet4,
    /// The target of the header's mainnet retarget period, from `MAINNET_BITS`.
    Mainnet,
}

/// A payment to the wallet's address at `index` that `prove_use` checked. Only `prove_use` builds one.
#[derive(Debug, PartialEq, Eq)]
pub(crate) struct ProvenUse {
    index: u32,
    txid: Txid,
    height: u64,
}

/// Why an address's use is not proven.
#[derive(Debug, PartialEq, Eq)]
pub(crate) enum NotProven {
    /// The source lists no confirmed transaction that pays the address. It is unused, or paid only
    /// in the mempool so far.
    NoConfirmedPayment,
    /// A read got no usable answer. Asking later may prove it.
    Unreachable(String),
    /// The source lied or contradicted itself.
    Lie(String),
}

impl ProvenUse {
    /// The wallet index whose payment this proves.
    pub(crate) fn index(&self) -> u32 {
        self.index
    }

    /// A proof of a payment to `index` that a test models instead of checking, so a scan runs without a source.
    #[cfg(test)]
    pub(crate) fn modelled(index: u32) -> Self {
        ProvenUse {
            index,
            txid: Txid::all_zeros(),
            height: 0,
        }
    }
}

/// A source's read failed. The wallet's reads report lost connections and unreadable bodies alike,
/// so a history this proof cannot read counts as unreachable, never as a lie. The text is the read's own,
/// so a throttled read still names its status.
impl From<MobileSignerError> for NotProven {
    fn from(error: MobileSignerError) -> Self {
        NotProven::Unreachable(match error {
            MobileSignerError::InvalidPsbtJson(text) => text,
            other => other.to_string(),
        })
    }
}

/// Proves that `address`, the wallet's address at `index`, was paid, from the newest confirmed
/// transaction the source lists as paying it. The transaction must hash to its id, not be 64 bytes
/// without its witness, and pay the address's script. Its branch must climb to the merkle root of
/// the block the source names, through no inner node that reads as a transaction. That block's
/// header must hash to the block's id and meet its own target, within the network's limit and the
/// `anchor`. Five reads: the history and tip, then the raw transaction, the branch and the header.
pub(crate) fn prove_use(
    source: &dyn ChainSource,
    anchor: Anchor,
    address: &Address,
    index: u32,
) -> Result<ProvenUse, NotProven> {
    // shortcut: the first history page only, so an address whose 25 newest confirmed transactions all
    // spend from it stays unproven, read further pages if a wallet ever hits that
    let (pages, _tip) = source.history(&[address])?;
    let shown = address.to_string();
    let Some((txid, block)) = pages.iter().flatten().find_map(|tx| {
        let block = tx.confirmed.as_ref()?;
        let pays = tx
            .outputs
            .iter()
            .any(|output| output.address.as_deref() == Some(shown.as_str()));
        pays.then_some((tx.txid.as_str(), block))
    }) else {
        return Err(NotProven::NoConfirmedPayment);
    };
    let lie = |what: String| NotProven::Lie(format!("{address}: {what}"));
    let txid: Txid = txid
        .parse()
        .map_err(|e| lie(format!("the history lists {txid:?}, which is no txid: {e}")))?;
    let block_hash = block.block_hash.ok_or_else(|| {
        lie(format!(
            "transaction {txid} is confirmed in no block the source names"
        ))
    })?;
    let height = block.height;

    let tx = source.raw_tx(&txid)?;
    if tx.compute_txid() != txid {
        return Err(lie(format!(
            "the raw transaction {txid} is {}",
            tx.compute_txid()
        )));
    }
    if tx.base_size() == 64 {
        return Err(lie(format!(
            "transaction {txid} is 64 bytes without its witness, a merkle node's size (CVE-2017-12842)"
        )));
    }
    if !tx
        .output
        .iter()
        .any(|output| output.script_pubkey == address.script_pubkey())
    {
        return Err(lie(format!("transaction {txid} does not pay it")));
    }
    let root = merkle_root(txid, &source.merkle_branch(&txid)?).map_err(lie)?;

    let header = source.header(&block_hash)?;
    if header.block_hash() != block_hash {
        return Err(lie(format!(
            "the header of block {block_hash} is block {}",
            header.block_hash()
        )));
    }
    if header.merkle_root != root {
        return Err(lie(format!(
            "transaction {txid}'s branch does not reach the merkle root of block {block_hash}"
        )));
    }
    has_work(&header, height, anchor)
        .map_err(|what| lie(format!("block {block_hash} at height {height}: {what}")))?;
    Ok(ProvenUse {
        index,
        txid,
        height,
    })
}

/// The root that `branch` climbs to from `txid`. A position with bits past the branch is refused,
/// and so is an inner node that reads as a transaction. That is how a 64-byte transaction in a real
/// block passes off a made-up one as its child (CVE-2017-12842), and Electrum refuses it the same way.
fn merkle_root(txid: Txid, branch: &MerkleBranch) -> Result<TxMerkleNode, String> {
    let mut node = txid.to_byte_array();
    let mut pos = branch.pos;
    for sibling in &branch.hashes {
        let pair = if pos & 1 == 1 {
            [sibling.to_byte_array(), node]
        } else {
            [node, sibling.to_byte_array()]
        }
        .concat();
        if deserialize::<Transaction>(&pair).is_ok() {
            return Err(format!(
                "an inner node of transaction {txid}'s branch reads as a transaction (CVE-2017-12842)"
            ));
        }
        node = TxMerkleNode::hash(&pair).to_byte_array();
        pos >>= 1;
    }
    if pos != 0 {
        return Err(format!(
            "position {} does not fit a branch of {} hashes",
            branch.pos,
            branch.hashes.len()
        ));
    }
    Ok(TxMerkleNode::from_byte_array(node))
}

/// Whether `header`, claimed at `height`, carries the work its network demands. Its hash must meet
/// the target its bits encode, that target must be within the network's limit, and on mainnet the
/// bits must fit the retarget period of `height`.
fn has_work(header: &Header, height: u64, anchor: Anchor) -> Result<(), String> {
    let params = match anchor {
        Anchor::Testnet4 => Params::TESTNET4,
        Anchor::Mainnet => Params::MAINNET,
    };
    let bits = header.bits.to_consensus();
    if header.target() > params.max_attainable_target {
        return Err(format!(
            "bits {bits:#010x} are easier than the network allows"
        ));
    }
    if !header.target().is_met_by(header.block_hash()) {
        return Err("its hash does not meet its own target".into());
    }
    match anchor {
        Anchor::Testnet4 => Ok(()),
        Anchor::Mainnet => fits_mainnet_period(bits, height),
    }
}

/// Whether `bits` fit the mainnet retarget period of `height`. Inside the table they must be the
/// period's own. Past it, the target may grow at most fourfold per period from the last entry. The
/// source picks the height it reports, so no height may make a forged proof cheaper than the easiest
/// period since segwit. A height before segwit is refused, and the growth past the table stops at
/// that period's target. Otherwise a block mined on a laptop at difficulty 1 would pass, claimed in
/// periods 0 to 15 or 24 periods past the table.
fn fits_mainnet_period(bits: u32, height: u64) -> Result<(), String> {
    let period = height / 2016;
    if period < SEGWIT_PERIOD {
        return Err(format!(
            "period {period} is before segwit activated in period {SEGWIT_PERIOD}"
        ));
    }
    if let Some(&own) = usize::try_from(period)
        .ok()
        .and_then(|p| MAINNET_BITS.get(p))
    {
        return if bits == own {
            Ok(())
        } else {
            Err(format!(
                "bits {bits:#010x} are not period {period}'s {own:#010x}"
            ))
        };
    }
    let easiest = MAINNET_BITS[SEGWIT_PERIOD as usize..]
        .iter()
        .map(|&bits| target(bits))
        .fold(Target::ZERO, Target::max);
    let last = MAINNET_BITS.len() - 1;
    let mut floor = target(MAINNET_BITS[last]);
    for _ in last as u64..period {
        if floor >= easiest {
            break;
        }
        floor = floor.max_transition_threshold(Params::MAINNET);
    }
    if target(bits) <= floor.min(easiest) {
        Ok(())
    } else {
        Err(format!(
            "bits {bits:#010x} are easier than period {period} can be"
        ))
    }
}

/// Segwit activated at height 481,824, the first block of retarget period 239. Every address this
/// wallet derives is a segwit one, so none was paid before it.
const SEGWIT_PERIOD: u64 = 239;

fn target(bits: u32) -> Target {
    Target::from_compact(CompactTarget::from_consensus(bits))
}

/// The target of every mainnet retarget period in compact form. `MAINNET_BITS[p]` covers heights 2016p
/// to 2016p + 2015. Period 0 is difficulty 1, as genesis says. Entry p from 1 on is entry p - 1 of
/// Electrum's `electrum/chains/mainnet/checkpoints.json` at tag 4.8.3 (commit b774e151de7e, file blob
/// 231e4c37faab), the target computed from period p - 1. A test checks each entry against a copy of
/// that file in `fixtures/electrum`. MIT License, Copyright (c) 2011-2024 The Electrum developers and
/// Copyright (c) 2011-2024 Thomas Voegtlin, in full in `fixtures/electrum/LICENCE`.
const MAINNET_BITS: [u32; 479] = [
    0x1d00ffff, 0x1d00ffff, 0x1d00ffff, 0x1d00ffff, 0x1d00ffff, 0x1d00ffff, 0x1d00ffff, 0x1d00ffff,
    0x1d00ffff, 0x1d00ffff, 0x1d00ffff, 0x1d00ffff, 0x1d00ffff, 0x1d00ffff, 0x1d00ffff, 0x1d00ffff,
    0x1d00d86a, 0x1d00c428, 0x1d00be71, 0x1d008cc3, 0x1c654657, 0x1c43b3e5, 0x1c387f6f, 0x1c381375,
    0x1c2a1115, 0x1c20bca7, 0x1c16546f, 0x1c13ec53, 0x1c159c24, 0x1c0f675c, 0x1c0eba64, 0x1c0d3142,
    0x1c0ae493, 0x1c05a3f4, 0x1c0168fd, 0x1c010c5a, 0x1c00ba18, 0x1c00800e, 0x1b692098, 0x1b5bede6,
    0x1b4766ed, 0x1b31b2a3, 0x1b2f8e9d, 0x1b1e7eca, 0x1b153263, 0x1b0e7256, 0x1b098b2a, 0x1b081cd2,
    0x1b055953, 0x1b04864c, 0x1b0404cb, 0x1b038dee, 0x1b02fa29, 0x1b028552, 0x1b01cc26, 0x1b012dcd,
    0x1b00dc31, 0x1b00f339, 0x1b00cbbd, 0x1b00b5ac, 0x1b0098fa, 0x1a6a93b3, 0x1a44b9f2, 0x1a269421,
    0x1a1d932f, 0x1a132185, 0x1a0c2a12, 0x1a0abbcf, 0x1a09ec04, 0x1a08e1e5, 0x1a094a86, 0x1a096fe3,
    0x1a098ea5, 0x1a09ee5d, 0x1a0b6d4b, 0x1a0df0ca, 0x1a0e119a, 0x1a0f61b1, 0x1a0e8668, 0x1a0e76ba,
    0x1a0d69d7, 0x1a0cd43f, 0x1a0c290b, 0x1a0c309c, 0x1a0b350c, 0x1a0b3287, 0x1a0a507e, 0x1a0aa1e3,
    0x1a0b1ef7, 0x1a09ae02, 0x1a0a8b5f, 0x1a0a98d6, 0x1a09b78a, 0x1a099431, 0x1a08fd2e, 0x1a083cc9,
    0x1a07a85e, 0x1a06dfbe, 0x1a063a38, 0x1a05db8b, 0x1a057e08, 0x1a0575ef, 0x1a0513c5, 0x1a04faeb,
    0x1a04e0ea, 0x1a04fa62, 0x1a05a16b, 0x1a0529b1, 0x1a05a6b1, 0x1a051f3c, 0x1a04985c, 0x1a03d74b,
    0x1a0375fa, 0x1a02816e, 0x1a022fbe, 0x1a01de94, 0x1a01aa3d, 0x1a017fe9, 0x1a016164, 0x1a011337,
    0x1a00de15, 0x1a00c94e, 0x1a00a429, 0x1a008968, 0x1972dbf2, 0x19548732, 0x19415257, 0x1931679c,
    0x19262222, 0x191cdc20, 0x1916b0ca, 0x19100ab6, 0x190afc85, 0x190867f3, 0x19070bfb, 0x19061242,
    0x1904ba6e, 0x1903a30c, 0x1903071f, 0x19026666, 0x1901f52c, 0x1901a36e, 0x19015f53, 0x19012026,
    0x190102b1, 0x1900db99, 0x1900b3aa, 0x19009d8c, 0x1900896c, 0x187c3053, 0x18692842, 0x185d859a,
    0x1851aba2, 0x18415fd1, 0x183f6be6, 0x183aaea2, 0x1837ba62, 0x182e1c58, 0x182815ee, 0x1824dbe9,
    0x181fb893, 0x181f6973, 0x181e8dc0, 0x181bc330, 0x181b4861, 0x181b7b74, 0x181bdd7c, 0x181b0dca,
    0x1819012f, 0x181aa3c0, 0x1818bb87, 0x18178d3a, 0x18172ec0, 0x181788f2, 0x18163c71, 0x181717f0,
    0x181713dd, 0x181686f5, 0x18171a8b, 0x18162043, 0x1816418e, 0x181586c8, 0x18150815, 0x1814dd04,
    0x181443c4, 0x18134dc1, 0x181287ba, 0x18121472, 0x18120f14, 0x1811a954, 0x1810b289, 0x180f1e76,
    0x180de64f, 0x180bc409, 0x180a9591, 0x1809b31b, 0x180928f0, 0x1807a114, 0x1806b99f, 0x1806f0a8,
    0x1806a4c3, 0x180696f4, 0x1806274b, 0x18062776, 0x1805a8fa, 0x18058436, 0x18059ba0, 0x18053fd6,
    0x180526fd, 0x18052669, 0x18057228, 0x18050edc, 0x1804fb08, 0x1804de5e, 0x18048ed4, 0x180440c4,
    0x180455d2, 0x18045174, 0x1803e6d4, 0x1803d589, 0x18038b85, 0x180375ff, 0x18034379, 0x1802cc47,
    0x18029ab9, 0x18027e93, 0x180262df, 0x18024fb1, 0x1802335a, 0x18021c73, 0x18021b3e, 0x1801f6a7,
    0x1801d854, 0x18019eaf, 0x18018b7e, 0x18018d30, 0x18015ddc, 0x18014735, 0x180130e0, 0x18013ce9,
    0x1801310b, 0x1800ff18, 0x1800fa73, 0x1800eb30, 0x1800c1bd, 0x1800ce4b, 0x1800d0f6, 0x1800b0ed,
    0x18009645, 0x180091c1, 0x177e578c, 0x176c2146, 0x1761e9f8, 0x175d97dc, 0x175589a3, 0x17514a49,
    0x17502ab7, 0x1749500d, 0x1745fb53, 0x1743eca9, 0x17415a49, 0x1738f841, 0x17376f56, 0x17347a28,
    0x17365a17, 0x172f4f7b, 0x172c0da7, 0x1729d72d, 0x172819a1, 0x17275a1f, 0x1725c191, 0x17272fbd,
    0x17272d92, 0x172a4e2f, 0x1731d97c, 0x17371ef4, 0x173218a5, 0x172fd633, 0x17306835, 0x172e6f88,
    0x172e5b50, 0x172e6117, 0x172c1f6c, 0x172c071d, 0x172c4e11, 0x1729ff38, 0x1729fb45, 0x1725bb76,
    0x1725fd03, 0x1723792c, 0x171f0d9b, 0x171f3a08, 0x171c3039, 0x171ba3d1, 0x171a213e, 0x1717abf5,
    0x17160f24, 0x1715a35c, 0x17148edf, 0x171620d1, 0x1715b23e, 0x1715dbd2, 0x1715bcd0, 0x171465f2,
    0x17130c78, 0x171232ff, 0x17121ad4, 0x17122cbc, 0x17110119, 0x17143b41, 0x171320bc, 0x1711a333,
    0x17117a39, 0x171297f6, 0x17147f35, 0x1711d4f2, 0x1711d519, 0x17103a15, 0x1710b4f8, 0x17109bac,
    0x171007ea, 0x17103a12, 0x170e92aa, 0x170e95de, 0x170e134e, 0x1710c433, 0x170ffedd, 0x170eb156,
    0x170f1372, 0x170f2217, 0x170da8a1, 0x170d8457, 0x170d21b9, 0x170cf4e3, 0x170d1f8c, 0x170cdf6f,
    0x170c2a48, 0x170bef93, 0x170da863, 0x170b3ce9, 0x170d5f7b, 0x170e1ef9, 0x171398ce, 0x17149624,
    0x17136aa2, 0x1712180b, 0x170ffaa0, 0x170f48e4, 0x170ed0eb, 0x170e2632, 0x170e0408, 0x170cfecf,
    0x170c69ea, 0x170c9a13, 0x170ba21f, 0x170b98ab, 0x170b8c8b, 0x170a9080, 0x170a8bb4, 0x170a1078,
    0x170a3773, 0x170a40c0, 0x1709d897, 0x1709f8d9, 0x17097275, 0x170901ba, 0x17096a20, 0x17094b6a,
    0x170984cc, 0x1709a7af, 0x170a2a04, 0x1709fd7e, 0x1709ed88, 0x17091620, 0x1708c894, 0x1708f9ae,
    0x1707e772, 0x1707a429, 0x1707a812, 0x17079e15, 0x17083830, 0x1707f590, 0x1708417e, 0x17077ce2,
    0x17072720, 0x17073039, 0x170689a3, 0x17067681, 0x1706023e, 0x1705e0b2, 0x1705c739, 0x1705dd01,
    0x1705ae3a, 0x17057e69, 0x1705606d, 0x17058ebe, 0x17053894, 0x17056102, 0x17055f5b, 0x17050f7b,
    0x170532ae, 0x1704ed7f, 0x1704e90f, 0x17049ca9, 0x17048194, 0x17045a12, 0x17042450, 0x17042e95,
    0x1703e8b3, 0x1703d869, 0x1704005a, 0x1703ba5d, 0x170371b1, 0x17038c12, 0x17035a59, 0x170362d3,
    0x17034219, 0x170331db, 0x1703629a, 0x170355f0, 0x17035cb8, 0x17035d25, 0x17038a6d, 0x17036e3a,
    0x17031abe, 0x17033d76, 0x1703255b, 0x1703098c, 0x17032f14, 0x17030ecd, 0x1702f128, 0x1702c4e4,
    0x1702c070, 0x1702b563, 0x170297fa, 0x1702905c, 0x17028c61, 0x17029a8a, 0x17027726, 0x17028bb1,
    0x17028281, 0x1702796c, 0x17025105, 0x170248b6, 0x17025ced, 0x17025049, 0x17023774, 0x17023a04,
    0x17026816, 0x17023aa6, 0x1702349e, 0x17022cb3, 0x17022b91, 0x170211ac, 0x1701fa38, 0x1701ddb4,
    0x1701eb21, 0x1701cdfb, 0x1701d936, 0x1701e2a0, 0x1701e63a, 0x1701e605, 0x1701ebf2, 0x1701fca1,
    0x17023c7e, 0x1701f303, 0x1701f0cc, 0x17021a91, 0x17020684, 0x17021369, 0x17021ff0, 0x17020f79,
    0x1702068f, 0x170240c3, 0x17021a42, 0x1702369d, 0x17023ad4, 0x1702353d, 0x17023cc1,
];

#[cfg(test)]
mod tests {
    use std::collections::HashMap;
    use std::sync::Mutex;
    use std::sync::atomic::{AtomicBool, Ordering};

    use bitcoin::absolute::LockTime;
    use bitcoin::block::Version;
    use bitcoin::consensus::encode::{deserialize, deserialize_hex, serialize_hex};
    use bitcoin::transaction::{self, OutPoint, TxIn, TxOut};
    use bitcoin::{Amount, BlockHash, Network, ScriptBuf};

    use super::*;
    use crate::chain::{ESPLORA_TESTNET4, EsploraSource, Transport};

    // Recorded on 5 Nov 2026 from blockstream.info, one read-only GET each:
    // testnet4_address_txs_real.json   /testnet4/api/address/tb1qtk89me2ae95dmlp3yfl4q9ynpux8mxjujuf2fr/txs
    // testnet4_tx_hex_real.txt         /testnet4/api/tx/c5ed075e.../hex
    // testnet4_merkle_proof_real.json  /testnet4/api/tx/c5ed075e.../merkle-proof
    // testnet4_header_156696_real.txt  /testnet4/api/block/000000000000000123d4.../header
    // mainnet_tx_real.json             /api/tx/f13c5f8f..., the transaction at position 1012 of block 800,000
    // mainnet_tx_hex_real.txt          /api/tx/f13c5f8f.../hex
    // mainnet_merkle_proof_real.json   /api/tx/f13c5f8f.../merkle-proof
    // mainnet_header_<height>_real.txt /api/block/<the hash /api/block-height/<height> gave>/header
    // mainnet_481824_coinbase_*        /api/tx/da917699.../hex and .../merkle-proof, the coinbase
    //                                  that /api/block/<block 481,824's hash>/txid/0 named
    macro_rules! fixture {
        ($name:literal) => {
            include_str!(concat!("../fixtures/proof/", $name))
        };
    }

    /// Receive address 0 of the public 24-word test phrase, `abandon` x23 then `art`.
    const TESTNET4_ADDRESS: &str = "tb1qtk89me2ae95dmlp3yfl4q9ynpux8mxjujuf2fr";
    const TESTNET4_TXID: &str = "c5ed075ef44b807f51123cf0c6e53c75385a17a510f995b560bf73b1102d5474";
    const TESTNET4_BLOCK: &str = "000000000000000123d492d5719746cbd6f43ea40aff45cb45ed5d8a6cff003d";
    const MAINNET_ADDRESS: &str = "bc1qwd9exte3yv03a7guel9t6nxfw0r5sh7pewma5l";
    const MAINNET_TXID: &str = "f13c5f8fbe7f01daf475ce39fdfb6bea766971a2a5cb3398f7da0e3fe7374877";
    const MAINNET_BLOCK: &str = "00000000000000000002a7c4c1e48d76c5a37902165a270156b7a8d72728a054";
    const SEGWIT_BLOCK: &str = "0000000000000000001c8018d9cb3b742ef25114f27563e3fc4a1902167f9893";
    const SEGWIT_COINBASE: &str =
        "da917699942e4a96272401b534381a75512eeebe8403084500bd637bd47168b3";
    const INDEX: u32 = 7;
    /// Where the forged block's header search starts, and what `mine_the_forged_block` found.
    const FORGED_TIME: u32 = 1_762_300_800;
    const FORGED_NONCE: u32 = 0x1d18_7bb5;

    /// A source that answers each URL in `bodies` with its body and any other URL as a 404, and
    /// keeps the URLs it was asked.
    struct Answers {
        bodies: HashMap<String, String>,
        asked: Mutex<Vec<String>>,
    }

    impl Transport for Answers {
        fn get(&self, url: &str) -> Result<String, MobileSignerError> {
            self.asked.lock().unwrap().push(url.to_string());
            self.bodies.get(url).cloned().ok_or_else(|| {
                MobileSignerError::InvalidPsbtJson(format!("{url}: status code 404"))
            })
        }

        fn broadcast(&self, _: &str, _: &str) -> Result<String, MobileSignerError> {
            unreachable!("a proof posts nothing")
        }
    }

    /// One payment as a source serves it, with the history page that lists it and the raw
    /// transaction, merkle proof and header it answers for it.
    struct Proof {
        address: Address,
        txid: Txid,
        block: BlockHash,
        page: String,
        raw: String,
        branch: String,
        header: String,
    }

    impl Proof {
        fn answers(&self) -> Answers {
            let (base, address, txid, block) =
                (ESPLORA_TESTNET4, &self.address, &self.txid, &self.block);
            Answers {
                bodies: HashMap::from([
                    (format!("{base}/address/{address}/txs"), self.page.clone()),
                    // The tip a history read also asks for. A proof does not use it.
                    (format!("{base}/blocks/tip/height"), "974650".into()),
                    (format!("{base}/tx/{txid}/hex"), self.raw.clone()),
                    (
                        format!("{base}/tx/{txid}/merkle-proof"),
                        self.branch.clone(),
                    ),
                    (format!("{base}/block/{block}/header"), self.header.clone()),
                ]),
                asked: Mutex::new(Vec::new()),
            }
        }

        fn prove(&self, anchor: Anchor) -> Result<ProvenUse, NotProven> {
            prove_use(
                &EsploraSource(&self.answers()),
                anchor,
                &self.address,
                INDEX,
            )
        }

        /// The source now lists the payment in block `block` at `height`.
        fn claim(&mut self, height: u64, block: BlockHash) {
            self.page = page(&self.txid, &self.address, height, &block);
            self.block = block;
        }
    }

    fn address(text: &str) -> Address {
        text.parse::<Address<_>>().unwrap().assume_checked()
    }

    /// The mainnet address with the same script as `text`.
    fn on_mainnet(text: &str) -> Address {
        Address::from_script(&address(text).script_pubkey(), Network::Bitcoin).unwrap()
    }

    /// A history page that lists `txid` as paying `address` in block `block` at `height`.
    fn page(txid: &Txid, address: &Address, height: u64, block: &BlockHash) -> String {
        format!(
            r#"[{{"txid":"{txid}","vin":[],"vout":[{{"scriptpubkey_address":"{address}","value":1000}}],"status":{{"confirmed":true,"block_height":{height},"block_hash":"{block}"}}}}]"#
        )
    }

    fn real_testnet4() -> Proof {
        Proof {
            address: address(TESTNET4_ADDRESS),
            txid: TESTNET4_TXID.parse().unwrap(),
            block: TESTNET4_BLOCK.parse().unwrap(),
            page: fixture!("testnet4_address_txs_real.json").into(),
            raw: fixture!("testnet4_tx_hex_real.txt").into(),
            branch: fixture!("testnet4_merkle_proof_real.json").into(),
            header: fixture!("testnet4_header_156696_real.txt").into(),
        }
    }

    fn real_mainnet() -> Proof {
        Proof {
            address: address(MAINNET_ADDRESS),
            txid: MAINNET_TXID.parse().unwrap(),
            block: MAINNET_BLOCK.parse().unwrap(),
            page: format!("[{}]", fixture!("mainnet_tx_real.json")),
            raw: fixture!("mainnet_tx_hex_real.txt").into(),
            branch: fixture!("mainnet_merkle_proof_real.json").into(),
            header: fixture!("mainnet_header_800000_real.txt").into(),
        }
    }

    /// The coinbase of block 481,824, the first block of segwit's period, listed as paying its first
    /// output's address.
    fn real_segwit_coinbase() -> Proof {
        let raw = fixture!("mainnet_481824_coinbase_hex_real.txt");
        let tx: Transaction = deserialize_hex(raw.trim()).unwrap();
        let address = Address::from_script(&tx.output[0].script_pubkey, Network::Bitcoin).unwrap();
        let (txid, block) = (
            SEGWIT_COINBASE.parse().unwrap(),
            SEGWIT_BLOCK.parse().unwrap(),
        );
        Proof {
            page: page(&txid, &address, 481_824, &block),
            raw: raw.into(),
            branch: fixture!("mainnet_481824_coinbase_merkle_proof_real.json").into(),
            header: fixture!("mainnet_header_481824_real.txt").into(),
            address,
            txid,
            block,
        }
    }

    fn node(tx: &Transaction) -> TxMerkleNode {
        TxMerkleNode::from_byte_array(tx.compute_txid().to_byte_array())
    }

    /// A made-up payment of 50,000 sats to `TESTNET4_ADDRESS`'s script that no block holds.
    fn payment(lock_time: u32) -> Transaction {
        Transaction {
            version: transaction::Version::TWO,
            lock_time: LockTime::from_consensus(lock_time),
            input: vec![TxIn {
                previous_output: OutPoint {
                    txid: Txid::from_byte_array([0x11; 32]),
                    vout: 0,
                },
                ..TxIn::default()
            }],
            output: vec![TxOut {
                value: Amount::from_sat(50_000),
                script_pubkey: address(TESTNET4_ADDRESS).script_pubkey(),
            }],
        }
    }

    /// The payment the forged block holds.
    fn paid() -> Transaction {
        payment(0)
    }

    /// A payment the forged block does not hold, whose txid begins the way a transaction with one
    /// input does, so that a 64-byte transaction can start with it.
    fn fake() -> Transaction {
        (1..)
            .map(payment)
            .find(|tx| tx.compute_txid().to_byte_array()[4] == 1)
            .unwrap()
    }

    /// The rest of a 64-byte transaction that starts with `fake()`'s txid: the end of the spent
    /// coin's txid, its output index, an empty scriptSig, the sequence, one output of 240 sats to
    /// the 4-byte P2A script, and the lock time.
    fn fake_sibling() -> TxMerkleNode {
        let mut half = vec![0; 5];
        half.extend(0u32.to_le_bytes());
        half.push(0);
        half.extend(u32::MAX.to_le_bytes());
        half.push(1);
        half.extend(240u64.to_le_bytes());
        half.push(4);
        half.extend(ScriptBuf::new_p2a().as_bytes());
        half.extend(0u32.to_le_bytes());
        TxMerkleNode::from_byte_array(half.try_into().unwrap())
    }

    /// The forged block's second transaction, a valid 64-byte transaction whose halves are
    /// `fake()`'s txid and `fake_sibling()`.
    fn t64() -> Transaction {
        let mut bytes = fake().compute_txid().to_byte_array().to_vec();
        bytes.extend(fake_sibling().to_byte_array());
        deserialize(&bytes).unwrap()
    }

    /// A block made up for these tests that holds `paid()` and `t64()`, with `bits` and `nonce`.
    fn forged_header(bits: u32, nonce: u32) -> Header {
        let leaves = [node(&paid()).to_byte_array(), node(&t64()).to_byte_array()];
        Header {
            version: Version::from_consensus(0x2000_0000),
            prev_blockhash: BlockHash::from_byte_array([0x22; 32]),
            merkle_root: TxMerkleNode::hash(&leaves.concat()),
            time: FORGED_TIME,
            bits: CompactTarget::from_consensus(bits),
            nonce,
        }
    }

    /// The forged block mined at difficulty 1, the easiest the network allows, in about 2^32 hashes.
    fn forged_block() -> Header {
        forged_header(0x1d00ffff, FORGED_NONCE)
    }

    /// The forged block's proof of `tx` at `pos` with sibling `hashes`, listed as paying `address`
    /// in it at `height`.
    fn forged(
        tx: &Transaction,
        address: Address,
        height: u64,
        hashes: &[TxMerkleNode],
        pos: u64,
        header: &Header,
    ) -> Proof {
        let txid = tx.compute_txid();
        let block = header.block_hash();
        let hashes: Vec<String> = hashes.iter().map(|h| format!("\"{h}\"")).collect();
        Proof {
            page: page(&txid, &address, height, &block),
            raw: serialize_hex(tx),
            branch: format!(
                r#"{{"block_height":{height},"merkle":[{}],"pos":{pos}}}"#,
                hashes.join(",")
            ),
            header: serialize_hex(header),
            address,
            txid,
            block,
        }
    }

    /// `paid()` proven by the forged block, listed as paying `address` at `height`.
    fn forged_payment(address: Address, height: u64) -> Proof {
        forged(
            &paid(),
            address,
            height,
            &[node(&t64())],
            0,
            &forged_block(),
        )
    }

    /// `proved` is a lie whose reason contains `why`, the words of the check that refused it.
    #[track_caller]
    fn assert_lie(proved: Result<ProvenUse, NotProven>, why: &str) {
        match proved {
            Err(NotProven::Lie(reason)) if reason.contains(why) => {}
            other => panic!("expected a lie about {why:?}, got {other:?}"),
        }
    }

    #[test]
    #[ignore = "mines FORGED_NONCE, minutes of CPU: cargo test --release -p mobile-signer-ffi mine_the_forged_block -- --ignored --nocapture"]
    fn mine_the_forged_block() {
        let found = AtomicBool::new(false);
        std::thread::scope(|scope| {
            for thread in 0..8 {
                let found = &found;
                scope.spawn(move || {
                    let mut header = forged_header(0x1d00ffff, 0);
                    header.time += thread;
                    for nonce in 0..=u32::MAX {
                        if nonce % (1 << 20) == 0 && found.load(Ordering::Relaxed) {
                            return;
                        }
                        header.nonce = nonce;
                        if header.target().is_met_by(header.block_hash()) {
                            found.store(true, Ordering::Relaxed);
                            println!(
                                "FORGED_TIME = {}, FORGED_NONCE = {nonce:#010x}",
                                header.time
                            );
                            return;
                        }
                    }
                });
            }
        });
        assert!(found.load(Ordering::Relaxed), "no nonce at these 8 times");
    }

    #[test]
    fn a_real_testnet4_payment_is_proven() {
        let proof = real_testnet4();
        let answers = proof.answers();
        let proved = prove_use(
            &EsploraSource(&answers),
            Anchor::Testnet4,
            &proof.address,
            INDEX,
        );
        // The newest confirmed transaction spends from the address, so the one before it is proven.
        let txid = proof.txid;
        assert_eq!(
            proved,
            Ok(ProvenUse {
                index: INDEX,
                txid,
                height: 156_696
            })
        );
        let base = ESPLORA_TESTNET4;
        assert_eq!(
            *answers.asked.lock().unwrap(),
            [
                format!("{base}/address/{TESTNET4_ADDRESS}/txs"),
                format!("{base}/blocks/tip/height"),
                format!("{base}/tx/{txid}/hex"),
                format!("{base}/tx/{txid}/merkle-proof"),
                format!("{base}/block/{TESTNET4_BLOCK}/header"),
            ]
        );
    }

    #[test]
    fn a_real_mainnet_payment_is_proven_at_its_periods_target() {
        let proof = real_mainnet();
        let txid = proof.txid;
        assert_eq!(
            proof.prove(Anchor::Mainnet),
            Ok(ProvenUse {
                index: INDEX,
                txid,
                height: 800_000
            })
        );
    }

    /// The risk the user accepted for testnet4 only. A block mined on a laptop in minutes proves a
    /// payment that never happened.
    #[test]
    fn a_forged_testnet4_block_proves_use() {
        let txid = paid().compute_txid();
        assert_eq!(
            forged_payment(address(TESTNET4_ADDRESS), 100).prove(Anchor::Testnet4),
            Ok(ProvenUse {
                index: INDEX,
                txid,
                height: 100
            })
        );
    }

    #[test]
    fn no_confirmed_payment_proves_nothing() {
        let mut proof = real_testnet4();
        proof.page = "[]".into();
        assert_eq!(
            proof.prove(Anchor::Testnet4),
            Err(NotProven::NoConfirmedPayment)
        );
        proof.page = fixture!("testnet4_address_txs_real.json")
            .replace(r#""confirmed":true"#, r#""confirmed":false"#);
        assert_eq!(
            proof.prove(Anchor::Testnet4),
            Err(NotProven::NoConfirmedPayment)
        );
    }

    #[test]
    fn a_read_that_gets_no_answer_is_unreachable() {
        let proof = real_testnet4();
        for url in proof.answers().bodies.keys() {
            let mut answers = proof.answers();
            answers.bodies.remove(url);
            let proved = prove_use(
                &EsploraSource(&answers),
                Anchor::Testnet4,
                &proof.address,
                INDEX,
            );
            assert!(
                matches!(proved, Err(NotProven::Unreachable(_))),
                "{url}: {proved:?}"
            );
        }
    }

    #[test]
    fn an_answer_that_is_not_the_thing_asked_is_a_lie() {
        let lie_after = |corrupt: fn(&mut Proof), why: &str| {
            let mut proof = real_testnet4();
            corrupt(&mut proof);
            assert_lie(proof.prove(Anchor::Testnet4), why);
        };
        lie_after(
            |p| p.raw = "<html>Sign in to Wi-Fi</html>".into(),
            "raw answer",
        );
        lie_after(|p| p.raw = "0200".into(), "raw answer");
        lie_after(|p| p.branch = "{}".into(), "merkle proof");
        lie_after(
            |p| p.branch = r#"{"pos":194,"merkle":["c8fc"]}"#.into(),
            "merkle proof",
        );
        lie_after(
            |p| p.branch = r#"{"pos":-1,"merkle":[]}"#.into(),
            "merkle proof",
        );
        lie_after(|p| p.header.truncate(158), "header answer");
        lie_after(
            |p| p.page = p.page.replace(TESTNET4_TXID, "c5ed"),
            "no txid",
        );
        lie_after(
            |p| p.page = p.page.replace(TESTNET4_BLOCK, "a block"),
            "no block",
        );
    }

    /// The source shows the real transaction's place in its block but hands over another body, one
    /// that pays the address.
    #[test]
    fn a_raw_transaction_with_another_id_is_refused() {
        let mut proof = real_testnet4();
        proof.raw = serialize_hex(&paid());
        assert_lie(proof.prove(Anchor::Testnet4), "raw transaction");
    }

    /// A 64-byte transaction is the size of a merkle node (CVE-2017-12842). One can pay only a
    /// script of at most 4 bytes, so the forged block's pays the P2A script.
    #[test]
    fn a_64_byte_transaction_is_refused() {
        let p2a = Address::from_script(&ScriptBuf::new_p2a(), Network::Testnet4).unwrap();
        let proof = forged(&t64(), p2a, 100, &[node(&paid())], 1, &forged_block());
        assert_lie(proof.prove(Anchor::Testnet4), "64 bytes");
    }

    #[test]
    fn a_transaction_that_does_not_pay_the_address_is_refused() {
        let mut proof = real_testnet4();
        // Receive address 0 of the 12-word test phrase, `abandon` x11 then `about`.
        proof.address = address("tb1q6rz28mcfaxtmd6v789l9rrlrusdprr9pqcpvkl");
        proof.claim(156_696, proof.block);
        assert_lie(proof.prove(Anchor::Testnet4), "does not pay");
    }

    #[test]
    fn a_flipped_branch_byte_is_refused() {
        let mut proof = real_testnet4();
        let flipped = proof.branch.replacen("c8fc569a", "c8fc569b", 1);
        assert_ne!(flipped, proof.branch);
        proof.branch = flipped;
        assert_lie(proof.prove(Anchor::Testnet4), "merkle root");
    }

    /// The source names a real block with real work that does not hold the transaction.
    #[test]
    fn a_branch_to_another_blocks_root_is_refused() {
        let mut proof = real_testnet4();
        proof.claim(156_696, MAINNET_BLOCK.parse().unwrap());
        proof.header = fixture!("mainnet_header_800000_real.txt").into();
        assert_lie(proof.prove(Anchor::Testnet4), "merkle root");
    }

    /// 706 is the real position 194 plus 2^9, a bit that a 9-hash branch would never read.
    #[test]
    fn a_position_past_the_branch_is_refused() {
        let mut proof = real_testnet4();
        proof.branch = proof.branch.replace(r#""pos":194"#, r#""pos":706"#);
        assert_lie(proof.prove(Anchor::Testnet4), "position");
    }

    /// `fake()`'s txid and `fake_sibling()` are the two halves of `t64()`, which the forged block
    /// holds, so this branch climbs from a transaction no block holds through `t64()`'s leaf to the
    /// real root (CVE-2017-12842). Electrum refuses such an inner node, and so does a proof here.
    #[test]
    fn an_inner_node_that_reads_as_a_transaction_is_refused() {
        let hashes = [fake_sibling(), node(&paid())];
        let proof = forged(
            &fake(),
            address(TESTNET4_ADDRESS),
            100,
            &hashes,
            0b10,
            &forged_block(),
        );
        assert_lie(proof.prove(Anchor::Testnet4), "reads as a transaction");
    }

    #[test]
    fn a_header_that_hashes_to_another_block_is_refused() {
        let mut proof = real_testnet4();
        let named: BlockHash = MAINNET_BLOCK.parse().unwrap();
        proof.claim(156_696, named);
        assert_lie(proof.prove(Anchor::Testnet4), "header of block");
    }

    #[test]
    fn a_header_that_misses_its_own_target_is_refused() {
        let mut proof = real_testnet4();
        let mut header: Header = deserialize_hex(proof.header.trim()).unwrap();
        header.nonce ^= 1;
        proof.header = serialize_hex(&header);
        proof.claim(156_696, header.block_hash());
        assert_lie(proof.prove(Anchor::Testnet4), "own target");
    }

    /// Bits of 0x207fffff, a target near 2^255, are met by about every second hash.
    #[test]
    fn a_header_easier_than_the_network_allows_is_refused() {
        let mut header = forged_header(0x207fffff, 0);
        while !header.target().is_met_by(header.block_hash()) {
            header.nonce += 1;
        }
        let proof = forged(
            &paid(),
            address(TESTNET4_ADDRESS),
            100,
            &[node(&t64())],
            0,
            &header,
        );
        assert_lie(proof.prove(Anchor::Testnet4), "network allows");
    }

    /// The real header of block 800,000, in period 396, claimed in period 478, whose target is lower.
    #[test]
    fn a_mainnet_header_off_its_periods_bits_is_refused() {
        let mut proof = real_mainnet();
        proof.claim(963_648, proof.block);
        assert!(proof.prove(Anchor::Testnet4).is_ok());
        assert_lie(proof.prove(Anchor::Mainnet), "period 478");
    }

    /// The source picks the height it reports. Periods 0 to 15 were difficulty 1, so a block mined
    /// on a laptop would pass as one of theirs, but no segwit address was paid before period 239.
    #[test]
    fn a_mainnet_proof_before_segwit_is_refused() {
        let proof = forged_payment(on_mainnet(TESTNET4_ADDRESS), 1_000);
        assert!(proof.prove(Anchor::Testnet4).is_ok());
        assert_lie(proof.prove(Anchor::Mainnet), "segwit");
    }

    /// Period 479 follows the table's last, so its target is at most 4 times that one's. Block
    /// 800,000's header is within that. Block 481,824's is not, though it proves its own coinbase at
    /// its real height, the first of segwit's period.
    #[test]
    fn one_period_past_the_table_a_target_may_be_four_times_the_last() {
        let one_past = 479 * 2016;
        let mut within = real_mainnet();
        within.claim(one_past, within.block);
        assert!(within.prove(Anchor::Mainnet).is_ok());
        let mut easier = real_segwit_coinbase();
        assert!(easier.prove(Anchor::Mainnet).is_ok());
        easier.claim(one_past, easier.block);
        assert_lie(easier.prove(Anchor::Mainnet), "period 479");
    }

    /// 24 periods of fourfold growth from the last entry reach the network's limit, so a source
    /// that claims a far-future height would let a block mined on a laptop pass.
    #[test]
    fn a_far_future_mainnet_height_does_not_lower_the_floor() {
        for height in [(478 + 24) * 2016, u64::MAX] {
            let proof = forged_payment(on_mainnet(TESTNET4_ADDRESS), height);
            assert_lie(proof.prove(Anchor::Mainnet), "can be");
        }
    }

    /// `digits`, a decimal number below 2^256, as 32 big-endian bytes.
    fn decimal(digits: &str) -> [u8; 32] {
        let mut number = [0u8; 32];
        for digit in digits.bytes() {
            assert!(digit.is_ascii_digit(), "{digits}");
            let mut carry = u32::from(digit - b'0');
            for byte in number.iter_mut().rev() {
                carry += u32::from(*byte) * 10;
                *byte = carry as u8;
                carry >>= 8;
            }
            assert_eq!(carry, 0, "{digits} is 2^256 or more");
        }
        number
    }

    /// Entry i of the copy of Electrum's file, a decimal target, is period i + 1's in the table.
    #[test]
    fn the_table_is_electrums_checkpoints() {
        let json = include_str!("../fixtures/electrum/mainnet_checkpoints.json");
        let targets: Vec<&str> = json
            .split(['[', ']', ',', ' ', '\n'])
            .filter(|token| !token.is_empty() && !token.starts_with('"'))
            .collect();
        assert_eq!(targets.len(), 478);
        assert_eq!(MAINNET_BITS.len(), targets.len() + 1);
        assert_eq!(MAINNET_BITS[0], 0x1d00ffff);
        for (entry, decimal_target) in targets.iter().enumerate() {
            let electrum = Target::from_be_bytes(decimal(decimal_target));
            assert_eq!(
                target(MAINNET_BITS[entry + 1]),
                electrum,
                "period {}",
                entry + 1
            );
        }
    }

    /// The first block of segwit's period, the first of the table's last period, and block
    /// 800,000, as blockstream.info served them.
    #[test]
    fn real_headers_carry_their_periods_bits() {
        let headers = [
            (
                481_824,
                SEGWIT_BLOCK,
                fixture!("mainnet_header_481824_real.txt"),
            ),
            (
                963_648,
                "00000000000000000001769d9a327f5b455aa8a2dd407b1b63040d2a9f832d32",
                fixture!("mainnet_header_963648_real.txt"),
            ),
            (
                800_000,
                MAINNET_BLOCK,
                fixture!("mainnet_header_800000_real.txt"),
            ),
        ];
        for (height, hash, header) in headers {
            let header: Header = deserialize_hex(header).unwrap();
            assert_eq!(header.block_hash().to_string(), hash);
            assert_eq!(
                header.bits.to_consensus(),
                MAINNET_BITS[height / 2016],
                "height {height}"
            );
        }
    }
}
