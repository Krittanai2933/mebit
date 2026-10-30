# Movie Page send (S* / M*)

Single-sig send checks the typed address and amount, shows the fee tiers, reviews the payment and ends on a slip. Without a key the wallet uses an offline demo balance (0.0125 BTC) and a mock broadcast. Multisig vault send collects mock signatures (Jade path is BLE demo without live sign_psbt).

## Sub-features

- `mp-s1-home` shows the single-sig balance.
- `mp-s2-backup-gate` blocks receive until backup confirmed.
- `mp-s4-send` takes an address, an amount and a fee tier, and says what is wrong with them.
- `mp-s5-review` shows what leaves and what stays, and signs.
- `mp-s6-slip` shows the sent slip with a txid.
- `mp-m1-m4` walks vault send → collect → (optional Jade demo) → broadcast mock.

## How to get to it (user POV)

- Home first-run → กระเป๋ากุญแจเดียว → ส่ง, or catalog SINGLE-SIG / SEND · VAULT.

## Driving it with verify.sh

Preconditions:

- iOS only for the single-sig path: `verify.sh ios-start` finished in this run and Welcome is showing. Without a key made in the run, the wallet is the offline demo one.
- For S2 continue path, onboarding backup must be confirmed.
- For M*, a created vault helps; empty vault still shows M1 error copy.

- **S1.** Catalog → `id:catalog_send/s1`, `see id:screen_send/s1`, `see id:ss_balance`. Shot `mp-s1-home`: the navy header with the balance (a small demo marker when the wallet is the offline one), รับ and ส่ง, the key status row and the Vault upgrade card.
- **Backup gate.** Before the backup is confirmed: `verify.sh ios-tap "id:open_catalog"`, `verify.sh ios-tap "id:catalog_home/first-run"`, `verify.sh ios-tap "id:cta_singlesig"`, `verify.sh ios-see "id:screen_send/s1"`, `verify.sh ios-tap "id:cta_s1_receive"`, `verify.sh ios-see "id:screen_send/s2"`, `verify.sh ios-see "id:cta_s2_backup"`. Shot `mp-s2-backup-gate`: the home dimmed behind a sheet that asks for the backup. `verify.sh ios-tap "id:cta_s2_cancel"`, `verify.sh ios-gone "id:cta_s2_backup"` closes it. `id:cta_s2_backup` goes to the backup words, and with the backup confirmed รับ skips the sheet and opens `id:screen_send/s3`.
- **Send form.** `verify.sh ios-tap "id:open_catalog"`, `verify.sh ios-tap "id:catalog_home/first-run"`, `verify.sh ios-tap "id:cta_singlesig"`, `verify.sh ios-see "id:screen_send/s1"`, `verify.sh ios-tap "id:cta_s1_send"`, `verify.sh ios-see "id:screen_send/s4"`. Shot `mp-s4-empty`: the receive/send switch, an empty address, 0.0000 BTC, three fee cards with ปกติ picked, and ตรวจสอบ grey.
- **Fill it.** `verify.sh ios-type "id:s4_address" "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"`, `verify.sh ios-type "id:s4_amount" "0.005"`. Shot `mp-s4-ready`: no warning and ตรวจสอบ navy. A text that is not a testnet address turns the field orange with `id:send_issue` reading INVALID ADDRESS, and an amount above the balance plus the fee turns the amount card orange with INSUFFICIENT FUNDS and the shortfall. Both keep ตรวจสอบ grey (checked with idb, not with these commands).
- **Review and sign.** `verify.sh ios-tap "id:cta_s4_review"`, `verify.sh ios-see "id:screen_send/s5"`. Shot `mp-s5-review`: the amount and the address, then the fee, the total taken and what is left. `verify.sh ios-tap "id:cta_s5_sign"`. With the app lock on, Face ID comes first; see [app lock](./movie-page-app-lock.md).
- **Slip.** `verify.sh ios-see "id:screen_send/s6_slip"`, `verify.sh ios-see "id:s6_txid"`. Shot `mp-s6-slip`. `verify.sh ios-tap "id:cta_s6_home"`, `verify.sh ios-see "id:screen_send/s1"`.
- **Multisig.** Catalog → `id:catalog_send/m1`, walk collect soft-sign demos if no vault keys, `see id:screen_send/m4`, broadcast, `see id:m4_txid` before navigate away. Shot `mp-m1-m4`.

## Gotchas

- Fee tiers are 4, 8 and 15 sat/vB times a typical 141 vB payment (564, 1,128 and 2,115 sat), not a live fee estimate.
- Receive leaves out the QR block (no encoder), the share button and the board's line about a fresh address every time: the hot wallet watches and spends the first address only. The catalog row `id:catalog_send/s3` sits below the first screen of the route list and `verify.sh` has no scroll action, so reach Receive through a confirmed backup instead.
- Home leaves out the recent transactions, the bottom tabs, the settings gear and the key-details link: the app has no history data, tab navigation or screens behind them yet.
- The send form leaves out the QR scan button, the ฿ toggle and every baht figure: the app has no camera path and no price feed. The slip leaves out the date line and the share button.
- The sign button reads เซ็นด้วย Face ID with the app lock on, เซ็นและส่ง for a live wallet, and เซ็นบนเครื่องนี้ (demo) otherwise.
- S* chain/PSBT paths are mocked unless the wallet is live; M* is mocked.
- M3 Jade is simulator-safe demo only.
