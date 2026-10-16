# BLE UX integration

The authorized integration advances `phase0_No.6` from `360febfd394aef4f11f67ab079c00809da50445d` to the reviewed friend tip `cee0733f78325f1415793626135aa34d352e374b`. All six upstream commits are present. Wallet edits remain uncommitted and unstaged. The labeled backup stash is retained at `055cf102609ac5f27764f8330cb26061e489b1ba`.

The two conflicts were resolved by combining both sides. The Trezor example keeps its wallet dependencies and the upstream Tokio `test-util` feature. The Jade fake device keeps the registration fields and request history, plus `awaiting_pin`, initialized to false. The production pinserver-cancellation and malformed-pairing retry changes merged automatically with the wallet's address-verification and signing additions.

Backup metadata covers all 29 source paths. Every path was restored. Wallet source outside the seven upstream overlaps remained byte-identical; appended audit updates to decisions.tsv and todo.md are explicitly classified separately. Root-index conflicts were cleared and the previously unstaged state was restored. No push or new wallet commit was made.

| Check | Result | Evidence under target/wallet-e2e/ble-ux-integration |
| --- | --- | --- |
| Core regression suite | PASS, 123 tests | core-regressions.log |
| Trezor BLE regression suite | PASS, 13 tests | trezor-regressions.log |
| Workspace suite | PASS, 174 tests | workspace-tests.log |
| Clippy, warnings denied | PASS | clippy.log |
| Both example builds | PASS | example-builds.log |
| Targeted formatting and whitespace | PASS | targeted-format.log; whitespace.log |
| Full workspace formatting | Existing failure in four unchanged packages | workspace-format.log |
| Full two-process offline CLI driver | PASS, all 7,315 cases in each process with identical results | cli-verification.log; results.json |
| Source preservation | PASS | backup.json; preservation.json |

The first locked test invocation stopped before compilation with Cargo's lockfile-update refusal. An offline Cargo check without `--locked` passed. Subsequent locked checks passed. The saved pre-check lockfile and final lockfile are byte-identical, as recorded in lockfile.json; no dependency version update is claimed from this check.

The two processes share digest `1e084bbf7182a3da749752498280785e21ee8bea86e2bd736234258b7fac0271`, unchanged from before integration. The driver also verified the independent matrix oracle, reporting, repeated-process determinism, and CLI rejection paths.

The narrow comment review proposed zero deletions and found no unresolved issue. Independent read-only review, configured as `gpt-5.6-sol`, returned VERIFIED for the offline integration. It confirmed the exact target commit, retained backup, unstaged wallet work, both conflict resolutions, source preservation, complete verification logs, and the corrected lockfile evidence. No integration correctness or evidence flags remain.

All verification is offline. Actual BLE recovery and testnet4 spend acceptance remain unverified; the live result stays 0/7,315. Full workspace formatting retains its documented unchanged-package failures.

## Later E2E status update — 2026-10-16

The preceding status describes the integration checkpoint and has since been superseded. The refreshed local report contains eight Jade/Hot Wallet single-sig PASS checkpoints and five multisig PASS checkpoints; all five multisig spends were confirmed at block 156696. Four Trezor single-sig confirmations are friend-reported rather than locally checkpointed. The offline run passes 7,315/7,315 with digest `951dc04a35aa056826e4bee3320f1224257ccf3bd7011bd7f883ac78a44eeec7`. Overall live acceptance is BLOCKED at 13/7,315; see [`verification.md`](verification.md).
