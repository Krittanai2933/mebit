//! Where each wallet's scan lives between calls. It lives in memory, and once the app names a folder, in one file
//! per wallet there too, so a restart asks only what can have changed. A file holds, per chain, the index whose
//! payment proved its frontier and the flags of each index asked, and when the last full pass started and whether it
//! ended. It holds no key, address, amount or txid, so nothing in it points at a payment, and the chain and the words
//! give each of them back. A file that cannot be read, names another wallet, comes from another build or holds what
//! no scan writes is ignored, which costs requests and never coins. A file that parses is still outside data, so its
//! indices back nothing until a source proves them used again. The rules of a scan are in `crate::scan`.

use std::collections::HashMap;
use std::fs;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};
use std::time::SystemTime;

use serde_json::{Value, json};

use crate::scan::{ChainScan, GAP, Proof, ScanStatus, Seen};

/// SHA-256 of the account's two chain xpubs, which is public and differs for other words, passphrase or network.
pub(crate) type WalletId = [u8; 32];

/// One wallet's scan as its file keeps it.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub(crate) struct WalletScan {
    /// The receive chain, then the change chain.
    pub(crate) chains: [ChainScan; 2],
    /// The last full pass, which asks every index again from 0. None before the first sync, whose discovery is
    /// the first full pass, and after a host lied, so the next sync starts one.
    pub(crate) full: Option<FullPass>,
}

/// When the last full pass started, in Unix seconds, and whether it ended.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum FullPass {
    /// It has not ended, so the next sync goes on with it.
    Running(u64),
    /// The next one is due a period after this one started, so a pass a throttle stretched out does not push it back.
    Ended(u64),
}

/// One wallet in memory. Its lock is held through a whole sync, so a second call for the wallet waits for the
/// first and then shares its answer. Nothing here but `scan` goes to a file.
#[derive(Default)]
pub(crate) struct Entry {
    pub(crate) scan: WalletScan,
    /// Each index's balance in sats as the round of syncs in progress found it, beside `seen` of the same chain.
    pub(crate) balances: [Vec<u64>; 2],
    /// How far the routine part of each chain got in the round in progress, which runs from a sync that starts
    /// one to the sync that ends Complete or Unverified: every index below it that the routine part asks was
    /// asked in the round. None when no round is in progress, so the next sync starts one.
    pub(crate) round: Option<[usize; 2]>,
    /// What the last sync found, while a call may still share it.
    pub(crate) last: Option<Last>,
}

/// The answer of a wallet's last sync, which no file holds.
#[derive(Clone)]
pub(crate) struct Last {
    pub(crate) ended: SystemTime,
    /// The store's epoch when the sync started. A payment or a lost host since then makes it stale.
    pub(crate) epoch: u64,
    pub(crate) status: ScanStatus,
}

/// Every wallet's scan, with the folder its files go to.
#[derive(Default)]
pub(crate) struct ScanStore {
    wallets: Mutex<Wallets>,
    /// How many payments went out and hosts were lost. A picture taken under an older count may miss them.
    epoch: AtomicU64,
}

#[derive(Default)]
struct Wallets {
    dir: Option<PathBuf>,
    open: HashMap<WalletId, Arc<Mutex<Entry>>>,
}

