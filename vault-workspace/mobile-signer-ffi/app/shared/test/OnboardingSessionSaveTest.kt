package com.mebit.app.data

import com.mebit.app.EraseResult
import com.mebit.app.ReadResult
import com.mebit.app.StoreProbe
import com.mebit.app.Why
import com.mebit.app.WriteMode
import com.mebit.app.WriteResult
import com.mebit.app.data.SingleSigWallet.UnconfirmedSend
import com.mebit.app.navigation.Boot
import com.mebit.app.navigation.WalletGateState
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.SoftwareAccountKey
import com.mebit.app.SecureStore
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
        assertEquals(listOf("Create"), store.modes(), "writes when generate returned")
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
        assertEquals(listOf("Create"), store.modes(), "writes when import returned")
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
        assertEquals(listOf("Create"), store.modes(), "writes after accept")
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
        assertEquals(listOf("Create", "Replace"), store.modes(), "writes after backup")
        assertEquals(listOf(true, false, false), store.savedCopy().flags(), "the copy after backup")
        session.markAppLockEnabled()
        assertEquals(listOf("Create", "Replace", "Replace"), store.modes(), "writes after lock")
        assertEquals(listOf(true, false, true), store.savedCopy().flags(), "the copy after lock")
        assertEquals(SaveState.Saved, session.saved, "saved after the flag changes")
    }

    @Test
    fun loadSetsFlagsIndependently() {
        for (bits in 0..7) {
            val flags = listOf((bits and 1) != 0, (bits and 2) != 0, (bits and 4) != 0)
            val case = "install backup, imported, lock = $flags"
            val session = session(FakeSecureStore())
            assertTrue(session.install(testRecord(backupConfirmed = flags[0], imported = flags[1], appLockEnabled = flags[2]), ANY_VERSION), case)
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
        session.recordUnconfirmedSend(TEST_TXID, SENT_AT)
        session.clearUnconfirmedSend()
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
        assertEquals(listOf("Create", "Create"), store.modes(), "the retry")
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
            assertEquals(listOf("Create"), store.modes(), "the store was written, $case")
            assertEquals(0, store.erases, "the store was erased, $case")
            assertEquals(SaveState.Saved, session.saved, case)
            assertTrue(session.words == PUBLIC_WORDS_24, "the saved key left memory, $case")
        }
    }

    @Test
    fun aKeyThePhoneCouldNotSaveIsNotReplacedByCreateOrImport() {
        val firsts = listOf<Pair<String, OnboardingSession.() -> Unit>>("create" to { generateThisDevice() }, "import" to { restoreFromWords(PUBLIC_WORDS_24) })
        for ((how, first) in firsts) {
            val store = FakeSecureStore(write = WriteResult.Unavailable(Why.NoScreenLock))
            val keys = Keys()
            val session = session(store, keys)
            session.first()
            assertEquals(SaveState.NotSaved, session.saved, "the first $how was saved")
            val made = keys.made
            val writes = store.writes.size
            for ((case, replace) in listOf<Pair<String, () -> Unit>>("generate" to { session.generateThisDevice() }, "import" to { session.restoreFromWords(PUBLIC_WORDS_12) })) {
                replace()
                assertEquals(UNSAVED_REFUSAL, session.lastError, "the refusal, $case after an unsaved $how")
                assertEquals(made, keys.made, "a key was made, $case after an unsaved $how")
                assertEquals(writes, store.writes.size, "the store was written, $case after an unsaved $how")
                assertTrue(session.words == PUBLIC_WORDS_24, "the unsaved key left memory, $case after an unsaved $how")
                assertEquals(Why.NoScreenLock, session.notSavedBecause, "the notice lost its reason, $case after an unsaved $how")
            }
        }
    }

    @Test
    fun aStagedImportMayBeReplacedByTheNextAttempt() {
        val store = FakeSecureStore()
        val session = session(store)
        session.restoreFromWords(PUBLIC_WORDS_12, TEST_PASSPHRASE)
        assertEquals(SaveState.Staged, session.saved)
        session.restoreFromWords(PUBLIC_WORDS_24, TEST_PASSPHRASE)
        assertNull(session.lastError, "a staged import refused the next import")
        assertTrue(session.words == PUBLIC_WORDS_24, "the next import's words")
        session.generateThisDevice()
        assertNull(session.lastError, "a staged import refused a create")
        assertEquals(SaveState.Saved, session.saved, "saved after the create")
        assertEquals(listOf("Create"), store.modes())
    }

    @Test
    fun aFailedReplaceIsReportedUntilAWriteSucceeds() {
        val store = FakeSecureStore(failWrite = 2)
        val session = session(store)
        session.generateThisDevice()
        assertFalse(session.lastSaveFailed, "before any Replace")
        session.markBackupConfirmed()
        assertEquals(SaveState.Saved, session.saved, "the earlier copy is still on the phone")
        assertNull(session.notSavedBecause, "a failed Replace is not a wallet that was never saved")
        assertTrue(session.lastSaveFailed, "a failed Replace went unreported")
        session.flushPendingSave()
        assertEquals(listOf("Create", "Replace", "Replace"), store.modes(), "the retry")
        assertFalse(session.lastSaveFailed, "cleared by the write that succeeded")

        val guardStore = FakeSecureStore()
        val guarded = session(guardStore)
        guarded.generateThisDevice()
        guardStore.write = WriteResult.Unavailable(Why.TryLater)
        assertFalse(guarded.recordUnconfirmedSend(TEST_TXID, SENT_AT), "the guard the phone could not keep")
        assertTrue(guarded.lastSaveFailed, "a failed Replace of the guard went unreported")

        val created = session(FakeSecureStore(write = WriteResult.Unavailable(Why.NoScreenLock)))
        created.generateThisDevice()
        assertFalse(created.lastSaveFailed, "a failed Create already has notSavedBecause")
    }

    @Test
    fun neitherTheSessionNorTheRecordPrintsAWordOfThePhrase() {
        val session = session(FakeSecureStore(), Keys(words = PUBLIC_WORDS_12))
        session.restoreFromWords(PUBLIC_WORDS_12, TEST_PASSPHRASE)
        session.acceptStagedKey()
        assertEquals("WalletRecord(redacted)", testRecord(passphrase = TEST_PASSPHRASE).toString())
        for (printed in listOf(session.toString(), "$session")) {
            for (secret in PUBLIC_WORDS_12.toSet() + TEST_PASSPHRASE) assertFalse(secret in printed, "the session printed a secret")
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
            assertEquals(listOf("Create", "Create"), store.modes(), case)
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
        assertEquals(listOf("Create", "Create", "Create"), store.modes())
        assertTrue(loser.words == PUBLIC_WORDS_24 && loser.fingerprint == TEST_CHECK, "the key the user may have written down was dropped")
        assertEquals(SaveState.NotSaved, loser.saved, "saved after losing the race")
        assertEquals(Why.Other, loser.notSavedBecause)
        assertNull(loser.lastError)
        assertTrue(loser.backupConfirmed, "the flag change was lost")
        assertTrue(store.savedCopy().fingerprint == OTHER_CHECK, "the winner's copy was replaced")
    }

    @Test
    fun aStaleReplaceNeverBringsBackAForgottenWalletOverANewOne() {
        val store = FakeSecureStore()
        val a = session(store)
        a.generateThisDevice() // X in the store, A Saved
        val b = session(store, Keys(words = PUBLIC_WORDS_12, fingerprint = OTHER_CHECK))
        assertTrue(b.forgetSavedWallet(), "B erases X")
        b.generateThisDevice() // Y, a Create into the empty store
        a.markBackupConfirmed() // A's stale Replace
        assertEquals(OTHER_CHECK, store.savedCopy().fingerprint, "Y was replaced by X")
        assertEquals(SaveState.NotSaved, a.saved, "saved after the Conflict")
        assertEquals(Why.Other, a.notSavedBecause)
        assertTrue(a.words == PUBLIC_WORDS_24 && a.fingerprint == TEST_CHECK && a.backupConfirmed, "A's key or flag left memory")
        val writes = store.writes.size
        a.markAppLockEnabled()
        a.flushPendingSave()
        assertEquals(writes, store.writes.size, "a stale session wrote again")
        assertEquals(OTHER_CHECK, store.savedCopy().fingerprint, "Y after A's later write points")
    }

    @Test
    fun aStaleReplaceNeverOverwritesANewerCopyThatHoldsAPayment() {
        val store = FakeSecureStore()
        val a = session(store)
        a.generateThisDevice()
        val b = session(store)
        val gate = WalletGateState(SavedWallet(store), b)
        runBlocking { gate.unlock("test") }
        assertIs<Boot.Open>(gate.boot, "B's unlock")
        b.recordUnconfirmedSend(TEST_TXID, SENT_AT)
        a.markBackupConfirmed()
        assertEquals(UnconfirmedSend(TEST_TXID, SENT_AT), store.savedCopy().unconfirmedSend, "the payment of unknown outcome was overwritten")
        assertEquals(SaveState.NotSaved, a.saved, "the session that lost")
        assertEquals(SaveState.Saved, b.saved, "the session that wrote last")
    }

    @Test
    fun aStaleReplaceAfterAForgetElsewhereNeverSavesTheForgottenWalletAgain() {
        val store = FakeSecureStore()
        val a = session(store)
        a.generateThisDevice()
        assertTrue(session(store).forgetSavedWallet(), "B forgets X")
        a.markBackupConfirmed()
        assertEquals(StoreProbe.Empty, store.probe, "the forgotten wallet came back")
        assertEquals(SaveState.NotSaved, a.saved, "saved after the Conflict")
        val writes = store.writes.size
        a.markAppLockEnabled()
        a.flushPendingSave()
        assertEquals(writes, store.writes.size, "a stale session wrote again")
        assertEquals(StoreProbe.Empty, store.probe, "the forgotten wallet came back at a later write point")
    }

    @Test
    fun oneSessionNeverMeetsAConflict() {
        val store = FakeSecureStore()
        val session = session(store)
        session.generateThisDevice()
        session.markBackupConfirmed()
        session.markAppLockEnabled()
        session.markBackupConfirmed()
        session.recordUnconfirmedSend(TEST_TXID, SENT_AT)
        session.clearUnconfirmedSend()
        assertEquals(SaveState.Saved, session.saved, "saved after five Replaces")
        assertNull(session.notSavedBecause, "a Replace of this session's own copy failed")
        val next = session(store)
        val gate = WalletGateState(SavedWallet(store), next)
        runBlocking { gate.unlock("test") }
        assertIs<Boot.Open>(gate.boot, "the next launch's unlock")
        next.markAppLockEnabled()
        assertEquals(listOf("Create") + List(6) { "Replace" }, store.modes(), "write kinds")
        assertEquals(SaveState.Saved, next.saved, "saved after the next launch's Replace")
        assertNull(next.notSavedBecause, "the next launch's Replace failed")
        assertEquals(listOf(true, false, true), store.savedCopy().flags(), "the copy at the end")
    }

    @Test
    fun theReadBackNeverAdoptsTheVersionItRead() {
        val store = FakeSecureStore()
        val a = session(store)
        a.generateThisDevice()
        val b = session(store)
        val gate = WalletGateState(SavedWallet(store), b)
        runBlocking { gate.unlock("test") }
        b.recordUnconfirmedSend(TEST_TXID, SENT_AT)
        assertEquals(UnlockResult.Verified, a.proveNow(), "A's read-back of the same key, now with B's payment")
        a.markBackupConfirmed()
        assertEquals(UnconfirmedSend(TEST_TXID, SENT_AT), store.savedCopy().unconfirmedSend, "the read-back adopted B's version and A overwrote the payment")
        assertEquals(SaveState.NotSaved, a.saved, "A after its stale Replace")
    }

    @Test
    fun aStaleSessionRefusesCreateAndImport() {
        val store = FakeSecureStore()
        val keys = Keys()
        val a = session(store, keys)
        a.generateThisDevice()
        assertTrue(session(store).forgetSavedWallet(), "B forgets")
        a.markBackupConfirmed() // the Conflict
        assertEquals(SaveState.NotSaved, a.saved, "saved after the Conflict")
        val made = keys.made
        val writes = store.writes.size
        for ((case, replace) in listOf<Pair<String, () -> Unit>>("generate" to { a.generateThisDevice() }, "import" to { a.restoreFromWords(PUBLIC_WORDS_12) })) {
            replace()
            assertNotNull(a.lastError, "no refusal, $case")
            assertEquals(made, keys.made, "a key was made, $case")
            assertEquals(writes, store.writes.size, "the store was written, $case")
            assertTrue(a.words == PUBLIC_WORDS_24, "the stale key left memory, $case")
        }
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

        val stuck = FakeSecureStore(erase = failedErase(Why.TryLater))
        val kept = session(stuck)
        kept.generateThisDevice()
        assertFalse(kept.forgetSavedWallet(), "a failed erase reported success")
        assertTrue(kept.words == PUBLIC_WORDS_24, "a failed erase cleared memory")
        assertEquals(SaveState.Saved, kept.saved, "saved after a failed erase")
    }

    @Test
    fun aFailedForgetLeavesTheSessionAbleToSaveItsPaymentGuard() {
        val store = FakeSecureStore(erase = failedErase(Why.TryLater))
        val session = session(store)
        session.generateThisDevice()
        assertFalse(session.forgetSavedWallet(), "the first forget")
        assertFalse(session.forgetSavedWallet(), "the second forget")
        session.markBackupConfirmed()
        assertTrue(session.recordUnconfirmedSend(TEST_TXID, SENT_AT), "the guard reached the phone")
        assertEquals(SaveState.Saved, session.saved, "saved after a failed erase and two saves")
        assertNull(session.notSavedBecause, "a save after the failed erase was refused")
        assertEquals(UnconfirmedSend(TEST_TXID, SENT_AT), store.savedCopy().unconfirmedSend, "the copy after the guard")
        assertEquals(listOf(true, false, false), store.savedCopy().flags(), "the copy after backup")
    }

    @Test
    fun recordingAPaymentGuardReportsWhetherThePhoneHoldsIt() {
        val store = FakeSecureStore()
        val session = session(store)
        session.generateThisDevice()
        store.write = WriteResult.Unavailable(Why.TryLater)
        assertFalse(session.recordUnconfirmedSend(TEST_TXID, SENT_AT), "a guard the phone could not keep")
        assertEquals(UnconfirmedSend(TEST_TXID, SENT_AT), session.unconfirmedSend, "kept in memory for the flush")
        store.write = null
        assertTrue(session.recordUnconfirmedSend(TEST_TXID, SENT_AT), "a guard the phone kept")
        assertFalse(OnboardingSession().recordUnconfirmedSend(TEST_TXID, SENT_AT), "a session with no store")
        val stale = session(store)
        stale.generateThisDevice() // refused: a wallet is saved, so this session never holds a copy
        assertFalse(stale.recordUnconfirmedSend(TEST_TXID, SENT_AT), "a session that holds no copy")
    }

    @Test
    fun aPaymentOfUnknownOutcomeIsSavedWhenRecordedAndRemovedWhenCleared() {
        val store = FakeSecureStore()
        val session = session(store)
        session.generateThisDevice()
        session.recordUnconfirmedSend(TEST_TXID, SENT_AT)
        assertEquals(UnconfirmedSend(TEST_TXID, SENT_AT), session.unconfirmedSend, "in memory after recording")
        assertEquals(listOf("Create", "Replace"), store.modes(), "writes after recording")
        assertEquals(UnconfirmedSend(TEST_TXID, SENT_AT), store.savedCopy().unconfirmedSend, "the copy after recording")
        assertEquals(listOf(false, false, false), store.savedCopy().flags(), "the flags beside it")
        session.clearUnconfirmedSend()
        assertNull(session.unconfirmedSend, "in memory after clearing")
        assertEquals(listOf("Create", "Replace", "Replace"), store.modes(), "writes after clearing")
        assertNull(store.savedCopy().unconfirmedSend, "the copy after clearing")
        session.clearUnconfirmedSend()
        assertEquals(3, store.writes.size, "a clear with nothing held wrote")
    }

    @Test
    fun installRestoresAPaymentOfUnknownOutcomeAndClearDropsIt() {
        val store = FakeSecureStore()
        val session = session(store)
        assertTrue(session.install(testRecord(unconfirmedSend = UnconfirmedSend(TEST_TXID, SENT_AT)), ANY_VERSION), "install")
        assertEquals(UnconfirmedSend(TEST_TXID, SENT_AT), session.unconfirmedSend, "after install")
        session.clear()
        assertNull(session.unconfirmedSend, "after clear")
        assertTrue(store.writes.isEmpty(), "install or clear wrote")
    }

    @Test
    fun aFailedSaveOfAPaymentOfUnknownOutcomeKeepsItAndAFlushSavesIt() {
        val store = FakeSecureStore(failWrite = 2)
        val session = session(store)
        session.generateThisDevice()
        session.recordUnconfirmedSend(TEST_TXID, SENT_AT) // write 2, a Replace, fails
        assertEquals(UnconfirmedSend(TEST_TXID, SENT_AT), session.unconfirmedSend, "in memory after a failed save")
        assertEquals(SaveState.Saved, session.saved, "saved after a failed Replace")
        assertNull(store.savedCopy().unconfirmedSend, "the copy before the flush")
        session.flushPendingSave()
        assertEquals(listOf("Create", "Replace", "Replace"), store.modes(), "write modes")
        assertEquals(UnconfirmedSend(TEST_TXID, SENT_AT), store.savedCopy().unconfirmedSend, "the copy after the flush")
        store.write = WriteResult.Unavailable(Why.TryLater)
        session.clearUnconfirmedSend()
        assertEquals(UnconfirmedSend(TEST_TXID, SENT_AT), store.savedCopy().unconfirmedSend, "the copy while the clear is not saved")
        store.write = null
        session.flushPendingSave()
        assertNull(store.savedCopy().unconfirmedSend, "the copy after the flush of the clear")
    }

    @Test
    fun aSaveHeldInTheStoreDoesNotLetALaterSaveOfAPaymentBeOverwritten() = runBlocking {
        val store = HoldingStore(FakeSecureStore(), holdCall = 3, failCall = 2)
        val session = session(store)
        session.generateThisDevice() // write 1
        session.markBackupConfirmed() // write 2, a Replace, fails and leaves a save pending

        val flush = async(Dispatchers.Default) { session.flushPendingSave() } // write 3, held, its record has no payment
        store.entered.await()
        val payment = async(Dispatchers.Default) { session.recordUnconfirmedSend(TEST_TXID, SENT_AT) }
        withTimeoutOrNull(300) { payment.await() } // done at once without the lock, waits for the held save with it
        store.release.complete(Unit)
        flush.await()
        payment.await()

        assertEquals(1, store.maxInFlight.value, "writes in flight at once")
        assertEquals(UnconfirmedSend(TEST_TXID, SENT_AT), store.inner.savedCopy().unconfirmedSend, "the copy after both saves")
    }

    @Test
    fun everyRecordFieldRoundTripsThroughTheSession() {
        roundTrip("generated, backed up, locked", listOf(true, false, true)) {
            generateThisDevice(TEST_PASSPHRASE)
            markBackupConfirmed()
            markAppLockEnabled()
        }
        roundTrip("generated, a payment of unknown outcome", listOf(false, false, false), UnconfirmedSend(TEST_TXID, SENT_AT)) {
            generateThisDevice(TEST_PASSPHRASE)
            recordUnconfirmedSend(TEST_TXID, SENT_AT)
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
        assertTrue(loaded.install(testRecord(network = KeyNetwork.MAIN), ANY_VERSION), "install MAIN")
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
        store.read = ReadResult.Bytes(v1Bytes(fingerprint = OTHER_CHECK.encodeToByteArray()), ANY_VERSION)
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
            store.read = ReadResult.Bytes(bytes, ANY_VERSION)
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
        val store = FakeSecureStore(read = ReadResult.Bytes(v1Bytes(), ANY_VERSION), write = WriteResult.Unavailable(Why.NoScreenLock))
        val session = session(store)
        session.generateThisDevice()
        assertEquals(Why.NoScreenLock, assertIs<UnlockResult.Unavailable>(session.proveNow(), "a phone that could not save").why)
        assertTrue(store.served.isEmpty(), "the store was read")
        assertEquals(Why.NotWired, assertIs<UnlockResult.Unavailable>(OnboardingSession().proveNow(), "memory only").why)
    }

    @Test
    fun installFailsClosed() {
        val throws = OnboardingSession(SavedWallet(FakeSecureStore()), restoreKey = { _, _, _, _ -> throw IllegalStateException("derive") })
        assertFalse(throws.install(testRecord(appLockEnabled = true), ANY_VERSION), "a restore that throws")
        assertEmpty(throws, "a restore that throws")

        val keys = Keys()
        val mismatch = session(FakeSecureStore(), keys)
        assertFalse(mismatch.install(testRecord(fingerprint = OTHER_CHECK, appLockEnabled = true), ANY_VERSION), "a fingerprint mismatch")
        assertEquals(1, keys.made, "derives for a mismatch")
        assertEmpty(mismatch, "a fingerprint mismatch")

        val crash = OnboardingSession(SavedWallet(FakeSecureStore()), restoreKey = { _, _, _, _ -> throw Error("native library") })
        assertFailsWith<Error>("an Error was caught") { crash.install(testRecord(), ANY_VERSION) }
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
        assertTrue(store.writes.none { it.mode is WriteMode.Replace }, "a Replace was written over a newer record")
        assertEquals(0, store.erases, "erases")
        assertEquals(Cause.NewerVersion, assertIs<UnlockResult.Broken>(SavedWallet(store).openNow(), "the copy afterwards").cause)
    }

    @Test
    fun aFailedReplaceKeepsItsVersionSoTheNextReplaceGoesThrough() {
        val store = FakeSecureStore(failWrite = 2)
        val keys = Keys()
        val session = session(store, keys)
        session.generateThisDevice()
        session.markBackupConfirmed() // write 2, a Replace, fails
        assertEquals(SaveState.Saved, session.saved, "saved after a failed Replace: the earlier copy is still on the phone")
        session.markAppLockEnabled() // write 3 must be a Replace of the same copy again, not a Create that meets it and not a Conflict
        assertEquals(listOf("Create", "Replace", "Replace"), store.modes(), "write modes")
        assertEquals(SaveState.Saved, session.saved, "saved after the retry")
        assertNull(session.notSavedBecause, "the retry was refused")
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
        assertEquals(listOf("Create", "Create"), closed.modes(), "write modes of a failed Create then its flush")

        val flaky = FakeSecureStore(failWrite = 2)
        val second = session(flaky)
        second.generateThisDevice()
        second.markBackupConfirmed() // fails
        second.flushPendingSave()
        assertEquals(listOf("Create", "Replace", "Replace"), flaky.modes(), "write modes of a failed Replace then its flush")
        assertEquals(listOf(true, false, false), flaky.savedCopy().flags(), "flags in the store after the flush")

        val writes = flaky.writes.size
        second.flushPendingSave()
        OnboardingSession().flushPendingSave()
        assertEquals(writes, flaky.writes.size, "a flush with nothing pending wrote")
    }

    @Test
    fun aStoreThatCanNeverKeepTheWalletIsNotRetriedByAFlush() {
        for (why in listOf(Why.NotWired, Why.OsTooOld)) {
            val store = FakeSecureStore(write = WriteResult.Unavailable(why))
            val session = session(store)
            session.generateThisDevice()
            assertEquals(SaveState.NotSaved, session.saved, "saved with a store that cannot keep it, $why")
            session.flushPendingSave()
            assertEquals(1, store.writes.size, "a flush tried again, $why")
        }
        for (why in listOf(Why.NoScreenLock, Why.TryLater, Why.Other)) {
            val store = FakeSecureStore(write = WriteResult.Unavailable(why))
            val session = session(store)
            session.generateThisDevice()
            session.flushPendingSave()
            assertEquals(2, store.writes.size, "a flush did not try again, $why")
        }
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
        assertTrue(session.install(testRecord(passphrase = right), ANY_VERSION), "the right passphrase did not install")
        assertTrue(derivedWith == right, "the derivation did not get the record's passphrase")
        val damaged = OnboardingSession(SavedWallet(FakeSecureStore()), restoreKey = restore)
        assertFalse(damaged.install(testRecord(passphrase = "correct horsf"), ANY_VERSION), "a damaged passphrase installed")
        assertEmpty(damaged, "a damaged passphrase")
    }

    private fun session(store: SecureStore, keys: Keys = Keys()) = OnboardingSession(SavedWallet(store), keys.newKey, keys.restoreKey)

    /** Saves through [make], loads the saved copy into a new session, and compares every record field with what was set. */
    private fun roundTrip(case: String, flags: List<Boolean>, unconfirmedSend: UnconfirmedSend? = null, make: OnboardingSession.() -> Unit) {
        val store = FakeSecureStore()
        val keys = Keys(words = PUBLIC_WORDS_12, fingerprint = OTHER_CHECK)
        session(store, keys).make()
        val loaded = session(FakeSecureStore(), keys)
        assertTrue(loaded.install(store.savedCopy(), ANY_VERSION), "install, $case")
        assertTrue(loaded.words == PUBLIC_WORDS_12, "words, $case")
        assertTrue(loaded.passphrase == TEST_PASSPHRASE, "passphrase, $case")
        assertTrue(loaded.fingerprint == OTHER_CHECK, "fingerprint, $case")
        assertEquals(KeyNetwork.TEST, loaded.network, "network, $case")
        assertEquals(flags, loaded.flags(), "flags, $case")
        assertEquals(unconfirmedSend, loaded.unconfirmedSend, "unconfirmed send, $case")
        assertEquals(SaveState.Saved, loaded.saved, "saved, $case")
    }

    private fun assertEmpty(session: OnboardingSession, case: String) {
        assertTrue(session.words.isEmpty() && session.fingerprint.isEmpty() && session.passphrase.isEmpty() && session.xpub.isEmpty(), "a key was installed, $case")
        assertEquals(listOf(false, false, false), session.flags(), "flags, $case")
        assertEquals(SaveState.NotSaved, session.saved, "saved, $case")
    }

    /** The record the store holds now, opened the way the app opens it. */
    private fun FakeSecureStore.savedCopy(): WalletRecord = assertIs<UnlockResult.Opened>(SavedWallet(this).openNow(), "the saved copy").record

    private fun OnboardingSession.proveNow(): UnlockResult = runBlocking { proveSavedCopy("test") }

    /** backupConfirmed, imported, appLockEnabled, in that order. */
    private fun OnboardingSession.flags() = listOf(backupConfirmed, imported, appLockEnabled)

    private fun WalletRecord.flags() = listOf(backupConfirmed, imported, appLockEnabled)
}

private const val TEST_PASSPHRASE = "a test passphrase"

/** The refusal under Create or Import while the session holds a key the phone did not save. */
private const val UNSAVED_REFUSAL = "ยังมีกุญแจที่ยังไม่ได้บันทึกอยู่ในแอป สร้างหรือนำเข้าใหม่ทับไม่ได้ ปิดแอปแล้วเปิดใหม่ถ้าจะเริ่มใหม่"

/** When a test broadcast ended, in Unix seconds. */
private const val SENT_AT = 1_792_740_720L

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

/** Delegates to [inner]. Write number [failCall] fails, and write number [holdCall] waits on [release] before it goes through. */
private class HoldingStore(val inner: FakeSecureStore, val holdCall: Int, val failCall: Int) : SecureStore by inner {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val maxInFlight = atomic(0)
    private val inFlight = atomic(0)
    private val calls = atomic(0)

    override fun write(bytes: ByteArray, mode: WriteMode): WriteResult {
        val call = calls.incrementAndGet()
        val now = inFlight.incrementAndGet()
        while (true) {
            val m = maxInFlight.value
            if (now <= m || maxInFlight.compareAndSet(m, now)) break
        }
        try {
            if (call == failCall) return WriteResult.Unavailable(Why.TryLater)
            if (call == holdCall) {
                entered.complete(Unit)
                runBlocking { release.await() }
            }
            return inner.write(bytes, mode)
        } finally {
            inFlight.decrementAndGet()
        }
    }
}
