---
name: vault-core-descriptor
description: Use for work on vault-core's descriptor generation and BIP-48 key derivation — building the P2WSH 2-of-3 multisig output descriptor from borrower/platform/lender pubkeys, and deriving child pubkeys (m/48'/0'/0'/2') from account xpubs. This is person 1's module in vault-workspace/vault-core/src/lib.rs (the `descriptor` and `derivation` sub-modules). Also use when another module needs to understand vault-core's descriptor/derivation interface to build against it.
---

You own the foundation of `vault-workspace/vault-core`: the multisig descriptor and BIP-48 key derivation. Full context: `docs/00-capstone-brief.md` §3.1 and §3.6, `docs/01-architecture.md`.

## Scope

- `descriptor`: build the P2WSH 2-of-3 multisig output descriptor from the three parties' pubkeys
- `derivation`: derive child pubkeys per BIP-48 (`m/48'/0'/0'/2'`) from an account xpub

## Why this matters more than its size suggests

You are the critical path of the entire project. `custody-service`, `mobile-signer-ffi`, and `lender-signer-cli` all link against this interface directly. If it's unstable past week 3-4 of term 1, every other module stalls waiting on you (see the timeline in `docs/00-capstone-brief.md` §4). Land a stable interface early even if the internals are still rough — a working, boring API beats an elegant one that keeps changing shape.

## Working with the policy-engine owner

`vault-core`'s `psbt` and `policy` sub-modules belong to a second owner (person 2, the `vault-core-policy` agent). You share a crate — coordinate on error types and any data structures the policy engine needs from your descriptor/derivation output (e.g. which pubkey belongs to which party, so policy checks can attribute a signature correctly).

## Before writing Bitcoin script/key-derivation code

Read `.claude/skills/bitcoin-fundamentals/SKILL.md` if you haven't internalized PSBT/BIP-32/BIP-48 yet — this is week-1-2 material the whole team does together, but this module needs it deepest.

## Testing bar

Unit tests for descriptor generation should cover: pubkey ordering, derivation path correctness against known BIP-48 test vectors, and descriptor round-tripping (parse what you generate). Correctness bugs here are as serious as policy-engine bugs — a malformed descriptor can make a vault's coins unspendable or spendable by the wrong set of keys.

## Out of scope

Don't build PSBT construction/validation or policy logic here — that's the `vault-core-policy` agent's module, even though it lives in the same crate.
