package com.mebit.app.feature.send

import com.mebit.ffi.signer.HotTx
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun tx(n: Int) = HotTx("tx$n", n * 1_000L, 6u, 1_787_036_520uL)

class HistoryRowsTest {
    @Test
    fun historyListsEveryTransactionInTheOrderGiven() {
        val history = (1..30).map { tx(it) }
        val rows = historyRows(history)
        assertEquals(30, rows.size)
        assertEquals(homeRows(history, limit = 30), rows)
        assertEquals("+0.00001", rows.first().amount)
        assertEquals("+0.0003", rows.last().amount)
    }

    @Test
    fun theLinkShowsOnlyWhenHistoryIsLongerThanHome() {
        assertFalse(homeHasMoreHistory(emptyList()))
        assertFalse(homeHasMoreHistory((1..4).map { tx(it) }))
        assertTrue(homeHasMoreHistory((1..5).map { tx(it) }))
    }

    @Test
    fun anEmptyHistoryHasNoRowsAndPlainWording() {
        assertTrue(historyRows(emptyList()).isEmpty())
        assertTrue(HISTORY_EMPTY_TEXT.isNotBlank())
        assertFalse("—" in HISTORY_EMPTY_TEXT || "–" in HISTORY_EMPTY_TEXT)
    }
}
