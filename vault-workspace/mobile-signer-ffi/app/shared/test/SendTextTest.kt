package com.mebit.app.feature.send

import com.mebit.app.data.BtcUnit
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
    fun theFeeIsSatsThenBaht() {
        assertEquals("1,128 sat · ฿41", realFeeText(1_128, boardPrice))
        assertEquals("564 sat · ฿20", realFeeText(564, boardPrice))
        assertEquals("12,345 sat · ฿445", realFeeText(12_345, boardPrice))
    }

    @Test
    fun theFeeIsJustSatsWithoutAPrice() {
        assertEquals("1,128 sat", realFeeText(1_128, null))
        assertEquals("1,234,567 sat", realFeeText(1_234_567, null))
    }

    @Test
    fun slipFeeIsBahtThenTheRate() {
        assertEquals("41 บาท · 8 sat/vB", slipFeeText(1_128, 8, boardPrice))
        assertEquals("1,235 บาท · 1 sat/vB", slipFeeText(34_285, 1, boardPrice))
        assertEquals("564 sat · 4 sat/vB", slipFeeText(564, 4, null))
        assertEquals("18,010 บาท", slipBaht(500_000, boardPrice))
        assertNull(slipBaht(500_000, null))
    }

    @Test
    fun theSlipGroupsAddressAndTxidAsTheBoard() {
        assertEquals("bc1q xy2k gdyg jrsq tzq2 n0yr\nf249 3p83 kkfj hx0w lh", groupedLines("bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh", 6))
        val txid = "e089b6d14c7a9f032e58d1a76bc40f927e3d85a1c6f04b29d8731e6c0a955a2f"
        assertEquals("e089 b6d1 4c7a 9f03 2e58 d1a7 6bc4 0f92\n7e3d 85a1 c6f0 4b29 d873 1e6c 0a95 5a2f", groupedLines(txid, 8))
    }

    @Test
    fun aZeroFeeNeedsNoPriceToBeZeroBaht() {
        assertEquals("0 sat · ฿0", realFeeText(0, null))
        assertEquals("0 sat · ฿0", realFeeText(0, boardPrice))
    }

    @Test
    fun reviewKeepsTheBalanceLessWhatLeaves() {
        // Board 4.5: 0.0312 BTC less 0.00501128 BTC taken leaves 0.02618872 BTC.
        assertEquals("0.02618872 BTC", keptText(3_120_000, 501_128))
        assertEquals("0.0000 BTC", keptText(501_128, 501_128))
        assertEquals("2,619,872 sat", keptText(3_120_000, 500_128, BtcUnit.Sat), "sat mode keeps whole sats")
    }

    @Test
    fun reviewKeepsADashWhileTheBalanceOrThePaymentIsUnknownAndNeverLessThanNothing() {
        assertEquals("—", keptText(null, 501_128))
        assertEquals("—", keptText(3_120_000, null))
        assertEquals("0.0000 BTC", keptText(400_000, 501_128), "a balance read before coins left")
    }

    @Test
    fun signaturesLeftLineNamesAnyKeysFirstThenTheCountLeft() {
        assertEquals("ใช้กุญแจดอกไหนก็ได้ 2 ดอก", signaturesLeftLine(signed = 0, m = 2, needed = 2), "board 5.2")
        assertEquals("เหลืออีก 1 ดอก", signaturesLeftLine(signed = 1, m = 2, needed = 1), "board 5.5.1")
        assertEquals("เหลืออีก 2 ดอก", signaturesLeftLine(signed = 1, m = 3, needed = 2))
    }

    @Test
    fun invalidAddressOffersScanningOnlyWithACamera() {
        assertEquals("ที่อยู่ไม่ถูกต้อง — ตรวจตัวอักษรและเครือข่าย (รองรับ Bitcoin เท่านั้น) แล้ววางหรือสแกนใหม่", invalidAddressLine(canScan = true))
        assertEquals("ที่อยู่ไม่ถูกต้อง — ตรวจตัวอักษรและเครือข่าย (รองรับ Bitcoin เท่านั้น) แล้ววางใหม่", invalidAddressLine(canScan = false))
    }
}
