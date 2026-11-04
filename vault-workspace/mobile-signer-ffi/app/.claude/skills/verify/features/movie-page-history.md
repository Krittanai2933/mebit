# Movie Page history (S7)

The history screen lists every transaction the wallet loaded, newest first. Each row is the Home row, with an arrow on a circle, รับ or ส่งออก, the date, the signed amount in compact BTC and the confirmations. Home links to it with ดูทั้งหมด when the wallet has more transactions than the four rows Home shows. A wallet with no loaded transactions shows ยังไม่มีธุรกรรม. A tap on a row opens that transaction's detail, see [transaction detail](./movie-page-tx-detail.md).

## Sub-features

- `mp-s7-link` Home shows ดูทั้งหมด (`cta_s1_history`) right of ธุรกรรมล่าสุด only when more than four transactions loaded, and the link opens S7.
- `mp-s7-list` S7 (`screen_send/s7`) lists every loaded transaction as a Home row, with `history_tx_0` the newest.
- `mp-s7-empty` S7 shows ยังไม่มีธุรกรรม (`history_empty`) for a wallet that loaded no transactions, the demo wallet included.

## How to get to it (user POV)

- Home → ดูทั้งหมด right of ธุรกรรมล่าสุด, on a live wallet with more than four transactions.
- The debug catalog row HISTORY · ประวัติธุรกรรม ✓ (`catalog_send/s7`). It shows what Home loaded last.

## Driving it with verify.sh

Preconditions:

- `verify.sh start` or `verify.sh ios-start` finished in this run.
- For the link and the list, a live wallet with more than four transactions, opened on Home as in [send](./movie-page-send.md). Without one, use the probe below.

- **Probe for seven rows.** This is a throwaway change that is never committed. In `SingleSigWallet.useDemoFixture()`, set `mode = WalletMode.Live` and set `history` to seven `HotTx(txid, netSats, confirmations, blockTime)` values, newest first, with money in and money out. Tag each line `// PROBE-HISTORY`. The demo wallet then loads as live with those rows whenever Home opens. Remove the probe before anything else and check that `grep -rIn "PROBE-" shared` prints nothing.
- **Link (Android).** `verify.sh tap "id:open_catalog"`, `verify.sh tap "text~:HOME · จะเริ่ม"`, `verify.sh tap "id:cta_singlesig"`, then `verify.sh see "id:cta_s1_history"`. Shot `mp-s7-link` shows ดูทั้งหมด right of ธุรกรรมล่าสุด, above four rows.
- **List (Android).** `verify.sh tap "id:cta_s1_history"`, `verify.sh see "id:screen_send/s7"` and `verify.sh see "id:history_tx_6"`. Shot `mp-s7-list` shows the eyebrow HISTORY, the title ประวัติธุรกรรม and seven rows with Home's arrows, colours and compact amounts, such as `+0.00019436`. `verify.sh tap "id:back"` and `verify.sh see "id:screen_send/s1"` return to Home. Run `20261105-020358` on `Medium_Phone_API_36.0` drove these steps and the link step with the probe, as open item 80 records.
- **iOS.** The same path is `verify.sh ios-tap "id:open_catalog"`, `verify.sh ios-tap "id:catalog_home/first-run"`, `verify.sh ios-tap "id:cta_singlesig"`, `verify.sh ios-see "id:cta_s1_history"`, `verify.sh ios-tap "id:cta_s1_history"`, `verify.sh ios-see "id:screen_send/s7"` and `verify.sh ios-see "id:history_tx_6"`. Not yet run.
- **Empty.** On a fresh start without the probe, open the catalog and swipe the route list up as for `catalog_send/s5` in [send](./movie-page-send.md) until `verify.sh ios-see "id:catalog_send/s7"` passes. Then `verify.sh ios-tap "id:catalog_send/s7"` and `verify.sh ios-see "id:history_empty"`. Shot `mp-s7-empty` shows ยังไม่มีธุรกรรม under the title. Not yet run.

## Gotchas

- S7 fetches nothing itself. It shows what Home's last fetch loaded, so pull Home down for newer transactions (see Pull to refresh in [send](./movie-page-send.md)).
- The core loads only the first page of each address, about 25 confirmed transactions plus those in the mempool, so the list of a busy wallet is not complete (open item 80).
- The ดูทั้งหมด link, the eyebrow HISTORY, the title ประวัติธุรกรรม and ยังไม่มีธุรกรรม have no Penpot board. Their copy is a default awaiting design review (open item 80).
- A tap on a row opens S8 (`id:screen_send/s8`) for that transaction, and back returns to S7. [Transaction detail](./movie-page-tx-detail.md) drives it from `id:history_tx_1` onward.
- While the probe is in place, the demo wallet's Home is live too, so it shows the baht line and no demo marker.
