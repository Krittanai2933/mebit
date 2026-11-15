package com.mebit.app

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShareNameTest {
    @Test
    fun aPlainNameStaysAsItIs() {
        assertEquals("slip-4f2a", safeShareName("slip-4f2a"))
        assertEquals("Slip_2569.10.23", safeShareName("Slip_2569.10.23"))
    }

    @Test
    fun spacesAndPunctuationBecomeUnderscores() {
        assertEquals("my_slip__23_10___", safeShareName("my slip (23/10)!\\"))
    }

    @Test
    fun everyThaiCharacterBecomesAnUnderscore() {
        assertEquals("____-4f2a", safeShareName("สลิป-4f2a"))
    }

    @Test
    fun aPathCannotClimbOutOfTheShareFolder() {
        assertEquals("_._.._etc_passwd", safeShareName("../../etc/passwd"))
    }

    @Test
    fun aLeadingDotNeverMakesAHiddenFileOrAPathPiece() {
        assertEquals("_hidden", safeShareName(".hidden"))
        assertEquals("_", safeShareName("."))
        assertEquals("_.", safeShareName(".."))
    }

    @Test
    fun onlyAnEmptyNameFallsBackToMebitShare() {
        assertEquals("mebit-share", safeShareName(""))
        assertEquals("___", safeShareName("/ /"))
    }

    @Test
    fun aLongNameKeepsItsFirstSixtyCharacters() {
        assertEquals("slip-" + "x".repeat(55), safeShareName("slip-" + "x".repeat(195)))
    }

    @Test
    fun anyNameGivesOneVisiblePathPieceOfSafeCharacters() {
        val alphabet = "aZ09._-/\\ :*?\"<>|\u0000สลิป\uD83D" + "\uDE00"
        val random = Random(42)
        repeat(1_000) {
            val name = CharArray(random.nextInt(0, 80)) { alphabet[random.nextInt(alphabet.length)] }.concatToString()
            val safe = safeShareName(name)
            assertTrue(safe.matches(Regex("[A-Za-z0-9._-]{1,60}")), "\"$name\" gave \"$safe\"")
            assertFalse(safe.startsWith('.'), "\"$name\" gave \"$safe\"")
        }
    }
}
