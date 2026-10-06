# Assumption register

| Assumption | Evidence or gate | State |
| --- | --- | --- |
| The baseline is clean main at 360febfd394aef4f11f67ab079c00809da50445d. | Initial git status and log. | Verified |
| Full coverage contains 12 single-sig and 7,303 multisig cases. | Manifest generator, independent combinatorial test, and CLI driver oracle. | Verified in both actual example processes |
| TEST-family device encoding does not prove testnet4. | Every live entry point compares Esplora height zero with rust-bitcoin testnet4 genesis. A read-only height-zero request returned the expected hash. | Gate implemented; no live spend verified |
| Hardware firmware can derive and display every requested address. | Actual BLE address RPC and host comparison. | Unverified without devices |
| One physical device cannot count as several holders. | Enrollment binds stable BLE physical identity, fingerprint, origins, and xpubs, with duplicate rejection. | Offline duplicate tests pass; device enrollment unverified |
| A saved Jade registration may be replaced only after exact prior readback matches a saved ownership record. | One test-owned slot and private enrollment ownership snapshot; resumed signing restores the case's registration. | Scripted RPC tests pass; actual firmware unverified |
| Jade RPC absence establishes that the test slot is empty. | Firmware hides registrations for other seeds. The first write requires a recorded manual empty-slot declaration. | Rejected; operator declaration required |
| Funding and spending each require a canonical confirmation. | Status plus canonical block hash and fetched transaction/outspend comparison; report refreshes recorded PASS rows without submitting. | Fake-chain rejection tests pass; live confirmations absent |
| Restart after an ambiguous broadcast must reuse identical bytes. | Atomic finalized checkpoint before submission, txid/outspend reconciliation, and interruption tests. | Accepted-loss and unknown-loss recovery tests pass |
| Software seeds and pairing credentials do not belong in evidence. | Separate private secret directory, restrictive file permissions, sanitized child logs. | Permission and record tests pass; no live credentials created |
| Offline checks cannot prove real-network acceptance. | Report keeps readiness and live results separate; every row starts BLOCKED. | Enforced; real-network PASS 0/7315 |
