#!/usr/bin/env python3
"""Exact RGBA pixel diff. Exit 1 on any differing pixel. No blur, no AA threshold."""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from PIL import Image, ImageChops

ROOT = Path(__file__).resolve().parents[1]


def diff_one(baseline: Path, capture: Path) -> dict:
    a = Image.open(baseline).convert("RGBA")
    b = Image.open(capture).convert("RGBA")
    if a.size != b.size:
        return {
            "ok": False,
            "baseline": str(baseline),
            "capture": str(capture),
            "error": f"size mismatch {a.size} vs {b.size}",
            "delta": None,
        }
    delta = ImageChops.difference(a, b)
    # Any non-zero channel counts.
    extrema = delta.getextrema()
    changed = any(hi > 0 for (_lo, hi) in extrema)
    # Count differing pixels for report.
    px = 0
    if changed:
        raw = delta.tobytes()
        # RGBA tuples
        for i in range(0, len(raw), 4):
            if raw[i] or raw[i + 1] or raw[i + 2] or raw[i + 3]:
                px += 1
    return {
        "ok": not changed,
        "baseline": str(baseline),
        "capture": str(capture),
        "delta": 0 if not changed else px,
        "size": list(a.size),
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--all", action="store_true", help="Diff all baselines vs captures/")
    ap.add_argument("--baseline")
    ap.add_argument("--capture")
    ap.add_argument("--path", help="route path; looks up baselines/<path>.png")
    args = ap.parse_args()
    results = []
    if args.all:
        manifest = json.loads((ROOT / "manifest.json").read_text())
        for route in manifest["routes"]:
            safe = route["path"].replace("/", "__")
            b = ROOT / "baselines" / f"{safe}.png"
            c = ROOT / "captures" / f"{safe}.png"
            if not b.exists():
                results.append({"ok": False, "path": route["path"], "error": "missing baseline"})
                continue
            if not c.exists():
                results.append({"ok": False, "path": route["path"], "error": "missing capture"})
                continue
            r = diff_one(b, c)
            r["path"] = route["path"]
            results.append(r)
    else:
        if args.path:
            safe = args.path.replace("/", "__")
            baseline = ROOT / "baselines" / f"{safe}.png"
            capture = Path(args.capture) if args.capture else ROOT / "captures" / f"{safe}.png"
        else:
            baseline = Path(args.baseline)
            capture = Path(args.capture)
        results.append(diff_one(baseline, capture))

    failed = [r for r in results if not r.get("ok")]
    print(json.dumps({"passed": len(results) - len(failed), "failed": len(failed), "results": results}, indent=2))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
