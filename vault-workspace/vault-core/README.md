# vault-core

**Owners**: person 1 (descriptor + key derivation) and person 2 (PSBT + policy engine) — `docs/00-capstone-brief.md` §3.6. Everyone else reads this crate; only these two write to it without a second reviewer.

**Depends on**: nothing in this workspace. Everything else depends on this.

## Responsibilities

- `descriptor` — P2WSH 2-of-3 multisig output descriptor from the borrower/platform/lender pubkeys
- `derivation` — BIP-48 child pubkey derivation (`m/48'/0'/0'/2'`) from an account xpub
- `psbt` — PSBT construction and validation
- `policy` — verifies a PSBT's outputs match the reason it was submitted for signing, before any key signs it
- `keys` — shared key data model (`VaultKey`/`KeySourceType`/`HwVendor`) for the M-of-N wallet-first pivot; real `bitcoin::bip32` types, no derivation logic yet
- `hw` — empty placeholder for the Jade/Trezor hardware-wallet clients

## Why this is the critical path

Every other module (`custody-service`, `mobile-signer-ffi`, `lender-signer-cli`, and eventually `monitor-service`) links against this crate or depends on the invariants it encodes. If the descriptor/PSBT interface is still shifting past week 4, downstream work stalls — see the timeline in `../../docs/00-capstone-brief.md` §4.

## The policy module is security-critical

The multisig script only enforces *how many* signatures; `policy` is what enforces *why*. Any change here needs two-person review (that's why it has two owners) and should grow the adversarial test suite alongside the feature — see `../../.claude/skills/policy-engine-review/SKILL.md`.

## Getting started

```
cargo test -p vault-core
```

Every module is a **simplified skeleton** right now: plain strings/structs stand in for real Bitcoin types (no actual script, keys, or signatures), but the public shapes and the policy engine's authorization logic are the real thing — `PolicyEngine::authorize` already enforces default-deny with adversarial tests for wrong address, wrong amount, wrong loan, and a legit output plus a siphoned extra output. `custody-service` and `lender-signer-cli` already depend on `psbt::UnsignedPsbt` and `policy::SigningReason` as their shared interchange format.

`bitcoin = "0.32"` / `miniscript = "12"` are enabled now, but only `keys` uses them — the next step is swapping the mock internals in `descriptor`/`derivation`/`psbt` for real ones — the function signatures shouldn't need to change much, and `policy`'s tests should keep passing against the real `Psbt` type with minimal edits.
