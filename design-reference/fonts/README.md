# FC Vision (Fontcraft)

Official Mapboss/mebit typeface. Required for Movie Page pixel-zero parity.

Drop these files here (operator):

- `FCVision-Light.otf`
- `FCVision-Regular.otf`
- `FCVision-Italic.otf`
- `FCVision-Medium.otf`
- `FCVision-SemiBold.otf`
- `FCVision-Bold.otf`
- `FCVision-ExtraBold.otf`

Then run `vault-workspace/mobile-signer-ffi/app/visual-parity/scripts/vendor_fonts.sh`.
It copies each present file to:

```
vault-workspace/mobile-signer-ffi/app/shared/composeResources/font/
  fc_vision_light.otf
  fc_vision_regular.otf
  fc_vision_medium.otf
  fc_vision_semibold.otf
  fc_vision_bold.otf
```

Wire `Type.kt` to `FontFamily(Font(Res.font.fc_vision_…))`. Until then
`visual-parity/scripts/check_fonts.py` exits 1 and no route may claim
`visual=pixel-zero`.
