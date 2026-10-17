# Wallet test inventory and verification report

This report inventories wallet tests, the code paths they exercise, and the latest recorded results. The operational test instructions are in [`wallet-e2e/README.md`](../wallet-e2e/README.md). Generated logs and per-case evidence are kept under the ignored `target/wallet-e2e/` directory.

## Latest E2E verification status — 2026-10-18

| Check | Result | Evidence and limits |
| --- | --- | --- |
| Example build | PASS | `cargo build --locked -p trezor-ble -p jade-ble --examples` completed on 2026-10-16. |
| E2E matrix | PASS — 7,315 cases | Both current example binaries generated the 12 single-sig + 7,303 multisig matrix. |
| Offline E2E | PASS — 7,315/7,315 finalized | Both current example binaries produced digest `951dc04a35aa056826e4bee3320f1224257ccf3bd7011bd7f883ac78a44eeec7`. This uses software fixtures; hardware behavior remains unverified by the offline check. |
| Live acceptance JSON | BLOCKED — 14/7,315 freshly rechecked; 7,301 remain; refreshed 2026-10-18 | `target/wallet-e2e/report.json` has `live_pass: 14`, `recorded_live_pass: 14`, and `remaining: 7301`. The 14 local PASS cases are four Jade single-sig, four Hot Wallet single-sig, and six multisig. |
| Documented live results | BLOCKED — 18/7,315 historically reported confirmed; 7,297 lack a documented PASS | Combines the 14 local cases freshly rechecked by this workspace with four friend-reported Trezor single-sig cases below. The Trezor transaction and confirmation details were supplied by the operator; they are not in this workspace's generated report or checkpoints. The four external results are included in this documented total, but not in the generated report's `live_pass` or `remaining` values. |
| Supplemental independent-PSBT signing | PASS — 1/1; outside the 7,315-case matrix | Case `multisig-p2wsh-3of3-independent-a0-h1j1t1-s07-r1-74-c1-75` confirmed spend `2f29b5e81d320db83ba8916ae42ab3c521814c9b556f6c7ec6d21ae097cf1c01`, fee 414 sats, vsize 207 vB, at block 156846. |
| Full `bash wallet-e2e/verify.sh` | NOT RUN as a single command | Matrix and offline checks were run previously. The latest `e2e report` successfully rechecked the 14 saved local matrix PASS cases and separately recorded the supplemental PASS. |

The current generated report distinguishes fresh chain checks from saved evidence. Its values are `recorded_live_pass: 14`, `live_pass: 14`, `remaining: 7301`, and `overall: BLOCKED`: all 14 locally saved matrix PASS results were freshly rechecked. They comprise four Jade single-sig, four Hot Wallet single-sig, and six multisig cases. Four friend-reported Trezor cases bring the documented historical matrix total to 18/7,315; they remain outside the local report, so its remaining count stays 7,301. The independent-signing PASS is supplemental and also stays outside the matrix count. Full live acceptance remains BLOCKED until the remaining matrix cases are run.

The supplemental case is `multisig-p2wsh-3of3-independent-a0-h1j1t1-s07-r1-74-c1-75`. It used pinned input `16ba3787cdcdef4dc85fa412cec081b73da174b4bb6efb8073b28314d0f7046c:1` (44,586 sats), the confirmed change output from the locally reconciled 3-of-3 case. The same unsigned PSBT was independently signed by the hot signer, Jade, and remote Trezor; each response was checked against the baseline and only valid signatures were copied into a host-owned merged PSBT. The spend `2f29b5e81d320db83ba8916ae42ab3c521814c9b556f6c7ec6d21ae097cf1c01` paid 5,000 sats, paid a 414-sat fee (207 vB), returned 39,172 sats in change, and confirmed at block 156846. The generated report records this as PASS under `supplemental_cases` / `supplemental_live_pass`; it does not change `live_pass`, `remaining`, or the 7,315-case acceptance criterion. Follow the [E2E README workflow](../wallet-e2e/README.md).

## Test inventory and application-path audit

