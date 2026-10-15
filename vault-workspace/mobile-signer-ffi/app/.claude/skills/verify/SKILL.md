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

1. Boots a headless emulator from the first AVD that `emulator -list-avds` prints. It uses port 5580 (serial `emulator-5580`), `-read-only` and `-cores 4`. The AVD may say fewer cores, and the default AVD here says one, which is why `-cores` is there (see "Seen in practice"). Set `VERIFY_CORES` to change it. Nothing a run installs or changes survives the emulator, and the AVD's `config.ini` and userdata image keep their modification times. The emulator still rewrites small launch files in the AVD folder (`bootcompleted.ini`, `emu-launch-params.txt`, `initrd`, `modem_simulator/`). This was checked by installing the app and changing a setting, rebooting, and comparing the AVD folder before and after.
2. Checks that its emulator owns the port, then runs `./kotlin build -m androidApp` while the emulator boots. If another start won the port, it stops and says so.
3. Uninstalls a copy of `com.mebit.app` that the AVD itself may already hold, so the app starts with empty data. Then it runs `./kotlin run -m androidApp -d emulator-5580`, which installs and launches the app.
4. Checks that the app is in front. If the launcher or nothing is there, it starts the app once more and fails when that does not work.

It ends with `ready: emulator-5580 runs com.mebit.app` and the evidence directory, but only after the app was in front, the UI answered and no system dialog was on screen. Set `VERIFY_AVD` to pick another AVD and `VERIFY_PORT` (an even number from 5554 to 5682) to pick another port. An AVD name that does not exist fails at once with the emulator's own message.

A warm build starts in 19 to 40 s. The first start in a fresh clone compiles the project while the emulator boots. In 30 such starts of the committed script on the default AVD it took 37 to 47 s, median 39 s. The first start on a machine with every cache empty took 14 min 38 s. The iOS build after it added 5 min 54 s and 2.1 GB, and both platforms together left 4.8 GB in the empty cache directories.

To test a cold start yourself, point every cache at an empty directory. `HOME` is not enough, because a JVM takes `user.home` from the operating system's user record. Set `HOME`, `JAVA_TOOL_OPTIONS=-Duser.home=<dir>`, `GRADLE_USER_HOME=<dir>/.gradle`, `KONAN_DATA_DIR=<dir>/.konan` and `ANDROID_USER_HOME=<dir>/.android`, and keep `ANDROID_AVD_HOME` on your real AVD folder. A run with `HOME` alone was cold only for the shell wrapper's downloads.

`verify.sh ios-start` creates a simulator named `mebit-verify-HHMMSS` on the newest installed iOS runtime, boots it, and runs `./kotlin run -m iosApp -p iosSimulatorArm64 -d <udid>`. It took 53 to 93 s, the longer figure with a fresh clone compiling Kotlin/Native. Set `VERIFY_IOS_RUNTIME` to use another runtime from `xcrun simctl list runtimes`. The device type then defaults to the first iPhone that runtime supports, so one variable is enough. Set `VERIFY_IOS_DEVICE_TYPE` as well to choose the device (an identifier from `xcrun simctl list devicetypes`). `ios-start` refuses a pair the runtime does not support and lists some that it does. On Xcode 27 the default is the iOS 26.5 runtime. The iOS 18.5 runtime passed 3 of 3 full replays with taps on Xcode 27 (`VERIFY_IOS_RUNTIME=com.apple.CoreSimulator.SimRuntime.iOS-18-5`, an iPhone 16 Pro, greeting `Compose: Hello, iOS 18.5!`). Do not run `start` and `ios-start` at the same time, in one checkout or in two. The Kotlin/Native build and the simulator boot starve the Android guest even with four vCPUs. In 7 such tries (3 in one checkout, 4 in two clones) the Android start was refused by the gate 6 times, with a "System UI isn't responding" or "Pixel Launcher isn't responding" dialog, while the iOS run was ready every time. Nothing collided in `build/`. Run them one after the other.

Needs macOS on Apple Silicon, a JDK, python3, and an arm64 AVD. iOS also needs Xcode with a simulator runtime. Xcode 27.0 on macOS 27.0.1 was the host for every result below.

### Several runs at once

