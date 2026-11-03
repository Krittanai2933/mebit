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
    fun aRealAmountNeverReadsAsZero() {
        // The review headline, the slip and the Home balance cut to 4 places (interrogate finding 15: 9,999 sats read as 0.0000).
        assertEquals("0.00009", formatBtc(9_999, 4))
        assertEquals("0.00000001", formatBtc(1, 4))
        assertEquals("0.00009", formatBtc(9_999, 0))
        assertEquals("0.0000", formatBtc(0, 4), "nothing is nothing")
        assertEquals("1.0000", formatBtc(100_009_999, 4), "a whole part already shows the amount")
        assertEquals("0.0050", formatBtc(500_000, 4), "an amount that shows at 4 places keeps 4")
    }

    @Test
    fun feeTiersAreRateTimesTheTypicalSize() {
        assertEquals(listOf(564L, 1_128L, 2_115L), FeeTier.entries.map { it.sats })
        assertEquals(LiveFeeTiers(4, 8, 15, live = false), LiveFeeTiers.DEFAULT, "the board's rates until the live ones arrive")
        assertEquals(
            listOf(FeeTile(FeeTier.Slow, 1, 141), FeeTile(FeeTier.Normal, 1, 141), FeeTile(FeeTier.Fast, 2, 282)),
            LiveFeeTiers(1, 1, 2, live = true).tiles(),
            "an empty testnet4 mempool, as the user's node reports it",
        )
    }

    @Test
    fun liveRatesChangeTheEstimateOfTheChosenTierAndSurviveAReset() {
        val draft = SendDraft()
        assertNull(draft.tiers, "nothing fetched yet")
        assertEquals(8L, draft.rateSatVb)
        draft.chooseTier(FeeTier.Fast)
        draft.takeTiers(LiveFeeTiers(1, 1, 2, live = true))
        assertEquals(2L, draft.rateSatVb)
        assertEquals(282L, draft.feeSats, "the Fast estimate at 2 sat/vB")
        draft.chooseTier(FeeTier.Slow)
        assertEquals(141L, draft.feeSats)
        draft.reset()
        assertEquals(FeeTier.Normal, draft.tier)
        assertEquals(141L, draft.feeSats, "Normal at the live 1 sat/vB, not the board's 1,128")
        assertEquals(LiveFeeTiers(1, 1, 2, live = true), draft.tiers)
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
