---
name: verify
description: Drive the mebit mobile app (Kotlin Multiplatform with Compose, application id com.mebit.app) on a throwaway Android emulator, tap through the real UI, and capture screenshots and UI hierarchies as proof. Also launches it on a throwaway iOS simulator for a screenshot. Use it to prove any change under vault-workspace/mobile-signer-ffi/app works, or when asked to run, screenshot, or verify the mobile app.
---

# Verify the mebit mobile app

The app lives in `vault-workspace/mobile-signer-ffi/app`. It is a Kotlin Multiplatform project built with the Kotlin Toolchain (`./kotlin`, pinned at 0.13.0). The Compose UI is in `shared/`, with thin shells in `androidApp/` and `iosApp/`. Today the UI is the stock template: one screen with one button (see `features/`).

Android gets a full drive: taps, the accessibility tree, screenshots. iOS gets launch and screenshot only, because `simctl` cannot inject touches and neither `idb` nor Maestro is installed.

`scripts/verify.sh` in this skill's directory does the work. Below, `verify.sh` means that path. From the repo root it is `vault-workspace/mobile-signer-ffi/app/.claude/skills/verify/scripts/verify.sh`. It finds the project itself, so run it from any directory.

Read `features/README.md` before you drive the app. Then follow the file for the feature you change.

## The whole loop

```
verify.sh start                          # about 40 s warm
verify.sh see "Click me! na kub"         # wait for the first screen
verify.sh tap "Click me! na kub"
verify.sh see "text~:Hello, Android"
verify.sh shot reveal                    # prints the PNG and XML paths
verify.sh stop                           # evidence stays on disk
```

Give the `start` call a 10 minute timeout. The first build on a machine downloads Gradle and the toolchain, and that run was not timed here.

## Launch

`verify.sh start` does this, in order:

1. Boots a headless emulator from the first AVD that `emulator -list-avds` prints. It uses port 5580 (serial `emulator-5580`) and `-read-only`. Everything a run writes is discarded when the emulator exits, so your AVD's saved state is never touched. This was checked by installing the app and changing a setting, then rebooting.
2. Runs `./kotlin build -m androidApp` while the emulator boots.
3. Uninstalls a copy of `com.mebit.app` that the AVD itself may already hold, so the app starts with empty data. Then it runs `./kotlin run -m androidApp -d emulator-5580`, which installs and launches the app.

It ends with `ready: emulator-5580 runs com.mebit.app` and the evidence directory. Set `VERIFY_AVD` to pick another AVD and `VERIFY_PORT` (an even number from 5554 to 5682) to pick another port.

`verify.sh ios-start` creates a simulator named `mebit-verify-HHMMSS` on the newest installed iOS runtime, boots it, and runs `./kotlin run -m iosApp -p iosSimulatorArm64 -d <udid>`. It takes about 70 s. Set `VERIFY_IOS_RUNTIME` and `VERIFY_IOS_DEVICE_TYPE` to use other identifiers from `xcrun simctl list runtimes` and `devicetypes`. Do not run `start` and `ios-start` at the same time, because both build into `build/`.

Isolation. The helper allows one Android run and one iOS run per checkout. It refuses a second `start` while one is active, and it refuses a port that already has an emulator it did not start. It has not been tried with two checkouts at once.

Needs macOS on Apple Silicon, a JDK, python3, an arm64 AVD, and, for iOS only, Xcode with a simulator runtime.

## Doctor

`verify.sh doctor` is read-only and returns 0 when everything it checks is fine. Run it first when anything looks off. It checks the tools, then for an active run:

- the emulator process is the one this skill started, and it owns the port
- the device is online and finished booting
- the installed APK has the same sha256 as the one in `build/tasks/_androidApp_buildAndroidDebug`, so you are not driving a stale build
- `com.mebit.app` is in the foreground
- the crash buffer is empty

For an active iOS run it checks that the simulator is booted and the app is running.

If it prints `FAIL emulator pid ... is gone`, run `verify.sh stop` and then `verify.sh start`. If the APK differs, do the same, because `start` rebuilds.

## Drive

| Command | What it does |
|---|---|
| `see SELECTOR [SECONDS]` | Waits until the node is on screen (default 10 s). On failure it prints what is on screen. |
| `gone SELECTOR [SECONDS]` | Waits until the node is off screen. |
| `tap SELECTOR [N]` | Taps the centre of the matching node. Pass N when several match. It does not wait afterwards. |
| `shot LABEL` | Saves `NN-LABEL.png` and `NN-LABEL.xml` (the UI hierarchy) in the run directory. |
| `adb ARGS...` | Runs `adb -s emulator-5580 ARGS`. Use it for key events, swipes, `am`, `pm`, and `logcat`. |

