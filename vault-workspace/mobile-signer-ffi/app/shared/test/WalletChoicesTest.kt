package com.mebit.app.feature.send

import com.mebit.app.Place
import com.mebit.app.data.CreatedVault
import com.mebit.app.data.CustodyPolicy
import com.mebit.app.data.FiatQuote
import com.mebit.app.data.PUBLIC_JADE_WALLET
import com.mebit.ffi.signer.WalletKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WalletChoicesTest {
    private val first = vault("vault_1")
    private val second = vault("vault_2")
    private val p1 = Place.FIRST
    private val p3 = Place.parse(3)!!

    @Test
    fun phoneWalletsInPlaceOrderThenHardwareWalletsThenSeveralKeyWalletsEachInTheOrderMade() {
        assertEquals(
            listOf(
                WalletChoice.Spending(p1), WalletChoice.Spending(p3),
                WalletChoice.Hardware(trezor), WalletChoice.Hardware(jade),
                WalletChoice.Vault(first), WalletChoice.Vault(second),
            ),
            walletChoices(spending = listOf(p1, p3), hardware = listOf(trezor, jade), vaults = listOf(first, second)),
        )
    }

    @Test
    fun withoutAKeyOnlyTheVaultsShow() {
        assertEquals(listOf(WalletChoice.Vault(first), WalletChoice.Vault(second)), walletChoices(emptyList(), emptyList(), listOf(first, second)))
    }

    @Test
    fun noWalletsGiveAnEmptyList() {
        assertTrue(walletChoices(emptyList(), emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun aVaultRowShowsItsBalanceAndBahtOnceKnownAndADashUntilThen() {
        val quote = FiatQuote(thbPerBtc = 3_602_257.0, fetchedAtSeconds = 0)
        assertEquals(WalletLine("vault_1", "0.8420 BTC", "≈ ฿3,033,100"), vaultLine(first, 84_200_000, quote), "Penpot 4.2 and 4.7")
        val unknown = vaultLine(first, null, quote)
        assertEquals("—", unknown.balance)
        assertNull(unknown.baht, "no baht for a figure nobody knows")
    }

    private val trezor = PUBLIC_JADE_WALLET.copy(id = "hw_1", name = "Trezor", kind = WalletKind.TREZOR_SAFE7)
    private val jade = PUBLIC_JADE_WALLET.copy(id = "hw_2")

    private fun vault(id: String) = CreatedVault(
        id = id,
        name = id,
        m = 2,
        n = 3,
        policy = CustodyPolicy.PersonalCustody,
        address = "tb1q$id",
        bsms = "",
        keyFingerprints = listOf("73c5da0a", "2f1a9b3c", "e4d5c6b7"),
    )
}
