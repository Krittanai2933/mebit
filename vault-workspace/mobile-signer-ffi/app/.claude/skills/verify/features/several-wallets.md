# Several wallets

A phone holds up to ten individual wallets. The Wallet list (Penpot 1.8) shows each one with its own balance and backup tag and a total, Add Key adds a wallet or a key for a Vault as two different things, the bar's รับ / ส่ง list and Home's own buttons pick the wallet, a wallet that no vault uses and a several-key wallet can be removed from their card's ⋯, and Forget erases every wallet. A key added as a key for a Vault ends on the Keyring and never becomes a wallet.

## Sub-features

- `sw-list` the Wallet list shows the title, a 32 by 32 + that opens Policy, the total and every wallet, then the vaults, with no buttons under them.
- `sw-add-key` Add Key shows one list of four cards, with the bar and the Keys tab lit. The first (this phone) and the last (import) add a wallet, and the middle two (another mebit phone, hardware) add a key for a Vault.
- `sw-add-create` a second wallet is created with no App Lock step and ends on the Wallet list with กระเป๋า 2 as a card.
- `sw-add-refused` an import of words already on the phone is refused, and a refused add shows one line on Add Key.
- `sw-choose` the bar's list and the lists' balances show every wallet, and two Backs return to where the user started.
- `sw-restart` a relaunch asks Face ID once and opens the Wallet list.
- `sw-keys` each wallet's key is on the Keyring, named มือถือ plus the wallet's name, and can be picked for a vault.
- `sw-remove` each card on the Wallet list has a ⋯ with a floating menu (Penpot 1.8.A1 to A3). A one-key wallet's menu offers ลบ Wallet only with two or more wallets, a saved wallet, a book that can be read and a key no vault uses, a sheet confirms it (1.8.8), and removing keeps the other wallet. A several-key wallet's menu offers ลบ Wallet while a wallet is saved and the book can be read. With no wallet saved (a phone with no screen lock) or no store (Android 10 and older) it shows ลบไม่ได้ · Wallet นี้ยังไม่ได้บันทึกในเครื่อง instead. Its sheet (1.8.9) can export the BSMS file first, then removes it after one owner check and leaves its keys on the Keyring. A blocked menu shows a grey ลบ Wallet and the reason. Home's key details have no remove row.
- `sw-forget` Forget on the unlock screen says it erases every wallet and erases them all.

## How to get to it (user POV)

- Tab กุญแจ → the + on the title row → Add Key. A wallet added from here ends on the Wallet list.
- Wallet list + → Policy → Choose Keys → เพิ่มกุญแจอีก → Add Key. A wallet added from here ends back on Choose Keys, see [vault](./movie-page-vault.md).
- Wallet list → a card's ⋯ → ลบ Wallet.
- Cold start → the unlock screen → ใช้ 24 คำแทน → the Forget sheet.

## Driving it with verify.sh

Preconditions:

- `verify.sh ios-start` on a fresh simulator with Face ID enrolled, and a wallet imported by `.audit/overnight-tools`' testnet driver (`drive.sh import funder native_segwit`, which never prints the words). Never the physical iPhone.
- `.audit/several-wallets-sim/check.py all` runs every step below in order and prints one PASS or FAIL line for each. `check.py w4` runs one.

