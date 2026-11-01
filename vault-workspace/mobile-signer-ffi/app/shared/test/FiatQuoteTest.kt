package com.mebit.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FiatQuoteTest {
    private fun priced(thbPerBtc: Double) = FiatQuote(thbPerBtc, fetchedAtSeconds = 0)
    private val twoMillion = priced(2_000_000.0)

    @Test
    fun halfABahtRoundsUpAndJustUnderItRoundsDown() {
        // 25 sats at 2,000,000 baht per bitcoin are exactly 0.5 baht, and 24 sats are 0.48.
        assertEquals("฿1", bahtText(25, twoMillion))
        assertEquals("฿0", bahtText(24, twoMillion))
        assertEquals("฿1", bahtText(1, priced(50_000_000.0)))
        assertEquals("฿0", bahtText(1, priced(49_999_999.0)))
        // 1.5 and 2.5 baht: half up gives 2 and 3, where rounding half to even would give 2 and 2.
        assertEquals("฿2", bahtText(75, twoMillion))
        assertEquals("฿3", bahtText(125, twoMillion))
    }

    @Test
    fun thousandsGetSeparators() {
        assertEquals("฿999", bahtText(49_950, twoMillion))
        assertEquals("฿1,000", bahtText(50_000, twoMillion))
        assertEquals("฿18,010", bahtText(900_500, twoMillion))
        assertEquals("฿2,619,363", bahtText(100_000_000, priced(2_619_363.0)))
    }

    @Test
    fun aPriceWithSatangIsAppliedBeforeRounding() {
        // 0.01234567 bitcoin at the price in the real Bitkub recording is 32,358.63 baht.
        assertEquals("฿32,359", bahtText(1_234_567, priced(2_621_051.11)))
    }

    @Test
    fun aLargeBalanceDoesNotOverflow() {
        // All 21 million coins. The product with the price is about 5.5e21, far past Long.MAX_VALUE (9.2e18).
        assertEquals("฿55,006,623,000,000", bahtText(2_100_000_000_000_000, priced(2_619_363.0)))
    }

    @Test
    fun zeroNeedsNoPriceAndAnyOtherAmountDoes() {
        assertEquals("฿0", bahtText(0, null))
        assertEquals("฿0", bahtText(0, twoMillion))
        assertNull(bahtText(1, null))
        assertNull(bahtText(100_000_000, null))
    }
}
