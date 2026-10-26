# mebit — project guide for coding agents

Bitcoin self-custody product for Thai users, built by a 4-person capstone team. Originally scoped as a lending product (2-of-3 multisig vault: borrower / platform / lender; no single party can move collateral alone) — as of 2026-08-25 the priority flipped to **wallet-first**: ship general self-custody (M-of-N, any key holder) before buy-sell and lending. Lending is still a real leg of the product, layered on top of the wallet once it's proven.

Start with [`docs/07-product-vision-mebit.md`](docs/07-product-vision-mebit.md) (north star, current framing) and [`docs/09-wallet-mvp-buildplan.md`](docs/09-wallet-mvp-buildplan.md) (what's actually being built right now, phase 0-3). [`docs/00-README.md`](docs/00-README.md) indexes the full doc set. The original lending-specific spec — [`docs/00-capstone-brief.md`](docs/00-capstone-brief.md) (source of truth, Thai) and [`docs/01-architecture.md`](docs/01-architecture.md) (English distillation) — is still accurate for the lending leg, just no longer the whole picture.

## Layout

- `docs/` — numbered doc set (`00`-`09`); see `docs/00-README.md` for what's current vs. lending-specific
- `design-reference/` — mebit/Mapboss design tokens + logos (for `mobile-signer-ffi`)
- `vault-workspace/` — the Rust workspace; `cargo build`/`cargo test` from here. Seven crates (`vault-core`, `custody-service`, `lender-signer-cli`, `mobile-signer-ffi`, `monitor-service`, `jade-ble`, `trezor-ble`), each with its own `README.md` with owner/deps/responsibilities. `jade-ble` and `trezor-ble` sit outside `default-members` (btleplug needs libdbus on Linux): reach them with `-p jade-ble` / `-p trezor-ble`. vault-core's Trezor protocol is behind its non-default `trezor` feature (`cargo test -p vault-core --features trezor`). Don't run cargo commands from the repo root, there's no top-level `Cargo.toml`.
- `.claude/`, `.agents/skills` and `scripts/` hold the agent context and its check. See Agent context below.

## Agent context

Module briefs live in `.claude/agents/` and skills in `.claude/skills/`. Claude Code loads both itself and runs each brief as a subagent, so prefer the matching one when working inside a module. Any other agent reads the matching brief before working in that module, and a skill when its description fits the task. Edit skills under `.claude/skills/` only, because `.agents/skills` is a link to it for tools that read just that path. Keep module rules out of nested `AGENTS.md` files, because Claude Code ignores them.

| Brief | Covers |
|---|---|
| `.claude/agents/vault-core-descriptor.md` | `vault-core/src/lib.rs`, descriptor and derivation |
| `.claude/agents/vault-core-policy.md` | `vault-core/src/lib.rs`, PSBT and policy |
| `.claude/agents/platform-services.md` | `custody-service`, `lender-signer-cli`, `monitor-service` |
| `.claude/agents/mobile-signer.md` | `mobile-signer-ffi` |

Skills (each one's `description` says when to use it):

- `.claude/skills/bitcoin-fundamentals/SKILL.md`
- `.claude/skills/policy-engine-review/SKILL.md`
- `.claude/skills/testnet-workflow/SKILL.md`
- `.claude/skills/design-tokens/SKILL.md`
- `vault-workspace/mobile-signer-ffi/app/.claude/skills/verify/SKILL.md` drives the mobile app on an emulator or simulator and applies only under `vault-workspace/mobile-signer-ffi/app`. Agents that read only `.agents/skills` do not list it, so read it before verifying app changes.

## Module dependency order

`vault-core` has no in-workspace dependencies and is depended on by everything else (directly, or via shared invariants for `monitor-service`). Treat changes to `vault-core`'s public interface (descriptor/PSBT/derivation signatures) as breaking changes — check who else in `vault-workspace/` calls them before changing signatures.

`vault-core` also has `keys` (shared `VaultKey`/`KeySourceType`/`HwVendor` data model, using real `bitcoin::bip32` types) and `hw` (hardware-wallet clients, each a protocol with no I/O: `hw::jade` for the Jade, transport crate `jade-ble`; `hw::trezor` for the Trezor Safe 7 and no other Trezor model, transport crate `trezor-ble`; `hw::psbt_check`, shared by both, checks what a device signed) — added 2026-08-25 as the first step of generalizing the crate from fixed 2-of-3 lending roles to M-of-N. These are additive and lower-risk than `descriptor`/`derivation`/`psbt`/`policy`; don't let work here bleed into changing those modules' existing logic.

## The policy engine is security-critical

`vault-core/src/lib.rs`'s `policy` module is what stops a validly co-signed PSBT from paying the wrong output. It is the single highest-risk piece of code in this project — a bug here means a key signs a transaction that drains a vault incorrectly, not a UI glitch. Any change to it:

- needs review from both `vault-core` owners (see `docs/00-capstone-brief.md` §3.6), not just the person who wrote it
- should come with new adversarial test cases, not just happy-path tests — see `.claude/skills/policy-engine-review/SKILL.md`

## Conventions

- Liquidation prices and LTV are always **derived** from loan/collateral state, never hardcoded or hand-entered — see the correction documented in `docs/design-notes.md`.
- State machine transitions (in `custody-service`) must be idempotent — resubmitting the same signing request must not double-spend.
- Don't build UniFFI/mobile app scaffolding speculatively ahead of `vault-core`'s interface stabilizing — mock it instead (see `vault-workspace/mobile-signer-ffi/README.md`).
- When a doc spec and the existing code disagree (e.g. derivation path format, field types) and it's not obviously a typo, don't pick one yourself — flag it as an open item in `docs/04-open-items.md` and ask, per the pattern already used there.
- After editing `AGENTS.md`, `CLAUDE.md`, a skill or a brief, run `python3 -I scripts/check-agent-context.py`. It exits 1 and names the fix for each problem.

## Out of scope

Legal/regulatory compliance (SEC/BOT) and the lender's fund/NAV ledger are the company's responsibility, not this codebase's. Don't design around solving either.
