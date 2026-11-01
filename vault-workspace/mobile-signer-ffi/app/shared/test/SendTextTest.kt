package com.mebit.app.feature.send

import com.mebit.app.data.FiatQuote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SendTextTest {
    // At this price 500,000 sats are exactly ฿18,010 and 1,128 sats are 40.63 baht, the numbers on boards 4.5 and 4.6.
    private val boardPrice = FiatQuote(3_602_000.0, fetchedAtSeconds = 0)
    private val twoMillion = FiatQuote(2_000_000.0, fetchedAtSeconds = 0)

    @Test
    fun fiatLineIsTheBoardSampleWithAnApproximatelySign() {
        assertEquals("≈ ฿18,010", fiatLine(500_000, boardPrice))
    }

    @Test
    fun fiatLineIsHiddenWithoutAPriceExceptForZero() {
        assertNull(fiatLine(500_000, null))
        assertNull(fiatLine(1, null))
        assertEquals("≈ ฿0", fiatLine(0, null))
        assertEquals("≈ ฿0", fiatLine(0, boardPrice))
    }

    @Test
    fun fiatLineRoundsHalfUpAndGroupsThousands() {
        // 25 sats are exactly half a baht at 2,000,000 baht per bitcoin, and 24 sats are 0.48.
        assertEquals("≈ ฿1", fiatLine(25, twoMillion))
        assertEquals("≈ ฿0", fiatLine(24, twoMillion))
        assertEquals("≈ ฿999", fiatLine(49_950, twoMillion))
        assertEquals("≈ ฿1,000", fiatLine(50_000, twoMillion))
    }

    @Test
    fun fiatLineHoldsAllTheCoinsThereWillEverBe() {
        assertEquals("≈ ฿55,006,623,000,000", fiatLine(2_100_000_000_000_000, FiatQuote(2_619_363.0, fetchedAtSeconds = 0)))
    }

    @Test
    fun reviewFeeIsSatsThenBaht() {
        assertEquals("1,128 sat · ฿41", reviewFeeText(1_128, boardPrice))
        assertEquals("564 sat · ฿20", reviewFeeText(564, boardPrice))
        assertEquals("12,345 sat · ฿445", reviewFeeText(12_345, boardPrice))
    }

    @Test
    fun reviewFeeIsJustSatsWithoutAPrice() {
        assertEquals("1,128 sat", reviewFeeText(1_128, null))
        assertEquals("564 sat", reviewFeeText(564, null))
        assertEquals("1,234,567 sat", reviewFeeText(1_234_567, null))
    }

    @Test
    fun slipFeeIsBahtThenTheRate() {
        assertEquals("฿41 · 8 sat/vB", slipFeeText(1_128, 8, boardPrice))
        assertEquals("฿76 · 15 sat/vB", slipFeeText(2_115, 15, boardPrice))
        assertEquals("฿1,235 · 4 sat/vB", slipFeeText(34_285, 4, boardPrice))
    }

    @Test
    fun slipFeeKeepsTodaysSatsAndRateWithoutAPrice() {
        assertEquals("1,128 sat · 8 sat/vB", slipFeeText(1_128, 8, null))
        assertEquals("564 sat · 4 sat/vB", slipFeeText(564, 4, null))
    }

    @Test
    fun aZeroFeeNeedsNoPriceToBeZeroBaht() {
        assertEquals("0 sat · ฿0", reviewFeeText(0, null))
        assertEquals("0 sat · ฿0", reviewFeeText(0, boardPrice))
        assertEquals("฿0 · 8 sat/vB", slipFeeText(0, 8, null))
        assertEquals("฿0 · 8 sat/vB", slipFeeText(0, 8, boardPrice))
    }
}
