package com.mebit.app.feature.send

import com.mebit.app.Place
import com.mebit.app.data.CreatedVault
import com.mebit.app.data.CustodyPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WalletChoicesTest {
    private val first = vault("vault_1")
    private val second = vault("vault_2")
    private val p1 = Place.FIRST
    private val p3 = Place.parse(3)!!

    @Test
    fun everyWalletComesFirstInPlaceOrderThenTheVaultsInOrder() {
        assertEquals(
            listOf(WalletChoice.Spending(p1), WalletChoice.Spending(p3), WalletChoice.Vault(first), WalletChoice.Vault(second)),
            walletChoices(spending = listOf(p1, p3), vaults = listOf(first, second)),
        )
    }

    @Test
    fun withoutAKeyOnlyTheVaultsShow() {
        assertEquals(listOf(WalletChoice.Vault(first), WalletChoice.Vault(second)), walletChoices(emptyList(), listOf(first, second)))
    }

    @Test
    fun noWalletsGiveAnEmptyList() {
        assertTrue(walletChoices(emptyList(), emptyList()).isEmpty())
    }

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
