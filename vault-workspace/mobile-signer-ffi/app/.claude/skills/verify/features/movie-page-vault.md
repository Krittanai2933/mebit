# Movie Page vault policy → list

A user starts a wallet from the Wallet list's +, picks single-sig or a 2-of-3 or 3-of-5 multisig, chooses confirmed keys, sees the script, reviews a real multisig descriptor from UniFFI, creates the wallet, saves or shares its BSMS file (not QR), and opens the vault list and a vault's detail (Penpot 3.7 and 3.7.1). T20 opens from the debug catalog.

## Sub-features

- `mp-policy` Policy is single-sig or multisig, and multisig offers the 2-of-3 and 3-of-5 presets with a disabled custom chip.
- `mp-choose-keys` Choose Keys counts the picks, enables its button at exactly N, and adds a key without losing the picks.
- `mp-vault-created-bsms` Wallet Created saves or shares the BSMS file after create.
- `mp-vault-list-detail` lists created vaults and opens the detail.
- `mp-vault-balance` a vault's card, the list's total and the Receive and Send chooser show its balance once its read lands.
- `mp-vault-detail` the detail draws Penpot 3.7 from real data: kind, name, balance and baht, รับ and ส่ง, the m-of-n card and the transactions.
- `mp-vault-detail-keys` the card's รายละเอียด opens the wallet's numbered keys (3.7.1) and ซ่อน closes them.
- `mp-vault-detail-close` the X returns to the Wallet list, also from the detail a vault send lands on.

## How to get to it (user POV)

- Wallet list + → Policy → Choose keys → Script type → Review → Create → Wallet Created → Wallet list. Back from Policy returns to the list, and a second + starts with nothing picked.
- "เพิ่มกุญแจอีก" on Choose keys opens Add Key. Confirm Key, or the end of a new phone wallet's setup, comes back to Choose keys with the earlier picks kept and the new key not picked. Add Key shows the bottom bar, so a tap on a tab there leaves the wizard and drops the picks.
- Catalog jumps to POLICY / SCRIPT TYPE / WALLET CREATED / VAULTS / T20 when state is seeded.

## Driving it with verify.sh

Preconditions:

- Three confirmed keys in the keyring for 2-of-3 (run onboarding + Add Key match, or seed via prior steps). For a thin smoke with empty keys, catalog → POLICY still shows the board.

