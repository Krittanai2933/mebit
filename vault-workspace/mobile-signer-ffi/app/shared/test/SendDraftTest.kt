package com.mebit.app.data

import com.mebit.ffi.signer.AddressType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        // formatBtc's 4-place cut is a primitive only. The screens that show money use formatBtcCompact, which never hides sats
        // (open item 74). This guard stays for any caller that still asks for 4 places (interrogate finding 15: 9,999 sats read as 0.0000).
        assertEquals("0.00009", formatBtc(9_999, 4))
        assertEquals("0.00000001", formatBtc(1, 4))
        assertEquals("0.00009", formatBtc(9_999, 0))
        assertEquals("0.0000", formatBtc(0, 4), "nothing is nothing")
        assertEquals("1.0000", formatBtc(100_009_999, 4), "a whole part already shows the amount")
        assertEquals("0.0050", formatBtc(500_000, 4), "an amount that shows at 4 places keeps 4")
    }

    @Test
    fun feeTiersAreRateTimesTheTypicalSize() {
        val native = SendDraft().typicalVsize
        assertEquals(listOf(564L, 1_128L, 2_115L), LiveFeeTiers.DEFAULT.tiles(native).map { it.feeSats }, "Native SegWit's board estimates as before")
        assertEquals(LiveFeeTiers(4, 8, 15, live = false), LiveFeeTiers.DEFAULT, "the board's rates until the live ones arrive")
        assertEquals(
            listOf(FeeTile(FeeTier.Slow, 1, 141), FeeTile(FeeTier.Normal, 1, 141), FeeTile(FeeTier.Fast, 2, 282)),
            LiveFeeTiers(1, 1, 2, live = true).tiles(native),
            "an empty testnet4 mempool, as the user's node reports it",
        )
    }

    // The round-2 review's repro, turned green. Review priced every type at Native SegWit's 141 vB, so a Legacy payment signed
    // 1,784 where Review showed 1,128, and a Legacy send all showed 98,872 and sent 98,488. The fees and amounts below are what
    // the core signs from one 100,000-sat coin at 8 sat/vB in hot_wallet.rs each_types_typical_size_is_what_one_coin_paying_its_own_type_signs:
    // 30,000 to an address of the wallet's own type with change, and a send all to a P2WPKH address.
    @Test
    fun reviewShowsTheFeeEachAddressTypeSignsAndNoSendAllShowsMoreThanItSends() {
        val signed = mapOf(AddressType.LEGACY to 1_808L, AddressType.NESTED_SEGWIT to 1_328L, AddressType.NATIVE_SEGWIT to 1_128L, AddressType.TAPROOT to 1_232L)
        val sentAll = mapOf(AddressType.LEGACY to 98_488L, AddressType.NESTED_SEGWIT to 98_936L, AddressType.NATIVE_SEGWIT to 99_120L, AddressType.TAPROOT to 99_208L)
        for (type in AddressType.entries) {
            assertEquals(signed[type], SendDraft(type).feeSats, "$type: Review's fee at the Normal tier")
            val all = SendDraft(type)
            AmountEntry(all).sendAll(100_000)
            assertTrue(all.amountSats <= sentAll.getValue(type), "$type: send all shows ${all.amountSats} and sends ${sentAll[type]}")
        }
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
        assertEquals(1_128L, draft.feeSats)
    }
}
