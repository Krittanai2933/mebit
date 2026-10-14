#!/usr/bin/env bash
# Offline regression checks for the verify skill. Boots no emulator or simulator and takes a few seconds.
# Fixtures in testdata/ are real captures: a "System UI isn't responding" dump from an emulator, the app's
# first screen, and the launcher in front of the app.
# Exit 0 when every check passes.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
T=$(mktemp -d)
PIDS=""
trap 'for p in $PIDS; do kill -9 "$p" $(pgrep -P "$p") 2> /dev/null; done; rm -rf "${T:?}"' EXIT
fails=0
check() { # check NAME ACTUAL EXPECTED
  if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: got '$2', wanted '$3'"; fails=$((fails + 1)); fi
}
ui() { python3 -I "$HERE/ui.py" "$@"; }
D=$HERE/testdata

# The start gate and doctor look for aerr_ nodes, and the recovery taps aerr_wait.
check "dialog dump has the Close app and Wait buttons" "$(ui count "$D/dialog-system-ui.xml" 'id~:aerr_')" 2
check "Wait button centre" "$(ui find "$D/dialog-system-ui.xml" 'id:aerr_wait')" "540 1331"
check "dialog dump has its title" "$(ui count "$D/dialog-system-ui.xml" "text:System UI isn't responding")" 1
check "first screen has the button" "$(ui count "$D/app-first-screen.xml" 'Click me! na kub')" 1
check "first screen has no dialog" "$(ui count "$D/app-first-screen.xml" 'id~:aerr_')" 0
check "launcher in front has no button" "$(ui count "$D/launcher-in-front.xml" 'Click me! na kub')" 0
check "launcher in front has no dialog" "$(ui count "$D/launcher-in-front.xml" 'id~:aerr_')" 0

# ios-start: the device type must follow the runtime, so VERIFY_IOS_RUNTIME alone gives a pair simctl accepts.
pick() { python3 -I "$HERE/ios_pick.py" "$@" < "$D/simctl-runtimes.json" 2> /dev/null; }
check "ios pick, newest runtime" "$(pick)" "com.apple.CoreSimulator.SimRuntime.iOS-26-5 com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro"
check "ios pick, runtime only" "$(pick com.apple.CoreSimulator.SimRuntime.iOS-18-5)" "com.apple.CoreSimulator.SimRuntime.iOS-18-5 com.apple.CoreSimulator.SimDeviceType.iPhone-16-Pro"
check "ios pick, both" "$(pick com.apple.CoreSimulator.SimRuntime.iOS-26-5 com.apple.CoreSimulator.SimDeviceType.iPhone-17e)" "com.apple.CoreSimulator.SimRuntime.iOS-26-5 com.apple.CoreSimulator.SimDeviceType.iPhone-17e"
pick com.apple.CoreSimulator.SimRuntime.iOS-18-5 com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro > /dev/null; check "ios pick rejects a device type the runtime lacks" "$?" 1
pick com.apple.CoreSimulator.SimRuntime.iOS-27-0 > /dev/null; check "ios pick rejects an unavailable runtime" "$?" 1

# ios-start end to end with a stub xcrun: the pair handed to `simctl create` must be one the runtime supports.
mkdir -p "$T/bin" "$T/iosapp/.claude/skills/verify" "$T/iosapp/build/verify"
ln -s "$HERE" "$T/iosapp/.claude/skills/verify/scripts"
printf '#!/bin/sh\nexit 0\n' > "$T/iosapp/kotlin"; chmod +x "$T/iosapp/kotlin"
cat > "$T/bin/xcrun" << EOS
#!/bin/sh
case "\$*" in
  "simctl list runtimes -j") cat "$D/simctl-runtimes.json" ;;
  "simctl create "*) echo "\$*" > "$T/create-args.txt"; exit 1 ;;
esac
exit 0
EOS
chmod +x "$T/bin/xcrun"
PATH=$T/bin:$PATH VERIFY_IOS_RUNTIME=com.apple.CoreSimulator.SimRuntime.iOS-18-5 \
  "$T/iosapp/.claude/skills/verify/scripts/verify.sh" ios-start > "$T/ios-start.txt" 2>&1
check "ios-start with only the runtime set creates a device the runtime supports" \
  "$(awk '{print $4, $5}' "$T/create-args.txt")" "com.apple.CoreSimulator.SimDeviceType.iPhone-16-Pro com.apple.CoreSimulator.SimRuntime.iOS-18-5"

