# Movie Page visual parity

Penpot Movie Page is layout/color source of truth. App ships **Noto Sans Thai** and **Montserrat Bold** (OFL),
the typefaces of Penpot's typography, so glyphs can match as well.

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
| `scripts/check_fonts.py` | App font gate |
| `scripts/vendor_fonts.sh` | Copy the app's fonts into `composeResources/font/` |
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

1. Noto Sans Thai and Montserrat Bold already in `design-reference/fonts/` + `shared/composeResources/font/`
2. `Type.kt` uses `Res.font.noto_sans_thai` and `Res.font.montserrat_bold`
3. Add Paparazzi or Roborazzi module rendering each route composable
4. Write PNGs into `captures/<route>.png` (same naming as baselines: `/` → `__`)
5. `./scripts/diff.sh` — baselines drawn before 2026-11-20 used older Penpot typefaces, so expect glyph deltas there

`verify.sh` emulator recipes stay for live smoke only.

## Font gate

```bash
python3 scripts/check_fonts.py   # exit 0 when the app's TTFs are present
```

## Self-check

```bash
# Diff tool identity (baseline vs copy of itself) must report delta 0
cp baselines/keys__add.png /tmp/keys__add.png
python3 scripts/diff.py baselines/keys__add.png /tmp/keys__add.png
```
