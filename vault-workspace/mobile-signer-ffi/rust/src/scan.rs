//! Discovery's stop rule, as a pure reducer. A chain is asked about up to `GAP` indices past its frontier, and
//! the frontier moves past an index only once `prove_use` proved that index paid in a block with valid work, so
//! no source can push the scan with claims alone. The address the wallet hands out stays below `frontier + GAP`,
//! where a restore with a gap of `GAP` over the real chain finds it.
//!
//! Why a scan ends without a cap. Let H be the highest index of a chain that was really paid in a block with
//! valid work. The frontier rises only to one past a proven index, so it never passes H + 1. At one frontier a
//! scan asks once and proves once, then the frontier rises or the scan ends. So one source's scan of a chain
//! asks at most H + 1 + GAP summaries and makes at most one proof per window it moves.

use std::ops::Range;

use crate::chain::Summary;
use crate::proof::{NotProven, ProvenUse};

/// BIP-44's gap limit, for scanning and for issuing, on both chains.
pub(crate) const GAP: u32 = 20;

/// One chain's scan. `frontier` is one past the highest index proven used, 0 before any proof. `seen[i]` is what
/// the source said about index i, for every index asked so far, never past `frontier + GAP`.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub(crate) struct ChainScan {
    pub(crate) frontier: u32,
    pub(crate) seen: Vec<Summary>,
}

/// What a scan does next.
#[derive(Debug, PartialEq, Eq)]
pub(crate) enum Step {
    /// Ask the source about these indices, the rest of the window.
    Ask(Range<u32>),
    /// Prove this index used, the highest in the window that the source says has confirmed history.
    Prove(u32),
    Done(ScanStatus),
}

/// The answer to a step.
pub(crate) enum Event {
    /// The source's summary of each index asked, in order.
    Asked(Vec<Summary>),
    Proven(ProvenUse),
    NotProven(NotProven),
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum ScanStatus {
    /// The window past the frontier was asked and nothing in it claims confirmed use, so the scan reached as far
    /// as a restore with a gap of `GAP` reaches.
    Complete,
    /// The scan stopped short, because a proof's read got no answer or a claim could not be proven yet.
    /// Asking later may finish it. The text says where.
    Continuing(String),
    /// The source lied in a proof, or called a proven index unused. The text says what it claimed.
    Unverified(String),
}

impl ChainScan {
    /// The step that follows from what is known.
    pub(crate) fn next(&self) -> Step {
        let end = self.frontier + GAP;
        // Proven facts only grow. A source that calls the proven index unused contradicts a block.
        if let Some(proven) = self.frontier.checked_sub(1)
            && self
                .seen
                .get(proven as usize)
                .is_some_and(|seen| !seen.used)
        {
            return Step::Done(ScanStatus::Unverified(format!(
                "index {proven} was proven used, and the source calls it unused"
            )));
        }
        let asked = self.seen.len() as u32;
        if asked < end {
            return Step::Ask(asked..end);
        }
        match (self.frontier..end)
            .rev()
            .find(|&i| self.seen[i as usize].confirmed)
        {
            Some(index) => Step::Prove(index),
            None => Step::Done(ScanStatus::Complete),
        }
    }

