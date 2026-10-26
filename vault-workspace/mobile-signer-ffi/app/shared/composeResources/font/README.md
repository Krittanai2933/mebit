# Vendored FC Vision for Compose

`visual-parity/scripts/vendor_fonts.sh` copies OTFs from `design-reference/fonts/` into this directory when they exist:

- `fc_vision_light.otf`
- `fc_vision_regular.otf`
- `fc_vision_medium.otf`
- `fc_vision_semibold.otf`
- `fc_vision_bold.otf`

Then update `src/ui/theme/Type.kt` to load `Res.font.*`.
