# mebit — project guide for Claude Code

Bitcoin self-custody lending capstone project. 2-of-3 multisig vault (borrower / platform / lender); no single party can move collateral alone. Full context: [`docs/00-capstone-brief.md`](docs/00-capstone-brief.md) (source of truth, Thai) and [`docs/01-architecture.md`](docs/01-architecture.md) (English distillation).

## Layout

- `docs/` — brief, architecture, design notes
- `design-reference/` — mebit/Mapboss design tokens + logos (for `mobile-signer-ffi`)
- `vault-workspace/` — the Rust workspace; `cargo build`/`cargo test` from here. Five crates, one per module — each has its own `README.md` with owner/deps/responsibilities. Don't run cargo commands from the repo root, there's no top-level `Cargo.toml`.
- `.claude/agents/` — one subagent per module (`vault-core`, `custody-service`, `mobile-signer`, `lender-cli`, `monitor-service`). Prefer the matching agent when working inside a specific module.
- `.claude/skills/` — shared knowledge usable from any module: `bitcoin-fundamentals`, `policy-engine-review`, `testnet-workflow`, `design-tokens`.

## Module dependency order

`vault-core` has no in-workspace dependencies and is depended on by everything else (directly, or via shared invariants for `monitor-service`). Treat changes to `vault-core`'s public interface (descriptor/PSBT/derivation signatures) as breaking changes — check who else in `vault-workspace/` calls them before changing signatures.

## The policy engine is security-critical

`vault-core/src/lib.rs`'s `policy` module is what stops a validly co-signed PSBT from paying the wrong output. It is the single highest-risk piece of code in this project — a bug here means a key signs a transaction that drains a vault incorrectly, not a UI glitch. Any change to it:

- needs review from both `vault-core` owners (see `docs/00-capstone-brief.md` §3.6), not just the person who wrote it
- should come with new adversarial test cases, not just happy-path tests — see `.claude/skills/policy-engine-review/SKILL.md`

## Conventions

- Liquidation prices and LTV are always **derived** from loan/collateral state, never hardcoded or hand-entered — see the correction documented in `docs/design-notes.md`.
- State machine transitions (in `custody-service`) must be idempotent — resubmitting the same signing request must not double-spend.
- Don't build UniFFI/mobile app scaffolding speculatively ahead of `vault-core`'s interface stabilizing — mock it instead (see `vault-workspace/mobile-signer-ffi/README.md`).

## Out of scope

Legal/regulatory compliance (SEC/BOT) and the lender's fund/NAV ledger are the company's responsibility, not this codebase's. Don't design around solving either.
