#!/usr/bin/env bash
# Drives the mebit app on a throwaway Android emulator and launches it on a throwaway iOS simulator.
# What each command does is in SKILL.md ("Launch", "Drive", "Cleanup"). Run it with no arguments for the list.
set -u

HERE=$(cd "$(dirname "$0")" && pwd)
APP=$(cd "$HERE/../../../.." && pwd) # Kotlin project root: vault-workspace/mobile-signer-ffi/app
PKG=com.mebit.app
OUT=$APP/build/verify # inside the gitignored build/; "./kotlin clean" removes build output and may remove this too
STATE=$OUT/android.env
IOS_STATE=$OUT/ios.env
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}
ADB=$SDK/platform-tools/adb;     [ -x "$ADB" ]      || ADB=$(command -v adb)
EMULATOR=$SDK/emulator/emulator; [ -x "$EMULATOR" ] || EMULATOR=$(command -v emulator)

die() { echo "verify.sh: $*" >&2; exit 1; }
[ -x "$APP/kotlin" ] || die "no Kotlin project at $APP; this script must live in <project>/.claude/skills/verify/scripts"

ui() { python3 -I "$HERE/ui.py" "$@"; }
# bounded SECONDS CMD...: give up on CMD after SECONDS (macOS has no timeout(1)). adb hangs on a wedged or dead emulator.
bounded() {
  local limit=$(($1 * 5)) pid i; shift
  "$@" & pid=$!
  for ((i = 0; i < limit; i++)); do
    kill -0 "$pid" 2> /dev/null || { wait "$pid"; return; }
    sleep 0.2
  done
  kill "$pid" 2> /dev/null; wait "$pid" 2> /dev/null
  return 124
}
adbs() { bounded 30 "$ADB" -s "$SERIAL" "$@"; }
step() { local IFS=$'\t'; printf '%s\t%s\n' "$(date -u +%FT%TZ)" "$*" >> "$RUN/steps.tsv"; }
load() { [ -f "$1" ] || die "no active run. Start one with: verify.sh $2"; . "$1"; }
# Match on the command line too, so a recycled PID is never mistaken for our emulator.
emu_alive() { ps -p "$EMU_PID" -o command= 2>/dev/null | grep -q -- "-port $PORT"; }
built_apk() { find "$APP/build/tasks/_androidApp_buildAndroidDebug" -maxdepth 1 -name '*-debug.apk' 2>/dev/null | head -1; }
sha() { shasum -a 256 "$1" | cut -d' ' -f1; }

# meta FILE [LINE...]: record which code a proof drove, so a reader can tell it from the working tree later.
meta() {
  local f=$1 l; shift
  {
    echo "date=$(date -u +%FT%TZ)"
    echo "git=$(git -C "$APP" branch --show-current) $(git -C "$APP" rev-parse --short HEAD) $([ -n "$(git -C "$APP" status --porcelain -- .)" ] && echo dirty || echo clean)"
    echo "toolchain=$(grep -m1 '^kotlin_cli_version=' "$APP/kotlin" | cut -d= -f2)"
    for l in "$@"; do echo "$l"; done
  } > "$f"
}

# uiautomator fails now and then while the UI animates, so retry.
dump_ui() {
  local i
  for i in 1 2 3; do
    if adbs shell uiautomator dump /sdcard/verify-ui.xml 2>&1 | grep -q 'dumped to'; then
      adbs exec-out cat /sdcard/verify-ui.xml > "$1" && return 0
    fi
    sleep 1
  done
  return 1
}

# wait_ui SELECTOR present|absent SECONDS
wait_ui() {
  local f="$RUN/.poll.xml" n i=0
  while :; do
    if dump_ui "$f" && n=$(ui count "$f" "$1" 2>/dev/null); then
      [ "$2" = present ] && [ "$n" -gt 0 ] && return 0
      [ "$2" = absent ] && [ "$n" -eq 0 ] && return 0
    fi
    i=$((i + 1)); [ "$i" -ge "$3" ] && break
    sleep 1
  done
  ui list "$f" >&2
  return 1
}

