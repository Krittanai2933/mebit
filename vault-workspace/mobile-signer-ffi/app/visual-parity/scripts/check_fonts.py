#!/usr/bin/env python3
"""Kanit font gate. Exit 1 if required TTFs are missing.

Any PR that claims visual=pixel-zero must pass this gate (layout parity only —
Kanit ≠ Penpot FC Vision glyphs).
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = json.loads((ROOT / "manifest.json").read_text())


def repo_root() -> Path:
    here = ROOT
    for p in [here, *here.parents]:
        if (p / "design-reference").is_dir() and (p / "vault-workspace").is_dir():
            return p
    raise SystemExit("could not locate repo root (design-reference + vault-workspace)")


def main() -> int:
    repo = repo_root()
    missing = []
    for rel in MANIFEST["fontGate"]["requiredFiles"]:
        p = repo / rel
        if not p.is_file():
            missing.append(str(p))
    if missing:
        print("Kanit fonts missing — visual font gate failed:")
        for m in missing:
            print(f"  - {m}")
        print("Operator: drop TTFs into design-reference/fonts/ then re-run vendor_fonts.sh.")
        return 1
    print("Kanit fonts present.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
