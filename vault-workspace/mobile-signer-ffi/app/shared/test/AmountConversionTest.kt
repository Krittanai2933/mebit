package com.mebit.app.data

import com.mebit.app.feature.send.thousands
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AmountConversionTest {
    @Test
    fun bahtBecomeSatsRoundedToTheNearestSat() {
        // At 3,000,000 baht per bitcoin 1 baht is 33.33 sats, 2 baht are 66.67 and 3 baht are exactly 100.
        assertEquals(33L, bahtToSats(1, 3_000_000.0))
        assertEquals(67L, bahtToSats(2, 3_000_000.0))
        assertEquals(100L, bahtToSats(3, 3_000_000.0))
        // The board price: 18,010 baht are exactly 500,000 sats.
        assertEquals(500_000L, bahtToSats(18_010, 3_602_000.0))
    }

    @Test
    fun aHalfSatRoundsUp() {
        // At 40,000,000 baht per bitcoin 1, 3 and 5 baht are 2.5, 7.5 and 12.5 sats. Half up gives 3, 8 and 13. Cutting gives 2, 7 and 12, half to even gives 2, 8 and 12.
        assertEquals(listOf(3L, 8L, 13L), listOf(1L, 3L, 5L).map { bahtToSats(it, 40_000_000.0) })
        // At 200,000,000 baht per bitcoin 1 baht is exactly half a sat.
        assertEquals(1L, bahtToSats(1, 200_000_000.0))
    }

    @Test
    fun zeroBahtIsZeroSats() {
        assertEquals(0L, bahtToSats(0, 3_000_000.0))
        assertEquals(0L, bahtToSats(0, 1_000.0))
    }

    @Test
    fun nineDigitsAtTheLowestPriceFit() {
        // 999,999,999 baht at 1,000 baht per bitcoin are 999,999.999 bitcoin, far under 21 million.
        assertEquals(99_999_999_900_000L, bahtToSats(999_999_999, 1_000.0))
    }

    @Test
    fun moreThan21MillionBitcoinIsRefused() {
        // At 1 baht per bitcoin 21,000,000 baht are exactly 21 million bitcoin and one baht more is too much.
        assertEquals(2_100_000_000_000_000L, bahtToSats(21_000_000, 1.0))
        assertNull(bahtToSats(21_000_001, 1.0))
        assertNull(bahtToSats(999_999_999, 10.0))
    }

    @Test
    fun satsBecomeBahtRoundedOrCut() {
        // At 2,000,000 baht per bitcoin 1 baht is 50 sats: 49 sats are 0.98 baht, 50 are 1 and 75 are 1.5.
        val price = 2_000_000.0
        assertEquals(listOf(1L, 1L, 2L), listOf(49L, 50L, 75L).map { satsToBahtRounded(it, price) })
        assertEquals(listOf(0L, 1L, 1L), listOf(49L, 50L, 75L).map { satsToBahtFloor(it, price) })
        // 1,128 sats at the board price are 40.63 baht.
        assertEquals(41L, satsToBahtRounded(1_128, 3_602_000.0))
        assertEquals(40L, satsToBahtFloor(1_128, 3_602_000.0))
        assertEquals(0L, satsToBahtRounded(0, price))
        assertEquals(0L, satsToBahtFloor(0, price))
    }

    @Test
    fun allTheCoinsThereWillEverBeDoNotOverflow() {
        assertEquals(55_006_623_000_000L, satsToBahtRounded(2_100_000_000_000_000, 2_619_363.0))
        assertEquals(55_006_623_000_000L, satsToBahtFloor(2_100_000_000_000_000, 2_619_363.0))
    }

    @Test
    fun theRoundedBahtIsTheNumberTheBahtLinePrints() {
        listOf(2_000_000.0, 3_602_000.0, 2_621_051.11, 2_619_363.0, 50_000_000.0).forEach { price ->
            listOf(0L, 1L, 24L, 25L, 49_950L, 500_000L, 1_128L, 3_118_872L, 1_234_567L, 100_000_000L).forEach { sats ->
                assertEquals(
                    bahtText(sats, FiatQuote(price, fetchedAtSeconds = 0)),
                    "฿" + thousands(satsToBahtRounded(sats, price)),
                    "$sats sats at $price",
                )
            }
        }
    }

    @Test
    fun btcShowsFourDecimalsAtLeastAndEightAtMost() {
        assertEquals("0.0050", formatBtcCompact(500_000))
        assertEquals("0.03118872", formatBtcCompact(3_118_872))
        assertEquals("0.031188", formatBtcCompact(3_118_800))
        assertEquals("0.1230", formatBtcCompact(12_300_000))
        assertEquals("1.0000", formatBtcCompact(100_000_000))
        assertEquals("0.0000", formatBtcCompact(0))
        assertEquals("0.00000001", formatBtcCompact(1))
    }

    @Test
    fun btcIsCutAndNeverRoundedUp() {
        // 0.03118872 would round to 0.03119 and 1.99999999 to 2.0000.
        assertEquals("0.03118872", formatBtcCompact(3_118_872))
        assertEquals("1.99999999", formatBtcCompact(199_999_999))
    }

    // Every BTC amount a screen shows, typed back into the amount field, must be the same sats. Cutting to four places broke this
    // for 99.99% of amounts and hid 9,999 sats of 0.03129999 BTC (open item 74).
    @Test
    fun everyAmountShownReadsBackAsTheSameSats() {
        val format: (Long) -> String = ::formatBtcCompact
        fun lost(sats: Long): String? {
            val shown = format(sats)
            val back = parseBtc(shown)
            return if (back == sats) null else "$sats sats shown as $shown read back as $back"
        }
        // Every pattern of the last five digits, where the cut hid sats.
        val firstLoss = (1L..100_000L).firstNotNullOfOrNull(::lost)
            // Seeded, so a failure reproduces, across the whole 21 million BTC supply.
            ?: Random(74).let { r -> (1..20_000).firstNotNullOfOrNull { lost(r.nextLong(1L, MAX_SUPPLY_SATS + 1)) } }
            ?: listOf(9_999L, 19_436L, 3_129_999L, 99_999_999L, 100_000_000L, 100_000_001L, 199_999_999L, MAX_SUPPLY_SATS)
                .firstNotNullOfOrNull(::lost)
        assertNull(firstLoss)
        assertEquals("0.0000", formatBtcCompact(0), "nothing reads as nothing")
    }
}

/** 21 million BTC, the most that can ever exist, in sats. */
private const val MAX_SUPPLY_SATS = 2_100_000_000_000_000L
