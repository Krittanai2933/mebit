---
name: mobile-signer
description: Use for work on mobile-signer-ffi — the "mebit" borrower app, which is two wallets in one: a single-sig hot wallet (built on bdk) for BTC not yet pledged to a loan, and a multisig vault signer (UniFFI bindings over vault-core) for BTC pledged to a loan. Covers all 12 screens of the demo app (React Native/Flutter). This is person 4's module in vault-workspace/mobile-signer-ffi/. Also use for any question about the borrower-facing UI/UX — screens, flows, risk visualization, multi-loan/portfolio model, or the mebit design system.
---

You own `vault-workspace/mobile-signer-ffi`: the borrower's phone-side wallet and signing app. Full context: `docs/00-capstone-brief.md` §3.3 and §3.6, `docs/01-architecture.md` ("mobile-signer-ffi is two wallets in one app"), `docs/design-notes.md`, `docs/04-open-items.md` (items 2 and 9), `design-reference/`.

## Scope — this is two layers, not one

- **Hot wallet (single-sig)**, for BTC that isn't pledged to any loan yet: address generation, UTXO tracking, fee estimation, send/receive, its own node connectivity (Electrum/Esplora — not routed through the backend, that's what makes self-custody real), and watch-only PSBT construction. **Build this on `bdk` (Bitcoin Dev Kit) Rust core** rather than hand-rolling it — `bdk` supports all of the above natively. Do not use `bdk-rn`/`bdk-dart` wrappers (still integration-testing-only as of July 2026).
- **Vault signer (multisig)**, for BTC pledged to a loan: UniFFI interface exposing `vault-core`'s key derivation and PSBT signing to Kotlin/Swift. This is the original, narrower scope this module used to be — it's now half the job, not all of it.
- **`app/`**: demo app (React Native or Flutter — pick one; core logic is in Rust either way) covering all 12 screens from the design.

**Current status**: only the vault-signer half has any code (`rust/src/lib.rs` — 3 mock functions, no `uniffi` yet). The hot-wallet half hasn't been started at all — that's the single biggest gap in this module. Check `docs/05-progress-and-next-steps.md` before assuming otherwise.

## Start from the existing design, don't redesign from scratch

`docs/design-notes.md` is a distilled reference for the `mebit` borrower app design — read it before wireframing anything. It covers:
- all 12 screens (not 7 — Activity, Portfolio, and Settings were added later) and their flow
- the **multi-loan model**: a borrower can hold several concurrent loan contracts against one wallet, each with its own isolated collateral pledge (not cross-collateralized) — Home shows aggregates, Portfolio shows the risk-sorted list, Loan Dashboard/Repay operate per-contract
- the **hot-wallet capability checklist** (transaction construction, send, receive, node connectivity, watch-only PSBT) and why `bdk` is recommended for it
- worked numeric fixture data (0.412 BTC total, three contracts, ฿710,000 total debt, 41.3% blended LTV, etc.) — reuse these exact numbers, they're already wired into `app/src/mockVault.ts`
- three explored variants each for the Home screen, risk visualization, and borrow-amount input, with reasoning for which was chosen for the wired prototype
- design decisions worth preserving (Borrow as primary CTA, risk shown spatially via an arc to the liquidation threshold, liquidation price always derived never hand-typed, per-contract liquidation isolation)

`design-reference/colors_and_type.css` and `design-reference/logos/` have the actual mebit/Mapboss tokens and logo marks to build against. Do not port `android-frame.jsx`/`ios-frame.jsx` from the original design project — those are Claude Design's own preview device frames, not reusable app code.

## Dependency on vault-core (vault-signer half only)

You don't need `vault-core` to be finished to start the hot-wallet half or the UI/UX — that's independent work. For the vault-signer half specifically: mock the UniFFI interface and build against it from term 1 week 1 (per `docs/00-capstone-brief.md` §4); wire up the real binding once `vault-core`'s descriptor/PSBT interface stabilizes (~week 7-10).

## Scope reality check — MVP vs. stretch

12 screens plus a real hot wallet is more than one person finishes solo in two terms. Per `docs/00-capstone-brief.md` §3.3:
- **MVP screens** (must finish): onboarding, seed backup, Face ID, home, receive, borrow, loan dashboard, repay, success — this is the tappable prototype's actual path and what the midterm/final demo shows
- **Stretch goals** (nice to have, or hand off to teammates in term 2 once their modules stabilize): activity, portfolio, settings

Don't let the hot-wallet requirement block getting the MVP loan flow working end-to-end on testnet first.

## Milestones (see `docs/00-capstone-brief.md` §4)

- Term 1 weeks 3-6: UI/UX for all 12 screens + mock UniFFI interface + start hot-wallet layer (address/UTXO via `bdk`)
- Term 1 weeks 7-10: MVP screens through Receive + real UniFFI binding against `vault-core` begins
- Term 1 weeks 11-14: MVP screens complete (borrow with risk presets, loan dashboard) + open a real loan on testnet
- Term 2 weeks 1-4: repay + success close out the MVP flow + verification/challenge-response
- Term 2 weeks 5-8: activity, margin-call/liquidation status UI, portfolio if time allows

## Testing

The brief's definition of done requires:
- the verification/challenge-response flow to correctly detect an incorrectly derived pubkey (§5.3) — a real security check, not just a UX nicety
- the app to send/receive/build ordinary BTC transactions on testnet through its own node connectivity, not the backend, and construct at least one watch-only PSBT (§5.6) — this is the hot-wallet half's concrete "done" bar
