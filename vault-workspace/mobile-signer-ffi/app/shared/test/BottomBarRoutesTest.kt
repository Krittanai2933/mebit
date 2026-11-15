package com.mebit.app.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BottomBarRoutesTest {
    private val withBar = mapOf(
        MovieRoute.S1HomeSingleSig to BarTab.Wallets,
        // Penpot 4.1.5 draws a hardware wallet's Home with the bar, กระเป๋า lit, as the phone wallet's.
        MovieRoute.HardwareHome to BarTab.Wallets,
        MovieRoute.VaultList to BarTab.Wallets,
        MovieRoute.VaultDetail to BarTab.Wallets,
        MovieRoute.Keyring to BarTab.Keys,
        MovieRoute.AddKey to BarTab.Keys,
        MovieRoute.T15KeyringEmpty to BarTab.Keys,
        MovieRoute.T15UnverifiedFirst to BarTab.Keys,
        MovieRoute.T20VaultActions to BarTab.None,
    )

    @Test
    fun exactlyTheseRoutesShowTheBarWithTheirTab() {
        val actual = MovieRoute.entries.filter { it.bar != null }.associateWith { it.bar }
        assertEquals(withBar, actual)
    }

    @Test
    fun noBarBeforeAWalletExistsOrOnReceiveAndSend() {
        listOf(
            MovieRoute.Welcome,
            MovieRoute.KeySource,
            MovieRoute.ImportSeed,
            MovieRoute.S3Receive,
            MovieRoute.HardwareReceive,
            MovieRoute.ChooseWalletReceive,
            MovieRoute.ChooseWalletSend,
        ).forEach { assertNull(it.bar, it.name) }
    }

    @Test
    fun theBarsTwoTabsAreTheOnlyScreensThatClearTheStack() {
        assertEquals(listOf(MovieRoute.Keyring, MovieRoute.VaultList), MovieRoute.entries.filter { it.tab })
    }
}
