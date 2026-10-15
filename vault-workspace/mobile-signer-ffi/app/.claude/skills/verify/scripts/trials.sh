#!/usr/bin/env bash
# Re-measure first-start reliability. Each trial is a fresh clone of this checkout, one `verify.sh start`, the
# first-screen recipe, a 30 s settle and `doctor`. A trial that fails once gets one documented recovery (stop,
# then start). The summary keeps initial success and recovery success apart.
#   trials.sh N [AVD]    about 100 s per trial, one emulator at a time
# The clone gets this working tree's skill files, so an uncommitted change is what gets measured.
# Output goes to app/build/verify/trials-<time>/. A passing trial's clone is removed and a failing one is kept
# with its run directories (screenshots, UI dumps, logs).
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(git -C "$HERE" rev-parse --show-toplevel)
REL=${HERE#"$REPO"/}
N=${1:?usage: trials.sh N [AVD]}
[ -n "${2:-}" ] && export VERIFY_AVD=$2
W=$REPO/${REL%/.claude/skills/verify/scripts}/build/verify/trials-$(date +%Y%m%d-%H%M%S)
mkdir -p "$W"
trap '[ -n "${V:-}" ] && "$V" stop > /dev/null 2>&1' EXIT

probe() { # first screen, one tap, the greeting, a 30 s settle, then doctor
  "$1" see "Click me! na kub" 10 > /dev/null 2>&1 && "$1" tap "Click me! na kub" > /dev/null 2>&1 \
    && "$1" see "text~:Hello, Android" 10 > /dev/null 2>&1 && sleep 30 && "$1" doctor > /dev/null 2>&1
}

init=0; rec=0; failed=0
for i in $(seq 1 "$N"); do
  C=$W/t$i
  git clone -q "$REPO" "$C" || exit 1
  cp -R "$HERE/." "$C/$REL/"
  V=$C/$REL/verify.sh
  t0=$SECONDS
  if "$V" start > "$W/start-$i.txt" 2>&1 && probe "$V"; then
    r=ok; init=$((init + 1))
  else
    failed=$((failed + 1)); "$V" stop > "$W/stop-$i.txt" 2>&1
    if "$V" start > "$W/recovery-$i.txt" 2>&1 && probe "$V"; then r="initial FAIL, recovered"; rec=$((rec + 1)); else r="initial FAIL, recovery FAIL"; fi
  fi
  echo "trial $i/$N: $r ($((SECONDS - t0)) s)"
  "$V" stop > /dev/null 2>&1
  case $r in ok) rm -rf "${C:?}" ;; *) echo "  kept $C, start said: $(tail -1 "$W/start-$i.txt" | cut -c1-110)" ;; esac
  V=
  sleep 15
done
echo "initial success $init of $N; recovery success $rec of $failed"
echo "evidence: $W"