- **List and +.** `verify.sh ios-tap "id:tab_wallets"`, `verify.sh ios-see "id:screen_vaults"`, then the title reads "Wallet ของคุณ", `id:cta_vaults_add` is 32 by 32 and `id:vaults_total` shows. `id:cta_add_wallet`, `id:cta_new_vault` and `id:cta_vaults_keyring` are gone. Shot `sw-list`.
- **Add Key.** `verify.sh ios-tap "id:tab_keys"`, `verify.sh ios-tap "id:cta_keyring_add"`, `verify.sh ios-see "id:screen_keys/add"`. The cards, top down, are `id:cta_add_this_device` (a wallet), `id:cta_add_mebit_other` (a key), `id:cta_add_hardware` (a key) and `id:cta_add_import` (a wallet), and `id:tab_keys` shows. Shot `sw-add-key`.
- **Create a second wallet.** `verify.sh ios-tap "id:cta_add_this_device"`, `id:cta_skip_backup`, `id:cta_address_type`. No App Lock screen shows. `verify.sh ios-see "id:screen_vaults"`, `verify.sh ios-gone "id:back"`, and `id:card_spending_2` carries the tag ยังไม่ได้สำรอง. `verify.sh ios-tap "id:card_spending_2"` opens Home named กระเป๋า 2. Shot `sw-home-2`.
- **Two cards.** `id:card_spending` and `id:card_spending_2` on 3.5, each with its own name and balance. Shot `sw-list-two`.
- **The bar's list.** `verify.sh ios-tap "id:tab_receive_send"` shows `id:choose_spending` and `id:choose_spending_2`. A pick pushes that wallet's screen, and `verify.sh ios-tap "id:back"` twice returns to the Home the user started from.
- **Refused import.** Add Key → `id:cta_add_import` → type the words of a wallet already on the phone → import. The screen stays on Import Seed and `id:error` shows a line.
- **Keyring and vault.** `id:tab_keys` lists `id:key_onboard_<fingerprint>` once per wallet, each a card named มือถือ plus the wallet's name with the pill ยืนยันแล้ว, or an amber ยังไม่ยืนยัน and a place at the top of the list while the wallet's backup is not done. Choose Keys offers `id:pick_onboard_<fingerprint>` for each.
- **Blocked and remove.** On the Wallet list a one-key wallet's ⋯ is `id:cta_more_spending_<place number>`, `id:cta_more_spending_1` for the first, and a tap on it never opens the card. On a wallet whose key a vault uses, its menu holds `id:menu_blocked_spending_<n>` reading ลบไม่ได้ · กุญแจนี้ใช้อยู่ใน plus the vault's name. On another, `id:menu_delete_spending_<n>` opens the sheet (`id:dialog_remove_wallet`) with `id:cta_remove_confirm` and `id:cta_remove_cancel`. Confirm asks the owner and ends on 3.5 with the other wallet, and a removal that fails shows `id:remove_failed` on the list. Shot `sw-remove-sheet`.
- **Remove a several-key wallet.** `id:cta_more_<vault id>` opens a menu with `id:menu_delete_<vault id>`, which opens `id:dialog_remove_vault` with `id:cta_vault_export` (ส่งออก BSMS ก่อน), `id:cta_vault_remove` (ลบออกจากเครื่องนี้) and `id:cta_vault_remove_cancel`. A saved export turns the first button into ส่งออก BSMS แล้ว and makes the remove the filled button. The remove asks the owner once with the reason ลบ plus the wallet's name and ออกจากเครื่องนี้, the card leaves the list and the Keyring keeps its keys. A removal that cannot be saved keeps the card and shows `id:remove_failed` on the list. Shot `sw-remove-vault`.
- **Forget.** `xcrun simctl terminate`, `xcrun simctl launch`, `notifyutil -p com.apple.BiometricKit_Sim.pearl.nomatch`, tap Cancel at (201, 519), `verify.sh ios-tap "id:cta_unlock_forget"`. The sheet title reads ลบกระเป๋าทั้งหมดออกจากเครื่องนี้ไหม. After the confirm and the owner check, Welcome shows and a relaunch still shows Welcome.

## Gotchas

- A relaunch opens the Wallet list whether or not a wallet is backed up, so the Home check taps the wallet's card.
- Taps on a simulator below y 800 are lost. Scroll first, let the scroll settle, then tap. The debug link floats over the bottom centre, so tap a wide button at its left end.
- A wallet whose key a vault uses cannot be removed, so a check that removes one keeps that key out of the vault.
- The sheets are their own windows. `idb ui describe-all` lists their tags, `verify.sh ios-see "id:screen_*"` does not apply to them.
- On Android a ⋯ menu is its own window too, where tags are not resource ids, so tap ลบ Wallet by its text. Whether `idb ui describe-all` lists the menu's tags on iOS has not been checked.
- Never print or copy the driver's `wallets.json`. Read only its `fingerprint` field.
