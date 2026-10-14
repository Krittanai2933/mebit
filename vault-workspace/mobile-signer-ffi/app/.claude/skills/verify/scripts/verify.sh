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
ADB_WAIT=${VERIFY_ADB_SECONDS:-30}
adbs() { bounded "$ADB_WAIT" "$ADB" -s "$SERIAL" "$@"; }
# adb_answers: false when the emulator does not answer a trivial command in 8 s. A frozen emulator keeps
# its adb transport listed, so every real command then waits out its whole bound.
adb_answers() { bounded $((ADB_WAIT < 8 ? ADB_WAIT : 8)) "$ADB" -s "$SERIAL" shell true > /dev/null 2>&1; }
step() { local IFS=$'\t'; printf '%s\t%s\n' "$(date -u +%FT%TZ)" "$*" >> "$RUN/steps.tsv"; }
load() { [ -f "$1" ] || die "no active run. Start one with: verify.sh $2"; . "$1"; }
# Match on the command line too, so a recycled PID is never mistaken for our emulator.
emu_alive() { ps -p "$EMU_PID" -o command= 2>/dev/null | grep -q -- "-port $PORT"; }
app_resumed() { adbs shell dumpsys activity activities 2> /dev/null | grep -q "ResumedActivity.* $PKG/"; }
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

# uiautomator fails now and then while the UI animates, so retry. Returns 2 when adb itself hangs, because
# another try only waits longer.
dump_ui() {
  local i out rc
  for i in 1 2 3; do
    out=$(adbs shell uiautomator dump /sdcard/verify-ui.xml 2>&1); rc=$?
    [ "$rc" -eq 124 ] && return 2
    if printf '%s' "$out" | grep -q 'dumped to'; then
      adbs exec-out cat /sdcard/verify-ui.xml > "$1" && return 0
    fi
    sleep 1
  done
  return 1
}

# show_screen FILE: what was on screen at the last good dump, or why there is none, and what the system says is in front
show_screen() {
  if [ -s "$1" ]; then ui list "$1" >&2
  else echo "no UI dump could be taken, so the emulator or the app in front is not responding. Run verify.sh doctor" >&2; fi
  adbs shell dumpsys activity activities 2> /dev/null | grep -E 'ResumedActivity|mFocusedApp' | head -2 >&2
}

# wait_ui SELECTOR present|absent SECONDS
wait_ui() {
  local f="$RUN/.poll.xml" n i=0 rc
  rm -f "$f" # a failed dump must not leave an earlier screen behind to be listed as the current one
  while :; do
    dump_ui "$f"; rc=$?
    [ "$rc" -eq 2 ] && { echo "the emulator does not answer adb (hung?). Run verify.sh doctor" >&2; return 1; }
    if [ "$rc" -eq 0 ] && n=$(ui count "$f" "$1" 2>/dev/null); then
      [ "$2" = present ] && [ "$n" -gt 0 ] && return 0
      [ "$2" = absent ] && [ "$n" -eq 0 ] && return 0
    fi
    i=$((i + 1)); [ "$i" -ge "$3" ] && break
    sleep 1
  done
  show_screen "$f"
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
  # -cores: headless runs render in software on the host, and a 1 vCPU guest (the Medium Phone AVD here) starved
  # while it booted. In 15 fresh-clone starts that failed 4 times, with ANRs in 10 of the 15. With 4 vCPUs it was
  # 0 failures and no ANR in 15. The AVD's own setting stays untouched.
  nohup "$EMULATOR" -avd "$AVD" -port "$PORT" -read-only -no-snapshot -no-audio -no-boot-anim -no-metrics -no-window \
    -cores "${VERIFY_CORES:-4}" < /dev/null > "$RUN/emulator.log" 2>&1 &
  EMU_PID=$!
  printf 'RUN=%q\nSERIAL=%q\nPORT=%q\nAVD=%q\nEMU_PID=%q\n' "$RUN" "$SERIAL" "$PORT" "$AVD" "$EMU_PID" > "$STATE"
  echo "emulator $AVD booting as $SERIAL (pid $EMU_PID); building meanwhile"

  # Two starts in the same second can both pass the check above. The serial then leads to the winner's
  # emulator, so make sure the port is ours before touching the device.
  local owner=
  for i in $(seq 1 30); do
    owner=$(lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t 2> /dev/null | head -1)
    [ -n "$owner" ] || ! emu_alive || { sleep 1; continue; }
    break
  done
  [ "$owner" = "$EMU_PID" ] || fail "emulator pid $EMU_PID does not own port $PORT (owner: ${owner:-nobody}). Another start may have won it. Set VERIFY_PORT to another even port"

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
  # The app can be gone or behind the launcher at this point. On a starved guest the launcher finishes starting
  # after the app did, or the framework restarts and takes the app with it. Start it again once, then insist.
  for i in 1 2 3 4 5 6 7 8 9 10; do app_resumed && break; sleep 1; done
  if ! app_resumed; then
    echo "$PKG is not in front, starting it again"
    adbs shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1
    for i in 1 2 3 4 5 6 7 8 9 10; do app_resumed && break; sleep 1; done
    app_resumed || fail "$PKG did not come to the front. Run verify.sh start again. What is in front: verify.sh adb shell dumpsys activity activities"
  fi

  # Ready means the UI answers and no "isn't responding" dialog is up. In a fresh clone the project compiles
  # while the emulator boots, and in 6 of 14 such starts the emulator then did not answer or showed that dialog.
  local ok='' f="$RUN/.poll.xml"
  for i in 1 2 3 4 5; do
    if dump_ui "$f" && [ "$(ui count "$f" 'id~:aerr_' 2> /dev/null)" = 0 ]; then ok=1; break; fi
    sleep 2
  done
  [ -n "$ok" ] || { ui list "$f" >&2 2> /dev/null; fail "the emulator is not responding, or a system dialog covers the app. Run verify.sh start again; the build is warm now"; }

  meta "$RUN/meta.txt" "apk_sha256=$(sha "$(built_apk)")" \
    "device=$(adbs shell getprop ro.product.model | tr -d '\r') api=$(adbs shell getprop ro.build.version.sdk | tr -d '\r') avd=$AVD vcpus=$(adbs shell nproc | tr -d '\r')"
  step start "$SERIAL" "$AVD"
  echo "ready: $SERIAL runs $PKG. Evidence goes to $RUN"
}

