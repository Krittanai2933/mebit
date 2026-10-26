#!/usr/bin/env bash
# Print route, baseline, capture, and font status. Exit 1 if either check fails.
set -uo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
status=0
python3 "$DIR/status.py" || status=$?
python3 "$DIR/check_fonts.py" || status=$?
exit "$status"
