package com.mebit.app

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import platform.CoreImage.CIDetector
import platform.CoreImage.CIDetectorAccuracy
import platform.CoreImage.CIDetectorAccuracyHigh
import platform.CoreImage.CIDetectorTypeQRCode
import platform.CoreImage.CIImage
import platform.CoreImage.CIQRCodeFeature

private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()
private const val ADDRESS = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"

private fun render(matrix: QrMatrix, width: Int, height: Int): ImageBitmap {
    val bitmap = ImageBitmap(width, height)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(width.toFloat(), height.toFloat())) {
        drawQr(matrix, Color.Black, Color.White)
    }
    return bitmap
}

private fun ImageBitmap.pixels() = IntArray(width * height).also { readPixels(it, 0, 0, width, height) }

/** What Core Image's QR detector reads from [png], or null when it finds no code. */
private fun detectQr(png: ByteArray): String? {
    val image = CIImage.imageWithData(png.toNSData()) ?: return null
    val detector = CIDetector.detectorOfType(CIDetectorTypeQRCode, null, mapOf(CIDetectorAccuracy to CIDetectorAccuracyHigh))
    return detector?.featuresInImage(image)?.filterIsInstance<CIQRCodeFeature>()?.firstOrNull()?.messageString
}

class QrIosTest {
    @Test
    fun mebitIsAVersionOneSymbolWithThreeFinderSquares() {
        val matrix = encodeQr("mebit")
        assertEquals(21, matrix.size)
        assertFinderAt(matrix, 0, 0)
        assertFinderAt(matrix, 0, 14)
        assertFinderAt(matrix, 14, 0)
    }

    @Test
    fun everyPixelIsPureDarkOrLightAtAFractionalModuleSize() {
        val matrix = encodeQr(ADDRESS)
        val side = 200
        val pixels = render(matrix, side, side).pixels()
        assertTrue(pixels.all { it == BLACK || it == WHITE }, "a blended pixel would be a seam between modules")
        val module = side.toFloat() / (matrix.size + 8)
        for (row in 0 until matrix.size) {
            for (col in 0 until matrix.size) {
                val x = ((col + 4.5f) * module).toInt()
                val y = ((row + 4.5f) * module).toInt()
                assertEquals(if (matrix.isDark(row, col)) BLACK else WHITE, pixels[y * side + x], "module ($row, $col)")
            }
        }
        assertEquals(WHITE, pixels[0], "the quiet zone is light")
        assertEquals(WHITE, pixels[side * side - 1], "the quiet zone is light")
    }

    @Test
    fun aWideCanvasGetsTheLargestCentredSquare() {
        val matrix = encodeQr("mebit")
        val pixels = render(matrix, 300, 200).pixels()
        assertEquals(0, pixels[0], "left of the square stays transparent")
        assertEquals(0, pixels[199 * 300 + 49], "left of the square stays transparent")
        assertEquals(WHITE, pixels[50], "the square starts at x = 50")
        assertEquals(0, pixels[250], "right of the square stays transparent")
        assertEquals(WHITE, pixels[249], "the square ends at x = 249")
    }

    @Test
    fun appleQrDetectorReadsWhatTheComposableDraws() {
        listOf("mebit", ADDRESS, "bitcoin:$ADDRESS?amount=0.00050000&label=Rent%20March&message=Thanks").forEach { text ->
            val png = render(encodeQr(text), 400, 400).encodePng()
            assertEquals(text, detectQr(png), "text of ${text.length} chars")
        }
    }
}
