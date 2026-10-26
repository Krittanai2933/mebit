# Movie Page onboarding (00.*)

A user creates a software key on this device, backs up 24 words, verifies chips, enables app lock, and reaches first-run home.

## Sub-features

- `mp-phone-key` generates a software key via UniFFI and shows Key Created.
- `mp-backup-verify` walks backup words and verify chips.
- `mp-home-first-run` reaches first-run home after app lock.

## How to get to it (user POV)

- Welcome → สร้างกระเป๋าใหม่ → มือถือเครื่องนี้ → สำรองเลย → ฉันจดแล้ว → verify chips → เปิดการล็อก or ข้าม.

## Driving it with verify.sh

Preconditions:

- Fresh `verify.sh start` (empty app data).
- Welcome is showing (`id:screen_welcome`).

- **Phone key.** `verify.sh tap "id:cta_create"`, `verify.sh see "id:screen_onboarding/key-source"`, `verify.sh tap "id:cta_phone"`, `verify.sh see "id:screen_onboarding/key-created"`, `verify.sh see "id:fingerprint"`. Shot `mp-phone-key`.
- **Backup.** `verify.sh tap "id:cta_backup"`, `verify.sh see "id:screen_onboarding/backup-words"`, `verify.sh see "id:word_0"`, `verify.sh tap "id:cta_to_verify"`. Shot `mp-backup`.
- **Verify chips.** On `id:screen_onboarding/verify-words`, tap the correct chip labels (words from the backup screen). Wrong chips show `id:verify_error`. After all targets, expect `id:screen_onboarding/app-lock`. Shot `mp-backup-verify`.
- **App lock skip.** `verify.sh tap "id:cta_skip_lock"`, `verify.sh see "id:screen_home/first-run"`. Shot `mp-home-first-run`.

## Gotchas

- Verify chips are shuffled; capture word list from backup PNGs/XML before tapping.
- Import path uses Welcome → นำเข้า (`id:cta_import`) and is not a separate MovieRoute.
