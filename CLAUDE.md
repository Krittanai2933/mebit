# mebit — project guide for Claude Code

Bitcoin self-custody product for Thai users, built by a 4-person capstone team. Originally scoped as a lending product (2-of-3 multisig vault: borrower / platform / lender; no single party can move collateral alone) — as of 2026-08-25 the priority flipped to **wallet-first**: ship general self-custody (M-of-N, any key holder) before buy-sell and lending. Lending is still a real leg of the product, layered on top of the wallet once it's proven.

Start with [`docs/07-product-vision-mebit.md`](docs/07-product-vision-mebit.md) (north star, current framing) and [`docs/09-wallet-mvp-buildplan.md`](docs/09-wallet-mvp-buildplan.md) (what's actually being built right now, phase 0-3). [`docs/00-README.md`](docs/00-README.md) indexes the full doc set. The original lending-specific spec — [`docs/00-capstone-brief.md`](docs/00-capstone-brief.md) (source of truth, Thai) and [`docs/01-architecture.md`](docs/01-architecture.md) (English distillation) — is still accurate for the lending leg, just no longer the whole picture.

## Layout

- `docs/` — numbered doc set (`00`-`09`); see `docs/00-README.md` for what's current vs. lending-specific
- `design-reference/` — mebit/Mapboss design tokens + logos (for `mobile-signer-ffi`)
- `vault-workspace/` — the Rust workspace; `cargo build`/`cargo test` from here. Five crates (`vault-core`, `custody-service`, `lender-signer-cli`, `mobile-signer-ffi`, `monitor-service`), each with its own `README.md` with owner/deps/responsibilities. Don't run cargo commands from the repo root, there's no top-level `Cargo.toml`.
- `.claude/agents/` — one subagent per module, split across the 4-person team: `vault-core-descriptor`, `vault-core-policy` (both work in `vault-core/src/lib.rs`), `platform-services` (`custody-service` + `lender-signer-cli` + `monitor-service`), `mobile-signer` (`mobile-signer-ffi`). Prefer the matching agent when working inside a specific module.
- `.claude/skills/` — shared knowledge usable from any module: `bitcoin-fundamentals`, `policy-engine-review`, `testnet-workflow`, `design-tokens`.

## Module dependency order

`vault-core` has no in-workspace dependencies and is depended on by everything else (directly, or via shared invariants for `monitor-service`). Treat changes to `vault-core`'s public interface (descriptor/PSBT/derivation signatures) as breaking changes — check who else in `vault-workspace/` calls them before changing signatures.

`vault-core` also has `keys` (shared `VaultKey`/`KeySourceType`/`HwVendor` data model, using real `bitcoin::bip32` types) and `hw` (empty placeholder for Jade/Trezor Safe 7 clients) — added 2026-08-25 as the first step of generalizing the crate from fixed 2-of-3 lending roles to M-of-N. These are additive and lower-risk than `descriptor`/`derivation`/`psbt`/`policy`; don't let work here bleed into changing those modules' existing logic.

## The policy engine is security-critical

`vault-core/src/lib.rs`'s `policy` module is what stops a validly co-signed PSBT from paying the wrong output. It is the single highest-risk piece of code in this project — a bug here means a key signs a transaction that drains a vault incorrectly, not a UI glitch. Any change to it:

- needs review from both `vault-core` owners (see `docs/00-capstone-brief.md` §3.6), not just the person who wrote it
- should come with new adversarial test cases, not just happy-path tests — see `.claude/skills/policy-engine-review/SKILL.md`

## Conventions

- Liquidation prices and LTV are always **derived** from loan/collateral state, never hardcoded or hand-entered — see the correction documented in `docs/design-notes.md`.
- State machine transitions (in `custody-service`) must be idempotent — resubmitting the same signing request must not double-spend.
- Don't build UniFFI/mobile app scaffolding speculatively ahead of `vault-core`'s interface stabilizing — mock it instead (see `vault-workspace/mobile-signer-ffi/README.md`).
- When a doc spec and the existing code disagree (e.g. derivation path format, field types) and it's not obviously a typo, don't pick one yourself — flag it as an open item in `docs/04-open-items.md` and ask, per the pattern already used there.

## Out of scope

Legal/regulatory compliance (SEC/BOT) and the lender's fund/NAV ledger are the company's responsibility, not this codebase's. Don't design around solving either.
