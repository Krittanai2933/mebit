# Hardware wallets (one key on a Jade or a Safe 7)

A one-key wallet whose key is on a Jade or a Trezor Safe 7 is created from Policy's single-sig card (Penpot 3.1.1, 3.2.2 or 3.2.3, 3.3.2, 2.3.3 to 2.3.6, 2.4.1, 3.5.2), listed after the phone's wallets (1.8.1), opened on a Home like the phone wallet's (4.1.5, 4.1.5.1), and pays through Send, Review 4.5.2, Sign 4.5.3 or 4.5.4, the confirmed 4.5.5 and the slip 4.6. A simulator has no Bluetooth, so a debug build launched with `MEBIT_FAKE_DEVICE=1` reaches a fake Jade and a fake Safe 7 that hold the public test phrase (fingerprint 73c5 da0a) and sign with it. The fake Safe 7 asks for any six digits until it is paired once, then connects with the pairing the phone kept, and forgets it at a relaunch.

## Sub-features

- `hw-choose-key` Choose Key (3.2.1 to 3.2.3) offers this phone and every confirmed Jade or Safe 7 key, one pick at a time, with the line naming the device.
- `hw-address-type` Address Type (3.3.2) offers the four types, Native SegWit first picked, and says the next step reads this type's key from the device.
- `hw-read-jade` the read screen for a Jade (2.3.3): only Jades are listed, the steps say to enter the PIN and name the type and path.
- `hw-read-safe7` the read screen for a Safe 7: the code field only once it asks (2.3.4), the check and two lines when the phone kept its pairing (2.3.5), the orange title when it forgot this phone (2.3.6).
- `hw-confirm` Confirm Key (2.4.1) saves the wallet, Wallet Created (3.5.2) shows it alone.
- `hw-list` the card กระเป๋า Jade after the phone's wallets, its balance in the total, and on the รับ/ส่ง chooser.
- `hw-home` Home 4.1.5 with the key on the device, and 4.1.5.1's JD or TRZ row when opened. No upgrade card.
- `hw-receive` Receive with the wallet's address and no backup gate.
- `hw-send` Send, Review 4.5.2 with เซ็นบน Jade, Sign 4.5.3 then 4.5.5 with ส่งธุรกรรม.
- `hw-remove` the card's ⋯ removes it after 1.8.8's sheet and the owner check.

## How to get to it (user POV)

- Wallet list + → Policy (กระเป๋าเดี่ยว) → ต่อไป เลือกกุญแจ → Choose Key. A Jade or Safe 7 key must be on the Keyring first: tab กุญแจ → + → Hardware wallet.
- Wallet list → the card กระเป๋า Jade → Home → รับ or ส่ง.

## Driving it with verify.sh

Preconditions:

- `verify.sh ios-start` on a fresh simulator with Face ID enrolled, then a phone wallet set up (Welcome → create → skip the backup → Address Type → App Lock), so the Wallet list shows.
- Relaunch with the switch: `. build/verify/ios.env; SIMCTL_CHILD_MEBIT_FAKE_DEVICE=1 xcrun simctl launch --terminate-running-process "$UDID" com.mebit.app`, then pass Face ID (`notifyutil -p com.apple.BiometricKit_Sim.pearl.match`). A launch without the variable has no fake, and the hardware screens then look for real Bluetooth.
- Send to Review needs the public phrase's testnet4 wallet to hold coins. Anyone can spend that phrase's coins (on 12 Nov 2026 its receive address 0 held 1,000 sats after 148 transactions), so read Home's balance first and stop at Review when it is 0.0000.

Steps, each a shot:

