# App capture harness (Paparazzi / Roborazzi)

Plan default is an Android JVM render at 402dp by 789dp (cropped content), locked density.
Prefer this over emulator `verify.sh` for visual diffs.

`captures/` contains `.gitkeep` only. Do not copy a file from `baselines/` into `captures/` to make `diff.py` exit 0. That comparison only proves the diff tool. The README self-check that copies `baselines/keys__add.png` is local and must not be committed.

## Status

From `app/visual-parity`:

```bash
./scripts/capture_status.sh
```

The script runs `status.py`, then `check_fonts.py`. Kanit is vendored — fonts should exit 0. The script does not write PNGs.

## Amper today

The app is an Amper project. `androidApp/module.yaml` is `product: android/app`. Shared tests run with:

```bash
cd vault-workspace/mobile-signer-ffi/app
./kotlin test -m shared
```

`shared/module.yaml` has no screenshot library. Paparazzi (`app.cash.paparazzi`) and Roborazzi (`io.github.takahirom.roborazzi`) are Gradle plugins. Amper `module.yaml` dependencies are Maven libraries, not Gradle plugins, so neither plugin loads from `androidApp` or `shared`.

## Fonts (done)

1. `Kanit-*.ttf` in `design-reference/fonts/` (+ `Kanit-OFL.txt`).
2. `./scripts/vendor_fonts.sh` → `shared/composeResources/font/kanit_*.ttf`.
3. `./scripts/check_fonts.py` exits 0.
4. `Type.kt` uses `Font(Res.font.kanit_*)`.

## Capture next

1. Add a Gradle module that can apply Paparazzi, because Amper cannot. Place it next to `androidApp`, depend on the shared composables, and set the device to 402 by 789 dp. Render each `MovieRoute` screen under `MebitTheme` with fixture state. Roborazzi is the same shape if you pick that plugin instead.
2. Write each PNG to `captures/<path-with-__>.png` at 402 by 789. The Import Seed file name is `captures/onboarding__import-seed.png`.
3. Run `./scripts/diff.sh`. Glyph deltas vs Penpot (FC Vision) baselines are expected; treat layout/color mismatches as real failures. `baselines/onboarding__import-seed.png` is a copy of Key Source until Penpot exports board `pending-penpot-import-seed`.

Do not draw a status bar, Dynamic Island, or home indicator in captures. Content only, matching the baseline crop.

## Route to screen map

See `../manifest.json` `path` fields and `testTag("screen_<path>")` on each `MebitScreen`.
