# lender-signer-cli

**Owner**: person 5 (shared with `monitor-service`) — `docs/00-capstone-brief.md` §3.6.

**Depends on**: `vault-core`.

## Responsibilities

Air-gapped offline signing workflow for the lender/fund representative:
- fetch a pending PSBT
- inspect its contents (amounts, outputs, purpose) before trusting it
- sign it offline

## Getting started

```
cargo run -p lender-signer-cli -- inspect some-psbt.json
cargo run -p lender-signer-cli -- sign some-psbt.json --out signed.json
cargo run -p lender-signer-cli -- fetch 1 --url http://127.0.0.1:8080   # needs custody-service running
```

A PSBT file is just JSON matching `vault-core::psbt::UnsignedPsbt` — e.g. `{"loan_id":"loan-1","inputs":["utxo:0"],"outputs":[{"address":"tb1qborrower","amount_sats":100000}]}`. `sign` wraps it with a mock signature (`SignedPsbt`, defined locally in this crate) — real offline signing belongs in `vault-core` once it exposes one.
