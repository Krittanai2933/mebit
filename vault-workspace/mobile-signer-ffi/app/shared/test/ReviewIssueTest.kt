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
}
