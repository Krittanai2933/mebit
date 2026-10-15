# Wallet test inventory and verification report

This report inventories wallet tests, the code paths they exercise, and the latest recorded results. The operational test instructions are in [`wallet-e2e/README.md`](../wallet-e2e/README.md). Generated logs and per-case evidence are kept under the ignored `target/wallet-e2e/` directory.

## Latest saved verification — 2026-10-13 16:32 +07:00

| Check | Result | Evidence and limits |
| --- | --- | --- |
| `cargo test --workspace --locked` | PASS — 181 passed, 0 failed | Full Rust workspace, including Jade and Trezor libraries and both hardware-harness example test suites. |
| `bash wallet-e2e/verify.sh` | PASS — exit 0 | Run from `vault-workspace`. Both CLI examples generated the same 7,315-case matrix; each finalized all 7,315 designated subsets with software fixtures. Independent composition/subset, repeat-process determinism, wrong-network, and unknown-case checks passed. |
| Offline E2E result | PASS — 7,315/7,315 finalized | `target/wallet-e2e/offline.json`; software fixtures exercise descriptor, PSBT, signature verification, and finalization. Hardware behavior is not verified by this check. |
| Live acceptance report | BLOCKED — 8/7,315 confirmed live PASS | `target/wallet-e2e/report.json`, generated at the timestamp above, records four Jade and four Hot Wallet cases as PASS. This report snapshot has not been refreshed for this documentation update. |

The E2E verification script exits successfully when its CLI and offline assertions pass, even when the separate live-acceptance report is BLOCKED. The saved `target/wallet-e2e/report.json` records `recorded_live_pass: 8`, `live_pass: 8`, and `overall: BLOCKED`. The eight PASS records consist of four Jade single-sig cases and four Hot Wallet single-sig cases. Their saved checkpoints include canonical funding and spend confirmations; chain state was not rechecked while updating this report.

## Test inventory and application-path audit

| Area | Tests | What they exercise | Boundary and status |
| --- | ---: | --- | --- |
| `vault-core` | 124 | Rust key derivation, descriptors, PSBT handling, Jade and Trezor protocol/client logic, and signature validation. | Unit tests use simulated devices and network interactions. These call Rust library functions directly. |
| `jade-ble` | 4 library + 13 example | BLE discovery/service behavior and the Jade hardware-test CLI/E2E coordinator. | Automated tests use fixtures or fake devices. Four separately recorded live Jade single-sig cases used the BLE client and testnet transaction workflow. |
| `trezor-ble` | 13 library + 16 example | BLE discovery, pairing/client behavior, and shared E2E coordinator safeguards. | Automated tests use fixtures or fake devices. No Safe 7 live test is recorded. |
| `wallet-e2e` | Included in example test totals | Matrix generation, checkpoints, chain reconciliation, and transaction workflows. Harness paths call `vault-core` derivation and PSBT-check functions; single-sig assembly also uses harness-side `bitcoin`/`miniscript` glue. | Offline signing uses software fixtures. Eight live cases are recorded below: four Jade and four Hot Wallet. No live Trezor or multisig result is claimed. |
| `mobile-signer-ffi` Rust | 5 | FFI-shaped function behavior and tests. | Current functions call mock `vault-core` descriptor/derivation/PSBT abstractions; `sign_psbt` returns a mock signature. UniFFI is not wired. The Expo app uses `mockVault.ts`, has no test script, and does not call this Rust crate. |

These are not all end-to-end tests of the shipped application. Rust unit tests call library functions directly; harness tests exercise CLI/coordinator code; offline E2E uses software signers. The saved Jade live cases exercised the hardware BLE client, while the Hot Wallet live cases exercised software signing; both used the testnet workflow. The React Native UI remains mock-backed.

The 181 workspace tests include six non-wallet service tests (two in `custody-service`, four in `monitor-service`); `lender-signer-cli` has no tests. The wallet-specific rows account for the other 175 tests.

## Recorded Jade single-sig testnet4 cases

The saved per-case result files record successful Jade testnet4 spends, including receive/change addresses, txids, fees, and confirmation checkpoints. The saved report marks them confirmed; their chain status has not been checked since that report snapshot.

