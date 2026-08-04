---
name: platform-services
description: Use for work on custody-service (platform-side gRPC/REST API, signing-request state machine, loan-to-vault-descriptor mapping, HSM/KMS integration), lender-signer-cli (the lender's offline air-gapped PSBT signing CLI), or monitor-service (LTV monitoring, price feeds, margin-call/liquidation triggers). These three modules are person 3's responsibility in a 4-person team — vault-workspace/custody-service/, vault-workspace/lender-signer-cli/, and vault-workspace/monitor-service/. Also use for LTV/liquidation-price calculation questions.
---

You own three modules — this is what a 4-person team (`docs/00-capstone-brief.md` §3.6) looks like instead of the original 5-person split: `custody-service`, `lender-signer-cli`, and `monitor-service` are bundled onto one person because the latter two are small and don't need `vault-core` early. Full context: `docs/00-capstone-brief.md` §3.2, §3.4, §3.5, §3.6, `docs/01-architecture.md`.

**Pacing tip specific to this role**: don't try to advance all three at once. The natural order is `monitor-service` + `lender-signer-cli` scaffolding first (term 1, week 1 — neither needs `vault-core`), then shift focus to `custody-service` once `vault-core`'s interface lands (~week 3-4), then come back to `lender-signer-cli`/`monitor-service` to wire them up to the real `custody-service`/`vault-core` interfaces. Trying to context-switch between all three every day will cost more than doing them in sequence.

## custody-service

The service that orchestrates signing requests between the borrower, platform, and lender.

- gRPC (tonic) or REST (axum) API surface for the existing NestJS Loan Service to call — pick one, don't build both
- Signing-request state machine: `created → awaiting_borrower_sig → awaiting_lender_sig → broadcast → confirmed`
- Postgres mapping between loans and vault descriptors
- HSM/KMS integration (or a mock, acceptable for capstone scope) for the platform's own signing key

**Dependency on vault-core**: won't be ready until roughly week 3-4 of term 1. Until then, design the API spec and state machine against a mock — that's exactly the gap the brief's timeline expects you to fill with spec work while `monitor-service`/`lender-signer-cli` get their initial scaffolding.

**State machine correctness**: every transition must be idempotent. Resubmitting a PSBT for the same signing request must never create a duplicate on-chain spend — this is a real race condition (concurrent signing attempts, retried API calls), not a hypothetical. A `loan_index`-style unique constraint at the database layer is the brief's suggested pattern (`docs/00-capstone-brief.md` §6). Design and test for this from day one, not as a bug-fix later.

**Integration testing**: once `vault-core`'s interface is live, integration-test against real testnet transactions, not mocks — see `.claude/skills/testnet-workflow/SKILL.md`. The brief's definition of done requires the full loan-open → deposit → vault-address-confirmed flow to run on testnet, not simulated.

## lender-signer-cli

Offline (air-gapped) CLI for the lender/fund representative: fetch a pending PSBT, inspect its contents, sign it offline. Depends on `vault-core`. Suggested subcommands: `fetch <signing-request-id>`, `inspect <psbt-file>`, `sign <psbt-file>` (clap).

## monitor-service

Watches BTC price and computes LTV for every active loan continuously, and triggers margin-call/liquidation in `custody-service` when thresholds are crossed. **Use the mebit design's thresholds** (`docs/design-notes.md`): warnings at 65% and 72% LTV, liquidation at 80% — these are more concrete than the brief's original 50/70/80 example and are what the mobile app already uses, but they're still a design proposal pending formal risk/business sign-off (`docs/04-open-items.md` item 5). Doesn't need `vault-core` early on — LTV arithmetic doesn't touch Bitcoin script — so start this in term 1 week 1 without waiting on anything. Add the `vault-core` dependency once liquidation PSBT construction starts (term 2).

Keep the price fetcher (public Esplora API for MVP) behind a trait so it can be swapped for a self-hosted Electrs instance later without touching the LTV calculation.

## Liquidation price is always derived, never hardcoded

`docs/design-notes.md` documents a real instance of this going wrong: a hand-entered liquidation price (฿3,200,000) that implied 78% LTV when the stated threshold was 80% (correct value: ฿3,125,000). Your LTV/liquidation formula in `monitor-service` should be the one source of truth that `vault-core`'s policy engine and the mobile UI agree with — don't let multiple modules each compute it slightly differently.

## Milestones (see `docs/00-capstone-brief.md` §4)

- Term 1 weeks 3-6: `custody-service` API spec + state machine design (pre-vault-core) alongside `monitor-service` price feed + basic LTV calc
- Term 1 weeks 7-10: wire `custody-service` to the real `vault-core` interface + start `lender-signer-cli` fetch/inspect/sign
- Term 1 weeks 11-14: full `custody-service` state machine + testnet integration tests + basic margin-call triggering in `monitor-service`
- Term 2 weeks 1-4: `custody-service` liquidation state support + `monitor-service` full threshold tiers
- Term 2 weeks 5-8: `custody-service` ↔ `monitor-service` integration for triggered liquidations + `lender-signer-cli` liquidation-PSBT signing, then help `mobile-signer-ffi` with stretch-goal screens or the hot-wallet layer once your own modules are stable

## Definition of done (from `docs/00-capstone-brief.md` §5)

- The full loan-open → deposit → vault-address-confirmed flow runs on testnet, not simulated (`custody-service`)
- Liquidation flow computes the exact BTC amount needed to cover debt + buffer — never sells more collateral than necessary (`monitor-service` + `lender-signer-cli` together)
