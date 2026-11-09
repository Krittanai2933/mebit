package com.mebit.app.feature.send

import com.mebit.app.data.CreatedVault
import com.mebit.app.data.CustodyPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WalletChoicesTest {
    private val first = vault("vault_1")
    private val second = vault("vault_2")

    @Test
    fun theSpendingWalletComesFirstThenTheVaultsInOrder() {
        assertEquals(
            listOf(WalletChoice.Spending, WalletChoice.Vault(first), WalletChoice.Vault(second)),
            walletChoices(hasSpendingKey = true, vaults = listOf(first, second)),
        )
    }

    @Test
    fun withoutAKeyOnlyTheVaultsShow() {
        assertEquals(listOf(WalletChoice.Vault(first), WalletChoice.Vault(second)), walletChoices(false, listOf(first, second)))
    }

    @Test
    fun noWalletsGiveAnEmptyList() {
        assertTrue(walletChoices(false, emptyList()).isEmpty())
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
