package com.mebit.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbBorder
import com.mebit.app.ui.theme.MbChip
import com.mebit.app.ui.theme.MbEyebrow
import com.mebit.app.ui.theme.MbWhite

/** A card's ⋯ menu holds one delete item: live, or greyed with the reason it cannot run. */
sealed interface DeleteItem {
    data object Offered : DeleteItem
    data class Blocked(val reason: String) : DeleteItem
}

/** The grey Penpot gives a blocked menu row, which the theme has no token for. */
private val BlockedInk = Color(0xFFB4BCC8)

/**
 * Penpot 2.1.A1 to A3 and 1.8.A1 to A3: the ⋯, a 44 dp target tagged `cta_more_<tagSuffix>` that is its own accessibility element,
 * and the menu that floats under it with the right edges aligned. The menu's one row is [label]: a button that closes the menu and runs
 * [onDelete] while [item] is offered, else greyed with the reason under it and nothing to tap. [onDark] draws the ⋯ for the navy card.
 * [onOpen] runs as the menu opens.
 */
@Composable
fun CardDeleteMenu(tagSuffix: String, label: String, item: DeleteItem, onDark: Boolean, onDelete: () -> Unit, modifier: Modifier = Modifier, onOpen: () -> Unit = {}) {
    var open by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(44.dp)
            .clickable(role = Role.Button) {
                open = true
                onOpen()
            }
            .semantics { contentDescription = "ตัวเลือกเพิ่มเติม" }
            .testTag("cta_more_$tagSuffix"),
        contentAlignment = Alignment.Center,
    ) {
        if (open) Box(Modifier.size(32.dp).background(if (onDark) MbWhite.copy(alpha = 0.14f) else MbChip, CircleShape))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) { Box(Modifier.size(4.dp).background(if (onDark) MbWhite.copy(alpha = 0.72f) else MbBody, CircleShape)) }
        }
        if (open) {
            // Penpot starts the menu 4 dp under the 32 dp circle, which is 2 dp above the bottom of this target.
            val overlap = with(LocalDensity.current) { 2.dp.roundToPx() }
            Popup(popupPositionProvider = remember(overlap) { UnderMore(overlap) }, onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
                DeleteMenu(tagSuffix, label, item) {
                    open = false
                    onDelete()
                }
            }
        }
    }
}

/** The menu's right edge on the target's and its top [overlap] above the target's bottom, or above the target when the window has no room below. */
private class UnderMore(private val overlap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val below = anchorBounds.bottom - overlap
        val y = if (below + popupContentSize.height <= windowSize.height) below else anchorBounds.top + overlap - popupContentSize.height
        return IntOffset((anchorBounds.right - popupContentSize.width).coerceAtLeast(0), y)
    }
}

@Composable
private fun DeleteMenu(tagSuffix: String, label: String, item: DeleteItem, onDelete: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val surface = Modifier.shadow(8.dp, shape).clip(shape).background(MbWhite).border(1.dp, MbBorder, shape)
    when (item) {
        DeleteItem.Offered -> Row(
            modifier = surface.width(196.dp).height(52.dp).clickable(role = Role.Button, onClick = onDelete).testTag("menu_delete_$tagSuffix").padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ItemLabel(label, MbEyebrow)
        }
        is DeleteItem.Blocked -> Column(
            modifier = surface.width(250.dp).semantics(mergeDescendants = true) {}.testTag("menu_blocked_$tagSuffix").padding(start = 16.dp, end = 16.dp, top = 15.dp, bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) { ItemLabel(label, BlockedInk) }
            Spacer(Modifier.height(8.dp))
            Text(item.reason, color = MbBody, fontSize = 12.sp, fontWeight = FontWeight.Light, lineHeight = 16.5.sp)
        }
    }
}

/** The 18 dp trash, then [label] 28 dp from its left edge. */
@Composable
private fun ItemLabel(label: String, ink: Color) {
    TrashIcon(ink)
    Spacer(Modifier.width(10.dp))
    Text(label, color = ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
}

/** Penpot's trash on its 18 dp box, in 1.6 dp strokes with round ends: the lid, its handle, the can and the two lines in it. */
@Composable
private fun TrashIcon(ink: Color) {
    Canvas(Modifier.size(18.dp)) {
        val u = size.width / 18f
        val trash = Path().apply {
            moveTo(3 * u, 4.5f * u)
            lineTo(15 * u, 4.5f * u)
            moveTo(7.5f * u, 4.5f * u)
            lineTo(7.5f * u, 3 * u)
            lineTo(10.5f * u, 3 * u)
            lineTo(10.5f * u, 4.5f * u)
            moveTo(4.5f * u, 4.5f * u)
            lineTo(5.25f * u, 15 * u)
            lineTo(12.75f * u, 15 * u)
            lineTo(13.5f * u, 4.5f * u)
            moveTo(7.5f * u, 7.5f * u)
            lineTo(7.5f * u, 12 * u)
            moveTo(10.5f * u, 7.5f * u)
            lineTo(10.5f * u, 12 * u)
        }
        drawPath(trash, ink, style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
