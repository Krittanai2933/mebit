package com.mebit.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mebit.app.resources.Res
import com.mebit.app.resources.montserrat_bold
import com.mebit.app.resources.noto_sans_thai
import org.jetbrains.compose.resources.Font

/**
 * Noto Sans Thai (OFL), the typeface of eight of Penpot's ten type styles, for Thai and the Latin beside it. One variable file serves
 * every weight: each Font sets the file's weight axis from its own weight.
 */
@Composable
private fun mebitFontFamily(): FontFamily = FontFamily(
    Font(Res.font.noto_sans_thai, FontWeight.Light),
    Font(Res.font.noto_sans_thai, FontWeight.Normal),
    Font(Res.font.noto_sans_thai, FontWeight.Medium),
    Font(Res.font.noto_sans_thai, FontWeight.SemiBold),
    Font(Res.font.noto_sans_thai, FontWeight.Bold),
)

/** Montserrat Bold (OFL), Penpot's Amount and Balance styles: a screen's large number, such as a balance, an amount or a fingerprint. */
@Composable
fun amountFontFamily(): FontFamily = FontFamily(Font(Res.font.montserrat_bold, FontWeight.Bold))

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
