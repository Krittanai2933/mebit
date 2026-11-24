package com.mebit.app.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

/** What a phone shows for its screen lock, as WalletGate reads it at the start and each time the app comes back. */
class LockGateTest {
    @Test
    fun aPhoneThatNeverHadALockThisRunWaitsOnTheBlockAlone() {
        assertEquals(LockGate.Wait, lockGate(present = false, seenThisRun = false))
    }

    @Test
    fun aPhoneWithItsLockRunsTheApp() {
        assertEquals(LockGate.Open, lockGate(present = true, seenThisRun = true))
        assertEquals(LockGate.Open, lockGate(present = true, seenThisRun = false))
    }

    @Test
    fun aLockGoneAfterItWasSeenCoversTheApp() {
        assertEquals(LockGate.Covered, lockGate(present = false, seenThisRun = true))
    }
}
