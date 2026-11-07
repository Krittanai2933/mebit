package com.mebit.app.data

import com.mebit.ffi.signer.AddressType
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.SoftwareAccountKey
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Penpot 1.5.1: the hot wallet's address type, chosen in first setup and saved with the wallet. */
class AddressTypeChoiceTest {
    @Test
    fun aNewWalletsChoiceReplacesTheSavedCopyOnce() {
        val store = FakeSecureStore()
        val session = session(store)
        session.generateThisDevice()
        assertEquals(AddressType.NATIVE_SEGWIT, session.addressType, "the preselected type")
        session.chooseAddressType(AddressType.TAPROOT)
        assertEquals(listOf("Create", "Replace"), store.modes(), "the choice must replace the copy saved at creation")
        assertEquals(AddressType.TAPROOT, store.savedCopy().addressType)
        session.chooseAddressType(AddressType.TAPROOT)
        assertEquals(2, store.writes.size, "the same type wrote again")
    }

    @Test
    fun anImportWithoutPassphraseChoosesToo() {
        val store = FakeSecureStore()
        val session = session(store)
        session.restoreFromWords(PUBLIC_WORDS_24)
        session.chooseAddressType(AddressType.LEGACY)
        assertEquals(listOf("Create", "Replace"), store.modes())
        assertEquals(AddressType.LEGACY, store.savedCopy().addressType)
    }

    @Test
    fun aStagedImportKeepsTheChoiceInMemoryUntilItsFingerprintIsAccepted() {
        val store = FakeSecureStore()
        val session = session(store)
        session.restoreFromWords(PUBLIC_WORDS_24, "a passphrase", accepted = false)
        session.chooseAddressType(AddressType.NESTED_SEGWIT)
        assertEquals(0, store.writes.size, "a staged import was written before its fingerprint matched")
        session.acceptStagedKey()
        assertEquals(listOf("Create"), store.modes())
        assertEquals(AddressType.NESTED_SEGWIT, store.savedCopy().addressType)
    }

    @Test
    fun aRejectedImportForgetsTheChoice() {
        val session = session(FakeSecureStore())
        session.restoreFromWords(PUBLIC_WORDS_24, "a passphrase", accepted = false)
        session.chooseAddressType(AddressType.TAPROOT)
        session.clear()
        assertEquals(AddressType.NATIVE_SEGWIT, session.addressType)
    }

    @Test
    fun aPhoneThatSavesNothingKeepsTheChoiceInMemory() {
        val session = OnboardingSession(newKey = { p, _, _ -> key(p) }, restoreKey = { _, p, _, _ -> key(p) }, forgetScans = {})
        session.generateThisDevice()
        session.chooseAddressType(AddressType.TAPROOT)
        assertEquals(AddressType.TAPROOT, session.addressType)
    }

    @Test
    fun theTypeIsFinalOnceHomeShowedTheWalletOrForAWalletLoadedAtStart() {
        val store = FakeSecureStore()
        val shown = session(store)
        shown.generateThisDevice()
        shown.endFirstSetup()
        shown.chooseAddressType(AddressType.TAPROOT)
        assertEquals(AddressType.NATIVE_SEGWIT, shown.addressType, "a wallet Home has shown changed its type")
        assertEquals(1, store.writes.size, "a wallet Home has shown was rewritten")

        val loaded = session(FakeSecureStore())
        assertTrue(loaded.install(store.savedCopy(), ANY_VERSION), "install")
        loaded.chooseAddressType(AddressType.TAPROOT)
        assertEquals(AddressType.NATIVE_SEGWIT, loaded.addressType, "a wallet loaded at start changed its type")
    }

    @Test
    fun aStaleSessionWritesNoChoice() {
        val store = FakeSecureStore()
        val a = session(store)
        a.generateThisDevice()
        assertTrue(session(store).forgetSavedWallet(), "B forgets")
        a.markBackupConfirmed() // the Conflict
        val writes = store.writes.size
        a.chooseAddressType(AddressType.TAPROOT)
        assertEquals(AddressType.NATIVE_SEGWIT, a.addressType)
        assertEquals(writes, store.writes.size)
    }

