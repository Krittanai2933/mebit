# App capture harness (Paparazzi / Roborazzi)

Plan default is an Android JVM render at 402dp by 789dp (cropped content), locked density.
Prefer this over emulator `verify.sh` for the zero-diff gate.

`captures/` contains `.gitkeep` only. Do not copy a file from `baselines/` into `captures/` to make `diff.py` exit 0. That comparison only proves the diff tool. The README self-check that copies `baselines/keys__add.png` is local and must not be committed.

## Status

From `app/visual-parity`:

```bash
./scripts/capture_status.sh
```

The script runs `status.py`, then `check_fonts.py`. Fonts are absent, so the second command exits 1. Pixel-zero stays closed. The script does not write PNGs.

## Amper today

The app is an Amper project. `androidApp/module.yaml` is `product: android/app`. Shared tests run with:

```bash
cd vault-workspace/mobile-signer-ffi/app
./kotlin test -m shared
```

`shared/module.yaml` has no screenshot library. Paparazzi (`app.cash.paparazzi`) and Roborazzi (`io.github.takahirom.roborazzi`) are Gradle plugins. Amper `module.yaml` dependencies are Maven libraries, not Gradle plugins, so neither plugin loads from `androidApp` or `shared`.

## Next steps after the FC Vision OTFs exist

1. Drop `FCVision-Regular.otf`, `FCVision-Medium.otf`, `FCVision-SemiBold.otf`, and `FCVision-Bold.otf` into `design-reference/fonts/`.
2. Run `./scripts/vendor_fonts.sh`. It copies each `FCVision-*.otf` to `shared/composeResources/font/fc_vision_*.otf`.
3. Confirm `./scripts/check_fonts.py` exits 0.
4. In `shared/src/ui/theme/Type.kt`, replace `FontFamily.SansSerif` with `FontFamily` of `Font(Res.font.fc_vision_*)` for the weights Compose generated. Do this only after the OTFs are in `composeResources/font/`. A `Res.font` reference before that fails the build.
5. Add a Gradle module that can apply Paparazzi, because Amper cannot. Place it next to `androidApp`, depend on the shared composables, and set the device to 402 by 789 dp. Render each `MovieRoute` screen under `MebitTheme` with fixture state. Roborazzi is the same shape if you pick that plugin instead.
6. Write each PNG to `captures/<path-with-__>.png` at 402 by 789. The Import Seed file name is `captures/onboarding__import-seed.png`.
7. Run `./scripts/diff.sh`. A nonzero pixel delta fails the route. `baselines/onboarding__import-seed.png` is a copy of Key Source until Penpot exports board `pending-penpot-import-seed`. A green diff on that file is not parity.

Do not draw a status bar, Dynamic Island, or home indicator in captures. Content only, matching the baseline crop.

## Route to screen map

See `../manifest.json` `path` fields and `testTag("screen_<path>")` on each `MebitScreen`.
