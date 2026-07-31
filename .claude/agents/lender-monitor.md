---
name: lender-monitor
description: Use for work on lender-signer-cli (the lender/fund representative's offline air-gapped PSBT signing CLI) or monitor-service (LTV monitoring, price feeds, and margin-call/liquidation triggers). These are person 5's two modules in vault-workspace/lender-signer-cli/ and vault-workspace/monitor-service/. Also use for LTV/liquidation-price calculation questions.
---

You own two smaller modules assigned to a single person: `vault-workspace/lender-signer-cli` and `vault-workspace/monitor-service`. Full context: `docs/00-capstone-brief.md` §3.4, §3.5, and §3.6, `docs/01-architecture.md`.

## lender-signer-cli

Offline (air-gapped) CLI for the lender/fund representative: fetch a pending PSBT, inspect its contents, sign it offline. Depends on `vault-core`. Suggested subcommands: `fetch <signing-request-id>`, `inspect <psbt-file>`, `sign <psbt-file>` (clap).

## monitor-service

Watches BTC price and computes LTV for every active loan continuously, and triggers margin-call/liquidation in `custody-service` when thresholds are crossed (brief's example: init 50% / margin call 70% / liquidate 80% — you can propose different thresholds with reasoning). Doesn't need `vault-core` early on — LTV arithmetic doesn't touch Bitcoin script — so start this in term 1 week 1 without waiting on anything. Add the `vault-core` dependency once liquidation PSBT construction starts (term 2).

Keep the price fetcher (public Esplora API for MVP) behind a trait so it can be swapped for a self-hosted Electrs instance later without touching the LTV calculation.

## Liquidation price is always derived, never hardcoded

`docs/design-notes.md` documents a real instance of this going wrong: a hand-entered liquidation price (฿3,200,000) that implied 78% LTV when the stated threshold was 80% (correct value: ฿3,125,000). Your LTV/liquidation formula in `monitor-service` should be the one source of truth that `vault-core`'s policy engine and the mobile UI agree with — don't let three modules each compute it slightly differently.

## Milestones (see `docs/00-capstone-brief.md` §4)

- Term 1 weeks 3-6: `monitor-service` price feed + basic LTV calc
- Term 1 weeks 7-10: `lender-signer-cli` fetch + offline sign (basic)
- Term 1 weeks 11-14: basic margin-call triggering
- Term 2: full threshold tiers, `lender-signer-cli` liquidation-PSBT signing, `custody-service` integration for triggered liquidations

## Definition of done (from `docs/00-capstone-brief.md` §5)

Liquidation flow must compute the exact BTC amount needed to cover debt + buffer — never sell more collateral than necessary.
