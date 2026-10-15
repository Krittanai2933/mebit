# App launch

A user opens the app from its launcher icon, leaves it, and comes back. This feature covers how the app starts, what state survives leaving it, and what the icon does when the app is already running.

## Sub-features

- `launch-icon` opens the app from its launcher icon and shows a fresh screen after the app was force-stopped.
- `launch-stacked-instance` shows a fresh screen when the icon is tapped after `verify.sh start` launched the app, because the icon opens a new instance on top of the old one.
- `launch-resume` returns to the same screen and state after HOME and the icon, when the icon started the app.
- `launch-ios` installs and opens the app on an iOS simulator.

## How to get to it (user POV)

- Android: the icon labeled `app` in the all-apps list. Swipe up from the home screen to open it.
- Android, developer path: `./kotlin run -m androidApp -d <serial>`, which `verify.sh start` runs for you.
- iOS simulator: `verify.sh ios-start`, which runs `./kotlin run -m iosApp -p iosSimulatorArm64 -d <udid>`.

## Driving it with verify.sh

Preconditions:

- `verify.sh start` finished in this run and `verify.sh doctor` passes.

- **Open the drawer.** Run `verify.sh drawer`. It presses HOME, waits for the launcher, then tries gestures in turn. From API 36 that is the all-apps key twice and then two swipes. Below API 36 it is the two swipes and then the key. After a failed round it presses HOME and tries once more. It prints `all-apps list open (via key)`, or the gesture that worked. Later steps say "open the drawer as above".
- **Stacked instance.** This needs the app to have been launched by `start`, not by the icon, with the greeting visible (run the Reveal recipe in `home-greeting.md`). Run `verify.sh adb shell pidof com.mebit.app` and note the number. Open the drawer as above, then run `verify.sh see "desc:app"`, `verify.sh tap "desc:app"`, `verify.sh see "Click me! na kub"`, and `verify.sh gone "text~:Hello, Android"`. The greeting is hidden and `pidof` prints the same number. The icon opened a new instance on top of the old one, in the same process. Run `verify.sh shot launch-stacked-instance`.
- **Cold start from the icon.** Run `verify.sh adb shell am force-stop com.mebit.app`. Open the drawer as above, then run `verify.sh see "desc:app"`, `verify.sh tap "desc:app"`, `verify.sh see "Click me! na kub"`, and `verify.sh gone "text~:Hello, Android"`. The home screen appears with the greeting hidden, and `pidof` prints a new number. Run `verify.sh shot launch-icon`.
- **Resume.** This needs the app to have been started by the icon, as at the end of the cold start. Reveal the greeting with `verify.sh tap "Click me! na kub"` and `verify.sh see "text~:Hello, Android"`. Note `pidof`. Open the drawer as above, run `verify.sh see "desc:app"`, `verify.sh tap "desc:app"`, then `verify.sh see "text~:Hello, Android"`. The greeting is still visible and `pidof` prints the same number. Run `verify.sh shot launch-resume`.
- **iOS.** Run `verify.sh ios-start` then `verify.sh ios-see "Click me! na kub"` and `verify.sh ios-shot launch-ios`. The PNG shows the home screen with the button. Run `verify.sh doctor`. It prints `ok    iOS app com.mebit.app is running`. Run `verify.sh ios-stop` when done. Opening the app from the simulator's home-screen icon is not driven.
- **Proof.** Cite the three Android PNGs and the `steps.tsv` rows for the taps. The stacked and resume PNGs differ in the greeting, and `pidof` separates a new process from a new instance.

## Gotchas

- Tap the icon with `desc:app`, not `app`. Once the launcher has learned the app it also lists `Predicted app: app` with the text `app`, so `tap "app"` matches two nodes and fails. The label comes from `androidApp/res/values/strings.xml`, so change the selector if `app_name` changes.
- Wait for the icon with `see "desc:app"` before you tap it. In one of 28 earlier stress cycles the list was open, yet an immediate `tap` found no `desc:app` node. With the `see` first, the icon was found in 339 of 339 calls where the list opened, and the tap reached the first screen every time.
- No single gesture opens the list on every image. `KEYCODE_ALL_APPS` opened it 4 of 4 times on API 36.1 Play and on the CANARY image and 0 of 4 on API 35, so `drawer` tries the key first from API 36 on, and below API 36 tries the swipes first and the key last. Swipes opened it 4 of 4 on API 35 and only 1 to 3 of 4 on API 36.1 Play. A starved launcher ignores gestures. Under six busy loops on the host one call on API 35 saw all four ignored for 103 s, with the launcher home screen in front and the app behind it. `drawer` waits a second after the launcher appears, and when a round fails it presses HOME and tries a second round. In this round `drawer` failed once in 260 calls on API 35 Play (idle, host load, guest load and first call after `start`) and 0 times in 80 on API 36 Play. A side-by-side run of the old and the new `drawer` on API 35 Play (idle, six busy loops, an iOS build loop) failed 0 of 180 calls for each.
- `drawer` finds the list through the node `apps_list_view` and the dock through `hotseat`. Both come from the Pixel Launcher (`com.google.android.apps.nexuslauncher`), which every tested image used. Another launcher needs other handles.
- After `start`, the icon does not bring the old instance back. Each tap stacks a new `MainActivity` on the same task, with fresh state. In one run the task grew from 1 to 4 activities in four taps. Force-stop and open the app from the icon first when a recipe needs resume, and do not read the reset as a bug.
- `./kotlin run -m iosApp -d <udid>` fails without `-p iosSimulatorArm64`. The helper passes it.
- Without `-d`, `./kotlin run -m iosApp` and the iOS tests pick one of the user's simulators and boot it. Always go through `ios-start`.
