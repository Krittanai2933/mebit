# Movie Page onboarding (00.*)

A user creates a software key on this device, sees Key Created, and backs up the 24 words now or later. App lock follows, then first-run home, where a banner asks for the backup until it is done.

## Sub-features

- `mp-phone-key` generates a software key via UniFFI and shows Key Created with its fingerprint.
- `mp-skip-backup` takes ทำทีหลัง to App Lock and then to home with the backup banner.
- `mp-backup-verify` shows the 24 words in three columns, then asks for three of them.
- `mp-backup-from-home` runs the backup from the home banner and returns to home without the banner.

## How to get to it (user POV)

- Welcome → สร้างกระเป๋าใหม่ → มือถือเครื่องนี้ → สำรองตอนนี้ → จดครบแล้ว ไปยืนยัน → pick three words → ยืนยัน → App Lock → home.
- Key Created → ทำทีหลัง → App Lock → home → สำรองเลย on the banner → the same backup and word check, then back to home.
- Debug: the route catalog rows NEW WALLET, KEY CREATED, BACKUP, VERIFY WORDS, APP LOCK and HOME. A row opens the screen with whatever session state exists, so Backup Words and Verify Words show no words until a key was made first.

## Driving it with verify.sh

Preconditions:

- iOS only: `verify.sh ios-start` finished in this run and Welcome is showing (`id:screen_welcome`). Android was not re-run for this flow.
- A simulator can play Face ID. Enrol and answer a scan as in [app lock](./movie-page-app-lock.md).

- **Phone key.** `verify.sh ios-tap "id:cta_create"`, `verify.sh ios-see "id:screen_onboarding/key-source"`, `verify.sh ios-tap "id:cta_phone"`, `verify.sh ios-see "id:screen_onboarding/key-created"`, `verify.sh ios-see "id:fingerprint"`. Shot `mp-phone-key`: the eyebrow reads "NEW WALLET · ขั้น 1 / 3", the key card shows the real fingerprint, and the buttons are สำรองตอนนี้ and ทำทีหลัง. There is no back chip.
- **Skip the backup.** `verify.sh ios-tap "id:cta_skip_backup"`, `verify.sh ios-see "id:screen_onboarding/app-lock"`. Shot `mp-lock-create-skipped`: the eyebrow reads "SECURITY · ขั้น 3 / 3" and there is no back chip. Enable the lock as in app lock, then `verify.sh ios-see "id:screen_home/first-run"` and `verify.sh ios-see "id:cta_backup_now"`. Shot `mp-home-backup-pending`: the banner sits above the balance.
- **Backup from the banner.** `verify.sh ios-tap "id:cta_backup_now"`, `verify.sh ios-see "id:screen_onboarding/backup-words"`, `verify.sh ios-see "id:word_0"`. Shot `mp-backup-from-home`: 24 numbered words. Read them from this PNG with the Read tool before going on.
- **Verify the words.** `verify.sh ios-tap "id:cta_to_verify"`, `verify.sh ios-see "id:screen_onboarding/verify-words"`. The screen asks for three positions, and for each one `verify.sh ios-tap "id:chip_<word>"` takes the word at that position from the backup PNG. Shot `mp-verify-picked`. Then `verify.sh ios-tap "id:cta_verify"`, `verify.sh ios-see "id:screen_home/first-run"`, `verify.sh ios-gone "id:cta_backup_now"`. Shot `mp-home-backed-up`: the banner is gone. ยืนยัน stays grey until all three are picked, and a wrong pick shows `id:verify_error` once ยืนยัน is tapped.

## Gotchas

- The three positions and the choices change on every run: read the words from the backup PNG before tapping a chip.
- In the create flow ยืนยัน goes on to App Lock, and ทำทีหลัง on Backup Words goes there too. Started from the home banner, both return to home. The create flow `cta_backup` then `cta_to_verify` then `cta_verify` path was driven with idb taps, not with these commands.
- The Backup Words board has a banner about blocked screenshots that is not built: iOS cannot block screenshots and the app does not on Android.
- Welcome → นำเข้า (`id:cta_import`) opens `MovieRoute.ImportSeed` (`id:screen_onboarding/import-seed`). An import skips the backup screens and goes to App Lock; see [import seed](./movie-page-import-seed.md).
