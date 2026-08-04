# mobile-signer-ffi

**Owner**: person 4 — `docs/00-capstone-brief.md` §3.6.

**Depends on**: `vault-core` for the vault-signer half (mock it in `rust/` until that interface stabilizes — UI/UX work can start immediately, no need to wait). Recommended to depend on `bdk` (Bitcoin Dev Kit, Rust core) for the hot-wallet half — see below.

## This is two wallets in one app

The borrower's BTC is always either **free** (not pledged to any loan) or **pledged** (locked in a specific loan's 2-of-3 vault). That means this module is genuinely two layers, not one:

1. **Hot wallet (single-sig)** — for free BTC. A real Bitcoin wallet: address generation, UTXO tracking, fee estimation, send/receive, its own node connectivity (Electrum/Esplora, not routed through the backend), and watch-only PSBT construction. **Recommended: build on `bdk` Rust core** rather than hand-rolling this — it supports all of the above natively. Do not use `bdk-rn`/`bdk-dart` wrappers (integration-testing-only as of July 2026).
2. **Vault signer (multisig)** — for pledged BTC. The original scope: wrap `vault-core`'s key derivation and PSBT signing behind UniFFI.

Both need to read as one balance on Home/Portfolio in the UI, but the key/signing logic stays clearly separated underneath — see `../../docs/01-architecture.md`'s "mobile-signer-ffi is two wallets in one app" section and `../../docs/04-open-items.md` (items 2 and 9) for the open questions here.

**Current status: only the vault-signer half has any code.** The hot-wallet half hasn't been started — see `../../docs/05-progress-and-next-steps.md`.

## Layout

```
mobile-signer-ffi/
├── rust/    # UniFFI bindings over vault-core (vault signer) -> Kotlin/Swift.
│            # Hot-wallet / bdk code belongs here too once started.
└── app/     # Demo app (React Native or Flutter — pick one, core logic lives in Rust either way)
```

## Responsibilities

- Hot wallet: transaction construction, send, receive, node connectivity, watch-only PSBT — see the wallet capability checklist in `../../docs/design-notes.md`
- Vault signer: wrap `vault-core`'s key derivation and PSBT signing behind a UniFFI interface callable from Kotlin/Swift
- Demo app covering all 12 screens from the design (see below) — but scope MVP screens first, the rest are stretch goals

## Design reference — don't design from scratch

`../../docs/design-notes.md` has the full screen list, multi-loan model, the hot-wallet requirement and capability checklist, worked numeric examples, and the three explored variants each for the Home screen, the risk display, and the borrow-amount input, distilled from the `mebit` mobile app design. `../../design-reference/` has the color/type tokens and logos. Read both before wireframing.

## Getting started

`rust/`:
```
cargo test -p mobile-signer-ffi
```

Has 3 functions already — `derive_borrower_pubkey`, `compute_vault_address`, `sign_psbt` — wired to `vault-core`'s mock descriptor/derivation/PSBT types, with 5 passing tests. This is the **vault-signer half only**. Deliberately **not** using the `uniffi` crate/macros yet (same "don't build the binding tooling speculatively" reasoning as elsewhere in this project) — plain Rust functions with FFI-friendly signatures so the shape is already right. Annotate with `#[uniffi::export]` and uncomment the `uniffi` dependency in `Cargo.toml` once `vault-core`'s real interface lands. The **hot-wallet half** (`bdk`-based) hasn't been started — that's the single biggest gap in this module right now.

`app/` is an Expo (React Native + TypeScript) app — chosen so the team has something to run and click through immediately instead of starting from a blank project:
```
cd vault-workspace/mobile-signer-ffi/app
npm start        # then press w for web, i for iOS simulator, a for Android
```

It covers all 12 screens from `docs/design-notes.md` — Onboarding → Seed backup → Face ID → Home → Receive → Borrow → Loan Dashboard → Repay → Success, plus Activity, Portfolio, and Settings reachable from the bottom nav — styled with the tokens in `design-reference/`. The wallet supports **multiple concurrent loan contracts** (each pledging its own isolated slice of BTC as collateral, per `docs/design-notes.md`'s multi-loan model), so Portfolio is where you see all of them and Loan Dashboard/Repay operate on whichever one you tapped into.

All numbers come from `app/src/mockVault.ts`, a plain-TypeScript stand-in for the real vault-core/UniFFI calls *and* the eventual hot-wallet/`bdk` calls (no cryptography, no native module, no real UTXOs) — swap its functions for real bindings once `rust/`'s interfaces exist. See `app/src/mockVault.ts`'s header comment for why it's mocked rather than wired up already.

Not built yet (left as follow-up work, not blocking the skeleton): the hot-wallet layer entirely (see above), react-navigation (current nav is a simple screen-state switch in `App.tsx`), the verification/challenge-response flow, and real margin-call/liquidation triggers (term 2 scope per `docs/00-capstone-brief.md` §4).