    @Test
    fun aRestartKeepsTheType() {
        val store = FakeSecureStore()
        session(store).apply {
            generateThisDevice()
            chooseAddressType(AddressType.NESTED_SEGWIT)
        }
        val loaded = session(FakeSecureStore())
        assertTrue(loaded.install(store.savedCopy(), ANY_VERSION), "install")
        assertEquals(AddressType.NESTED_SEGWIT, loaded.addressType)
    }

    @Test
    fun aFailedReplaceOfTheTypeIsCaughtAtAppLock() {
        val store = FakeSecureStore(failWrite = 2)
        val session = session(store)
        session.generateThisDevice()
        session.chooseAddressType(AddressType.TAPROOT)
        assertTrue(session.lastSaveFailed, "a failed Replace went unreported")
        assertEquals(AddressType.NATIVE_SEGWIT, store.savedCopy().addressType, "the earlier copy is not on the phone")
        assertIs<UnlockResult.Broken>(runBlocking { session.proveSavedCopy("test") }, "App Lock accepted a copy of another type")
        assertEquals(SaveState.NotSaved, session.saved)
    }

    @Test
    fun aReImportAfterAStagedChoiceStartsFromNativeSegwit() {
        val store = FakeSecureStore()
        val session = session(store)
        session.restoreFromWords(PUBLIC_WORDS_24, "one", accepted = false)
        session.chooseAddressType(AddressType.LEGACY)
        session.restoreFromWords(PUBLIC_WORDS_24) // back to Import Seed and typed again, with no passphrase
        assertEquals(AddressType.NATIVE_SEGWIT, session.addressType, "the preselected type is not Native SegWit")
        assertEquals(AddressType.NATIVE_SEGWIT, store.savedCopy().addressType, "the saved copy took the earlier staged choice")
    }

    @Test
    fun aPassphraseChangeAfterTheChoiceKeepsIt() {
        val store = FakeSecureStore()
        val session = session(store)
        session.generateThisDevice()
        session.chooseAddressType(AddressType.TAPROOT)
        session.changeNewKeyPassphrase("a passphrase")
        assertEquals(AddressType.TAPROOT, session.addressType, "the passphrase change dropped the choice")
        assertEquals(AddressType.TAPROOT, store.savedCopy().addressType, "the saved copy dropped the choice")
    }

    // On mainnet only Native SegWit can be chosen: it is preselected, and a tap on any other type leaves it. The same tap on testnet chooses.
    @Test
    fun onMainnetATapOnATypeTheCoreRefusesChangesNothing() {
        val main = session(FakeSecureStore())
        main.generateThisDevice(network = KeyNetwork.MAIN)
        assertEquals(AddressType.NATIVE_SEGWIT, main.addressType, "the preselected type on mainnet")
        for (type in listOf(AddressType.LEGACY, AddressType.NESTED_SEGWIT, AddressType.TAPROOT)) {
            main.chooseAddressType(type)
            assertEquals(AddressType.NATIVE_SEGWIT, main.addressType, "$type on mainnet")
        }
        val test = session(FakeSecureStore())
        test.generateThisDevice()
        test.chooseAddressType(AddressType.TAPROOT)
        assertEquals(AddressType.TAPROOT, test.addressType, "the same tap on testnet")
    }

    private fun session(store: FakeSecureStore) = OnboardingSession(
        SavedWallet(store),
        newKey = { p, _, _ -> key(p) },
        restoreKey = { _, p, _, _ -> key(p) },
        forgetScans = {},
    )

    private fun key(passphrase: String) =
        SoftwareAccountKey(PUBLIC_WORDS_24, if (passphrase.isEmpty()) TEST_CHECK else "5a5a5a5a", "tpub-test", "m/84'/1'/0'")

    private fun FakeSecureStore.savedCopy(): WalletRecord = assertIs<UnlockResult.Opened>(SavedWallet(this).openNow(), "the saved copy").record
}
