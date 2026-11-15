#!/usr/bin/env python3
"""Report visual-parity readiness."""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = json.loads((ROOT / "manifest.json").read_text())


def main() -> int:
    routes = MANIFEST["routes"]
    baselines = []
    missing_b = []
    for r in routes:
        safe = r["path"].replace("/", "__")
        p = ROOT / "baselines" / f"{safe}.png"
        (baselines if p.exists() else missing_b).append(r["path"])
    captures = []
    missing_c = []
    for r in routes:
        safe = r["path"].replace("/", "__")
        p = ROOT / "captures" / f"{safe}.png"
        (captures if p.exists() else missing_c).append(r["path"])
    font = subprocess.run([sys.executable, str(ROOT / "scripts" / "check_fonts.py")])
    print(f"routes:     {len(routes)}")
    print(f"baselines:  {len(baselines)} / {len(routes)}")
    print(f"captures:   {len(captures)} / {len(routes)}")
    print(f"fonts:      {'ok' if font.returncode == 0 else 'MISSING (pixel-zero forbidden)'}")
    if missing_b:
        print("missing baselines:", ", ".join(missing_b))
    pixel_ready = font.returncode == 0 and not missing_b and not missing_c
    print(f"pixel-zero claim allowed: {pixel_ready}")
    return 0 if not missing_b else 1


if __name__ == "__main__":
    sys.exit(main())
