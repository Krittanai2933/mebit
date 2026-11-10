package com.mebit.app.navigation

import com.mebit.app.Place
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class BackStackTest {
    private val p1 = Place.FIRST
    private val p2 = Place.parse(2)!!

    private fun stops(place: Place, vararg routes: MovieRoute) = routes.map { Stop(it, place) }

    @Test
    fun theWalletListAndTheKeyringAreTheOnlyEntryHoweverTheyOpen() {
        val stacks = listOf(
            "first setup" to stops(p1, MovieRoute.Welcome, MovieRoute.KeySource, MovieRoute.KeyCreated, MovieRoute.AddressType, MovieRoute.AppLock),
            "a later add" to stops(p1, MovieRoute.Keyring, MovieRoute.AddKey) + stops(p2, MovieRoute.KeyCreated, MovieRoute.AddressType),
            "a hardware start" to stops(p1, MovieRoute.Welcome, MovieRoute.KeySource, MovieRoute.HardwareWallet, MovieRoute.VerifyKey),
            "a hardware add" to stops(p1, MovieRoute.Keyring, MovieRoute.AddKey, MovieRoute.HardwareWallet, MovieRoute.VerifyKey),
            "a finished vault" to stops(p1, MovieRoute.VaultList, MovieRoute.Policy, MovieRoute.ChooseKeys, MovieRoute.Review, MovieRoute.VaultCreated),
        )
        for ((case, stack) in stacks) for (tab in MovieRoute.entries.filter { it.tab }) {
            assertEquals(listOf(Stop(tab, p2)), stackAfterOpen(stack, Stop(tab, p2)), "$case, ${tab.name}")
        }
    }

    @Test
    fun anyOtherScreenGoesOnTopOnce() {
        val base = stops(p1, MovieRoute.VaultList)
        for (route in MovieRoute.entries.filter { !it.tab }) {
            val once = stackAfterOpen(base, Stop(route, p1))
            assertEquals(base + Stop(route, p1), once, route.name)
            assertSame(once, stackAfterOpen(once, Stop(route, p1)), "${route.name} again changes nothing")
        }
    }

    @Test
    fun aFirstSetupBackupGoesOnToAddressType() {
        val verified = stops(p1, MovieRoute.Welcome, MovieRoute.KeySource, MovieRoute.KeyCreated, MovieRoute.BackupWords, MovieRoute.VerifyWords)
        assertEquals(verified + Stop(MovieRoute.AddressType, p1), stackAfterBackup(verified, inFirstSetup = true), "verified")
        val skipped = verified.dropLast(1)
        assertEquals(skipped + Stop(MovieRoute.AddressType, p1), stackAfterBackup(skipped, inFirstSetup = true), "skipped")
    }

    @Test
    fun aLaterBackupReturnsToTheScreenThatOpenedItWithTheBackupScreensGone() {
        val gate = stops(p1, MovieRoute.VaultList) + stops(p2, MovieRoute.S1HomeSingleSig, MovieRoute.S2ReceiveBackupGate)
        assertEquals(gate, stackAfterBackup(gate + stops(p2, MovieRoute.BackupWords, MovieRoute.VerifyWords), inFirstSetup = false), "after Verify Words")
        assertEquals(gate, stackAfterBackup(gate + stops(p2, MovieRoute.BackupWords), inFirstSetup = false), "a skip from Backup Words")
        assertEquals(gate, stackAfterBackup(gate + stops(p2, MovieRoute.BackupWords, MovieRoute.VerifyWords, MovieRoute.BackupWords), inFirstSetup = false), "a repeated run")
    }

    @Test
    fun aLaterBackupWithNothingBelowItEndsOnTheWalletList() {
        assertEquals(stops(p2, MovieRoute.VaultList), stackAfterBackup(stops(p2, MovieRoute.BackupWords), inFirstSetup = false))
        assertEquals(stops(p1, MovieRoute.VaultList), stackAfterBackup(stops(p1, MovieRoute.VerifyWords), inFirstSetup = false), "the catalog's Verify Words alone")
    }
}
