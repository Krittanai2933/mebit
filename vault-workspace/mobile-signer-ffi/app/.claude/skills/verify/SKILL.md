---
name: verify
description: Drive the mebit mobile app (Kotlin Multiplatform with Compose, application id com.mebit.app) on a throwaway Android emulator or iOS simulator, tap through the real UI, and capture screenshots and UI hierarchies as proof. Use it to prove any change under vault-workspace/mobile-signer-ffi/app works, or when asked to run, screenshot, or verify the mobile app.
---

# Verify the mebit mobile app

The app lives in `vault-workspace/mobile-signer-ffi/app`. It is a Kotlin Multiplatform project built with the Kotlin Toolchain (`./kotlin`, pinned at 0.13.0). The Compose UI is in `shared/`, with thin shells in `androidApp/` and `iosApp/`. Today the UI is the stock template: one screen with one button (see `features/`).

Android is driven through `adb` and the accessibility tree. iOS is driven through a small XCUITest bundle in `scripts/ios-ui`, because `simctl` cannot inject touches and neither `idb` nor Maestro is installed.

`scripts/verify.sh` in this skill's directory does the work. Below, `verify.sh` means that path. From the repo root it is `vault-workspace/mobile-signer-ffi/app/.claude/skills/verify/scripts/verify.sh`. It finds the project itself, so run it from any directory.

Read `features/README.md` before you drive the app. Then follow the file for the feature you change.

## The whole loop

```
verify.sh start                          # about 40 s with a warm build
verify.sh see "Click me! na kub"         # wait for the first screen
verify.sh tap "Click me! na kub"
verify.sh see "text~:Hello, Android"
verify.sh shot reveal                    # prints the PNG and XML paths
verify.sh stop                           # evidence stays on disk
```

For iOS replace the commands with `ios-start`, `ios-see`, `ios-tap`, `ios-shot`, and `ios-stop`.

Give the first `start` on a machine a 30 minute timeout, and later ones 5 minutes. With every cache empty it took 14 min 38 s and downloaded 2.6 GB. The first 560 MB (the Kotlin Toolchain and its JRE) took 13 min on one run of this machine and about 8 min on another, so the time follows the network.

## Launch

`verify.sh start` does this, in order:

1. Boots a headless emulator from the first AVD that `emulator -list-avds` prints. It uses port 5580 (serial `emulator-5580`) and `-read-only`. Nothing a run installs or changes survives the emulator, and the AVD's `config.ini` and userdata image keep their modification times. The emulator still rewrites small launch files in the AVD folder (`bootcompleted.ini`, `emu-launch-params.txt`, `initrd`, `modem_simulator/`). This was checked by installing the app and changing a setting, rebooting, and comparing the AVD folder before and after.
2. Checks that its emulator owns the port, then runs `./kotlin build -m androidApp` while the emulator boots. If another start won the port, it stops and says so.
3. Uninstalls a copy of `com.mebit.app` that the AVD itself may already hold, so the app starts with empty data. Then it runs `./kotlin run -m androidApp -d emulator-5580`, which installs and launches the app.

It ends with `ready: emulator-5580 runs com.mebit.app` and the evidence directory, but only after the UI answered and no system dialog was on screen. Set `VERIFY_AVD` to pick another AVD and `VERIFY_PORT` (an even number from 5554 to 5682) to pick another port.

A warm build starts in 19 to 40 s. The first start in a fresh clone compiles the project while the emulator boots and took 43 to 79 s when it worked. The first start on a machine with every cache empty took 14 min 38 s. The iOS build after it added 5 min 54 s and 2.1 GB, and both platforms together left 4.8 GB in the empty cache directories.

To test a cold start yourself, point every cache at an empty directory. `HOME` is not enough, because a JVM takes `user.home` from the operating system's user record. Set `HOME`, `JAVA_TOOL_OPTIONS=-Duser.home=<dir>`, `GRADLE_USER_HOME=<dir>/.gradle`, `KONAN_DATA_DIR=<dir>/.konan` and `ANDROID_USER_HOME=<dir>/.android`, and keep `ANDROID_AVD_HOME` on your real AVD folder. A run with `HOME` alone was cold only for the shell wrapper's downloads.

