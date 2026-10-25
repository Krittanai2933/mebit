#!/usr/bin/env bash
# vault-workspace/mobile-signer-ffi/scripts/ffi.sh: the one lever between the Rust crate
# (../rust) and the Kotlin module (../app/mobileSignerFfi).
#
# Usage: ffi.sh build | bindings | check | test | apk | clean
#   build  Build the Rust library for the host and every declared Kotlin target, regenerate the
#          committed Kotlin bindings, vendor btleplug's Android Java, write the git-ignored files the
#          Kotlin build needs, and assert the iOS minos and the Android 16 KB alignment. Idempotent: run
#          it after cloning and after any change under vault-core/, jade-ble/, trezor-ble/ or
#          mobile-signer-ffi/rust/, then commit what it changed.
#   bindings  Regenerate and sync the committed Kotlin bindings and the vendored Java only, with no iOS or
#          Android build. For a quick refresh of what gets committed, and for a host that cannot run build.
#          The native libraries stay as they were, so run build before any Kotlin test of a changed export
#          or doc comment. Needs cargo and python3. Only macOS was tried.
#   check  Regenerate the bindings into a temp dir, apply scripts/patch_bindings.py, and diff them
#          against the committed ones; also diff the vendored btleplug Java and THIRD_PARTY_NOTICES.md
#          against the btleplug version cargo resolves; fail when a Kotlin golden address is absent from
#          rust/src/lib.rs, the two JNA versions in module.yaml differ, or a file named like a generated
#          one was not written by the bindgen. Writes nothing under app/ or rust/ (cargo builds into
#          vault-workspace/target, where the first run also installs the pinned bindgen). Needs cargo
#          and python3. Only macOS was tried.
#          This is the guard against stale bindings: iOS generated code has no load-time API check, and
#          UniFFI checksums cover the exported functions but not the error enum.
#   test   build, then cargo test, then the Kotlin tests: android on the host JVM, iosSimulatorArm64
#          on a simulator the Toolchain boots and shuts down. iosArm64 is compiled only. Prints which
#          rows ran at runtime and which were build only. Refuses to start while a simulator is booted.
#   apk    Opt in: copy the built .so files into app/androidApp/jniLibs/<abi>/, the only place that
#          reaches an APK. Never edits androidApp/module.yaml. After this, every build refreshes the copies.
#   clean  Remove what build and apk wrote outside git: the link def, the host library copy, the iOS and
#          Android target dirs, the staged .so files. Keeps the installed bindgen and the host build.
# Exit: 0 ok, 1 failure (reason on stderr), 2 usage or a missing tool (the message has the install command).
#
# Inputs: ANDROID_HOME, else ANDROID_SDK_ROOT (default ~/Library/Android/sdk). NDK_HOME, ANDROID_NDK_ROOT and ANDROID_NDK_HOME
# are overridden for the NDK build, and CARGO_TARGET_DIR for every cargo call that builds the crate.
# Host: build, test and apk need macOS (the iOS libraries need Xcode). check and bindings need cargo and python3.
# cargo-ndk 4.1.2 is what was verified; the alignment assert is the guard for any other version.
#
# Writes (C = committed, I = git-ignored), paths under vault-workspace/mobile-signer-ffi/app/mobileSignerFfi unless absolute:
#   C src/com/mebit/ffi/signer/mobile_signer_ffi.common.kt
#   C src@android/com/mebit/ffi/signer/mobile_signer_ffi.android.kt
#   C src@ios/com/mebit/ffi/signer/mobile_signer_ffi.native.kt
#   C cinterop@ios/include/mobile_signer_ffi.h
#   C src@android/com/nonpolynomial/, src@android/io/github/gedgygedgy/   btleplug's Android Java (28 files), THIRD_PARTY_NOTICES.md
#   I cinterop@ios/mobile_signer_ffi_link.def          absolute library paths + a hash of the .a files
#   I testResources@android/darwin-<arch>/libmobile_signer_ffi.dylib   host library on the host-JVM test classpath
#   I ../androidApp/jniLibs/<abi>/libmobile_signer_ffi.so   created by apk, refreshed by build
#   I vault-workspace/target/debug/libmobile_signer_ffi.dylib            host cdylib: bindgen input
#   I vault-workspace/target/ios-15.0/<triple>/release/libmobile_signer_ffi.a
#   I vault-workspace/target/android-ndk-27.1.12297006/...               cargo-ndk objects
#   I vault-workspace/target/jniLibs/<abi>/libmobile_signer_ffi.so       staged, 16 KB aligned
#   I vault-workspace/target/tools/bin/gobley-uniffi-bindgen             pinned, installed on first use
# Of the generated files, the android and native ones are the bindgen's output plus scripts/patch_bindings.py
# (one function, the cancellation leak). Only the files marked C are overwritten, and none is to be hand-edited.
# Files beside them are left alone.
set -euo pipefail