| Area | Tests | What they exercise | Boundary and status |
| --- | ---: | --- | --- |
| `vault-core` | 124 | Rust key derivation, descriptors, PSBT handling, Jade and Trezor protocol/client logic, and signature validation. | Unit tests use simulated devices and network interactions. These call Rust library functions directly. |
| `jade-ble` | 4 library + 13 example | BLE discovery/service behavior and the Jade hardware-test CLI/E2E coordinator. | Automated tests use fixtures or fake devices. Four separately recorded live Jade single-sig cases used the BLE client and testnet transaction workflow. |
| `trezor-ble` | 13 library + 16 example | BLE discovery, pairing/client behavior, and shared E2E coordinator safeguards. | Automated tests use fixtures or fake devices. Four Trezor Safe 7 single-sig results were reported by the operator's friend and are included in the 18-case documented live total; they are not in this workspace's saved live checkpoints. |
| `wallet-e2e` | Included in example test totals | Matrix generation, checkpoints, chain reconciliation, and transaction workflows. Harness paths call `vault-core` derivation and PSBT-check functions; single-sig assembly also uses harness-side `bitcoin`/`miniscript` glue. | Offline signing uses software fixtures. Fourteen local matrix PASS records were freshly rechecked; four friend-reported Trezor results are documented separately below. The supplemental independent-signing PASS is reported outside the matrix. |
| `mobile-signer-ffi` Rust | 5 | FFI-shaped function behavior and tests. | Current functions call mock `vault-core` descriptor/derivation/PSBT abstractions; `sign_psbt` returns a mock signature. UniFFI is not wired. The Expo app uses `mockVault.ts`, has no test script, and does not call this Rust crate. |

These are not all end-to-end tests of the shipped application. Rust unit tests call library functions directly; harness tests exercise CLI/coordinator code; offline E2E uses software signers. The locally saved Jade live cases exercised the hardware BLE client, while the Hot Wallet live cases exercised software signing; both used the testnet workflow. The four Trezor results below were supplied from a friend's E2E report and operator-provided explorer output. They count in the documented historical total, but have not been imported into this workspace's private checkpoints. The supplemental independent-signing case used the local Jade and hot signer plus a remote Trezor response. The React Native UI remains mock-backed.

The 181 workspace tests include six non-wallet service tests (two in `custody-service`, four in `monitor-service`); `lender-signer-cli` has no tests. The wallet-specific rows account for the other 175 tests.

## Recorded Jade single-sig testnet4 cases

The saved per-case result files record successful Jade testnet4 spends, including receive/change addresses, txids, fees, and confirmation checkpoints. The latest report refresh freshly rechecked these saved results against testnet4; all four remain live PASS.

| BIP | Receive address | Change address | Spend txid | Fee | Confirmed block in saved report |
| --- | --- | --- | --- | ---: | ---: |
| 44 | `n2BMo5arHDyAK2CM8c56eoEd18uEkKnRLC` | `msNHWXQNUtKkqwW1RudHr68GBtQ7sy5a4F` | `098ce687ec1e00ad08206baec216db3b9daabb1ed51435b49e49321573c509d1` | 448 sats | 156266 |
| 49 | `2N5pTWRLrRdAPGvTd9agPFLFZvPfGNy7xuM` | `2MxDFWSepVt6svMXJp2MwB1Swarp7CFW2Lz` | `d5e3c43f384c63bd610de934e1c1128f84282d652e65630ec631bdf370a627a3` | 330 sats | 156266 |
| 84 | `tb1q4e9q5taxnsvc6m0uxv6h75mkzvnkxeqkckuztr` | `tb1qsqtfalqsdcqc06s0naxrzzluh4utc0ld4pte6n` | `6e7276a5619cf3fa47f42c24611a849f3cd07d1b57e2a544498c1a44826ba15e` | 282 sats | 156242 |
| 86 | `tb1pyfv094rr0vk28lf8v9yx3veaacdzg26ztqk4ga84zucqqhafnn5q9my9rz` | `tb1pf30xyljsqsyszrn9lzyjykt97j8r9z0062f6u6l9fvjk9m68fy0smwcpn5` | `f49671838a2c0cbff57dc251bbb62922dd87cc2775a9b432a17d235e41e965a7` | 284 sats | 156266 |

Each Jade spend paid 5,000 sats to `tb1qn7pyhvj80jc7k6vhmqprj4m79y6meav6jq2vm9`. The saved report records all four Jade cases as confirmed live PASS; chain state has not been rechecked since that report snapshot.

## Recorded Hot Wallet single-sig testnet4 cases

These four cases used the enrolled `h1` software signer and the same 5,000-sat destination shown above. Their saved E2E result records show each spend as confirmed, and the latest report refresh freshly rechecked them as live PASS.

