#[cfg(test)]
use std::collections::BTreeSet;
use std::fmt;
use std::str::FromStr;

use clap::{Subcommand, ValueEnum};
use serde::{Deserialize, Serialize};

pub const CASE_COUNT: usize = 7_315;
pub const FUNDING_SATS: u64 = 50_000;
pub const CONFIRMED_SAFE7_P2WSH_FUNDING_SATS: u64 = 221_956;
pub const CONFIRMED_SAFE7_P2WSH_CASE_ID: &str = "multisig-p2wsh-1of1-a0-h0j0t1-s01-r0-12-c1-12";
pub const INDEPENDENT_SIGNING_CASE_ID: &str =
    "multisig-p2wsh-3of3-independent-a0-h1j1t1-s07-r1-74-c1-75";
pub const INDEPENDENT_SIGNING_SOURCE_CASE_ID: &str =
    "multisig-p2wsh-3of3-a0-h1j1t1-s07-r0-74-c1-74";
pub const INDEPENDENT_SIGNING_FUNDING_SATS: u64 = 44_586;
pub const INDEPENDENT_SIGNING_OUTPOINT: &str =
    "16ba3787cdcdef4dc85fa412cec081b73da174b4bb6efb8073b28314d0f7046c:1";
pub const PAYMENT_SATS: u64 = 5_000;
pub const FEE_RATE: u64 = 2;
pub const FEE_CAP: u64 = 5_000;
pub const RATE_CAP: u64 = 10;

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize, ValueEnum)]
#[serde(rename_all = "snake_case")]
pub enum Source {
    Hot,
    Jade,
    Trezor,
}

impl Source {
    pub fn prefix(self) -> char {
        match self {
            Self::Hot => 'h',
            Self::Jade => 'j',
            Self::Trezor => 't',
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
pub struct Slot {
    pub source: Source,
    pub number: u8,
}

impl fmt::Display for Slot {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}{}", self.source.prefix(), self.number)
    }
}

impl FromStr for Slot {
    type Err = String;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        let source = match s.as_bytes().first() {
            Some(b'h') => Source::Hot,
            Some(b'j') => Source::Jade,
            Some(b't') => Source::Trezor,
            _ => return Err("use a canonical signer slot h1..h7, j1..j7, or t1..t7".into()),
        };
        let number = s
            .get(1..)
            .unwrap_or_default()
            .parse::<u8>()
            .map_err(|_| "invalid slot")?;
        let slot = Self { source, number };
        if !(1..=7).contains(&number) || slot.to_string() != s {
            return Err("use a canonical signer slot h1..h7, j1..j7, or t1..t7".into());
        }
        Ok(slot)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Family {
    Bip44,
    Bip49,
    Bip84,
    Bip86,
    P2wsh,
}

impl Family {
    pub const ALL: [Self; 5] = [
        Self::Bip44,
        Self::Bip49,
        Self::Bip84,
        Self::Bip86,
        Self::P2wsh,
    ];

    pub fn name(self) -> &'static str {
        match self {
            Self::Bip44 => "bip44",
            Self::Bip49 => "bip49",
            Self::Bip84 => "bip84",
            Self::Bip86 => "bip86",
            Self::P2wsh => "p2wsh",
        }
    }

