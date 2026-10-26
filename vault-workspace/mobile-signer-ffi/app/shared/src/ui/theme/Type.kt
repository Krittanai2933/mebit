package com.mebit.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * FC Vision is the Movie Page typeface. [MebitFont] stays [FontFamily.SansSerif]
 * so this module compiles while the OTFs are absent.
 *
 * When composeResources/font/fc_vision_*.otf exists, Compose generates
 * Res.font.fc_vision_*. Switch [MebitFont] to
 * FontFamily(Font(Res.font.fc_vision_regular), Font(Res.font.fc_vision_medium),
 * Font(Res.font.fc_vision_semibold), Font(Res.font.fc_vision_bold)).
 * Do not reference Res.font.fc_vision_* before those files exist.
 * visual-parity/scripts/vendor_fonts.sh copies them from design-reference/fonts/.
 * visual-parity/scripts/check_fonts.py still exits 1 until the source OTFs are present.
 */
private val MebitFont = FontFamily.SansSerif

val MebitTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = MebitFont,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 44.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = MebitFont,
        fontWeight = FontWeight.Medium,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = MebitFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.5.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = MebitFont,
        fontWeight = FontWeight.Light,
        fontSize = 13.5.sp,
        lineHeight = 20.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = MebitFont,
        fontWeight = FontWeight.Light,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = MebitFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.5.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.6.sp,
    ),
)
