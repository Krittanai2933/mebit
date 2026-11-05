//! Discovery's stop rule, as a pure reducer. A chain is asked about up to `GAP` indices past its frontier, and
//! the frontier moves past an index only once `prove_use` proved that index paid in a block with valid work, so
//! no source can push the scan with claims alone. The address the wallet hands out stays below `frontier + GAP`,
//! and once a pass answered a gap, no further than a restore with a gap of `GAP` asks over the confirmed history
//! the source showed, so after a full pass over a source that shows the real chain a restore over it finds that
//! address.
//!
//! The payment that proved the frontier is kept in memory, so it can be shown again. The scan file keeps only that
//! payment's index, which backs nothing until a source proves the index used again, because the file is outside data
//! and a txid in it would point any explorer at the wallet's payments. A source that calls the proven index
//! unused has the payment checked again on its own data: a payment it still shows makes it a liar, and one it
//! does not, which a reorg or a forged proof leaves, sends the chain back to index 0 to be found again.
//!
//! Why a scan ends without a cap. Let H be the highest index of a chain that was really paid in a block with
//! valid work. The frontier rises only to one past a proven index, so it never passes H + 1. At one frontier a
//! scan asks the rest of the window, in parts when the source answers a few indices at a time, and proves, then
//! the frontier rises, or the source withdraws the claim it was proving, or the scan ends. A window holds at most
//! `GAP` claims to withdraw. So one source's scan of a chain asks at most H + 1 + GAP summaries and makes at most
//! one proof per window it moves plus one per claim withdrawn. A file's index that does not hold, or a payment
//! shown again and gone, empties the chain once at most. An empty chain holds no index from the file and no payment
//! to show again until a proof, and the index a proof proves is one the scan saw used, which it does not ask again.

use std::ops::Range;

use crate::chain::Summary;
use crate::proof::{Evidence, NotProven, ProvenUse};

/// BIP-44's gap limit, for scanning and for issuing, on both chains.
pub(crate) const GAP: u32 = 20;

/// One chain's scan. `proof` is the payment to its highest proven index, and `frontier` is one past that index.
/// `seen[i]` is what the source said about index i, for every index asked so far, never past the proof's index
/// plus 1 + `GAP`.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub(crate) struct ChainScan {
    pub(crate) proof: Option<Proof>,
    pub(crate) seen: Vec<Seen>,
}

/// A chain's highest proven index.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Proof {
    /// The index alone, as the scan file holds it. It backs no frontier until a source proves it used again.
    Stored(u32),
    /// The payment to it, shown by a source since the app started.
    Shown(ProvenUse),
}

impl Proof {
    pub(crate) fn index(&self) -> u32 {
        match self {
            Proof::Stored(index) => *index,
            Proof::Shown(proven) => proven.index(),
        }
    }
}

/// What a scan keeps of a source's summary of one index, which is its flags and never its amount.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct Seen {
    pub(crate) used: bool,
    pub(crate) confirmed: bool,
    pub(crate) pending: bool,
    /// It held coins, so a routine sync asks it again for its balance.
    pub(crate) funded: bool,
}

impl From<&Summary> for Seen {
    fn from(summary: &Summary) -> Self {
        Seen {
            used: summary.used,
            confirmed: summary.confirmed,
            pending: summary.pending,
            funded: summary.balance > 0,
        }
    }
}

/// What a scan does next.
#[derive(Debug, PartialEq, Eq)]
pub(crate) enum Step {
    /// Ask the source about these indices, the rest of the window.
    Ask(Range<u32>),
    /// Prove the index the scan file holds used again, by its address as any proof is made.
    Reprove(u32),
    /// Show this payment to the proven index again on the source, because the source calls its index unused.
    Recheck(Evidence),
    /// Prove this index used, the highest in the window that the source says has confirmed history.
    Prove(u32),
    Done(ScanStatus),
}

