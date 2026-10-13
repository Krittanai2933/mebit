# Home greeting

The home screen is the only screen of the app. A button labeled `Click me! na kub` shows and hides a panel with the Compose Multiplatform logo and the text `Compose: Hello, <platform>!`. On Android `<platform>` is `Android <API level>`, for example `Android 36`. On iOS it is the system name and version.

## Sub-features

- `home-first-screen` shows the button and no greeting after a fresh launch.
- `home-reveal` shows the logo and the greeting after one tap.
- `home-hide` hides both again after a second tap.
- `home-platform-text` names the Android API level of the device.

## How to get to it (user POV)

- Open the app. The home screen is the root screen, with no navigation.
- Android: `verify.sh start` installs and opens it. `app-launch.md` covers the launcher icon.
- iOS simulator: `verify.sh ios-start` installs and opens it. Taps cannot be driven there.

## Driving it with verify.sh

Preconditions:

- `verify.sh start` finished in this run and `verify.sh doctor` passes.
- The app has had no taps since launch.

- **First screen.** Wait for the button. Run `verify.sh see "Click me! na kub"`. The button is visible. Run `verify.sh gone "text~:Hello, Android"` and it succeeds at once. Run `verify.sh shot home-first-screen`. The PNG shows a lavender screen with one purple button at the top and nothing below it.
- **Reveal.** Tap the button. Run `verify.sh tap "Click me! na kub"` then `verify.sh see "text~:Hello, Android"`. Run `verify.sh shot home-reveal`. The PNG shows the blue and purple Compose cube above the text `Compose: Hello, Android 36!`. The cube is not in the hierarchy, so only the PNG proves it.
- **Platform text.** With the greeting revealed, compare the number in it with the device. Run `verify.sh adb shell getprop ro.build.version.sdk`. The number printed equals the number after `Android` in the greeting.
- **Hide.** Tap the button again. Run `verify.sh tap "Click me! na kub"` then `verify.sh gone "text~:Hello, Android"`. Run `verify.sh shot home-hide`. The PNG matches the first screen.
- **iOS first screen.** Run `verify.sh ios-start` then `verify.sh ios-shot home-first-screen`. The PNG shows the same button and no greeting. Reveal, hide, and the iOS greeting text (`iOS <version>`, from `shared/src@ios/Platform.ios.kt`) cannot be driven. Report them as not verified on iOS.

## Gotchas

- The greeting contains the API level, so an exact match is specific to one device. Use `text~:Hello, Android` unless you pinned the AVD.
- The logo has no content description, so `see` cannot find it. Read the PNG.
- Rotating the device or force-stopping the app hides the greeting again, because the screen keeps its state with `remember` and not `rememberSaveable`. Do not read that as a regression from your change.
- BACK on the home screen leaves the app and shows the launcher. The app process keeps running.
- `tap` presses the centre of the node's bounds. With a second button on screen, pass the index, as in `tap "text~:Click me" 2`.
