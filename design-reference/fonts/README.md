# Kanit (Cadson Demak) — shipping app typeface

Commercial-safe OFL Thai + Latin sans used by the mobile app. Penpot's current Organize design uses Anuphan as its design typeface; Anuphan is not bundled in this repository, so visual references should not claim glyph-level parity with Kanit.

Files are vendored from the [Google Fonts Kanit project](https://github.com/google/fonts/tree/main/ofl/kanit):
- Kanit-Light.ttf
- Kanit-Regular.ttf
- Kanit-Medium.ttf
- Kanit-SemiBold.ttf
- Kanit-Bold.ttf
- Kanit-OFL.txt — SIL Open Font License

Run the [vendor_fonts.sh script](../../vault-workspace/mobile-signer-ffi/app/visual-parity/scripts/vendor_fonts.sh) to copy the bundled files into shared Compose resources:

    vault-workspace/mobile-signer-ffi/app/shared/composeResources/font/
      kanit_light.ttf
      kanit_regular.ttf
      kanit_medium.ttf
      kanit_semibold.ttf
      kanit_bold.ttf

Type.kt loads the Res.font.kanit_* resources, and check_fonts.py checks that the files exist. Keep Penpot's Anuphan typography documented separately from the app's bundled Kanit assets.