`verify.sh ios-start` creates a simulator named `mebit-verify-HHMMSS` on the newest installed iOS runtime, boots it, and runs `./kotlin run -m iosApp -p iosSimulatorArm64 -d <udid>`. It took 53 to 93 s, the longer figure with a fresh clone compiling Kotlin/Native. Set `VERIFY_IOS_RUNTIME` and `VERIFY_IOS_DEVICE_TYPE` to use other identifiers from `xcrun simctl list runtimes` and `devicetypes`. On Xcode 27 it ran on the iOS 26.5 runtime. The iOS 18.5 runtime worked on Xcode 26.6 in an earlier session and was not rerun. Do not run `start` and `ios-start` at the same time, because both build into `build/`.

Needs macOS on Apple Silicon, a JDK, python3, and an arm64 AVD. iOS also needs Xcode with a simulator runtime. Xcode 27.0 on macOS 27.0.1 was the host for every result below.

### Several runs at once

- One Android run and one iOS run per checkout. A second `start` in the same checkout is refused while the first emulator is alive.
- Two clones at once worked. With the default port the second clone's `start` is refused with `emulator-5580 is already device and this skill did not start it`. With `VERIFY_PORT=5582` both clones ran the full recipe at the same time with 0 failures each, using their own run directories, emulator processes and state files. Stopping one left the other running and drivable.
- Two starts in the same second on one port: in 5 of 5 rounds exactly one won. The other failed with `does not own port 5580`, left no state behind, and the winner stayed healthy. Before the ownership check, both starts reported success for the same serial.
- The machine matters. This host has 16 GB and 8 cores. See "Seen in practice" for what a compile and a booting emulator did to each other.

### Images that were tested

Each row counts full replays of the recipes in `features/` with the final scripts.

| AVD | System image | Screen | Replays passed |
|---|---|---|---|
| `Medium_Phone_API_36.0` | API 36 Google Play | 1080x2400 | 3 of 4. The miss was a first start in a fresh clone with the system dialog over the app. |
| `Pixel_5` | API 36.1 Google APIs, no Play Store | 1080x2340 | 2 of 2 |
| `Pixel_7` | API 36.1 Google Play | 1080x2400 | 2 of 2 |
| `Small_Phone` | CANARY (API 36.1) Google Play | 720x1280 | 2 of 2 |
| throwaway | API 35 Google Play | 1080x2400 | 1 of 2, and 5 of 5 in a follow-up. The miss was one `drawer` call, cause not known. |
| throwaway | API 37.2, 16 KB page size | 1080x2400 | blocked, see below |

The throwaway AVDs were created in a separate AVD folder with `avdmanager`, from system images that were already installed, and removed afterwards. Nothing was downloaded.

The API 37.2 image did not reach `boot_completed` in 240 s on two attempts, and both emulator logs said `hvf is not enabled on this aarch64 host`. None of the other 69 saved boot logs contains that line. The cause was not isolated, and the app was not tested on that image. The two API 35 16 KB images are empty directories, so they were not tested. Pixel_4a, Pixel_7a and Pixel_6 share images with Pixel_5 and Pixel_7 and were skipped.

## Doctor

`verify.sh doctor` is read-only and returns 0 when everything it checks is fine. Run it first when anything looks off. It checks the tools, then for an active run:

- the emulator process is the one this skill started, and it owns the port
- the device is online and finished booting
- the installed APK has the same sha256 as the one in `build/tasks/_androidApp_buildAndroidDebug`, so you are not driving a stale build
- `com.mebit.app` is in the foreground
- no "isn't responding" dialog is on screen, and `uiautomator` can dump the UI
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
| `drawer` | Presses HOME and opens the launcher's all-apps list, trying a key and then swipes. |
| `adb ARGS...` | Runs `adb -s emulator-5580 ARGS`. Use it for key events, `am`, `pm`, and `logcat`. |
| `ios-see`, `ios-tap`, `ios-gone SELECTOR` | The same three actions on the iOS simulator. About 5 s each, 15 s the first time while the test bundle builds, and about 20 s when the action fails. |
| `ios-shot LABEL` | Saves `NN-LABEL.png` from the simulator. |