    pub fn path(self) -> bitcoin::bip32::DerivationPath {
        let purpose = match self {
            Self::Bip44 => 44,
            Self::Bip49 => 49,
            Self::Bip84 => 84,
            Self::Bip86 => 86,
            Self::P2wsh => 48,
        };
        let suffix = if self == Self::P2wsh { "/2'" } else { "" };
        format!("m/{purpose}'/1'/0'{suffix}")
            .parse()
            .expect("fixed account path")
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Location {
    pub branch: u32,
    pub index: u32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Case {
    pub id: String,
    pub wallet_class: String,
    pub family: Family,
    pub account: u32,
    pub threshold: usize,
    pub holders: Vec<Slot>,
    pub signing_subset: Vec<Slot>,
    pub receive: Location,
    pub change: Location,
}

impl Case {
    pub fn funding_target_sats(&self) -> u64 {
        if self.id == INDEPENDENT_SIGNING_CASE_ID {
            INDEPENDENT_SIGNING_FUNDING_SATS
        } else if self.id == CONFIRMED_SAFE7_P2WSH_CASE_ID {
            CONFIRMED_SAFE7_P2WSH_FUNDING_SATS
        } else {
            FUNDING_SATS
        }
    }

    pub fn expected_funding_outpoint(&self) -> Option<&'static str> {
        (self.id == INDEPENDENT_SIGNING_CASE_ID).then_some(INDEPENDENT_SIGNING_OUTPOINT)
    }
}

fn case(family: Family, holders: Vec<Slot>, threshold: usize, mask: u8, index: u32) -> Case {
    let signing_subset = holders
        .iter()
        .enumerate()
        .filter_map(|(i, slot)| (mask & (1 << i) != 0).then_some(*slot))
        .collect();
    let count = |source| holders.iter().filter(|s| s.source == source).count();
    let wallet_class = if family == Family::P2wsh {
        "multisig".to_owned()
    } else {
        match holders[0].source {
            Source::Hot => "hot",
            Source::Jade => "jade",
            Source::Trezor => "trezor",
        }
        .to_owned()
    };
    let subtype = if family == Family::P2wsh {
        format!("p2wsh-{threshold}of{}", holders.len())
    } else {
        family.name().to_owned()
    };
    let id = format!(
        "{wallet_class}-{subtype}-a0-h{}j{}t{}-s{mask:02x}-r0-{index}-c1-{index}",
        count(Source::Hot),
        count(Source::Jade),
        count(Source::Trezor)
    );
    Case {
        id,
        wallet_class,
        family,
        account: 0,
        threshold,
        holders,
        signing_subset,
        receive: Location { branch: 0, index },
        change: Location { branch: 1, index },
    }
}

pub fn matrix() -> Vec<Case> {
    manifest().to_vec()
}

pub fn manifest() -> &'static [Case] {
    static CASES: std::sync::OnceLock<Vec<Case>> = std::sync::OnceLock::new();
    CASES.get_or_init(generate_matrix)
}

/// One live supplemental protocol check. It is deliberately excluded from
/// `manifest()` so it cannot change the required 7,315-case acceptance count.
pub fn supplemental_cases() -> &'static [Case] {
    static CASES: std::sync::OnceLock<Vec<Case>> = std::sync::OnceLock::new();
    CASES.get_or_init(|| {
        vec![Case {
            id: INDEPENDENT_SIGNING_CASE_ID.to_owned(),
            wallet_class: "multisig".to_owned(),
            family: Family::P2wsh,
            account: 0,
            threshold: 3,
            holders: vec![
                Slot {
                    source: Source::Hot,
                    number: 1,
                },
                Slot {
                    source: Source::Jade,
                    number: 1,
                },
                Slot {
                    source: Source::Trezor,
                    number: 1,
                },
            ],
            signing_subset: vec![
                Slot {
                    source: Source::Hot,
                    number: 1,
                },
                Slot {
                    source: Source::Jade,
                    number: 1,
                },
                Slot {
                    source: Source::Trezor,
                    number: 1,
                },
            ],
            receive: Location {
                branch: 1,
                index: 74,
            },
            change: Location {
                branch: 1,
                index: 75,
            },
        }]
    })
}

fn generate_matrix() -> Vec<Case> {
    let mut rows = Vec::with_capacity(CASE_COUNT);
    for source in [Source::Hot, Source::Jade, Source::Trezor] {
        for family in Family::ALL.into_iter().filter(|f| *f != Family::P2wsh) {
            rows.push(case(
                family,
                vec![Slot { source, number: 1 }],
                1,
                1,
                rows.len() as u32,
            ));
        }
    }
    for n in 1..=7u8 {
        for hot in 0..=n {
            for jade in 0..=n - hot {
                let trezor = n - hot - jade;
                let holders: Vec<_> = [
                    (Source::Hot, hot),
                    (Source::Jade, jade),
                    (Source::Trezor, trezor),
                ]
                .into_iter()
                .flat_map(|(source, count)| (1..=count).map(move |number| Slot { source, number }))
                .collect();
                for threshold in 1..=usize::from(n) {
                    for mask in 1..(1u16 << n) {
                        if mask.count_ones() as usize == threshold {
                            rows.push(case(
                                Family::P2wsh,
                                holders.clone(),
                                threshold,
                                mask as u8,
                                rows.len() as u32,
                            ));
                        }
                    }
                }
            }
        }
    }
    rows
}

pub fn find_case(id: &str) -> Result<Case, String> {
    manifest()
        .iter()
        .find(|c| c.id == id)
        .or_else(|| supplemental_cases().iter().find(|c| c.id == id))
        .cloned()
        .ok_or_else(|| format!("unknown case {id}; use e2e matrix"))
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize, ValueEnum)]
#[serde(rename_all = "snake_case")]
pub enum SigningMode {
    #[default]
    Sequential,
    Independent,
}

