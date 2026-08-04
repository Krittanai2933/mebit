# mebit — Bitcoin self-custody lending, capstone workspace

A 2-of-3 multisig Bitcoin-collateralized lending system: a borrower deposits BTC, draws a THB loan against it, and no single party (borrower, platform, or lender) can move the collateral alone. Built as a university capstone project — 4 students, 2 semesters, one team owning the system end-to-end.

Start here:

- **[`docs/00-capstone-brief.md`](docs/00-capstone-brief.md)** — the original, authoritative project brief (Thai). Read this first.
- **[`docs/01-architecture.md`](docs/01-architecture.md)** — distilled English architecture reference.
- **[`docs/02-roles-and-responsibilities.md`](docs/02-roles-and-responsibilities.md)** — who holds which key, who co-signs with whom and when (Thai).
- **[`docs/03-flows.md`](docs/03-flows.md)** — step-by-step: onboarding, open loan, repayment, liquidation, fallback (Thai).
- **[`docs/04-open-items.md`](docs/04-open-items.md)** — design/business decisions not yet finalized — check before assuming a number or rule is locked in (Thai).
- **[`docs/design-notes.md`](docs/design-notes.md)** — the borrower app's UI/flow reference (from the `mebit` mobile app design), for the `mobile-signer-ffi` team.
- **[`docs/05-progress-and-next-steps.md`](docs/05-progress-and-next-steps.md)** — current status and full to-do list per module (Thai). Update this as milestones land — it's a living document, not a snapshot.

## Layout

```
mebit/
├── docs/                 # brief, architecture, design notes — read these first
├── design-reference/     # mebit/Mapboss design tokens + logos for the mobile app
├── vault-workspace/      # the actual system — 5 Rust crates (some modules share an owner, see Team split)
│   ├── vault-core/           # descriptor, key derivation, PSBT, policy engine — the critical path
│   ├── custody-service/      # platform-side gRPC/REST + signing-request state machine
│   ├── mobile-signer-ffi/    # borrower app: hot wallet (bdk) + UniFFI vault signer, two layers in one
│   ├── lender-signer-cli/    # offline signing CLI for the lender/fund rep
│   └── monitor-service/      # LTV monitoring + liquidation triggers
├── .claude/
│   ├── agents/           # one subagent per module — responsibilities, tech, dependencies
│   └── skills/           # shared knowledge: Bitcoin fundamentals, policy review, testnet workflow, design tokens
└── README.md             # this file
```

Every crate under `vault-workspace/` has its own `README.md` with its owner, dependencies, and responsibilities — start there once you know which module you're on.

## Team split

5 modules, **4 people** — `custody-service`, `lender-signer-cli`, and `monitor-service` are bundled onto one person since the latter two are small and don't need `vault-core` early; `vault-core` keeps its 2-person split on purpose (see below). See `docs/00-capstone-brief.md` §3.6 for the full reasoning and `docs/00-capstone-brief.md` §4 for the two-semester timeline. Short version:

| Module | Owner |
|---|---|
| `vault-core` (descriptor + derivation) | person 1 |
| `vault-core` (PSBT + policy engine) | person 2 |
| `custody-service` + `lender-signer-cli` + `monitor-service` | person 3 |
| `mobile-signer-ffi` (hot wallet + vault signer — the largest single scope in the team) | person 4 |

`vault-core` is the critical path — every other module depends on it. Its policy engine is the single highest-risk piece of code in the project, which is why it kept a dedicated second owner even when the team shrank from 5 to 4 (see `.claude/skills/policy-engine-review/SKILL.md`). `mobile-signer-ffi` carries the most work of any single module now that its scope includes a full `bdk`-based hot wallet on top of the multisig vault signer — see `docs/01-architecture.md` and `docs/00-capstone-brief.md` §3.3 for why, and lean on the MVP-vs-stretch screen split there if the team is short on time. Person 3 juggles three modules — timed to stagger rather than overlap (see the `platform-services` agent), but worth checking in on more often than the others.

## Working with Claude Code on this project

`.claude/agents/` has one subagent per person/scope: `vault-core-descriptor`, `vault-core-policy`, `platform-services` (custody-service + lender-signer-cli + monitor-service), and `mobile-signer`. Use the one matching what you're working on — e.g. from the `vault-core` directory, or by asking for "the vault-core agent." `.claude/skills/` has knowledge shared across modules (Bitcoin fundamentals, the policy-engine adversarial review checklist, the testnet workflow, and the mebit design tokens).

## Explicitly out of scope

Legal/regulatory compliance and the lender's fund/NAV ledger are the company's responsibility, not this team's — see `docs/00-capstone-brief.md` §1.