- One Android run and one iOS run per checkout. A second `start` in the same checkout is refused while the first emulator is alive.
- Two clones at once worked. With the default port the second clone's `start` is refused with `emulator-5580 is already device and this skill did not start it`. With `VERIFY_PORT=5582` both clones ran the full recipe at the same time with 0 failures each, using their own run directories, emulator processes and state files. Stopping one left the other running and drivable.
- Three starts in the same second on one port: in 5 of 5 rounds exactly one won. The other two failed with `does not own port 5580`, left no state behind, and the winner stayed healthy. Before the ownership check, both starts reported success for the same serial.
- Three clones at once on three AVDs and ports 5580, 5582 and 5584 all became ready with distinct run directories, pids and ports. Free memory went from 56% to 36%. Stopping the middle one left the other two running with `doctor` passing.
- Two iOS runs from two clones, started in the same second: 3 of 3 rounds, both ready, the full recipe in parallel with 0 failures, distinct simulators, and stopping one left the other booted and tappable.
- The machine matters. This host has 16 GB and 8 cores. See "Seen in practice" for what a compile and a booting emulator did to each other.

### Images that were tested

Each row counts full replays of the recipes in `features/`, run one after the other in one fresh clone, one emulator at a time. Every AVD ran 3 replays on the script of `c6fbb2c`. The 16 KB AVD failed all 3 on the closing `doctor` (the crash buffer, see below), so `doctor` was changed and that AVD ran 3 more on the final script, which passed. The default AVD and `Pixel_5` also ran one more replay on the final script. The `start` column is the median over the `c6fbb2c` replays.

| AVD | System image | Screen | vCPUs in its config | Replays passed | `start` median |
|---|---|---|---|---|---|
| `Medium_Phone_API_36.0` (the default) | API 36 Google Play | 1080x2400 | 1 | 4 of 4 | 32 s |
| `Pixel_4a` | API 36.1 Google APIs, no Play Store | 1080x2340 | 4 | 3 of 3 | 30 s |
| `Pixel_5` | API 36.1 Google APIs, no Play Store | 1080x2340 | 4 | 4 of 4 | 30 s |
| `Pixel_6` | API 36.1 Google APIs, no Play Store | 1080x2400 | 4 | 3 of 3 | 29 s |
| `Pixel_7` | API 36.1 Google Play | 1080x2400 | 4 | 3 of 3 | 29 s |
| `Pixel_7a` | API 36.1 Google Play | 1080x2400 | 4 | 3 of 3 | 29 s |
| `Small_Phone` | CANARY (API 36.1) Google Play | 720x1280 | 4 | 3 of 3 | 23 s |
| throwaway | API 35 Google Play | 1080x2400 | 4 | 3 of 3 | 32 s |
| throwaway | API 37.2 Google APIs, 16 KB page size | 1080x2340 | 4 | 0 of 3, then 3 of 3 on the final script | 27 s |

`start` gives every AVD 4 vCPUs, whatever its config says (`VERIFY_CORES`, see Launch). `Pixel_4a`, `Pixel_6` and `Pixel_7a` share images with `Pixel_5` and `Pixel_7`, and they are different AVDs, so each ran on its own. Of the 32 replays, the only ones that failed were the 3 first replays on the 16 KB AVD, and they failed in `doctor` with every earlier step passing.

The throwaway AVDs were created in a separate AVD folder with `avdmanager`, from system images that were already installed, and removed afterwards. The API 35 one is the user's `Pixel_7` config with only the image changed. Nothing was downloaded. Pixel_6's folder holds a stale `hardware-qemu.ini.lock` from December that names a dead process. The emulator started anyway.

The API 37.2 image with 16 KB pages boots in 18 to 21 s on HVF (page size 16384, Android 17), and the app passed 3 of 3 replays on it. The first round's two attempts that did not boot were an AVD made with this machine's `avdmanager`, and what failed was that AVD. `avdmanager` warns that it understands SDK XML up to version 3 while the SDK has version 4, and it wrote `target=android-0` into the AVD's `.ini`. The emulator then logs `hvf is not enabled on this aarch64 host` and starts a software CPU that does not boot in minutes. Setting only that value to `android-37.2` made the same AVD boot in 40 s, and putting `android-0` into a working AVD's `.ini` reproduced the hang. `start` now looks for that line and fails at once with the reason. Make AVDs for new images in Android Studio, or fix the `.ini` by hand. The image's UWB service (`android.hardware.uwb-service`) aborts at every boot and fills the crash buffer with 190 KB, which is why `doctor` judges a crash by its owner. The two API 35 16 KB image folders are empty, so they were not tested.

