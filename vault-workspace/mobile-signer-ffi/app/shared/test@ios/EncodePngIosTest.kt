package com.mebit.app

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import org.jetbrains.skia.Image
import platform.UIKit.UIImage

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

class EncodePngIosTest {
    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun aBitmapEncodesToAPngThatUIKitReadsBackAtItsSize() {
        val png = ImageBitmap(2, 2).encodePng()
        assertTrue(png.size > 8)
        assertContentEquals(PNG_SIGNATURE, png.copyOf(8))
        val image = assertNotNull(UIImage.imageWithData(png.toNSData()))
        assertEquals(2.0 to 2.0, image.size.useContents { width to height })
    }

    @Test
    fun aSolidColourComesBackFromTheEncodedPng() {
        val bitmap = ImageBitmap(4, 4)
        Canvas(bitmap).drawRect(Rect(0f, 0f, 4f, 4f), Paint().apply { color = Color(0xFFF7931A) })
        val decoded = Image.makeFromEncoded(bitmap.encodePng()).toComposeImageBitmap()
        val pixel = IntArray(1)
        decoded.readPixels(pixel, startX = 0, startY = 0, width = 1, height = 1)
        assertEquals(0xFFF7931A.toInt(), pixel[0])
    }
}
