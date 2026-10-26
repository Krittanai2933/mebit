# STACK-READY — Perfect Penpot match (PP-0…LIVE-5)

Branch tip for operator land: `movie-page-vp-0` (based on `movie-page-pr-h` / PR #25).

## Delivered

- [x] **44** content baselines (402×789) under `baselines/` (43 Penpot + Import Seed provisional)
- [x] Import Seed is a first-class `MovieRoute` + catalog row. Baseline is a Key Source copy until Penpot MCP exports `pending-penpot-import-seed`.
- [x] Crop + exact RGBA diff + FC Vision font gate + `vendor_fonts.sh`
- [x] Shared Movie Page chrome (`MebitChrome.kt`)
- [x] LIVE: Hardware `WalletLink` enroll, Pair paste/share (#26), BSMS with key origin, hot wallet Esplora send (`hot_*`), `WalletLink.signPsbt`
- [x] Coverage matrix updated (`.audit/movie-page-coverage.tsv`)

## Blocked for green pixel-zero

- FC Vision OTFs absent from `design-reference/fonts/` — `check_fonts.py` exits 1
- App Paparazzi/Roborazzi captures not yet wired. `captures/` holds `.gitkeep` only. Do not copy baselines into `captures/`.

Matrix: `visual=baselines-ready` (not `pixel-zero`) until fonts + captures + `diff.py --all` are green.

## Operator next

1. Drop FC Vision OTFs into `design-reference/fonts/`. Run `visual-parity/scripts/vendor_fonts.sh`. Switch `Type.kt` to `Res.font.fc_vision_*`.
2. Wire Paparazzi/Roborazzi per `harness/CAPTURE.md` → `captures/`
3. Iterate PP-1…PP-7 boards until `diff.py --all` is all-green
4. Flip matrix to `visual=pixel-zero` and land the stack
5. Penpot: replace Import Seed provisional baseline with a real board export
