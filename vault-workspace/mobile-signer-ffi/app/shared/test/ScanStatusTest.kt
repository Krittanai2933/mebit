package com.mebit.app.feature.send

import com.mebit.app.data.SendIssue
import com.mebit.app.data.SingleSigWallet.WalletMode
import com.mebit.app.data.figuresKnown
import com.mebit.app.data.scanErrorThai
import com.mebit.app.data.sendErrorThai
import com.mebit.app.data.sendIssue
import com.mebit.ffi.signer.HotScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What Home and Send make of how far the scan behind a snapshot got. */
class ScanStatusTest {
    // The user's rule is that no sum of part of the wallet ever shows as its balance, or they may think money is missing.
    @Test
    fun aLiveWalletShowsItsBalanceOnlyOnceItsScanIsComplete() {
        assertEquals("0.0004 BTC", homeBalanceText(40_000, figuresKnown(WalletMode.Live, HotScan.COMPLETE), loading = false))
        for (scan in listOf(HotScan.CONTINUING, HotScan.UNVERIFIED)) {
            assertEquals("—", homeBalanceText(40_000, figuresKnown(WalletMode.Live, scan), loading = false), "$scan")
        }
        for (scan in HotScan.entries) {
            assertEquals("—", homeBalanceText(40_000, figuresKnown(WalletMode.Unavailable, scan), loading = false), "a failed snapshot, $scan")
        }
        assertTrue(figuresKnown(WalletMode.Demo, HotScan.COMPLETE), "the demo fixture's own figures")
    }

    @Test
    fun onlyAScanThatGoesOnShowsTheCalmLineAndAsksAgainByItself() {
        assertEquals("กำลังค้นหาประวัติของกระเป๋า · จะค้นต่อให้อัตโนมัติ", homeScanLine(WalletMode.Live, HotScan.CONTINUING))
        assertTrue(scanContinues(WalletMode.Live, HotScan.CONTINUING))
        for (mode in WalletMode.entries) for (scan in HotScan.entries) {
            if (mode == WalletMode.Live && scan == HotScan.CONTINUING) continue
            assertNull(homeScanLine(mode, scan), "$mode $scan")
            assertFalse(scanContinues(mode, scan), "$mode $scan")
        }
    }

    // A host that refused with its request limit is left alone for the wait it asked for, or a minute, so Home asks a minute
    // later, then waits twice as long each time, and never longer than a quarter of an hour.
    @Test
    fun homeAsksAgainAfterAMinuteThenWaitsTwiceAsLongUpToAQuarterOfAnHour() {
        assertEquals(listOf(60L, 120L, 240L, 480L, 900L, 900L), (0..5).map { scanContinueDelayMillis(it) / 1_000 })
        assertEquals(900_000L, scanContinueDelayMillis(Int.MAX_VALUE), "no overflow however long it goes on")
    }

    @Test
    fun aScanAHostLiedInShowsTheRedLineOfAPaymentItRefusedAndNoOtherScanDoes() {
        assertEquals("ประวัติของกระเป๋านี้ผิดปกติ ตรวจกับ explorer ก่อนส่ง", scanErrorThai(HotScan.UNVERIFIED))
        assertEquals(sendErrorThai("invalid PSBT json: esplora scan: receive index 19: tb1qexample: transaction 1111 does not pay it"), scanErrorThai(HotScan.UNVERIFIED))
        assertNull(scanErrorThai(HotScan.COMPLETE))
        assertNull(scanErrorThai(HotScan.CONTINUING), "a scan that goes on is no error, Home says so in its calm line")
    }

    @Test
    fun sendStaysOffUnlessTheScanIsComplete() {
        for (scan in HotScan.entries) {
            val issue = sendIssue(addressOk = true, amountSats = 10_000, feeSats = 1_128, balanceSats = 1_000_000, balanceKnown = figuresKnown(WalletMode.Live, scan))
            if (scan == HotScan.COMPLETE) assertNull(issue, "$scan") else assertEquals(SendIssue.BalanceUnavailable, issue, "$scan")
        }
    }
}
