---
name: testnet-workflow
description: How to test this project's flows against real Bitcoin testnet instead of mocks — getting testnet coins, inspecting vault addresses and transactions, and what "definition of done" actually requires. Use when integration-testing custody-service, lender-signer-cli, monitor-service, or the mobile-signer-ffi demo app.
---

# Testnet workflow

Per `docs/00-capstone-brief.md` §5, this project's definition of done explicitly requires running on real Bitcoin testnet — "not mock." Every module that touches a live transaction should be exercised against testnet before being called complete, not just against unit-test fixtures.

## Getting testnet coins

Use a testnet faucet to fund a test wallet, then send from there to a vault address your code generates. (Search for a current "bitcoin testnet faucet" — faucet addresses change over time; don't hardcode one into project docs.) Keep a small pool of pre-funded testnet UTXOs around for the team to reuse rather than everyone faucet-hunting individually.

## Inspecting vault addresses and transactions

Use a testnet block explorer (Esplora-based explorers are the natural fit since `monitor-service`'s MVP price/UTXO feed is also Esplora-based — see `docs/00-capstone-brief.md` §3.5) to confirm:
- a generated vault address matches the expected descriptor (same address every time for the same inputs — descriptor generation must be deterministic)
- a broadcast PSBT actually confirms on-chain with the expected outputs
- the borrower can independently verify their own vault's balance without going through the platform — this is a core product requirement (`docs/00-capstone-brief.md` §2), not just a testing convenience

## What "tested on testnet" means per module

- **vault-core**: descriptors it generates should produce addresses that a block explorer recognizes as valid P2WSH multisig; PSBTs it builds should be broadcastable and confirm.
- **custody-service**: the full state machine (`created → awaiting_borrower_sig → awaiting_lender_sig → broadcast → confirmed`) should be driven end-to-end against a real testnet broadcast, not a mocked RPC response — this is what catches idempotency bugs (double-broadcast, stuck states) that unit tests miss.
- **mobile-signer-ffi**: the demo app's "open a new loan" and "verify vault on-chain" flows should show a real testnet address and real confirmation, not fixture data.
- **lender-signer-cli**: the offline-sign step should be tested with a PSBT actually fetched from a running `custody-service`, not a hand-crafted file, at least once per milestone.
- **monitor-service**: LTV calculations should be checked against a testnet vault's actual live value (even though testnet BTC has no real price — cross-check the math using a hypothetical mainnet-price oracle value, since the point is verifying the *formula*, not the *price feed*).

## Company-provided support

Per `docs/00-capstone-brief.md` §8, the company provides a sandbox testnet environment and CI — check with the mentor before standing up ad hoc infrastructure that might duplicate it.
