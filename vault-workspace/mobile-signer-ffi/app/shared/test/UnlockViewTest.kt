package com.mebit.app.feature.onboarding

import com.mebit.app.Why
import com.mebit.app.data.Cause
import com.mebit.app.data.UnlockResult
import com.mebit.app.navigation.Boot
import kotlin.test.Test
import kotlin.test.assertEquals

class UnlockViewTest {
    @Test
    fun aLockedWalletAsksWithNoBannerBeforeTheFirstAnswerAndAfterACancel() {
        assertEquals(UnlockView.Ask, unlockView(Boot.Locked, null), "before the first answer")
        assertEquals(UnlockView.Ask, unlockView(Boot.Locked, UnlockResult.Cancelled), "after a cancel")
    }

    @Test
    fun aRefusedFaceOrCodeShowsTheFailedStateAndALockoutItsOwn() {
        assertEquals(UnlockView.Failed, unlockView(Boot.Locked, UnlockResult.Denied))
        assertEquals(UnlockView.LockedOut, unlockView(Boot.Locked, UnlockResult.LockedOut))
    }

    @Test
    fun onlyTryLaterSaysTryAgainAndEveryOtherReasonSaysThePhoneCannotOpenItNow() {
        for (why in Why.entries) {
            assertEquals(UnlockView.Unavailable(tryLater = why == Why.TryLater), unlockView(Boot.Locked, UnlockResult.Unavailable(why)), "$why")
        }
    }

    @Test
    fun aLockedWalletExplainsEveryAttemptEvenTheOneItArrivedWith() {
        for (attempt in ATTEMPTS) {
            // Only the recovery screen sets aside the attempt it arrived with. A Locked screen explains whatever came last.
            assertEquals(unlockView(Boot.Locked, attempt), unlockView(Boot.Locked, attempt, attempt), label(attempt))
        }
    }

    @Test
    fun aBrokenWalletShowsRecoveryWithItsCauseWhateverAttemptLedThere() {
        for (cause in Cause.entries) {
            assertEquals(UnlockView.Recovery(cause, forgetFailed = false), unlockView(Boot.Broken(cause), null), "$cause at start")
            for (arrival in ATTEMPTS) {
                assertEquals(UnlockView.Recovery(cause, forgetFailed = false), unlockView(Boot.Broken(cause), arrival, arrival), "$cause after ${label(arrival)}")
            }
        }
    }

    @Test
    fun aForgetWhoseEraseFailsOnTheRecoveryScreenSaysSo() {
        for (cause in Cause.entries) {
            for (arrival in ATTEMPTS) {
                // The gate reports a failed erase as a new Unavailable(Other), also after an attempt that read the same.
                val failedErase = UnlockResult.Unavailable(Why.Other)
                assertEquals(UnlockView.Recovery(cause, forgetFailed = true), unlockView(Boot.Broken(cause), failedErase, arrival), "$cause after ${label(arrival)}")
            }
        }
    }
}

/** Every attempt the gate can keep as the last one, and none: before the first answer. */
private val ATTEMPTS: List<UnlockResult?> =
    listOf(null, UnlockResult.Cancelled, UnlockResult.Denied, UnlockResult.LockedOut) + Why.entries.map { UnlockResult.Unavailable(it) }

private fun label(attempt: UnlockResult?): String = if (attempt is UnlockResult.Unavailable) "Unavailable(${attempt.why})" else "$attempt"