cmd_stop() {
  [ -f "$STATE" ] || { echo "no active Android run"; return 0; }
  . "$STATE"
  if emu_alive; then
    # The serial reaches whichever emulator owns the port. Talk to it only when that is ours.
    if [ "$(lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t 2> /dev/null | head -1)" = "$EMU_PID" ] && adb_answers; then
      # An emulator that never booted has no logs, and adb would write "waiting for device" into crash.txt.
      if [ "$(adbs get-state 2> /dev/null)" = device ]; then
        adbs logcat -d -v threadtime '*:W' > "$RUN/logcat-warn.txt" 2>&1 # the full log was 10 MB after a 2 minute run
        adbs logcat -b crash -d -v threadtime > "$RUN/crash.txt" 2>&1
      fi
      adbs emu kill > /dev/null 2>&1
      local i=0; while emu_alive && [ "$i" -lt 20 ]; do sleep 1; i=$((i + 1)); done
    fi
    if emu_alive; then
      kill "$EMU_PID"
      local j=0; while emu_alive && [ "$j" -lt 8 ]; do sleep 1; j=$((j + 1)); done
    fi
    # Keep the state, so the live process is not forgotten and the next start still refuses to run beside it.
    if emu_alive; then
      echo "emulator pid $EMU_PID would not exit. Run: kill -9 $EMU_PID, then verify.sh stop again" >&2
      return 1
    fi
    # adb keeps listing the serial for a moment after the process exits, and a start right after would be refused.
    for i in 1 2 3 4 5 6 7 8 9 10; do "$ADB" devices | grep -q "^${SERIAL}[[:space:]]" || break; sleep 1; done
  fi
  rm -f "$STATE" "$RUN/.poll.xml" "$RUN/.tap.xml"
  [ -s "$RUN/crash.txt" ] && echo "WARNING: a process crashed during this run ($(sed -n 's/.*Process: \([^,]*\),.*/\1/p' "$RUN/crash.txt" | sort -u | tr '\n' ' ')). See $RUN/crash.txt"
  echo "stopped. Evidence kept in $RUN"
}

