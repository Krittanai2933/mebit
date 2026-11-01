package com.mebit.app

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.mebit.app.ui.theme.MbNavyInk
import com.mebit.app.ui.theme.MbWhite
import kotlin.math.abs
import kotlin.math.roundToInt
import qrcode.raw.ErrorCorrectionLevel
import qrcode.raw.MaskPattern
import qrcode.raw.QRCodeDataType
import qrcode.raw.QRCodeProcessor

/** The largest version [encodeQr] makes. Version 10 holds 213 bytes at error correction level M. */
private const val MAX_VERSION = 10

private const val QUIET_ZONE_MODULES = 4

/** A QR symbol of [size] x [size] modules, without the quiet zone. [modules] holds the rows one after the other, true for a dark module. */
class QrMatrix(val size: Int, private val modules: BooleanArray) {
    init {
        require(size > 0) { "A QR matrix needs a positive size, got $size" }
        require(modules.size == size * size) { "A $size x $size QR matrix needs ${size * size} modules, got ${modules.size}" }
    }

    /** Whether the module at [row] and [col] is dark. Throws [IndexOutOfBoundsException] outside the symbol. */
    fun isDark(row: Int, col: Int): Boolean {
        if (row !in 0 until size || col !in 0 until size) {
            throw IndexOutOfBoundsException("($row, $col) is outside a $size x $size QR matrix")
        }
        return modules[row * size + col]
    }
}

/**
 * [text] as a QR symbol: byte mode on its UTF-8 bytes, error correction level M, the smallest version that fits,
 * and the mask the standard's penalty rules score lowest. Throws [IllegalArgumentException] when the text needs
 * more than version 10 (213 bytes) or holds a NUL character.
 */
fun encodeQr(text: String): QrMatrix {
    // shortcut: no NUL characters, the library's Reed-Solomon step never ends on a block of only zero bytes, upgrade when it is fixed.
    require('\u0000' !in text) { "The text holds a NUL character, which the QR encoder cannot take" }
    val version = QRCodeProcessor.infoDensityForDataAndECL(text, ErrorCorrectionLevel.MEDIUM, QRCodeDataType.DEFAULT)
    require(version <= MAX_VERSION) {
        "The text takes ${text.encodeToByteArray().size} bytes, more than a version $MAX_VERSION QR code holds at level M"
    }
    val processor = QRCodeProcessor(text, ErrorCorrectionLevel.MEDIUM, QRCodeDataType.DEFAULT)
    val size = 17 + 4 * version
    // The library only draws the mask it is given (mask 0 by default), so the choice is made here.
    val candidates = MaskPattern.entries.map { mask ->
        val raw = processor.encode(version, mask)
        BooleanArray(size * size) { raw[it / size][it % size].dark }
    }
    return QrMatrix(size, candidates.minBy { penalty(it, size) })
}

private val finderRatio = booleanArrayOf(true, false, true, true, true, false, true)

/**
 * The standard's four mask penalty rules over [modules], an [n] x [n] symbol: N1 = 3 for a run of five or more
 * (plus one per extra module), N2 = 3 per 2 x 2 block, N3 = 40 per 1:1:3:1:1 pattern next to four light modules,
 * N4 = 10 per 5% the dark share is off 50%. Rule 3 counts a pattern once even when light modules border it on
 * both sides, as ZXing's encoder does.
 */
private fun penalty(modules: BooleanArray, n: Int): Int {
    var score = 0
    for (vertical in booleanArrayOf(false, true)) {
        for (line in 0 until n) {
            fun dark(i: Int) = if (vertical) modules[i * n + line] else modules[line * n + i]
            fun light(from: Int, to: Int) = from >= 0 && to <= n && (from until to).none { dark(it) }

            var run = 1
            for (i in 1..n) {
                if (i < n && dark(i) == dark(i - 1)) {
                    run++
                } else {
                    if (run >= 5) score += 3 + run - 5
                    run = 1
                }
            }
            for (i in 0..n - finderRatio.size) {
                val matches = finderRatio.indices.all { dark(i + it) == finderRatio[it] }
                if (matches && (light(i - 4, i) || light(i + finderRatio.size, i + finderRatio.size + 4))) score += 40
            }
        }
    }
    for (row in 0 until n - 1) {
        for (col in 0 until n - 1) {
            val module = modules[row * n + col]
            if (module == modules[row * n + col + 1] && module == modules[(row + 1) * n + col] && module == modules[(row + 1) * n + col + 1]) {
                score += 3
            }
        }
    }
    return score + abs(modules.count { it } * 2 - n * n) * 10 / (n * n) * 10
}

/** Draws [matrix] in the largest centred square of this scope, [dark] modules on [light], with a quiet zone of four modules. */
internal fun DrawScope.drawQr(matrix: QrMatrix, dark: Color, light: Color) {
    val side = minOf(size.width, size.height)
    val left = ((size.width - side) / 2).roundToInt().toFloat()
    val top = ((size.height - side) / 2).roundToInt().toFloat()
    val module = side / (matrix.size + 2 * QUIET_ZONE_MODULES)
    // Both neighbours of a shared edge round the same number, so no gap or overlap shows between modules.
    fun edge(origin: Float, index: Int) = (origin + (index + QUIET_ZONE_MODULES) * module).roundToInt().toFloat()

    drawRect(light, Offset(left, top), Size(side, side))
    for (row in 0 until matrix.size) {
        var col = 0
        while (col < matrix.size) {
            if (!matrix.isDark(row, col)) {
                col++
                continue
            }
            val start = col
            while (col < matrix.size && matrix.isDark(row, col)) col++
            val x = edge(left, start)
            val y = edge(top, row)
            drawRect(dark, Offset(x, y), Size(edge(left, col) - x, edge(top, row + 1) - y))
        }
    }
}

/**
 * [text] as a QR code in the largest centred square of its space. Draws nothing when [encodeQr] throws, so the
 * caller decides what to show instead. Most scanners need [dark] to be darker than [light].
 */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier, dark: Color = MbNavyInk, light: Color = MbWhite) {
    val matrix = remember(text) {
        try {
            encodeQr(text)
        } catch (_: Exception) {
            null
        }
    } ?: return
    Canvas(modifier) { drawQr(matrix, dark, light) }
}
