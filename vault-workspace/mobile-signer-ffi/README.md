# mobile-signer-ffi

**Owner**: person 4 — `docs/00-capstone-brief.md` §3.6.

**Depends on**: `vault-core` for the vault-signer half. The generalized multisig API has landed and the Kotlin bindings expose it. The three-party functions still sit on `vault-core` mocks, and UI/UX work does not wait for either. Recommended to depend on `bdk` (Bitcoin Dev Kit, Rust core) for the hot-wallet half — see below.

## This is two wallets in one app

The borrower's BTC is always either **free** (not pledged to any loan) or **pledged** (locked in a specific loan's 2-of-3 vault). That means this module is genuinely two layers, not one:

1. **Hot wallet (single-sig)** — for free BTC. A real Bitcoin wallet: address generation, UTXO tracking, fee estimation, send/receive, its own node connectivity (Electrum/Esplora, not routed through the backend), and watch-only PSBT construction. **Recommended: build on `bdk` Rust core** rather than hand-rolling this — it supports all of the above natively. Do not use `bdk-rn`/`bdk-dart` wrappers (integration-testing-only as of July 2026).
2. **Vault signer (multisig)** — for pledged BTC. The original scope: wrap `vault-core`'s key derivation and PSBT signing behind UniFFI.

Both need to read as one balance on Home/Portfolio in the UI, but the key/signing logic stays clearly separated underneath — see `../../docs/01-architecture.md`'s "mobile-signer-ffi is two wallets in one app" section and `../../docs/04-open-items.md` (items 2 and 9) for the open questions here.

