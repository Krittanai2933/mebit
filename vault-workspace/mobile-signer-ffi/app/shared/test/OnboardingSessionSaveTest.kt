package com.mebit.app.data

import com.mebit.app.EraseResult
import com.mebit.app.ReadResult
import com.mebit.app.Why
import com.mebit.app.WriteMode
import com.mebit.app.WriteResult
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.SoftwareAccountKey
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnboardingSessionSaveTest {
    @Test
    fun generateSavesBeforeItReturns() {
        val store = FakeSecureStore()
        val session = session(store)
        session.generateThisDevice()
        assertEquals(listOf(WriteMode.Create), store.modes(), "writes when generate returned")
        assertEquals(SaveState.Saved, session.saved, "saved after generate")
        assertNull(session.notSavedBecause)
        val copy = store.savedCopy()
        assertTrue(copy.words == PUBLIC_WORDS_24 && copy.passphrase.isEmpty() && copy.fingerprint == TEST_CHECK, "the saved key")
        assertEquals(listOf(false, false, false), copy.flags(), "flags of a new key")
    }

    @Test
    fun importWithoutPassphraseSavesAtOnceAsBackedUpAndImported() {
        val store = FakeSecureStore()
        val session = session(store)
        session.restoreFromWords(PUBLIC_WORDS_12)
        assertEquals(listOf(WriteMode.Create), store.modes(), "writes when import returned")
        assertEquals(SaveState.Saved, session.saved, "saved after import")
        val copy = store.savedCopy()
        assertTrue(copy.words == PUBLIC_WORDS_12, "the saved words")
        assertEquals(listOf(true, true, false), copy.flags(), "flags of an import")
    }

    @Test
    fun importWithPassphraseWritesNothingUntilAccepted() {
        val store = FakeSecureStore()
        val session = session(store)
        session.restoreFromWords(PUBLIC_WORDS_12, TEST_PASSPHRASE)
        assertTrue(store.writes.isEmpty(), "written before the fingerprint was confirmed")
        assertEquals(SaveState.Staged, session.saved, "saved before the fingerprint was confirmed")
        assertTrue(session.words == PUBLIC_WORDS_12, "the staged words")
        session.acceptStagedKey()
        assertEquals(listOf(WriteMode.Create), store.modes(), "writes after accept")
        assertEquals(SaveState.Saved, session.saved, "saved after accept")
        assertTrue(store.savedCopy().passphrase == TEST_PASSPHRASE, "the saved passphrase")
        session.acceptStagedKey()
        assertEquals(1, store.writes.size, "a second accept wrote again")

        val rejected = FakeSecureStore()
        val other = session(rejected)
        other.restoreFromWords(PUBLIC_WORDS_12, TEST_PASSPHRASE)
        other.clear()
        assertTrue(rejected.writes.isEmpty() && rejected.erases == 0 && rejected.markerWrites.isEmpty(), "a rejected import touched the store")
        assertEquals(SaveState.NotSaved, other.saved, "saved after reject")
        assertTrue(other.words.isEmpty() && other.passphrase.isEmpty(), "a rejected import stayed in memory")
    }

    @Test
    fun flagsRideInOneRecord() {
        val store = FakeSecureStore()
        val session = session(store)
        session.generateThisDevice()
        session.markBackupConfirmed()
        assertEquals(listOf(WriteMode.Create, WriteMode.Replace), store.modes(), "writes after backup")
        assertEquals(listOf(true, false, false), store.savedCopy().flags(), "the copy after backup")
        session.markAppLockEnabled()
        assertEquals(listOf(WriteMode.Create, WriteMode.Replace, WriteMode.Replace), store.modes(), "writes after lock")
        assertEquals(listOf(true, false, true), store.savedCopy().flags(), "the copy after lock")
        assertEquals(SaveState.Saved, session.saved, "saved after the flag changes")
    }

    @Test
    fun loadSetsFlagsIndependently() {
        for (bits in 0..7) {
            val flags = listOf((bits and 1) != 0, (bits and 2) != 0, (bits and 4) != 0)
            val case = "install backup, imported, lock = $flags"
            val session = session(FakeSecureStore())
            assertTrue(session.install(testRecord(backupConfirmed = flags[0], imported = flags[1], appLockEnabled = flags[2])), case)
            assertEquals(flags, session.flags(), case)
            assertEquals(SaveState.Saved, session.saved, case)
        }
    }

    @Test
    fun hardwarePathWritesNothing() {
        val store = FakeSecureStore()
        val session = session(store)
        session.markBackupConfirmed()
        session.markAppLockEnabled()
        assertTrue(store.writes.isEmpty() && store.markerWrites.isEmpty(), "a session with no words wrote")
        assertEquals(SaveState.NotSaved, session.saved, "a wallet was inferred from a flag")
        assertTrue(session.words.isEmpty(), "words appeared")
        assertTrue(session.backupConfirmed && session.appLockEnabled, "the flags stopped changing in memory")
    }

    @Test
    fun storeUnavailableKeepsTheWalletInMemoryAndSaysWhy() {
        val store = FakeSecureStore(write = WriteResult.Unavailable(Why.NoScreenLock))
        val session = session(store)
        session.generateThisDevice()
        assertEquals(SaveState.NotSaved, session.saved, "saved on a store that refused")
        assertEquals(Why.NoScreenLock, session.notSavedBecause)
        assertTrue(session.words == PUBLIC_WORDS_24 && session.fingerprint == TEST_CHECK, "the key left memory")
        assertNull(session.lastError)
        store.write = null
        session.markBackupConfirmed()
        assertEquals(listOf(WriteMode.Create, WriteMode.Create), store.modes(), "the retry")
        assertEquals(SaveState.Saved, session.saved, "saved after the retry")
        assertNull(session.notSavedBecause)
        assertEquals(listOf(true, false, false), store.savedCopy().flags(), "the copy after the retry")
    }

    @Test
    fun aSavedWalletCannotBeReplaced() {
        val store = FakeSecureStore()
        val keys = Keys()
        val session = session(store, keys)
        session.generateThisDevice()
        val replacements = listOf<Pair<String, () -> Unit>>(
            "generate" to { session.generateThisDevice() },
            "import" to { session.restoreFromWords(PUBLIC_WORDS_12) },
            "import with a passphrase" to { session.restoreFromWords(PUBLIC_WORDS_12, TEST_PASSPHRASE) },
        )
        for ((case, replace) in replacements) {
            val made = keys.made
            replace()
            assertNotNull(session.lastError, "no refusal, $case")
            assertEquals(made, keys.made, "a key was made, $case")
            assertEquals(listOf(WriteMode.Create), store.modes(), "the store was written, $case")
            assertEquals(0, store.erases, "the store was erased, $case")
            assertEquals(SaveState.Saved, session.saved, case)
            assertTrue(session.words == PUBLIC_WORDS_24, "the saved key left memory, $case")
        }
    }

    @Test
    fun aFreshCreateThatLosesTheRaceDropsItsKey() {
        val creates = listOf<Pair<String, OnboardingSession.() -> Unit>>(
            "generate" to { generateThisDevice() },
            "import" to { restoreFromWords(PUBLIC_WORDS_12) },
            "accepted import" to { restoreFromWords(PUBLIC_WORDS_12, TEST_PASSPHRASE); acceptStagedKey() },
        )
        for ((case, create) in creates) {
            val store = FakeSecureStore()
            session(store, Keys(fingerprint = OTHER_CHECK)).generateThisDevice()
            val loser = session(store)
            loser.create()
            assertEquals(listOf(WriteMode.Create, WriteMode.Create), store.modes(), case)
            assertTrue(loser.words.isEmpty() && loser.fingerprint.isEmpty() && loser.passphrase.isEmpty(), "the losing key was kept, $case")
            assertEquals(SaveState.NotSaved, loser.saved, case)
            assertNotNull(loser.lastError, "no refusal, $case")
            assertTrue(store.savedCopy().fingerprint == OTHER_CHECK, "the winner's copy was replaced, $case")
        }
    }

    @Test
    fun aFlagChangeThatLosesTheRaceKeepsItsKey() {
        val store = FakeSecureStore(write = WriteResult.Unavailable(Why.TryLater))
        val loser = session(store)
        loser.generateThisDevice()
        assertEquals(Why.TryLater, loser.notSavedBecause)
        store.write = null
        session(store, Keys(fingerprint = OTHER_CHECK)).generateThisDevice()
        loser.markBackupConfirmed()
        assertEquals(listOf(WriteMode.Create, WriteMode.Create, WriteMode.Create), store.modes())
        assertTrue(loser.words == PUBLIC_WORDS_24 && loser.fingerprint == TEST_CHECK, "the key the user may have written down was dropped")
        assertEquals(SaveState.NotSaved, loser.saved, "saved after losing the race")
        assertEquals(Why.Other, loser.notSavedBecause)
        assertNull(loser.lastError)
        assertTrue(loser.backupConfirmed, "the flag change was lost")
        assertTrue(store.savedCopy().fingerprint == OTHER_CHECK, "the winner's copy was replaced")
    }

    @Test
    fun forgetSavedWalletErasesThenClears() {
        val store = FakeSecureStore()
        val session = session(store)
        session.generateThisDevice()
        session.markBackupConfirmed()
        assertTrue(session.forgetSavedWallet(), "forget")
        assertEquals(1, store.erases, "erases")
        assertTrue(session.words.isEmpty() && session.fingerprint.isEmpty() && session.passphrase.isEmpty(), "the key stayed in memory")
        assertEquals(listOf(false, false, false), session.flags(), "flags after forget")
        assertEquals(SaveState.NotSaved, session.saved, "saved after forget")
        assertNull(session.notSavedBecause)

        val stuck = FakeSecureStore(erase = EraseResult.Failed(Why.TryLater))
        val kept = session(stuck)
        kept.generateThisDevice()
        assertFalse(kept.forgetSavedWallet(), "a failed erase reported success")
        assertTrue(kept.words == PUBLIC_WORDS_24, "a failed erase cleared memory")
        assertEquals(SaveState.Saved, kept.saved, "saved after a failed erase")
    }

    @Test
    fun everyRecordFieldRoundTripsThroughTheSession() {
        roundTrip("generated, backed up, locked", listOf(true, false, true)) {
            generateThisDevice(TEST_PASSPHRASE)
            markBackupConfirmed()
            markAppLockEnabled()
        }
        roundTrip("imported, not locked", listOf(true, true, false)) {
            restoreFromWords(PUBLIC_WORDS_12, TEST_PASSPHRASE)
            acceptStagedKey()
        }
        roundTrip("generated, locked, not backed up", listOf(false, false, true)) {
            generateThisDevice(TEST_PASSPHRASE)
            markAppLockEnabled()
        }
        // The saved wallet refuses MAIN, so a MAIN key that writes nothing proves the record carries the session's network.
        val store = FakeSecureStore()
        session(store).generateThisDevice(network = KeyNetwork.MAIN)
        assertTrue(store.writes.isEmpty(), "a MAIN key was saved")
        val loaded = session(FakeSecureStore())
        assertTrue(loaded.install(testRecord(network = KeyNetwork.MAIN)), "install MAIN")
        assertEquals(KeyNetwork.MAIN, loaded.network, "network after install")
    }

    @Test
    fun enableReadsTheSavedCopyBack() {
        val store = FakeSecureStore()
        val session = session(store)
        session.generateThisDevice()
        assertEquals(UnlockResult.Verified, session.proveNow())
        assertEquals(1, store.served.size, "reads of the saved copy")
        assertEquals(1, store.writes.size, "the read-back wrote")
        assertEquals(SaveState.Saved, session.saved, "saved after the read-back")
        assertNull(session.notSavedBecause)
        assertNull(session.lastError)
        assertTrue(session.words == PUBLIC_WORDS_24 && session.fingerprint == TEST_CHECK, "the read-back changed the key")
        assertEquals(listOf(false, false, false), session.flags(), "the read-back changed a flag")
    }

    @Test
    fun aMismatchedReadBackIsBrokenAndMarksTheWalletNotSaved() {
        val store = FakeSecureStore()
        val session = session(store)
        session.generateThisDevice()
        store.read = ReadResult.Bytes(v1Bytes(fingerprint = OTHER_CHECK.encodeToByteArray()))
        assertEquals(Cause.Corrupt, assertIs<UnlockResult.Broken>(session.proveNow(), "the read-back of another wallet").cause)
        assertEquals(SaveState.NotSaved, session.saved, "saved after a mismatched read-back")
        assertNotNull(session.notSavedBecause, "no cause for not saved")
        assertTrue(session.words == PUBLIC_WORDS_24 && session.fingerprint == TEST_CHECK, "the key left memory")
        assertEquals(1, store.writes.size, "the read-back wrote")
        assertEquals(0, store.erases, "the read-back erased")
    }

    @Test
    fun aReadBackWithTheRightCheckValueButOtherSecretsIsBroken() {
        // The check value alone would pass a copy whose passphrase or words were damaged after the write, and the next cold start would fail.
        val damaged = listOf(
            "another passphrase" to v1Bytes(passphrase = "damaged".encodeToByteArray()),
            "other words" to v1Bytes(words = PUBLIC_WORDS_12.joinToString(" ").encodeToByteArray()),
        )
        for ((case, bytes) in damaged) {
            val store = FakeSecureStore()
            val session = session(store)
            session.generateThisDevice()
            store.read = ReadResult.Bytes(bytes)
            assertEquals(Cause.Corrupt, assertIs<UnlockResult.Broken>(session.proveNow(), case).cause, case)
            assertEquals(SaveState.NotSaved, session.saved, "saved after a read-back with $case")
        }
    }

    @Test
    fun aReadBackThatCannotOpenMarksTheWalletNotSavedButARefusalDoesNot() {
        val store = FakeSecureStore()
        val session = session(store)
        session.generateThisDevice()
        for (refusal in listOf(ReadResult.Cancelled, ReadResult.Denied, ReadResult.LockedOut, ReadResult.Unavailable(Why.TryLater))) {
            store.read = refusal
            session.proveNow()
            assertEquals(SaveState.Saved, session.saved, "a refusal learned nothing, $refusal")
        }
        for (broken in listOf(ReadResult.Unreadable, ReadResult.KeyGone, ReadResult.NotFound)) {
            val own = session(FakeSecureStore(read = broken))
            own.generateThisDevice()
            assertIs<UnlockResult.Broken>(own.proveNow(), "$broken")
            assertEquals(SaveState.NotSaved, own.saved, "saved after $broken")
            assertNotNull(own.notSavedBecause, "no cause after $broken")
            assertTrue(own.words == PUBLIC_WORDS_24, "the key left memory after $broken")
        }
    }

    @Test
    fun enableOnAPhoneThatCouldNotSaveReportsWhyAndLeavesTheBiometricsScan() {
        val store = FakeSecureStore(read = ReadResult.Bytes(v1Bytes()), write = WriteResult.Unavailable(Why.NoScreenLock))
        val session = session(store)
        session.generateThisDevice()
        assertEquals(Why.NoScreenLock, assertIs<UnlockResult.Unavailable>(session.proveNow(), "a phone that could not save").why)
        assertTrue(store.served.isEmpty(), "the store was read")
        assertEquals(Why.NotWired, assertIs<UnlockResult.Unavailable>(OnboardingSession().proveNow(), "memory only").why)
    }

    @Test
    fun installFailsClosed() {
        val throws = OnboardingSession(SavedWallet(FakeSecureStore()), restoreKey = { _, _, _, _ -> throw IllegalStateException("derive") })
        assertFalse(throws.install(testRecord(appLockEnabled = true)), "a restore that throws")
        assertEmpty(throws, "a restore that throws")

        val keys = Keys()
        val mismatch = session(FakeSecureStore(), keys)
        assertFalse(mismatch.install(testRecord(fingerprint = OTHER_CHECK, appLockEnabled = true)), "a fingerprint mismatch")
        assertEquals(1, keys.made, "derives for a mismatch")
        assertEmpty(mismatch, "a fingerprint mismatch")

        val crash = OnboardingSession(SavedWallet(FakeSecureStore()), restoreKey = { _, _, _, _ -> throw Error("native library") })
        assertFailsWith<Error>("an Error was caught") { crash.install(testRecord()) }
        assertEmpty(crash, "an Error")
    }

    @Test
    fun aNewerRecordIsNeverReplacedBySession() {
        val store = FakeSecureStore()
        store.write(v1Bytes(version = 2), WriteMode.Create)
        store.writes.clear()
        assertEquals(Cause.NewerVersion, assertIs<UnlockResult.Broken>(SavedWallet(store).openNow(), "the copy").cause)
        val session = session(store)
        session.markBackupConfirmed()
        session.markAppLockEnabled()
        assertIs<UnlockResult.Unavailable>(session.proveNow(), "a read-back with nothing saved")
        session.generateThisDevice()
        session.markBackupConfirmed()
        session.restoreFromWords(PUBLIC_WORDS_12)
        session.markAppLockEnabled()
        session.restoreFromWords(PUBLIC_WORDS_12, TEST_PASSPHRASE)
        session.acceptStagedKey()
        session.markBackupConfirmed()
        assertTrue(store.writes.none { it.mode == WriteMode.Replace }, "a Replace was written over a newer record")
        assertEquals(0, store.erases, "erases")
        assertEquals(Cause.NewerVersion, assertIs<UnlockResult.Broken>(SavedWallet(store).openNow(), "the copy afterwards").cause)
    }

    @Test
    fun aFailedReplaceKeepsTheWalletSavedAndTheNextWritePointSavesIt() {
        val store = FakeSecureStore(failWrite = 2)
        val keys = Keys()
        val session = session(store, keys)
        session.generateThisDevice()
        session.markBackupConfirmed() // write 2, a Replace, fails
        assertEquals(SaveState.Saved, session.saved, "saved after a failed Replace: the earlier copy is still on the phone")
        session.markAppLockEnabled() // write 3 must be a Replace again, not a Create that meets the copy
        assertEquals(listOf(WriteMode.Create, WriteMode.Replace, WriteMode.Replace), store.modes(), "write modes")
        assertEquals(listOf(true, false, true), store.savedCopy().flags(), "flags in the store")
        assertIs<UnlockResult.Verified>(session.proveNow(), "the read-back after the retry")
        val made = keys.made
        session.generateThisDevice()
        assertEquals(made, keys.made, "a key was made while one is saved")
        assertTrue(session.words == PUBLIC_WORDS_24, "the saved key left memory")
    }

    @Test
    fun aFailedSaveIsRepeatedByAFlushAndAFlushWithNothingPendingWritesNothing() {
        val closed = FakeSecureStore(write = WriteResult.Unavailable(Why.NoScreenLock))
        val first = session(closed)
        first.generateThisDevice()
        assertEquals(SaveState.NotSaved, first.saved, "saved while the phone could not keep it")
        closed.write = null // the phone has a screen lock now
        first.flushPendingSave()
        assertEquals(SaveState.Saved, first.saved, "saved after the flush")
        assertEquals(listOf(WriteMode.Create, WriteMode.Create), closed.modes(), "write modes of a failed Create then its flush")

        val flaky = FakeSecureStore(failWrite = 2)
        val second = session(flaky)
        second.generateThisDevice()
        second.markBackupConfirmed() // fails
        second.flushPendingSave()
        assertEquals(listOf(WriteMode.Create, WriteMode.Replace, WriteMode.Replace), flaky.modes(), "write modes of a failed Replace then its flush")
        assertEquals(listOf(true, false, false), flaky.savedCopy().flags(), "flags in the store after the flush")

        val writes = flaky.writes.size
        second.flushPendingSave()
        OnboardingSession().flushPendingSave()
        assertEquals(writes, flaky.writes.size, "a flush with nothing pending wrote")
    }

    @Test
    fun installDerivesWithTheRecordsPassphraseAndRefusesADamagedOne() {
        val right = "correct horse"
        var derivedWith: String? = null
        val restore: (List<String>, String, KeyNetwork, UInt) -> SoftwareAccountKey = { w, p, _, _ ->
            derivedWith = p
            SoftwareAccountKey(w, if (p == right) TEST_CHECK else OTHER_CHECK, "tpub-test", "m/48'/1'/0'/2'")
        }
        val session = OnboardingSession(SavedWallet(FakeSecureStore()), restoreKey = restore)
        assertTrue(session.install(testRecord(passphrase = right)), "the right passphrase did not install")
        assertTrue(derivedWith == right, "the derivation did not get the record's passphrase")
        val damaged = OnboardingSession(SavedWallet(FakeSecureStore()), restoreKey = restore)
        assertFalse(damaged.install(testRecord(passphrase = "correct horsf")), "a damaged passphrase installed")
        assertEmpty(damaged, "a damaged passphrase")
    }

    private fun session(store: FakeSecureStore, keys: Keys = Keys()) = OnboardingSession(SavedWallet(store), keys.newKey, keys.restoreKey)

    /** Saves through [make], loads the saved copy into a new session, and compares every record field with what was set. */
    private fun roundTrip(case: String, flags: List<Boolean>, make: OnboardingSession.() -> Unit) {
        val store = FakeSecureStore()
        val keys = Keys(words = PUBLIC_WORDS_12, fingerprint = OTHER_CHECK)
        session(store, keys).make()
        val loaded = session(FakeSecureStore(), keys)
        assertTrue(loaded.install(store.savedCopy()), "install, $case")
        assertTrue(loaded.words == PUBLIC_WORDS_12, "words, $case")
        assertTrue(loaded.passphrase == TEST_PASSPHRASE, "passphrase, $case")
        assertTrue(loaded.fingerprint == OTHER_CHECK, "fingerprint, $case")
        assertEquals(KeyNetwork.TEST, loaded.network, "network, $case")
        assertEquals(flags, loaded.flags(), "flags, $case")
        assertEquals(SaveState.Saved, loaded.saved, "saved, $case")
    }

    private fun assertEmpty(session: OnboardingSession, case: String) {
        assertTrue(session.words.isEmpty() && session.fingerprint.isEmpty() && session.passphrase.isEmpty() && session.xpub.isEmpty(), "a key was installed, $case")
        assertEquals(listOf(false, false, false), session.flags(), "flags, $case")
        assertEquals(SaveState.NotSaved, session.saved, "saved, $case")
    }

    /** The record the store holds now, opened the way the app opens it. */
    private fun FakeSecureStore.savedCopy(): WalletRecord = assertIs<UnlockResult.Opened>(SavedWallet(this).openNow(), "the saved copy").record

    private fun FakeSecureStore.modes() = writes.map { it.mode }

    private fun OnboardingSession.proveNow(): UnlockResult = runBlocking { proveSavedCopy("test") }

    /** backupConfirmed, imported, appLockEnabled, in that order. */
    private fun OnboardingSession.flags() = listOf(backupConfirmed, imported, appLockEnabled)

    private fun WalletRecord.flags() = listOf(backupConfirmed, imported, appLockEnabled)
}

private const val TEST_PASSPHRASE = "a test passphrase"

/** Another wallet's check value, distinct from [TEST_CHECK]. */
private const val OTHER_CHECK = "9f8e7d6c"

/**
 * Key makers that never load the native library. [newKey] returns [words] and [restoreKey] echoes the words it is given,
 * as the real restore does. Both answer [fingerprint] and count their calls in [made]. A key holds the words, so no test
 * compares or prints one.
 */
private class Keys(val words: List<String> = PUBLIC_WORDS_24, val fingerprint: String = TEST_CHECK) {
    var made = 0
        private set

    val newKey: (String, KeyNetwork, UInt) -> SoftwareAccountKey = { _, _, _ -> key(words) }
    val restoreKey: (List<String>, String, KeyNetwork, UInt) -> SoftwareAccountKey = { w, _, _, _ -> key(w) }

    private fun key(words: List<String>): SoftwareAccountKey {
        made++
        return SoftwareAccountKey(words, fingerprint, "tpub-test", "m/48'/1'/0'/2'")
    }
}