fail() { echo "verify.sh: $*" >&2; cmd_stop > /dev/null; exit 1; }

cmd_start() {
  if [ -f "$STATE" ]; then
    . "$STATE"
    emu_alive && die "run $RUN is still active (emulator pid $EMU_PID). Stop it first: verify.sh stop"
    echo "dropping state of dead run $RUN"; rm -f "$STATE"
  fi
  [ -n "$ADB" ] && [ -n "$EMULATOR" ] || die "adb or emulator not found; set ANDROID_HOME"
  PORT=${VERIFY_PORT:-5580}; SERIAL=emulator-$PORT
  local st i
  for i in 1 2 3 4 5 6 7 8 9 10; do # adb lists a just-killed emulator as offline for a moment
    st=$("$ADB" devices | awk -v s="$SERIAL" '$1 == s { print $2 }')
    [ "$st" = offline ] || break
    sleep 1
  done
  [ -z "$st" ] || die "$SERIAL is already $st and this skill did not start it. Set VERIFY_PORT to another even port in 5554-5682"
  AVD=${VERIFY_AVD:-$("$EMULATOR" -list-avds | grep -E '^[A-Za-z0-9._-]+$' | head -1)}
  [ -n "$AVD" ] || die "no AVD found. Create one in Android Studio (Device Manager) or set VERIFY_AVD"
  RUN=$OUT/$(date +%Y%m%d-%H%M%S); mkdir -p "$RUN"
  # -read-only gives the run its own throwaway data, so the AVD's saved state stays untouched.
  nohup "$EMULATOR" -avd "$AVD" -port "$PORT" -read-only -no-snapshot -no-audio -no-boot-anim -no-metrics -no-window \
    < /dev/null > "$RUN/emulator.log" 2>&1 &
  EMU_PID=$!
  printf 'RUN=%q\nSERIAL=%q\nPORT=%q\nAVD=%q\nEMU_PID=%q\n' "$RUN" "$SERIAL" "$PORT" "$AVD" "$EMU_PID" > "$STATE"
  echo "emulator $AVD booting as $SERIAL (pid $EMU_PID); building meanwhile"

  ( cd "$APP" && ./kotlin build -m androidApp ) > "$RUN/build.log" 2>&1 \
    || { tail -20 "$RUN/build.log"; fail "build failed, full log: $RUN/build.log"; }

  i=0
  until [ "$(adbs shell getprop sys.boot_completed 2> /dev/null | tr -d '\r')" = 1 ]; do
    emu_alive || { tail -5 "$RUN/emulator.log"; fail "emulator exited while booting, log: $RUN/emulator.log"; }
    i=$((i + 2)); [ "$i" -gt 240 ] && fail "emulator not booted after 240s, log: $RUN/emulator.log"
    sleep 2
  done

  adbs uninstall "$PKG" > /dev/null 2>&1 # empty app data, and no signing-key clash with an older debug build
  ( cd "$APP" && ./kotlin run -m androidApp -d "$SERIAL" ) > "$RUN/run.log" 2>&1 \
    || { tail -20 "$RUN/run.log"; fail "kotlin run failed, full log: $RUN/run.log"; }
  for i in 1 2 3 4 5 6 7 8 9 10; do
    adbs shell dumpsys activity activities | grep -q "ResumedActivity.* $PKG/" && break
    sleep 1
  done

  meta "$RUN/meta.txt" "apk_sha256=$(sha "$(built_apk)")" \
    "device=$(adbs shell getprop ro.product.model | tr -d '\r') api=$(adbs shell getprop ro.build.version.sdk | tr -d '\r') avd=$AVD"
  step start "$SERIAL" "$AVD"
  echo "ready: $SERIAL runs $PKG. Evidence goes to $RUN"
}