GOBLEY=0.3.7                  # built on uniffi_bindgen =0.29.4 (seen in its install log)
UNIFFI=0.29.4
NDK_VERSION=27.1.12297006     # r27b; cargo-ndk otherwise picks the newest installed NDK, an r29 beta on the author's machine
ABIS="arm64-v8a x86_64"
IOS_MIN=15.0                  # what Kotlin/Native 2.4.20 links test executables at (observed minos)
MODULE=mobileSignerFfi
NS=mobile_signer_ffi          # UniFFI namespace = crate name = library file stem = cdylib_name in rust/uniffi.toml

FFI=$(cd "$(dirname "$0")/.." && pwd)
WS=$(cd "$FFI/.." && pwd)
APP=$FFI/app
MOD=$APP/$MODULE
case $(uname) in Darwin) DYLIB_EXT=dylib ;; *) DYLIB_EXT=so ;; esac
HOST_LIB=$WS/target/debug/lib$NS.$DYLIB_EXT
IOS_TARGET_DIR=$WS/target/ios-$IOS_MIN
ANDROID_TARGET_DIR=$WS/target/android-ndk-$NDK_VERSION
JNILIBS=$WS/target/jniLibs
TOOLS=$WS/target/tools
BINDGEN=$TOOLS/bin/gobley-uniffi-bindgen
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

