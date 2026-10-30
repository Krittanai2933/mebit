# Movie Page onboarding (00.*)

A user creates a software key on this device, sees Key Created, and backs up the 24 words now or later. App lock follows, then first-run home, where a banner asks for the backup until it is done.

## Sub-features

- `mp-phone-key` generates a software key via UniFFI and shows Key Created with its fingerprint.
- `mp-skip-backup` takes ทำทีหลัง to App Lock and then to home with the backup banner.
- `mp-backup-verify` shows the 24 words in three columns, then asks for three of them.
- `mp-backup-from-home` runs the backup from the home banner and returns to home without the banner.
- `mp-hw-key` pairs a hardware key from Key Source, verifies its fingerprint, confirms the seed backup with the paired device on the card, and reaches home with the backup marked done.

## How to get to it (user POV)

- Welcome → สร้างกระเป๋าใหม่ → มือถือเครื่องนี้ → สำรองตอนนี้ → จดครบแล้ว ไปยืนยัน → pick three words → ยืนยัน → App Lock → home.
- Key Created → ทำทีหลัง → App Lock → home → สำรองเลย on the banner → the same backup and word check, then back to home.
- Welcome → สร้างกระเป๋าใหม่ → the hardware option → pair a Jade or Safe 7 → confirm the fingerprint → confirm the seed backup → home with no backup banner.
- Debug: the route catalog rows NEW WALLET, KEY CREATED, BACKUP, VERIFY WORDS, APP LOCK and HOME. A row opens the screen with whatever session state exists, so Backup Words and Verify Words show no words until a key was made first.

## Driving it with verify.sh

Preconditions:

- iOS only: `verify.sh ios-start` finished in this run and Welcome is showing (`id:screen_welcome`). Android was not re-run for this flow.
- A simulator can play Face ID. Enrol and answer a scan as in [app lock](./movie-page-app-lock.md).

- **Phone key.** `verify.sh ios-tap "id:cta_create"`, `verify.sh ios-see "id:screen_onboarding/key-source"`, `verify.sh ios-tap "id:cta_phone"`, `verify.sh ios-see "id:screen_onboarding/key-created"`, `verify.sh ios-see "id:fingerprint"`. Shot `mp-phone-key`: the eyebrow reads "NEW WALLET · ขั้น 1 / 3", the key card shows the real fingerprint, and the buttons are สำรองตอนนี้ and ทำทีหลัง. There is no back chip.
- **Skip the backup.** `verify.sh ios-tap "id:cta_skip_backup"`, `verify.sh ios-see "id:screen_onboarding/app-lock"`. Shot `mp-lock-create-skipped`: the eyebrow reads "SECURITY · ขั้น 3 / 3" and there is no back chip. Enable the lock as in app lock, then `verify.sh ios-see "id:screen_home/first-run"` and `verify.sh ios-see "id:cta_backup_now"`. Shot `mp-home-backup-pending`: the banner sits above the balance.
- **Backup from the banner.** `verify.sh ios-tap "id:cta_backup_now"`, `verify.sh ios-see "id:screen_onboarding/backup-words"`, `verify.sh ios-see "id:word_0"`. Shot `mp-backup-from-home`: 24 numbered words. Read them from this PNG with the Read tool before going on.
- **Verify the words.** `verify.sh ios-tap "id:cta_to_verify"`, `verify.sh ios-see "id:screen_onboarding/verify-words"`. The screen asks for three positions, and for each one `verify.sh ios-tap "id:chip_<word>"` takes the word at that position from the backup PNG. Shot `mp-verify-picked`. Then `verify.sh ios-tap "id:cta_verify"`, `verify.sh ios-see "id:screen_home/first-run"`, `verify.sh ios-gone "id:cta_backup_now"`. Shot `mp-home-backed-up`: the banner is gone. ยืนยัน stays grey until all three are picked, and a wrong pick shows `id:verify_error` once ยืนยัน is tapped.
- **Hardware key.** `verify.sh ios-tap "id:cta_create"`, `verify.sh ios-see "id:screen_onboarding/key-source"`, `verify.sh ios-tap "id:cta_hardware"`, `verify.sh ios-see "id:screen_keys/hardware"`, `verify.sh ios-tap "id:cta_hw_demo_jade"` (the demo Jade; a real Jade or Safe 7 uses `id:cta_hw_jade` or `id:cta_hw_trezor` and needs the device), `verify.sh ios-see "id:screen_keys/verify"`, `verify.sh ios-tap "id:cta_fill_expected"`, `verify.sh ios-tap "id:cta_verify_match"`, `verify.sh ios-see "id:screen_onboarding/hw-backup-confirm"`, `verify.sh ios-see "id:hw_device_meta"`. Shot `mp-hw-backup-confirm`: the card shows the monogram JD, "Jade ของฉัน", "Bluetooth · " with the key's fingerprint in groups of four, and the green pill เชื่อมแล้ว. The line under the button names Jade and opens nothing. Then `verify.sh ios-tap "id:check_seed_noted"`, `verify.sh ios-tap "id:check_paper_apart"`, `verify.sh ios-tap "id:cta_hw_backup_done"`, `verify.sh ios-see "id:screen_home/first-run"`, `verify.sh ios-gone "id:cta_backup_now"`. Shot `mp-hw-home`: no backup banner. Verified on the simulator with the demo Jade.

## Gotchas

- The hardware path goes Hardware Wallet, then Confirm Key, then HW Backup Confirm, as the Penpot prototype wires it. Add Key (Hardware Wallet, Confirm Key, Keyring) is unchanged, see [keys](./movie-page-keys.md). The debug catalog keeps the flow of the last Key Source or keyring screen it passed through.
- Boards 2.3.1 and 2.4.1 are not built. Their screens are still the demo layouts (2 of 14 and 2 of 15 board texts match), and pairing a real device from Key Source has not been run on the phone.
- Home after the hardware path still offers the single-sig card that says "ใช้กุญแจ iPhone ของฉันที่เพิ่งสร้าง" and opens the demo wallet, because the phone holds no key on this path.
- The three positions and the choices change on every run: read the words from the backup PNG before tapping a chip.
- In the create flow ยืนยัน goes on to App Lock, and ทำทีหลัง on Backup Words goes there too. Started from the home banner, both return to home. The create flow `cta_backup` then `cta_to_verify` then `cta_verify` path was driven with idb taps, not with these commands.
- The Backup Words board has a banner about blocked screenshots that is not built: iOS cannot block screenshots and the app does not on Android.
- Welcome → นำเข้า (`id:cta_import`) opens `MovieRoute.ImportSeed` (`id:screen_onboarding/import-seed`). An import skips the backup screens and goes to App Lock; see [import seed](./movie-page-import-seed.md).