| BIP | Receive address | Change address | Spend txid | Fee | Confirmed block in saved report |
| --- | --- | --- | --- | ---: | ---: |
| 44 | `n2BMo5arHDyAK2CM8c56eoEd18uEkKnRLC` | `msNHWXQNUtKkqwW1RudHr68GBtQ7sy5a4F` | `098ce687ec1e00ad08206baec216db3b9daabb1ed51435b49e49321573c509d1` | 448 sats | 156266 |
| 49 | `2N5pTWRLrRdAPGvTd9agPFLFZvPfGNy7xuM` | `2MxDFWSepVt6svMXJp2MwB1Swarp7CFW2Lz` | `d5e3c43f384c63bd610de934e1c1128f84282d652e65630ec631bdf370a627a3` | 330 sats | 156266 |
| 84 | `tb1q4e9q5taxnsvc6m0uxv6h75mkzvnkxeqkckuztr` | `tb1qsqtfalqsdcqc06s0naxrzzluh4utc0ld4pte6n` | `6e7276a5619cf3fa47f42c24611a849f3cd07d1b57e2a544498c1a44826ba15e` | 282 sats | 156242 |
| 86 | `tb1pyfv094rr0vk28lf8v9yx3veaacdzg26ztqk4ga84zucqqhafnn5q9my9rz` | `tb1pf30xyljsqsyszrn9lzyjykt97j8r9z0062f6u6l9fvjk9m68fy0smwcpn5` | `f49671838a2c0cbff57dc251bbb62922dd87cc2775a9b432a17d235e41e965a7` | 284 sats | 156266 |

Each Jade spend paid 5,000 sats to `tb1qn7pyhvj80jc7k6vhmqprj4m79y6meav6jq2vm9`. The saved report records all four Jade cases as confirmed live PASS; chain state has not been rechecked since that report snapshot.

## Recorded Hot Wallet single-sig testnet4 cases

These four cases used the enrolled `h1` software signer and the same 5,000-sat destination shown above. The saved E2E report records each spend as confirmed.

| BIP | Receive address | Change address | Spend txid | Fee | Confirmed block |
| --- | --- | --- | --- | ---: | ---: |
| 44 | `n2Dqe6jageYAjNKZ73H8vqtbhqkkPHVPMP` | `msCLdkYwTnwYjPsJMx4AkhpGLUiLWgTkd5` | `af2b1a9450bb8d4d3399515ff98e2d0c1106ad9182b5a191a88f513f3d893839` | 448 sats | 156368 |
| 49 | `2MtLdd4k84HnmFGjEzCUY4VDZtVjFiMbwSX` | `2N9n7ZLP8BrcR1Tbkv3mmn1uDStAkooZTjR` | `bb6cd916febee1ad06bfdddd34f1e0a9f27dc97069f618e12485c0bbbe57c37c` | 330 sats | 156378 |
| 84 | `tb1qrgdq0p2vx9x3zfsrkxwjm6qhg4p2z5rhw5tmns` | `tb1qpp7u5fndqq58hcm2nr76vae69x57mpqt5wueku` | `69e519b086640750201cf70af1b91636218f6108a4509e42b5a4f40cdd15c992` | 282 sats | 156368 |
| 86 | `tb1pn3syu8cacmn5jpsw5yrazx38herqw53exn0g5jf30dwkshuskmjst2j4er` | `tb1pz7gr5q3d654xm3qzjxnq6xtvzumfukfgus8tps4k78whvwfygy0spz6d95` | `fd21a08da582d1d7c35ea6c867b38c47a2ba81db8bb9ed2cddf65c51368068b5` | 284 sats | 156378 |

Across the saved report, eight cases have live PASS checkpoints: Jade 4/4 and Hot Wallet 4/4. The other 7,307 cases have no recorded live acceptance result. The aggregate remains BLOCKED until all 7,315 required cases pass.

## Changes in the preceding commit

Commit `61a838f` (`test(wallet): complete Jade live single-sig BIP44/49/84/86`) changed nine files relative to `cf51e3d`. It identifies an enrolled Jade across BLE address rotation, verifies device responses and copies only verified signatures onto the original host PSBT, keeps Trezor identity checks strict, and adds opt-in malformed-pinserver-response regression coverage and documentation. The four live Jade cases above are the demonstrated scope; the commit does not claim full matrix acceptance.

## Reproduce the local checks

Run from `vault-workspace`:

```sh
cargo test --workspace --locked
bash wallet-e2e/verify.sh
```

For the broader testnet4 matrix workflow and hardware commands, follow [`wallet-e2e/README.md`](../wallet-e2e/README.md). The overall live acceptance criterion remains 7,315 confirmed cases; offline readiness does not count toward live acceptance.

## Historical verification record — initial E2E runner implementation

The following notes document the earlier runner implementation checkpoint and are retained as history. Their 0/7,315 status predates the eight live single-sig cases documented in the current inventory report.

The implementation was ready for offline review. Real-network acceptance was BLOCKED: no devices were enrolled, no addresses were verified through BLE, and no testnet4 funds were spent. The reported live count was 0/7,315.

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
