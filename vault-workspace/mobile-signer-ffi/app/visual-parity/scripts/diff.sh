#!/usr/bin/env bash
# Exact RGBA diff gate for Movie Page visual parity.
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
VP="$(cd "$DIR/.." && pwd)"
python3 "$DIR/check_fonts.py"
python3 "$DIR/diff.py" --all
