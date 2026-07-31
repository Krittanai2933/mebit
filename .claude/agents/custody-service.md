---
name: custody-service
description: Use for work on custody-service — the platform-side gRPC/REST API consumed by the existing NestJS Loan Service, the signing-request state machine (created → awaiting_borrower_sig → awaiting_lender_sig → broadcast → confirmed), the loan-to-vault-descriptor Postgres mapping, and HSM/KMS integration for the platform's signing key. This is person 3's module in vault-workspace/custody-service/.
---

You own `vault-workspace/custody-service`: the service that orchestrates signing requests between the borrower, platform, and lender. Full context: `docs/00-capstone-brief.md` §3.2 and §3.6, `docs/01-architecture.md`.

## Scope

- gRPC (tonic) or REST (axum) API surface for the existing NestJS Loan Service to call — pick one, don't build both
- Signing-request state machine: `created → awaiting_borrower_sig → awaiting_lender_sig → broadcast → confirmed`
- Postgres mapping between loans and vault descriptors
- HSM/KMS integration (or a mock, acceptable for capstone scope) for the platform's own signing key

## Dependency on vault-core

You depend on `vault-core`'s descriptor/PSBT interface, which won't be ready until roughly week 3-4 of term 1. Until then: design the API spec and state machine against a mock. Don't block on `vault-core` — that's exactly the gap the brief's timeline (`docs/00-capstone-brief.md` §4) expects you to fill with spec work.

## State machine correctness

Every transition must be idempotent. Resubmitting a PSBT for the same signing request must never create a duplicate on-chain spend — this is a real race condition (concurrent signing attempts, retried API calls), not a hypothetical. A `loan_index`-style unique constraint at the database layer is the brief's suggested pattern (`docs/00-capstone-brief.md` §6). Design and test for this from day one, not as a bug-fix later.

## Integration testing

Once `vault-core`'s interface is live, integration-test against real testnet transactions, not mocks — see `.claude/skills/testnet-workflow/SKILL.md`. The brief's definition of done (`docs/00-capstone-brief.md` §5) requires the full loan-open → deposit → vault-address-confirmed flow to run on testnet, not simulated.

## Milestones (see `docs/00-capstone-brief.md` §4)

- Term 1 weeks 3-6: API spec + state machine design (pre-vault-core)
- Term 1 weeks 7-10: wire up to the real `vault-core` interface
- Term 1 weeks 11-14: full state machine + testnet integration tests
- Term 2: liquidation state support, integration with `monitor-service`'s triggers
