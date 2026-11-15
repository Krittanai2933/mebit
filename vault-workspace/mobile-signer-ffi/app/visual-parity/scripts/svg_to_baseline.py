#!/usr/bin/env python3
"""Convert a Penpot board SVG (or agent-tools JSON dump) into a cropped baseline PNG."""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = json.loads((ROOT / "manifest.json").read_text())
TOP = int(MANIFEST["crop"]["top"])
BOTTOM = int(MANIFEST["crop"]["bottom"])
WIDTH = int(MANIFEST["device"]["width"])
HEIGHT = int(MANIFEST["device"]["height"])


def extract_svg(raw: str) -> str:
    raw = raw.strip()
    if raw.startswith("<svg") or raw.startswith("<?xml"):
        return raw
    data = json.loads(raw)
    while isinstance(data, dict) and "result" in data:
        data = data["result"]
    if isinstance(data, dict) and "data" in data:
        data = data["data"]
    if not isinstance(data, str) or "<svg" not in data:
        raise SystemExit("no SVG found in input")
    return data


def force_viewbox(svg: str, board_w: int = WIDTH, board_h: int = HEIGHT) -> str:
    """Normalize export to exact board size (Penpot may include shadow bleed)."""
    m = re.search(r'viewBox="([^"]+)"', svg)
    if not m:
        return svg
    parts = [float(x) for x in m.group(1).split()]
    if len(parts) != 4:
        return svg
    x, y, _w, _h = parts
    # Keep origin; force 402×874 content window from board top-left.
    new_vb = f"{x} {y} {board_w} {board_h}"
    svg = re.sub(r'viewBox="[^"]+"', f'viewBox="{new_vb}"', svg, count=1)
    svg = re.sub(r'\bwidth="[^"]+"', f'width="{board_w}"', svg, count=1)
    svg = re.sub(r'\bheight="[^"]+"', f'height="{board_h}"', svg, count=1)
    return svg


def rasterize(svg: str, dst_raw: Path) -> None:
    with tempfile.NamedTemporaryFile("w", suffix=".svg", delete=False) as f:
        f.write(svg)
        svg_path = Path(f.name)
    try:
        subprocess.check_call(
            [
                "rsvg-convert",
                "-w",
                str(WIDTH),
                "-h",
                str(HEIGHT),
                "-o",
                str(dst_raw),
                str(svg_path),
            ]
        )
    finally:
        svg_path.unlink(missing_ok=True)


def crop_content(src: Path, dst: Path) -> None:
    im = Image.open(src).convert("RGBA")
    if im.size != (WIDTH, HEIGHT):
        canvas = Image.new("RGBA", (WIDTH, HEIGHT), (246, 247, 249, 255))
        canvas.paste(im.crop((0, 0, min(im.width, WIDTH), min(im.height, HEIGHT))), (0, 0))
        im = canvas
    out = im.crop((0, TOP, WIDTH, HEIGHT - BOTTOM))
    dst.parent.mkdir(parents=True, exist_ok=True)
    out.save(dst)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("input", help="SVG file or agent-tools JSON containing SVG")
    ap.add_argument("--path", required=True, help="route path e.g. keys/add")
    args = ap.parse_args()
    raw = Path(args.input).read_text()
    svg = force_viewbox(extract_svg(raw))
    raw_dir = ROOT / "baselines-raw"
    raw_dir.mkdir(parents=True, exist_ok=True)
    safe = args.path.replace("/", "__")
    raw_png = raw_dir / f"{safe}.png"
    rasterize(svg, raw_png)
    out = ROOT / "baselines" / f"{safe}.png"
    crop_content(raw_png, out)
    print(f"baseline {args.path} -> {out} ({Image.open(out).size})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
