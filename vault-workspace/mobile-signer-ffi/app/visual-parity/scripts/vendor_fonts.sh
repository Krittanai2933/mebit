#!/usr/bin/env bash
# Copy the app's fonts from design-reference/fonts into Compose font resources when they exist.
# Exit 0 when the source directory has none. check_fonts.py remains the font gate.
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
VP="$(cd "$DIR/.." && pwd)"

find_repo() {
  local p="$VP"
  while [ "$p" != "/" ]; do
    if [ -d "$p/design-reference" ] && [ -d "$p/vault-workspace" ]; then
      printf '%s\n' "$p"
      return 0
    fi
    p="$(dirname "$p")"
  done
  echo "could not locate repo root (design-reference + vault-workspace)" >&2
  return 1
}

REPO="$(find_repo)"
SRC="${1:-$REPO/design-reference/fonts}"
DEST="${2:-$VP/../shared/composeResources/font}"
mkdir -p "$DEST"

copied=0
for pair in "NotoSansThai-Variable.ttf:noto_sans_thai.ttf" "Montserrat-Bold.ttf:montserrat_bold.ttf"; do
  src="$SRC/${pair%%:*}"
  [ -f "$src" ] || continue
  cp "$src" "$DEST/${pair#*:}"
  echo "vendored ${pair%%:*} -> ${pair#*:}"
  copied=$((copied + 1))
done

if [ "$copied" -eq 0 ]; then
  echo "No app fonts in $SRC."
fi
