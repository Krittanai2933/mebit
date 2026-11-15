#!/usr/bin/env bash
# Copy Kanit-*.ttf into Compose font resources when those files exist.
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

shopt -s nullglob
copied=0
for src in "$SRC"/Kanit-*.ttf; do
  base="$(basename "$src")"
  # Skip license text if ever named oddly
  case "$base" in
    *OFL*) continue ;;
  esac
  stem="${base#Kanit-}"
  stem="${stem%.ttf}"
  lower="$(printf '%s' "$stem" | tr '[:upper:]' '[:lower:]')"
  cp "$src" "$DEST/kanit_${lower}.ttf"
  echo "vendored $base -> kanit_${lower}.ttf"
  copied=$((copied + 1))
done

if [ "$copied" -eq 0 ]; then
  echo "No Kanit-*.ttf in $SRC."
fi