| BIP | Receive address | Change address | Spend txid | Fee | Confirmed block |
| --- | --- | --- | --- | ---: | ---: |
| 44 | `n2Dqe6jageYAjNKZ73H8vqtbhqkkPHVPMP` | `msCLdkYwTnwYjPsJMx4AkhpGLUiLWgTkd5` | `af2b1a9450bb8d4d3399515ff98e2d0c1106ad9182b5a191a88f513f3d893839` | 448 sats | 156368 |
| 49 | `2MtLdd4k84HnmFGjEzCUY4VDZtVjFiMbwSX` | `2N9n7ZLP8BrcR1Tbkv3mmn1uDStAkooZTjR` | `bb6cd916febee1ad06bfdddd34f1e0a9f27dc97069f618e12485c0bbbe57c37c` | 330 sats | 156378 |
| 84 | `tb1qrgdq0p2vx9x3zfsrkxwjm6qhg4p2z5rhw5tmns` | `tb1qpp7u5fndqq58hcm2nr76vae69x57mpqt5wueku` | `69e519b086640750201cf70af1b91636218f6108a4509e42b5a4f40cdd15c992` | 282 sats | 156368 |
| 86 | `tb1pn3syu8cacmn5jpsw5yrazx38herqw53exn0g5jf30dwkshuskmjst2j4er` | `tb1pz7gr5q3d654xm3qzjxnq6xtvzumfukfgus8tps4k78whvwfygy0spz6d95` | `fd21a08da582d1d7c35ea6c867b38c47a2ba81db8bb9ed2cddf65c51368068b5` | 284 sats | 156378 |

The documented historical total is 18/7,315: 14 local matrix PASS results (Jade 4/4, Hot Wallet 4/4, multisig 6/6) plus four friend-reported, explorer-confirmed Trezor single-sig spends. The Trezor results remain outside `target/wallet-e2e/report.json`. The latest local report has `live_pass: 14`, `recorded_live_pass: 14`, and `remaining: 7301`; adding the four externally reported Trezor cases gives 18 documented cases and 7,297 matrix cases without a documented PASS in this tracker. Full acceptance remains BLOCKED.

## Locally reconciled multisig PASS cases

The six result files below have local progress `PASS`. The first five `e2e resume` runs reconciled their finalized spends against canonical testnet4 chain data and recorded confirmation at block 156696. The remote Trezor case was reconciled at block 156802. The latest aggregate report refresh successfully rechecked the 14 saved local matrix PASS cases against testnet4.

| Threshold | Case ID | Funding outpoint | Receive address | Spend txid | Fee | vsize | Local result | Confirmed block |
| --- | --- | --- | --- | --- | ---: | ---: | --- | ---: |
| 2-of-2 | `multisig-p2wsh-2of2-a0-h1j1t0-s03-r0-29-c1-29` | `a00f06ec6b7a788c0977e71e7be74cb3c6b16d4ca03c0b09af8f3311b9faa319:0` | `tb1q7qhrj0q25skfu83ycuah8mhuzw83u25rzsqkvvapjkxvzwyqnd9qw8pcmw` | `d53ce05760e7b0af344b4d5f144fa14c70c873ff54ef0103776533ff8fac3e8b` | 362 sats | 180 vB | PASS | 156696 |
| 1-of-2 | `multisig-p2wsh-1of2-a0-h1j1t0-s02-r0-28-c1-28` | `b60aee372c49078594657ea364fcf383452381c3d256bafcd0fcd747c0d92e24:1` | `tb1q3ryd0wqq9304fd0t32renmtdujz8ged9uld2jysdvmwuyl0ggadqgdl76e` | `d1e57d01db7b059514401cbcf9d51882b8dd714453cd2f871c1c6de2a6fa73de` | 324 sats | 162 vB | PASS | 156696 |
| 1-of-3 | `multisig-p2wsh-1of3-a0-h2j1t0-s04-r0-91-c1-91` | `40171d6634cbe1f5f23d4c6cb7d37259f31a2a65b251aabfea7f47d1356b89aa:0` | `tb1q6x4yve26xwlfe6jem6t0rp26hxc867y34qcwy5gr92qu0h5zpacq7qjuhx` | `872d0f955fff6a7163d27ace7861e7cc12222ef64a85bb155e37c7fe919c28c1` | 342 sats | 171 vB | PASS | 156696 |
| 2-of-3 | `multisig-p2wsh-2of3-a0-h2j1t0-s05-r0-93-c1-93` | `719ab4127b46396425f6d9008b327b8dfe7195f2bddcbcdd29d4b0ce07706522:1` | `tb1ql3nehdurgl9w6vkt2x6qcwvdlm26ux28782jmlfzjed60gzpv2rss9jnrt` | `443b623aa5b962901a2734f08739f2271dea74cc086ccdeda641bad06e254db0` | 378 sats | 189 vB | PASS | 156696 |
| 3-of-3 | `multisig-p2wsh-3of3-a0-h2j1t0-s07-r0-95-c1-95` | `69bc117c181b18dbd9975c60aa4c9209f4418c34e0498cde1eca881956841e1a:0` | `tb1qdl5hscq6k2kt7a9z9v6dys96ew5np6nqedf29zhtvckfsf7g587sdz79gx` | `19987dd29483a1542a1325ad1167a51f6b7c9cbf60e29d35cdaf27639dc61a86` | 414 sats | 207 vB | PASS | 156696 |
| 3-of-3 (hot + Jade + remote Trezor) | `multisig-p2wsh-3of3-a0-h1j1t1-s07-r0-74-c1-74` | `65efd83bbad75d0bc0fc6d4c5f7d6d8b6c9a34090e85a07e6387f7226d39a3b7:0` | `tb1qytuvgx08684upxp5a9v3uzd3pfwgjw0yzurky35fdy20xvhe7x9srl9jqp` | `16ba3787cdcdef4dc85fa412cec081b73da174b4bb6efb8073b28314d0f7046c` | 414 sats | 207 vB | PASS | 156802 |

