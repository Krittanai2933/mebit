package com.mebit.app

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The wallet export's codes: far over version 10, which only Receive's addresses keep to. */
class QrLargeDecodeTest {
    private fun random(length: Int, alphabet: String, seed: Int): String {
        val random = Random(seed)
        return String(CharArray(length) { alphabet[random.nextInt(alphabet.length)] })
    }

    @Test
    fun aThreeOfFiveSizedTextScansBackAtVersionTwentyFiveOrMore() {
        val text = random(950, "abcdefghijklmnopqrstuvwxyz0123456789()[]/'\n,<;>*#", 25)
        val matrix = encodeQr(text, LARGEST_VERSION)
        assertTrue(matrix.size >= 17 + 4 * 25, "size ${matrix.size}")
        assertEquals(text, scan(matrix).text)
    }

    @Test
    fun aUrPartTakesAlphanumericModeAndScansBack() {
        val part = "UR:BYTES/2-4/" + random(310, "ABCDEFGHIJKLMNOPQRSTUVWXYZ", 7)
        val matrix = encodeQr(part, LARGEST_VERSION, alphanumeric = true)
        assertEquals(part, scan(matrix).text)
        assertTrue(matrix.size < encodeQr(part, LARGEST_VERSION).size, "alphanumeric mode is the smaller code")
    }

    @Test
    fun receivesLimitStaysAtVersionTen() {
        assertTrue(runCatching { encodeQr("a".repeat(214)) }.isFailure)
    }
}
