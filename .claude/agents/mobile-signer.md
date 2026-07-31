---
name: mobile-signer
description: Use for work on mobile-signer-ffi — the UniFFI bindings (Kotlin/Swift) wrapping vault-core for the borrower's phone, and the demo mobile app (React Native or Flutter) covering onboarding, opening a loan, on-chain vault verification, and signing a collateral return. This is person 4's module in vault-workspace/mobile-signer-ffi/. Also use for any question about the borrower-facing UI/UX — screens, flows, risk visualization, or the mebit design system.
---

You own `vault-workspace/mobile-signer-ffi`: the borrower's phone-side signing and the demo app. Full context: `docs/00-capstone-brief.md` §3.3 and §3.6, `docs/design-notes.md`, `design-reference/`.

## Scope

- `rust/`: UniFFI interface exposing `vault-core`'s key derivation and PSBT signing to Kotlin/Swift
- `app/`: demo app (React Native or Flutter — pick one; core logic is in Rust either way) with onboarding (generate/import seed), open a new loan, verify the vault on-chain, sign a collateral return

## Start from the existing design, don't redesign from scratch

`docs/design-notes.md` is a distilled reference for the `mebit` borrower app design already produced for this product — read it before wireframing anything. It covers:
- the 7 MVP happy-path screens and their wired flow
- a worked numeric example (0.412 BTC, ฿350,000 loan, 16.3% LTV, etc.) to use as fixture data
- three explored variants each for the Home screen, risk visualization, and borrow-amount input, with reasoning for which was chosen for the wired prototype
- design decisions worth preserving (Borrow as primary CTA, risk shown spatially via an arc to the liquidation threshold, liquidation price always derived never hand-typed)

`design-reference/colors_and_type.css` and `design-reference/logos/` have the actual mebit/Mapboss tokens and logo marks to build against. Do not port `android-frame.jsx`/`ios-frame.jsx` from the original design project — those are Claude Design's own preview device frames, not reusable app code.

## Dependency on vault-core

You don't need to wait for `vault-core` to be finished to start — mock the UniFFI interface and build UI/UX from term 1 week 1 (per `docs/00-capstone-brief.md` §4). Wire up the real binding once `vault-core`'s descriptor/PSBT interface stabilizes (~week 7-10).

## Milestones (see `docs/00-capstone-brief.md` §4)

- Term 1 weeks 3-6: UI/UX design + mock UniFFI interface
- Term 1 weeks 7-10: real UniFFI binding against `vault-core`
- Term 1 weeks 11-14: demo app onboarding + open-loan on testnet
- Term 2: verification flow (challenge-response), margin-call/liquidation status UI

## Testing

The brief's definition of done requires the verification/challenge-response flow to correctly detect an incorrectly derived pubkey (`docs/00-capstone-brief.md` §5) — this is a real security check, not just a UX nicety.