## Doctor

`verify.sh doctor` is read-only and returns 0 when everything it checks is fine. Run it first when anything looks off. It checks the tools, then for an active run:

- the emulator process is the one this skill started, and it owns the port
- the emulator answers `adb shell` within 8 s. A frozen emulator does not, and `doctor` then reports that one line and skips the checks that need the device, so it takes about 10 s instead of minutes
- the device is online and finished booting
- the installed APK has the same sha256 as the one in `build/tasks/_androidApp_buildAndroidDebug`, so you are not driving a stale build
- `com.mebit.app` is in the foreground
- no "isn't responding" dialog is on screen, and `uiautomator` can dump the UI
- the app has not crashed. The crash buffer holds every process of the guest, and other processes show as information: the Pixel launcher crashes at boot now and then, and the UWB service of the Android 17 image aborts at every boot

For an active iOS run it checks that the simulator is booted and the app is running.

If it prints `FAIL emulator pid ... is gone`, run `verify.sh stop` and then `verify.sh start`. If the APK differs, do the same, because `start` rebuilds.

What to do when `doctor` is red:

| `doctor` says | Do this |
|---|---|
| a system "isn't responding" dialog is on screen | If the screen answers, `verify.sh tap "id:aerr_wait"`, then `doctor` again. A dialog that comes back means the process behind it is still stuck, so `stop` and `start`. |
| `uiautomator` cannot dump the UI | Something is frozen, the emulator or the app in front. `stop`, then `start`. |
| the emulator does not answer `adb` within 8 s | The emulator process is hung. `stop`, and if it says `would not exit`, run the `kill -9` it prints and `stop` again. |
| anything about the emulator pid or the port | `stop`, then `start`. |

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
- Each Android command dumps the hierarchy with `uiautomator`, so expect 2 to 3 s per command. When the emulator does not answer `adb` at all, `see`, `gone`, `tap` and `drawer` stop after one bounded attempt and say so, instead of retrying for minutes.
- The Android commands see the whole screen, not only this app. `features/app-launch.md` uses the same selectors in the launcher.
- The app sets no content descriptions or test tags today. When you add a screen, give each control a `contentDescription` or a `testTag`. Both `id:` forms work once a control carries a tag. This was tried in a scratch clone with `Modifier.testTag("reveal")` on the button. On iOS `ios-see id:reveal`, `ios-tap id:reveal` and `ios-see "id~:rev"` found it, and `id:nope` failed as it should. On Android the tag alone is not a resource id (`see id:reveal` failed), and it became one when the root set `Modifier.semantics { testTagsAsResourceId = true }`, which the app does not do yet.
- Scrolling and typing go through `verify.sh adb shell input swipe ...` and `input text` (ASCII only, `%s` for a space). Neither is exercised yet, because the app has no scrolling or text field. The iOS commands have no scroll or type action either.

## Evidence

Each run writes to `vault-workspace/mobile-signer-ffi/app/build/verify/<run-id>/`, and each iOS run to `<run-id>-ios/`. The directory is inside the gitignored `build/`.

| File | Content |
|---|---|
| `NN-LABEL.png`, `NN-LABEL.xml` | Screenshot and UI hierarchy from `shot`, numbered in order. iOS has the PNG only. |
| `steps.tsv` | UTC time, command, selector, and result of every `start`, `see`, `gone`, `tap`, `shot`, `drawer` and `ios-*` action |
| `meta.txt` | Branch, commit, clean or dirty tree, toolchain version, APK sha256, device model and API level |
| `crash.txt`, `logcat-warn.txt` | Written by `stop` when the device was online. `crash.txt` lists crashes of every process in the guest, so it is not always empty. `stop` says whether the app is among them. |
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

`verify.sh stop` saves the logs, shuts down the emulator this skill started, waits until `adb` stops listing the serial, removes the run state, and keeps the evidence. It is safe on a run whose emulator already died. When the emulator ignores a polite kill, `stop` exits 1, prints the `kill -9` to run, and keeps the run state, so the next `start` still refuses to run beside that process. Run the `kill -9`, then `stop` again. `verify.sh ios-stop` shuts down and deletes the simulator it created, and warns if the app was not running at the end.

