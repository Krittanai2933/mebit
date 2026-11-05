package com.mebit.app.feature.send

import com.mebit.app.data.GUARD_NOT_SAVED
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReviewIssueTest {
    @Test
    fun noRefusalAndNoFailureShowsNoBanner() {
        assertNull(reviewIssue(refused = false, failed = false))
    }

    @Test
    fun aFailedAttemptAloneIsAFailedBroadcast() {
        val issue = reviewIssue(refused = false, failed = true)
        assertEquals(ReviewIssue.BroadcastFailed, issue)
        // The verify recipes find the banner message by these ids.
        assertEquals("error", issue?.tag)
    }

    @Test
    fun aRefusalAloneIsASignRefusal() {
        val issue = reviewIssue(refused = true, failed = false)
        assertEquals(ReviewIssue.SignRefused, issue)
        assertEquals("sign_refused", issue?.tag)
    }

    @Test
    fun aRefusalBeatsAFailedBroadcastBecauseNothingWasSigned() {
        assertEquals(ReviewIssue.SignRefused, reviewIssue(refused = true, failed = true))
    }

    @Test
    fun aPaymentThatMayBeOutBeatsEveryOtherIssueBecauseSendingAgainCouldPayTwice() {
        for (refused in listOf(false, true)) {
            for (failed in listOf(false, true)) {
                assertEquals(ReviewIssue.BroadcastUnknown, reviewIssue(refused, failed, unconfirmedSend = true), "refused=$refused failed=$failed")
            }
        }
        assertEquals("send_unknown", ReviewIssue.BroadcastUnknown.tag)
    }

    // N1c, decision 3. A guard the phone could not keep posted nothing, so Review says why and that a later try is safe, in the line the
    // wallet already had, instead of the generic failure that asks the user to check the balance before sending again.
    @Test
    fun aGuardThePhoneCouldNotKeepNamesItsOwnReason() {
        val issue = reviewIssue(refused = false, failed = true, failure = GUARD_NOT_SAVED)
        assertEquals(ReviewIssue.GuardNotSaved, issue)
        assertEquals("บันทึกสถานะการส่งไม่ได้ จึงยังไม่ส่ง ลองใหม่ภายหลัง", issue?.message)
        assertEquals("guard_not_saved", issue?.tag)
        assertEquals(ReviewIssue.BroadcastFailed, reviewIssue(refused = false, failed = true, failure = "ส่งไม่สำเร็จ"), "any other failure")
        assertEquals(ReviewIssue.BroadcastFailed, reviewIssue(refused = false, failed = true, failure = null), "a failure with no line")
        assertNull(reviewIssue(refused = false, failed = false, failure = GUARD_NOT_SAVED), "a line from an attempt this screen did not make")
        assertEquals(ReviewIssue.SignRefused, reviewIssue(refused = true, failed = true, failure = GUARD_NOT_SAVED))
        assertEquals(ReviewIssue.BroadcastUnknown, reviewIssue(refused = false, failed = true, unconfirmedSend = true, failure = GUARD_NOT_SAVED))
    }

    @Test
    fun anUnknownOutcomeIsNeverHeadlinedAsAFailureBecauseThePaymentMayBeOut() {
        assertEquals("ส่งไม่สำเร็จ", ReviewIssue.BroadcastFailed.title)
        assertEquals("ยังไม่ทราบผลการส่ง", ReviewIssue.BroadcastUnknown.title)
    }
}