- **Add the fake Jade's key.** `ios-tap "id:tab_keys"`, `ios-tap "id:cta_keyring_add"`, `ios-tap "id:cta_add_hardware"`, `ios-see "id:screen_keys/hardware"`, `ios-tap "id:cta_hw_jade"`, `ios-see "id:screen_keys/verify"`, `ios-tap "id:cta_verify_match"`. The Keyring lists Jade ของฉัน, 73c5 da0a. Shot `hw-key-jade`.
- **Add the fake Safe 7's key.** Again with `id:cta_hw_trezor`. The card shows `id:hw_pairing_first` and `id:hw_pairing_code`; type `123456` into it. Confirm Key, then the Keyring lists Trezor Safe 7 ของฉัน. Shot `hw-key-safe7`.
- **Create กระเป๋า Jade.** `ios-tap "id:tab_wallets"`, `ios-tap "id:cta_vaults_add"`, `ios-tap "id:policy_single"`; the eyebrow reads POLICY · ขั้น 1/3. `ios-tap "id:cta_policy_next"`, `ios-see "id:screen_vault/choose-key"`, `ios-tap "id:pick_key_1_73c5da0a"` (the Jade's row; its id is the Keyring's), `ios-see "id:choose_key_note"`, `ios-tap "id:cta_choose_key_next"`, `ios-see "id:screen_vault/hw-address-type"` with Native SegWit picked, `ios-tap "id:cta_hw_address_type"`, `ios-see "id:screen_vault/hw-read-key"` (`id:hw_steps` names m/84'/1'/0'), `ios-tap "id:cta_hw_jade"`, `ios-see "id:screen_vault/hw-confirm-key"`, `ios-tap "id:cta_verify_match"`, `ios-see "id:screen_vault/hw-created"` with the title กระเป๋า Jade พร้อมใช้งาน and no back chip. Shots `hw-choose-key`, `hw-address-type`, `hw-read-jade`, `hw-created`.
- **The list.** `ios-tap "id:cta_open_wallet"`, `ios-see "id:screen_vaults"`, `id:card_hw_hw_1` reads กุญแจเดียว · Jade ของฉัน, after `id:card_spending`. Shot `hw-list`.
- **Home.** `ios-tap "id:card_hw_hw_1"`, `ios-see "id:screen_hw/home"`, `id:key_status_line` reads กุญแจ: Jade ของฉัน · อยู่บนอุปกรณ์, `id:cta_s1_vault` is gone. `ios-tap "id:key_details_toggle"`: the row JD, Jade ของฉัน, Jade · Native SegWit · 73c5 da0a. Shot `hw-home`.
- **Receive.** `ios-tap "id:cta_s1_receive"`, `ios-see "id:screen_hw/receive"`, the address starts tb1q. No backup gate. Shot `hw-receive`.
- **Send to Review.** Back, `ios-tap "id:cta_s1_send"`, `ios-see "id:screen_hw/send"`, paste an address of another wallet of yours, type an amount under the balance, `ios-tap "id:cta_s4_review"`, `ios-see "id:screen_hw/review"`, the button reads เซ็นบน Jade (`id:cta_hw_review_sign`). Shot `hw-review`.
- **Sign with the fake.** `ios-tap "id:cta_hw_review_sign"`, `ios-see "id:screen_hw/sign"`: for about 3 s `id:hw_sign_status` reads รอยืนยัน and `id:hw_sign_details` shows the amount, the whole address in groups of four, the change and the fee (4.5.3). Then the title reads ยืนยันบนอุปกรณ์แล้ว, the pill ยืนยันแล้ว, and `id:cta_hw_send` reads ส่งธุรกรรม (4.5.5). Shots `hw-sign`, `hw-confirmed`. Stop here: do not tap ส่งธุรกรรม from a simulator, which would broadcast.
- **กระเป๋า Trezor, pairing remembered.** In the same run (the fake Safe 7 still holds the pairing): Wallet list + → Policy → single-sig → Choose Key → the Safe 7's row → Address Type → `ios-tap "id:hw_address_type_taproot"` (both fakes hold one phrase, so Native SegWit would be กระเป๋า Jade's addresses again and Confirm Key would refuse it as Wallet นี้อยู่ในเครื่องนี้แล้ว · กระเป๋า Jade) → `ios-tap "id:cta_hw_address_type"`. The card shows `id:hw_pairing_kept` (2.3.5), and the steps say กด Allow บนหน้าจอ Trezor. `ios-tap "id:cta_hw_trezor"`: no code field shows, and Confirm Key follows. Save, then Home of กระเป๋า Trezor, Send to Review (เซ็นบน Trezor), Sign (4.5.4) to the confirmed screen. Shots `hw-read-safe7-kept`, `hw-trezor-confirmed`. A run relaunched with the switch shows 2.3.6 (`id:hw_pairing_forgot`) on the next Safe 7 connect.
- **Remove.** On the list, `ios-tap "id:cta_more_hw_1"`, then the menu's ลบ Wallet, `id:dialog_remove_wallet` reads ลบกระเป๋า Jade ออกจากเครื่องนี้ไหม and กระเป๋าจะถูกลบออกจากเครื่องนี้ · Jade ยังเก็บกุญแจนี้ไว้. `id:cta_remove_confirm`, then Face ID, and the card leaves the list. Shot `hw-remove`.

## Gotchas

- The rows of Choose Key carry the Keyring's key ids (`pick_<key id>`); read them from `idb ui describe-all` on the Keyring or Choose Key.
- Taps below y 800 on a simulator are lost: scroll first. The debug link floats over the bottom centre, so tap a wide button at its left end.
- The fake signs only for the public phrase. A wallet made from any other key is refused on Sign with Fingerprint ไม่ตรง.
- On Android the switch is the launch intent's extra: `adb shell am start -n com.mebit.app/.MainActivity --es MEBIT_FAKE_DEVICE 1`.
- The fake's pairing lives in memory, so a relaunch makes it a Safe 7 that forgot this phone (2.3.6), while the phone still keeps the pairing in the Keychain.
- The phone keeps a pairing in the real store, a Keychain item that needs a passcode set. Whether a simulator keeps such an item has not been checked. If it does not, the Trezor steps show 2.3.4's code field (`id:hw_pairing_first`) in place of 2.3.5, which is a finding about the simulator, not the fake.
