package com.mebit.app.navigation

import com.mebit.app.Place
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

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
            "the detail a vault send's slip went back to, which its X closes to the list" to stops(
                p1, MovieRoute.VaultList, MovieRoute.ChooseWalletSend, MovieRoute.VaultDetail,
            ),
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

    private val wizard = stops(p1, MovieRoute.VaultList, MovieRoute.Policy, MovieRoute.ChooseKeys)

    @Test
    fun everyAddThatBeganOnChooseKeysReturnsThereWithTheWizardKept() {
        val retried = keyRetryBackStack(
            (wizard + stops(p1, MovieRoute.AddKey, MovieRoute.HardwareWallet, MovieRoute.VerifyKey, MovieRoute.T14VerifyMismatch)).map { it.route },
            MovieRoute.HardwareWallet,
        ).map { Stop(it, p1) } + Stop(MovieRoute.VerifyKey, p1)
        val adds = listOf(
            "a hardware key" to wizard + stops(p1, MovieRoute.AddKey, MovieRoute.HardwareWallet, MovieRoute.VerifyKey),
            "a pasted key" to wizard + stops(p1, MovieRoute.AddKey, MovieRoute.PairMebitOther, MovieRoute.VerifyKey),
            "a retried hardware key" to retried,
            "a new phone wallet, backed up, with App Lock" to wizard + stops(p1, MovieRoute.AddKey) +
                stops(p2, MovieRoute.KeyCreated, MovieRoute.BackupWords, MovieRoute.VerifyWords, MovieRoute.AddressType, MovieRoute.AppLock),
            "a new phone wallet, backup skipped" to wizard + stops(p1, MovieRoute.AddKey) + stops(p2, MovieRoute.KeyCreated, MovieRoute.AddressType),
            "an imported wallet" to wizard + stops(p1, MovieRoute.AddKey) + stops(p2, MovieRoute.ImportSeed, MovieRoute.AddressType),
            "a new wallet that shares the wizard's place" to wizard + stops(p1, MovieRoute.AddKey, MovieRoute.KeyCreated, MovieRoute.AddressType, MovieRoute.SpendingPin),
        )
        for ((case, stack) in adds) {
            assertEquals(wizard, stackAfterAdd(stack, MovieRoute.Keyring), "$case ends on the Keyring's rule")
            assertEquals(wizard, stackAfterAdd(stack, MovieRoute.VaultList), "$case ends on the Wallet list's rule")
        }
    }

    @Test
    fun aReturnKeepsEachStopOnItsOwnPlace() {
        val mixed = stops(p1, MovieRoute.VaultList) + stops(p2, MovieRoute.Policy, MovieRoute.ChooseKeys, MovieRoute.AddKey, MovieRoute.HardwareWallet)
        assertEquals(stops(p1, MovieRoute.VaultList) + stops(p2, MovieRoute.Policy, MovieRoute.ChooseKeys), stackAfterAdd(mixed, MovieRoute.Keyring))
    }

    @Test
    fun anAddThatBeganAnywhereElseOpensTheTabAsBefore() {
        val keyringHardware = stops(p1, MovieRoute.Keyring, MovieRoute.AddKey, MovieRoute.HardwareWallet, MovieRoute.VerifyKey)
        assertEquals(stops(p1, MovieRoute.Keyring), stackAfterAdd(keyringHardware, MovieRoute.Keyring), "the Keyring's hardware add")
        val keyringPhone = stops(p1, MovieRoute.Keyring, MovieRoute.AddKey) + stops(p2, MovieRoute.KeyCreated, MovieRoute.AddressType)
        assertEquals(stops(p2, MovieRoute.VaultList), stackAfterAdd(keyringPhone, MovieRoute.VaultList), "the Keyring's phone add")
        val firstSetup = stops(p1, MovieRoute.Welcome, MovieRoute.KeySource, MovieRoute.KeyCreated, MovieRoute.AddressType, MovieRoute.AppLock)
        assertEquals(stops(p1, MovieRoute.VaultList), stackAfterAdd(firstSetup, MovieRoute.VaultList), "a first setup")
        val hardwareStart = stops(p1, MovieRoute.Welcome, MovieRoute.KeySource, MovieRoute.HardwareWallet, MovieRoute.VerifyKey)
        assertEquals(stops(p1, MovieRoute.Keyring), stackAfterAdd(hardwareStart, MovieRoute.Keyring), "a hardware start")
        val singleSig = stops(p1, MovieRoute.VaultList, MovieRoute.Policy) + stops(p2, MovieRoute.KeyCreated, MovieRoute.AddressType, MovieRoute.AppLock)
        assertEquals(stops(p2, MovieRoute.VaultList), stackAfterAdd(singleSig, MovieRoute.VaultList), "3.1.1's single-sig start")
        val catalog = stops(p1, MovieRoute.T15KeyringEmpty, MovieRoute.AddKey, MovieRoute.HardwareWallet, MovieRoute.VerifyKey)
        assertEquals(stops(p1, MovieRoute.Keyring), stackAfterAdd(catalog, MovieRoute.Keyring), "the catalog's empty Keyring")
        val alone = stops(p1, MovieRoute.AddKey, MovieRoute.VerifyKey)
        assertEquals(stops(p1, MovieRoute.Keyring), stackAfterAdd(alone, MovieRoute.Keyring), "an Add Key with nothing under it")
    }

    @Test
    fun anAddEndsOnATabOrNotAtAll() {
        assertFailsWith<IllegalArgumentException> { stackAfterAdd(wizard, MovieRoute.Review) }
    }

    @Test
    fun theWizardIsOpenExactlyWhilePolicyIsOnTheStack() {
        val detour = wizard + stops(p1, MovieRoute.AddKey, MovieRoute.HardwareWallet)
        assertTrue(inCreateFlow(stops(p1, MovieRoute.VaultList, MovieRoute.Policy)))
        assertTrue(inCreateFlow(detour), "the Add Key detour keeps it")
        assertTrue(inCreateFlow(stackAfterAdd(detour, MovieRoute.Keyring)), "so does the return to Choose Keys")
        assertFalse(inCreateFlow(stops(p1, MovieRoute.VaultList)), "Back from Policy drops it")
        assertFalse(inCreateFlow(stackAfterOpen(detour, Stop(MovieRoute.Keyring, p1))), "a tab drops it")
        assertFalse(inCreateFlow(stackAfterOpen(detour, Stop(MovieRoute.VaultList, p1))), "the Wallet list drops it")
        assertFalse(inCreateFlow(stackAfterCreate(wizard + stops(p1, MovieRoute.ScriptType, MovieRoute.Review))), "a created wallet drops it")
    }

    @Test
    fun aVaultPaymentOutLandsOnItsSlipWhoseBackIsTheWalletsDetailWithNoSendScreenUnderIt() {
        val send = arrayOf(MovieRoute.M1SendVault, MovieRoute.M2CollectSignatures, MovieRoute.M8ReviewVault)
        // กลับหน้าหลัก and the system Back both go Back from the slip, so the stack under it is where they land.
        val slip = Stop(MovieRoute.M9SentVault, p1)
        val fromDetail = stops(p1, MovieRoute.VaultList, MovieRoute.VaultDetail, *send)
        assertEquals(stops(p1, MovieRoute.VaultList, MovieRoute.VaultDetail) + slip, stackAfterVaultSend(fromDetail), "back to the detail it began on")
        val viaReceive = stops(p1, MovieRoute.VaultList, MovieRoute.VaultDetail, MovieRoute.M1SendVault, MovieRoute.VaultReceive, *send)
        assertEquals(stops(p1, MovieRoute.VaultList, MovieRoute.VaultDetail) + slip, stackAfterVaultSend(viaReceive), "every send and Receive screen above the detail goes")
        val fromChooser = stops(p1, MovieRoute.VaultList, MovieRoute.ChooseWalletSend, *send)
        assertEquals(stops(p1, MovieRoute.VaultList, MovieRoute.ChooseWalletSend, MovieRoute.VaultDetail) + slip, stackAfterVaultSend(fromChooser), "the detail on top of what opened the send")
        assertEquals(stops(p1, MovieRoute.VaultList, MovieRoute.VaultDetail) + slip, stackAfterVaultSend(stops(p1, *send)), "the catalog's send alone")
    }

    @Test
    fun aCreatedWalletIsAloneOnTheStackAtTheWizardsPlace() {
        val review = wizard + stops(p2, MovieRoute.ScriptType, MovieRoute.Review)
        assertEquals(listOf(Stop(MovieRoute.VaultCreated, p2)), stackAfterCreate(review))
    }

    /** Opens each of [routes] in turn from [start] the way NavGraph's navigate does, and returns every stack on the way. */
    private fun walk(start: List<Stop>, vararg routes: MovieRoute): List<List<Stop>> =
        routes.toList().runningFold(start) { stack, route -> stackAfterOpen(stack, stopFor(stack.last(), route)) }

    private val oneKeySteps = arrayOf(MovieRoute.Policy, MovieRoute.ChooseKey, MovieRoute.HardwareAddressType, MovieRoute.HardwareReadKey, MovieRoute.HardwareConfirmKey)

    @Test
    fun eachOneKeySetupStepGoesOnTopAndBackReturnsToTheStepBefore() {
        val stacks = walk(stops(p1, MovieRoute.VaultList), *oneKeySteps)
        for (i in 1 until stacks.size) {
            assertEquals(stacks[i - 1], stacks[i].dropLast(1), "Back from ${stacks[i].last().route.name}")
            assertTrue(inCreateFlow(stacks[i]), "the wizard's draft, and so the pick and the type, lives on ${stacks[i].last().route.name}")
            assertEquals(null, stacks[i].last().hardware, "no setup step names a wallet yet")
        }
    }

    @Test
    fun aSavedOneKeyWalletIsAloneOnTheStackNamingItsWallet() {
        val confirm = walk(stops(p1, MovieRoute.VaultList), *oneKeySteps).last()
        val created = stackAfterHardwareCreate(confirm, "hw_2")
        assertEquals(listOf(Stop(MovieRoute.HardwareCreated, p1, "hw_2")), created)
        assertFalse(inCreateFlow(created), "nothing on the stack can reach Confirm Key and save the key again")
        assertEquals(listOf(Stop(MovieRoute.VaultList, p1)), walk(created, MovieRoute.VaultList).last(), "เข้าใช้งานกระเป๋า opens the list alone")
    }

    @Test
    fun aHardwareWalletsScreensKeepItsNameAndBackWalksThemHome() {
        val home = stackAfterOpen(stops(p1, MovieRoute.VaultList), Stop(MovieRoute.HardwareHome, p1, "hw_1"))
        val stacks = walk(home, MovieRoute.HardwareHistory, MovieRoute.HardwareTxDetail)
        assertEquals(listOf("hw_1", "hw_1", "hw_1"), stacks.map { it.last().hardware })
        assertEquals(home, stacks.last().dropLast(2), "Back twice from the detail is Home")
        val other = stackAfterOpen(stacks.last(), Stop(MovieRoute.HardwareReceive, p1, "hw_2"))
        assertEquals(stacks.last(), other.dropLast(1), "a switch to another hardware wallet is a push, and Back undoes it")
        assertEquals(listOf(Stop(MovieRoute.VaultList, p1)), walk(other, MovieRoute.VaultList).last(), "the bar's กระเป๋า drops every hardware screen")
    }

    @Test
    fun aHardwarePaymentGoesOnTopStepByStepAndItsSlipTakesThePlaceOfItsScreens() {
        val home = stackAfterOpen(stops(p1, MovieRoute.VaultList), Stop(MovieRoute.HardwareHome, p1, "hw_1"))
        val stacks = walk(home, MovieRoute.HardwareSend, MovieRoute.HardwareReview, MovieRoute.HardwareSign)
        for (i in 1 until stacks.size) {
            assertEquals(stacks[i - 1], stacks[i].dropLast(1), "Back from ${stacks[i].last().route.name}")
            assertEquals("hw_1", stacks[i].last().hardware)
        }
        val sent = stackAfterHardwareSent(stacks.last())
        assertEquals(home + Stop(MovieRoute.HardwareSent, p1, "hw_1"), sent, "Back from the slip is Home, never a screen that could sign again")
        assertEquals(home, stackAfterSlipHome(sent), "the slip's way home is Back to the Home under it")

        val chooser = stops(p1, MovieRoute.VaultList, MovieRoute.ChooseWalletSend)
        val fromChooser = stackAfterOpen(chooser, Stop(MovieRoute.HardwareSend, p1, "hw_1"))
        val sentFromChooser = stackAfterHardwareSent(walk(fromChooser, MovieRoute.HardwareReview, MovieRoute.HardwareSign).last())
        assertEquals(chooser + Stop(MovieRoute.HardwareSent, p1, "hw_1"), sentFromChooser)
        assertEquals(sentFromChooser + Stop(MovieRoute.HardwareHome, p1, "hw_1"), stackAfterSlipHome(sentFromChooser), "with no Home under it the slip opens one")
        val signAlone = listOf(Stop(MovieRoute.HardwareSign, p1, "hw_1"))
        assertEquals(listOf(Stop(MovieRoute.VaultList, p1), Stop(MovieRoute.HardwareSent, p1, "hw_1")), stackAfterHardwareSent(signAlone), "the catalog's Sign alone")
    }

    @Test
    fun aHardwareScreenStaysOnItsWalletAndNoOtherScreenCarriesOne() {
        val created = Stop(MovieRoute.HardwareCreated, p1, "hw_2")
        for (route in MovieRoute.entries) {
            assertEquals(if (route.hardware) "hw_2" else null, stopFor(created, route).hardware, route.name)
            assertEquals(null, stopFor(Stop(MovieRoute.VaultList, p1), route).hardware, "${route.name} from a phone screen")
        }
        assertEquals(p2, stopFor(created, MovieRoute.KeyCreated, p2).place, "a place given wins")
    }
}
