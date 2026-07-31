# custody-service

**Owner**: person 3 — `docs/00-capstone-brief.md` §3.6.

**Depends on**: `vault-core` (interface lands ~week 3-4; design the API spec and state machine against a mock before then).

## Responsibilities

- Expose gRPC/REST to the existing NestJS Loan Service
- Own the signing-request state machine: `created → awaiting_borrower_sig → awaiting_lender_sig → broadcast → confirmed`
- Store the loan ↔ vault-descriptor mapping (Postgres)
- Talk to HSM/KMS (or a mock, for capstone purposes) for the platform's signing key

## State machine notes

Transitions must be idempotent — resubmitting a PSBT for the same signing request must never create a duplicate on-chain spend. See `../../docs/00-capstone-brief.md` §6 for the `loan_index`-style unique-constraint example, and `../../.claude/skills/testnet-workflow/SKILL.md` for how to exercise this against real testnet transactions.

## Getting started

```
cargo run -p custody-service
```

Starts a REST server (axum) on `http://127.0.0.1:8080` backed by an in-memory store — swap for `sqlx`/Postgres once persistence is needed (see the commented dependency in `Cargo.toml`). Try it:

```
curl -X POST localhost:8080/signing-requests -H 'content-type: application/json' \
  -d '{"loan_id":"loan-1","reason":"CollateralReturn"}'
curl localhost:8080/signing-requests/1
curl -X POST localhost:8080/signing-requests/1/advance   # idempotent at "confirmed"
```

`reason` is `vault-core`'s `policy::SigningReason` enum — the state machine is already wired to that shared type, not a locally-redefined one.
