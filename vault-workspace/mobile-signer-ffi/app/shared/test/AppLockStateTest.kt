package com.mebit.app.data

import com.mebit.app.BiometricResult
import com.mebit.app.Biometrics
import com.mebit.app.NanosTimeSource
import com.mebit.app.SleepCountingTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.runBlocking

class AppLockStateTest {
    private val clock = TestTimeSource()
    private val state = AppLockState(clock)

    /** The phone's own check. [lock] is whether the phone has a passcode or a secure screen lock. */
    private class Scan(private val result: BiometricResult, private val lock: Boolean = true) : Biometrics {
        var asked = 0
        override fun available() = lock
        override suspend fun authenticate(reason: String, allowPasscode: Boolean): BiometricResult {
            asked += 1
            return result
        }
    }

    @Test
    fun comingBackWithinAMinuteStaysUnlocked() {
        state.onBackground()
        clock += 60.seconds
        state.onForeground(enabled = true)
        assertFalse(state.locked)
    }

    @Test
    fun comingBackAfterMoreThanAMinuteLocks() {
        state.onBackground()
        clock += 61.seconds
        state.onForeground(enabled = true)
        assertTrue(state.locked)
    }

    @Test
    fun aLockThatIsOffNeverLocks() {
        state.onBackground()
        clock += 3600.seconds
        state.onForeground(enabled = false)
        assertFalse(state.locked)
    }

    @Test
    fun aForegroundWithoutABackgroundDoesNothingAndUnlockClears() {
        state.onForeground(enabled = true)
        assertFalse(state.locked)

        state.onBackground()
        clock += 120.seconds
        state.onForeground(enabled = true)
        assertTrue(state.locked)
        state.unlock()
        assertFalse(state.locked)
    }

    @Test
    fun aMinuteAsleepLocksOnlyOnAClockThatCountsSleep() {
        // Ten minutes away, all but two seconds of them in deep sleep, read by a clock that stops in deep sleep, as System.nanoTime
        // does on Android, and by one that counts it.
        var awake = 0L
        var withSleep = 0L
        val stopsAsleep = AppLockState(NanosTimeSource { awake })
        val countsSleep = AppLockState(NanosTimeSource { withSleep })
        stopsAsleep.onBackground()
        countsSleep.onBackground()
        awake += 2.seconds.inWholeNanoseconds
        withSleep += 10.minutes.inWholeNanoseconds
        stopsAsleep.onForeground(enabled = true)
        countsSleep.onForeground(enabled = true)
        assertFalse(stopsAsleep.locked, "a clock that stops in deep sleep counts two seconds away and leaves Home open")
        assertTrue(countsSleep.locked, "ten minutes away locks, asleep or not")
    }

    @Test
    fun theLockRunsOnTheClockThatCountsSleep() {
        assertSame(SleepCountingTime, AppLockState().clock, "the 60-second lock counts a minute spent asleep")
    }

    @Test
    fun signingNeedsAMatchOnlyWhenTheLockIsOn() = runBlocking {
        val off = Scan(BiometricResult.Failed)
        assertTrue(state.confirm(off, enabled = false, reason = "r"))
        assertEquals(0, off.asked)

        assertTrue(state.confirm(Scan(BiometricResult.Success), enabled = true, reason = "r"))
        for (result in listOf(BiometricResult.Failed, BiometricResult.Cancelled, BiometricResult.LockedOut, BiometricResult.Unavailable("x"))) {
            assertFalse(state.confirm(Scan(result), enabled = true, reason = "r"), "must fail closed on $result")
        }
    }

    @Test
    fun aPhoneWithNoLockOfItsOwnIsAskedEvenWithTheLockOff() = runBlocking {
        // Only an owner check that is not the phone's own, the Spending PIN, can answer here. The phone's own fails closed.
        val noLock = Scan(BiometricResult.Unavailable("NoScreenLock"), lock = false)
        assertFalse(state.confirm(noLock, enabled = false, reason = "r"))
        assertEquals(1, noLock.asked)
    }
}
