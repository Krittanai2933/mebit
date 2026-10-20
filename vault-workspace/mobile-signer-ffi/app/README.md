# Kotlin Multiplatform project

This project is targeting Android, iOS, built with
the [Kotlin Toolchain](https://kotlin-toolchain.org/latest/).

- [/androidApp](./androidApp) contains the Android application.
- [/iosApp](./iosApp) contains the iOS application.
- [/shared](./shared) holds the code shared across your applications — Compose UI,
  business logic, and platform-specific implementations. [src](./shared/src) is for common code; the
  sibling `src@<platform>` folders (for example `src@android`) hold code compiled only for the platform named in the
  folder.
- [/mobileSignerFfi](./mobileSignerFfi) holds the Kotlin bindings to the Rust library in [`../rust`](../rust). A script
  generates its sources. No other module depends on it yet. See
  [`../README.md`](../README.md#kotlin-bindings-uniffi-and-gobley).

The `kotlin` (macOS/Linux) and `kotlin.bat` (Windows) scripts in the project root are self-bootstrapping wrappers for
the Kotlin Toolchain: they download the pinned toolchain version on first use, so no separate installation is required.
Build the whole project with `./kotlin build`, after you run `../scripts/ffi.sh build` once. The `mobileSignerFfi` module
links the Rust libraries, and without them `./kotlin build` and `./kotlin test` fail. The commands scoped to one of the
other modules (`-m shared`, `-m androidApp`, `-m iosApp`) do not need the script.

### Running

Run each module from the command line:

- Android app: `./kotlin run -m androidApp`
- iOS app: `./kotlin run -m iosApp`

### Testing

Run the project's tests with `./kotlin test` (after `../scripts/ffi.sh build`, see above), or `./kotlin test -m <module>` for a single module. `../scripts/ffi.sh test` runs the `mobileSignerFfi` tests on every target that can run them.

---

Learn more
about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html), [Kotlin Toolchain](https://kotlin-toolchain.org/latest/), [Compose Multiplatform](https://kotlinlang.org/compose-multiplatform/), [Kotlin/Wasm](https://kotl.in/wasm/), [Ktor](https://ktor.io/).