A selector is `[KIND[~]:]VALUE`. KIND is `text` (the default), `desc` (content description) or `id` (resource id, or a Compose `testTag`). A `~` after the kind means "contains" instead of "equals". Examples are `"Click me! na kub"`, `"text~:Hello, Android"`, `"desc:app"`. On iOS `text` and `desc` both match the accessibility label, and `id` matches the accessibility identifier.

- Find controls by what the user sees. Never type coordinates yourself.
- Follow every `tap` with `see` or `gone` for its result. Never use `sleep` to wait for the UI.
- Each Android command dumps the hierarchy with `uiautomator`, so expect 2 to 3 s per command.
- The Android commands see the whole screen, not only this app. `features/app-launch.md` uses the same selectors in the launcher.
- The app sets no content descriptions or test tags today. When you add a screen, give each control a `contentDescription` or a `testTag`. Compose shows a `testTag` as an `id` only if the root sets `testTagsAsResourceId = true`, which the app does not do yet.
- Scrolling and typing go through `verify.sh adb shell input swipe ...` and `input text` (ASCII only, `%s` for a space). Neither is exercised yet, because the app has no scrolling or text field. The iOS commands have no scroll or type action either.

## Evidence

Each run writes to `vault-workspace/mobile-signer-ffi/app/build/verify/<run-id>/`, and each iOS run to `<run-id>-ios/`. The directory is inside the gitignored `build/`.

| File | Content |
|---|---|
| `NN-LABEL.png`, `NN-LABEL.xml` | Screenshot and UI hierarchy from `shot`, numbered in order. iOS has the PNG only. |
| `steps.tsv` | UTC time, command, selector, and result of every `start`, `see`, `gone`, `tap`, `shot`, `drawer` and `ios-*` action |
| `meta.txt` | Branch, commit, clean or dirty tree, toolchain version, APK sha256, device model and API level |
| `crash.txt`, `logcat-warn.txt` | Written by `stop` when the device was online. `crash.txt` is empty on a healthy run. |
| `build.log`, `run.log`, `emulator.log` | Output of the launch steps |
| `ui-NN-ACTION.log` | The `xcodebuild` output of each iOS action |

Proof standards for this app:

- Drive the real path. Tap the rendered UI. Do not call into the app, fake state with intents, or edit app data.
- Capture the action and the result. Take a `shot` before the action, act, wait with `see` or `gone`, then take a `shot` after.
- Look at the PNGs. The Android accessibility tree misses images, so the Compose logo is provable only from the screenshot. Use the Read tool on the PNG.
- The app writes no files, makes no network calls, and holds no keys yet. The only side effect to check is a crash. `stop` prints a WARNING and `crash.txt` is non-empty if the app crashed. When the app starts to store data or call a service, add a read-only second view of it to the feature file.
- There are no mocks today. The app has no external boundary.
- In your report, name the run directory, the feature IDs you drove, and every path you could not drive.

## Cleanup

`verify.sh stop` saves the logs, shuts down the emulator this skill started, waits until `adb` stops listing the serial, removes the run state, and keeps the evidence. It is safe on a run whose emulator already died. `verify.sh ios-stop` shuts down and deletes the simulator it created, and warns if the app was not running at the end.

- Kill only what this skill started. Both `stop` commands check the recorded process or device before they act, and `stop` talks to the serial only while its own emulator owns the port.
- Never run `adb kill-server`, `pkill`, or `killall`. The user may have their own emulators and simulators open.
- Leave the Gradle and Kotlin daemons running. The toolchain and the user's own builds share them.
- Do not delete `build/verify`. `./kotlin clean` removes build output and may remove this directory too, so copy evidence you still need before you run it.
- An abandoned run whose emulator is still alive blocks the next `start`. Run `verify.sh stop` and `verify.sh ios-stop` to clear it.

