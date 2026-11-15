# STACK-READY — Perfect Penpot match (PP-0…LIVE-5)

Branch tip for operator land: `movie-page-vp-0` (based on `movie-page-pr-h` / PR #25).

## Delivered

- [x] **44** content baselines (402×789) under `baselines/` (43 Penpot + Import Seed provisional)
- [x] Import Seed board + baseline (`2067de56-df70-80bd-8008-d9703fd167f8`, cloned from Key Source)
- [x] Crop + exact RGBA diff + **Kanit** font gate + `vendor_fonts.sh`
- [x] Shared Movie Page chrome (`MebitChrome.kt`)
- [x] LIVE: Hardware `WalletLink` enroll, Pair paste/share (#26), BSMS with key origin, hot wallet Esplora send (`hot_*`), `WalletLink.signPsbt`
- [x] Coverage matrix updated (`.audit/movie-page-coverage.tsv`)
- [x] **Kanit** wired in `Type.kt` (`Res.font.kanit_*`) — commercial-safe OFL

## Visual claim

Matrix stays `visual=baselines-ready` (not `pixel-zero` vs Penpot). Penpot boards use FC Vision; the app ships Kanit. Layout/color/chrome match is the target.

## Still open

- App Paparazzi/Roborazzi captures not yet wired. `captures/` holds `.gitkeep` only.
## Operator next

1. Wire Paparazzi/Roborazzi per `harness/CAPTURE.md` → `captures/`
2. Diff for layout/color (accept glyph deltas from Kanit)
3. Land the stack when ready