    /// The index the wallet hands out on this chain, one past the last index the source calls used but never
    /// `frontier + GAP` or past it. When claims fill the window that is its last index, which may be used already.
    pub(crate) fn issued(&self) -> u32 {
        let after_last_use = self
            .seen
            .iter()
            .rposition(|seen| seen.used)
            .map_or(0, |last| last as u32 + 1);
        after_last_use.min(self.frontier + GAP - 1)
    }
}

/// The one transition, `event` being the answer to `at`. Every call raises the frontier, or moves to a later
/// step at the same frontier (Ask, then Prove, then Done).
pub(crate) fn step(scan: &mut ChainScan, at: Step, event: Event) -> Step {
    let before = standing(scan, &at);
    let next = match (at, event) {
        (Step::Ask(asked), Event::Asked(summaries)) => {
            debug_assert_eq!(summaries.len(), asked.len(), "one summary per index asked");
            scan.seen.extend(summaries);
            scan.next()
        }
        (Step::Prove(index), Event::Proven(proven)) => {
            debug_assert_eq!(proven.index(), index, "the proof is of the index asked");
            scan.frontier = proven.index() + 1;
            scan.next()
        }
        (Step::Prove(index), Event::NotProven(NotProven::Lie(why))) => {
            Step::Done(ScanStatus::Unverified(format!("index {index}: {why}")))
        }
        (Step::Prove(index), Event::NotProven(NotProven::NoConfirmedPayment)) => {
            Step::Done(ScanStatus::Continuing(format!(
                "index {index} has confirmed history by the source's count, and its history lists no confirmed payment to it"
            )))
        }
        (Step::Prove(index), Event::NotProven(NotProven::Unreachable(why))) => Step::Done(
            ScanStatus::Continuing(format!("index {index} could not be proven: {why}")),
        ),
        (at, _) => unreachable!("{at:?} got the answer to another step"),
    };
    debug_assert!(
        standing(scan, &next) > before,
        "a step must raise (frontier, phase), it went from {before:?} to {:?}",
        standing(scan, &next)
    );
    next
}

/// Where a scan stands, by its frontier and then by how far along the window it is.
fn standing(scan: &ChainScan, at: &Step) -> (u32, u8) {
    let phase = match at {
        Step::Ask(_) => 0,
        Step::Prove(_) => 1,
        Step::Done(_) => 2,
    };
    (scan.frontier, phase)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// xorshift64, so every random run is a seed that can be run again.
    struct Rng(u64);

    impl Rng {
        fn below(&mut self, n: u64) -> u64 {
            self.0 ^= self.0 << 13;
            self.0 ^= self.0 >> 7;
            self.0 ^= self.0 << 17;
            self.0 % n
        }

        fn chance(&mut self, percent: u64) -> bool {
            self.below(100) < percent
        }
    }

    /// Where a scan stands, ordered as the invariant says it must rise, by the frontier and then Ask, Prove, Done.
    fn rank(scan: &ChainScan, at: &Step) -> (u32, u8) {
        let phase = match at {
            Step::Ask(_) => 0,
            Step::Prove(_) => 1,
            Step::Done(_) => 2,
        };
        (scan.frontier, phase)
    }

    fn claim(used: bool, confirmed: bool) -> Summary {
        Summary {
            used,
            confirmed,
            balance: 0,
        }
    }

    fn lie() -> Event {
        Event::NotProven(NotProven::Lie("forged".into()))
    }

    /// A scan of `scan` to its end against `answer`, checking at every step that the scan's standing rose, with
    /// the status it ended in, the summaries it asked and the proofs it tried.
    fn run(scan: &mut ChainScan, mut answer: impl FnMut(&Step) -> Event) -> (ScanStatus, u32, u32) {
        let (mut asked, mut proofs) = (0, 0);
        let mut at = scan.next();
        loop {
            match &at {
                Step::Ask(range) => asked += range.len() as u32,
                Step::Prove(_) => proofs += 1,
                Step::Done(status) => return (status.clone(), asked, proofs),
            }
            let before = rank(scan, &at);
            let event = answer(&at);
            at = step(scan, at, event);
            let after = rank(scan, &at);
            assert!(after > before, "the scan went from {before:?} to {after:?}");
        }
    }

    /// A source that tells the truth about `paid`, the indices really paid in a block.
    fn honest(paid: &[u32]) -> impl FnMut(&Step) -> Event + '_ {
        move |at| match at {
            Step::Ask(range) => Event::Asked(
                range
                    .clone()
                    .map(|i| claim(paid.contains(&i), paid.contains(&i)))
                    .collect(),
            ),
            Step::Prove(i) if paid.contains(i) => Event::Proven(ProvenUse::modelled(*i)),
            Step::Prove(_) => Event::NotProven(NotProven::NoConfirmedPayment),
            Step::Done(_) => unreachable!("a finished scan asks nothing"),
        }
    }

    /// Where a plain BIP-44 restore stops. It asks indices in order until `GAP` in a row are unpaid, so it reaches
    /// every index below the returned one.
    fn restore_reach(paid: &[u32]) -> u32 {
        let mut reach = GAP;
        let mut i = 0;
        while i < reach {
            if paid.contains(&i) {
                reach = i + 1 + GAP;
            }
            i += 1;
        }
        reach
    }

    /// A source whose answers are drawn from `rng`. Each summary may tell the truth about `paid`, invent a use or
    /// omit one, and each proof may be lost or refused. It never proves an index that was not paid, which is
    /// what proof of work buys.
    fn random_source<'a>(rng: &'a mut Rng, paid: &'a [u32]) -> impl FnMut(&Step) -> Event + 'a {
        move |at| match at {
            Step::Ask(range) => Event::Asked(
                range
                    .clone()
                    .map(|i| {
                        let used = paid.contains(&i) != rng.chance(15);
                        claim(used, used && rng.chance(80))
                    })
                    .collect(),
            ),
            Step::Prove(i) => match rng.below(10) {
                0 => Event::NotProven(NotProven::Unreachable("esplora get: timed out".into())),
                1 => Event::NotProven(NotProven::NoConfirmedPayment),
                _ if paid.contains(i) => Event::Proven(ProvenUse::modelled(*i)),
                _ => lie(),
            },
            Step::Done(_) => unreachable!("a finished scan asks nothing"),
        }
    }

