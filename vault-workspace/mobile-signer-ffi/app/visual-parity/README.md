# Movie Page visual parity

Penpot Movie Page is layout/color source of truth. App ships **Kanit** (OFL);
Penpot boards use FC Vision — do not claim glyph-level pixel-zero.

## Layout

| Path | Role |
|------|------|
| `manifest.json` | 44 routes. 43 Penpot board ids, plus Import Seed `pending-penpot-import-seed`. |
| `baselines/<route>.png` | Cropped Penpot content (402×789), committed |
| `baselines-raw/` | Full 402×874 raster before crop (gitignored ok) |
| `captures/` | Empty except `.gitkeep`. App screenshots land here later. Do not copy baselines in. |
| `scripts/crop.py` | Status-bar + home-indicator crop |
| `scripts/svg_to_baseline.py` | Penpot SVG → cropped baseline |
| `scripts/diff.py` | Exact RGBA compare (any pixel → exit 1) |
| `scripts/check_fonts.py` | Kanit gate |
| `scripts/vendor_fonts.sh` | Copy `Kanit-*.ttf` into `composeResources/font/` |
| `scripts/capture_status.sh` | Run `status.py` and `check_fonts.py` |
| `scripts/diff.sh` | fonts + `--all` diff |

## Crop (locked)

Device board 402×874. Content AABB:

- strip top **59** px (status / Dynamic Island)
- strip bottom **26** px (home indicator)
- result **402×789**

Same crop for baselines and captures. No mid-migration crop tweaks.

## Refresh baselines

1. In Penpot MCP: `generateMarkup([board], {type:'svg'})` for each `manifest.json` boardId.
2. `python3 scripts/svg_to_baseline.py <svg-or-json> --path <route>`
3. Dedicated baseline-refresh PR only — do not overwrite baselines inside a UI PR.

## App capture (Paparazzi / Roborazzi)

Prefer Android JVM screenshot of each screen at **width 402dp**, height = cropped content (789dp), locked density:

1. Kanit already in `design-reference/fonts/` + `shared/composeResources/font/`
2. `Type.kt` uses `Res.font.kanit_*`
3. Add Paparazzi or Roborazzi module rendering each route composable
4. Write PNGs into `captures/<route>.png` (same naming as baselines: `/` → `__`)
5. `./scripts/diff.sh` — expect glyph deltas vs Penpot FC Vision baselines

`verify.sh` emulator recipes stay for live smoke only.

## Font gate

```bash
python3 scripts/check_fonts.py   # exit 0 when Kanit TTFs present
```

## Self-check

```bash
# Diff tool identity (baseline vs copy of itself) must report delta 0
cp baselines/keys__add.png /tmp/keys__add.png
python3 scripts/diff.py baselines/keys__add.png /tmp/keys__add.png
```
