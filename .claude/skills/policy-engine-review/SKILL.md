---
name: policy-engine-review
description: Adversarial review checklist for vault-core's policy engine — the layer that authorizes what a PSBT is allowed to do before any party signs it. Use whenever the policy engine changes, before any signing-key integration ships, and for the term-2 team-wide security review (docs/00-capstone-brief.md §4).
---

# Policy engine adversarial review

The policy engine is the single highest-risk piece of code in this project (see `.claude/agents/vault-core-policy.md` and `docs/01-architecture.md`). The multisig script only enforces signature *count*; this engine is what enforces signature *purpose*. Review every change against this checklist — as the author, and again as an independent reviewer trying to break it.

## Checklist: what should this PSBT change reject?

For every code path that authorizes a signature, ask:

- **Wrong output address.** Does the PSBT pay an address that isn't the expected destination for this loan's action (collateral return → borrower's registered address; liquidation → the designated sale/settlement path)?
- **Wrong amount.** Does the PSBT move more (or less) value than the authorized action allows — e.g. a "repayment" PSBT that also siphons off extra collateral, or a liquidation that sells more BTC than needed to cover debt + buffer?
- **Wrong loan.** Does the PSBT reference a different loan's vault/UTXO than the one the signing request claims to be for?
- **Wrong action for current state.** Is a "collateral return" PSBT being authorized for a loan that hasn't actually been repaid? Is a "liquidation" PSBT being authorized when LTV hasn't crossed the threshold?
- **Replay / duplicate.** Can the same authorization be reused to justify signing a second, different PSBT (e.g. a stale approval token, or a signing request whose state should have already advanced past this point)?
- **Fee manipulation.** Can an attacker inflate the fee to redirect value away from expected outputs, or use a fee-bumping mechanism to change effective outputs after the fact?
- **Partial-spend / change correctness (liquidation flow).** Does change from a partial-collateral sale go back to the vault (not to an attacker-controlled address), and is the sold amount the minimum needed to cover debt + buffer — never more?

## How to structure the test suite

- One test per checklist item **per authorized action type** (repayment, liquidation, fallback) — don't just test the happy path once and assume it generalizes.
- Prefer property-style tests where feasible (e.g. "for any PSBT where the output amount exceeds the authorized amount, the policy engine rejects it") over enumerating a handful of hand-picked cases.
- Every rejected case should have an assertion on *why* it was rejected (the specific policy violation), not just that it returned an error — this catches the case where a test passes for the wrong reason.

## Process

- Two-person review minimum for any change to `vault-core`'s `policy` module (`docs/00-capstone-brief.md` §3.6) — the second reviewer's job is specifically to try the checklist above against the diff, not just read it for style.
- Term 2 weeks 13-14 is a dedicated team-wide adversarial session (`docs/00-capstone-brief.md` §4): the whole team tries to construct a PSBT that gets wrongly authorized. Treat every successful attack found here as a required fix before the final demo, not a nice-to-have.

## Definition of done

Per `docs/00-capstone-brief.md` §5: the policy engine must reject 100% of the adversarial test suite's PSBTs whose outputs don't match their claimed authorization reason.
