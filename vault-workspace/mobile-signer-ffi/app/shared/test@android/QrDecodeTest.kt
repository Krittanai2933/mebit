package com.mebit.app

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val QUIET_ZONE = 4
private const val PIXELS_PER_MODULE = 4
private const val BECH32 = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

// Bytes a symbol holds in byte mode at level M, versions 1 to 10, typed from the QR standard's capacity table.
private val capacityAtM = intArrayOf(14, 26, 42, 62, 84, 106, 122, 152, 180, 213)

private fun versionFor(text: String): Int = capacityAtM.indexOfFirst { text.encodeToByteArray().size <= it } + 1

// PURE_BARCODE skips ZXing's finder search, which mistakes data for a finder pattern in about 1 valid symbol in 100.
// ZXing's own encoder's output fails the same way, and the modules are compared with that encoder below.
private fun scan(matrix: QrMatrix): Result {
    val side = (matrix.size + 2 * QUIET_ZONE) * PIXELS_PER_MODULE
    val pixels = IntArray(side * side) { 0xFFFFFFFF.toInt() }
    for (row in 0 until matrix.size) {
        for (col in 0 until matrix.size) {
            if (!matrix.isDark(row, col)) continue
            for (dy in 0 until PIXELS_PER_MODULE) {
                for (dx in 0 until PIXELS_PER_MODULE) {
                    pixels[((row + QUIET_ZONE) * PIXELS_PER_MODULE + dy) * side + (col + QUIET_ZONE) * PIXELS_PER_MODULE + dx] =
                        0xFF000000.toInt()
                }
            }
        }
    }
    val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
    return QRCodeReader().decode(bitmap, mapOf(DecodeHintType.CHARACTER_SET to "UTF-8", DecodeHintType.PURE_BARCODE to true))
}

private fun assertScansBackAsLevelM(text: String): QrMatrix {
    val matrix = encodeQr(text)
    val result = scan(matrix)
    assertEquals(text, result.text, "decoded text of ${text.length} chars")
    assertEquals("M", result.resultMetadata[ResultMetadataType.ERROR_CORRECTION_LEVEL], "level of \"$text\"")
    assertEquals(17 + 4 * versionFor(text), matrix.size, "size of \"$text\"")
    return matrix
}

private fun Random.string(length: Int, alphabet: String) = String(CharArray(length) { alphabet[nextInt(alphabet.length)] })

private fun Random.address(prefix: String, length: Int) = prefix + string(length, BECH32)

// Two hundred shapes the app shows: testnet and mainnet P2WPKH, a P2WSH vault address, short and long BIP-21 URIs, plain ASCII.
private fun sampleTexts(): List<String> {
    val random = Random(2569)
    return List(200) { i ->
        when (i % 6) {
            0 -> random.address("tb1q", 38)
            1 -> random.address("bc1q", 38)
            2 -> random.address("tb1q", 58)
            3 -> "bitcoin:${random.address("tb1q", 38)}?amount=0.0${random.string(7, "0123456789")}&label=Rent%20March&message=Thanks"
            4 -> "bitcoin:${random.address("tb1q", 58)}?amount=1.${random.string(8, "0123456789")}" +
                "&label=${random.string(40, "ABCabc%20")}&message=${random.string(40, "xyzXYZ%20")}"
            else -> random.string(random.nextInt(1, 151), String(CharArray(95) { ' ' + it }))
        }
    }
}

private fun QrMatrix.rows() = List(size) { row -> String(CharArray(size) { col -> if (isDark(row, col)) '#' else '.' }) }

class QrDecodeTest {
    @Test
    fun twoHundredSeededAddressesUrisAndPlainTextsScanBackAtLevelM() {
        sampleTexts().forEach { assertScansBackAsLevelM(it) }
    }

    @Test
    fun textFillingAVersionExactlyKeepsItAndOneByteMoreTakesTheNext() {
        capacityAtM.forEachIndexed { index, capacity ->
            val version = index + 1
            assertEquals(17 + 4 * version, assertScansBackAsLevelM("a".repeat(capacity)).size, "$capacity bytes")
            if (version < capacityAtM.size) {
                assertEquals(17 + 4 * (version + 1), assertScansBackAsLevelM("a".repeat(capacity + 1)).size, "${capacity + 1} bytes")
            }
        }
    }

    @Test
    fun nonAsciiTextTravelsAsUtf8() {
        listOf("สวัสดี mebit", "Café déjà vu", "₿ 0.5 \uD83D\uDE00", "ค่าเช่าเดือนมีนาคม").forEach { assertScansBackAsLevelM(it) }
    }

    @Test
    fun emptyTextScansBackAsEmpty() {
        assertScansBackAsLevelM("")
    }

    @Test
    fun everyModuleMatchesZxingsEncoderSoTheMaskIsTheStandardsChoice() {
        // ZXing picks numeric or alphanumeric mode for such text, while encodeQr always uses byte mode.
        val byteModeInZxing = Regex("[^0-9A-Z $%*+\\-./:]")
        // Lopsided bytes push the dark share off 50%. The last four were found by search: rule 4 alone changes their mask.
        val lopsided = capacityAtM.flatMap { listOf("\u0001".repeat(it), "a".repeat(it), "~".repeat(it)) } +
            listOf("a".repeat(22), "\u0002".repeat(16), "~".repeat(116), "~".repeat(148))
        val masksSeen = mutableSetOf<Int>()
        for (text in (sampleTexts() + lopsided).filter { byteModeInZxing.containsMatchIn(it) }) {
            val reference = Encoder.encode(text, ErrorCorrectionLevel.M)
            val referenceRows = List(reference.matrix.height) { row ->
                String(CharArray(reference.matrix.width) { col -> if (reference.matrix.get(col, row).toInt() == 1) '#' else '.' })
            }
            assertEquals(referenceRows, encodeQr(text).rows(), "\"$text\"")
            masksSeen += reference.maskPattern
        }
        assertTrue(masksSeen.size >= 6, "the samples only used masks $masksSeen, so the mask choice is hardly tested")
    }

    @Test
    fun theSameTextGivesTheSameMatrixTwice() {
        val text = "bitcoin:tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx?amount=0.0005"
        assertEquals(encodeQr(text).rows(), encodeQr(text).rows())
    }

    @Test
    fun aTextOverVersionTenIsRejectedByBytesNotByCharacters() {
        val tooLong = assertFailsWith<IllegalArgumentException> { encodeQr("a".repeat(capacityAtM.last() + 1)) }
        assertTrue(tooLong.message.orEmpty().contains("214"), "the message should give the size, was ${tooLong.message}")
        assertFailsWith<IllegalArgumentException> { encodeQr("ก".repeat(72)) }
        assertScansBackAsLevelM("ก".repeat(71))
    }
}