A selector is `[KIND[~]:]VALUE`. KIND is `text` (the default), `desc` (content description) or `id` (resource id, or a Compose `testTag`). A `~` after the kind means "contains" instead of "equals". Examples are `"Click me! na kub"`, `"text~:Hello, Android"`, `"desc:Search"`.

- Find controls by what the user sees. Never type coordinates yourself.
- Follow every `tap` with `see` or `gone` for its result. Never use `sleep` to wait for the UI.
- Each command dumps the hierarchy with `uiautomator`, so expect 2 to 3 s per command.
- The commands see the whole screen, not only this app. `features/app-launch.md` uses the same selectors in the launcher.
- The app sets no content descriptions or test tags today. When you add a screen, give each control a `contentDescription` or a `testTag`. Compose shows a `testTag` as an `id` only if the root sets `testTagsAsResourceId = true`, which the app does not do yet.
- Scrolling and typing go through `verify.sh adb shell input swipe ...` and `input text` (ASCII only, `%s` for a space). Neither is exercised yet, because the app has no scrolling or text field.

## Evidence

Each run writes to `vault-workspace/mobile-signer-ffi/app/build/verify/<run-id>/`, and each iOS run to `<run-id>-ios/`. The directory is inside the gitignored `build/`.

| File | Content |
|---|---|
| `NN-LABEL.png`, `NN-LABEL.xml` | Screenshot and UI hierarchy from `shot`, numbered in order |
| `steps.tsv` | UTC time, command, selector, and result of every `start`, `see`, `gone`, `tap`, `shot` |
| `meta.txt` | Branch, commit, clean or dirty tree, toolchain version, APK sha256, device model and API level |
| `crash.txt`, `logcat-warn.txt` | Written by `stop`. `crash.txt` is empty on a healthy run. |
| `build.log`, `run.log`, `emulator.log` | Output of the launch steps |

Proof standards for this app:

- Drive the real path. Tap the rendered UI. Do not call into the app, fake state with intents, or edit app data.
- Capture the action and the result. Take a `shot` before the action, act, wait with `see` or `gone`, then take a `shot` after.
- Look at the PNGs. The accessibility tree misses images, so the Compose logo is provable only from the screenshot. Use the Read tool on the PNG.
- The app writes no files, makes no network calls, and holds no keys yet. The only side effect to check is a crash. `stop` prints a WARNING and `crash.txt` is non-empty if the app crashed. When the app starts to store data or call a service, add a read-only second view of it to the feature file.
- There are no mocks today. The app has no external boundary.
- In your report, name the run directory, the feature IDs you drove, and every path you could not drive.

## Cleanup

`verify.sh stop` saves the logs, shuts down the emulator this skill started, removes the run state, and keeps the evidence. It is safe on a run whose emulator already died. `verify.sh ios-stop` shuts down and deletes the simulator it created, and warns if the app was not running at the end.

- Kill only what this skill started. Both `stop` commands check the recorded process or device before they act.
- Never run `adb kill-server`, `pkill`, or `killall`. The user may have their own emulators and simulators open.
- Leave the Gradle and Kotlin daemons running. The toolchain and the user's own builds share them.
- Do not delete `build/verify`. `./kotlin clean` removes build output and may remove this directory too, so copy evidence you still need before you run it.
- An abandoned run whose emulator is still alive blocks the next `start`. Run `verify.sh stop` and `verify.sh ios-stop` to clear it.

## Helpers

- `scripts/verify.sh` is the script you run. With no arguments it prints the command list. It exits 0 on success, 1 on failure with the reason on stderr, and 2 on a usage error.
- `scripts/ui.py` matches selectors against a `uiautomator` dump, and `verify.sh` calls it for you. Run it yourself only to test a selector against a saved hierarchy, for example `python3 -I scripts/ui.py find <run-dir>/03-home-hide.xml "Click me! na kub"`, which prints the tap point `540 126`. Its `list` command prints every node that has text, a description, or an id.
- `start` writes `build/verify/android.env` and `ios-start` writes `build/verify/ios.env`. They record the run directory and the emulator pid or simulator id. The matching `stop` command removes them.

## Other checks in this repo

- `./kotlin test -p android`, run from the app directory, runs the 3 shared unit tests on the JVM with no device attached. It takes 24 s when it compiles and 2 s when nothing changed. Do not run the iOS tests (`-p iosSimulatorArm64`) while the user has simulators open. The toolchain picks a simulator itself (here "iPhone 17e"), boots it, and shuts it down when the tests end.
- The Rust crates in `vault-workspace/` have no mobile UI. The root `CLAUDE.md` lists their `cargo` commands. This skill does not cover them, and it does not cover the Jade and Trezor hardware, which need the physical device over Bluetooth.
- `vault-workspace/mobile-signer-ffi/README.md` still describes an Expo app and `npm start`. That app is gone from this branch. Use the commands in this file.