/// The answer to a step.
pub(crate) enum Event {
    /// The source's summary of the first indices asked, in order, at least one, so a scan keeps what it was
    /// told before a later read is refused.
    Asked(Vec<Summary>),
    /// The source refused to answer the ask for now.
    Throttled(String),
    Proven(ProvenUse),
    NotProven(NotProven),
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum ScanStatus {
    /// The window past the frontier was asked and nothing in it claims confirmed use, so the scan reached as far
    /// as a restore with a gap of `GAP` reaches.
    Complete,
    /// The scan stopped short, because a read got no answer. Asking later may finish it. The text says where.
    Continuing(String),
    /// The source lied in a proof, or contradicted itself or a proof. The text says what it claimed.
    Unverified(String),
}

impl ChainScan {
    /// One past the highest index proven used, or 0 before any proof and while the file's index waits to be proven.
    pub(crate) fn frontier(&self) -> u32 {
        match self.proof {
            Some(Proof::Shown(proven)) => proven.index() + 1,
            Some(Proof::Stored(_)) | None => 0,
        }
    }

    /// The step that follows from what is known.
    pub(crate) fn next(&self) -> Step {
        match self.proof {
            Some(Proof::Stored(index)) => return Step::Reprove(index),
            Some(Proof::Shown(proven))
                if self
                    .seen
                    .get(proven.index() as usize)
                    .is_some_and(|seen| !seen.used) =>
            {
                return Step::Recheck(proven.evidence());
            }
            _ => {}
        }
        let (frontier, end) = (self.frontier(), self.frontier() + GAP);
        let asked = self.seen.len() as u32;
        if asked < end {
            return Step::Ask(asked..end);
        }
        match (frontier..end)
            .rev()
            .find(|&i| self.seen[i as usize].confirmed)
        {
            Some(index) => Step::Prove(index),
            None => Step::Done(ScanStatus::Complete),
        }
    }