cmd_doctor() {
  local bad=0 inst d l apk crash
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
      if ! adb_answers; then
        no "$SERIAL does not answer adb within 8 s, so the emulator is hung or still starting. Run verify.sh stop, then verify.sh start"
      else
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
        app_resumed && ok "$PKG is in the foreground" || no "$PKG is not in the foreground (a dialog, the launcher, or a crash)"
        crash=$(adbs logcat -b crash -d 2> /dev/null)
        # The buffer holds every process. The launcher sometimes crashes while the guest boots, so name them.
        if [ -z "$crash" ]; then ok "crash buffer is empty"
        else no "crash buffer has entries from: $(printf '%s\n' "$crash" | sed -n 's/.*Process: \([^,]*\),.*/\1/p' | sort -u | tr '\n' ' ')See: verify.sh adb logcat -b crash -d"; fi
        if dump_ui "$RUN/.poll.xml"; then
          [ "$(ui count "$RUN/.poll.xml" 'id~:aerr_' 2> /dev/null)" = 0 ] && ok "no system dialog on screen" || no "a system 'isn't responding' dialog is on screen. Run verify.sh stop, then verify.sh start"
        else no "uiautomator cannot dump the UI, so the emulator or the app in front is not responding. Run verify.sh stop, then verify.sh start"; fi
      fi
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

# drawer: leave the app and open the launcher's all-apps list. No one gesture works on every image
# (KEYCODE_ALL_APPS fails on API 35 and swipes often fail on 36.1), so try them in turn. The launcher
# drops input while it is still settling, so wait a second first and give the key a second chance.
cmd_drawer() {
  load "$STATE" start
  local sz W H a i rc f="$RUN/.poll.xml" up=
  rm -f "$f"
  adbs shell input keyevent KEYCODE_HOME
  # After a force-stop the launcher can still be showing the all-apps list it launched the app from, and
  # that list has no hotseat. Either one means the launcher is forward.
  for i in 1 2 3 4 5 6 7 8 9 10; do
    dump_ui "$f"; rc=$?
    [ "$rc" -eq 2 ] && die "the emulator does not answer adb (hung?). Run verify.sh doctor"
    if [ "$rc" -eq 0 ]; then
      if [ "$(ui count "$f" 'id~:apps_list_view')" -gt 0 ]; then step drawer already-open ok; echo "all-apps list open (already)"; return 0; fi
      if [ "$(ui count "$f" 'id~:hotseat')" -gt 0 ]; then up=1; break; fi
    fi
    sleep 1
  done
  [ -n "$up" ] || { show_screen "$f"; die "the launcher did not come forward"; }
  sleep 1
  sz=$(adbs shell wm size | tr -d '\r' | sed -n 's/.*: *\([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1); W=${sz% *}; H=${sz#* }
  [ -n "$W" ] && [ -n "$H" ] || die "cannot read the screen size from wm size"
  for a in key key-again swipe-slow swipe-long; do
    case $a in
      key | key-again) adbs shell input keyevent KEYCODE_ALL_APPS ;;
      swipe-slow) adbs shell input swipe $((W / 2)) $((H * 83 / 100)) $((W / 2)) $((H * 30 / 100)) 700 ;;
      swipe-long) adbs shell input swipe $((W / 2)) $((H * 90 / 100)) $((W / 2)) $((H * 10 / 100)) 300 ;;
    esac
    if wait_ui "id~:apps_list_view" present 4 2> /dev/null; then step drawer "$a" ok; echo "all-apps list open (via $a)"; return 0; fi
  done
  step drawer FAIL
  dump_ui "$RUN/.poll.xml"; show_screen "$RUN/.poll.xml"
  die "could not open the all-apps list. Is the launcher one with an apps_list_view node?"
}

