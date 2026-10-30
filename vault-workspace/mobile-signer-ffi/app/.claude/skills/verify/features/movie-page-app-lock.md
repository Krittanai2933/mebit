# Movie Page app lock

After onboarding the user turns on Face ID. From then on the app asks for Face ID again after more than a minute in the background, and before it signs a single-sig send. The text button uses the phone's own passcode instead.

## Sub-features

- `mp-lock-enable` turns the lock on with a Face ID scan.
- `mp-lock-failed` shows the failed state with the attempts left.
- `mp-lock-unlock` shows the unlock screen when the app comes back after a minute.
- `mp-lock-sign` asks for Face ID before a send is signed.
- `mp-lock-unavailable` says so where nothing can scan.

## How to get to it (user POV)

- Create or import a key, then the App Lock screen. The unlock screen and the sign check come later, from using the app.

## Driving it with verify.sh

Preconditions:

- A simulator can play Face ID. Enrol once per simulator: `xcrun simctl spawn booted notifyutil -s com.apple.BiometricKit.enrollmentChanged 1`, then `xcrun simctl spawn booted notifyutil -p com.apple.BiometricKit.enrollmentChanged`. Answer a pending scan with `xcrun simctl spawn booted notifyutil -p com.apple.BiometricKit_Sim.pearl.match`, or `.pearl.nomatch` for a miss.
- The scan prompt and the passcode sheet are iOS's own, outside the app's accessibility tree. XCUITest cannot tap or type into them, so a simulator run never taps ใช้รหัส 6 หลักแทน: on a simulator it opens the passcode sheet and stays there.
- A real scan on the phone needs the iPhone 15, with you in the loop: the phone unlocked, plugged in, Developer Mode on, Settings, Developer, Enable UI Automation on, Face ID enrolled. Start with `VERIFY_IOS_DEVICE=<udid> VERIFY_IOS_TEAM=<team id> VERIFY_IOS_BUNDLE_ID=com.<you>.mebit.verify VERIFY_IOS_KEEP_APP=1 verify.sh ios-start`. The first launch is refused until the developer is trusted (Settings, General, VPN & Device Management); run `ios-start` again after that. Export the same variables for every later `verify.sh` call, and say what is about to happen before each step so the person holding the phone is ready.
- On the phone, the person taps and scans, and the agent only takes `ios-shot` while a Face ID or passcode sheet is up. Every `ios-tap`, `ios-see`, `ios-type` and `ios-gone` starts a test run that wakes the app, and iOS cancels the sheet when that happens. Navigate with `ios-tap` first, then hand over.
- Do not loop over command strings in zsh with `set -- $s`: it does not split and `verify.sh` just prints its usage. Write each command out.

