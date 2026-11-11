package com.mebit.app.feature.vault

import com.mebit.app.Place
import com.mebit.app.ui.components.DeleteItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WalletListTest {
    private fun card(sats: Long, known: Boolean = true, backedUp: Boolean = true) = SpendingCard(Place.FIRST, "w", sats, known, backedUp, DeleteItem.Offered)

    // Commit f6734a2 moved every other money line to formatBtcCompact, and the first-run hero kept cutting 379,087 sats to 0.0037.
    @Test
    fun aCardShowsEverySatThatMattersAndADashUntilTheChainAnswers() {
        assertEquals("0.00379087", cardBalanceText(379_087, known = true))
        assertEquals("0.00019436", cardBalanceText(19_436, known = true))
        assertEquals("1.00009999", cardBalanceText(100_009_999, known = true), "a whole part never hides the sats")
        assertEquals("0.0125", cardBalanceText(1_250_000, known = true), "a round amount keeps four places")
        assertEquals("0.0000", cardBalanceText(0, known = true))
        assertEquals("—", cardBalanceText(379_087, known = false))
    }

    @Test
    fun theTotalAddsEveryOneKeyWallet() {
        assertEquals("0.0312 BTC", spendingTotal(listOf(card(3_000_000), card(120_000)), vaultListed = false, walletUnopened = false))
        assertEquals("0.0000 BTC", spendingTotal(listOf(card(0)), vaultListed = false, walletUnopened = false))
    }

    @Test
    fun theTotalIsADashWhileAnyWalletsBalanceIsUnknown() {
        assertEquals("—", spendingTotal(listOf(card(3_000_000), card(0, known = false)), vaultListed = false, walletUnopened = false))
    }

    // The interrogate review's probe: wallet 2 answered TryLater, so the list held wallet 1 alone and the total read its 0.0300 BTC as all of it.
    @Test
    fun theTotalIsADashWhileAWalletDidNotOpen() {
        assertEquals("—", spendingTotal(listOf(card(3_000_000)), vaultListed = false, walletUnopened = true))
        assertEquals("—", spendingTotal(emptyList(), vaultListed = false, walletUnopened = true), "a wallet waiting to open is still money")
        assertNull(spendingTotal(listOf(card(3_000_000)), vaultListed = true, walletUnopened = true), "a vault hides the line first")
    }

    @Test
    fun theTotalHidesWhileAVaultIsListedOrNoWalletIs() {
        assertNull(spendingTotal(listOf(card(3_000_000)), vaultListed = true, walletUnopened = false))
        assertNull(spendingTotal(emptyList(), vaultListed = false, walletUnopened = false))
    }

    @Test
    fun onlyAWalletThatIsNotBackedUpCarriesTheTag() {
        assertEquals("ยังไม่ได้สำรอง", card(0, backedUp = false).backupTag)
        assertNull(card(0, backedUp = true).backupTag)
    }
}