impl ScanStore {
    fn wallets(&self) -> MutexGuard<'_, Wallets> {
        self.wallets.lock().unwrap_or_else(PoisonError::into_inner)
    }

    /// Keeps each wallet's file in `dir` from now on. A wallet already open is written there at its next sync.
    pub(crate) fn use_dir(&self, dir: PathBuf) {
        self.wallets().dir = Some(dir);
    }

    /// The wallet `id`, from memory, else from its file, else as a wallet never scanned.
    pub(crate) fn wallet(&self, id: &WalletId) -> Arc<Mutex<Entry>> {
        let mut wallets = self.wallets();
        let dir = wallets.dir.clone();
        wallets
            .open
            .entry(*id)
            .or_insert_with(|| {
                let scan = dir
                    .and_then(|dir| fs::read_to_string(dir.join(file_name(id))).ok())
                    .and_then(|text| decode(id, &text))
                    .unwrap_or_default();
                Arc::new(Mutex::new(Entry {
                    scan,
                    ..Entry::default()
                }))
            })
            .clone()
    }

    pub(crate) fn epoch(&self) -> u64 {
        self.epoch.load(Ordering::SeqCst)
    }

    /// A payment went out or the host in use was lost, so no picture taken before now answers another call.
    /// Each wallet keeps its scan, and its next call is a routine sync.
    pub(crate) fn mark_stale(&self) {
        self.epoch.fetch_add(1, Ordering::SeqCst);
    }

    /// Writes `scan` to the file of `id`, when a folder is set and `entry` is still the wallet open, so a sync
    /// that ends after `forget` leaves no file behind. A failed write leaves the last file, which is only older.
    pub(crate) fn save(&self, id: &WalletId, entry: &Arc<Mutex<Entry>>, scan: &WalletScan) {
        let wallets = self.wallets();
        let (Some(dir), Some(open)) = (&wallets.dir, wallets.open.get(id)) else {
            return;
        };
        if !Arc::ptr_eq(open, entry) {
            return;
        }
        // A temp file renamed into place, so a crash mid-write leaves the old file or the new one, never half.
        let path = dir.join(file_name(id));
        let temp = path.with_extension("json.tmp");
        let _ = fs::create_dir_all(dir)
            .and_then(|()| fs::write(&temp, encode(id, scan)))
            .and_then(|()| fs::rename(&temp, &path));
    }

    /// Every wallet's scan gone, from memory and from the folder.
    pub(crate) fn forget(&self) {
        let mut wallets = self.wallets();
        wallets.open.clear();
        let Some(Ok(files)) = wallets.dir.as_ref().map(fs::read_dir) else {
            return;
        };
        for file in files.flatten() {
            let name = file.file_name();
            let name = name.to_string_lossy();
            if name.ends_with(".json") || name.ends_with(".json.tmp") {
                let _ = fs::remove_file(file.path());
            }
        }
    }
}

fn file_name(id: &WalletId) -> String {
    format!("{}.json", hex(id))
}

fn hex(id: &WalletId) -> String {
    id.iter().map(|byte| format!("{byte:02x}")).collect()
}

/// The file's format. A file of any other version is a fresh start.
const VERSION: u64 = 2;

/// Each index's flags as one number.
const USED: u64 = 1;
const CONFIRMED: u64 = 2;
const PENDING: u64 = 4;
const FUNDED: u64 = 8;

fn encode(id: &WalletId, scan: &WalletScan) -> String {
    let chain = |chain: &ChainScan| {
        let seen: Vec<Value> = chain
            .seen
            .iter()
            .map(|seen| {
                let flags = [
                    (seen.used, USED),
                    (seen.confirmed, CONFIRMED),
                    (seen.pending, PENDING),
                    (seen.funded, FUNDED),
                ];
                let flags: u64 = flags.iter().filter(|(on, _)| *on).map(|(_, bit)| bit).sum();
                json!(flags)
            })
            .collect();
        json!({"proof": chain.proof.map(|proof| proof.index()), "seen": seen})
    };
    let full = scan.full.map(|full| match full {
        FullPass::Running(started) => json!({"started": started, "ended": false}),
        FullPass::Ended(started) => json!({"started": started, "ended": true}),
    });
    json!({
        "version": VERSION,
        "wallet": hex(id),
        "full": full,
        "chains": [chain(&scan.chains[0]), chain(&scan.chains[1])],
    })
    .to_string()
}

