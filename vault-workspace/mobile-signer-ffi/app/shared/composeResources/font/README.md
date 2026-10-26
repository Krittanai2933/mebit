# Vendored Kanit for Compose

`visual-parity/scripts/vendor_fonts.sh` copies TTFs from `design-reference/fonts/`:

- `kanit_light.ttf`
- `kanit_regular.ttf`
- `kanit_medium.ttf`
- `kanit_semibold.ttf`
- `kanit_bold.ttf`

`src/ui/theme/Type.kt` loads `Res.font.kanit_*` (OFL — see
`design-reference/fonts/Kanit-OFL.txt`).