## Supplemental independent-signing PASS

Case `multisig-p2wsh-3of3-independent-a0-h1j1t1-s07-r1-74-c1-75` independently signed one unchanged unsigned PSBT with the hot signer, Jade, and remote Trezor, then merged only validated signatures. It spent `16ba3787cdcdef4dc85fa412cec081b73da174b4bb6efb8073b28314d0f7046c:1` (44,586 sats) to the 5,000-sat test destination. Spend txid: `2f29b5e81d320db83ba8916ae42ab3c521814c9b556f6c7ec6d21ae097cf1c01`; fee: 414 sats; vsize: 207 vB; change: 39,172 sats to `tb1q26wm7ccjtktcuw5eej2u4fdgphcmppqax6gjfmn9lj7v9yq5e70qqfn08j`; confirmed at block 156846. The generated report records this separate supplemental case as PASS (1/1); it does not count toward the 7,315-case matrix.

## Friend-reported Trezor Safe 7 single-sig testnet4 cases

The following four cases and confirmed spends were supplied from the friend's E2E report and the operator-provided explorer output. The report said these were `e2e resume` checks, so no new Trezor signing was performed during those checks. The funding receive addresses were not included in the supplied evidence. These count as friend-reported cases in the documented historical 18/7,315 total, but are not part of this workspace's local `report.json` count.

| BIP | Case ID | Payment output | Change address | Spend txid | Fee | Confirmed block |
| --- | --- | --- | --- | --- | ---: | ---: |
| 44 | `trezor-bip44-a0-h0j0t1-s01-r0-8-c1-8` | 5,000 sats to `tb1qphjkxm23hqyjgy53g2kl5cxvanhvw8zlfhfx4t` | `mgCCGaQXWu1JGnuyhqvzgvUEmgAyRVMesi` (44,552 sats) | `e94d90f65ac97a3646207b11fd8896fa4aad5ef6b8215ca370d84db20f4e75b9` | 448 sats | 156281 |
| 49 | `trezor-bip49-a0-h0j0t1-s01-r0-9-c1-9` | 5,000 sats to `tb1qphjkxm23hqyjgy53g2kl5cxvanhvw8zlfhfx4t` | `2N1K4jqn55X8ULQYhL3A9T8JVReFSbR2zg5` (44,670 sats) | `28156e0b09ccf4d4193bbf602faa81c5c2f41ed7ad1c9ac543bdcc3ed147bdde` | 330 sats | 156281 |
| 84 | `trezor-bip84-a0-h0j0t1-s01-r0-10-c1-10` | 5,000 sats to `tb1qphjkxm23hqyjgy53g2kl5cxvanhvw8zlfhfx4t` | `tb1q6mpyx6qvvpuhkg0u9fq7ddllh6zrsrpw8zw67p` (44,718 sats) | `db41499941f8f3d7a4b52a80b0a207d617adf487b40038e1faa6e3ea5bcd136a` | 282 sats | 156281 |
| 86 | `trezor-bip86-a0-h0j0t1-s01-r0-11-c1-11` | 5,000 sats to `tb1qphjkxm23hqyjgy53g2kl5cxvanhvw8zlfhfx4t` | `tb1pqctq7eyaeyg3y4xf94pgj2dj5ds8p2ap96fl3awt8turymksn8xsguykl3` (44,716 sats) | `659cb71576f40d7d0cbf16344dc0f62682f6278772ff61ecc488c5b52f7affb7` | 284 sats | 156281 |

The friend's report showed a separate 4/7,315 snapshot for the Trezor cases and offline PASS. This tracker combines those four reported cases with the 14 locally saved PASS result records above; the generated local JSON does not contain the friend's Trezor checkpoints. The current local report has `live_pass: 14`, `recorded_live_pass: 14`, and `remaining: 7301`. This partial combined result does not establish full-matrix acceptance.

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
