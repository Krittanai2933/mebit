package com.mebit.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mebit.app.resources.Res
import com.mebit.app.resources.kanit_bold
import com.mebit.app.resources.kanit_light
import com.mebit.app.resources.kanit_medium
import com.mebit.app.resources.kanit_regular
import com.mebit.app.resources.kanit_semibold
import org.jetbrains.compose.resources.Font

/**
 * Kanit (OFL) — shipping typeface (Thai + Latin). Penpot still uses FC Vision;
 * layout/color parity only, not glyph pixel-zero.
 */
@Composable
private fun mebitFontFamily(): FontFamily = FontFamily(
    Font(Res.font.kanit_light, FontWeight.Light),
    Font(Res.font.kanit_regular, FontWeight.Normal),
    Font(Res.font.kanit_medium, FontWeight.Medium),
    Font(Res.font.kanit_semibold, FontWeight.SemiBold),
    Font(Res.font.kanit_bold, FontWeight.Bold),
)

@Composable
fun mebitTypography(): Typography {
    val font = mebitFontFamily()
    return remember(font) {
        Typography(
            displayLarge = TextStyle(
                fontFamily = font,
                fontWeight = FontWeight.Bold,
                fontSize = 40.sp,
                lineHeight = 44.sp,
            ),
            headlineLarge = TextStyle(
                fontFamily = font,
                fontWeight = FontWeight.Medium,
                fontSize = 28.sp,
                lineHeight = 34.sp,
            ),
            titleMedium = TextStyle(
                fontFamily = font,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.5.sp,
                lineHeight = 22.sp,
            ),
            bodyLarge = TextStyle(
                fontFamily = font,
                fontWeight = FontWeight.Light,
                fontSize = 13.5.sp,
                lineHeight = 20.sp,
            ),
            bodyMedium = TextStyle(
                fontFamily = font,
                fontWeight = FontWeight.Light,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            ),
            labelLarge = TextStyle(
                fontFamily = font,
                fontWeight = FontWeight.SemiBold,
                fontSize = 10.5.sp,
                lineHeight = 14.sp,
                letterSpacing = 0.6.sp,
            ),
        )
    }
}
