# monitor-service

**Owner**: person 5 (shared with `lender-signer-cli`) — `docs/00-capstone-brief.md` §3.6.

**Depends on**: nothing in this workspace at first — LTV math doesn't need Bitcoin script logic. Add `vault-core` once liquidation PSBT construction starts (term 2).

## Responsibilities

- Poll BTC price (public Esplora API for MVP; keep the fetcher behind a trait so it can be swapped for self-hosted Electrs later)
- Compute LTV per active loan continuously
- Trigger margin-call / liquidation in `custody-service` at threshold (brief's example: init 50% / margin call 70% / liquidate 80% — propose changes with reasoning if the team disagrees)

## Liquidation price is derived, never hardcoded

See `../../docs/design-notes.md` for a worked example and the correction it documents (a hand-entered liquidation price that didn't match its own stated LTV threshold). `monitor-service`'s LTV/liquidation formula should be the one source of truth other modules (mobile UI, `vault-core`'s policy engine) agree with.

## Getting started

```
cargo run -p monitor-service
```

Runs 5 ticks against 3 mock loans (same fixture numbers as `mobile-signer-ffi/app/src/mockVault.ts`, so both sides of the product agree), printing LTV and liquidation price per loan each tick and flagging margin-call/liquidation-worthy loans — `price_feed::MockPriceFeed` random-walks the price so the numbers actually move between ticks. Swap `MockPriceFeed` for a real Esplora client behind the same `PriceFeed` trait when that's ready.
