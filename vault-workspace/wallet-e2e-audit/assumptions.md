# Assumption register

This register preserves the original audit assumptions and checkpoint. Its earlier `0/7,315` live status has been superseded by later tests. As of 2026-10-16, the primary status is in [`verification.md`](verification.md): eight local single-sig cases and five multisig cases have saved PASS checkpoints; four Trezor single-sig results are friend-reported and remain outside the local report.

| Assumption | Evidence or gate | State |
| --- | --- | --- |
| The baseline is clean main at 360febfd394aef4f11f67ab079c00809da50445d. | Initial git status and log. | Verified |
| Full coverage contains 12 single-sig and 7,303 multisig cases. | Manifest generator, independent combinatorial test, and CLI driver oracle. | Verified in both actual example processes |
| TEST-family device encoding does not prove testnet4. | Every live entry point compares Esplora height zero with rust-bitcoin testnet4 genesis. A read-only height-zero request returned the expected hash. | Gate implemented; no live spend verified |
| Hardware firmware can derive and display every requested address. | Actual BLE address RPC and host comparison. | Verified for the recorded Jade cases and friend-reported Trezor cases only; other devices and matrix combinations remain unverified. |
| One physical device cannot count as several holders. | Enrollment binds stable BLE physical identity, fingerprint, origins, and xpubs, with duplicate rejection. | Offline duplicate tests pass; device enrollment unverified |
| A saved Jade registration may be replaced only after exact prior readback matches a saved ownership record. | One test-owned slot and private enrollment ownership snapshot; resumed signing restores the case's registration. | Scripted RPC tests pass; actual firmware unverified |
| Jade RPC absence establishes that the test slot is empty. | Firmware hides registrations for other seeds. The first write requires a recorded manual empty-slot declaration. | Rejected; operator declaration required |
| Funding and spending each require a canonical confirmation. | Status plus canonical block hash and fetched transaction/outspend comparison; report refreshes recorded PASS rows without submitting. | Fake-chain rejection tests pass. Eight local Jade/Hot Wallet PASS checkpoints and five local multisig PASS checkpoints exist. The five multisig confirmations are at block 156696. Four Trezor confirmations remain friend-reported. |
| Restart after an ambiguous broadcast must reuse identical bytes. | Atomic finalized checkpoint before submission, txid/outspend reconciliation, and interruption tests. | Accepted-loss and unknown-loss recovery tests pass |
| Software seeds and pairing credentials do not belong in evidence. | Separate private secret directory, restrictive file permissions, sanitized child logs. | Permission and record tests pass; private credentials are kept outside the report and evidence records. |
| Offline checks cannot prove real-network acceptance. | Report keeps readiness and live results separate; every row starts BLOCKED. | Enforced; local live JSON remains BLOCKED at 13/7,315, with 7,302 cases remaining. |
