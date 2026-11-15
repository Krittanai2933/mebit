# Testnet4 wallet verification

- [x] Read the Principles section of the poteto-mode skill.
- [x] Phase A: Frame.
- [x] Phase B: Design the workflow.
- [x] Phase C: Run the loop.
- [x] Generate the canonical 7,315-case manifest and independent derivation checks.
- [x] Extend shared signature verification for BIP86 and unsupported-policy rejection.
- [x] Extend Trezor single-sig preparation and both vendors' address RPCs.
- [x] Implement private enrollment, atomic evidence, reservations, and serial workers.
- [x] Implement real prevout validation, signing, finalization, broadcast, and resume.
- [x] Drive offline CLI and rejection paths through both examples.
- [x] Phase D: Keep the audit trail.
- [x] Phase E: Verify and hand back.
- [x] Complete workspace tests, Clippy, both builds, and targeted formatting.
- [x] Recheck canonical confirmations during reporting and reject stale PASS evidence.
- [x] Complete independent review of the implementation and audit trail.
- [ ] Confirm every live row on testnet4. Still BLOCKED; see the current status note below and `verification.md` for confirmed, pending, and friend-reported results.

## Latest live checkpoint status — 2026-10-17

The generated JSON was refreshed on 2026-10-17 13:45 +07:00. Fourteen local result records remain PASS: four Jade single-sig, four Hot Wallet single-sig, and six multisig cases. The newest multisig result is the Jade + hot + remote Trezor 3-of-3 case, saved as confirmed at block 156802. This refresh could not resolve mempool.space; `report.json` therefore shows `live_pass: 0`, `recorded_live_pass: 14`, and DNS errors on the fresh checks. Four Trezor single-sig confirmations remain friend-reported and excluded from this workspace's local JSON. Overall acceptance remains BLOCKED; rerun the report once DNS/network access is restored.

The settled user plan supplies the architecture. Skip a second architecture arena because it would repeat the planning pass. Three isolated workers own the verifier, Trezor protocol, and Jade protocol. The coordinator owns manifests, enrollment, evidence, and network execution. Each unit ends with targeted verification.

Throughput checkpoint: count rows and exercise one case from each script family before testing all source compositions. Compare results against independently derived scripts and observed CLI output. The original implementation request did not authorize live transactions; later operator-authorized testnet cases are listed above and in `verification.md`.

At the original implementation checkpoint, workspace tests passed 165 tests; Clippy with warnings denied, both example builds, targeted formatting, and the CLI driver passed. Each example process signed and finalized all 7,315 designated software subsets with the then-current digest. Independent review corrected public-result integrity and audited the decision trail. Full workspace formatting still fails in four unchanged packages, as recorded in verification.md. Later live test progress and the refreshed offline digest are recorded above and in verification.md.

## BLE UX integration

- [x] Detect all conflicting files from git status and conflict markers.
- [x] Resolve each conflict with minimal, correctness-first edits.
- [x] Prefer preserving both sides when safe. Otherwise, choose the variant that compiles and keeps public behavior stable.
- [x] Regenerate lockfiles with package manager tools instead of hand-editing. An offline Cargo check cleared the initial locked-check refusal; the saved and final lockfiles are byte-identical.
- [x] Run compile, lint, and relevant tests.
- [x] Stage resolved files and summarize key decisions. Restore the previously unstaged wallet changes after clearing conflicts.
- [x] Retain a labeled backup stash, fast-forward phase0_No.6 to cee0733, and preserve all wallet sources.
- [x] Complete the full offline CLI driver in both processes, with identical results.
- [x] Complete independent integration review.

Throughput checkpoint: resolve the two previewed files, run the targeted Jade and Trezor regression suites, then run the workspace checks and both full software fixture processes. The original wallet edits remain uncommitted. No BLE session or live transaction is part of this integration.

The integrated source passes 174 workspace tests, Clippy with warnings denied, both example builds, changed-file formatting, and the full CLI driver. Both processes finalized all 7,315 software cases with the same digest as before integration. Final source preservation classifies the appended audit updates separately from wallet source. Full workspace formatting still has the documented unchanged-package failure.