/// The scan in `text` when it is this version's file of wallet `id`, else None.
fn decode(id: &WalletId, text: &str) -> Option<WalletScan> {
    let file: Value = serde_json::from_str(text).ok()?;
    if file["version"].as_u64() != Some(VERSION) || file["wallet"].as_str() != Some(&hex(id)) {
        return None;
    }
    let full = match &file["full"] {
        Value::Null => None,
        full => {
            let started = full["started"].as_u64()?;
            Some(match full["ended"].as_bool()? {
                true => FullPass::Ended(started),
                false => FullPass::Running(started),
            })
        }
    };
    let chain = |chain: &Value| -> Option<ChainScan> {
        let proof = match &chain["proof"] {
            Value::Null => None,
            index => Some(u32::try_from(index.as_u64()?).ok()?),
        };
        let seen = chain["seen"]
            .as_array()?
            .iter()
            .map(|seen| {
                let flags = seen.as_u64().filter(|flags| *flags < 2 * FUNDED)?;
                Some(Seen {
                    used: flags & USED != 0,
                    confirmed: flags & CONFIRMED != 0,
                    pending: flags & PENDING != 0,
                    funded: flags & FUNDED != 0,
                })
            })
            .collect::<Option<Vec<_>>>()?;
        // A scan writes a window that BIP-32 normal indices reach, and no index past it.
        let frontier = proof.map_or(Some(0), |index| index.checked_add(1))?;
        let end = frontier.checked_add(GAP).filter(|end| *end <= 1 << 31)?;
        (seen.len() <= end as usize).then_some(ChainScan {
            proof: proof.map(Proof::Stored),
            seen,
        })
    };
    let [receive, change] = file["chains"].as_array()?.as_slice() else {
        return None;
    };
    Some(WalletScan {
        chains: [chain(receive)?, chain(change)?],
        full,
    })
}

#[cfg(test)]
pub(crate) mod tests {
    use std::sync::atomic::AtomicUsize;

    use super::*;
    use crate::proof::ProvenUse;

    /// A folder of its own under the system's temp folder, removed when dropped.
    pub(crate) struct Folder(pub(crate) PathBuf);

    impl Folder {
        pub(crate) fn new(name: &str) -> Self {
            static COUNT: AtomicUsize = AtomicUsize::new(0);
            let n = COUNT.fetch_add(1, Ordering::SeqCst);
            let dir =
                std::env::temp_dir().join(format!("mebit-scan-{name}-{}-{n}", std::process::id()));
            let _ = fs::remove_dir_all(&dir);
            Folder(dir)
        }

        pub(crate) fn files(&self) -> Vec<String> {
            let mut names: Vec<String> = fs::read_dir(&self.0)
                .map(|files| {
                    files
                        .flatten()
                        .map(|f| f.file_name().to_string_lossy().into_owned())
                        .collect()
                })
                .unwrap_or_default();
            names.sort();
            names
        }
    }

    impl Drop for Folder {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    const ID: WalletId = [7; 32];

    fn scan() -> WalletScan {
        let seen = |used, confirmed, pending, funded| Seen {
            used,
            confirmed,
            pending,
            funded,
        };
        WalletScan {
            chains: [
                ChainScan {
                    proof: Some(Proof::Stored(2)),
                    seen: vec![
                        seen(true, true, false, false),
                        seen(false, false, false, false),
                        seen(true, true, true, true),
                        seen(true, false, true, true),
                    ],
                },
                ChainScan::default(),
            ],
            full: Some(FullPass::Ended(1_762_300_000)),
        }
    }

    #[test]
    fn a_scan_reads_back_from_its_file_as_it_was_written() {
        assert_eq!(decode(&ID, &encode(&ID, &scan())), Some(scan()));
        let far_future = WalletScan {
            full: Some(FullPass::Ended(u64::MAX)),
            ..scan()
        };
        assert_eq!(
            decode(&ID, &encode(&ID, &far_future)),
            Some(far_future),
            "any time reads back, and a sync takes one it cannot place as a full pass due"
        );
        for full in [Some(FullPass::Running(1_762_300_000)), None] {
            let scan = WalletScan { full, ..scan() };
            assert_eq!(decode(&ID, &encode(&ID, &scan)), Some(scan));
        }
    }