#[derive(Debug, Subcommand)]
pub enum Command {
    /// Write all 7,315 required cases without opening BLE or HTTP.
    Matrix,
    /// Derive scripts independently, sign, and finalize without BLE or HTTP.
    Offline,
    /// Enroll one independent test key holder. Software secrets stay outside evidence.
    Enroll {
        #[arg(long)]
        slot: Slot,
        /// Declare the Jade test registration slot manually verified empty.
        #[arg(long)]
        jade_slot_empty: bool,
    },
    /// Export only a Trezor's public test-account identity for a remote coordinator.
    ExportEnrollment {
        #[arg(long)]
        slot: Slot,
        #[arg(long)]
        out: std::path::PathBuf,
    },
    /// Import a remote Trezor's public identity without importing transport credentials.
    ImportEnrollment {
        #[arg(long)]
        slot: Slot,
        #[arg(long)]
        file: std::path::PathBuf,
    },
    /// Verify receiving and change addresses, then print funding instructions.
    Prepare {
        #[arg(long)]
        case: String,
        /// Use a file-based remote worker for this enrolled Trezor slot.
        #[arg(long)]
        remote_slot: Option<Slot>,
    },
    /// Import the pending remote device response and continue the saved case.
    ImportRemote {
        #[arg(long)]
        case: String,
        #[arg(long)]
        response: std::path::PathBuf,
    },
    /// Spend a confirmed funding outpoint. Broadcast requires an explicit flag.
    Run {
        #[arg(long)]
        case: String,
        #[arg(long)]
        funding_outpoint: bitcoin::OutPoint,
        #[arg(long)]
        broadcast: bool,
        /// Ask each signer to sign the same saved unsigned PSBT, then merge
        /// only the verified signatures. Required for the supplemental case.
        #[arg(long, value_enum)]
        signing_mode: Option<SigningMode>,
    },
    /// Reconcile saved transaction bytes and chain state before continuing.
    Resume {
        #[arg(long)]
        case: String,
        /// Authorize broadcast of the saved identical bytes if not already authorized.
        #[arg(long)]
        broadcast: bool,
    },
    /// Report offline readiness and each required live row separately.
    Report,
    #[command(hide = true)]
    Device {
        #[arg(long)]
        request: std::path::PathBuf,
        #[arg(long)]
        response: std::path::PathBuf,
    },
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn matrix_covers_compositions_thresholds_and_exact_subsets() {
        let rows = matrix();
        assert_eq!(rows.len(), CASE_COUNT);
        assert_eq!(
            rows.iter().map(|c| &c.id).collect::<BTreeSet<_>>().len(),
            CASE_COUNT
        );
        assert_eq!(
            rows.iter()
                .map(|c| c.receive.index)
                .collect::<BTreeSet<_>>()
                .len(),
            CASE_COUNT
        );
        let mut expected = BTreeSet::new();
        for n in 1..=7 {
            for h in 0..=n {
                for j in 0..=n - h {
                    for mask in 1u32..(1 << n) {
                        expected.insert((n, h, j, n - h - j, mask.count_ones() as usize, mask));
                    }
                }
            }
        }
        let actual: BTreeSet<_> = rows
            .iter()
            .filter(|c| c.family == Family::P2wsh)
            .map(|c| {
                let count = |s| c.holders.iter().filter(|k| k.source == s).count();
                let mask = c
                    .holders
                    .iter()
                    .enumerate()
                    .filter(|(_, s)| c.signing_subset.contains(s))
                    .fold(0, |acc, (i, _)| acc | (1u32 << i));
                (
                    c.holders.len(),
                    count(Source::Hot),
                    count(Source::Jade),
                    count(Source::Trezor),
                    c.threshold,
                    mask,
                )
            })
            .collect();
        assert_eq!(actual, expected);
        assert_eq!(matrix(), rows);
    }

    #[test]
    fn only_the_confirmed_safe7_case_has_a_nondefault_funding_target() {
        let rows = matrix();
        let special_cases = rows
            .iter()
            .filter(|case| case.funding_target_sats() == CONFIRMED_SAFE7_P2WSH_FUNDING_SATS)
            .collect::<Vec<_>>();

        assert_eq!(special_cases.len(), 1);
        assert_eq!(special_cases[0].id, CONFIRMED_SAFE7_P2WSH_CASE_ID);
        assert!(rows.iter().all(|case| {
            case.funding_target_sats()
                == if case.id == CONFIRMED_SAFE7_P2WSH_CASE_ID {
                    CONFIRMED_SAFE7_P2WSH_FUNDING_SATS
                } else {
                    FUNDING_SATS
                }
        }));
    }

    #[test]
    fn supplemental_independent_signing_case_is_pinned_but_outside_required_matrix() {
        let case = find_case(INDEPENDENT_SIGNING_CASE_ID).unwrap();
        assert_eq!(manifest().len(), CASE_COUNT);
        assert!(manifest().iter().all(|row| row.id != case.id));
        assert_eq!(supplemental_cases(), &[case.clone()]);
        assert_eq!(case.threshold, 3);
        assert_eq!(case.holders.len(), 3);
        assert_eq!(case.signing_subset, case.holders);
        assert_eq!(case.receive.branch, 1);
        assert_eq!(case.receive.index, 74);
        assert_eq!(case.change.branch, 1);
        assert_eq!(case.change.index, 75);
        assert_eq!(case.funding_target_sats(), INDEPENDENT_SIGNING_FUNDING_SATS);
        assert_eq!(
            case.expected_funding_outpoint(),
            Some(INDEPENDENT_SIGNING_OUTPOINT)
        );
        assert_eq!(
            INDEPENDENT_SIGNING_FUNDING_SATS - PAYMENT_SATS - 438,
            39_148
        );
    }
}