- Kill only what this skill started. Both `stop` commands check the recorded process or device before they act, and `stop` talks to the serial only while its own emulator owns the port.
- Never run `adb kill-server`, `pkill`, or `killall`. The user may have their own emulators and simulators open.
- Leave the Gradle and Kotlin daemons running. The toolchain and the user's own builds share them.
- Do not delete `build/verify`. `./kotlin clean` removes build output and may remove this directory too, so copy evidence you still need before you run it.
- An abandoned run whose emulator is still alive blocks the next `start`. Run `verify.sh stop` and `verify.sh ios-stop` to clear it.

## Seen in practice

- **The first start failed on the default AVD because its guest has one vCPU.** The default is the first AVD `emulator -list-avds` prints, `Medium_Phone_API_36.0`, and it is the only AVD on this host with `hw.cpu.ncore = 1`. A headless emulator renders in software on the host, and a one vCPU guest starved while it booted. The guest logged ANRs in SystemUI, phone, GMS and the keyboard. In 15 fresh-clone starts of the earlier script on that AVD, 10 logged an ANR and 4 failed on the first try. One failure was a launcher crash. In two the framework restarted and took the app with it, so `start` reported ready with the launcher in front and no app. One was refused by the gate with a "System UI isn't responding" dialog over the app. An earlier round also lost its adb transport once (`Transport endpoint is not connected`). Two of the four recovered with `stop` and `start`, and two failed again. With `-cores 4` it was 0 failures and no ANR in 15 starts, and 0 in 10 more with six busy loops on the host. The Pixel_7 AVD, with four vCPUs and another image, passed 10 of 10. The host GPU alone (`-gpu host`) passed 10 of 10 as well. A project built before the boot, with no compile during it, still failed 2 of 10. So the compile that the earlier notes blamed is not needed for the failure, and building before booting does not remove it. On the same AVD the comparison is 4 of 15 failed without `-cores 4` against 0 of 15 with it, which is suggestive and not conclusive (Fisher exact p = 0.10). Pooling the one vCPU starts of both builds gives 6 of 25 failed against 0 of 45 on every other arm (p = 0.0014), a comparison chosen after the data. `start` now passes `-cores 4` and checks that the app is in front. On the committed script, 30 fresh clones on the default AVD passed 30 of 30 on the first try, with no ANR in any run (median 39 s). That bounds the failure rate under 10% at 95%. The final commit then passed 10 of 10 more through `scripts/trials.sh`. The gate stays, because a dialog can still come from a slower host.
- **A dialog can arrive after `start` reported ready.** In one replay the first `see` passed and a "System UI isn't responding" dialog was there by the fourth step. A pilot trial saw the same, a dialog right after a `start` that had returned 0. The gate cannot see that. `doctor` can, and the recipes end with it.
- **Dismissing the dialog.** With a real dialog on the default AVD (forced with `VERIFY_CORES=1` and six busy loops), `doctor` exited 1 with the dialog line, `verify.sh tap "id:aerr_wait"` cleared it, and `doctor` then passed and the first screen was visible. `stop` and `start` also restored everything. A dialog cannot be forced on these images by freezing a process, with or without root, so that provocation is not a recipe.
- **A frozen emulator used to cost minutes.** With its process stopped by SIGSTOP, `doctor` took 226 s and said the app was not installed, `see` with a 5 s budget ran past 300 s, and `stop` took 131 s, deleted its state and said stopped while the process was alive. Now `doctor` answers in 8 to 9 s with one line, `see` in about 16 s, and `stop` in about 17 s with exit 1, the `kill -9` to run, and the state kept.
- **The crash buffer holds every process.** The launcher crashed during a boot now and then, and `doctor` and `stop` used to say the app crashed. They name the processes now.
- **`stop` no longer reports a crash for an emulator that never booted.** Before the fix it wrote adb's "waiting for device" into `crash.txt` and warned that the app crashed.
- **Right after a stop, adb still lists the old serial for a moment.** `stop` now waits for it to disappear. Before that, an immediate `start` was refused, or its emulator could not bind the port.
- **`xcodebuild` can sit after a finished test.** Once on Xcode 27, an `ios-gone` that had already failed at 10 s left `xcodebuild` running for 8 minutes and stalled the helper. That was 1 hang in 7 failing actions. The `ios-*` actions now read the result from the test log, give `xcodebuild` six seconds to exit, and then stop it. Six failing actions after that change did not hang.
- **Gestures for the all-apps list differ by image, and a starved launcher ignores them.** See `features/app-launch.md`. The preserved logs hold 25 `drawer` calls on API 35 with one failure, "the launcher did not come forward", and the screen at that moment was not kept. In this round 260 calls on API 35 Play (idle 0 of 80, six busy loops on the host 1 of 80, two busy loops in the guest 0 of 80, first call after `start` 0 of 20) and 80 on API 36 Play (0 of 80) failed once. That one failure was under host load. The launcher home screen was in front with the app behind it, and all four gestures were ignored for 103 s (`wp4/api35-hostload/fail-3B` in the evidence). So a starved launcher drops input, which is a timing problem of the launcher, not of the app or the selector. The original miss did not recur in 340 calls and is still unexplained. `drawer` now tries the swipes first on API 35, where the key never worked, presses HOME again and tries a second round when the first round fails, and prints what is in front when it gives up.

