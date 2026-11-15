package com.mebit.app.feature.send

import kotlin.test.Test
import kotlin.test.assertEquals

class HomePullTest {
    @Test
    fun aPullWithNoBindRunningStartsOne() {
        assertEquals(4, afterPull(retries = 3, binding = false))
    }

    @Test
    fun aPullDuringABindStartsNoSecondOne() {
        assertEquals(3, afterPull(retries = 3, binding = true))
    }

    @Test
    fun aRunningRefreshShowsTheSpinnerOverAnArmedPullAndTheLink() {
        assertEquals(TxHeaderEnd.Refreshing, txHeaderEnd(refreshing = true, armed = true, hasMore = true))
    }

    @Test
    fun anArmedPullShowsTheTextInPlaceOfTheLink() {
        assertEquals(TxHeaderEnd.Armed, txHeaderEnd(refreshing = false, armed = true, hasMore = true))
    }

    @Test
    fun theLinkShowsWhenThereIsMoreHistoryAndNoPull() {
        assertEquals(TxHeaderEnd.SeeAll, txHeaderEnd(refreshing = false, armed = false, hasMore = true))
    }

    @Test
    fun theHeaderEndsEmptyWhenNothingApplies() {
        assertEquals(TxHeaderEnd.None, txHeaderEnd(refreshing = false, armed = false, hasMore = false))
    }
}
