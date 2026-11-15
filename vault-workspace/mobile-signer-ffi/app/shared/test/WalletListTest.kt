package com.mebit.app.feature.vault

import com.mebit.app.Place
import com.mebit.app.data.CreatedVault
import com.mebit.app.data.CustodyPolicy
import com.mebit.app.data.FakeVaultsStorage
import com.mebit.app.data.PUBLIC_JADE_WALLET
import com.mebit.app.data.SavedVaults
import com.mebit.app.data.VaultOwners
import com.mebit.app.ui.components.DeleteItem
import com.mebit.ffi.signer.WalletKind
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

    // The independent review's probe: a book saved before Choose Keys refused a repeat can list one wallet twice, and the total read 1.6840 BTC.
    @Test
    fun aWalletTheBookListsTwiceCountsOnceInTheTotal() {
        val vault = CreatedVault("vault_1", "Wallet 1", 2, 3, CustodyPolicy.PersonalCustody, "tb1qvault1", "", emptyList())
        val book = SavedVaults(null, owners = { null }, deriveAddress = { _, _ -> "" })
        book.edit { it.copy(vaults = listOf(vault, vault.copy(id = "vault_2", name = "Wallet 2"))) }
        assertEquals(2, book.current.vaults.size, "two cards of one wallet")
        val sats: (CreatedVault) -> Long? = { if (it.address == vault.address) 84_200_000L else null }
        assertEquals("0.8420 BTC", listTotal(emptyList(), book, sats, walletUnopened = false))
    }

    // The independent review's probe: beside a book this run cannot read, the one-key wallet's 0.0300 BTC read as all of the money.
    @Test
    fun theTotalIsADashWhileTheBookOfSeveralKeyWalletsCannotBeRead() {
        val owner = VaultOwners(listOf("aaaa0000"), everyPlaceOpened = true)
        val books = mapOf(
            "a newer app's book" to SavedVaults(FakeVaultsStorage("MV3\n"), { owner }, { _, _ -> "" }).apply { sync() },
            "a damaged book" to SavedVaults(FakeVaultsStorage("not a book"), { owner }, { _, _ -> "" }).apply { sync() },
            "a book not read yet" to SavedVaults(FakeVaultsStorage(""), { owner }, { _, _ -> "" }),
        )
        for ((what, book) in books) {
            assertEquals("—", listTotal(listOf(card(3_000_000)), book, { null }, walletUnopened = false), "$what (${book.health})")
        }
    }

    @Test
    fun aBookReadWithNoSeveralKeyWalletKeepsTheOneKeyTotal() {
        val book = SavedVaults(FakeVaultsStorage(""), { VaultOwners(listOf("aaaa0000"), everyPlaceOpened = true) }, { _, _ -> "" }).apply { sync() }
        assertEquals("0.0300 BTC", listTotal(listOf(card(3_000_000)), book, { null }, walletUnopened = false))
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

    private fun jade(sats: Long, known: Boolean = true) = HardwareCard(PUBLIC_JADE_WALLET, "Jade ของฉัน", sats, known, DeleteItem.Offered)

    // Penpot 1.8.1: 0.0312 on the phone wallet and 0.0150 on กระเป๋า Jade read 0.0462 in all.
    @Test
    fun theTotalAddsTheHardwareWalletsAsOneKeyWallets() {
        assertEquals("0.0462 BTC", walletsTotal(listOf(card(3_120_000)), vaults = emptyList(), walletUnopened = false, hardware = listOf(jade(1_500_000))))
        assertEquals("0.0150 BTC", walletsTotal(emptyList(), vaults = emptyList(), walletUnopened = false, hardware = listOf(jade(1_500_000))), "a phone with only a Jade wallet")
        assertEquals("—", walletsTotal(listOf(card(3_120_000)), vaults = emptyList(), walletUnopened = false, hardware = listOf(jade(0, known = false))), "a Jade not read yet")
        assertEquals("0.8882 BTC", walletsTotal(listOf(card(3_120_000)), vaults = listOf(84_200_000), walletUnopened = false, hardware = listOf(jade(1_500_000))), "beside a several-key wallet, which 1.8.0 adds in")
    }

    @Test
    fun aHardwareWalletsRemovalSaysTheDeviceKeepsTheKey() {
        assertEquals("กระเป๋าจะถูกลบออกจากเครื่องนี้ · Jade ยังเก็บกุญแจนี้ไว้", com.mebit.app.feature.send.removeHardwareBody(WalletKind.JADE))
        assertEquals("กระเป๋าจะถูกลบออกจากเครื่องนี้ · Trezor ยังเก็บกุญแจนี้ไว้", com.mebit.app.feature.send.removeHardwareBody(WalletKind.TREZOR_SAFE7))
        assertEquals("ลบกระเป๋า Jade ออกจากเครื่องนี้ไหม", com.mebit.app.feature.send.removeSheetTitle("กระเป๋า Jade"), "1.8.8's title")
    }
}