## Helpers

- `scripts/verify.sh` is the script you run. With no arguments it prints the command list. It exits 0 on success, 1 on failure with the reason on stderr, and 2 on a usage error.
- `scripts/ui.py` matches selectors against a `uiautomator` dump, and `verify.sh` calls it for you. Run it yourself only to test a selector against a saved hierarchy, for example `python3 -I scripts/ui.py find <run-dir>/03-home-hide.xml "Click me! na kub"`, which prints the tap point `540 126`. Its `list` command prints every node that has text, a description, or an id. `ui.py crash FILE PKG` reads a crash buffer and prints `app` when the package is in it, and otherwise the names of the processes that crashed.
- `scripts/ios-ui/` holds the XCUITest bundle behind `ios-tap`, `ios-see` and `ios-gone`: `Tests/IosUi.swift` is the test, and `IosUi.xcodeproj` is generated from `project.yml` with `xcodegen generate --spec project.yml`. You need xcodegen only to regenerate the project. `verify.sh` passes the action to the test through `TEST_RUNNER_UI_*` environment variables and builds into `build/verify/ios-ui-derived`.
- `scripts/selftest.sh` runs the offline regression checks in about 15 s and boots nothing. It covers the selectors against real captured dumps (`scripts/testdata/`), the iOS runtime and device type pairing (`scripts/ios_pick.py`), and `stop`, `doctor` and `see` against a frozen emulator stub. Run it after any change to `verify.sh`.
- `scripts/trials.sh N [AVD]` re-measures first-start reliability. Each trial is a fresh clone of this checkout with the working tree's skill files, one `start`, the first-screen recipe, a 30 s settle and `doctor`. A trial that fails once gets one `stop` and `start`. The last line gives initial success and recovery success apart. About 100 s per trial. Each run writes `app/build/verify/trials-<time>/`, which keeps the start output of every trial and the whole clone of a failing one.
- `VERIFY_ADB_SECONDS` (default 30) bounds every `adb` call. The self-test sets it to 1 so the frozen-emulator checks take seconds.
- `start` writes `build/verify/android.env` and `ios-start` writes `build/verify/ios.env`. They record the run directory and the emulator pid or simulator id. The matching `stop` command removes them.

## Other checks in this repo

- `./kotlin test -p android`, run from the app directory, runs the 3 shared unit tests on the JVM with no device attached. It takes 24 s when it compiles and 2 s when nothing changed. Do not run the iOS tests (`-p iosSimulatorArm64`) while the user has simulators open. The toolchain picks a simulator itself (here "iPhone 17e"), boots it, and shuts it down when the tests end.
- The Rust crates in `vault-workspace/` have no mobile UI. The root `CLAUDE.md` lists their `cargo` commands. This skill does not cover them, and it does not cover the Jade and Trezor hardware, which need the physical device over Bluetooth.
- `vault-workspace/mobile-signer-ffi/README.md` still describes an Expo app and `npm start`. That app is gone from this branch. Use the commands in this file.
