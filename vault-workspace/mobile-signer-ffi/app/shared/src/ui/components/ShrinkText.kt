package com.mebit.app.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * One line of a big number that gets smaller until it fits the room it was given, so a long figure such as 2,100,000,000 sat never
 * wraps or runs off its card. A figure that already fits stays at [fontSize]. The line height stays, so nothing below it moves.
 */
@Composable
fun ShrinkText(
    text: AnnotatedString,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
) {
    var size by remember(text, fontSize) { mutableStateOf(fontSize) }
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = size,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        lineHeight = lineHeight,
        maxLines = 1,
        softWrap = false,
        onTextLayout = { if (it.didOverflowWidth && size.value > fontSize.value * 0.4f) size = (size.value * 0.92f).sp },
    )
}

@Composable
fun ShrinkText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
) = ShrinkText(AnnotatedString(text), color, fontSize, modifier, fontWeight, fontFamily, lineHeight)
