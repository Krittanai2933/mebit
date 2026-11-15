package com.mebit.app.feature.send

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignAttemptTest {
    @Test
    fun aSecondTapWhileOneRunsStartsNothing() {
        val attempt = SignAttempt()
        assertTrue(attempt.begin())
        assertTrue(attempt.running)
        assertFalse(attempt.begin())
        assertFalse(attempt.begin())
    }

    @Test
    fun aRefusedOrFailedAttemptFreesTheButtonForAnotherTry() {
        val attempt = SignAttempt()
        assertTrue(attempt.begin())
        attempt.end(paid = false)
        assertFalse(attempt.running)
        assertTrue(attempt.begin())
    }

    @Test
    fun aPaidAttemptKeepsTheButtonLockedForGood() {
        val attempt = SignAttempt()
        assertTrue(attempt.begin())
        attempt.end(paid = true)
        assertTrue(attempt.running)
        assertFalse(attempt.begin())
    }
}
