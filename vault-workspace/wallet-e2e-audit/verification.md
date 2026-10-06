# Verification record

The implementation is ready for offline review. Real-network acceptance is BLOCKED: no devices were enrolled, no addresses were verified through BLE, and no testnet4 funds were spent. The reported live count is 0/7,315.

The baseline was clean main at `360febfd394aef4f11f67ab079c00809da50445d`. Baseline workspace tests passed 122 tests. The implementation adds actual descriptor and PSBT handling, protocol transcript tests, and an example-only coordinator. Lending policies and mock transaction helpers remain outside the acceptance path.

| Check | Observed result | Evidence from vault-workspace |
| --- | --- | --- |
| Workspace tests | PASS, 165 tests including both example suites | target/wallet-e2e/final-checks/workspace-tests.log |
| Clippy, all targets, warnings denied | PASS after correcting the shared Esplora constructor's Default lint | target/wallet-e2e/final-checks/clippy.log |
| Targeted formatting | PASS | target/wallet-e2e/final-checks/targeted-format.log |
| Whitespace check | PASS | target/wallet-e2e/final-checks/diff-whitespace.log |
| Full workspace formatting | FAIL in unchanged custody-service, lender-signer-cli, mobile-signer-ffi, and monitor-service sources | target/wallet-e2e/final-checks/workspace-format.log |
| Both example builds and complete CLI driver | PASS; each process signed and finalized all 7,315 cases, with independent matrix expectations, equal digests, and CLI rejection checks | wallet-e2e/verify.sh; target/wallet-e2e/verification.log; target/wallet-e2e/final-checks/example-builds.log |
| Earlier full offline CLI run | PASS, all 7,315 designated subsets signed and finalized with software fixtures | target/wallet-e2e/offline.json |
| Real testnet4 genesis request | Expected genesis returned; this verifies endpoint identity only | target/wallet-e2e/final-checks/testnet4-genesis.txt |
| Actual BLE and real-network spends | NOT VERIFIED | No enrollment or funded live checkpoint was created |

The core suite covers the published BIP86 vector, all four single-sig families, BIP48 multisig, wrong origins and scripts, changed prevouts and outputs, invalid signatures and sighashes, insufficient signatures, and scripted hardware rejection or abandonment. Shared example tests verify independent scripts and sorting, signer identity duplication, exclusive locks, reservations, restrictive permissions, and atomic checkpoint replacement.

Coordinator tests use a fake API and real hot BIP86 signing. A lost response from an accepted broadcast resumes without a second submission. An unknown lost response retries only identical saved bytes. Wrong genesis and absent broadcast authorization prevent submission. Report tests remove canonical confirmation, hide the saved spend, change genesis, and forge a public PASS without its private checkpoint; none counts as current acceptance. Altered public addresses, funding, origins, signature metadata, fees, commands, and versions are replaced by validated private checkpoint evidence. Reporting refreshes both funding and spend confirmations, submits no transactions, and leaves checkpoints untouched.

Independent review identified three coordinator defects that were corrected: excess funding had been accepted, an outsider signature could be preloaded into the unsigned baseline, and a resumed Jade case could sign after a different case replaced the test registration. The original unsigned-baseline failure was observed in the earlier transcript; its output was not retained as a file. A later guard-removal experiment reproduced the same acceptance and retained the failure and restored-guard success in final-checks/unsigned-baseline-without-guard.log and unsigned-baseline-restored-guard.log. Final review also identified stale PASS aggregation after a reorg; reporting now requires fresh canonical evidence. A subsequent cross-model audit found that public metadata could be altered while keeping a valid transaction identity. Removing the report regeneration line made that regression fail; restoring it passed, as retained in final-checks/report-mutation-before-fix.log and report-mutation-after-fix.log.

The first four decision timestamps were placeholders. Their decisions remain valid, but the trail explicitly supersedes those timestamps; later rows record the time the decision was logged. Some implementation decisions were logged retrospectively after the interrupted session resumed. The same root conversation created and continued the trail across the quota interruption and compacted context; no other agent wrote it. Under the skill's definition this remains one run. Source pointers prove the retained changes, and final log files record checks actually run. Generated logs and private fixture data are gitignored; rerun wallet-e2e/verify.sh and the README commands to reproduce them.

The two process results share digest `1e084bbf7182a3da749752498280785e21ee8bea86e2bd736234258b7fac0271`. The long offline processes started before the final public-report correction; their descriptor/signing paths were unchanged by that correction. The driver's later reporting invocation used the rebuilt corrected binary. Workspace tests, Clippy, builds, and the reporting regression were also checked after that correction.