- **Unavailable (simulator, not enrolled).** Reach App Lock through the import flow. `verify.sh ios-tap "id:cta_enable_lock"`, `verify.sh ios-see "id:lock_unavailable"`. Shot `mp-lock-unavailable`.
- **Enable (simulator).** Enrol, then `verify.sh ios-tap "id:cta_enable_lock"`, `notifyutil … pearl.match`, `verify.sh ios-see "id:screen_home/first-run"`. Shot `mp-lock-enabled`.
- **Miss (simulator).** After a pending scan, `pearl.nomatch` shows iOS's own "Face Not Recognized" sheet with Try Face ID Again and Cancel. The app's failed state (`id:lock_failed`, board 1B.5.1) only shows once iOS reports the second miss, which a simulator run cannot reach. A later `pearl.match` still completes the pending scan.
- **Unlock on return (simulator).** With the lock on and the app at Home, `xcrun simctl launch booted com.apple.Preferences` leaves the app. Wait 65 s, then `xcrun simctl launch booted com.mebit.app`. `verify.sh ios-see "id:screen_unlock"` passes and the Face ID sheet is up. After `pearl.match`, `verify.sh ios-gone "id:screen_unlock"` and `verify.sh ios-see "id:screen_home/first-run"`. Shot `mp-lock-unlock`.
- **Sign check (simulator).** From Home: `verify.sh ios-tap "id:cta_singlesig"`, `verify.sh ios-tap "id:cta_s1_send"`, `verify.sh ios-tap "id:cta_s4_review"`, `verify.sh ios-see "id:screen_send/s5"`, `verify.sh ios-tap "id:cta_s5_sign"`. The Face ID sheet shows over the review screen and nothing is signed: `verify.sh ios-see "id:screen_send/s5"` still passes. Shot `mp-lock-sign`. After `pearl.match` signing goes on to the broadcast, and with the demo address and an empty wallet it ends in an error line under the button ("invalid PSBT json: base58 error"), not in S6.
- **Failed scan (phone).** On a fresh App Lock the person taps เปิดใช้ Face ID, covers their face, taps Try Face ID Again, and covers it once more. The app then shows `id:lock_failed`: the title "Face ID ไม่ผ่าน", an orange ring on the tile, and the FACE ID · FAILED box. iOS counts misses across attempts, including cancelled ones, and locks Face ID out after five. The first phone run went straight to the lockout: the screen read 5 misses and 0 left, and the primary button read "ใช้รหัส 6 หลัก". "2 misses, 3 left" after the first failed result is not confirmed on the phone. Shot `mp-lock-failed`.
- **Passcode (phone).** The person taps the passcode button (ใช้รหัส 6 หลัก or ใช้รหัส 6 หลักแทน), iOS's passcode sheet opens, and the correct code enables the lock, clears the Face ID lockout and goes to `id:screen_home/first-run`. Verified on the iPhone 15.
- **Unlock on return (phone).** With the lock on and the app at Home, `xcrun devicectl device process launch --device <udid> com.apple.Preferences` leaves the app. Wait 70 s, then `xcrun devicectl device process launch --device <udid> com.<you>.mebit.verify` brings it back. The unlock screen and the Face ID sheet come up on their own, and the first scan starts at once, so the person must already be looking at the phone. A miss shows iOS's "Face Not Recognized" sheet. A match goes back to Home. Verified on the iPhone 15.
- **Sign check (phone).** Navigate with `ios-tap` up to `id:screen_send/s5`, then the person taps เซ็นและส่ง and scans. Signing continues to the broadcast, which ends in "invalid PSBT json: base58 error" for the demo address and empty wallet. Verified on the iPhone 15.
- **Refused sign (phone).** The Face ID sheet has no Cancel until a scan fails, and for signing a failed scan moves to iOS's passcode sheet. The person covers their face, then presses Cancel on the passcode sheet: `id:sign_refused` shows "ยืนยันตัวตนไม่สำเร็จ ลองอีกครั้ง" and nothing is signed. An error line from an earlier attempt can stay under it. Verified on the iPhone 15.

## Gotchas

- `ios-stop` removes the app from the phone and the developer trust goes with it. `VERIFY_IOS_KEEP_APP=1` keeps it.
- Android has no biometric code yet, so the lock never turns on there and the sign check is skipped.
- Hardware wallets (`WalletLink.signPsbt`) confirm on their own screen and are not gated by this lock.
- The lock state is in memory, like the keys: a cold start is onboarding again.
- Leaving the app with `simctl launch` of another app leaves a "‹ Settings" breadcrumb in the status bar. That is the simulator, not the app.
- `devicectl device capture screenshot` shows the whole screen, system sheets included, and does not wake the app. It is the only safe way to look while a sheet is up.
- After a key was created, App Lock shows the eyebrow "SECURITY · ขั้น 3 / 3" and no back chip (boards 1.3.1 and 1.6); after an import it shows neither. The failed state adds "SECURITY · Face ID ไม่ผ่าน" in the create flow (boards 1.3.1.1 and 1.6.1), which has only been checked by reading the code.
- On a simulator the app's failed state cannot be reached even with idb: five `pearl.nomatch` answers, each followed by an idb tap on iOS's Try Face ID Again, left the system sheet up and the app never reported a result.