    // R1 of the 5 Nov interrogate. A payment a source showed is written as its index alone and reads back as that
    // index, so after a restart it backs nothing until a source proves the index used again.
    #[test]
    fn a_payment_a_source_showed_reads_back_as_its_index_alone() {
        let mut scan = scan();
        scan.chains[1].proof = Some(Proof::Shown(ProvenUse::modelled(7)));
        let read = decode(&ID, &encode(&ID, &scan)).unwrap();
        assert_eq!(read.chains[1].proof, Some(Proof::Stored(7)));
        assert_eq!(read.chains[1].frontier(), 0);
    }

    #[test]
    fn a_file_that_cannot_be_read_names_another_wallet_or_comes_from_a_newer_build_is_ignored() {
        let good = encode(&ID, &scan());
        let edit = |from: &str, to: &str| {
            assert!(good.contains(from), "{from}");
            good.replacen(from, to, 1)
        };
        for (case, text) in [
            ("empty", String::new()),
            ("cut short", good[..good.len() / 2].to_string()),
            ("not an object", "[1,2]".into()),
            ("a newer version", edit("\"version\":2", "\"version\":3")),
            ("another wallet", encode(&[8; 32], &scan())),
            (
                "a proven index past u32",
                edit("\"proof\":2", "\"proof\":4294967296"),
            ),
            // No BIP-32 normal index reaches it, and a scan would walk toward it address by address.
            (
                "a proven index past the normal indices",
                edit("\"proof\":2", "\"proof\":2147483628"),
            ),
            (
                "a proof that is no index",
                edit("\"proof\":2", "\"proof\":-1"),
            ),
            (
                "a proof that names its payment",
                edit("\"proof\":2", "\"proof\":{\"index\":2,\"txid\":\"c5c5\"}"),
            ),
            (
                "more indices than the window past the frontier holds",
                edit(
                    ",{\"proof\":null,\"seen\":[]}",
                    &format!(",{{\"proof\":null,\"seen\":[{}]}}", ["0"; 21].join(",")),
                ),
            ),
            ("an unknown flag", edit("[3,0,15,", "[3,0,16,")),
            (
                "flags that are not a number",
                edit("[3,0,15,", "[3,\"0\",15,"),
            ),
            (
                "flags with a token beside them",
                edit("[3,0,15,", "[[3,1],0,15,"),
            ),
            ("one chain", edit(",{\"proof\":null,\"seen\":[]}", "")),
        ] {
            assert_eq!(decode(&ID, &text), None, "{case}: {text}");
        }
    }

    #[test]
    fn a_saved_scan_is_one_file_named_by_the_wallet_that_a_new_store_reads_back() {
        let folder = Folder::new("save");
        let store = ScanStore::default();
        store.use_dir(folder.0.clone());
        let entry = store.wallet(&ID);
        store.save(&ID, &entry, &scan());
        assert_eq!(folder.files(), [format!("{}.json", hex(&ID))]);
        let restarted = ScanStore::default();
        restarted.use_dir(folder.0.clone());
        assert_eq!(restarted.wallet(&ID).lock().unwrap().scan, scan());
        let without_folder = ScanStore::default();
        assert_eq!(
            without_folder.wallet(&ID).lock().unwrap().scan,
            WalletScan::default()
        );
    }

    #[test]
    fn forget_removes_every_scan_file_and_a_sync_that_ends_after_it_writes_nothing() {
        let folder = Folder::new("forget");
        let store = ScanStore::default();
        store.use_dir(folder.0.clone());
        let entry = store.wallet(&ID);
        store.save(&ID, &entry, &scan());
        let other = store.wallet(&[9; 32]);
        store.save(&[9; 32], &other, &scan());
        assert_eq!(folder.files().len(), 2);
        store.forget();
        assert!(folder.files().is_empty(), "{:?}", folder.files());
        store.save(&ID, &entry, &scan());
        assert!(folder.files().is_empty(), "{:?}", folder.files());
        assert_eq!(
            store.wallet(&ID).lock().unwrap().scan,
            WalletScan::default()
        );
    }
}
