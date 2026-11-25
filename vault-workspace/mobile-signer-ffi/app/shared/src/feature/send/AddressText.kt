package com.mebit.app.feature.send

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import com.mebit.app.ui.theme.amountFontFamily

/**
 * An address in Montserrat Bold, as many groups of four a line as the width it is given holds and the lines shared evenly
 * ([addressLines]), its ends in [accent] ([addressAccents]): a 62-character address reads in two lines on a 393 pt iPhone and in three on
 * a narrower one.
 */
@Composable
internal fun AddressText(address: String, ink: Color, accent: Color, fontSize: TextUnit, lineHeight: TextUnit, modifier: Modifier = Modifier) {
    val style = TextStyle(fontFamily = amountFontFamily(), fontWeight = FontWeight.Bold, fontSize = fontSize, lineHeight = lineHeight)
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth
        val most = remember(address, width, style) {
            val groups = (address.length + 3) / 4
            (groups downTo 1).firstOrNull { m ->
                addressLines(address, m).lines().all { measurer.measure(it, style, softWrap = false).size.width <= width }
            } ?: 1
        }
        Text(accentedAddress(address, ink, accent, most), style = style)
    }
}