**Current status: only the vault-signer half has any code.** It is callable from Kotlin through UniFFI, see [Kotlin bindings](#kotlin-bindings-uniffi-and-gobley). The hot-wallet half hasn't been started — see `../../docs/05-progress-and-next-steps.md`.

## Layout

```
mobile-signer-ffi/
├── rust/      # UniFFI exports over vault-core (vault signer) -> Kotlin.
│              # Hot-wallet / bdk code belongs here too once started.
├── scripts/   # ffi.sh builds the Rust libraries, generates the Kotlin bindings and checks they are current
└── app/       # Kotlin Multiplatform project (Kotlin Toolchain 0.13.0): androidApp, iosApp, shared,
               # and mobileSignerFfi, the generated Kotlin bindings
```

## Responsibilities

- Hot wallet: transaction construction, send, receive, node connectivity, watch-only PSBT — see the wallet capability checklist in `../../docs/design-notes.md`
- Vault signer: wrap `vault-core`'s key derivation and PSBT signing behind a UniFFI interface callable from Kotlin/Swift
- Demo app covering all 12 screens from the design (see below) — but scope MVP screens first, the rest are stretch goals

## Design reference — don't design from scratch

`../../docs/design-notes.md` has the full screen list, multi-loan model, the hot-wallet requirement and capability checklist, worked numeric examples, and the three explored variants each for the Home screen, the risk display, and the borrow-amount input, distilled from the `mebit` mobile app design. `../../design-reference/` has the color/type tokens and logos. Read both before wireframing.

## Getting started

### Rust crate

```
cargo test -p mobile-signer-ffi
```

The crate exports four functions through UniFFI: `derive_borrower_pubkey`, `compute_vault_address`, `sign_psbt` and `compute_vault_address_multisig`. Its golden addresses are re-derived by `scripts/address_oracle.py`. This is the **vault-signer half only**. The **hot-wallet half** (`bdk`-based) hasn't been started, and that is the single biggest gap in this module right now.

### Kotlin bindings (UniFFI and Gobley)

The Rust crate reaches Kotlin through [UniFFI](https://mozilla.github.io/uniffi-rs/) 0.29.4 and the [Gobley](https://github.com/gobley/gobley) 0.3.7 bindgen, which generates Kotlin Multiplatform code. Gobley's Gradle plugins do not run under the Kotlin Toolchain, so `scripts/ffi.sh` runs the bindgen directly. The generated files are committed in the `app/mobileSignerFfi` module. They compile unmodified under the Toolchain's Kotlin 2.4.20, although Gobley 0.3.7 was built against Kotlin 2.1.10.

#### What Kotlin gets

The package is `com.mebit.ffi.signer`.

| Kotlin function | Result |
|---|---|
| `computeVaultAddressMultisig(xpubs: List<String>, threshold: UByte): String` | A real address. It is the first receive address of `wsh(sortedmulti(threshold, <xpub>/0/*, ...))`, built by `vault-core`. `xpub` keys give `bc1...` and `tpub` keys give `tb1...`. Throws `MobileSignerException`. |
| `computeVaultAddress(borrowerPubkey: String, platformPubkey: String, lenderPubkey: String): String` | **Mock.** The three-party lending vault. Returns `tb1q` plus a hash from `vault-core`'s mock descriptor, which is not a Bitcoin address. |
| `deriveBorrowerPubkey(accountXpub: String, index: UInt): String` | **Mock.** Returns `<accountXpub>/m/48'/0'/0'/2'/<index>`, which is not a public key. |
| `signPsbt(psbtJson: String, borrowerPubkey: String): String` | **Mock.** Returns `MOCK_SIG[<loan_id>:<output count>:<borrowerPubkey>]`. It signs nothing and does not run the policy engine. Throws `MobileSignerException.InvalidPsbtJson` for JSON that is not an `UnsignedPsbt`. |

`computeVaultAddressMultisig` always returns index 0 and takes the network from the key prefix, because its fixed two-argument signature has no room for either. Another index, the change chain or regtest needs a new function. `tpub` keys cover testnet, testnet4 and signet, which all give the same `tb1...` string. Regtest (`bcrt1...`) cannot be produced.

Before it calls `vault-core`, `computeVaultAddressMultisig` checks the keys one at a time, in list order, and the first bad key decides the error. It rejects:

- A string longer than 120 characters, before it decodes the string.
- A key deeper than 253 levels. Under miniscript 13.1.0 `derive_address_at` panics on such a key, and under 13.2.0 it returns an error. This check gives both the same `InvalidXpub`.
- A SLIP-132 key (`zpub`, `Zpub`, `vpub`, `ypub`). Convert it to `xpub` or `tpub` first.
- A list that mixes `xpub` and `tpub` keys.
- A list that holds one public key twice, even under a different chain code.

`vault-core` accepts the mixed and the repeated lists (`docs/04-open-items.md` item 28). The threshold and key-count rules run after every key has passed, so a list of 21 keys with one repeat reports the repeat. The repeat check catches one key entered twice. It does not prove that the keys belong to different signers, because a child of a listed key, or a second account from one seed, has a different public key.

#### Errors

Every fallible function throws a subclass of `MobileSignerException`. The error is flat, so the list index appears only inside `message`, and `message` equals the Rust error's `Display` text.

| Subclass | Thrown when | `message` |
|---|---|---|
| `InvalidPsbtJson` | `signPsbt` gets malformed JSON | `invalid PSBT json: ` followed by the `serde_json` text. The prefix is unchanged from before UniFFI. |
| `InvalidXpub` | a key is not a base58 `xpub` or `tpub`, is longer than 120 characters, or is deeper than 253 levels | `key at index 1 is not a valid xpub or tpub: ` followed by the parser's text or the length, or `key at index 0 is too deep to derive addresses from: depth 254, the maximum is 253`. It never contains the key. |
| `NetworkMismatch` | the list mixes `xpub` and `tpub` keys | `key at index 2 is for a different network than key at index 0 (xpub is mainnet, tpub is testnet)` |
| `DuplicateXpub` | two keys share one public key | `key at index 2 is the same as key at index 0` |
| `InvalidThreshold` | the threshold is 0 or above the key count, or the list is empty or has more than 20 keys | `vault-core`'s text, for example `invalid multisig threshold: invalid threshold 4-of-3; cannot have k > n` |
| `VaultCore` | any other `vault-core` failure | `vault-core`'s text |

The bindings also define `InternalException`, which UniFFI throws for an unexpected error such as a Rust panic. It is not a `MobileSignerException`, so a caller that must not crash catches both. No known input triggers it. A binding that is out of date with the Rust enum can also throw a plain `RuntimeException`.

#### Types

| Rust | Kotlin |
|---|---|
| `&str`, `String` | `String` |
| `u32` | `UInt` |
| `u8` | `UByte` |
| `Vec<String>` | `List<String>` |
| `Result<T, MobileSignerError>` | `T`, annotated `@Throws(MobileSignerException::class)` |

`Int.toUByte()` wraps without an error, so `300.toUByte()` is 44. Check a threshold's range before you convert it.

#### Build and test

Run the commands from `vault-workspace/mobile-signer-ffi`. You need macOS for `build`, `test` and `apk`.

1. Install the prerequisites.
   - Rust with four targets: `rustup target add aarch64-apple-ios aarch64-apple-ios-sim aarch64-linux-android x86_64-linux-android`
   - `cargo install cargo-ndk`. Version 4.1.2 is the one tested.
   - NDK 27.1.12297006: `sdkmanager --install 'ndk;27.1.12297006'`. `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) must point at the SDK, or the SDK must sit at `~/Library/Android/sdk`.
   - Xcode with an iOS simulator runtime.
2. Run `scripts/ffi.sh build`. Run it once per clone and after every change under `rust/` or `vault-core/`. It installs the pinned bindgen into `vault-workspace/target/tools`, builds the Rust libraries for the host, iOS and Android, regenerates the Kotlin bindings, and stops when an assertion fails. Commit the generated files it changes.
3. Run `scripts/ffi.sh test`. It runs `build`, then `cargo test`, then the Kotlin tests on the Android host JVM and on an iOS simulator. It refuses to start while a simulator is booted.
4. Run `scripts/ffi.sh check` before you commit a Rust change. It regenerates the bindings in a temporary directory and diffs the four generated files against the committed ones. It writes nothing under `app/` or `rust/` and needs `cargo` and `python3`. Only macOS was tried.

Three more commands are optional. `scripts/ffi.sh bindings` regenerates only the committed Kotlin, without the iOS and Android builds. Use it to refresh what you commit, or on a host that cannot run `build`. It leaves the iOS and Android libraries as they were, so run `build` before any Kotlin test of a changed export or doc comment. On macOS it does refresh the host test library. Only macOS was tried. `scripts/ffi.sh apk` copies the Android libraries into `app/androidApp/jniLibs/`. `scripts/ffi.sh clean` removes the files `build` and `apk` staged outside git and keeps the installed bindgen and the host build. `python3 scripts/address_oracle.py` re-derives the golden multisig addresses from the constants in `rust/src/lib.rs` without `vault-core`, using only the Python standard library. `build`, `check` and `test` run it too.

Plain `./kotlin build` and `./kotlin test` include `mobileSignerFfi`, so they fail until `ffi.sh build` has run. `ffi.sh build` needs macOS, so on another OS nothing stages the host test library and `./kotlin test` cannot pass. That is inferred from the code, and no run happened there. On a fresh clone, `./kotlin build` stops at the link task of an iOS test target (`linkIosArm64TestDebug` or `linkIosSimulatorArm64TestDebug`, whichever fails first) with `Undefined symbols`, and `./kotlin test -p android` fails with `UnsatisfiedLinkError: Unable to load library 'mobile_signer_ffi'`. Scope the commands to one module, for example `./kotlin build -m androidApp` or `./kotlin test -m shared -p android`, and they do not need the Rust libraries.

To use the library from an app module, add `- //mobileSignerFfi` to its `dependencies`. No module does yet. For Android, run `scripts/ffi.sh apk` once as well, because the library reaches an APK only from `androidApp/jniLibs/`. After that, every `ffi.sh build` refreshes those copies. Decide `settings.android.abiFilters` first. The library exists only for `arm64-v8a` and `x86_64`, so the Toolchain warns about the other ABIs, and a filter drops 32-bit devices from the whole app. For iOS, every build that includes the module needs the Rust libraries, so developers must run `ffi.sh build` before they build the app.

#### Supported targets

| Target | Native library | Bindings compile | Call path |
|---|---|---|---|
| Android `arm64-v8a` | `libmobile_signer_ffi.so`, NDK 27.1.12297006, 16 KB aligned | yes | Ran four times on an arm64 emulator with API 36, the last time on the final code. The app loaded the library through JNA, and every call returned the expected value. Five error paths threw the expected subclass with the expected message (`InvalidThreshold`, `DuplicateXpub`, `NetworkMismatch`, and `InvalidXpub` for a key too deep and for a string too long). `InvalidPsbtJson`, an `InvalidXpub` from the parser and `VaultCore` never ran on ART. This check was manual and no script in the repo repeats it. It needed a temporary patch to `androidApp` and the verify skill. |
| Android `x86_64` | `libmobile_signer_ffi.so`, same build | yes | Built, not run. This Mac has no x86_64 emulator. |
| Android host JVM | host `libmobile_signer_ffi.dylib` | yes | Ran. `ffi.sh test` runs the generated Android code against the host library. It does not load the cross-built `.so`. |
| `iosSimulatorArm64` | `libmobile_signer_ffi.a`, embedded in the cinterop klib | yes | Ran on a simulator by `ffi.sh test`. |
| `iosArm64` | `libmobile_signer_ffi.a` for devices | yes | Built, not run. There is no device. |

No app module depends on `mobileSignerFfi`, so the Xcode link of an app against the Rust library is not part of these checks. A release build with R8 minification has not been tried. It probably needs keep rules for JNA and the generated interfaces, and `androidApp/proguard-rules.pro` has none. The generated output was not checked on Linux or on a second Mac. No device or image with 16 KB pages ran the library, although the ELF alignment of both the library and JNA 5.18.1's own `libjnidispatch.so` is 0x4000.

#### Pitfalls found

**The generator**
- **Gobley ignores a misplaced config.** `rust/uniffi.toml` needs its keys at the root of the file, and `--config` must name it. Without them the bindgen exits 0 and writes only the common file. `ffi.sh` fails when the four expected files are not all there. The file also pins `cdylib_name`, because without it the generated `findLibraryName` follows the name of the library file the bindgen reads.
- **Rust and Kotlin must match.** Every exported signature, and every `///` doc comment on an export, feeds a UniFFI checksum. Android checks it when the library loads and throws `UniFFI API checksum mismatch`. The iOS generated code never checks it, so stale bindings pass on iOS. `MobileSignerError` has no checksum at all, and Kotlin reads its variants by position. Reordering a variant, or inserting or removing one before the last, makes both platforms throw the wrong subclass, and nothing reports it. Appending one throws a plain `RuntimeException`. `cargo test` fails when a function's checksum differs from the committed Android file. Only `ffi.sh check` sees an enum change or an iOS-only drift, so run it after every change.
- **Doc comments reach Kotlin as KDoc.** A `/*` or `*/` inside a `///` line breaks the generated file, because Kotlin block comments nest. `ffi.sh` fails before it generates.
- **A leftover file named like generated output breaks the Kotlin build.** A renamed or copied `mobile_signer_ffi.*` file redeclares the generated objects, and the compiler errors point into the generated code. `ffi.sh` fails on such a file in the four generated folders. Files with other names are left alone.
- **The versions are coupled.** Gobley 0.3.7 is built on UniFFI 0.29.4. `Cargo.lock` is git-ignored, so `rust/Cargo.toml` pins `uniffi = "=0.29.4"`. `ffi.sh` fails when that pin differs from its own constant or when the installed bindgen is not 0.3.7. The link between Gobley 0.3.7 and UniFFI 0.29.4 comes from the bindgen's install log and nothing checks it, so bump all three together.

**The Kotlin Toolchain**
- **The cinterop `.def` must sit in `cinterop@ios/`.** A `cinterop/` folder at the module root is ignored when android is a platform, and the iOS build then reports hundreds of unresolved references.
- **`libraryPaths` must be absolute.** cinterop runs with the Kotlin/Native install directory as its working directory and expands no variables. So the header-only `mobile_signer_ffi.def` is committed, and `ffi.sh build` writes `mobile_signer_ffi_link.def` per machine. Git ignores that file.
- **The Toolchain does not rebuild the cinterop klib when only the `.a` changes.** Without a hash line in the link def, iOS tests run old Rust and report no error. `ffi.sh` writes the hash and replaces the def only when its text changed, because a new mtime alone costs a rebuild.
- **`jniLibs/` in a library module is ignored.** Only `androidApp/jniLibs/<abi>/` reaches an APK.
- **JNA needs two artifacts.** The device needs the AAR. The host JVM test needs the plain JAR, because the AAR has no desktop natives. The test loads the host library from `testResources@android/darwin-aarch64/` (`darwin-x86-64` on an Intel Mac), which git ignores, and JNA finds it by name.
- **A failing iOS test run leaves its simulator booted.** `ffi.sh test` refuses to start while any simulator is booted, and after a failing run it shuts down only the simulators booted since just before the iOS run. Do not boot a simulator during that run.

**The native builds**
- **cargo-ndk picks the newest NDK unless `ANDROID_NDK_HOME` is set.** On the machine used here that is an r29 beta. An empty `ANDROID_NDK_HOME`, which some shells export, makes cargo-ndk fail. `ffi.sh` sets the variable to the pinned NDK itself.
- **The 16 KB alignment comes from cargo-ndk.** Plain `cargo` with the same NDK links at 0x1000, and 16 KB page-size devices cannot load that. `ffi.sh` asserts 0x4000 on every `LOAD` segment.
- **Build the iOS libraries for iOS 15.0, in their own target directory.** Kotlin/Native links its test executable at 15.0, and the Xcode project targets 18.2. Without the setting, the C objects carry the SDK default and the link warns once per object. `ffi.sh` checks every object's minimum version with `otool -l`, because `vtool` does not read an archive.

**The behavior**
- **`vault-core` accepts duplicate and mixed-network xpubs, and miniscript 13.1.0 panics on a deep one.** Under miniscript 13.1.0, `derive_address_at` hits an `unreachable!` for a key at depth 254 or more, which UniFFI turns into `InternalException`. Miniscript 13.2.0 returns an error instead (measured on both). `Cargo.lock` is git-ignored, so one machine can hold 13.1.0 while a fresh clone resolves 13.2.0. The wrapper rejects all three cases under both versions, and `docs/04-open-items.md` item 28 asks the `vault-core` owners to take the checks over and to require miniscript 13.2.
- **Dependency text can change.** `Cargo.lock` is git-ignored, so a fresh resolve can reword the `miniscript`, `bitcoin` and `serde_json` messages. The tests assert the wrapper's own texts exactly and the dependency texts by prefix. A fresh resolve with no lockfile (miniscript 13.2.0, serde_json 1.0.154) passes all 17 tests.
- **The generated Android file exposes helper types.** Gobley makes `FfiConverter*`, `RustBuffer` and similar types public. The Rust side cannot hide them.

#### Synchronous calls

All four functions are synchronous. In Kotlin they are ordinary functions, not `suspend` functions, and each call blocks its thread until Rust returns. They do CPU-only work. Valid input returns fast, since the whole Rust test run takes 0.07 s. Base58 decoding is quadratic, though. A 100,000-character string took 7.3 s in a release build before the wrapper started rejecting any string over 120 characters. Call them off the main thread if a call could ever take long. Nothing here is asynchronous. The Bluetooth calls to hardware wallets in `jade-ble` and `trezor-ble` will need UniFFI async exports in Phase 1, and that work is out of scope for this change.

### Removed Expo prototype (stale)

Commit `346aeb2` replaced the Expo app with the Kotlin Multiplatform project in `app/`. The paragraphs below describe the removed app. They stay until the team rewrites this file, see `docs/04-open-items.md` item 27.

`app/` is an Expo (React Native + TypeScript) app — chosen so the team has something to run and click through immediately instead of starting from a blank project:
```
cd vault-workspace/mobile-signer-ffi/app
npm start        # then press w for web, i for iOS simulator, a for Android
```

It covers all 12 screens from `docs/design-notes.md` — Onboarding → Seed backup → Face ID → Home → Receive → Borrow → Loan Dashboard → Repay → Success, plus Activity, Portfolio, and Settings reachable from the bottom nav — styled with the tokens in `design-reference/`. The wallet supports **multiple concurrent loan contracts** (each pledging its own isolated slice of BTC as collateral, per `docs/design-notes.md`'s multi-loan model), so Portfolio is where you see all of them and Loan Dashboard/Repay operate on whichever one you tapped into.

All numbers come from `app/src/mockVault.ts`, a plain-TypeScript stand-in for the real vault-core/UniFFI calls *and* the eventual hot-wallet/`bdk` calls (no cryptography, no native module, no real UTXOs) — swap its functions for real bindings once `rust/`'s interfaces exist. See `app/src/mockVault.ts`'s header comment for why it's mocked rather than wired up already.

Not built yet (left as follow-up work, not blocking the skeleton): the hot-wallet layer entirely (see above), react-navigation (current nav is a simple screen-state switch in `App.tsx`), the verification/challenge-response flow, and real margin-call/liquidation triggers (term 2 scope per `docs/00-capstone-brief.md` §4).