# stop: an emulator that ignores SIGTERM must keep its state file and make stop fail, so the run is not
# forgotten while its process still holds the port. Stubs stand in for adb, emulator and the toolchain.
APP=$T/app
mkdir -p "$APP/.claude/skills/verify" "$APP/build/verify" "$T/sdk/platform-tools" "$T/sdk/emulator"
ln -s "$HERE" "$APP/.claude/skills/verify/scripts"
printf '#!/bin/sh\nexit 0\n' > "$APP/kotlin"
printf '#!/bin/sh\nexit 0\n' > "$T/sdk/platform-tools/adb"
printf '#!/bin/sh\nexit 0\n' > "$T/sdk/emulator/emulator"
chmod +x "$APP/kotlin" "$T/sdk/platform-tools/adb" "$T/sdk/emulator/emulator"
bash -c 'trap "" TERM; while :; do sleep 1; done' -port 5994 &
STUCK=$!; PIDS="$STUCK"; disown "$STUCK"
mkdir -p "$APP/build/verify/run1"
printf 'RUN=%q\nSERIAL=%q\nPORT=%q\nAVD=%q\nEMU_PID=%q\n' "$APP/build/verify/run1" emulator-5994 5994 stub "$STUCK" > "$APP/build/verify/android.env"
V=$APP/.claude/skills/verify/scripts/verify.sh
ANDROID_HOME=$T/sdk "$V" stop > "$T/stop1.txt" 2>&1; rc=$?
check "stop fails while the emulator ignores SIGTERM" "$rc" 1
check "stop keeps the state file of a live emulator" "$([ -f "$APP/build/verify/android.env" ] && echo kept || echo removed)" kept
check "stop says what to do" "$(grep -c 'kill -9' "$T/stop1.txt")" 1
kill -9 "$STUCK" $(pgrep -P "$STUCK") 2> /dev/null; sleep 1
ANDROID_HOME=$T/sdk "$V" stop > "$T/stop2.txt" 2>&1; rc=$?
check "stop succeeds once the process is gone" "$rc" 0
check "stop removes the state file then" "$([ -f "$APP/build/verify/android.env" ] && echo kept || echo removed)" removed

# A frozen emulator keeps its adb transport listed, and every adb command then blocks. doctor, see and stop
# must answer in seconds, say that the emulator does not answer, and not claim the app is missing.
mkdir -p "$T/sdk-hung/platform-tools" "$T/sdk-hung/emulator"
printf '#!/bin/sh\ncase "$*" in *devices*) exit 0 ;; esac\nexec sleep 20\n' > "$T/sdk-hung/platform-tools/adb"
cp "$T/sdk/emulator/emulator" "$T/sdk-hung/emulator/emulator"
chmod +x "$T/sdk-hung/platform-tools/adb"
# a process that owns the port, as a real emulator does, so stop takes the same path as for a frozen one
python3 -I -c 'import socket, time; s = socket.socket(); s.bind(("127.0.0.1", 5996)); s.listen(); time.sleep(600)' -port 5996 &
HUNG=$!; PIDS="$PIDS $HUNG"; disown "$HUNG"
sleep 1
printf 'RUN=%q\nSERIAL=%q\nPORT=%q\nAVD=%q\nEMU_PID=%q\n' "$APP/build/verify/run2" emulator-5996 5996 stub "$HUNG" > "$APP/build/verify/android.env"
hung() { ANDROID_HOME=$T/sdk-hung VERIFY_ADB_SECONDS=1 "$V" "$@"; }
t0=$SECONDS; hung doctor > "$T/doctor.txt" 2>&1; rc=$?
check "doctor on a hung emulator exits 1" "$rc" 1
check "doctor on a hung emulator answers within 20 s" "$([ $((SECONDS - t0)) -lt 20 ] && echo yes || echo no)" yes
check "doctor says the emulator does not answer adb" "$(grep -c 'does not answer adb' "$T/doctor.txt")" 1
check "doctor does not claim the app is missing" "$(grep -c 'not installed' "$T/doctor.txt")" 0
t0=$SECONDS; hung see "Click me! na kub" 5 > "$T/see.txt" 2>&1; rc=$?
check "see on a hung emulator exits 1" "$rc" 1
check "see on a hung emulator keeps to its budget" "$([ $((SECONDS - t0)) -lt 20 ] && echo yes || echo no)" yes
check "see says the emulator does not answer adb" "$(grep -c 'does not answer adb' "$T/see.txt")" 1
t0=$SECONDS; hung stop > "$T/stop3.txt" 2>&1; rc=$?
check "stop on a hung emulator that obeys SIGTERM exits 0" "$rc" 0
check "stop on a hung emulator is quick" "$([ $((SECONDS - t0)) -lt 20 ] && echo yes || echo no)" yes
check "stop ended that process" "$(kill -0 "$HUNG" 2> /dev/null && echo alive || echo gone)" gone

echo
[ "$fails" -eq 0 ] && echo "selftest: all checks passed" || echo "selftest: $fails check(s) failed"
exit "$fails"
