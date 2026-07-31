# Architecture

Distilled, English-language reference for engineering work. The authoritative spec (with full rationale) is [`00-capstone-brief.md`](./00-capstone-brief.md); this doc exists so anyone can orient without reading the whole brief.

## System in one paragraph

A borrower deposits BTC into a 2-of-3 multisig vault (borrower / platform / lender). No single party can move the coins alone. The borrower draws a THB loan against the vault's value. `vault-core` builds and validates every transaction that could move those coins; a **policy engine** inside it is the only thing standing between "a validly co-signed PSBT" and "a validly co-signed PSBT that pays the wrong person." `custody-service` orchestrates the signing workflow, `mobile-signer-ffi` lets the borrower sign from a phone, `lender-signer-cli` lets the lender sign offline, and `monitor-service` watches LTV and triggers liquidation before the collateral value can fall through the multisig's safety margin.

## Module map

```
vault-workspace/
├── vault-core/           Rust lib. Descriptor + BIP-48 derivation + PSBT + policy engine.
│                         Dependency of every other module. Nothing else touches Bitcoin
│                         script or key derivation directly — they all go through this.
├── custody-service/      Rust service (gRPC/REST via tonic/axum) + Postgres.
│                         State machine for signing requests; the integration point for
│                         the existing NestJS Loan Service.
├── mobile-signer-ffi/    UniFFI bindings (Kotlin/Swift) wrapping vault-core, plus a demo
│                         app (RN or Flutter) — the borrower-facing client.
├── lender-signer-cli/    Rust CLI (clap). Air-gapped offline signing for the lender/fund
│                         representative. Depends on vault-core.
└── monitor-service/      Rust service. Polls price (Esplora/oracle), computes LTV per
                          active loan, triggers margin-call/liquidation in custody-service.
```

## Why vault-core is the critical path

Every other module either links against it directly (custody-service, mobile-signer-ffi, lender-signer-cli) or depends on decisions it encodes (monitor-service's liquidation thresholds must match what the policy engine will actually authorize). If vault-core's PSBT/descriptor interface is unstable past week 4, every downstream module stalls. Treat interface changes to vault-core as breaking changes requiring a team-wide heads-up, not a routine refactor.

## The policy engine is the actual product

The multisig script (2-of-3 P2WSH) only enforces *how many* signatures — it has no idea *why* a transaction is being signed. The policy engine is the layer that says "this specific PSBT is a legitimate repayment / liquidation / fallback, given this loan's state" before a signer's key is used. A bug here isn't a UI bug — it's a bug where the platform's or lender's key co-signs a transaction that drains a vault to the wrong address. Treat every policy-engine change as security-sensitive: two-person review minimum (see the team split in `00-capstone-brief.md` §3.6), and build the adversarial test suite ([`.claude/skills/policy-engine-review`](../.claude/skills/policy-engine-review/SKILL.md)) alongside the feature, not after.

## Signing request lifecycle (custody-service state machine)

```
created → awaiting_borrower_sig → awaiting_lender_sig → broadcast → confirmed
```

Each transition should be idempotent — a PSBT can be re-submitted for the same signing request without creating a duplicate on-chain spend. This is the "race condition handling" skill called out in the brief's §6 (e.g. a `loan_index`-style unique constraint to stop double-signing).

## Explicitly out of scope

- Legal/regulatory compliance (SEC/BOT) for the lending product itself
- Fund/NAV ledger accounting on the lender side

These are the company's responsibility, not the capstone team's. If a design decision seems to require solving either, that's a sign to flag it rather than build around it.

## Related docs

- [`00-capstone-brief.md`](./00-capstone-brief.md) — full original brief (Thai), source of truth
- [`design-notes.md`](./design-notes.md) — borrower app UI/flow reference for `mobile-signer-ffi`
- Per-module `README.md` under `vault-workspace/*/` — responsibilities, tech, dependencies, milestones
- `.claude/agents/*.md` — one subagent per module for AI-assisted work on that module
- `.claude/skills/*` — shared knowledge (Bitcoin fundamentals, policy review, testnet workflow, design tokens)