## Seen in practice

- **The first start in a fresh clone is the fragile one.** The project compiles while the emulator boots. In 14 such starts on this host, 6 ended with an emulator that did not answer or a "System UI isn't responding" dialog over the app (4 dialogs and 2 failed UI dumps). Every later `see` and `tap` then failed, each failure listing the dialog among the on-screen nodes. Replays on a warm build, which check the first screen right after `start`, did not fail there in about 20 runs. Building before booting was tried and did not clearly help (2 of 8 starts failed, against 6 of 14), so `start` still overlaps the two. Instead `start` now ends with a check: it reports ready only when the UI can be dumped and no `isn't responding` dialog is up. Otherwise it stops its own emulator and tells you to run `start` again, which is then a warm start. `doctor` runs the same check.
- **Dismissing the dialog.** In one provoked case (SystemUI frozen, then resumed, on a userdebug image) `verify.sh tap "id:aerr_wait"` found the dialog's Wait button and the app was back in the foreground afterwards. Freezing SystemUI did not produce the dialog in a second attempt, so treat that as one observation.
- **`stop` no longer reports a crash for an emulator that never booted.** Before the fix it wrote adb's "waiting for device" into `crash.txt` and warned that the app crashed.
- **Right after a stop, adb still lists the old serial for a moment.** `stop` now waits for it to disappear. Before that, an immediate `start` was refused, or its emulator could not bind the port.
- **`xcodebuild` can sit after a finished test.** Once on Xcode 27, an `ios-gone` that had already failed at 10 s left `xcodebuild` running for 8 minutes and stalled the helper. That was 1 hang in 7 failing actions. The `ios-*` actions now read the result from the test log, give `xcodebuild` six seconds to exit, and then stop it. Six failing actions after that change did not hang.
- **Gestures for the all-apps list differ by image.** See `features/app-launch.md`. One `drawer` call out of eight failed on API 35 with "the launcher did not come forward". It did not recur in 10 more drawers, and the cause is not known. `drawer` now also accepts a list that is already open, and prints the screen when it fails.

## Helpers

- `scripts/verify.sh` is the script you run. With no arguments it prints the command list. It exits 0 on success, 1 on failure with the reason on stderr, and 2 on a usage error.
- `scripts/ui.py` matches selectors against a `uiautomator` dump, and `verify.sh` calls it for you. Run it yourself only to test a selector against a saved hierarchy, for example `python3 -I scripts/ui.py find <run-dir>/03-home-hide.xml "Click me! na kub"`, which prints the tap point `540 126`. Its `list` command prints every node that has text, a description, or an id.
- `scripts/ios-ui/` holds the XCUITest bundle behind `ios-tap`, `ios-see` and `ios-gone`: `Tests/IosUi.swift` is the test, and `IosUi.xcodeproj` is generated from `project.yml` with `xcodegen generate --spec project.yml`. You need xcodegen only to regenerate the project. `verify.sh` passes the action to the test through `TEST_RUNNER_UI_*` environment variables and builds into `build/verify/ios-ui-derived`.
- `start` writes `build/verify/android.env` and `ios-start` writes `build/verify/ios.env`. They record the run directory and the emulator pid or simulator id. The matching `stop` command removes them.

## Other checks in this repo

- `./kotlin test -p android`, run from the app directory, runs the 3 shared unit tests on the JVM with no device attached. It takes 24 s when it compiles and 2 s when nothing changed. Do not run the iOS tests (`-p iosSimulatorArm64`) while the user has simulators open. The toolchain picks a simulator itself (here "iPhone 17e"), boots it, and shuts it down when the tests end.
- The Rust crates in `vault-workspace/` have no mobile UI. The root `CLAUDE.md` lists their `cargo` commands. This skill does not cover them, and it does not cover the Jade and Trezor hardware, which need the physical device over Bluetooth.
- `vault-workspace/mobile-signer-ffi/README.md` still describes an Expo app and `npm start`. That app is gone from this branch. Use the commands in this file.