cmd_stop() {
  [ -f "$STATE" ] || { echo "no active Android run"; return 0; }
  . "$STATE"
  if emu_alive; then
    adbs logcat -d -v threadtime '*:W' > "$RUN/logcat-warn.txt" 2>&1 # the full log was 10 MB after a 2 minute run
    adbs logcat -b crash -d -v threadtime > "$RUN/crash.txt" 2>&1
    adbs emu kill > /dev/null 2>&1
    local i=0; while emu_alive && [ "$i" -lt 20 ]; do sleep 1; i=$((i + 1)); done
    if emu_alive; then kill "$EMU_PID"; sleep 3; fi
    emu_alive && echo "emulator pid $EMU_PID would not exit. Run: kill -9 $EMU_PID" >&2
  fi
  rm -f "$STATE" "$RUN/.poll.xml" "$RUN/.tap.xml"
  [ -s "$RUN/crash.txt" ] && echo "WARNING: the app crashed during this run. See $RUN/crash.txt"
  echo "stopped. Evidence kept in $RUN"
}

cmd_doctor() {
  local bad=0 inst d l apk
  ok() { echo "ok    $*"; }
  no() { echo "FAIL  $*"; bad=1; }
  command -v java > /dev/null && ok "java: $(java -version 2>&1 | head -1)" || no "java not found"
  command -v python3 > /dev/null && ok "python3 found" || no "python3 not found"
  [ -n "$ADB" ] && ok "adb: $ADB" || no "adb not found; set ANDROID_HOME"
  [ -n "$EMULATOR" ] && ok "emulator: $EMULATOR" || no "emulator not found; set ANDROID_HOME"
  ok "kotlin toolchain pinned at $(grep -m1 '^kotlin_cli_version=' "$APP/kotlin" | cut -d= -f2)"
  [ -n "$EMULATOR" ] && ok "AVDs: $("$EMULATOR" -list-avds | grep -E '^[A-Za-z0-9._-]+$' | tr '\n' ' ')"

  if [ ! -f "$STATE" ]; then
    echo "--    no active Android run (verify.sh start)"
  else
    . "$STATE"
    if ! emu_alive; then
      no "emulator pid $EMU_PID is gone. Run verify.sh stop, then verify.sh start"
    else
      ok "emulator pid $EMU_PID is ours (its command line has -port $PORT)"
      lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t 2> /dev/null | grep -qx "$EMU_PID" && ok "port $PORT is owned by that pid" || no "port $PORT is not owned by pid $EMU_PID"
      [ "$(adbs get-state 2> /dev/null)" = device ] && ok "$SERIAL is online" || no "$SERIAL is not online"
      [ "$(adbs shell getprop sys.boot_completed 2> /dev/null | tr -d '\r')" = 1 ] && ok "boot completed" || no "boot not completed"
      inst=$(adbs shell pm path "$PKG" 2> /dev/null | tr -d '\r' | sed -n 's/^package://p' | head -1)
      apk=$(built_apk)
      if [ -z "$inst" ]; then no "$PKG is not installed"
      elif [ -z "$apk" ]; then no "no built APK under build/tasks/_androidApp_buildAndroidDebug. Run ./kotlin build -m androidApp"
      else
        d=$(adbs shell sha256sum "$inst" | cut -d' ' -f1); l=$(sha "$apk")
        [ "$d" = "$l" ] && ok "installed APK is the one in build/ (sha256 ${d:0:12}...)" || no "installed APK differs from the last build. Run verify.sh stop, then verify.sh start"
      fi
      adbs shell dumpsys activity activities 2> /dev/null | grep -q "ResumedActivity.* $PKG/" && ok "$PKG is in the foreground" || no "$PKG is not in the foreground (a dialog, the launcher, or a crash)"
      [ -z "$(adbs logcat -b crash -d 2> /dev/null)" ] && ok "crash buffer is empty" || no "crash buffer has entries. See: verify.sh adb logcat -b crash -d"
    fi
  fi

  if [ -f "$IOS_STATE" ]; then
    . "$IOS_STATE"
    xcrun simctl list devices | grep "$UDID" | grep -q Booted && ok "iOS simulator $UDID is booted" || no "iOS simulator $UDID is not booted"
    xcrun simctl spawn "$UDID" launchctl list 2> /dev/null | grep -q "UIKitApplication:$PKG" && ok "iOS app $PKG is running" || no "iOS app $PKG is not running"
  else
    echo "--    no active iOS run (verify.sh ios-start)"
  fi
  return $bad
}