    /// The highest paid index H and the paid indices of a random chain, each index below H paid by chance.
    fn random_chain(rng: &mut Rng) -> (u32, Vec<u32>) {
        let h = rng.below(150) as u32;
        let mut paid: Vec<u32> = (0..h).filter(|_| rng.chance(25)).collect();
        paid.push(h);
        (h, paid)
    }

    // The termination argument as a property. Whatever a source answers, every step raises (frontier, phase),
    // and the frontier never passes H + 1, so a scan ends within 2 (H + 2) steps having asked H + 1 + GAP.
    #[test]
    fn every_step_raises_the_frontier_or_the_phase_whatever_the_source_answers() {
        for seed in 1..=3_000 {
            let mut rng = Rng(seed);
            let (h, paid) = random_chain(&mut rng);
            let mut scan = ChainScan::default();
            let mut steps = 0;
            let mut at = scan.next();
            while !matches!(at, Step::Done(_)) {
                let before = rank(&scan, &at);
                let event = random_source(&mut rng, &paid)(&at);
                at = step(&mut scan, at, event);
                let after = rank(&scan, &at);
                assert!(
                    after > before,
                    "seed {seed}: went from {before:?} to {after:?}"
                );
                assert!(
                    scan.frontier <= h + 1,
                    "seed {seed}: frontier {}",
                    scan.frontier
                );
                steps += 1;
            }
            assert!(steps <= 2 * (h + 2), "seed {seed}: {steps} steps for H {h}");
            assert!(scan.seen.len() as u32 <= h + 1 + GAP, "seed {seed}");
        }
    }

    #[test]
    fn a_source_that_claims_every_index_used_and_cannot_prove_one_ends_unverified_after_one_window()
    {
        let lies = [
            lie(),
            Event::NotProven(NotProven::Lie("the raw transaction is another one".into())),
        ];
        for lie in lies {
            let mut lie = Some(lie);
            let mut scan = ChainScan::default();
            let (status, asked, proofs) = run(&mut scan, |at| match at {
                Step::Ask(range) => {
                    Event::Asked(range.clone().map(|_| claim(true, true)).collect())
                }
                Step::Prove(_) => lie.take().expect("one proof attempt"),
                Step::Done(_) => unreachable!(),
            });
            assert!(matches!(status, ScanStatus::Unverified(_)), "{status:?}");
            assert_eq!((scan.frontier, asked, proofs), (0, GAP, 1));
        }
    }

    // Five times the old per-chain limit.
    #[test]
    fn an_honest_chain_used_through_index_4999_completes_one_proof_per_window() {
        let h = 4_999;
        let paid: Vec<u32> = (0..=h).collect();
        let mut scan = ChainScan::default();
        let (status, asked, proofs) = run(&mut scan, honest(&paid));
        assert_eq!(status, ScanStatus::Complete);
        assert_eq!(scan.frontier, h + 1);
        assert_eq!(asked, h + 1 + GAP);
        assert_eq!(proofs, (h + 1).div_ceil(GAP));
        assert_eq!(scan.issued(), h + 1);
    }

    #[test]
    fn a_fresh_chain_asks_one_window_and_proves_nothing() {
        let mut scan = ChainScan::default();
        let (status, asked, proofs) = run(&mut scan, honest(&[]));
        assert_eq!((status, asked, proofs), (ScanStatus::Complete, GAP, 0));
        assert_eq!(scan.issued(), 0);
    }

    #[test]
    fn a_claim_in_the_mempool_counts_for_reuse_and_never_moves_the_frontier() {
        let mut scan = ChainScan::default();
        let (status, asked, _) = run(&mut scan, |at| match at {
            Step::Ask(range) => Event::Asked(range.clone().map(|i| claim(i == 7, false)).collect()),
            _ => unreachable!("nothing is confirmed, so nothing is proven"),
        });
        assert_eq!(
            (status, asked, scan.frontier),
            (ScanStatus::Complete, GAP, 0)
        );
        assert_eq!(scan.issued(), 8);
    }

    #[test]
    fn claims_that_fill_the_window_issue_its_last_index_and_never_one_past_it() {
        // Index 10 is really paid. Every other index is claimed used, in the mempool only.
        let mut scan = ChainScan::default();
        let (status, ..) = run(&mut scan, |at| match at {
            Step::Ask(range) => Event::Asked(range.clone().map(|i| claim(true, i == 10)).collect()),
            Step::Prove(10) => Event::Proven(ProvenUse::modelled(10)),
            other => panic!("unexpected {other:?}"),
        });
        assert_eq!(status, ScanStatus::Complete);
        assert_eq!(scan.frontier, 11);
        assert_eq!(scan.issued(), scan.frontier + GAP - 1);
    }

