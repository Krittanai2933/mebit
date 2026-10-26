#!/usr/bin/env python3
"""FC Vision font gate. Exit 1 if required OTFs are missing.

Any PR that claims visual=pixel-zero must pass this gate.
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
        print("FC Vision fonts missing — pixel-zero claims forbidden:")
        for m in missing:
            print(f"  - {m}")
        print("Operator: drop OTFs into design-reference/fonts/ then re-run.")
        return 1
    print("FC Vision fonts present.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
