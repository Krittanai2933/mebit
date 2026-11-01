package com.mebit.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Asserts the 7 x 7 finder square whose top-left module is ([row], [col]): a dark border, a light ring, a dark 3 x 3 centre. */
internal fun assertFinderAt(matrix: QrMatrix, row: Int, col: Int) {
    for (dr in 0..6) {
        for (dc in 0..6) {
            val dark = dr == 0 || dr == 6 || dc == 0 || dc == 6 || (dr in 2..4 && dc in 2..4)
            assertEquals(dark, matrix.isDark(row + dr, col + dc), "finder at ($row, $col), module ($dr, $dc)")
        }
    }
}

class QrMatrixTest {
    // Versions 1, 1, 3, 7 (the first with version information) and 10 (the largest).
    private val texts = listOf("", "mebit", "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx", "x".repeat(122), "y".repeat(213))

    @Test
    fun threeFinderSquaresSitInTheCorners() {
        for (text in texts) {
            val matrix = encodeQr(text)
            assertFinderAt(matrix, 0, 0)
            assertFinderAt(matrix, 0, matrix.size - 7)
            assertFinderAt(matrix, matrix.size - 7, 0)
        }
    }

    @Test
    fun timingPatternAlternatesBetweenTheFinders() {
        for (text in texts) {
            val matrix = encodeQr(text)
            for (i in 8..matrix.size - 9) {
                assertEquals(i % 2 == 0, matrix.isDark(6, i), "row 6, column $i of ${matrix.size}")
                assertEquals(i % 2 == 0, matrix.isDark(i, 6), "row $i, column 6 of ${matrix.size}")
            }
        }
    }

    @Test
    fun theFixedDarkModuleSitsBesideTheBottomLeftFinder() {
        for (text in texts) {
            val matrix = encodeQr(text)
            assertTrue(matrix.isDark(matrix.size - 8, 8), "size ${matrix.size}")
        }
    }

    @Test
    fun emptyTextGivesAValidVersionOneSymbol() {
        val matrix = encodeQr("")
        assertEquals(21, matrix.size)
        assertFinderAt(matrix, 0, 0)
        assertFinderAt(matrix, 0, 14)
        assertFinderAt(matrix, 14, 0)
    }

    @Test
    fun aNulCharacterIsRejectedInsteadOfOverflowingTheStack() {
        assertFailsWith<IllegalArgumentException> { encodeQr("tb1q\u0000abc") }
        assertFailsWith<IllegalArgumentException> { encodeQr("\u0000".repeat(84)) }
    }

    @Test
    fun aMatrixWithTheWrongNumberOfModulesIsRejected() {
        assertFailsWith<IllegalArgumentException> { QrMatrix(21, BooleanArray(21 * 21 - 1)) }
        assertFailsWith<IllegalArgumentException> { QrMatrix(21, BooleanArray(21 * 21 + 1)) }
        assertFailsWith<IllegalArgumentException> { QrMatrix(-3, BooleanArray(9)) }
        assertEquals(21, QrMatrix(21, BooleanArray(21 * 21)).size)
    }

    @Test
    fun aModuleOutsideTheSymbolIsRejectedInsteadOfWrappingToAnotherRow() {
        val matrix = QrMatrix(3, BooleanArray(9) { true })
        assertTrue(matrix.isDark(2, 2))
        for ((row, col) in listOf(-1 to 0, 0 to -1, 3 to 0, 0 to 3, 1 to 3)) {
            assertFailsWith<IndexOutOfBoundsException>("($row, $col)") { matrix.isDark(row, col) }
        }
    }
}