    #[test]
    fn whatever_a_source_answers_a_plain_restore_reaches_every_index_the_wallet_hands_out() {
        let mut completed = 0;
        for seed in 1..=3_000 {
            let mut rng = Rng(seed);
            let (_, paid) = random_chain(&mut rng);
            let mut scan = ChainScan::default();
            let (status, ..) = run(&mut scan, random_source(&mut rng, &paid));
            if status != ScanStatus::Complete {
                continue;
            }
            completed += 1;
            let issued = scan.issued();
            let window = scan.frontier + GAP;
            assert!(
                issued < window,
                "seed {seed}: issued {issued}, window ends at {window}"
            );
            let reach = restore_reach(&paid);
            assert!(
                issued < reach,
                "seed {seed}: issued {issued} past the restore's {reach}"
            );
        }
        assert!(completed > 100, "only {completed} random scans completed");
    }

    // A source that really pays every address it has seen the wallet ask about, in each new block. Each pass
    // moves the frontier at most one window, because the next window's addresses are new to it.
    #[test]
    fn a_source_that_pays_every_address_it_saw_moves_the_frontier_one_window_a_block() {
        let mut paid: Vec<u32> = Vec::new();
        let (mut seen, mut frontier) = (0, 0);
        for block in 1..=10 {
            paid.extend(paid.len() as u32..seen);
            let mut scan = ChainScan::default();
            let (status, ..) = run(&mut scan, honest(&paid));
            assert_eq!(status, ScanStatus::Complete, "block {block}");
            assert!(
                scan.frontier <= frontier + GAP,
                "block {block}: {frontier} to {}",
                scan.frontier
            );
            (seen, frontier) = (scan.seen.len() as u32, scan.frontier);
        }
        assert_eq!(
            frontier,
            9 * GAP,
            "it moves, one window a block after the first"
        );
    }

    #[test]
    fn a_source_that_calls_a_proven_index_unused_is_unverified() {
        // Index 4 was proven on another host, which the scan left; this one omits it.
        let mut scan = ChainScan {
            frontier: 5,
            seen: Vec::new(),
        };
        let (status, asked, proofs) = run(&mut scan, |at| match at {
            Step::Ask(range) => Event::Asked(range.clone().map(|_| claim(false, false)).collect()),
            other => panic!("unexpected {other:?}"),
        });
        assert!(
            matches!(&status, ScanStatus::Unverified(why) if why.contains("index 4")),
            "{status:?}"
        );
        assert_eq!((asked, proofs, scan.frontier), (5 + GAP, 0, 5));
    }

    #[test]
    fn a_proof_that_got_no_answer_or_found_no_confirmed_payment_continues_later() {
        for not in [
            NotProven::Unreachable("esplora get: HTTP status 429, gave up after 3 attempts".into()),
            NotProven::NoConfirmedPayment,
        ] {
            let mut not = Some(not);
            let mut scan = ChainScan::default();
            let (status, ..) = run(&mut scan, |at| match at {
                Step::Ask(range) => {
                    Event::Asked(range.clone().map(|i| claim(i == 3, i == 3)).collect())
                }
                Step::Prove(3) => Event::NotProven(not.take().unwrap()),
                other => panic!("unexpected {other:?}"),
            });
            assert!(matches!(status, ScanStatus::Continuing(_)), "{status:?}");
            assert_eq!(scan.frontier, 0);
        }
    }

    // No number but the gap bounds a scan. The 5,000-index chain above is the behaviour, and this is the guard
    // against a ceiling coming back.
    #[test]
    fn no_numeric_ceiling_bounds_a_scan() {
        let production = |text: &'static str| text.split("#[cfg(test)]").next().unwrap();
        for (file, text) in [
            ("scan.rs", include_str!("scan.rs")),
            ("hot_wallet.rs", include_str!("hot_wallet.rs")),
            ("chain.rs", include_str!("chain.rs")),
        ] {
            for ceiling in ["MAX_SCAN", "PER_CHAIN", "SCAN_LIMIT", "SCAN_CAP"] {
                assert!(
                    !production(text).contains(ceiling),
                    "{file} names {ceiling}"
                );
            }
        }
        let constants: Vec<&str> = production(include_str!("scan.rs"))
            .lines()
            .filter(|line| line.contains("const "))
            .collect();
        assert_eq!(constants, ["pub(crate) const GAP: u32 = 20;"]);
    }
}
