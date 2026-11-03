package com.mebit.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AmountEntryTest {
    // At this price 500,000 sats are exactly 18,010 baht and 1,128 sats are 40.63, the numbers on boards 4.4 and 4.5.
    private val boardPrice = FiatQuote(3_602_000.0, fetchedAtSeconds = 0)
    private val address = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"

    private fun fresh(): Pair<SendDraft, AmountEntry> = SendDraft().let { it to AmountEntry(it) }

    @Test
    fun pickingAUnitNeverChangesTheSatsOrTheBtcText() {
        val (draft, entry) = fresh()
        draft.setAmountBtc("0.0050")
        repeat(3) {
            entry.pickUnit(AmountUnit.Thb, boardPrice)
            assertEquals("18010", entry.fieldText(boardPrice))
            entry.pickUnit(AmountUnit.Btc, boardPrice)
            assertEquals("0.0050", entry.fieldText(boardPrice))
        }
        assertEquals(500_000L, draft.amountSats)
        assertEquals("0.0050", draft.amountText)
    }

    @Test
    fun anAmountThatIsNotWholeBahtIsNotRewrittenByTheToggle() {
        // 123,457 sats are 3,235.87 baht at this price. Read back from the 3,236 shown they would be 123,462.
        val price = FiatQuote(2_621_051.11, fetchedAtSeconds = 0)
        val (draft, entry) = fresh()
        draft.setAmountBtc("0.00123457")
        repeat(5) {
            entry.pickUnit(AmountUnit.Thb, price)
            assertEquals("3236", entry.fieldText(price))
            entry.pickUnit(AmountUnit.Btc, price)
        }
        assertEquals(123_457L, draft.amountSats)
        assertEquals("0.00123457", draft.amountText)
    }

    @Test
    fun typingBahtSetsTheSatsByThePriceAndWritesTheBtcText() {
        val (draft, entry) = fresh()
        entry.pickUnit(AmountUnit.Thb, boardPrice)
        entry.typeBaht("18010", boardPrice)
        assertEquals(500_000L, draft.amountSats)
        assertEquals("0.005", draft.amountText)
        assertEquals("18010", entry.fieldText(boardPrice))
        assertEquals("≈ 0.0050 BTC", entry.otherLine(boardPrice))

        // 2 baht at 3,000,000 per bitcoin are 66.67 sats and 3 baht are exactly 100.
        val cheap = FiatQuote(3_000_000.0, fetchedAtSeconds = 0)
        entry.typeBaht("2", cheap)
        assertEquals(67L, draft.amountSats)
        assertEquals("0.00000067", draft.amountText)
        entry.typeBaht("3", cheap)
        assertEquals(100L, draft.amountSats)
        assertEquals("0.000001", draft.amountText)

        entry.typeBaht("", cheap)
        assertEquals(0L, draft.amountSats)
        assertEquals("", draft.amountText)
    }

    @Test
    fun whatWasTypedInBahtReadsBackAsTyped() {
        listOf(1_000.0, 2_621_051.11, 3_602_000.0, 50_000_000.0, 99_999_999.0).forEach { price ->
            val quote = FiatQuote(price, fetchedAtSeconds = 0)
            listOf("1", "2", "3", "41", "999", "18010", "112349", "999999999").forEach { typed ->
                val (_, entry) = fresh()
                entry.pickUnit(AmountUnit.Thb, quote)
                entry.typeBaht(typed, quote)
                assertEquals(typed, entry.fieldText(quote), "$typed baht at $price")
            }
        }
    }

    @Test
    fun withoutAPriceThereIsNoBahtAtAll() {
        val (draft, entry) = fresh()
        draft.setAmountBtc("0.01")
        entry.pickUnit(AmountUnit.Thb, null)
        assertEquals(AmountUnit.Btc, entry.unit)
        entry.typeBaht("500", null)
        assertEquals(1_000_000L, draft.amountSats)
        assertEquals("0.01", draft.amountText)
        assertFalse(entry.inBaht(null))
        assertEquals("0.01", entry.fieldText(null))
        assertNull(entry.otherLine(null))
        assertEquals("ใช้ได้ 0.0312 BTC", entry.caption(3_129_999, null))

        // A unit picked while a price existed shows BTC once the price is gone.
        entry.pickUnit(AmountUnit.Thb, boardPrice)
        assertTrue(entry.inBaht(boardPrice))
        assertFalse(entry.inBaht(null))
        assertEquals("0.01", entry.fieldText(null))
        assertEquals("ใช้ได้ 0.0312 BTC", entry.caption(3_129_999, null))
    }

    @Test
    fun sendAllInBahtLeavesTheSatsExactAndShowsTheBahtCutDown() {
        val (draft, entry) = fresh()
        entry.pickUnit(AmountUnit.Thb, boardPrice)
        entry.sendAll(3_120_000)
        // The balance less the normal fee of 1,128 sats, to the sat.
        assertEquals(3_118_872L, draft.amountSats)
        assertEquals("0.03118872", draft.amountText)
        // That is 112,341.77 baht, cut to 112,341 where rounding would give 112,342.
        assertEquals("112341", entry.fieldText(boardPrice))
        assertEquals("≈ 0.03118872 BTC", entry.otherLine(boardPrice))
        assertTrue(entry.isSendAll)
        assertEquals("ส่งยอดทั้งหมด · หักค่าธรรมเนียมแล้ว", entry.caption(3_120_000, boardPrice))

        entry.pickUnit(AmountUnit.Btc, boardPrice)
        assertEquals(3_118_872L, draft.amountSats)
        assertEquals("0.03118872", entry.fieldText(boardPrice))
        assertEquals("≈ ฿112,342", entry.otherLine(boardPrice))
        assertEquals("ส่งยอดทั้งหมด · หักค่าธรรมเนียมแล้ว", entry.caption(3_120_000, boardPrice))
    }

    @Test
    fun sendAllInBtcIsTheSameSats() {
        val (draft, entry) = fresh()
        entry.sendAll(3_120_000)
        assertEquals(3_118_872L, draft.amountSats)
        assertEquals("0.03118872", entry.fieldText(null))
        assertEquals("ส่งยอดทั้งหมด · หักค่าธรรมเนียมแล้ว", entry.caption(3_120_000, null))
        // Tapping it again does the same again.
        entry.sendAll(3_120_000)
        assertEquals(3_118_872L, draft.amountSats)
        assertTrue(entry.isSendAll)
    }

    @Test
    fun availableIsTheBalanceInTheUnitShownAndBahtIsCutDown() {
        val (_, entry) = fresh()
        assertEquals("ใช้ได้ 0.0312 BTC", entry.caption(3_129_999, boardPrice))
        entry.pickUnit(AmountUnit.Thb, boardPrice)
        // 3,129,999 sats are 112,742.56 baht.
        assertEquals("ใช้ได้ ฿112,742", entry.caption(3_129_999, boardPrice))
        assertEquals("ใช้ได้ ฿0", entry.caption(0, boardPrice))
    }

    @Test
    fun theOtherLineShowsTheOtherUnitOnlyForAnAmount() {
        val (draft, entry) = fresh()
        assertNull(entry.otherLine(boardPrice))
        draft.setAmountBtc("0.0050")
        assertEquals("≈ ฿18,010", entry.otherLine(boardPrice))
        assertNull(entry.otherLine(null))
        entry.pickUnit(AmountUnit.Thb, boardPrice)
        assertEquals("≈ 0.0050 BTC", entry.otherLine(boardPrice))
        assertNull(entry.otherLine(null))
        draft.setAmountBtc("")
        assertNull(entry.otherLine(boardPrice))
    }

    @Test
    fun aFeeTierChangeWorksSendAllOutAgain() {
        val (draft, entry) = fresh()
        entry.pickUnit(AmountUnit.Thb, boardPrice)
        entry.sendAll(3_120_000)
        entry.chooseTier(FeeTier.Fast, 3_120_000)
        assertEquals(FeeTier.Fast, draft.tier)
        assertEquals(3_117_885L, draft.amountSats)
        assertEquals("0.03117885", draft.amountText)
        assertEquals("112306", entry.fieldText(boardPrice))
        entry.chooseTier(FeeTier.Slow, 3_120_000)
        assertEquals(3_119_436L, draft.amountSats)
        assertTrue(entry.isSendAll)
    }

    @Test
    fun liveRatesThatArriveAfterSendAllWorkItOutAgain() {
        val (draft, entry) = fresh()
        entry.sendAll(3_120_000)
        assertEquals(3_118_872L, draft.amountSats, "the balance less 8 x 141")
        entry.applyTiers(LiveFeeTiers(1, 1, 2, live = true), 3_120_000)
        assertEquals(3_119_859L, draft.amountSats, "the balance less 1 x 141")
        assertTrue(entry.isSendAll)
        entry.typeBtc("0.01")
        entry.applyTiers(LiveFeeTiers(3, 5, 9, live = true), 3_120_000)
        assertEquals(1_000_000L, draft.amountSats, "a typed amount stays")
        assertEquals(705L, draft.feeSats)
    }

    @Test
    fun aFeeTierChangeLeavesATypedAmountAlone() {
        val (draft, entry) = fresh()
        entry.typeBtc("0.01")
        entry.chooseTier(FeeTier.Fast, 3_120_000)
        assertEquals(1_000_000L, draft.amountSats)
        assertEquals("0.01", draft.amountText)

        entry.pickUnit(AmountUnit.Thb, boardPrice)
        entry.typeBaht("5000", boardPrice)
        assertEquals(138_812L, draft.amountSats)
        entry.chooseTier(FeeTier.Slow, 3_120_000)
        assertEquals(138_812L, draft.amountSats)
        assertEquals(FeeTier.Slow.sats, draft.feeSats)
    }

    @Test
    fun typingEndsSendAllEvenWhenItRetypesTheSameAmount() {
        val (draft, entry) = fresh()
        entry.sendAll(3_120_000)
        entry.typeBtc("0.0311887")
        assertFalse(entry.isSendAll)
        entry.typeBtc("0.03118872")
        assertEquals(3_118_872L, draft.amountSats)
        assertFalse(entry.isSendAll)
        assertEquals("ใช้ได้ 0.0312 BTC", entry.caption(3_120_000, null))
        entry.chooseTier(FeeTier.Fast, 3_120_000)
        assertEquals(3_118_872L, draft.amountSats)

        entry.sendAll(3_120_000)
        entry.pickUnit(AmountUnit.Thb, boardPrice)
        entry.typeBaht("112341", boardPrice)
        assertFalse(entry.isSendAll)
        entry.chooseTier(FeeTier.Slow, 3_120_000)
        assertEquals(bahtToSats(112_341, 3_602_000.0), draft.amountSats)
    }

    @Test
    fun aPaymentLinkAmountShowsInBothUnits() {
        val (draft, entry) = fresh()
        draft.enterDestination("bitcoin:$address?amount=0.005") { true }
        assertEquals(500_000L, draft.amountSats)
        assertEquals("0.005", entry.fieldText(boardPrice))
        assertEquals("≈ ฿18,010", entry.otherLine(boardPrice))
        entry.pickUnit(AmountUnit.Thb, boardPrice)
        assertEquals("18010", entry.fieldText(boardPrice))
        assertEquals("≈ 0.0050 BTC", entry.otherLine(boardPrice))
        // A link opened while baht is shown.
        draft.enterDestination("bitcoin:$address?amount=0.01") { true }
        assertEquals("36020", entry.fieldText(boardPrice))
        assertEquals("≈ 0.0100 BTC", entry.otherLine(boardPrice))
    }

    @Test
    fun aPaymentLinkAmountEndsSendAll() {
        val (draft, entry) = fresh()
        entry.sendAll(3_120_000)
        draft.enterDestination("bitcoin:$address?amount=0.01") { true }
        assertFalse(entry.isSendAll)
        assertEquals("ใช้ได้ 0.0312 BTC", entry.caption(3_120_000, null))
        entry.chooseTier(FeeTier.Fast, 3_120_000)
        assertEquals(1_000_000L, draft.amountSats)
        assertEquals("0.01", draft.amountText)
    }

    @Test
    fun aBalanceUnderTheFeeSendsNothingAndKeepsTheNormalCaption() {
        val (draft, entry) = fresh()
        entry.sendAll(1_000)
        assertEquals(0L, draft.amountSats)
        assertEquals("", draft.amountText)
        assertFalse(entry.isSendAll)
        assertEquals("ใช้ได้ 0.00001 BTC", entry.caption(1_000, null), "a real balance never reads as 0.0000")
        // A cheaper tier leaves something, and the amount follows it because send all is still marked.
        entry.chooseTier(FeeTier.Slow, 1_000)
        assertEquals(436L, draft.amountSats)
        assertTrue(entry.isSendAll)
    }

    @Test
    fun theBahtFieldTakesNineDigitsAndNoMore() {
        val lowest = FiatQuote(1_000.0, fetchedAtSeconds = 0)
        val (draft, entry) = fresh()
        entry.pickUnit(AmountUnit.Thb, lowest)
        entry.typeBaht("999999999", lowest)
        assertEquals(99_999_999_900_000L, draft.amountSats)
        assertEquals("999999999", entry.fieldText(lowest))
        entry.typeBaht("1000000000", lowest)
        assertEquals(99_999_999_900_000L, draft.amountSats)
        assertEquals("999999999", entry.fieldText(lowest))
        // Leading zeros are not digits of the amount.
        entry.typeBaht("000000000123", lowest)
        assertEquals(12_300_000L, draft.amountSats)
    }

    @Test
    fun theBahtFieldIgnoresAnythingButDigits() {
        val (draft, entry) = fresh()
        entry.pickUnit(AmountUnit.Thb, boardPrice)
        entry.typeBaht("18010", boardPrice)
        listOf("18010.5", "18,010", "-5", "1e3", " 5", "5 ", "๕", "٥", "0x10").forEach {
            entry.typeBaht(it, boardPrice)
            assertEquals(500_000L, draft.amountSats, it)
            assertEquals("18010", entry.fieldText(boardPrice), it)
        }
    }

    @Test
    fun anAmountOver21MillionBitcoinIsIgnored() {
        // At 10 baht per bitcoin 999,999,999 baht are 99,999,999.9 bitcoin.
        val absurd = FiatQuote(10.0, fetchedAtSeconds = 0)
        val (draft, entry) = fresh()
        draft.setAmountBtc("0.5")
        entry.pickUnit(AmountUnit.Thb, absurd)
        entry.typeBaht("999999999", absurd)
        assertEquals(50_000_000L, draft.amountSats)
        assertEquals("0.5", draft.amountText)

        // At 1 baht per bitcoin 21,000,000 baht are exactly 21 million bitcoin.
        val one = FiatQuote(1.0, fetchedAtSeconds = 0)
        entry.typeBaht("21000000", one)
        assertEquals(2_100_000_000_000_000L, draft.amountSats)
        assertEquals("21000000", draft.amountText)
        entry.typeBaht("21000001", one)
        assertEquals(2_100_000_000_000_000L, draft.amountSats)
    }

    @Test
    fun theBtcFieldKeepsTodaysRules() {
        val (draft, entry) = fresh()
        entry.typeBtc("0.0050")
        assertEquals(500_000L, draft.amountSats)
        assertEquals("0.0050", draft.amountText)
        listOf("0.123456789", "abc", "1.2.3", "123456789", "-1", "1,5").forEach {
            entry.typeBtc(it)
            assertEquals("0.0050", draft.amountText, it)
        }
        entry.typeBtc("")
        assertEquals(0L, draft.amountSats)
        assertEquals("", draft.amountText)
    }
}
