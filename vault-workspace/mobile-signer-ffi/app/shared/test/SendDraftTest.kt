package com.mebit.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SendDraftTest {
    @Test
    fun btcAmountsBecomeSatsExactly() {
        assertEquals(500_000L, parseBtc("0.0050"))
        assertEquals(100_000_000L, parseBtc("1"))
        assertEquals(1L, parseBtc("0.00000001"))
        assertEquals(150_000_000L, parseBtc("1.5"))
        assertEquals(50_000_000L, parseBtc(".5"))
    }

    @Test
    fun unreadableOrEmptyAmountsAreNoAmount() {
        listOf("", ".", "0", "0.0", "abc", "1.2.3", "-1", "0.000000001", "22000000").forEach { assertNull(parseBtc(it), it) }
    }

    @Test
    fun formattingCutsAndNeverRounds() {
        assertEquals("0.00501128", formatBtc(501_128))
        assertEquals("0.0050", formatBtc(501_128, 4))
        assertEquals("0.0312", formatBtc(3_129_999, 4))
        assertEquals("1", formatBtc(100_000_000, 0))
    }

    @Test
    fun feeTiersAreRateTimesTheTypicalSize() {
        assertEquals(listOf(564L, 1_128L, 2_115L), FeeTier.entries.map { it.sats })
    }

    @Test
    fun anInvalidAddressBeatsEveryOtherIssue() {
        assertEquals(SendIssue.InvalidAddress, sendIssue(false, 10, 1, 1))
        assertNull(sendIssue(null, 0, 1_128, 0))
    }

    @Test
    fun aPaymentAboveTheBalanceNamesTheShortfallWithTheFee() {
        // 0.0500 BTC plus a 1,128 sat fee against 0.0312 BTC: 0.0188 BTC and the fee are missing.
        val issue = sendIssue(true, 5_000_000, 1_128, 3_120_000)
        assertEquals(SendIssue.Insufficient(1_881_128), issue)
        assertNull(sendIssue(true, 3_118_872, 1_128, 3_120_000))
    }

    @Test
    fun resetClearsTheDraftAndRestoresTheNormalFee() {
        val draft = SendDraft()
        draft.destination = "tb1q"
        draft.setAmountBtc("0.5")
        draft.chooseTier(FeeTier.Fast)
        draft.reset()
        assertEquals("", draft.destination)
        assertEquals(0L, draft.amountSats)
        assertEquals(FeeTier.Normal.sats, draft.feeSats)
    }
}
