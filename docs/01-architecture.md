# Architecture

Distilled, English-language reference for engineering work. The authoritative spec (with full rationale) is [`00-capstone-brief.md`](./00-capstone-brief.md); this doc exists so anyone can orient without reading the whole brief. Role-by-role detail lives in [`02-roles-and-responsibilities.md`](./02-roles-and-responsibilities.md); step-by-step flows (onboarding, open loan, repayment, liquidation, fallback) live in [`03-flows.md`](./03-flows.md).

## System in one paragraph

A borrower deposits BTC into a 2-of-3 multisig vault (borrower / platform / lender). No single party can move the coins alone. The borrower draws a THB loan against the vault's value. `vault-core` builds and validates every transaction that could move those coins; a **policy engine** inside it is the only thing standing between "a validly co-signed PSBT" and "a validly co-signed PSBT that pays the wrong person." `custody-service` orchestrates the signing workflow, `mobile-signer-ffi` is the borrower's full Bitcoin wallet app (not just a PSBT-signing screen — see below), `lender-signer-cli` lets the lender sign offline, and `monitor-service` watches LTV and triggers liquidation before the collateral value can fall through the multisig's safety margin.

## Module map

```
vault-workspace/
├── vault-core/           Rust lib. Descriptor + BIP-48 derivation + PSBT + policy engine.
│                         Dependency of every other module. Nothing else touches Bitcoin
│                         script or key derivation directly — they all go through this.
├── custody-service/      Rust service (gRPC/REST via tonic/axum) + Postgres.
│                         State machine for signing requests; the integration point for
│                         the existing NestJS Loan Service.
├── mobile-signer-ffi/    UniFFI bindings (Kotlin/Swift) + a demo app (RN or Flutter) — the
│                         borrower-facing "mebit" wallet. Two layers: a real single-sig hot
│                         wallet for unpledged BTC (built on `bdk`), and a multisig vault
│                         signer wrapping vault-core for BTC pledged to a loan. See below.
├── lender-signer-cli/    Rust CLI (clap). Air-gapped offline signing for the lender/fund
│                         representative. Depends on vault-core.
└── monitor-service/      Rust service. Polls price (Esplora/oracle), computes LTV per
                          active loan, triggers margin-call/liquidation in custody-service.
```

## Why vault-core is the critical path

Every other module either links against it directly (custody-service, mobile-signer-ffi, lender-signer-cli) or depends on decisions it encodes (monitor-service's liquidation thresholds must match what the policy engine will actually authorize). If vault-core's PSBT/descriptor interface is unstable past week 4, every downstream module stalls. Treat interface changes to vault-core as breaking changes requiring a team-wide heads-up, not a routine refactor.

## The policy engine is the actual product

The multisig script (2-of-3 P2WSH) only enforces *how many* signatures — it has no idea *why* a transaction is being signed. The policy engine is the layer that says "this specific PSBT is a legitimate repayment / liquidation / fallback, given this loan's state" before a signer's key is used. A bug here isn't a UI bug — it's a bug where the platform's or lender's key co-signs a transaction that drains a vault to the wrong address. Treat every policy-engine change as security-sensitive: two-person review minimum (see the team split in `00-capstone-brief.md` §3.6), and build the adversarial test suite ([`.claude/skills/policy-engine-review`](../.claude/skills/policy-engine-review/SKILL.md)) alongside the feature, not after.

## mobile-signer-ffi is two wallets in one app

The "mebit" design (see [`design-notes.md`](./design-notes.md)) turned what was originally scoped as a thin PSBT-signing wrapper into a full Bitcoin wallet, because a borrower's BTC is always in one of two states:

- **Free** — not pledged to any loan. This needs a real single-sig hot wallet: address generation, UTXO tracking, fee estimation, send/receive, and its own node connectivity (Electrum/Esplora) so the borrower can see and move this BTC even if the backend is down — that's the actual point of self-custody. Recommended to build this on **`bdk`** (Bitcoin Dev Kit, Rust core — not the `bdk-rn`/`bdk-dart` wrappers, which are integration-testing-only as of July 2026) rather than reimplementing wallet plumbing. `bdk` also natively supports watch-only PSBT construction (a device with only the public descriptor builds an unsigned PSBT; the device holding the private key signs it separately) — the same pattern `lender-signer-cli` already uses.
- **Pledged** — locked into a specific loan's 2-of-3 vault. This is the existing scope: wrap `vault-core`'s key derivation and PSBT signing behind UniFFI.

Both need to read as one balance on the Home screen (see `design-notes.md`'s Portfolio section), but the key/signing logic underneath must stay clearly separated — free BTC is single-sig (weaker security model, by design, since it isn't collateral), pledged BTC is multisig.

This roughly doubles `mobile-signer-ffi`'s scope, which is why `00-capstone-brief.md` §3.3 calls out MVP screens vs. stretch-goal screens explicitly — don't let the hot-wallet requirement block getting an MVP loan flow working on testnet first.

## Signing request lifecycle (custody-service state machine)

```
created → awaiting_borrower_sig → awaiting_lender_sig → broadcast → confirmed
```

Each transition should be idempotent — a PSBT can be re-submitted for the same signing request without creating a duplicate on-chain spend. This is the "race condition handling" skill called out in the brief's §6 (e.g. a `loan_index`-style unique constraint to stop double-signing).

## Data model (draft)

`custody-service`'s Postgres schema, sketched but not yet implemented (current skeleton is in-memory — see `docs/05-progress-and-next-steps.md`):

- **`customers`**: `account_xpub`, `next_loan_index` — the borrower onboards once, submitting an account xpub; every subsequent loan derives the next child index server-side without asking the borrower for key material again.
- **`loans`**: `customer_id`, `loan_index`, `vault_descriptor`, `vault_address`, `status` (`pending_deposit → active → margin_call → liquidating → closed`), with `UNIQUE (customer_id, loan_index)` to prevent address collisions.

This schema already supports the multi-loan/portfolio model from `design-notes.md` without changes — one customer can have many rows in `loans`, each with its own isolated vault. The complexity that model adds is entirely on the mobile app's side (aggregating balances across vault addresses), not the backend schema.

## Explicitly out of scope

- Legal/regulatory compliance (SEC/BOT) for the lending product itself
- Fund/NAV ledger accounting on the lender side

These are the company's responsibility, not the capstone team's. If a design decision seems to require solving either, that's a sign to flag it rather than build around it. See [`04-open-items.md`](./04-open-items.md) for the full list of things still undecided (including these two).

## Related docs

- [`00-capstone-brief.md`](./00-capstone-brief.md) — full brief (Thai), source of truth
- [`02-roles-and-responsibilities.md`](./02-roles-and-responsibilities.md) — who holds which key, who co-signs with whom and when
- [`03-flows.md`](./03-flows.md) — step-by-step: onboarding, open loan, repayment, liquidation, fallback
- [`04-open-items.md`](./04-open-items.md) — undecided items the team will need to revisit
- [`design-notes.md`](./design-notes.md) — borrower app UI/flow reference for `mobile-signer-ffi`
- [`05-progress-and-next-steps.md`](./05-progress-and-next-steps.md) — current skeleton status per module and full TODO
- Per-module `README.md` under `vault-workspace/*/` — responsibilities, tech, dependencies, milestones
- `.claude/agents/*.md` — one subagent per module for AI-assisted work on that module
- `.claude/skills/*` — shared knowledge (Bitcoin fundamentals, policy review, testnet workflow, design tokens)
