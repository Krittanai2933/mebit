package com.mebit.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.ui.theme.MbBtcOrange
import com.mebit.app.ui.theme.MbGold
import com.mebit.app.ui.theme.MbNavy
import com.mebit.app.ui.theme.MbWhite

// Penpot Organize > 1.1 Welcome > "Card fan · single → multisig" (402×400). Card-local offsets are dp.
private val CardShape = RoundedCornerShape(26.dp)
private val CardBack = Color(0xFF16486E)
private val CardMid = Color(0xFF215A84)
private val CardDeepOrange = Color(0xFFC2700F)

/** Single-sig card in front of a 2-of-3 and a 3-of-5 vault card. The sample balance is decorative, so it is hidden from screen readers. */
@Composable
fun CardFan(modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(402.dp, 400.dp).testTag("card_fan").clearAndSetSemantics {}) {
        FanCard(x = 114, y = 32, degrees = 12f, fill = SolidColor(CardBack), borderAlpha = 0.12f) {
            CardText("MULTISIG 3-of-5", 22, 22, 10f, FontWeight.Bold, MbWhite.copy(alpha = 0.5f), 1.2f)
            CardText("Vault มรดก", 22, 40, 18f, FontWeight.SemiBold, MbWhite.copy(alpha = 0.85f))
            listOf(204, 220, 236).forEach { KeyDot(it, MbGold) }
            listOf(252, 268).forEach { KeyDot(it, null) }
        }
        FanCard(x = 78, y = 96, degrees = 5f, fill = SolidColor(CardMid), borderAlpha = 0.16f) {
            CardText("MULTISIG 2-of-3", 22, 22, 10f, FontWeight.Bold, MbWhite.copy(alpha = 0.55f), 1.2f)
            CardText("Vault ครอบครัว", 22, 40, 18f, FontWeight.SemiBold, MbWhite.copy(alpha = 0.95f))
            listOf(236, 252).forEach { KeyDot(it, MbBtcOrange) }
            KeyDot(268, null)
        }
        FanCard(
            x = 34,
            y = 172,
            degrees = -5f,
            fill = Brush.linearGradient(0f to MbGold, 0.45f to MbBtcOrange, 1f to CardDeepOrange),
            borderAlpha = 0f,
            shadow = Shadow(
                radius = 48.dp,
                color = Color.Black,
                spread = (-16).dp,
                offset = DpOffset(0.dp, 28.dp),
                alpha = 0.5f,
            ),
        ) {
            CardText("SINGLE-SIG · กุญแจเดียว", 22, 22, 10f, FontWeight.Bold, MbWhite.copy(alpha = 0.8f), 1.2f)
            CardText("กระเป๋าใช้จ่าย", 22, 40, 18f, FontWeight.SemiBold, MbWhite)
            CardText("0.0312", 22, 96, 40f, FontWeight.Bold, MbWhite, -1f)
            CardText("BTC", 155, 119, 15f, FontWeight.Bold, MbWhite.copy(alpha = 0.85f))
            CardText("≈ ฿112,390", 22, 146, 13f, FontWeight.SemiBold, MbNavy.copy(alpha = 0.75f))
            KeyDot(266, MbWhite, y = 24, size = 12)
        }
    }
}

@Composable
private fun FanCard(
    x: Int,
    y: Int,
    degrees: Float,
    fill: Brush,
    borderAlpha: Float,
    shadow: Shadow? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .offset(x.dp, y.dp)
            .size(300.dp, 186.dp)
            .rotate(degrees)
            .then(if (shadow != null) Modifier.dropShadow(CardShape, shadow) else Modifier)
            .clip(CardShape)
            .background(fill)
            .border(1.dp, MbWhite.copy(alpha = borderAlpha), CardShape),
        content = content,
    )
}

// Penpot sets every card text at line-height 1.2.
@Composable
private fun CardText(
    text: String,
    x: Int,
    y: Int,
    size: Float,
    weight: FontWeight,
    color: Color,
    spacing: Float = 0f,
) {
    Text(
        text = text,
        color = color,
        fontSize = size.sp,
        fontWeight = weight,
        lineHeight = (size * 1.2f).sp,
        letterSpacing = spacing.sp,
        modifier = Modifier.offset(x.dp, y.dp),
    )
}

/** A filled key signs; a ring is a backup key. */
@Composable
private fun KeyDot(x: Int, fill: Color?, y: Int = 26, size: Int = 10) {
    val dot = Modifier.offset(x.dp, y.dp).size(size.dp)
    Box(
        if (fill != null) dot.background(fill, CircleShape)
        else dot.border(1.5.dp, MbWhite.copy(alpha = 0.45f), CircleShape),
    )
}
