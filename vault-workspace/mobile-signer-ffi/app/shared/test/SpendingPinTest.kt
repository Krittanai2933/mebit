package com.mebit.app.data

import com.mebit.app.SleepCountingTime
import com.mebit.app.sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class SpendingPinTest {
    private val clock = TestTimeSource()
    private val pin = SpendingPin(clock)

    @Test
    fun theWaitsAfterWrongPinsArePhoenixs() {
        val zero = Duration.ZERO
        assertEquals(
            listOf(zero, zero, zero, 10.seconds, 30.seconds, 1.minutes, 2.minutes, 5.minutes, 10.minutes, 30.minutes, 30.minutes),
            (0..10).map(::pinWait),
        )
        assertEquals(30.minutes, pinWait(1_000), "every try after the eighth waits 30 minutes, with no end and no wipe")
    }

    @Test
    fun commonPinsAreRefusedAndSetNothing() {
        val common = listOf("123456", "000000", "111111", "121212", "654321", "112233", "123123", "999999", "012345", "987654", "343434", "456456", "332211")
        for (p in common) {
            assertTrue(isCommonPin(p), p)
            assertFalse(pin.set(p), "set $p")
            assertFalse(pin.isSet, "a PIN set by $p")
        }
    }

    @Test
    fun anOrdinaryPinIsKept() {
        for (p in listOf("270419", "583920", "102938", "135791", "246802")) assertFalse(isCommonPin(p), p)
        assertTrue(pin.set("270419"))
        assertTrue(pin.isSet)
    }

    @Test
    fun onlySixAsciiDigitsMakeAPin() {
        for (p in listOf("", "27041", "2704190", "27041a", " 70419", "２７０４１９")) assertFalse(pin.set(p), "[$p]")
        assertFalse(pin.isSet)
    }

    @Test
    fun theHashIsTheStandardSha256() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex(sha256("abc".encodeToByteArray())))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            hex(sha256("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray())),
        )
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex(sha256(ByteArray(0))))
    }

    @Test
    fun onlyTheSessionsPinPasses() {
        assertFalse(pin.check("270419"), "no PIN is set, so nothing passes")
        assertTrue(pin.set("270419"))
        assertTrue(pin.check("270419"))
        assertFalse(pin.check("270418"))
        assertFalse(pin.check("27041"))
        assertTrue(pin.check("270419"), "two wrong PINs start no wait")
        assertFalse(pin.check("2704190"))
        assertTrue(pin.check("270419"))
    }

    @Test
    fun theThirdWrongPinStartsAWaitThatEvenTheRightPinWaitsOut() {
        assertTrue(pin.set("270419"))
        repeat(2) {
            assertFalse(pin.check("583920"))
            assertEquals(Duration.ZERO, pin.waitLeft(), "wrong PIN ${it + 1}")
        }
        assertFalse(pin.check("583920"))
        assertEquals(10.seconds, pin.waitLeft())
        clock += 9.seconds
        assertFalse(pin.check("270419"), "the right PIN inside the wait")
        assertEquals(1.seconds, pin.waitLeft(), "a try inside the wait neither counts nor restarts it")
        clock += 1.seconds
        assertTrue(pin.check("270419"))
        assertFalse(pin.check("583920"))
        assertEquals(Duration.ZERO, pin.waitLeft(), "the right PIN starts the count again")
    }

    @Test
    fun eachWrongPinAfterItsWaitWaitsLonger() {
        assertTrue(pin.set("270419"))
        val waits = (1..11).map {
            clock += pin.waitLeft()
            assertFalse(pin.check("583920"))
            pin.waitLeft()
        }
        assertEquals(listOf(Duration.ZERO, Duration.ZERO, 10.seconds, 30.seconds, 1.minutes, 2.minutes, 5.minutes, 10.minutes, 30.minutes, 30.minutes, 30.minutes), waits)
    }

    @Test
    fun theWaitsRunOnTheClockThatCountsSleep() {
        assertSame(SleepCountingTime, SpendingPin().clock, "a wait spent asleep has passed")
    }

    @Test
    fun noNumberOfWrongPinsWipesTheWalletOrThePin() {
        val session = sessionWithKey(pin)
        assertTrue(pin.set("270419"))
        repeat(40) {
            clock += pin.waitLeft()
            assertFalse(pin.check("583920"))
        }
        clock += pin.waitLeft()
        assertEquals(PUBLIC_WORDS_24, session.words, "the words after 40 wrong PINs")
        assertTrue(pin.check("270419"), "the right PIN after the last wait")
    }

    @Test
    fun theSessionDropsItsPinWithTheWords() {
        val session = sessionWithKey(pin)
        assertTrue(pin.set("270419"))
        session.clear()
        assertFalse(pin.isSet)
        assertFalse(pin.check("270419"))
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