    /// The index the wallet hands out on this chain, one past the last index the source calls used but never
    /// `frontier + GAP` or past it. When claims fill the window that is its last index, which may be used already.
    /// Never below the frontier either, where a scan cut short has not asked yet and the last index is used.
    ///
    /// A reorg, or a forged proof shown nowhere else, can undo a payment below the proven index and leave a gap no
    /// restore crosses. So once the source answered `GAP` indices in a row with no confirmed history, all of that is
    /// capped at the last of them, which is the last index a restore with a gap of `GAP` asks over the confirmed
    /// history the source showed. A payment there carries a restore furthest, and once it confirms the cap moves
    /// `GAP` on, until the restore reaches the proven index again. One in the mempool only moves nothing, because it
    /// can still be replaced, which would open the gap again behind an address handed out past it. A scan that has not
    /// asked as far as a gap caps nothing, as when it was cut short, a liar's claims were dropped or it never ran.
    /// shortcut: after a reorg, a pass cut short below the gap hands out the uncapped index until a pass gets there,
    /// and a reorg that undoes a lower payment whose coin was spent shows only at the next full pass; close them if a
    /// wallet is seen paid past a restore's reach.
    pub(crate) fn issued(&self) -> u32 {
        let after_last_use = self
            .seen
            .iter()
            .rposition(|seen| seen.used)
            .map_or(0, |last| last as u32 + 1);
        let mut last_asked = GAP - 1;
        for (i, seen) in (0..).zip(&self.seen) {
            if seen.confirmed && i <= last_asked {
                last_asked = i + GAP;
            }
        }
        let cap = match (last_asked as usize) < self.seen.len() {
            true => last_asked,
            false => u32::MAX,
        };
        after_last_use
            .max(self.frontier())
            .min(self.frontier() + GAP - 1)
            .min(cap)
    }
}

/// The one transition, `event` being the answer to `at`. Every call raises the frontier, or moves to a later
/// step at the same frontier (Ask, then Reprove or Recheck, then Prove, then Done), or asks more indices in the
/// same step, or withdraws a claim, except a file's index that does not hold or a payment shown again and gone,
/// which empties the chain.
pub(crate) fn step(scan: &mut ChainScan, at: Step, event: Event) -> Step {
    let before = standing(scan, &at);
    let mut emptied = false;
    let next = match (at, event) {
        (Step::Ask(asked), Event::Asked(summaries)) => {
            debug_assert!(
                (1..=asked.len()).contains(&summaries.len()),
                "{} summaries for {asked:?}",
                summaries.len()
            );
            scan.seen.extend(summaries.iter().map(Seen::from));
            scan.next()
        }
        (Step::Ask(asked), Event::Throttled(why)) => Step::Done(ScanStatus::Continuing(format!(
            "index {} could not be asked: {why}",
            asked.start
        ))),
        (Step::Recheck(evidence), Event::Proven(proven)) => {
            debug_assert_eq!(
                proven.evidence(),
                evidence,
                "the payment shown is the one asked"
            );
            Step::Done(ScanStatus::Unverified(format!(
                "index {}: the source shows its payment {} and calls it unused",
                evidence.index, evidence.txid
            )))
        }
        // A reorg undid the payment, or it never was, so the chain is found again from index 0.
        (Step::Recheck(_), Event::NotProven(NotProven::Gone(_)))
        | (Step::Reprove(_), Event::NotProven(NotProven::Unconfirmed(_))) => {
            *scan = ChainScan::default();
            emptied = true;
            scan.next()
        }
        (Step::Recheck(evidence), Event::NotProven(NotProven::Lie(why))) => Step::Done(
            ScanStatus::Unverified(format!("index {}: {why}", evidence.index)),
        ),
        (Step::Recheck(evidence), Event::NotProven(NotProven::Unreachable(why))) => {
            Step::Done(ScanStatus::Continuing(format!(
                "index {} could not be shown again: {why}",
                evidence.index
            )))
        }
        (Step::Prove(index) | Step::Reprove(index), Event::Proven(proven)) => {
            debug_assert_eq!(proven.index(), index, "the proof is of the index asked");
            scan.proof = Some(Proof::Shown(proven));
            scan.next()
        }
        // The source no longer counts confirmed history for the index, so its claim goes and the window is read again.
        (Step::Prove(index), Event::NotProven(NotProven::Unconfirmed(summary))) => {
            scan.seen[index as usize] = Seen::from(&summary);
            scan.next()
        }
        (
            Step::Prove(index) | Step::Reprove(index),
            Event::NotProven(NotProven::Lie(why) | NotProven::Gone(why)),
        ) => Step::Done(ScanStatus::Unverified(format!("index {index}: {why}"))),
        (
            Step::Prove(index) | Step::Reprove(index),
            Event::NotProven(NotProven::Unreachable(why)),
        ) => Step::Done(ScanStatus::Continuing(format!(
            "index {index} could not be proven: {why}"
        ))),
        (at, _) => unreachable!("{at:?} got the answer to another step"),
    };
    debug_assert!(
        emptied || standing(scan, &next) > before,
        "a step must raise (frontier, phase), it went from {before:?} to {:?}",
        standing(scan, &next)
    );
    next
}

/// Where a scan stands, by its frontier, then by how far along the window it is, then by how many indices it
/// was told about, then by how many of them claim no confirmed history.
fn standing(scan: &ChainScan, at: &Step) -> (u32, u8, usize, usize) {
    let phase = match at {
        Step::Ask(_) => 0,
        Step::Reprove(_) | Step::Recheck(_) => 1,
        Step::Prove(_) => 2,
        Step::Done(_) => 3,
    };
    let unconfirmed = scan.seen.iter().filter(|seen| !seen.confirmed).count();
    (scan.frontier(), phase, scan.seen.len(), unconfirmed)
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

    /// Where a scan stands, ordered as the invariant says it must rise. The frontier counts first, then Ask,
    /// Reprove or Recheck, Prove and Done, then how many indices were asked, then how many claim no confirmed history.
    fn rank(scan: &ChainScan, at: &Step) -> (u32, u8, usize, usize) {
        let phase = match at {
            Step::Ask(_) => 0,
            Step::Reprove(_) | Step::Recheck(_) => 1,
            Step::Prove(_) => 2,
            Step::Done(_) => 3,
        };
        let unconfirmed = scan.seen.iter().filter(|seen| !seen.confirmed).count();
        (scan.frontier(), phase, scan.seen.len(), unconfirmed)
    }

    /// `step`, checking that it raised the scan's rank or emptied the chain after the file's index did not hold or a
    /// payment shown again was gone.
    #[track_caller]
    fn checked_step(scan: &mut ChainScan, at: Step, event: Event) -> Step {
        let (before, recheck) = (
            rank(scan, &at),
            matches!(at, Step::Reprove(_) | Step::Recheck(_)),
        );
        let next = step(scan, at, event);
        let after = rank(scan, &next);
        let emptied = recheck && *scan == ChainScan::default();
        assert!(
            after > before || emptied,
            "the scan went from {before:?} to {after:?}"
        );
        next
    }

    /// A chain whose payment to `index` was proven, by a scan that saw nothing else.
    fn proven(index: u32) -> ChainScan {
        ChainScan {
            proof: Some(Proof::Shown(ProvenUse::modelled(index))),
            seen: Vec::new(),
        }
    }

    /// A chain whose proven index a scan file holds as `index`, with `seen` as the file holds it.
    fn stored(index: u32, seen: Vec<Seen>) -> ChainScan {
        ChainScan {
            proof: Some(Proof::Stored(index)),
            seen,
        }
    }

    fn gone() -> Event {
        Event::NotProven(NotProven::Gone(
            "the source does not know transaction 00..00".into(),
        ))
    }

    /// `answer` with every ask answered for its first `k` indices only, as a source read a window at a time does.
    fn a_few_at_a_time(
        k: usize,
        mut answer: impl FnMut(&Step) -> Event,
    ) -> impl FnMut(&Step) -> Event {
        move |at| match answer(at) {
            Event::Asked(mut summaries) => {
                summaries.truncate(k);
                Event::Asked(summaries)
            }
            other => other,
        }
    }

    fn claim(used: bool, confirmed: bool) -> Summary {
        Summary {
            used,
            confirmed,
            pending: used && !confirmed,
            balance: 0,
        }
    }

    fn lie() -> Event {
        Event::NotProven(NotProven::Lie("forged".into()))
    }

    /// A scan of `scan` to its end against `answer`, checking at every step that the scan's standing rose, with
    /// the status it ended in, the summaries it was given and the proofs it tried, the file's index included.
    fn run(scan: &mut ChainScan, mut answer: impl FnMut(&Step) -> Event) -> (ScanStatus, u32, u32) {
        let (mut asked, mut proofs) = (0, 0);
        let mut at = scan.next();
        loop {
            match &at {
                Step::Ask(_) => {}
                Step::Prove(_) | Step::Reprove(_) => proofs += 1,
                Step::Done(status) => return (status.clone(), asked, proofs),
                Step::Recheck(_) => {}
            }
            let event = answer(&at);
            if let Event::Asked(summaries) = &event {
                asked += summaries.len() as u32;
            }
            at = checked_step(scan, at, event);
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
            Step::Prove(i) | Step::Reprove(i) if paid.contains(i) => {
                Event::Proven(ProvenUse::modelled(*i))
            }
            Step::Prove(_) | Step::Reprove(_) => {
                Event::NotProven(NotProven::Unconfirmed(claim(false, false)))
            }
            Step::Recheck(paid_to) if paid.contains(&paid_to.index) => {
                Event::Proven(ProvenUse::modelled(paid_to.index))
            }
            Step::Recheck(_) => gone(),
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

    /// A source whose answers are drawn from `rng`. Each ask may be throttled or answered for its first few
    /// indices only, each summary may tell the truth about `paid`, invent a use or omit one, each proof may be
    /// lost, refused or the claim withdrawn, a file's index proven again may be lost, lied about or called unpaid,
    /// and a payment shown again may be lost, lied about or gone as after a reorg. It never proves or shows a payment
    /// to an index that was not paid, which is what proof of work buys.
    fn random_source<'a>(rng: &'a mut Rng, paid: &'a [u32]) -> impl FnMut(&Step) -> Event + 'a {
        move |at| match at {
            Step::Ask(_) if rng.below(1_000) < 5 => {
                Event::Throttled("esplora get: HTTP status 429".into())
            }
            Step::Ask(range) => {
                let answered = match rng.chance(30) {
                    true => 1 + rng.below(range.len() as u64) as usize,
                    false => range.len(),
                };
                Event::Asked(
                    range
                        .clone()
                        .take(answered)
                        .map(|i| {
                            let used = paid.contains(&i) != rng.chance(15);
                            claim(used, used && rng.chance(80))
                        })
                        .collect(),
                )
            }
            Step::Prove(i) => match rng.below(10) {
                0 => Event::NotProven(NotProven::Unreachable("esplora get: timed out".into())),
                1 => Event::NotProven(NotProven::Unconfirmed(claim(rng.chance(50), false))),
                _ if paid.contains(i) => Event::Proven(ProvenUse::modelled(*i)),
                _ => lie(),
            },
            Step::Reprove(i) => match rng.below(10) {
                0 => Event::NotProven(NotProven::Unreachable("esplora get: timed out".into())),
                1 => lie(),
                _ if paid.contains(i) && rng.chance(80) => Event::Proven(ProvenUse::modelled(*i)),
                _ => Event::NotProven(NotProven::Unconfirmed(claim(rng.chance(50), false))),
            },
            Step::Recheck(paid_to) => match rng.below(10) {
                0 => Event::NotProven(NotProven::Unreachable("esplora get: timed out".into())),
                1 => lie(),
                _ if paid.contains(&paid_to.index) && rng.chance(80) => {
                    Event::Proven(ProvenUse::modelled(paid_to.index))
                }
                _ => gone(),
            },
            Step::Done(_) => unreachable!("a finished scan asks nothing"),
        }
    }

    /// A chain as a scan file may hand it over: nothing, or a paid index or one past H, which only an edited file or a
    /// forged or reorged proof names, with some claims seen below the window it opens.
    fn random_file(rng: &mut Rng, h: u32, paid: &[u32]) -> ChainScan {
        let index = match rng.below(3) {
            0 => return ChainScan::default(),
            1 => paid[rng.below(paid.len() as u64) as usize],
            _ => h + 1 + rng.below(1_000) as u32,
        };
        let seen = (0..rng.below(u64::from(index + 1 + GAP)))
            .map(|i| {
                let used = paid.contains(&(i as u32)) != rng.chance(15);
                Seen::from(&claim(used, used && rng.chance(80)))
            })
            .collect();
        stored(index, seen)
    }

    /// The highest paid index H and the paid indices of a random chain, each index below H paid by chance.
    fn random_chain(rng: &mut Rng) -> (u32, Vec<u32>) {
        let h = rng.below(150) as u32;
        let mut paid: Vec<u32> = (0..h).filter(|_| rng.chance(25)).collect();
        paid.push(h);
        (h, paid)
    }

    // The termination argument as a property. Whatever a source answers and whatever index a file held, every
    // step raises (frontier, phase, indices asked, claims withdrawn) or empties the chain once, and the frontier
    // never passes H + 1. So a scan asks at most H + 1 + GAP indices twice, proves and withdraws at most as often,
    // proves the file's index or shows a payment again at most twice, and ends.
    #[test]
    fn every_step_raises_the_frontier_or_the_phase_or_the_indices_asked_whatever_the_source_answers()
     {
        for seed in 1..=3_000 {
            let mut rng = Rng(seed);
            let (h, paid) = random_chain(&mut rng);
            let mut scan = random_file(&mut rng, h, &paid);
            let mut steps = 0;
            let mut at = scan.next();
            while !matches!(at, Step::Done(_)) {
                let event = random_source(&mut rng, &paid)(&at);
                at = checked_step(&mut scan, at, event);
                assert!(
                    scan.frontier() <= h + 1,
                    "seed {seed}: frontier {}",
                    scan.frontier()
                );
                steps += 1;
            }
            assert!(
                steps <= 4 * (h + 1 + GAP) + 2,
                "seed {seed}: {steps} steps for H {h}"
            );
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
                other => unreachable!("{other:?}"),
            });
            assert!(matches!(status, ScanStatus::Unverified(_)), "{status:?}");
            assert_eq!((scan.frontier(), asked, proofs), (0, GAP, 1));
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
        assert_eq!(scan.frontier(), h + 1);
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
            (status, asked, scan.frontier()),
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
        assert_eq!(scan.frontier(), 11);
        assert_eq!(scan.issued(), scan.frontier() + GAP - 1);
    }

    #[test]
    fn whatever_a_source_answers_a_plain_restore_reaches_every_index_the_wallet_hands_out() {
        let mut completed = 0;
        // About one random scan in 35 completes, so 3,000 seeds sat right at the floor below.
        for seed in 1..=5_000 {
            let mut rng = Rng(seed);
            let (_, paid) = random_chain(&mut rng);
            let mut scan = ChainScan::default();
            let (status, ..) = run(&mut scan, random_source(&mut rng, &paid));
            if status != ScanStatus::Complete {
                continue;
            }
            completed += 1;
            let issued = scan.issued();
            let window = scan.frontier() + GAP;
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

    // R7, a later reviewer's repro, as a property. A scan proved its frontier on a chain whose payments are at most
    // `GAP` apart. Then a host shows a view of its own: a payment a reorg undid or a forgery left is gone, a liar hides
    // some and claims others it can prove by forgery, and some indices hold a payment in the mempool only. However far
    // a full pass over it gets before a throttle, the wallet hands out the index it handed out before the cap, lowered
    // to the last index a restore over the confirmed history the host showed asks only where the host answered the gap
    // that stops that restore. So a pass that completes never hands out an index a restore over the host's view misses.
    #[test]
    fn whatever_view_a_host_shows_the_wallet_hands_out_an_index_a_restore_over_it_asks() {
        let before_the_cap = |scan: &ChainScan| {
            let after_last_use = scan
                .seen
                .iter()
                .rposition(|seen| seen.used)
                .map_or(0, |last| last as u32 + 1);
            after_last_use
                .max(scan.frontier())
                .min(scan.frontier() + GAP - 1)
        };
        let (mut completed, mut capped, mut cut_capped, mut cut_uncapped) = (0, 0, 0, 0);
        for seed in 1..=3_000 {
            let mut rng = Rng(seed);
            let mut paid = Vec::new();
            let mut next = rng.below(u64::from(GAP)) as u32;
            while next < 150 {
                paid.push(next);
                next += 1 + rng.below(u64::from(GAP)) as u32;
            }
            let mut scan = ChainScan::default();
            run(&mut scan, honest(&paid));
            let view: Vec<u32> = (0..scan.frontier() + GAP)
                .filter(|i| rng.chance(if paid.contains(i) { 70 } else { 3 }))
                .collect();
            let mempool: Vec<u32> = (0..scan.frontier() + GAP)
                .filter(|_| rng.chance(5))
                .collect();
            let host = |at: &Step| match at {
                Step::Ask(range) => Event::Asked(
                    range
                        .clone()
                        .map(|i| {
                            let confirmed = view.contains(&i);
                            claim(confirmed || mempool.contains(&i), confirmed)
                        })
                        .collect(),
                ),
                at => honest(&view)(at),
            };
            scan.seen.clear();
            let k = 1 + rng.below(u64::from(GAP)) as usize;
            let mut asks = rng.below(u64::from(scan.frontier() + GAP));
            let (status, ..) = run(&mut scan, |at| match at {
                Step::Ask(_) if asks == 0 => {
                    Event::Throttled("esplora get: HTTP status 429".into())
                }
                at => {
                    asks = asks.saturating_sub(1);
                    a_few_at_a_time(k, host)(at)
                }
            });
            let said: Vec<u32> = (0..scan.seen.len() as u32)
                .filter(|&i| scan.seen[i as usize].confirmed)
                .collect();
            let reach = restore_reach(&said);
            let cap = match reach as usize <= scan.seen.len() {
                true => reach - 1,
                false => u32::MAX,
            };
            let (issued, before) = (scan.issued(), before_the_cap(&scan));
            assert_eq!(
                issued,
                before.min(cap),
                "seed {seed}: {status:?}, view {view:?}, mempool {mempool:?}"
            );
            match status {
                ScanStatus::Complete => {
                    assert!(
                        issued < restore_reach(&view),
                        "seed {seed}: issued {issued} past a restore over the view {view:?}"
                    );
                    completed += 1;
                    capped += usize::from(issued < before);
                }
                _ if issued < before => cut_capped += 1,
                _ => cut_uncapped += usize::from(before >= restore_reach(&view)),
            }
        }
        assert!(
            completed > 1_000 && capped > 600 && cut_capped > 100 && cut_uncapped > 50,
            "{completed} completed, {capped} of them capped, and of the passes cut short {cut_capped} capped by a gap \
             they answered and {cut_uncapped} past the view's restore with no gap answered yet"
        );
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
                scan.frontier() <= frontier + GAP,
                "block {block}: {frontier} to {}",
                scan.frontier()
            );
            (seen, frontier) = (scan.seen.len() as u32, scan.frontier());
        }
        assert_eq!(
            frontier,
            9 * GAP,
            "it moves, one window a block after the first"
        );
    }

    // R3 of the 5 Nov interrogate. Index 4 was proven on another host, which the scan left, and this one calls it
    // unused. Its payment is shown again on this host's own data. Still there, the host contradicts itself.
    #[test]
    fn a_source_that_calls_a_proven_index_unused_and_still_shows_its_payment_is_unverified() {
        let mut scan = proven(4);
        let (status, asked, proofs) = run(&mut scan, |at| match at {
            Step::Ask(range) => Event::Asked(range.clone().map(|_| claim(false, false)).collect()),
            Step::Recheck(paid) => Event::Proven(ProvenUse::modelled(paid.index)),
            other => panic!("unexpected {other:?}"),
        });
        assert!(
            matches!(&status, ScanStatus::Unverified(why) if why.contains("index 4")),
            "{status:?}"
        );
        assert_eq!((asked, proofs, scan.frontier()), (5 + GAP, 0, 5));
    }

    // R3. A reorg undid index 4's payment, or a lying host forged it, so the honest host calls index 4 unused and
    // does not know the payment. The chain is found again from index 0, which hands out an index a restore reaches.
    #[test]
    fn a_proven_payment_the_source_no_longer_shows_sends_the_chain_back_to_index_0() {
        let mut scan = proven(4);
        let (status, asked, proofs) = run(&mut scan, honest(&[1]));
        assert_eq!(
            (status, asked, proofs),
            (ScanStatus::Complete, (5 + GAP) + (2 + GAP), 1)
        );
        assert_eq!((scan.frontier(), scan.issued()), (2, 2));
    }

    // R1. The file is outside data. Its index backs no frontier, and none of its claims widens what the wallet hands
    // out, until a source proves that index used again. Proven, the file's scan stands. Unpaid, the chain is found
    // again from index 0.
    #[test]
    fn a_stored_index_backs_nothing_until_a_source_proves_it_used_again() {
        let claims = |used: u32| -> Vec<Seen> {
            (0..used + GAP)
                .map(|i| Seen::from(&claim(i < used, i < used)))
                .collect()
        };
        let file = stored(29, claims(30));
        assert_eq!((file.frontier(), file.issued()), (0, GAP - 1));
        assert_eq!(file.next(), Step::Reprove(29));
        let paid: Vec<u32> = (0..30).collect();
        let mut shown = file.clone();
        assert_eq!(
            run(&mut shown, honest(&paid)),
            (ScanStatus::Complete, 0, 1),
            "proven again, nothing is asked and nothing else is proven"
        );
        assert_eq!((shown.frontier(), shown.issued()), (30, 30));
        let mut pushed = stored(4_999, claims(5_000));
        let (status, asked, proofs) = run(&mut pushed, honest(&[2]));
        assert_eq!((status, asked, proofs), (ScanStatus::Complete, 3 + GAP, 2));
        assert_eq!((pushed.frontier(), pushed.issued()), (3, 3));
    }

    // The file's index can cost requests and never move the wallet. A source that lies about it is unverified and one
    // that gets no answer continues later, and after either the index and the claims of the file back nothing.
    #[test]
    fn a_stored_index_a_source_lies_about_or_cannot_prove_backs_nothing() {
        let claims: Vec<Seen> = (0..50)
            .map(|i| Seen::from(&claim(i < 30, i < 30)))
            .collect();
        let throttled = || {
            Event::NotProven(NotProven::Unreachable(
                "esplora get: HTTP status 429".into(),
            ))
        };
        for (answer, ended) in [
            (lie(), ScanStatus::Unverified(String::new())),
            (throttled(), ScanStatus::Continuing(String::new())),
        ] {
            let mut scan = stored(29, claims.clone());
            let mut answer = Some(answer);
            let (status, asked, proofs) = run(&mut scan, |_| answer.take().expect("one answer"));
            assert_eq!(
                std::mem::discriminant(&status),
                std::mem::discriminant(&ended),
                "{status:?}"
            );
            assert_eq!((asked, proofs), (0, 1));
            assert_eq!((scan.frontier(), scan.issued()), (0, GAP - 1));
        }
    }

    #[test]
    fn a_proof_that_got_no_answer_continues_later() {
        let mut scan = ChainScan::default();
        let (status, ..) = run(&mut scan, |at| match at {
            Step::Ask(range) => {
                Event::Asked(range.clone().map(|i| claim(i == 3, i == 3)).collect())
            }
            Step::Prove(3) => Event::NotProven(NotProven::Unreachable(
                "esplora get: HTTP status 429, gave up after 3 attempts".into(),
            )),
            other => panic!("unexpected {other:?}"),
        });
        assert!(matches!(status, ScanStatus::Continuing(_)), "{status:?}");
        assert_eq!(scan.frontier(), 0);
    }

    // The claims came from an earlier pass, or a reorg undid the payment since. Index 3's summary, read again during
    // its proof, counts it in the mempool only, so the next claim of the window is proven instead.
    #[test]
    fn a_claim_the_source_withdraws_during_its_proof_gives_way_to_the_next_claim_of_the_window() {
        let mut scan = ChainScan::default();
        let (status, asked, proofs) = run(&mut scan, |at| match at {
            Step::Ask(range) => {
                Event::Asked(range.clone().map(|i| claim(i <= 3, i <= 3)).collect())
            }
            Step::Prove(3) => Event::NotProven(NotProven::Unconfirmed(claim(true, false))),
            Step::Prove(2) => Event::Proven(ProvenUse::modelled(2)),
            other => panic!("unexpected {other:?}"),
        });
        assert_eq!((status, asked, proofs), (ScanStatus::Complete, 3 + GAP, 2));
        assert_eq!(scan.frontier(), 3);
        assert!(scan.seen[3].used && scan.seen[3].pending && !scan.seen[3].confirmed);
        assert_eq!(scan.issued(), 4);
    }

    #[test]
    fn a_scan_answered_a_few_indices_at_a_time_ends_where_one_answered_whole_ends() {
        let paid: Vec<u32> = (0..=137).filter(|i| i % 3 == 0).collect();
        let mut whole = ChainScan::default();
        let (status, asked, proofs) = run(&mut whole, honest(&paid));
        for k in [1, 7, 10] {
            let mut parts = ChainScan::default();
            assert_eq!(
                run(&mut parts, a_few_at_a_time(k, honest(&paid))),
                (status.clone(), asked, proofs),
                "{k} at a time"
            );
            assert_eq!(parts, whole, "{k} at a time");
        }
    }

    // A public host answers 429 once its hourly quota is spent. The pass keeps every index it was told about, so
    // the next one asks only the rest and proves nothing twice.
    #[test]
    fn a_throttled_ask_ends_the_scan_continuing_and_the_next_scan_asks_only_the_rest() {
        let paid: Vec<u32> = (0..=57).collect();
        let mut whole = ChainScan::default();
        let (_, asked, proofs) = run(&mut whole, honest(&paid));
        let mut scan = ChainScan::default();
        let mut answers = 0;
        let (status, first_asked, first_proofs) = run(&mut scan, |at| {
            answers += 1;
            match at {
                Step::Ask(_) if answers > 6 => Event::Throttled(
                    "esplora get: HTTP status 429, gave up after 3 attempts".into(),
                ),
                at => a_few_at_a_time(10, honest(&paid))(at),
            }
        });
        assert!(
            matches!(&status, ScanStatus::Continuing(why) if why.contains("429")),
            "{status:?}"
        );
        assert!(
            scan.frontier() > 0 && scan.frontier() < whole.frontier(),
            "{scan:?}"
        );
        let (status, rest_asked, rest_proofs) = run(&mut scan, a_few_at_a_time(10, honest(&paid)));
        assert_eq!(status, ScanStatus::Complete);
        assert_eq!(scan, whole);
        assert_eq!(first_asked + rest_asked, asked, "no index is asked twice");
        assert_eq!(
            first_proofs + rest_proofs,
            proofs,
            "no index is proven twice"
        );
    }

    // A full pass starts again from index 0 and keeps the frontier, so until it reaches the frontier the indices
    // below it are unknown. One of them is the last proven used, and the ones before it most likely are too.
    #[test]
    fn a_scan_cut_short_below_its_frontier_hands_out_no_index_below_the_frontier() {
        let mut scan = proven(29);
        assert_eq!(scan.issued(), 30);
        let mut answered = false;
        let (status, ..) = run(&mut scan, |at| match at {
            Step::Ask(range) if !answered => {
                answered = true;
                Event::Asked(
                    range
                        .clone()
                        .take(10)
                        .map(|i| claim(i < 4, i < 4))
                        .collect(),
                )
            }
            _ => Event::Throttled("esplora get: HTTP status 429".into()),
        });
        assert!(matches!(status, ScanStatus::Continuing(_)), "{status:?}");
        assert_eq!(scan.seen.len(), 10);
        assert_eq!(scan.issued(), 30);
    }

    // No number but the gap bounds a scan. The 5,000-index chain above is the behaviour, and this is the guard
    // against a ceiling coming back.
    #[test]
    fn no_numeric_ceiling_bounds_a_scan() {
        let production = |text: &'static str| text.split("#[cfg(test)]").next().unwrap();
        for (file, text) in [
            ("scan.rs", include_str!("scan.rs")),
            ("scan_store.rs", include_str!("scan_store.rs")),
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