cmd_see() {
  [ $# -ge 1 ] || die "usage: verify.sh see SELECTOR [SECONDS]"
  load "$STATE" start
  if wait_ui "$1" present "${2:-10}"; then step see "$1" ok; echo "visible: $1"
  else step see "$1" FAIL; die "not visible after ${2:-10}s: $1"; fi
}

cmd_gone() {
  [ $# -ge 1 ] || die "usage: verify.sh gone SELECTOR [SECONDS]"
  load "$STATE" start
  if wait_ui "$1" absent "${2:-10}"; then step gone "$1" ok; echo "not visible: $1"
  else step gone "$1" FAIL; die "still visible after ${2:-10}s: $1"; fi
}

cmd_tap() {
  [ $# -ge 1 ] || die "usage: verify.sh tap SELECTOR [N]"
  load "$STATE" start
  local f="$RUN/.tap.xml" xy
  dump_ui "$f" || die "uiautomator dump failed; run verify.sh doctor"
  xy=$(ui find "$f" "$1" "${2:-}") || { step tap "$1" FAIL; exit 1; }
  # shellcheck disable=SC2086 # xy is "x y" and must split into two arguments
  adbs shell input tap $xy
  step tap "$1" "$xy"; echo "tapped $1 at $xy"
}

cmd_shot() {
  [ $# -eq 1 ] || die "usage: verify.sh shot LABEL"
  case $1 in *[!A-Za-z0-9._-]*) die "LABEL may only use letters, digits, . _ -" ;; esac
  load "$STATE" start
  local n base
  n=$(find "$RUN" -maxdepth 1 -name '[0-9][0-9]-*.png' | wc -l)
  base="$RUN/$(printf %02d $((n + 1)))-$1"
  adbs exec-out screencap -p > "$base.png" || die "screencap failed"
  dump_ui "$base.xml" || die "uiautomator dump failed"
  step shot "$1" "$base.png"; echo "$base.png"; echo "$base.xml"
}

# iOS: simctl cannot inject touches and idb/Maestro are not installed, so iOS gets launch + screenshot only.
cmd_ios_start() {
  if [ -f "$IOS_STATE" ]; then
    . "$IOS_STATE"
    xcrun simctl list devices | grep -q "$UDID" && die "iOS run $IOS_RUN is still active (simulator $UDID). Stop it first: verify.sh ios-stop"
    rm -f "$IOS_STATE"
  fi
  local pick rt dt
  pick=$(xcrun simctl list runtimes -j | python3 -I -c '
import json, sys
rts = [r for r in json.load(sys.stdin)["runtimes"] if r["isAvailable"] and ".iOS-" in r["identifier"]]
r = max(rts, key=lambda r: [int(x) for x in r["version"].split(".")])
d = next(d for d in r["supportedDeviceTypes"] if d.get("productFamily") == "iPhone")
print(r["identifier"], d["identifier"])')
  rt=${VERIFY_IOS_RUNTIME:-${pick% *}}; dt=${VERIFY_IOS_DEVICE_TYPE:-${pick#* }}
  [ -n "$rt" ] && [ -n "$dt" ] || die "no iOS simulator runtime found; install one in Xcode > Settings > Components"
  IOS_RUN=$OUT/$(date +%Y%m%d-%H%M%S)-ios; mkdir -p "$IOS_RUN"
  UDID=$(xcrun simctl create "mebit-verify-$(date +%H%M%S)" "$dt" "$rt") || die "simctl create failed for $dt on $rt"
  printf 'IOS_RUN=%q\nUDID=%q\n' "$IOS_RUN" "$UDID" > "$IOS_STATE"
  echo "simulator $UDID ($dt, $rt) booting"
  xcrun simctl boot "$UDID" && xcrun simctl bootstatus "$UDID" -b > "$IOS_RUN/boot.log" 2>&1 \
    || ios_fail "boot failed, log: $IOS_RUN/boot.log"
  # -p is required: with -d the toolchain refuses to guess between iosArm64 and iosSimulatorArm64.
  ( cd "$APP" && ./kotlin run -m iosApp -p iosSimulatorArm64 -d "$UDID" ) > "$IOS_RUN/run.log" 2>&1 \
    || { tail -20 "$IOS_RUN/run.log"; ios_fail "kotlin run failed, full log: $IOS_RUN/run.log"; }
  sleep 2 # first frame
  meta "$IOS_RUN/meta.txt" "simulator=$UDID $dt $rt"
  echo "ready: simulator $UDID runs $PKG. Evidence goes to $IOS_RUN"
}

ios_fail() { echo "verify.sh: $*" >&2; cmd_ios_stop > /dev/null; exit 1; }

cmd_ios_shot() {
  [ $# -eq 1 ] || die "usage: verify.sh ios-shot LABEL"
  case $1 in *[!A-Za-z0-9._-]*) die "LABEL may only use letters, digits, . _ -" ;; esac
  load "$IOS_STATE" ios-start
  local n base
  n=$(find "$IOS_RUN" -maxdepth 1 -name '[0-9][0-9]-*.png' | wc -l)
  base="$IOS_RUN/$(printf %02d $((n + 1)))-$1.png"
  xcrun simctl io "$UDID" screenshot "$base" > /dev/null 2>&1 || die "simctl screenshot failed"
  echo "$base"
}

cmd_ios_stop() {
  [ -f "$IOS_STATE" ] || { echo "no active iOS run"; return 0; }
  . "$IOS_STATE"
  xcrun simctl spawn "$UDID" launchctl list 2> /dev/null | grep -q "UIKitApplication:$PKG" \
    || echo "WARNING: $PKG was not running when the iOS run ended; it may have crashed"
  xcrun simctl shutdown "$UDID" > /dev/null 2>&1
  # delete only a device this skill created
  xcrun simctl list devices | grep "$UDID" | grep -q 'mebit-verify-' && xcrun simctl delete "$UDID"
  rm -f "$IOS_STATE"
  echo "stopped. Evidence kept in $IOS_RUN"
}

case ${1:-} in
  start | stop | doctor | see | gone | tap | shot) c=$1; shift; "cmd_$c" "$@" ;;
  ios-start | ios-shot | ios-stop) c=${1//-/_}; shift; "cmd_$c" "$@" ;;
  adb) shift; load "$STATE" start; "$ADB" -s "$SERIAL" "$@" ;; # not bounded, so logcat -f and similar can stream
  *) cat << 'EOF'
usage: verify.sh COMMAND [ARGS]
  start                   boot a throwaway emulator, build, install and launch the app
  doctor                  read-only health check of the toolchain and any active run
  see SELECTOR [SECONDS]  wait until SELECTOR is on screen (default 10 s)
  gone SELECTOR [SECONDS] wait until SELECTOR is off screen
  tap SELECTOR [N]        tap the centre of the Nth match
  shot LABEL              save NN-LABEL.png and NN-LABEL.xml (the UI hierarchy) in the run directory
  adb ARGS...             run adb against the run's emulator
  stop                    save logs, shut the emulator down, keep the evidence
  ios-start | ios-shot LABEL | ios-stop    the same idea on a throwaway iOS simulator (no taps)
SELECTOR is [text|desc|id][~]:VALUE, default text; "~" means contains. Example: tap "text~:Click me"
EOF
    exit 2 ;;
esac
