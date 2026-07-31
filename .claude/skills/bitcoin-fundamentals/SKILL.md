---
name: bitcoin-fundamentals
description: Primer on the Bitcoin protocol concepts this project is built on — UTXOs, script, multisig, PSBT, and BIP-32/48 key derivation. Use before writing any vault-core code, when onboarding a new team member, or when any module needs to reason about how the multisig vault actually works on-chain.
---

# Bitcoin fundamentals for this project

This is the term-1 week-1-2 material the whole team studies together (`docs/00-capstone-brief.md` §3.6 and §4) before splitting into modules. Skim it as a refresher; go deep on whichever section maps to the module you own.

## UTXO model

Bitcoin has no account balances — only unspent transaction outputs (UTXOs). A vault "holding" BTC really means: there exists a UTXO whose spending condition (script) is the vault's multisig policy. Every deposit, repayment, and liquidation in this project is really "construct a new transaction that spends existing UTXO(s) into new ones."

## Script and multisig

A P2WSH (Pay-to-Witness-Script-Hash) 2-of-3 multisig output can be spent by any 2 of the 3 named public keys signing. This is what `vault-core`'s `descriptor` module builds. Critically: **the script only checks signature count and validity — it has no concept of "why."** That's the entire reason the policy engine (`vault-core`'s `policy` module) exists as a separate layer on top.

## BIP-32 / BIP-48 — hierarchical deterministic key derivation

- BIP-32 defines deriving a tree of child keys from one master seed/xpub, so a party never has to generate and back up a new key per vault.
- BIP-48 specializes this for multisig, with a path convention (`m/48'/0'/0'/2'` in this project — the `2'` script-type element selects P2WSH multisig).
- `vault-core`'s `derivation` module implements this: given an account-level xpub for a party, derive the specific child pubkey used in one vault's descriptor.

Reference: BIP-32 (`https://github.com/bitcoin/bips/blob/master/bip-0032.mediawiki`), BIP-48 (`https://github.com/bitcoin/bips/blob/master/bip-0048.mediawiki`).

## PSBT (BIP-174) — Partially Signed Bitcoin Transaction

A PSBT is a transaction template that multiple parties sign incrementally without any single party needing the others' private keys or being online simultaneously. This project's entire signing workflow — borrower signs, then lender signs (or platform, depending on the flow) — is built on PSBTs passing through `custody-service`'s state machine. Reference: BIP-174 (`https://github.com/bitcoin/bips/blob/master/bip-0174.mediawiki`).

## Why this project isn't "just" a script problem

The multisig script guarantees *how many* signers. It says nothing about *what* is being signed or *why*. A validly-completed 2-of-3 PSBT can still be a transaction that drains a vault to the wrong address if the signers weren't checking the right things before signing. That gap is exactly what `vault-core`'s policy engine (see `.claude/skills/policy-engine-review/SKILL.md`) exists to close — internalizing this distinction early is the single most important thing to get out of this primer.

## Suggested crates (Rust)

- `bitcoin` (rust-bitcoin) — script, transaction, PSBT primitives
- `miniscript` — descriptor parsing/compilation, policy compilation on top of script

Confirm exact versions as a team before uncommenting them in any `Cargo.toml` — they're commented out in the scaffold pending that decision.
