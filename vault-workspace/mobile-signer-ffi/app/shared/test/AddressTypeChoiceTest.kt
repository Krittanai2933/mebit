package com.mebit.app.data

import com.mebit.ffi.signer.AddressType
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.SoftwareAccountKey
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    // Home comes only after the user confirmed the type, so a wallet Home showed, or one loaded with its type confirmed, keeps it.
    @Test
    fun theTypeIsFinalOnceHomeShowedTheWalletOrForAWalletLoadedAtStart() {
        val store = FakeSecureStore()
        val shown = session(store)
        shown.generateThisDevice()
        shown.confirmAddressType()
        shown.endFirstSetup()
        shown.chooseAddressType(AddressType.TAPROOT)
        assertEquals(AddressType.NATIVE_SEGWIT, shown.addressType, "a wallet Home has shown changed its type")
        assertEquals(2, store.writes.size, "a wallet Home has shown was rewritten")

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

    // A restart during Address Type must not finalize Native SegWit. A new key is saved with its type pending, each tap saves its type
    // pending, and only leaving the step forward saves it confirmed, once.
    @Test
    fun aNewKeyIsSavedWithItsTypePendingEachTapStaysPendingAndTheConfirmIsSavedOnce() {
        val store = FakeSecureStore()
        val session = session(store)
        session.generateThisDevice()
        assertEquals(AddressType.NATIVE_SEGWIT to false, store.savedType(), "the key's first save")
        session.chooseAddressType(AddressType.TAPROOT)
        assertEquals(AddressType.TAPROOT to false, store.savedType(), "a tap")
        session.confirmAddressType()
        assertEquals(AddressType.TAPROOT to true, store.savedType(), "the confirm")
        val writes = store.writes.size
        session.confirmAddressType()
        assertEquals(writes, store.writes.size, "a second confirm wrote again")
    }

    // An import without a passphrase is saved pending as a new key is. A passphrase import is written once, when its fingerprint is
    // accepted, already confirmed because the screen confirms the type first.
    @Test
    fun anImportIsSavedPendingAndAStagedOneIsWrittenOnceAlreadyConfirmed() {
        val plain = FakeSecureStore()
        session(plain).restoreFromWords(PUBLIC_WORDS_24)
        assertEquals(AddressType.NATIVE_SEGWIT to false, plain.savedType())
        val store = FakeSecureStore()
        val staged = session(store)
        staged.restoreFromWords(PUBLIC_WORDS_24, "a passphrase", accepted = false)
        staged.chooseAddressType(AddressType.NESTED_SEGWIT)
        staged.confirmAddressType()
        assertEquals(0, store.writes.size, "a staged import was written before its fingerprint matched")
        staged.acceptStagedKey()
        assertEquals(listOf("Create"), store.modes(), "one write")
        assertEquals(AddressType.NESTED_SEGWIT to true, store.savedType())
    }

    // Another passphrase is another wallet, so the type confirmed for the first waits for the user's confirm again.
    @Test
    fun aPassphraseChangeReopensTheType() {
        val store = FakeSecureStore()
        val session = session(store)
        session.generateThisDevice()
        session.chooseAddressType(AddressType.TAPROOT)
        session.confirmAddressType()
        session.changeNewKeyPassphrase("a passphrase")
        assertEquals(AddressType.TAPROOT to false, store.savedType())
    }

    // A cold start of a wallet whose type was not confirmed comes back to first setup with the user's last tap, which can still change
    // and then be confirmed. Once confirmed, the next cold start loads the type as final.
    @Test
    fun aRestartBeforeTheConfirmKeepsTheTapAndFirstSetup() {
        val store = FakeSecureStore()
        session(store).apply {
            generateThisDevice()
            chooseAddressType(AddressType.TAPROOT)
        }
        val loaded = session(store)
        val opened = assertIs<UnlockResult.Opened>(SavedWallet(store).openNow())
        assertTrue(loaded.install(opened.record, opened.version), "install")
        assertEquals(Triple(AddressType.TAPROOT, false, true), Triple(loaded.addressType, loaded.addressTypeConfirmed, loaded.inFirstSetup))
        loaded.chooseAddressType(AddressType.NESTED_SEGWIT)
        loaded.confirmAddressType()
        assertEquals(AddressType.NESTED_SEGWIT to true, store.savedType(), "the tap and the confirm after the restart")
        val again = session(store)
        val reopened = assertIs<UnlockResult.Opened>(SavedWallet(store).openNow())
        assertTrue(again.install(reopened.record, reopened.version), "install")
        assertFalse(again.inFirstSetup, "a confirmed type reopened first setup")
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

    /** The saved copy's address type and whether the user confirmed it. */
    private fun FakeSecureStore.savedType(): Pair<AddressType, Boolean> = savedCopy().let { it.addressType to it.addressTypeConfirmed }
}
