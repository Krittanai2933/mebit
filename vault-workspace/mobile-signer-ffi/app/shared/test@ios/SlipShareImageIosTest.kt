package com.mebit.app.feature.send

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.mebit.app.encodePng
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.skia.Image

private val CARD = Color(0xFF123456)

class SlipShareImageIosTest {
    /** The share picture of a 40 x 20 card whose content is [card], drawn into a bitmap and read back through the PNG encoder the share sheet uses. */
    private fun picture(card: DrawScope.() -> Unit): ImageBitmap {
        val size = slipShareSize(IntSize(40, 20))
        val bitmap = ImageBitmap(size.width, size.height)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(size.width.toFloat(), size.height.toFloat())) {
            drawSlipShare(card)
        }
        return Image.makeFromEncoded(bitmap.encodePng()).toComposeImageBitmap()
    }

    private fun ImageBitmap.pixel(x: Int, y: Int): Int = IntArray(1).also { readPixels(it, x, y, 1, 1) }[0]

    @Test
    fun theMarginIs32PixelsOnEverySide() {
        assertEquals(IntSize(104, 84), slipShareSize(IntSize(40, 20)))
        val shared = picture { drawRect(CARD, size = Size(40f, 20f)) }
        assertEquals(IntSize(104, 84), IntSize(shared.width, shared.height))
    }

    @Test
    fun theCardSitsCentredOnThePageColour() {
        val shared = picture { drawRect(CARD, size = Size(40f, 20f)) }
        val page = SlipPage.toArgb()
        assertEquals(page, shared.pixel(0, 0), "top left corner")
        assertEquals(page, shared.pixel(103, 83), "bottom right corner")
        assertEquals(page, shared.pixel(31, 31), "just above and left of the card")
        assertEquals(CARD.toArgb(), shared.pixel(32, 32), "the card's first pixel")
        assertEquals(CARD.toArgb(), shared.pixel(52, 42), "the centre")
        assertEquals(CARD.toArgb(), shared.pixel(71, 51), "the card's last pixel")
        assertEquals(page, shared.pixel(72, 52), "just below and right of the card")
    }

    @Test
    fun aCornerTheCardLeavesUndrawnShowsThePageAndNotTransparency() {
        val shared = picture {
            drawRect(CARD, topLeft = Offset(4f, 0f), size = Size(36f, 20f))
            drawRect(CARD, topLeft = Offset(0f, 4f), size = Size(4f, 16f))
        }
        assertEquals(SlipPage.toArgb(), shared.pixel(32, 32), "the card's own top left corner, opaque")
        assertEquals(CARD.toArgb(), shared.pixel(36, 36), "inside the card")
    }
}
