#!/usr/bin/env python3
"""Crop status-bar + home-indicator bands from a 402×874 board PNG.

Content AABB matches manifest.json crop (top=59, bottom=26) → 402×789.
Same crop MUST be applied to Penpot baselines and app captures.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = json.loads((ROOT / "manifest.json").read_text())
TOP = int(MANIFEST["crop"]["top"])
BOTTOM = int(MANIFEST["crop"]["bottom"])
WIDTH = int(MANIFEST["device"]["width"])
HEIGHT = int(MANIFEST["device"]["height"])


def crop_content(im: Image.Image) -> Image.Image:
    if im.size != (WIDTH, HEIGHT):
        # Allow slight export padding: center-crop / pad to device size first.
        canvas = Image.new("RGBA", (WIDTH, HEIGHT), (246, 247, 249, 255))
        src = im.convert("RGBA")
        # If wider (shadow bleed), take left WIDTH of content; if taller, top HEIGHT.
        box = src.crop((0, 0, min(src.width, WIDTH), min(src.height, HEIGHT)))
        canvas.paste(box, (0, 0))
        im = canvas
    y1 = TOP
    y2 = HEIGHT - BOTTOM
    return im.crop((0, y1, WIDTH, y2))


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("src")
    ap.add_argument("dst")
    args = ap.parse_args()
    src = Path(args.src)
    dst = Path(args.dst)
    dst.parent.mkdir(parents=True, exist_ok=True)
    out = crop_content(Image.open(src))
    out.save(dst)
    print(f"{src} -> {dst} ({out.size[0]}x{out.size[1]})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
