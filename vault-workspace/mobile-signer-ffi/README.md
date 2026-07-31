# mobile-signer-ffi

**Owner**: person 4 — `docs/00-capstone-brief.md` §3.6.

**Depends on**: `vault-core` for the real signing/derivation logic (mock it in `rust/` until that interface stabilizes — UI/UX work can start immediately, no need to wait).

## Layout

```
mobile-signer-ffi/
├── rust/    # UniFFI bindings over vault-core -> Kotlin/Swift
└── app/     # Demo app (React Native or Flutter — pick one, core logic lives in Rust either way)
```

## Responsibilities

- Wrap `vault-core`'s key derivation and PSBT signing behind a UniFFI interface callable from Kotlin/Swift
- Demo app covering: onboarding (generate/import seed), open a new loan, verify the vault on-chain, sign a collateral return

## Design reference — don't design from scratch

`../../docs/design-notes.md` has the full screen list, flow, worked numeric example, and the three explored variants each for the Home screen, the risk display, and the borrow-amount input, distilled from the `mebit` mobile app design. `../../design-reference/` has the color/type tokens and logos. Read both before wireframing.

## Getting started

`rust/`:
```
cargo build -p mobile-signer-ffi
```

`app/` is an Expo (React Native + TypeScript) app — chosen so the team has something to run and click through immediately instead of starting from a blank project:
```
cd vault-workspace/mobile-signer-ffi/app
npm start        # then press w for web, i for iOS simulator, a for Android
```

It covers all 12 screens from `docs/design-notes.md` — Onboarding → Seed backup → Face ID → Home → Receive → Borrow → Loan Dashboard → Repay → Success, plus Activity, Portfolio, and Settings reachable from the bottom nav — styled with the tokens in `design-reference/`. The wallet supports **multiple concurrent loan contracts** (each pledging its own isolated slice of BTC as collateral, per `docs/design-notes.md`'s multi-loan model), so Portfolio is where you see all of them and Loan Dashboard/Repay operate on whichever one you tapped into.

All numbers come from `app/src/mockVault.ts`, a plain-TypeScript stand-in for the real vault-core/UniFFI calls (no cryptography, no native module) — swap its functions for real UniFFI bindings once `rust/`'s interface exists. See `app/src/mockVault.ts`'s header comment for why it's mocked rather than wired up already.

Not built yet (left as follow-up work, not blocking the skeleton): react-navigation (current nav is a simple screen-state switch in `App.tsx`), the verification/challenge-response flow, and real margin-call/liquidation triggers (term 2 scope per `docs/00-capstone-brief.md` §4).
