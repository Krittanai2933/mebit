package com.mebit.app.data

import com.mebit.ffi.signer.SoftwareAccountKey
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Penpot 1.4.2: a passphrase for the key Create just made, in first setup only. */
class CreatePassphraseTest {
    @Test
    fun aPassphraseReDerivesTheNewKeyAndReplacesTheSavedCopy() {
        val store = FakeSecureStore()
        val session = session(store)
        session.generateThisDevice()
        session.changeNewKeyPassphrase(PASSPHRASE)
        assertEquals(checkFor(PASSPHRASE), session.fingerprint, "the key was not derived with the passphrase")
        assertEquals(PUBLIC_WORDS_24, session.words, "the words changed")
        assertEquals(listOf("Create", "Replace"), store.modes(), "the passphrase must replace the copy saved at creation")
        val copy = store.savedCopy()
        assertTrue(copy.passphrase == PASSPHRASE && copy.fingerprint == checkFor(PASSPHRASE) && copy.words == PUBLIC_WORDS_24, "the saved copy")
        assertEquals(SaveState.Saved, session.saved)
    }

    @Test
    fun clearingThePassphraseGivesTheOriginalKeyBack() {
        val store = FakeSecureStore()
        val session = session(store)
        session.generateThisDevice()
        val original = session.fingerprint
        session.changeNewKeyPassphrase(PASSPHRASE)
        session.changeNewKeyPassphrase("")
        assertEquals(original, session.fingerprint, "clearing did not give the original key back")
        val copy = store.savedCopy()
        assertTrue(copy.passphrase.isEmpty() && copy.fingerprint == original, "the saved copy after clearing")
        session.changeNewKeyPassphrase("")
        assertEquals(3, store.writes.size, "an unchanged passphrase wrote again")
    }

    @Test
    fun aNewPassphraseMeansTheWordsAreVerifiedAgain() {
        val store = FakeSecureStore()
        val session = session(store)
        session.generateThisDevice()
        session.markBackupConfirmed()
        session.changeNewKeyPassphrase(PASSPHRASE)
        assertFalse(session.backupConfirmed, "the new key was never verified")
        assertFalse(store.savedCopy().backupConfirmed, "the saved copy says verified")
    }

    @Test
    fun noPassphraseChangeOutsideFirstSetup() {
        val shown = FakeSecureStore()
        val afterHome = session(shown)
        afterHome.generateThisDevice()
        afterHome.endFirstSetup()
        afterHome.changeNewKeyPassphrase(PASSPHRASE)
        assertEquals(checkFor(""), afterHome.fingerprint, "a wallet Home has shown changed its key")
        assertEquals(1, shown.writes.size, "a wallet Home has shown was rewritten")

        val imported = session(FakeSecureStore())
        imported.restoreFromWords(PUBLIC_WORDS_24)
        imported.changeNewKeyPassphrase(PASSPHRASE)
        assertEquals(checkFor(""), imported.fingerprint, "an imported key changed")

        val loaded = session(FakeSecureStore())
        assertTrue(loaded.install(shown.savedCopy(), ANY_VERSION), "install")
        loaded.changeNewKeyPassphrase(PASSPHRASE)
        assertEquals(checkFor(""), loaded.fingerprint, "a wallet loaded at start changed its key")
    }

    @Test
    fun aFailedReplaceKeepsTheEarlierCopyAndTheReadBackCatchesIt() {
        val store = FakeSecureStore(failWrite = 2)
        val session = session(store)
        session.generateThisDevice()
        session.changeNewKeyPassphrase(PASSPHRASE)
        assertTrue(session.lastSaveFailed, "a failed Replace went unreported")
        assertTrue(store.savedCopy().passphrase.isEmpty(), "the earlier copy is not on the phone")
        assertIs<UnlockResult.Broken>(runBlocking { session.proveSavedCopy("test") }, "App Lock accepted a copy without the passphrase")
        assertEquals(SaveState.NotSaved, session.saved, "the wallet still says saved")
        assertEquals(PASSPHRASE, session.passphrase, "the key left memory")
    }

    @Test
    fun aRestartKeepsThePassphrase() {
        val store = FakeSecureStore()
        session(store).apply {
            generateThisDevice()
            changeNewKeyPassphrase(PASSPHRASE)
        }
        val loaded = session(FakeSecureStore())
        assertTrue(loaded.install(store.savedCopy(), ANY_VERSION), "install")
        assertEquals(PASSPHRASE, loaded.passphrase)
        assertEquals(checkFor(PASSPHRASE), loaded.fingerprint)
    }

    private fun session(store: FakeSecureStore) = OnboardingSession(
        SavedWallet(store),
        newKey = { p, _, _ -> key(PUBLIC_WORDS_24, p) },
        restoreKey = { w, p, _, _ -> key(w, p) },
        forgetScans = {},
    )

    /** Like BIP-39, the same words with another passphrase give another key. */
    private fun key(words: List<String>, passphrase: String) = SoftwareAccountKey(words, checkFor(passphrase), "tpub-test", "m/84'/1'/0'")

    private fun checkFor(passphrase: String) = if (passphrase.isEmpty()) TEST_CHECK else "5a5a5a5a"

    private fun FakeSecureStore.savedCopy(): WalletRecord = assertIs<UnlockResult.Opened>(SavedWallet(this).openNow(), "the saved copy").record
}

private const val PASSPHRASE = "correct horse"
