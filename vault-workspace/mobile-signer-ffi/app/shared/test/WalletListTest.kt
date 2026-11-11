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
        assertEquals("0.0312 BTC", walletsTotal(listOf(card(3_000_000), card(120_000)), vaults = emptyList(), walletUnopened = false))
        assertEquals("0.0000 BTC", walletsTotal(listOf(card(0)), vaults = emptyList(), walletUnopened = false))
    }

    @Test
    fun theTotalAddsEveryVaultInAsPenpot180Does() {
        assertEquals("0.8882 BTC", walletsTotal(listOf(card(3_120_000), card(1_500_000)), vaults = listOf(84_200_000), walletUnopened = false))
    }

    @Test
    fun vaultsAloneStillHaveATotal() {
        assertEquals("0.8420 BTC", walletsTotal(emptyList(), vaults = listOf(84_200_000), walletUnopened = false))
        assertEquals("0.0000 BTC", walletsTotal(emptyList(), vaults = listOf(0), walletUnopened = false), "a fresh vault read to the end holds 0")
    }

    @Test
    fun theTotalIsADashWhileAnyWalletsBalanceIsUnknown() {
        assertEquals("—", walletsTotal(listOf(card(3_000_000), card(0, known = false)), vaults = emptyList(), walletUnopened = false))
    }

    @Test
    fun theTotalIsADashWhileAnyVaultIsUnknown() {
        assertEquals("—", walletsTotal(listOf(card(3_120_000)), vaults = listOf(null), walletUnopened = false), "a vault whose first read is still out")
        assertEquals("—", walletsTotal(emptyList(), vaults = listOf(84_200_000, null), walletUnopened = false))
    }

    // The interrogate review's probe: wallet 2 answered TryLater, so the list held wallet 1 alone and the total read its 0.0300 BTC as all of it.
    @Test
    fun theTotalIsADashWhileAWalletDidNotOpen() {
        assertEquals("—", walletsTotal(listOf(card(3_000_000)), vaults = emptyList(), walletUnopened = true))
        assertEquals("—", walletsTotal(emptyList(), vaults = emptyList(), walletUnopened = true), "a wallet waiting to open is still money")
        assertEquals("—", walletsTotal(listOf(card(3_000_000)), vaults = listOf(84_200_000), walletUnopened = true), "a wallet waiting to open is still money beside a vault")
    }

    @Test
    fun theTotalHidesOnlyWhileNoWalletIs() {
        assertNull(walletsTotal(emptyList(), emptyList(), walletUnopened = false))
    }

    @Test
    fun onlyAWalletThatIsNotBackedUpCarriesTheTag() {
        assertEquals("ยังไม่ได้สำรอง", card(0, backedUp = false).backupTag)
        assertNull(card(0, backedUp = true).backupTag)
    }
}
