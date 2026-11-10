# Movie Page keys + critical boards

A user adds a key from three sources, confirms its fingerprint with one tap (the ไม่ตรง link saves nothing), and reaches the keyring. Critical boards T13–T15 are reachable from hardware demo and catalog.

## Sub-features

- `mp-add-key-three-sources` shows two groups: add as a wallet (this device, import) and add as a key for a Vault (mebit on another phone, hardware).
- `mp-hw-scan` lists the Jade and Safe 7 found nearby, or says none was found.
- `mp-verify-mismatch` discards the staged key and returns to the screen it came from.
- `mp-t13-unsupported` shows Unsupported Model from hardware demo.

## How to get to it (user POV)

- Catalog → ADD KEY, or the Keys tab → เพิ่มกุญแจ.
- Hardware → จำลองรุ่นอื่น (Unsupported) → T13.
- Confirm Key → ไม่ตรง · กลับไปเชื่อมต่อ (ไม่บันทึก) → back to the connect screen, or to the pairing screen for a key from another mebit. T14 opens only from the catalog row VERIFY KEY · Fingerprint ไม่ตรง.

## Driving it with verify.sh

Preconditions:

- App running; prefer catalog jump for isolation.

- **Add Key.** Open catalog (`id:open_catalog`), tap the ADD KEY row (`id:catalog_keys/add` or text containing `ADD KEY`), `verify.sh see "id:screen_keys/add"`, `verify.sh see "id:cta_add_this_device"`, `verify.sh see "id:cta_add_import"`, `verify.sh see "id:cta_add_mebit_other"`, `verify.sh see "id:cta_add_hardware"`. Shot `mp-add-key-three-sources`. The first two rows add a wallet and the last two add a key for a Vault.
- **Hardware scan.** From Add Key `verify.sh ios-tap "id:cta_add_hardware"`, or from Key Source `verify.sh ios-tap "id:cta_hardware"`, then `verify.sh ios-see "id:screen_keys/hardware"` and `verify.sh ios-see "id:hw_none_found"`. Shot `mp-hw-scan-empty`: a simulator has no Bluetooth, so the scan ends with the pill "Bluetooth เปิดอยู่ · ไม่พบอุปกรณ์", the line `id:hw_none_found` and the link `id:cta_hw_rescan`. `verify.sh ios-tap "id:cta_hw_rescan"` and `verify.sh ios-see "id:hw_none_found"` run the scan again. A real Jade or Safe 7 in range adds a row with its own เชื่อม button (`id:cta_hw_jade` or `id:cta_hw_trezor`). That needs the device on a phone and is not recorded here yet. Verified on the simulator for the empty state.
- **Add Key, hardware.** From Add Key, `verify.sh ios-tap "id:cta_add_hardware"`, `verify.sh ios-see "id:screen_keys/hardware"`, `verify.sh ios-tap "id:cta_hw_demo_jade"` twice (the demo buttons are below the first screen, so the first tap only scrolls the button into view), `verify.sh ios-see "id:screen_keys/verify"`, `verify.sh ios-tap "id:cta_verify_match"`, `verify.sh ios-see "id:screen_keys/keyring"`, `verify.sh ios-see "text~:Jade · จำลอง"`. Shot `mp-addkey-hw-keyring`. A demo key is saved with "· จำลอง" after its name. This path ends at the keyring alone, with no back chip, and so does the same match from Key Source, see [onboarding](./movie-page-onboarding.md). From the keyring `verify.sh ios-tap "id:cta_keyring_add"` returns to Add Key. Verified on the simulator. On 2026-11-11 the end on the keyring alone ran for a pasted key from Add Key and for a hardware start from Key Source (run `20261111-005741-ios`). The demo Jade from Add Key has not run again.
- **Add Key, this device.** `id:cta_add_this_device` no longer stages a key. It adds a wallet, so it goes to Key Created and ends on the Wallet list, see [several wallets](./several-wallets.md). The stand-in key and its จำลอง label are gone, and a key saved that way before stays on the keyring.
- **Mismatch (mebit on another phone).** From Add Key, `verify.sh ios-tap "id:cta_add_mebit_other"`, `verify.sh ios-tap "id:cta_pair_demo"`, `verify.sh ios-see "id:screen_keys/verify"`, `verify.sh ios-tap "id:cta_verify_mismatch"`, `verify.sh ios-see "id:screen_keys/pair-mebit"`. Shot `mp-verify-mismatch`. The staged key is not saved: after a match and then a mismatch, the catalog row `id:catalog_keys/keyring` lists one key. From a hardware demo the same link returns to `id:screen_keys/hardware` instead. Verified on the simulator.
- **T13.** On the hardware screen of a simulator, `verify.sh ios-tap "id:cta_hw_unsupported"` twice (the first tap only scrolls), `verify.sh ios-see "id:screen_critical/t13"`, `verify.sh ios-see "id:t13_model"`. Shot `mp-t13-unsupported`. The ปิด button (`id:cta_t13_close`) and the button เลือกอุปกรณ์ที่รองรับ (`id:cta_t13_back`) each return to `id:screen_keys/hardware`. Verified on the simulator.
- **T14.** `verify.sh ios-tap "id:open_catalog"`, `verify.sh ios-see "id:catalog_keys/add"`, `verify.sh ios-tap "id:catalog_critical/t14"` twice (the first tap scrolls the sheet), `verify.sh ios-see "id:screen_critical/t14"`, `verify.sh ios-see "id:mismatch_message"`. Shot `mp-t14-mismatch`. `verify.sh ios-tap "id:cta_t14_back"` returns to `id:screen_keys/hardware`. Verified on the simulator.

## Gotchas

- Confirm Key is one tap. `id:cta_verify_match` saves the staged key under the name in `id:verify_key_name`, and there is no typed check (docs/04 item 41). The link `id:cta_verify_mismatch` discards the key.
- The demo buttons `id:cta_hw_demo_jade`, `id:cta_hw_demo_trezor` and `id:cta_hw_unsupported` sit below the first screen of the hardware screen. The first `ios-tap` only scrolls them into view, so tap again.
- Pair another mebit by pasting three lines (fingerprint, xpub, path) or BSMS 1.0 into `id:pair_paste`, then `id:cta_pair_paste`. `id:cta_pair_demo` still stages a fixture key when the run has no paste. No QR. docs/04 item 26.
