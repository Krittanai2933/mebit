# App launch

A user opens the app from its launcher icon, leaves it, and comes back. This feature covers how the app starts, what state survives leaving it, and what the icon does when the app is already running.

## Sub-features

- `launch-icon` opens the app from its launcher icon and shows a fresh screen after the app was force-stopped.
- `launch-stacked-instance` shows a fresh screen when the icon is tapped after `verify.sh start` launched the app, because the icon opens a new instance on top of the old one.
- `launch-resume` returns to the same screen and state after HOME and the icon, when the icon started the app.
- `launch-ios` installs and opens the app on an iOS simulator.

## How to get to it (user POV)

- Android: the icon labeled `app` in the all-apps drawer. Swipe up from the home screen to open the drawer.
- Android, developer path: `./kotlin run -m androidApp -d <serial>`, which `verify.sh start` runs for you.
- iOS simulator: `verify.sh ios-start`, which runs `./kotlin run -m iosApp -p iosSimulatorArm64 -d <udid>`.

## Driving it with verify.sh

Preconditions:

- `verify.sh start` finished in this run and `verify.sh doctor` passes.
- The screen is 1080x2400. For another size, scale the swipe from `verify.sh adb shell wm size`.

- **Open the drawer.** Leave the app, then swipe up. Run `verify.sh adb shell input keyevent KEYCODE_HOME` and `verify.sh gone "Click me! na kub"`. Run `verify.sh adb shell input swipe 540 2100 540 700 350` then `verify.sh see "desc:Search"`. The launcher's search bar is visible. Later steps say "open the drawer as above" for these four commands.
- **Stacked instance.** This needs the app to have been launched by `start`, not by the icon, with the greeting visible (run the Reveal recipe in `home-greeting.md`). Run `verify.sh adb shell pidof com.mebit.app` and note the number. Open the drawer as above, then run `verify.sh tap "app"`, `verify.sh see "Click me! na kub"`, and `verify.sh gone "text~:Hello, Android"`. The greeting is hidden and `pidof` prints the same number. The icon opened a new instance on top of the old one, in the same process. Run `verify.sh shot launch-stacked-instance`.
- **Cold start from the icon.** Run `verify.sh adb shell am force-stop com.mebit.app`. Open the drawer as above, then run `verify.sh tap "app"`, `verify.sh see "Click me! na kub"`, and `verify.sh gone "text~:Hello, Android"`. The home screen appears with the greeting hidden, and `pidof` prints a new number. Run `verify.sh shot launch-icon`.
- **Resume.** This needs the app to have been started by the icon, as at the end of the cold start. Reveal the greeting with `verify.sh tap "Click me! na kub"` and `verify.sh see "text~:Hello, Android"`. Note `pidof`. Open the drawer as above, run `verify.sh tap "app"`, then `verify.sh see "text~:Hello, Android"`. The greeting is still visible and `pidof` prints the same number. Run `verify.sh shot launch-resume`.
- **iOS.** Run `verify.sh ios-start` then `verify.sh ios-shot launch-ios`. The PNG shows the home screen with the button. Run `verify.sh doctor`. It prints `ok    iOS app com.mebit.app is running`. Run `verify.sh ios-stop` when done. Opening the app from the simulator's home-screen icon is not driven.
- **Proof.** Cite the three Android PNGs and the `steps.tsv` rows for the taps. The stacked and resume PNGs differ in the greeting, and `pidof` separates a new process from a new instance.

## Gotchas

- The icon label `app` comes from `androidApp/res/values/strings.xml`. If `app_name` changes, change the selector. Other apps in the drawer can have the same label, so `tap` fails and lists them. Pass the index then.
- The drawer lists apps alphabetically. With many apps installed, the icon may sit below the fold. Swipe up again before `tap`.
- The launcher belongs to the system image. The `desc:Search` handle was seen on the Google Play arm64 image for API 36, and another image may use other handles.
- After `start`, the icon does not bring the old instance back. Each tap stacks a new `MainActivity` on the same task, with fresh state. In one run the task grew from 1 to 4 activities in four taps. Force-stop and open the app from the icon first when a recipe needs resume, and do not read the reset as a bug.
- `./kotlin run -m iosApp -d <udid>` fails without `-p iosSimulatorArm64`. The helper passes it.
- Without `-d`, `./kotlin run -m iosApp` and the iOS tests pick one of the user's simulators and boot it. Always go through `ios-start`.
