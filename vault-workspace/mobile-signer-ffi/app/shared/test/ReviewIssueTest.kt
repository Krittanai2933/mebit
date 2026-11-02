package com.mebit.app.feature.send

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

    @Test
    fun anUnknownOutcomeIsNeverHeadlinedAsAFailureBecauseThePaymentMayBeOut() {
        assertEquals("ส่งไม่สำเร็จ", ReviewIssue.BroadcastFailed.title)
        assertEquals("ยังไม่ทราบผลการส่ง", ReviewIssue.BroadcastUnknown.title)
    }
}
