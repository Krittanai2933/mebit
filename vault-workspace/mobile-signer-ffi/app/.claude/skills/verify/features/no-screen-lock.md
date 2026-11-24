# No screen lock

mebit guards every wallet and every payment with the phone's own screen lock, so a phone without one gets only the block screen ตั้งล็อกหน้าจอก่อนใช้ mebit, with hardware wallets too. On Android it has a button that opens the security settings. On iOS it lists the steps to set a passcode. Once the lock is set and the user comes back, the app goes on by itself. If the lock is removed while the app runs, the block covers every screen until the lock is back, and the wallets stay open underneath.

## Sub-features

- `nl-wait` shows only the block on a phone that has had no lock since the app started, before Welcome or the unlock screen.
- `nl-settings` opens the security settings from the block on Android.
- `nl-return` goes on to Welcome, or to the unlock screen when a wallet is saved, once the lock is set and the app comes back.
- `nl-covered` covers every screen when the lock is removed while the app runs. Back sends the app to the background.
- `nl-uncovered` shows the same screen again, with the same wallets, once the lock is back.

## How to get to it (user POV)

- Open mebit on a phone with no screen lock.
- Remove the screen lock in Settings while mebit runs, then come back to mebit.

## Driving it with verify.sh

Preconditions:

- `verify.sh start` sets the screen lock PIN `1234` before the app's first launch. Use `VERIFY_SCREEN_LOCK=0 verify.sh start` for `nl-wait`, `nl-settings` and `nl-return`.
- The app reads the lock again only when it comes back to the front. `adb shell locksettings` changes the lock without that, so after each change leave the app (`verify.sh adb shell input keyevent 3`) and open it again (`verify.sh adb shell monkey -p com.mebit.app -c android.intent.category.LAUNCHER 1`).

- **Wait.** `VERIFY_SCREEN_LOCK=0 verify.sh start`, then `verify.sh see "id:screen_no_screen_lock"`, `verify.sh see "text:ตั้งล็อกหน้าจอก่อนใช้ mebit"` and `verify.sh see "id:no_lock_come_back"` (ตั้งล็อกหน้าจอแล้วกลับมาที่ mebit แอปจะไปต่อเอง). `verify.sh gone "id:screen_welcome"`. Shot `nl-wait`. Ran on API 36 (run `20261124-221256`) and API 30 (run `20261124-222058`).
- **Settings.** `verify.sh tap "id:cta_open_security_settings"`. `verify.sh adb shell dumpsys window | grep mCurrentFocus` names `com.android.settings/...SecurityDashboardActivity` on API 30 and `com.google.android.permissioncontroller/...SafetyCenterActivity` on API 36, whose Security & privacy page shows Device unlock: None and a Set screen lock button. The first read right after the tap can still be null. Shot `nl-settings`. Ran on both runs above.
- **Return.** With Settings in front, `verify.sh adb shell locksettings set-pin 1234`, then `verify.sh adb shell input keyevent 4` back to the app, and `verify.sh see "id:screen_welcome" 20`. Shot `nl-return`. Ran on API 36 (run `20261124-221256`), same pid.
- **Covered.** On a plain `verify.sh start`, create a wallet up to the Wallet list (`id:cta_create`, `id:cta_phone`, `id:cta_skip_backup`, `id:cta_address_type`, `id:cta_skip_lock`, `verify.sh see "id:screen_vaults"`). Then `verify.sh adb shell locksettings clear --old 1234`, leave and open the app, and `verify.sh see "id:screen_no_screen_lock"`. `verify.sh adb shell input keyevent 4` puts the launcher in front, and the process keeps its pid (`verify.sh adb shell pidof com.mebit.app`). Shot `nl-covered`. Ran on API 36 (run `20261124-221256`). `cta_skip_lock` reads the saved copy back, so it shows the system prompt: answer it with `input text 1234` and Enter.
- **Uncovered.** `verify.sh adb shell locksettings set-pin 1234`, open the app, `verify.sh gone "id:screen_no_screen_lock"` and `verify.sh see "id:screen_vaults"` with the same wallet card. Shot `nl-uncovered`. Ran on API 36 (run `20261124-221256`): the same card came back with `id:not_saved_notice` (บันทึกกุญแจไว้ในเครื่องไม่สำเร็จ · จด 24 คำไว้ให้ดี), see the gotcha below.

## Gotchas

- The iOS simulator always reports a passcode, so the block never shows there. Nothing in the app fakes a missing lock, and no probe is kept for it.
- A cold start with no lock always shows the block, even when the process before it had a lock. The app keeps the "had a lock" fact only for one process.
- On Android, removing the screen lock destroys the Keystore key that guards the saved wallet. After the lock is back, a cold start may reach the recovery screen (`id:unlock_broken`) rather than the unlock screen. See [wallet restart](./wallet-restart.md) ("Key gone").
- A popup or sheet that was open when the lock went is its own window, so it draws above the block, and `uiautomator` then dumps only that window, so `see "id:screen_no_screen_lock"` fails while the block is there. Seen with a Wallet card's ⋯ menu (`id:cta_more_spending_1`) on API 36 (run `20261124-225203`, shot `02-sheet-menu-after-clear`). Every action in such a menu still asks the owner, which answers Unavailable with no lock.
