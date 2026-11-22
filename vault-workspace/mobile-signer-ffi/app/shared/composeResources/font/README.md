# The app's fonts for Compose

`visual-parity/scripts/vendor_fonts.sh` copies them from `design-reference/fonts/`:

- `noto_sans_thai.ttf`: Noto Sans Thai, one variable file for every weight. `src/ui/theme/Type.kt` loads it for the theme.
- `montserrat_bold.ttf`: Montserrat Bold, which `amountFontFamily()` gives the large amounts and balances.

Both are OFL. The licences are in `design-reference/fonts/`.