die() { echo "ffi.sh: $*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || { echo "ffi.sh: $1 not found: $2" >&2; exit 2; }; }

usage() {
  awk 'NR > 1 { if (!/^#/) exit; print }' "$0" >&2
  exit 2
}

preflight() {
  need cargo "install Rust: https://rustup.rs"
  grep -qE "^uniffi = (\"=${UNIFFI//./\\.}\"|\{ *version = \"=${UNIFFI//./\\.}\"[^}]*\})$" "$FFI/rust/Cargo.toml" \
    || die "rust/Cargo.toml must pin uniffi to =$UNIFFI (gobley-uniffi-bindgen $GOBLEY is built on it); bump both together"
  grep -qxF 'platforms: [android, iosArm64, iosSimulatorArm64]' <(sed 's/^ *//' "$MOD/module.yaml") \
    || die "$MODULE/module.yaml platforms changed; this script builds Rust for exactly android, iosArm64, iosSimulatorArm64"
  awk '/^\[/ { exit } /^package_name *=/ { found = 1 } END { exit !found }' "$FFI/rust/uniffi.toml" \
    || die "rust/uniffi.toml: package_name must sit at the TOML root (Gobley ignores nested tables silently)"
  [ "$(sed -n 's/^cdylib_name *= *"\(.*\)"$/\1/p' "$FFI/rust/uniffi.toml")" = "$NS" ] \
    || die "rust/uniffi.toml: cdylib_name must be \"$NS\", the library file stem"
  # Doc comments become KDoc, and Kotlin block comments nest: a stray /* inside one swallows the rest of the file.
  if grep -rnE --include='*.rs' '^[[:space:]]*//[/!].*(/\*|\*/)' "$FFI/rust/src" >&2; then
    die "rust/src: a doc comment contains /* or */ (lines above); reword it"
  fi
  PKG=$(sed -n 's/^package_name *= *"\(.*\)"$/\1/p' "$FFI/rust/uniffi.toml")
  [ -n "$PKG" ] || die "rust/uniffi.toml: could not read package_name"
  PKG_DIR=${PKG//.//}
  guard_synced_values
  cargo install --list --root "$TOOLS" 2>/dev/null | grep -qxF "gobley-uniffi-bindgen v$GOBLEY:" \
    || cargo install gobley-uniffi-bindgen --version "$GOBLEY" --locked --root "$TOOLS"
  [ "$("$BINDGEN" --version)" = "gobley-uniffi-bindgen $GOBLEY" ] \
    || die "$BINDGEN is not gobley-uniffi-bindgen $GOBLEY"
}

guard_synced_values() {
  local lit n=0 aar jar
  while read -r lit; do
    n=$((n + 1))
    grep -qF -- "\"$lit\"" "$FFI/rust/src/lib.rs" \
      || die "a Kotlin test asserts address $lit, which rust/src/lib.rs does not hold; both suites must pin one value"
  done < <(grep -rhoE '"(tb1|bc1)[a-z0-9]{30,}"' "$MOD"/test* | tr -d '"')
  [ $n -gt 0 ] || die "no golden address literal found under $MODULE/test*; the guard would pass with nothing to compare"
  need python3 "install Python 3 (macOS: xcode-select --install)"
  python3 -I "$FFI/scripts/address_oracle.py" > "$TMP/oracle.log" 2>&1 \
    || { cat "$TMP/oracle.log" >&2; die "scripts/address_oracle.py failed (output above): the golden addresses in rust/src/lib.rs disagree with its independent BIP-32/67 derivation, or the script could not run"; }
  python3 -I "$FFI/scripts/test_patch_bindings.py" > "$TMP/patchtest.log" 2>&1 \
    || { cat "$TMP/patchtest.log" >&2; die "scripts/test_patch_bindings.py failed (output above): scripts/patch_bindings.py no longer patches exactly once or no longer fails closed"; }
  aar=$(sed -n 's/^ *- net\.java\.dev\.jna:jna:\([0-9.]*\)@aar$/\1/p' "$MOD/module.yaml")
  jar=$(sed -n 's/^ *- net\.java\.dev\.jna:jna:\([0-9.]*\)$/\1/p' "$MOD/module.yaml")
  [ -n "$aar" ] && [ "$aar" = "$jar" ] || die "$MODULE/module.yaml: the JNA AAR ('$aar') and JAR ('$jar') versions must be equal"
}

build_host_cdylib() {
  (cd "$WS" && CARGO_TARGET_DIR=$WS/target cargo build -p mobile-signer-ffi)
}

generate() {
  local gen=$TMP/gen d
  rm -rf "$gen" "$TMP/mirror"
  "$BINDGEN" --library "$HOST_LIB" --crate "$NS" --config "$FFI/rust/uniffi.toml" --out-dir "$gen" >/dev/null
  # Every Gobley trap (no --config, nested keys, kotlin_multiplatform or kotlin_targets
  # missing, wrong package) changes this file set.
  local expected="commonMain/kotlin/$PKG_DIR/$NS.common.kt
androidMain/kotlin/$PKG_DIR/$NS.android.kt
nativeMain/kotlin/$PKG_DIR/$NS.native.kt
nativeInterop/cinterop/headers/$NS/$NS.h"
  [ "$(cd "$gen" && find . -type f | sed 's|^\./||' | sort)" = "$(echo "$expected" | sort)" ] \
    || die "bindgen wrote an unexpected file set; check the root keys of rust/uniffi.toml. Got: $(cd "$gen" && find . -type f | tr '\n' ' ')"
  for d in "src/$PKG_DIR" "src@android/$PKG_DIR" "src@ios/$PKG_DIR" cinterop@ios/include; do mkdir -p "$TMP/mirror/$d"; done
  cp -R "$gen/commonMain/kotlin/$PKG_DIR/." "$TMP/mirror/src/$PKG_DIR/"
  cp -R "$gen/androidMain/kotlin/$PKG_DIR/." "$TMP/mirror/src@android/$PKG_DIR/"
  cp -R "$gen/nativeMain/kotlin/$PKG_DIR/." "$TMP/mirror/src@ios/$PKG_DIR/"
  cp "$gen/nativeInterop/cinterop/headers/$NS/$NS.h" "$TMP/mirror/cinterop@ios/include/"
  # Gobley 0.3.7 leaks an async call's Rust future when the caller is cancelled before the call starts, and
  # the object the future holds can then never drop. Exact text once per file, or stop (README, Pitfalls).
  python3 -I "$FFI/scripts/patch_bindings.py" "$TMP/mirror/src@android/$PKG_DIR/$NS.android.kt" "$TMP/mirror/src@ios/$PKG_DIR/$NS.native.kt" \
    || die "scripts/patch_bindings.py failed (reason above). If Gobley's uniffiRustCallAsync is no longer the 0.3.7 text, review the cancellation leak (README, Pitfalls), then update or delete the script"
  guard_no_stray_generated
}

require_rust_targets() {
  command -v rustup >/dev/null 2>&1 || return 0
  local triple installed
  installed=$(rustup target list --installed)
  for triple in "$@"; do
    echo "$installed" | grep -qx "$triple" || { echo "ffi.sh: rust target $triple missing: rustup target add $triple" >&2; exit 2; }
  done
}

# vtool -show-build rejects an archive; otool -l reads every member.
ios_minos_ok() {
  otool -l "$1" | awk -v max="$2" '
    function num(v,  p) { split(v, p, "."); return p[1]*10000 + p[2]*100 + p[3] }
    /cmd LC_BUILD_VERSION|cmd LC_VERSION_MIN_/ { want = 1; n++ }
    want && ($1 == "minos" || $1 == "version") { if (num($2) > num(max)) bad++; want = 0 }
    END { exit (!n || bad) ? 1 : 0 }'
}

ios() {
  need xcrun "install Xcode, then: xcode-select --install"
  local triple lib
  require_rust_targets aarch64-apple-ios aarch64-apple-ios-sim
  for triple in aarch64-apple-ios aarch64-apple-ios-sim; do
    # Without IPHONEOS_DEPLOYMENT_TARGET the SDK default (27.0 here) lands in secp256k1-sys's C objects and
    # the 15.0 link warns once per object (observed). Rust objects from the shipped std are at 14.0.
    (cd "$WS" && CARGO_TARGET_DIR=$IOS_TARGET_DIR IPHONEOS_DEPLOYMENT_TARGET=$IOS_MIN \
      cargo build --release --target "$triple" -p mobile-signer-ffi)
    lib=$IOS_TARGET_DIR/$triple/release/lib$NS.a
    ios_minos_ok "$lib" "$IOS_MIN" || die "$triple: $lib has no objects, or one targets iOS above $IOS_MIN"
  done
}

write_link_def() {
  # cinterop runs with the Kotlin/Native install dir as its cwd and expands no variables, so libraryPaths
  # must be absolute (observed). Hence generated per machine and git-ignored.
  local dev=$IOS_TARGET_DIR/aarch64-apple-ios/release sim=$IOS_TARGET_DIR/aarch64-apple-ios-sim/release
  local def=$MOD/cinterop@ios/${NS}_link.def
  # The Toolchain rebuilds the cinterop klib when the def's mtime changes, not when the .a changes (observed),
  # so a stale klib would silently test old Rust. The hash line changes the text whenever a library changes.
  cat > "$def.new" <<EOF
# rust-libs-sha256 = $(cat "$dev/lib$NS.a" "$sim/lib$NS.a" | shasum -a 256 | cut -d' ' -f1)
package = $NS.link
# btleplug uses CoreBluetooth data symbols, and rustc does not embed the framework in a staticlib.
linkerOpts = -framework CoreBluetooth
staticLibraries.ios_arm64 = lib$NS.a
libraryPaths.ios_arm64 = $dev
staticLibraries.ios_simulator_arm64 = lib$NS.a
libraryPaths.ios_simulator_arm64 = $sim
EOF
  # Rewriting an identical def costs a rebuild too (new mtime, observed), so replace it only when the text changed.
  if cmp -s "$def.new" "$def"; then rm "$def.new"; else mv "$def.new" "$def"; fi
}

elf_load_aligned_16k() {
  "$1" -lW "$2" | awk '$1 == "LOAD" { n++; if ($NF != "0x4000") bad++ } END { exit (!n || bad) ? 1 : 0 }'
}

android() {
  command -v cargo-ndk >/dev/null 2>&1 || { echo "ffi.sh: cargo-ndk not found: cargo install cargo-ndk" >&2; exit 2; }
  require_rust_targets aarch64-linux-android x86_64-linux-android
  local sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}} abi
  local ndk=$sdk/ndk/$NDK_VERSION
  grep -qx "Pkg.Revision = $NDK_VERSION" "$ndk/source.properties" 2>/dev/null \
    || { echo "ffi.sh: NDK $NDK_VERSION not found under $sdk: sdkmanager --install 'ndk;$NDK_VERSION' (set ANDROID_HOME if the SDK is elsewhere)" >&2; exit 2; }
  local targets=(); for abi in $ABIS; do targets+=(-t "$abi"); done
  # ANDROID_NDK_HOME must be non-empty and valid: cargo-ndk fails on an empty one, which some login shells export (observed).
  (cd "$WS" && env -u NDK_HOME -u ANDROID_NDK_ROOT ANDROID_NDK_HOME="$ndk" CARGO_TARGET_DIR="$ANDROID_TARGET_DIR" \
    cargo ndk -v "${targets[@]}" -o "$JNILIBS" build --release -p mobile-signer-ffi) > "$TMP/cargo-ndk.log" 2>&1 \
    || { tail -20 "$TMP/cargo-ndk.log" >&2; die "cargo ndk failed"; }
  grep -q "Detected NDK v$NDK_VERSION " "$TMP/cargo-ndk.log" \
    || die "cargo-ndk did not pick NDK $NDK_VERSION: $(grep Detected "$TMP/cargo-ndk.log" || echo 'no Detected line')"
  # 16 KB alignment comes from cargo-ndk's linker wrapper: plain cargo with the same NDK links at 0x1000 (observed).
  local readelf; readelf=$(echo "$ndk"/toolchains/llvm/prebuilt/*/bin/llvm-readelf)
  [ -x "$readelf" ] || die "llvm-readelf missing in NDK $NDK_VERSION"
  for abi in $ABIS; do
    elf_load_aligned_16k "$readelf" "$JNILIBS/$abi/lib$NS.so" \
      || die "$abi: a LOAD segment is not 16 KB aligned (0x4000); 16 KB page-size devices cannot load it"
  done
}

host_test_lib() {
  # JNA finds the library by the generated name on the classpath under <os>-<arch>/, so the host-JVM test needs
  # no path, property or env var (observed). Copy only on change: a new mtime can trigger a rebuild.
  local arch dest; case $(uname -m) in arm64) arch=darwin-aarch64 ;; *) arch=darwin-x86-64 ;; esac
  dest=$MOD/testResources@android/$arch
  mkdir -p "$dest"
  cmp -s "$HOST_LIB" "$dest/lib$NS.dylib" || cp "$HOST_LIB" "$dest/"
}

generated_files() { echo "src/$PKG_DIR/$NS.common.kt src@android/$PKG_DIR/$NS.android.kt src@ios/$PKG_DIR/$NS.native.kt cinterop@ios/include/$NS.h"; }

sync_generated() {
  local f
  for f in $(generated_files); do
    cmp -s "$TMP/mirror/$f" "$MOD/$f" || { mkdir -p "$(dirname "$MOD/$f")"; cp "$TMP/mirror/$f" "$MOD/$f"; }
  done
  DIRTY=
  for f in $(generated_files); do DIRTY="$DIRTY$(git -C "$FFI" status --porcelain -- "app/$MODULE/$f" 2>/dev/null || true)"; done
  [ -z "$DIRTY" ] || echo "ffi.sh: bindings differ from git; commit app/$MODULE/src*, cinterop@ios/include with the Rust change"
}

# A leftover named like a generated file (after a rename or a bad merge) redeclares its objects, and the
# Kotlin build then fails far from the cause. Files with other names are left alone.
guard_no_stray_generated() {
  local d f rel stray=
  for d in "src/$PKG_DIR" "src@android/$PKG_DIR" "src@ios/$PKG_DIR" cinterop@ios/include; do
    for f in "$MOD/$d/$NS".*; do
      [ -e "$f" ] || continue
      rel=${f#"$MOD"/}
      case " $(generated_files) " in *" $rel "*) ;; *) stray="$stray $rel" ;; esac
    done
  done
  [ -z "$stray" ] || die "not written by the bindgen but named like its output:$stray (delete or rename them)"
}

# btleplug's Android half is Java that the app must compile itself.
JAVA_ROOTS="com/nonpolynomial io/github/gedgygedgy"

stage_btleplug_java() {
  local meta dir version root
  meta=$(cd "$WS" && cargo metadata --format-version 1 --filter-platform aarch64-linux-android) || die "cargo metadata failed"
  { read -r dir; read -r version; } < <(python3 -I -c '
import json, os, sys
found = [p for p in json.load(sys.stdin)["packages"] if p["name"] == "btleplug"]
assert len(found) == 1, "expected one btleplug in the lock, got %d" % len(found)
print(os.path.dirname(found[0]["manifest_path"]))
print(found[0]["version"])' <<<"$meta") || die "could not find btleplug in cargo metadata"
  local src=$dir/src/droidplug/java/src/main/java
  [ -d "$src" ] && [ -f "$dir/LICENSE.md" ] || die "btleplug $version has no Android Java under $src, or no LICENSE.md"
  [ "$(cd "$src" && find . -type f ! -name '*.java' | wc -l | tr -d ' ')" = 0 ] || die "$src holds a file that is not Java"
  [ "$(cd "$src" && find . -mindepth 1 -maxdepth 1 -type d | sed 's|^\./||' | sort | tr '\n' ' ')" = "com io " ] \
    || die "btleplug $version: the Java tree no longer starts with com/ and io/; update JAVA_ROOTS"
  rm -rf "$TMP/java"; mkdir -p "$TMP/java"
  for root in $JAVA_ROOTS; do mkdir -p "$TMP/java/$root"; cp -R "$src/$root/." "$TMP/java/$root/"; done
  [ "$(find "$TMP/java" -type f | wc -l | tr -d ' ')" = "$(find "$src" -type f | wc -l | tr -d ' ')" ] \
    || die "btleplug $version: Java files outside JAVA_ROOTS ($JAVA_ROOTS)"
  {
    echo "# Third-party notices"
    echo
    echo "## btleplug $version"
    echo
    echo "Its Android Java sources are copied into \`src@android/\` (\`com/nonpolynomial\` and \`io/github/gedgygedgy\`)"
    echo "by \`scripts/ffi.sh\`. Do not edit them: \`ffi.sh check\` diffs them against the crate. Its licence follows."
    echo
    cat "$dir/LICENSE.md"
    echo
    echo "---"
    echo
    echo "## jni-utils-rs"
    echo
    echo "btleplug $version carries jni-utils-rs 0.1.1's Java classes (\`io/github/gedgygedgy\`) and its own licence"
    echo "file does not name their authors. The licence of jni-utils-rs follows."
    echo
    cat <<'JNI_UTILS_LICENSE'
Copyright 2021-2022 Gedgy Gedgy, Kyle Machulis <kyle@nonpolynomial.com>

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its contributors
   may be used to endorse or promote products derived from this software
   without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
JNI_UTILS_LICENSE
  } > "$TMP/notices.md"
}

sync_btleplug_java() {
  local root
  for root in $JAVA_ROOTS; do
    rm -rf "${MOD:?}/src@android/$root"; mkdir -p "$MOD/src@android/$root"
    cp -R "$TMP/java/$root/." "$MOD/src@android/$root/"
  done
  cmp -s "$TMP/notices.md" "$MOD/THIRD_PARTY_NOTICES.md" || cp "$TMP/notices.md" "$MOD/THIRD_PARTY_NOTICES.md"
}

diff_btleplug_java() {
  local root drift=0
  for root in $JAVA_ROOTS; do diff -ru "$MOD/src@android/$root" "$TMP/java/$root" || drift=1; done
  diff -u "$MOD/THIRD_PARTY_NOTICES.md" "$TMP/notices.md" || drift=1
  [ $drift = 0 ] || die "the vendored btleplug Java is stale: run scripts/ffi.sh build and commit the result"
}

cmd_build() {
  [ "$(uname)" = Darwin ] || die "build needs macOS: the iOS libraries need Xcode"
  case $WS in *' '*) die "the iOS link def is space-separated; move the checkout to a path without spaces: $WS" ;; esac
  preflight; build_host_cdylib; generate; stage_btleplug_java
  # Everything that depends on the new Rust switches over only after every native build has passed, so a
  # missing NDK leaves the committed bindings and the iOS link def as they were.
  ios; android; host_test_lib
  write_link_def
  sync_generated; sync_btleplug_java
  refresh_apk_libs
}

cmd_bindings() {
  preflight; build_host_cdylib; generate; stage_btleplug_java
  sync_generated; sync_btleplug_java
  # The host library was just rebuilt, and an Android host test loads its copy: leaving it old would fail the
  # checksum after any doc-comment edit. The iOS and Android libraries are what stays stale.
  if [ "$(uname)" = Darwin ]; then host_test_lib; fi
  echo "ffi.sh bindings: the Kotlin and the header are synced. The iOS and Android libraries were not rebuilt: run build before any Kotlin test of a changed export or doc comment."
}

cmd_check() {
  preflight; build_host_cdylib; generate; stage_btleplug_java
  local f drift=0
  for f in $(generated_files); do
    diff -u "$MOD/$f" "$TMP/mirror/$f" || drift=1
  done
  [ $drift = 0 ] || die "committed bindings are stale: run scripts/ffi.sh build and commit the result"
  diff_btleplug_java
  echo "ffi.sh check: committed bindings match rust/src, and the vendored Java matches btleplug"
}

booted_simulators() { xcrun simctl list devices booted | grep -Eo '[0-9A-F]{8}-([0-9A-F]{4}-){3}[0-9A-F]{12}' || true; }

# Shuts down the booted simulators that are not in $1 (one UDID per line), that is the ones booted since $1 was taken.
shutdown_simulators_not_in() {
  local u
  for u in $(booted_simulators); do
    grep -qx "$u" <<<"$1" || { xcrun simctl shutdown "$u" && echo "ffi.sh: shut down leftover simulator $u"; }
  done
}

cmd_test() {
  [ "$(uname)" = Darwin ] || die "test needs macOS: the iOS simulator and libraries need Xcode"
  need xcrun "install Xcode, then: xcode-select --install"
  if xcrun simctl list devices booted | grep -q '(Booted)'; then
    die "a simulator is already booted; shut it down first (the Toolchain boots and shuts down its own)"
  fi
  cmd_build
  (cd "$WS" && CARGO_TARGET_DIR=$WS/target cargo test -p mobile-signer-ffi)
  cd "$APP"
  ./kotlin build -m "$MODULE" -p iosArm64
  ./kotlin test -m "$MODULE" -p android
  local rc=0 before
  before=$(booted_simulators)
  ./kotlin test -m "$MODULE" -p iosSimulatorArm64 || rc=$?
  if [ $rc != 0 ]; then
    # A failing Toolchain test run leaves its simulator booted. A passing one shuts it down (both observed).
    shutdown_simulators_not_in "$before"
    die "iosSimulatorArm64 tests failed"
  fi
  [ -z "$DIRTY" ] || echo "ffi.sh test: the generated bindings differ from git, so green here does not mean the committed ones were fresh. Commit them."
  cat <<EOF
ffi.sh test: all green. What ran:
  at runtime   cargo test -p mobile-signer-ffi
  at runtime   android: generated JNA code against the HOST library on the JVM (not the cross-built .so, not ART)
  at runtime   iosSimulatorArm64: the .a embedded in the cinterop klib, on a simulator
  build only   iosArm64 (no device here)
  build only   the arm64-v8a and x86_64 .so files: built and checked for 16 KB alignment, never loaded
EOF
}

# No checksum covers a function body, so an APK that kept an older .so would run old Rust without any error.
# Only a copy this script made (apk makes the first) is refreshed. Other files in jniLibs are not ours to touch.
refresh_apk_libs() {
  local abi dest
  for abi in $ABIS; do
    dest=$APP/androidApp/jniLibs/$abi/lib$NS.so
    if [ -f "$dest" ]; then cmp -s "$JNILIBS/$abi/lib$NS.so" "$dest" || cp "$JNILIBS/$abi/lib$NS.so" "$dest"; fi
  done
}

cmd_apk() {
  [ "$(uname)" = Darwin ] || die "apk needs macOS"
  local abi
  for abi in $ABIS; do
    [ -f "$JNILIBS/$abi/lib$NS.so" ] || die "$JNILIBS/$abi/lib$NS.so missing: run scripts/ffi.sh build first"
  done
  for abi in $ABIS; do
    mkdir -p "$APP/androidApp/jniLibs/$abi"
    cp "$JNILIBS/$abi/lib$NS.so" "$APP/androidApp/jniLibs/$abi/"
  done
  cat <<EOF
ffi.sh apk: copied $ABIS into app/androidApp/jniLibs (git-ignored). Any androidApp build now packages them.
  Only these ABIs have the library. The Toolchain can warn "Incomplete native libraries" for the others
  (armeabi-v7a, x86, ...). To silence it, list the ABIs in settings.android.abiFilters of androidApp/module.yaml
  yourself; this script never edits that file.
EOF
}

cmd_clean() {
  local abi
  rm -f "$MOD/cinterop@ios/${NS}_link.def"
  rm -rf "${MOD:?}/testResources@android" "${IOS_TARGET_DIR:?}" "${ANDROID_TARGET_DIR:?}" "${JNILIBS:?}"
  for abi in $ABIS; do
    rm -f "$APP/androidApp/jniLibs/$abi/lib$NS.so"
    rmdir "$APP/androidApp/jniLibs/$abi" 2>/dev/null || true
  done
  rmdir "$APP/androidApp/jniLibs" 2>/dev/null || true
  echo "ffi.sh clean: removed the link def, the host library copy, target/ios-$IOS_MIN, target/android-ndk-$NDK_VERSION, target/jniLibs and the jniLibs copies"
}

case ${1:-} in
  build) cmd_build ;;
  bindings) cmd_bindings ;;
  check) cmd_check ;;
  test) cmd_test ;;
  apk) cmd_apk ;;
  clean) cmd_clean ;;
  *) usage ;;
esac
