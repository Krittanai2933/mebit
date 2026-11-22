# Noto Sans Thai and Montserrat: the app's typefaces

Penpot Organize's typography uses these two, and the mobile app bundles them. Both are under the SIL Open Font License 1.1.

- `NotoSansThai-Variable.ttf`: Noto Sans Thai from [Google Fonts](https://github.com/google/fonts/tree/main/ofl/notosansthai) (`NotoSansThai[wdth,wght].ttf`). It has Latin as well as Thai, and one variable file covers every weight. Eight of Penpot's ten type styles use it. The notofonts release is Thai only, so it would leave Latin to the system font.
- `Montserrat-Bold.ttf`: Montserrat Bold from [its project](https://github.com/JulietaUla/Montserrat). Penpot's Amount and Balance styles use it for large numbers.
- `NotoSansThai-OFL.txt` and `Montserrat-OFL.txt`: the licences.

Run the [vendor_fonts.sh script](../../vault-workspace/mobile-signer-ffi/app/visual-parity/scripts/vendor_fonts.sh) to copy them into the shared Compose resources:

    vault-workspace/mobile-signer-ffi/app/shared/composeResources/font/
      noto_sans_thai.ttf
      montserrat_bold.ttf

`Type.kt` loads `Res.font.noto_sans_thai` for the theme and `Res.font.montserrat_bold` for `amountFontFamily()`, and check_fonts.py checks that the files exist.
