# Kanit (Cadson Demak) — shipping typeface

Commercial-safe OFL Thai + Latin sans. Replaces FC Vision for the app
because FC Vision’s available cut is non-commercial.

Files (vendored from https://github.com/google/fonts/tree/main/ofl/kanit):

- `Kanit-Light.ttf`
- `Kanit-Regular.ttf`
- `Kanit-Medium.ttf`
- `Kanit-SemiBold.ttf`
- `Kanit-Bold.ttf`
- `Kanit-OFL.txt` — SIL Open Font License

Run `vault-workspace/mobile-signer-ffi/app/visual-parity/scripts/vendor_fonts.sh`
to copy into Compose:

```
vault-workspace/mobile-signer-ffi/app/shared/composeResources/font/
  kanit_light.ttf
  kanit_regular.ttf
  kanit_medium.ttf
  kanit_semibold.ttf
  kanit_bold.ttf
```

`Type.kt` loads `Res.font.kanit_*`. `check_fonts.py` exits 0 when the
files above exist.

Mapboss Penpot Movie Page still uses FC Vision — layout/color parity only;
do not claim glyph-level pixel-zero against those baselines.
