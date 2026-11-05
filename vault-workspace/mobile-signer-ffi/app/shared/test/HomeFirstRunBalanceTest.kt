package com.mebit.app.feature.onboarding

import com.mebit.app.data.SingleSigWallet.WalletMode
import com.mebit.ffi.signer.HotScan
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeFirstRunBalanceTest {
    // Commit f6734a2 moved every other money line to formatBtcCompact, and this hero kept cutting 379,087 sats to 0.0037.
    @Test
    fun theFirstRunHeroShowsEverySatThatMattersAndADashUntilTheChainAnswers() {
        assertEquals("0.00379087", heroBalanceText(379_087, known = true))
        assertEquals("0.00019436", heroBalanceText(19_436, known = true))
        assertEquals("1.00009999", heroBalanceText(100_009_999, known = true), "a whole part never hides the sats")
        assertEquals("0.0125", heroBalanceText(1_250_000, known = true), "a round amount keeps four places")
        assertEquals("0.0000", heroBalanceText(0, known = true))
        assertEquals("—", heroBalanceText(379_087, known = false))
    }

    // The user's rule holds on this Home too. No sum of part of the wallet shows as the total while the scan goes on or after a host lied.
    @Test
    fun theFirstRunHeroShowsALiveWalletsTotalOnlyOnceItsScanIsComplete() {
        for (mode in WalletMode.entries) for (scan in HotScan.entries) {
            val shown = mode == WalletMode.Live && scan == HotScan.COMPLETE
            assertEquals(if (shown) "0.0040" else "—", heroBalanceText(400_000, heroKnows(mode, scan)), "$mode $scan")
        }
    }
}