# iOS: simctl cannot inject touches and idb/Maestro are not installed, so taps go through the XCUITest bundle in ios-ui/.
cmd_ios_start() {
  if [ -f "$IOS_STATE" ]; then
    . "$IOS_STATE"
    xcrun simctl list devices | grep -q "$UDID" && die "iOS run $IOS_RUN is still active (simulator $UDID). Stop it first: verify.sh ios-stop"
    rm -f "$IOS_STATE"
  fi
  local pick rt dt
  # The device type follows the runtime, so setting only VERIFY_IOS_RUNTIME still gives a pair simctl accepts.
  pick=$(xcrun simctl list runtimes -j | python3 -I "$HERE/ios_pick.py" "${VERIFY_IOS_RUNTIME:-}" "${VERIFY_IOS_DEVICE_TYPE:-}") \
    || die "no usable iOS runtime and device type pair (see above)"
  rt=${pick% *}; dt=${pick#* }
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

# ios_ui ACTION SELECTOR: run one tap, see or gone through ios-ui/Tests/IosUi.swift on the run's simulator.
ios_ui() {
  local act=$1 sel=$2 kind=label contains=0 val=$2 n log
  case $sel in
    text~:* | desc~:*) contains=1; val=${sel#*:} ;;
    text:* | desc:*) val=${sel#*:} ;;
    id~:*) kind=identifier; contains=1; val=${sel#*:} ;;
    id:*) kind=identifier; val=${sel#*:} ;;
  esac
  load "$IOS_STATE" ios-start
  n=$(find "$IOS_RUN" -maxdepth 1 -name 'ui-[0-9][0-9]-*.log' | wc -l)
  log="$IOS_RUN/ui-$(printf %02d $((n + 1)))-$act.log"
  # xcodebuild once sat for 8 minutes after the test had finished (Xcode 27), so read the result from the
  # test log, give the process a few seconds to exit, and then stop it.
  TEST_RUNNER_UI_ACTION=$act TEST_RUNNER_UI_KIND=$kind TEST_RUNNER_UI_CONTAINS=$contains TEST_RUNNER_UI_LABEL=$val \
    xcodebuild test -project "$HERE/ios-ui/IosUi.xcodeproj" -scheme IosUi -destination "platform=iOS Simulator,id=$UDID" \
      -derivedDataPath "$OUT/ios-ui-derived" CODE_SIGNING_ALLOWED=NO > "$log" 2>&1 &
  local pid=$! i
  for ((i = 0; i < 480; i++)); do
    grep -qE "Test Case '.*testAction.*' (passed|failed)" "$log" && break
    kill -0 "$pid" 2> /dev/null || break
    sleep 0.5
  done
  for i in 1 2 3 4 5 6; do kill -0 "$pid" 2> /dev/null || break; sleep 1; done
  kill "$pid" 2> /dev/null; wait "$pid" 2> /dev/null
  if grep -q "Test Case '.*testAction.*' passed" "$log"; then
    printf '%s\tios-%s\t%s\tok\n' "$(date -u +%FT%TZ)" "$act" "$sel" >> "$IOS_RUN/steps.tsv"
    echo "ios-$act ok: $sel"
  else
    printf '%s\tios-%s\t%s\tFAIL\n' "$(date -u +%FT%TZ)" "$act" "$sel" >> "$IOS_RUN/steps.tsv"
    grep -E "error:" "$log" | sed 's/.*error: //' | head -3 | cut -c1-240 >&2
    grep -qE "Test Case '.*testAction.*' failed" "$log" || echo "verify.sh: no test result in the log (xcodebuild failed to build or run it, or timed out after 240 s)" >&2
    echo "verify.sh: ios-$act failed for $sel. Full log: $log" >&2
    exit 1
  fi
}
cmd_ios_tap() { [ $# -eq 1 ] || die "usage: verify.sh ios-tap SELECTOR"; ios_ui tap "$1"; }
cmd_ios_see() { [ $# -eq 1 ] || die "usage: verify.sh ios-see SELECTOR"; ios_ui see "$1"; }
cmd_ios_gone() { [ $# -eq 1 ] || die "usage: verify.sh ios-gone SELECTOR"; ios_ui gone "$1"; }

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
  start | stop | doctor | see | gone | tap | shot | drawer) c=$1; shift; "cmd_$c" "$@" ;;
  ios-start | ios-shot | ios-stop | ios-tap | ios-see | ios-gone) c=${1//-/_}; shift; "cmd_$c" "$@" ;;
  adb) shift; load "$STATE" start; "$ADB" -s "$SERIAL" "$@" ;; # not bounded, so logcat -f and similar can stream
  *) cat << 'EOF'
usage: verify.sh COMMAND [ARGS]
  start                   boot a throwaway emulator, build, install and launch the app
  doctor                  read-only health check of the toolchain and any active run
  see SELECTOR [SECONDS]  wait until SELECTOR is on screen (default 10 s)
  gone SELECTOR [SECONDS] wait until SELECTOR is off screen
  tap SELECTOR [N]        tap the centre of the Nth match
  shot LABEL              save NN-LABEL.png and NN-LABEL.xml (the UI hierarchy) in the run directory
  drawer                  leave the app and open the launcher's all-apps list (tries a key, then swipes)
  adb ARGS...             run adb against the run's emulator
  stop                    save logs, shut the emulator down, keep the evidence
  ios-start | ios-shot LABEL | ios-stop    the same idea on a throwaway iOS simulator
  ios-tap SELECTOR        tap an element on the simulator (XCUITest: about 5 s, and 15 s the first time)
  ios-see SELECTOR        wait for an element on the simulator; ios-gone waits for it to disappear
SELECTOR is [text|desc|id][~]:VALUE, default text; "~" means contains. Example: tap "text~:Click me"
On iOS text and desc both match the accessibility label, and id matches the accessibility identifier.
EOF
    exit 2 ;;
esac