- **Policy.** Catalog → `id:catalog_vault/policy`, `see id:screen_vault/policy`, `see id:policy_multi`, `see id:preset_2_3`. `tap id:preset_3_5` and Choose Keys then counts to 5. `id:preset_custom` is not clickable. `tap id:policy_single` hides the chips, and `tap id:policy_multi` brings them back. The old `policy_personal` and `cta_policy_decaying` are gone. Shot `mp-policy`.
- **Choose keys.** `tap id:cta_policy_next`, `see id:screen_vault/choose-keys`, `see id:choose_count` (reads "เลือกแล้ว 0 / 3 ใบ"). `tap id:pick_<key id>` three times: the count reads 3 / 3 and `id:cta_choose_next` turns on. A fourth tap picks nothing. `tap id:cta_choose_add_key` opens Add Key. Shot `mp-choose-keys`.
- **Same wallet refused.** With a wallet already created from three keys, pick the same three keys in any order: `see id:choose_duplicate` ("Wallet นี้อยู่ในเครื่องนี้แล้ว · " and that wallet's name) and `id:cta_choose_next` is off. Untick one: `gone id:choose_duplicate`.
- **Script type.** `tap id:cta_choose_next`, `see id:screen_vault/script-type`, `see id:script_native` (selected, "ค่าเริ่มต้น"), `id:script_nested` is greyed and not clickable, `tap id:cta_script_next`.
- **Review.** `see id:screen_vault/review`, `see id:review_summary`, `see id:review_rows`. `tap id:cta_review_descriptor` shows `id:review_descriptor` with the full descriptor, and a second tap hides it. Back returns to Script type. Shot `mp-review`.
- **Created BSMS.** `tap id:cta_create_vault`, `see id:screen_vault/created`, `see id:created_subtitle`, `see id:cta_bsms_save`, `see id:cta_bsms_share`, `gone id:back`. Each button opens a system sheet, and closing it without choosing leaves no `id:error`. Confirm no QR affordance. Shot `mp-vault-created-bsms`.
- **Refused keys (T18).** Add Key → "mebit บนเครื่องอื่น" with a pasted vpub key (built from the fixture in MebitOtherPasteTest), confirm it, pick it with two other keys, `tap id:cta_create_vault`, `see id:screen_critical/t18`. Shot `mp-t18`.
- **List/detail/T20.** `tap id:cta_open_vaults` or catalog vaults, `see id:screen_vaults` (the list is the only entry on the stack, so there is no back chip), open a vault, `see id:screen_vaults/detail`. The detail has no button to T20, so `tap id:open_catalog`, `tap id:catalog_critical/t20`, `see id:screen_critical/t20`, `see id:t20_bsms`. Shot `mp-vault-list-detail`.
- **Detail.** With three keys in the keyring, create a 2-of-3 and `tap id:cta_open_vault_1` on the list. `see id:screen_vaults/detail`: the navy block reads MULTISIG 2-OF-3, the wallet's name and `id:vault_balance`, which is "—" until the read lands and then "0.0000 BTC" for a vault nobody paid, with `id:vault_hero_line` "≈ ฿0" under it. The card reads ต้องเซ็น 2 จาก 3 กุญแจ. Under ธุรกรรม (`id:vault_tx_label`), `id:vault_tx_note` reads กำลังดึงธุรกรรม… while the read is out, ยังไม่มีธุรกรรม once it found none, and ยังดึงธุรกรรมไม่ได้ในตอนนี้ when the history failed under a known balance or the balance did not reach the end of both chains, which leaves the balance a dash with no line under it. A paid vault lists `id:vault_tx_0`, `id:vault_tx_1` and so on in place of the note: รับ or ส่งออก, the date, the signed amount and the confirmations, a divider under each, and a tap on a row does nothing. A detail opened again within a minute asks nothing. Shot `mp-vault-detail`.
- **Keys.** On the detail, `tap id:key_details_toggle`. It reads ซ่อน, and `id:vault_key_line_0` to `id:vault_key_line_2` read the keyring's lines ("mebit บนเครื่องนี้ · …", "Jade · …") in the order the keys were picked, numbered 1 to 3. A key the keyring cannot name reads กุญแจ N over ไม่ทราบที่มา and its fingerprint. `tap id:key_details_toggle` closes it. Shot `mp-vault-detail-keys`.
- **Close.** `tap id:cta_vault_close` (the X, read as à¸›à¸´à¸”), then `see id:screen_vaults` and `gone id:back`. Then send from the detail (`id:cta_vault_send`, 5.1 and 5.2 as the Multisig bullet of [send](./movie-page-send.md) says, `id:cta_m2_send`), which lands on the detail with no send screen under it. `tap id:cta_vault_close` lands on `id:screen_vaults` with no back chip. Shot `mp-vault-detail-close`.
- The detail, keys and close bullets are written from the code and have not been run yet.
- **Vault balance.** On `id:screen_vaults` a new vault's card `id:cta_open_vault_1` reads "—" until its read lands, then "0.0000" for a vault of three demo keys nobody paid, and `id:vaults_total` adds it to the one-key cards. `tap id:tab_receive_send`, and `id:choose_vault_vault_1_balance` reads "0.0000 BTC" with "≈ ฿0" under it. For a figure above zero, send testnet4 coins to the address `id:screen_vaults/receive` shows and open the list again a minute later, because a vault read in the last minute is not asked again. Shot `mp-vault-balance`.

## Gotchas

- Review needs N verified keys with real tpubs; HW/mebit mocks mint real software keys labeled as HW.
- BSMS text is a file-shaped export string until BIP-129 FFI exists.
- Wallet Created is the only entry on the stack, so there is no back chip, and Android's system Back has no handler on any screen.
- The sheet buttons need a real system UI. Check the saved file name ends in `.bsms` on Android's Files picker, which may rename an unknown type.
