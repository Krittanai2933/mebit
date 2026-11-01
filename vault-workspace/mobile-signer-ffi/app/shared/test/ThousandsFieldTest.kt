package com.mebit.app.feature.send

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThousandsFieldTest {
    @Test
    fun digitsGetACommaEveryThreePlaces() {
        assertEquals("", groupDigits(""))
        assertEquals("5", groupDigits("5"))
        assertEquals("999", groupDigits("999"))
        assertEquals("1,000", groupDigits("1000"))
        assertEquals("18,010", groupDigits("18010"))
        assertEquals("112,349", groupDigits("112349"))
        assertEquals("999,999,999", groupDigits("999999999"))
    }

    @Test
    fun theBahtFieldGroupsLikeEveryOtherBahtFigure() {
        listOf(0L, 5L, 999L, 1_000L, 18_010L, 112_349L, 999_999_999L).forEach {
            assertEquals(thousands(it), groupDigits(it.toString()))
        }
    }

    @Test
    fun aLeadingZeroKeepsItsPlaceSoTheCaretStillMapsOneToOne() {
        assertEquals("0,012", groupDigits("0012"))
    }

    @Test
    fun theCaretMapsBackAndForthAtEveryPlace() {
        for (length in 0..13) {
            val grouped = groupDigits("1234567890123".take(length))
            var lastShown = -1
            for (typed in 0..length) {
                val shown = caretInGrouped(grouped, typed)
                assertTrue(shown in 0..grouped.length, "$typed digits in $grouped")
                assertTrue(shown >= lastShown, "$typed digits in $grouped")
                assertEquals(typed, caretInDigits(grouped, shown), "$typed digits in $grouped")
                lastShown = shown
            }
            var lastTyped = -1
            for (shown in 0..grouped.length) {
                val typed = caretInDigits(grouped, shown)
                assertTrue(typed in 0..length, "caret $shown in $grouped")
                assertTrue(typed >= lastTyped, "caret $shown in $grouped")
                lastTyped = typed
            }
        }
    }

    @Test
    fun aCaretAfterTheLastDigitStaysAfterIt() {
        for (length in 0..13) {
            val grouped = groupDigits("1234567890123".take(length))
            assertEquals(grouped.length, caretInGrouped(grouped, length))
        }
    }
}
