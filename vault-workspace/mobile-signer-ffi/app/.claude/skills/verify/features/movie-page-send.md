# Movie Page send (S* / M*)

Single-sig send uses mocked chain balance/fees/broadcast. Multisig vault send collects mock signatures (Jade path is BLE demo without live sign_psbt).

## Sub-features

- `mp-s1-home` shows mocked single-sig balance.
- `mp-s2-backup-gate` blocks receive until backup confirmed.
- `mp-s6-sent` shows mock txid after sign.
- `mp-m1-m4` walks vault send → collect → (optional Jade demo) → broadcast mock.

## How to get to it (user POV)

- Home first-run → กระเป๋ากุญแจเดียว, or catalog SINGLE-SIG / SEND · VAULT.

## Driving it with verify.sh

Preconditions:

- For S2 continue path, onboarding backup must be confirmed.
- For M*, a created vault helps; empty vault still shows M1 error copy.

- **S1.** Catalog → `id:catalog_send/s1`, `see id:screen_send/s1`, `see id:ss_balance`. Shot `mp-s1-home`.
- **Backup gate.** From S1 tap receive (`id:cta_s1_receive`), on `id:screen_send/s2` either see backup CTA or continue. Shot `mp-s2-backup-gate`.
- **Sent.** S1 → send → review → sign → `see id:screen_send/s6_default`, `see id:s6_txid`. Shot `mp-s6-sent`.
- **Multisig.** Catalog → `id:catalog_send/m1`, walk collect soft-sign demos if no vault keys, `see id:screen_send/m4`, broadcast, `see id:m4_txid` before navigate away. Shot `mp-m1-m4`.

## Gotchas

- S* and M* chain/PSBT paths are mocked; matrix marks them mocked.
- M3 Jade is simulator-safe demo only.
