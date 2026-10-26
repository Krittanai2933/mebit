# STACK-READY — Movie Page visual parity (VP-0…VP-7)

Branch tip for operator land: `movie-page-vp-0` (based on `movie-page-pr-h` / PR #25).

## Delivered

- [x] 43 Penpot content baselines (402×789) under `baselines/`
- [ ] Import Seed baseline is provisional. `baselines/onboarding__import-seed.png` is a copy of `baselines/onboarding__key-source.png` until Penpot MCP exports the board. `manifest.json` uses `boardId` `pending-penpot-import-seed`. The same copy is on disk at `baselines-raw/onboarding__import-seed.png`. `baselines-raw/` stays gitignored. A zero diff against this file is not parity.
- [x] Crop + exact RGBA diff + FC Vision font gate
- [x] Shared Movie Page chrome (`MebitChrome.kt` option cards, back chip, dark welcome, navy CTA)
- [x] All Movie Page screens rebuilt onto shared chrome (onboarding → keys → vault → S* → M*)
- [x] Type scale aligned to Penpot measures; FC Vision wire path documented

## Blocked for green pixel-zero

- FC Vision OTFs absent from `design-reference/fonts/` — `check_fonts.py` exits 1
- App Paparazzi/Roborazzi captures not yet wired (waiting on fonts for meaningful text match). `captures/` holds `.gitkeep` only. Do not copy baselines into `captures/` to make `diff.py` pass. `scripts/capture_status.sh` reports status and does not write PNGs.

Until fonts + captures land and `diff.py --all` reports 0 for every route, matrix stays:

`visual=baselines-ready` (not `pixel-zero`)

## Operator next

1. Drop FC Vision OTFs into `design-reference/fonts/`. Run `visual-parity/scripts/vendor_fonts.sh`. Then switch `Type.kt` off SansSerif. `check_fonts.py` stays the gate and still exits 1 while the OTFs are absent.
2. Add JVM screenshot captures → `captures/`
3. Iterate per-board until `diff.py --all` is all-green
4. Flip matrix to `visual=pixel-zero` and merge stack
