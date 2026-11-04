package com.mebit.app.feature.send

import com.mebit.ffi.signer.HotTx
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val BANGKOK = "Asia/Bangkok"

// 18 Aug 2026 14:02 in Bangkok (07:02 UTC): the first row on board 4.1.
private const val BOARD_TIME = 1_787_036_520uL

private fun tx(netSats: Long, confirmations: UInt = 6u, blockTime: ULong? = BOARD_TIME, txid: String = "tx$netSats") =
    HotTx(txid, netSats, confirmations, blockTime)

class HomeRowsTest {
    @Test
    fun theFirstRowOfTheBoardIsReadBackWhole() {
        val rows = homeRows(listOf(tx(2_000_000)), timeZoneId = BANGKOK)
        assertEquals(
            listOf(HomeTxRow(received = true, title = "รับ", date = "18 ส.ค. 2569 · 14:02", amount = "+0.0200", status = "ยืนยัน 6 บล็อก")),
            rows,
        )
    }

    @Test
    fun aReceivedAmountHasAPlusAndAtLeastFourDecimals() {
        val row = homeRows(listOf(tx(2_000_000)), timeZoneId = BANGKOK).single()
        assertTrue(row.received)
        assertEquals("รับ", row.title)
        assertEquals("+0.0200", row.amount)
    }

    @Test
    fun aSentAmountHasARealMinusNotAHyphen() {
        val row = homeRows(listOf(tx(-80_000)), timeZoneId = BANGKOK).single()
        assertFalse(row.received)
        assertEquals("ส่งออก", row.title)
        assertEquals("−0.0008", row.amount)
        assertEquals(0x2212, row.amount.first().code)
    }

    @Test
    fun dustKeepsAllEightDecimals() {
        assertEquals("+0.00001234", homeRows(listOf(tx(1_234))).single().amount)
        assertEquals("−0.00001234", homeRows(listOf(tx(-1_234))).single().amount)
    }

    @Test
    fun aSendShowsWhatLeftTheWalletFeeIncluded() {
        // 80,000 sats to the payee and a 1,128 sat fee leave the wallet together.
        assertEquals("−0.00081128", homeRows(listOf(tx(-81_128))).single().amount)
    }

    @Test
    fun aWholeBitcoinKeepsItsSign() {
        assertEquals("+1.0000", homeRows(listOf(tx(100_000_000))).single().amount)
        assertEquals("−21000000.0000", homeRows(listOf(tx(-2_100_000_000_000_000))).single().amount)
    }

    @Test
    fun aNetOfZeroCountsAsReceived() {
        val row = homeRows(listOf(tx(0))).single()
        assertTrue(row.received)
        assertEquals("รับ", row.title)
        assertEquals("+0.0000", row.amount)
    }

    @Test
    fun theStatusCountsBlocksUpToSixAndThenIsSettled() {
        fun status(confirmations: UInt) = homeRows(listOf(tx(1_000, confirmations = confirmations))).single().status
        assertEquals("รอยืนยัน", status(0u))
        assertEquals("ยืนยัน 1 บล็อก", status(1u))
        assertEquals("ยืนยัน 6 บล็อก", status(6u))
        assertEquals("ยืนยันแล้ว", status(7u))
        assertEquals("ยืนยันแล้ว", status(144u))
    }

    @Test
    fun anUnconfirmedRowWaitsForABlock() {
        val row = homeRows(listOf(tx(-50_000, confirmations = 0u, blockTime = null))).single()
        assertEquals("กำลังรอบล็อก", row.date)
        assertEquals("รอยืนยัน", row.status)
    }

    @Test
    fun aConfirmedRowWithoutATimeShowsADashInsteadOfWaiting() {
        val row = homeRows(listOf(tx(50_000, confirmations = 3u, blockTime = null))).single()
        assertEquals("—", row.date)
        assertEquals("ยืนยัน 3 บล็อก", row.status)
    }

    @Test
    fun theDateLineHasNoSuffix() {
        val date = homeRows(listOf(tx(1_000)), timeZoneId = BANGKOK).single().date
        assertEquals("18 ส.ค. 2569 · 14:02", date)
        assertFalse(date.endsWith("น."))
    }

    @Test
    fun theDateFollowsTheZoneItIsAskedFor() {
        assertEquals("18 ส.ค. 2569 · 07:02", homeRows(listOf(tx(1_000)), timeZoneId = "UTC").single().date)
    }

    @Test
    fun onlyTheFirstFourRowsAreKeptInTheOrderGiven() {
        val history = listOf(tx(1_000_000), tx(-2_000_000), tx(3_000_000), tx(-4_000_000), tx(5_000_000), tx(-6_000_000))
        assertEquals(
            listOf("+0.0100", "−0.0200", "+0.0300", "−0.0400"),
            homeRows(history, timeZoneId = BANGKOK).map { it.amount },
        )
    }

    @Test
    fun theLimitCanBeChanged() {
        val history = listOf(tx(1_000_000), tx(-2_000_000), tx(3_000_000))
        assertEquals(listOf("+0.0100", "−0.0200"), homeRows(history, limit = 2).map { it.amount })
        assertEquals(3, homeRows(history, limit = 10).size)
    }

    @Test
    fun fewerThanFourRowsAreAllShownAndNoHistoryGivesNone() {
        assertEquals(1, homeRows(listOf(tx(1_000))).size)
        assertEquals(emptyList(), homeRows(emptyList()))
    }

    @Test
    fun noRowShowsTheTxid() {
        val txid = "9f2c4b7a0d1e3f5a6b8c9d0e1f2a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c"
        val rows = homeRows(listOf(tx(-1_000, txid = txid), tx(2_000, confirmations = 0u, blockTime = null, txid = txid)))
        rows.forEach { assertFalse(it.toString().contains(txid.take(8)), it.toString()) }
    }

    // On 4 Nov 2026 a real iPhone showed 19,436 sats as 0.0001 BTC, hiding nearly half the money (open item 74).
    @Test
    fun theBalanceShowsEverySatThatMattersAndTheBoardsRoundAmountKeepsFourPlaces() {
        assertEquals("0.00019436 BTC", homeBalanceText(19_436, known = true, loading = false))
        assertEquals("0.00009999 BTC", homeBalanceText(9_999, known = true, loading = false))
        assertEquals("1.00009999 BTC", homeBalanceText(100_009_999, known = true, loading = false), "a whole part never hides the sats")
        assertEquals("0.0312 BTC", homeBalanceText(3_120_000, known = true, loading = false), "the board's own balance keeps four places")
        assertEquals("0.0000 BTC", homeBalanceText(0, known = true, loading = false))
        assertEquals("—", homeBalanceText(19_436, known = false, loading = false))
        assertEquals("—", homeBalanceText(19_436, known = true, loading = true))
    }
}
