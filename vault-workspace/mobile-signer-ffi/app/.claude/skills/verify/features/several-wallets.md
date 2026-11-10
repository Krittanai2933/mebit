# Several wallets

A phone holds up to ten individual wallets. The list 3.5 shows each one with its own balance, Add Key adds a wallet or a key for a Vault as two different things, the bar's รับ / ส่ง list and Home's own buttons pick the wallet, a wallet that no vault uses can be removed, and Forget erases every wallet. A key added as a key for a Vault ends on the Keyring and never becomes a wallet.

## Sub-features

- `sw-list` 3.5 lists every wallet, then the vaults, with the buttons เพิ่มกระเป๋า and ประกอบ Vault ใหม่ side by side.
- `sw-add-key` Add Key shows the group เพิ่มเป็นกระเป๋าใหม่ and the group เพิ่มเป็นกุญแจสำหรับ Vault.
- `sw-add-create` a second wallet is created with no App Lock step and ends on its own Home, named กระเป๋า 2.
- `sw-add-refused` an import of words already on the phone is refused, and a refused add shows one line on Add Key.
- `sw-choose` the bar's list and the lists' balances show every wallet, and two Backs return to where the user started.
- `sw-restart` a relaunch asks Face ID once and opens the wallet used last.
- `sw-keys` each wallet's key is on the Keyring, named มือถือ plus the wallet's name, and can be picked for a vault.
- `sw-remove` Home's key details offer ลบกระเป๋านี้ออกจากเครื่อง only with two or more wallets, a sheet confirms it, a key used by a vault shows ลบไม่ได้ in its place, and removing keeps the other wallet.
- `sw-forget` Forget on the unlock screen says it erases every wallet and erases them all.

## How to get to it (user POV)

- Home → tab กระเป๋า (the wallets list) → เพิ่มกระเป๋า → Add Key.
- Keyring → เพิ่มกุญแจอีก → Add Key. A wallet added from here returns to the Keyring.
- Home → รายละเอียด (key details) → ลบกระเป๋านี้ออกจากเครื่อง.
- Cold start → the unlock screen → ใช้ 24 คำแทน → the Forget sheet.

## Driving it with verify.sh

Preconditions:

- `verify.sh ios-start` on a fresh simulator with Face ID enrolled, and a wallet imported by `.audit/overnight-tools`' testnet driver (`drive.sh import funder native_segwit`, which never prints the words). Never the physical iPhone.
- `.audit/several-wallets-sim/check.py all` runs every step below in order and prints one PASS or FAIL line for each. `check.py w4` runs one.

- **List and buttons.** `verify.sh ios-tap "id:tab_wallets"`, `verify.sh ios-see "id:screen_vaults"`, then `id:cta_add_wallet` and `id:cta_new_vault` are 175 by 52 and 12 apart. Shot `sw-list`.
- **Add Key.** `verify.sh ios-tap "id:cta_add_wallet"`, `verify.sh ios-see "id:screen_keys/add"`. The wallet rows are `id:cta_add_this_device` and `id:cta_add_import`. The key rows are `id:cta_add_mebit_other` and `id:cta_add_hardware`. Shot `sw-add-key`.
- **Create a second wallet.** `verify.sh ios-tap "id:cta_add_this_device"`, `id:cta_skip_backup`, `id:cta_address_type`. No App Lock screen shows. `verify.sh ios-see "id:screen_home/first-run"`, `verify.sh ios-tap "id:cta_singlesig"`, and Home is named กระเป๋า 2. Shot `sw-home-2`.
- **Two cards.** `id:card_spending` and `id:card_spending_2` on 3.5, each with its own name and balance. Shot `sw-list-two`.
- **The bar's list.** `verify.sh ios-tap "id:tab_receive_send"` shows `id:choose_spending` and `id:choose_spending_2`. A pick pushes that wallet's screen, and `verify.sh ios-tap "id:back"` twice returns to the Home the user started from.
- **Refused import.** Add Key → `id:cta_add_import` → type the words of a wallet already on the phone → import. The screen stays on Import Seed and `id:error` shows a line.
- **Keyring and vault.** `id:tab_keys` lists `id:key_onboard_<fingerprint>` once per wallet. Choose Keys offers `id:pick_onboard_<fingerprint>` for each.
- **Blocked and remove.** On a wallet whose key a vault uses, `id:key_details_toggle` shows `id:remove_blocked`. On another, it shows `id:cta_remove_wallet`, which opens the sheet (`id:dialog_remove_wallet`) with `id:cta_remove_confirm` and `id:cta_remove_cancel`. Confirm asks the owner and ends on 3.5 with the other wallet. Shot `sw-remove-sheet`.
- **Forget.** `xcrun simctl terminate`, `xcrun simctl launch`, `notifyutil -p com.apple.BiometricKit_Sim.pearl.nomatch`, tap Cancel at (201, 519), `verify.sh ios-tap "id:cta_unlock_forget"`. The sheet title reads ลบกระเป๋าทั้งหมดออกจากเครื่องนี้ไหม. After the confirm and the owner check, Welcome shows and a relaunch still shows Welcome.

## Gotchas

- A wallet that is not backed up opens at First Run after a relaunch, so the Home check taps `id:cta_singlesig` once more.
- Taps on a simulator below y 800 are lost. Scroll first, let the scroll settle, then tap. The debug link floats over the bottom centre, so tap a wide button at its left end.
- A wallet whose key a vault uses cannot be removed, so a check that removes one keeps that key out of the vault.
- The sheets are their own windows. `idb ui describe-all` lists their tags, `verify.sh ios-see "id:screen_*"` does not apply to them.
- Never print or copy the driver's `wallets.json`. Read only its `fingerprint` field.
