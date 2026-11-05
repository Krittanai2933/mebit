//! Where each wallet's scan lives between calls. It lives in memory, and once the app names a folder, in one file
//! per wallet there too, so a restart asks only what can have changed. A file holds the frontier of each chain, the flags
//! and change token of each index asked, and when the last full pass ended. It holds no key, address or amount, all
//! of which the chain and the words give back. A file that cannot be read, names another wallet, comes from a
//! newer build or holds what no scan writes is ignored, which costs requests and never coins. The rules of a scan
//! are in `crate::scan`.

use std::collections::HashMap;
use std::fs;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};
use std::time::SystemTime;

use serde_json::{Value, json};

use crate::scan::{ChainScan, GAP, ScanStatus, Seen};

/// SHA-256 of the account's two chain xpubs, which is public and differs for other words, passphrase or network.
pub(crate) type WalletId = [u8; 32];

/// One wallet's scan as its file keeps it.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub(crate) struct WalletScan {
    /// The receive chain, then the change chain.
    pub(crate) chains: [ChainScan; 2],
    /// Unix seconds when the last full pass ended. None while discovery, or a full pass started since, has not
    /// ended, so the next sync goes on with it.
    pub(crate) full_at: Option<u64>,
}

/// One wallet in memory. Its lock is held through a whole sync, so a second call for the wallet waits for the
/// first and then shares its answer.
#[derive(Default)]
pub(crate) struct Entry {
    pub(crate) scan: WalletScan,
    /// What the last sync found, while a call may still share it.
    pub(crate) last: Option<Last>,
}

/// The answer of a wallet's last sync, which no file holds.
#[derive(Clone)]
pub(crate) struct Last {
    pub(crate) ended: SystemTime,
    /// The store's epoch when the sync started. A payment or a lost host since then makes it stale.
    pub(crate) epoch: u64,
    /// Each index's balance in sats as that sync found it, beside `seen` of the same chain.
    pub(crate) balances: [Vec<u64>; 2],
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
                Arc::new(Mutex::new(Entry { scan, last: None }))
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
const VERSION: u64 = 1;

/// Each index's flags as one number, its token beside it.
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
                json!([flags, seen.token])
            })
            .collect();
        json!({"frontier": chain.frontier, "seen": seen})
    };
    json!({
        "version": VERSION,
        "wallet": hex(id),
        "full_at": scan.full_at,
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
    let full_at = match &file["full_at"] {
        Value::Null => None,
        at => Some(at.as_u64()?),
    };
    let chain = |chain: &Value| -> Option<ChainScan> {
        let frontier = u32::try_from(chain["frontier"].as_u64()?).ok()?;
        let seen = chain["seen"]
            .as_array()?
            .iter()
            .map(|seen| {
                let [flags, token] = seen.as_array()?.as_slice() else {
                    return None;
                };
                let (flags, token) = (flags.as_u64()?, token.as_u64()?);
                (flags < 2 * FUNDED).then_some(Seen {
                    used: flags & USED != 0,
                    confirmed: flags & CONFIRMED != 0,
                    pending: flags & PENDING != 0,
                    funded: flags & FUNDED != 0,
                    token,
                })
            })
            .collect::<Option<Vec<_>>>()?;
        // A scan writes a window that BIP-32 normal indices reach, no index past it, and its proven index as used.
        let end = frontier.checked_add(GAP).filter(|end| *end <= 1 << 31)?;
        let proven_used = frontier
            .checked_sub(1)
            .is_none_or(|proven| seen.get(proven as usize).is_none_or(|seen| seen.used));
        (seen.len() <= end as usize && proven_used).then_some(ChainScan { frontier, seen })
    };
    let [receive, change] = file["chains"].as_array()?.as_slice() else {
        return None;
    };
    Some(WalletScan {
        chains: [chain(receive)?, chain(change)?],
        full_at,
    })
}

#[cfg(test)]
pub(crate) mod tests {
    use std::sync::atomic::AtomicUsize;

    use super::*;

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
        let seen = |used, confirmed, pending, funded, token| Seen {
            used,
            confirmed,
            pending,
            funded,
            token,
        };
        WalletScan {
            chains: [
                ChainScan {
                    frontier: 3,
                    seen: vec![
                        seen(true, true, false, false, 1),
                        seen(false, false, false, false, 2),
                        seen(true, true, true, true, u64::MAX),
                        seen(true, false, true, true, 0),
                    ],
                },
                ChainScan {
                    frontier: 0,
                    seen: Vec::new(),
                },
            ],
            full_at: Some(1_762_300_000),
        }
    }

    #[test]
    fn a_scan_reads_back_from_its_file_as_it_was_written() {
        assert_eq!(decode(&ID, &encode(&ID, &scan())), Some(scan()));
        let far_future = WalletScan {
            full_at: Some(u64::MAX),
            ..scan()
        };
        assert_eq!(
            decode(&ID, &encode(&ID, &far_future)),
            Some(far_future),
            "any time reads back, and a sync takes one it cannot place as a full pass due"
        );
        let started = WalletScan {
            full_at: None,
            ..scan()
        };
        assert_eq!(decode(&ID, &encode(&ID, &started)), Some(started));
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
            ("a newer version", edit("\"version\":1", "\"version\":2")),
            ("another wallet", encode(&[8; 32], &scan())),
            (
                "a frontier past u32",
                edit("\"frontier\":3", "\"frontier\":4294967296"),
            ),
            // No BIP-32 normal index reaches it, and a scan would walk toward it address by address.
            (
                "a frontier past the normal indices",
                edit("\"frontier\":3", "\"frontier\":2147483629"),
            ),
            (
                "more indices than the window past the frontier holds",
                edit(
                    ",{\"frontier\":0,\"seen\":[]}",
                    &format!(",{{\"frontier\":0,\"seen\":[{}]}}", ["[0,0]"; 21].join(",")),
                ),
            ),
            // Index 1 is unused in the file, so a frontier of 2 claims a proof of it, which no scan writes.
            (
                "a proven index the file calls unused",
                edit("\"frontier\":3", "\"frontier\":2"),
            ),
            ("an unknown flag", edit("[15,", "[16,")),
            ("a token that is not a number", edit("[0,2]", "[0,\"2\"]")),
            ("one chain", edit(",{\"frontier\":0,\"seen\":[]}", "")),
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
