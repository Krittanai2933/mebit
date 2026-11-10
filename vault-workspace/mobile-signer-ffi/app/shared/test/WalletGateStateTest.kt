package com.mebit.app.navigation

import com.mebit.app.EraseResult
import com.mebit.app.Marker
import com.mebit.app.Place
import com.mebit.app.ReadResult
import com.mebit.app.SecureStore
import com.mebit.app.StoreProbe
import com.mebit.app.Why
import com.mebit.app.WriteMode
import com.mebit.app.data.ANY_VERSION
import com.mebit.app.data.Cause
import com.mebit.app.data.Claim
import com.mebit.app.data.FakePlainFile
import com.mebit.app.data.FakeSecureStore
import com.mebit.app.data.failedErase
import com.mebit.app.data.guardOf
import com.mebit.app.data.OnboardingSession
import com.mebit.app.data.PendingPayments
import com.mebit.app.data.PUBLIC_WORDS_12
import com.mebit.app.data.PUBLIC_WORDS_24
import com.mebit.app.data.SaveResult
import com.mebit.app.data.SaveState
import com.mebit.app.data.SavedWallet
import com.mebit.app.data.SingleSigWallet.UnconfirmedSend
import com.mebit.app.data.StartNotice
import com.mebit.app.data.TEST_CHECK
import com.mebit.app.data.TEST_TXID
import com.mebit.app.data.UnlockResult
import com.mebit.app.data.WalletRecord
import com.mebit.app.data.Wallets
import com.mebit.app.data.OnePlaceShelf
import com.mebit.app.data.oldRecordBytes
import com.mebit.app.data.testRecord
import com.mebit.app.data.v1Bytes
import com.mebit.ffi.signer.AddressType
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.SoftwareAccountKey
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class WalletGateStateTest {
    @Test
    fun nothingIsInstalledUntilOpened(): Unit = runBlocking {
        val store = savedPhone()
        var opened = false
        val wallets = Wallets(OnePlaceShelf(store), restoreKey = { words, _, _, _ ->
            if (!opened) fail("derived before the wallet opened")
            SoftwareAccountKey(words, TEST_CHECK, "tpub-test", "m/48'/1'/0'/2'")
        })
        val session = wallets.at(Place.FIRST).session
        val gate = WalletGateState(wallets)
        assertEquals(Boot.Locked, gate.boot, "a saved wallet starts")
        assertNothingInstalled(session, "a Locked gate")
        store.read = ReadResult.Cancelled
        gate.unlock("test")
        assertNothingInstalled(session, "a cancelled unlock")
        store.read = null
        opened = true
        gate.unlock("test")
        assertIs<Boot.Open>(gate.boot, "the owner's unlock")
        assertTrue(session.words == PUBLIC_WORDS_24 && session.fingerprint == TEST_CHECK, "the opened wallet is not in the session")
    }

    @Test
    fun cancelDeniedLockedOutAndUnavailableStayLockedAndInstallNothing(): Unit = runBlocking {
        for (answer in REFUSALS) {
            val case = label(answer)
            val app = AppLaunch(savedPhone().apply { read = answer })
            app.gate.unlock("test")
            assertEquals(Boot.Locked, app.gate.boot, case)
            assertEquals(case, label(app.gate.lastAttempt), "what the screen explains, $case")
            assertEquals(0, app.keys.restores, "derivations, $case")
            assertNothingInstalled(app.session, case)
            assertTrue(app.store.writes.isEmpty() && app.store.erases == 0, "the saved copy changed, $case")
        }
    }

    @Test
    fun brokenResultsGoToBrokenWithTheirCause(): Unit = runBlocking {
        for ((case, answer, cause) in brokenAnswers()) {
            val app = AppLaunch(savedPhone().apply { read = answer })
            app.gate.unlock("test")
            assertEquals(cause, assertIs<Boot.Broken>(app.gate.boot, case).cause, case)
            assertEquals(0, app.keys.restores, "derivations, $case")
            assertNothingInstalled(app.session, case)
            assertTrue(app.store.writes.isEmpty() && app.store.erases == 0, "the saved copy changed, $case")
        }
    }

    @Test
    fun openedInstallsOnceAndOpensAtTheStartRoute(): Unit = runBlocking {
        val cases = listOf(
            Triple("backed up", testRecord(passphrase = "a test passphrase", backupConfirmed = true, imported = true), MovieRoute.VaultList),
            Triple("not backed up", testRecord(appLockEnabled = true), MovieRoute.VaultList),
            Triple("address type not confirmed", testRecord(backupConfirmed = true, addressType = AddressType.TAPROOT, addressTypeConfirmed = false), MovieRoute.AddressType),
        )
        for ((case, record, route) in cases) {
            val app = AppLaunch(savedPhone(record))
            app.gate.unlock("test")
            assertEquals(route, assertIs<Boot.Open>(app.gate.boot, case).route, case)
            assertEquals(1, app.keys.restores, "derivations, $case")
            val s = app.session
            assertTrue(s.words == record.words && s.passphrase == record.passphrase && s.fingerprint == record.fingerprint, "the session does not hold the saved key, $case")
            assertEquals(record.flags(), s.flags(), "flags, $case")
            assertEquals(SaveState.Saved, s.saved, case)
            // A type the user had not confirmed comes back selected, with first setup open so it can still change.
            assertEquals(Triple(record.addressType, record.addressTypeConfirmed, !record.addressTypeConfirmed), Triple(s.addressType, s.addressTypeConfirmed, s.inFirstSetup), "the type, $case")
        }
    }

    @Test
    fun fingerprintMismatchLeavesTheSessionEmptyAndBroken(): Unit = runBlocking {
        val app = AppLaunch(savedPhone())
        app.keys.fingerprint = OTHER_CHECK // the saved words derive to another wallet, as after a damaged passphrase byte
        app.gate.unlock("test")
        assertEquals(Cause.Corrupt, assertIs<Boot.Broken>(app.gate.boot, "a mismatch").cause, "a mismatch")
        assertEquals(1, app.keys.restores, "derivations")
        assertNothingInstalled(app.session, "a mismatch")
        assertEquals(0, app.store.erases, "a mismatch erased the copy")
    }

    @Test
    fun unlockIsIdempotentWhileBusyAndOutsideLocked(): Unit = runBlocking {
        val app = AppLaunch(savedPhone())
        val sheet = CompletableDeferred<Unit>()
        app.sheets.hold = sheet
        val first = launch(start = CoroutineStart.UNDISPATCHED) { app.gate.unlock("first") }
        // A call that was not ignored would wait on the held sheet, so the timeout turns that into a failure.
        assertNotNull(withTimeoutOrNull(1_000) { app.gate.unlock("second") }, "a second unlock waited on the first one's sheet")
        assertNotNull(withTimeoutOrNull(1_000) { app.gate.forget("during") }, "a forget waited on the unlock's sheet")
        assertEquals(1, app.sheets.reads, "sheets while an unlock runs")
        sheet.complete(Unit)
        first.join()
        val open = assertIs<Boot.Open>(app.gate.boot, "the first unlock")
        assertEquals(1, app.keys.restores, "derivations")
        assertEquals(0, app.store.erases, "the forget during the unlock erased")
        app.gate.unlock("again")
        assertSame(open, app.gate.boot, "an unlock after Open")
        assertEquals(1, app.sheets.reads, "sheets after Open")

        // From Broken and from Onboarding the store would now serve a copy that opens, so an unlock that ran would show.
        val broken = AppLaunch(savedPhone().apply { read = ReadResult.Unreadable })
        broken.gate.unlock("test")
        broken.store.read = null
        broken.gate.unlock("again")
        assertIs<Boot.Broken>(broken.gate.boot, "an unlock from Broken")
        assertEquals(1, broken.sheets.reads, "sheets from Broken")
        assertNothingInstalled(broken.session, "an unlock from Broken")

        val none = AppLaunch(FakeSecureStore(read = ReadResult.Bytes(v1Bytes(), ANY_VERSION)))
        none.gate.unlock("test")
        assertEquals(Boot.Onboarding, none.gate.boot, "an unlock from Onboarding")
        assertEquals(0, none.sheets.reads, "sheets from Onboarding")
        assertNothingInstalled(none.session, "an unlock from Onboarding")

        // The screen left while its sheet was up, and the next attempt must not find the gate still busy.
        val left = AppLaunch(savedPhone())
        left.sheets.hold = CompletableDeferred()
        launch(start = CoroutineStart.UNDISPATCHED) { left.gate.unlock("first") }.cancelAndJoin()
        left.sheets.hold = null
        left.gate.unlock("next")
        assertIs<Boot.Open>(left.gate.boot, "an unlock after a cancelled one")
    }

    @Test
    fun forgetNeedsTheOwnerWhileTheCopyStillOpens(): Unit = runBlocking {
        for (answer in REFUSALS) {
            val case = label(answer)
            val app = AppLaunch(savedPhone().apply { read = answer })
            app.gate.forget("test")
            assertEquals(Boot.Locked, app.gate.boot, case)
            assertEquals(case, label(app.gate.lastAttempt), "what the screen explains, $case")
            assertEquals(1, app.sheets.reads, "owner checks, $case")
            assertEquals(0, app.store.erases, "a refused owner check erased, $case")
        }
        val app = AppLaunch(savedPhone())
        app.gate.forget("test")
        assertEquals(Boot.Onboarding, app.gate.boot, "a forget the owner proved")
        assertEquals(1, app.sheets.reads, "owner checks")
        assertEquals(1, app.store.erases, "erases")
        assertEquals(0, app.keys.restores, "the proof was derived") // it proves the owner and is dropped unread
        assertNothingInstalled(app.session, "a forget the owner proved")
    }

    @Test
    fun forgetFromBrokenNeedsOnlyTheConfirm(): Unit = runBlocking {
        // An owner check would be refused here, so only a forget on the confirm alone can erase.
        val keyGone = AppLaunch(FakeSecureStore(probe = StoreProbe.KeyGone, marker = Marker.Kept, read = ReadResult.Cancelled))
        assertEquals(Cause.KeyGone, assertIs<Boot.Broken>(keyGone.gate.boot, "a key gone at start").cause, "a key gone at start")
        val corrupt = AppLaunch(savedPhone().apply { read = ReadResult.Unreadable })
        corrupt.gate.unlock("test")
        assertEquals(Cause.Corrupt, assertIs<Boot.Broken>(corrupt.gate.boot, "an unreadable copy").cause, "an unreadable copy")
        corrupt.store.read = ReadResult.Cancelled
        for ((case, app) in listOf("Broken(KeyGone)" to keyGone, "Broken(Corrupt)" to corrupt)) {
            val sheets = app.sheets.reads
            app.gate.forget("test")
            assertEquals(Boot.Onboarding, app.gate.boot, case)
            assertEquals(sheets, app.sheets.reads, "owner checks, $case")
            assertEquals(1, app.store.erases, "erases, $case")
        }
    }

    @Test
    fun aForgetThatFindsTheCopyBrokenStopsOnTheRecoveryScreen(): Unit = runBlocking {
        for ((case, answer, cause) in brokenAnswers()) {
            val app = AppLaunch(savedPhone().apply { read = answer })
            app.gate.forget("test")
            assertEquals(cause, assertIs<Boot.Broken>(app.gate.boot, case).cause, case)
            assertEquals(0, app.store.erases, "erased before the recovery screen, $case")
            assertNothingInstalled(app.session, case)
            app.gate.forget("test")
            assertEquals(Boot.Onboarding, app.gate.boot, "the recovery screen's forget, $case")
            assertEquals(1, app.sheets.reads, "owner checks, $case")
        }
    }

    @Test
    fun forgetErasesThenOnboardingAndAFailedEraseStaysPut(): Unit = runBlocking {
        val app = AppLaunch(savedPhone().apply { erase = failedErase(Why.TryLater) })
        app.gate.forget("test")
        assertEquals(Boot.Locked, app.gate.boot, "a failed erase from Locked")
        assertEquals("Unavailable(Other)", label(app.gate.lastAttempt), "a failed erase from Locked")
        assertEquals(StoreProbe.Present, app.store.probe(), "the copy after a failed erase")
        assertNothingInstalled(app.session, "a failed erase from Locked")

        app.store.erase = EraseResult.Erased
        app.gate.forget("test")
        assertEquals(Boot.Onboarding, app.gate.boot, "a forget whose erase worked")
        assertEquals(StoreProbe.Empty, app.store.probe(), "the copy after forget")
        assertEquals(Marker.Empty, app.store.marker(), "the marker after forget")
        app.session.restoreFromWords(PUBLIC_WORDS_12) // the way out, where the user types their words again
        assertEquals(SaveState.Saved, app.session.saved, "an import after forget")
        assertNull(app.session.lastError, "an import after forget was refused")

        val broken = AppLaunch(FakeSecureStore(probe = StoreProbe.KeyGone, marker = Marker.Kept, erase = failedErase(Why.Other)))
        broken.gate.forget("test")
        assertEquals(Cause.KeyGone, assertIs<Boot.Broken>(broken.gate.boot, "a failed erase from Broken").cause, "a failed erase from Broken")
        assertEquals("Unavailable(Other)", label(broken.gate.lastAttempt), "a failed erase from Broken")
    }

    @Test
    fun forgetDoesNothingOnceTheWalletIsOpenOrThereIsNone(): Unit = runBlocking {
        val open = AppLaunch(savedPhone())
        open.gate.unlock("test")
        val opened = assertIs<Boot.Open>(open.gate.boot, "the unlock")
        open.gate.forget("test")
        assertSame(opened, open.gate.boot, "a forget from Open")
        assertTrue(open.sheets.reads == 1 && open.store.erases == 0, "a forget from Open touched the store")
        assertTrue(open.session.words == PUBLIC_WORDS_24, "a forget from Open cleared the session")

        val fresh = AppLaunch(FakeSecureStore())
        fresh.session.restoreFromWords(PUBLIC_WORDS_12) // the nav host imported a wallet in this launch
        assertEquals(SaveState.Saved, fresh.session.saved, "the import")
        fresh.gate.forget("test")
        assertEquals(Boot.Onboarding, fresh.gate.boot, "a forget from Onboarding")
        assertTrue(fresh.sheets.reads == 0 && fresh.store.erases == 0, "a forget from Onboarding touched the store")
        assertTrue(fresh.session.words == PUBLIC_WORDS_12, "a forget from Onboarding cleared the wallet made in this launch")
    }

    // N1c, the review of 2026-11-05. Both ways back to the 24 words forget the saved wallet: the recovery screen's one button, which a
    // removed screen lock leads to on Android, and the unlock screen's link. The forget erased every guard line, so the same words
    // imported again could pay while a payment of unknown outcome may be out. The guard file holds no key and is keyed by fingerprint,
    // so it stays: the same words take their guard back, and another wallet is not stopped by it.
    @Test
    fun forgetKeepsTheGuardFileSoTheSameWordsStillRefuseAndAnotherWalletDoesNot(): Unit = runBlocking {
        val starts = listOf(
            "the recovery screen with the key gone" to FakeSecureStore(probe = StoreProbe.KeyGone, marker = Marker.Kept),
            "the unlock screen's link" to FakeSecureStore(marker = Marker.Kept).also { it.write(v1Bytes(), WriteMode.Create) },
        )
        for ((case, store) in starts) {
            val pending = UnconfirmedSend(TEST_TXID, SENT_AT)
            val guards = FakePlainFile()
            assertEquals(Claim.Written, PendingPayments(guards).claim(TEST_CHECK, pending), "the guard written before a post that ended unknown")
            val wallets = Wallets(
                OnePlaceShelf(store),
                restoreKey = { words, _, _, _ -> SoftwareAccountKey(words, TEST_CHECK, "tpub-test", "m/48'/1'/0'/2'") },
                forgetScans = {},
                payments = PendingPayments(guards),
            )
            val gate = WalletGateState(wallets)
            gate.forget("test")
            assertEquals(Boot.Onboarding, gate.boot, case)
            assertEquals(pending, guards.guardOf(), "the guard file after the forget, $case")
            val session = wallets.at(Place.FIRST).session
            session.restoreFromWords(PUBLIC_WORDS_24)
            assertEquals(pending, session.unconfirmedSend(), "the same words imported again, $case")
            assertEquals(pending, assertIs<Claim.Taken>(session.recordUnconfirmedSend("f".repeat(64), SENT_AT + 600), case).earlier)
            val other = OnboardingSession(restoreKey = { words, _, _, _ -> SoftwareAccountKey(words, OTHER_CHECK, "tpub-test", "m/48'/1'/0'/2'") }, payments = PendingPayments(guards))
            other.restoreFromWords(PUBLIC_WORDS_12)
            assertNull(other.unconfirmedSend(), "another wallet, $case")
            assertEquals(Claim.Written, other.recordUnconfirmedSend("e".repeat(64), SENT_AT + 600), "another wallet's payment, $case")
        }
    }

    // R8 H1, the review of 2026-11-06. A Forget from Locked proved the owner by opening the record and dropped what it read, so an
    // older record's guard (flag 8) went with the erase.
    @Test
    fun forgetFromLockedKeepsAnOlderRecordsGuard(): Unit = runBlocking {
        val pending = UnconfirmedSend(TEST_TXID, SENT_AT)
        val store = FakeSecureStore(probe = StoreProbe.Present, marker = Marker.Kept)
        store.write(oldRecordBytes(pending), WriteMode.Create)
        val guards = FakePlainFile()
        val gate = WalletGateState(Wallets(OnePlaceShelf(store), payments = PendingPayments(guards)))
        gate.forget("test")
        assertEquals(Boot.Onboarding, gate.boot, "the forget")
        assertNotNull(guards.guardOf(), "the older record's guard was dropped by Forget")
        assertEquals(pending, guards.guardOf(), "the older record's guard after Forget")
    }

    // R8, the rule behind S1 and H1. An older record's guard leaves the record only for the guard file, so a Forget that cannot move it
    // is refused as a failed erase is. The record and its guard stay, and the screen says the forget failed. The unlock screen's link
    // reads the guard in its owner check, and the recovery screen has the one a record showed when it opened but did not install.
    @Test
    fun aForgetThatCannotMoveAnOlderRecordsGuardErasesNothingAndSaysItFailed(): Unit = runBlocking {
        val pending = UnconfirmedSend(TEST_TXID, SENT_AT)
        val cannotMove = listOf(
            "a damaged guard file" to { FakePlainFile().apply { text = "garbage\n" } },
            "a guard file that cannot be written" to { FakePlainFile().apply { failWrite = true } },
            "another payment's guard in the file" to {
                FakePlainFile().also { assertEquals(Claim.Written, PendingPayments(it).claim(TEST_CHECK, UnconfirmedSend("f".repeat(64), SENT_AT))) }
            },
        )
        for ((file, guardFile) in cannotMove) {
            for (recovery in listOf(false, true)) {
                val case = (if (recovery) "the recovery screen" else "the unlock screen's link") + ", $file"
                val app = OlderRecordLaunch(pending, guardFile(), derivesTo = if (recovery) OTHER_CHECK else TEST_CHECK)
                if (recovery) app.gate.unlock("test")
                val boot = app.gate.boot
                val text = app.guards.text
                app.gate.forget("test")
                assertSame(boot, app.gate.boot, "a refused forget left the screen, $case")
                assertEquals("Unavailable(Other)", label(app.gate.lastAttempt), "what the screen says, $case")
                assertEquals(0, app.store.erases, "erases, $case")
                assertEquals(pending, assertIs<UnlockResult.Opened>(SavedWallet(app.store).open("test"), case).oldGuard, "the record's guard, $case")
                assertEquals(text, app.guards.text, "the guard file, $case")
            }
        }
        for (recovery in listOf(false, true)) {
            val app = OlderRecordLaunch(pending, FakePlainFile(), derivesTo = if (recovery) OTHER_CHECK else TEST_CHECK)
            if (recovery) {
                app.gate.unlock("test")
                assertEquals(Cause.Corrupt, assertIs<Boot.Broken>(app.gate.boot, "a record whose key derives to another wallet").cause)
            }
            app.gate.forget("test")
            assertEquals(Boot.Onboarding, app.gate.boot, "a forget whose guard moved, recovery $recovery")
            assertEquals(pending, app.guards.guardOf(TEST_CHECK), "the guard under the record's fingerprint, recovery $recovery")
        }
    }

    @Test
    fun aFreshInstallStartsOnboardingAndAnOrphanIsWipedWithItsNotice() {
        val starts = listOf(
            Start("a fresh install", Marker.Absent, StoreProbe.Empty, Boot.Onboarding, null),
            Start("an orphan from a deleted install", Marker.Absent, StoreProbe.Present, Boot.Onboarding, StartNotice.OrphanWiped),
            Start("a wallet the OS removed", Marker.Kept, StoreProbe.Empty, Boot.Onboarding, StartNotice.WalletVanished),
            Start("a wallet kept from an earlier launch", Marker.Kept, StoreProbe.Present, Boot.Locked, null),
            Start("an orphan whose wipe failed", Marker.Absent, StoreProbe.Present, Boot.Locked, null, failedErase(Why.TryLater)),
        )
        for (s in starts) {
            val app = AppLaunch(FakeSecureStore(probe = s.probe, marker = s.marker, erase = s.erase))
            assertEquals(s.boot, app.gate.boot, s.case)
            assertEquals(s.notice, app.gate.notice, s.case)
            assertEquals(0, app.sheets.reads, "the cold start asked for the owner, ${s.case}")
        }
    }

    @Test
    fun randomWalksKeepTheGateRules(): Unit = runBlocking {
        val seen = mutableSetOf<String>()
        for (walk in 0 until WALKS) {
            val random = Random(walk)
            val (phone, store) = startPhone(random)
            val app = AppLaunch(store)
            val trail = mutableListOf(phone)
            repeat(STEPS) {
                val before = app.gate.boot
                val erases = app.store.erases
                val served = app.store.served.size
                val forget = random.nextBoolean()
                val (answer, setUp) = ANSWERS.random(random)
                app.keys.fingerprint = TEST_CHECK
                setUp(app)
                val eraseFails = random.nextInt(6) == 0
                app.store.erase = if (eraseFails) failedErase(Why.TryLater) else EraseResult.Erased
                if (forget) app.gate.forget("walk") else app.gate.unlock("walk")
                trail += (if (forget) "forget: " else "unlock: ") + answer + (if (eraseFails) ", erase fails" else "")
                val case = "walk $walk, ${trail.joinToString("; ")}"
                val after = app.gate.boot
                seen += "${kind(before)} to ${kind(after)}"
                if (after is Boot.Open) {
                    assertTrue(app.session.words == PUBLIC_WORDS_24, "open without the saved key, $case")
                } else {
                    assertNothingInstalled(app.session, case)
                }
                if (app.store.erases > erases) {
                    val ruleKept = before is Boot.Broken || (before == Boot.Locked && app.store.served.size > served)
                    assertTrue(forget && ruleKept, "erased without passing the forget rule, $case")
                }
                if (before is Boot.Open) assertTrue(after != Boot.Locked, "Open went back to Locked, $case")
            }
        }
        val edges = listOf(
            "Locked to Locked", "Locked to Open", "Locked to Broken", "Locked to Onboarding",
            "Broken to Broken", "Broken to Onboarding", "Open to Open", "Onboarding to Onboarding",
        )
        for (edge in edges) assertTrue(edge in seen, "no walk went $edge")
    }
}

/** Another wallet's check value, distinct from [TEST_CHECK]. */
private const val OTHER_CHECK = "9f8e7d6c"

private const val SENT_AT = 1_792_740_720L

/** The model test replays this many seeded walks of [STEPS] steps each. */
private const val WALKS = 1_000
private const val STEPS = 12

/** The answers that keep a wallet locked, when the owner cancelled, failed or is locked out, or the phone cannot ask now. */
private val REFUSALS: List<ReadResult> =
    listOf(ReadResult.Cancelled, ReadResult.Denied, ReadResult.LockedOut) + Why.entries.map { ReadResult.Unavailable(it) }

/** A saved copy that can never open, with its cause. Fresh each call, because opening zeroes the bytes it was served. */
private fun brokenAnswers() = listOf(
    Triple("not found", ReadResult.NotFound, Cause.Missing),
    Triple("key gone", ReadResult.KeyGone, Cause.KeyGone),
    Triple("unreadable", ReadResult.Unreadable, Cause.Corrupt),
    Triple("a newer version", ReadResult.Bytes(v1Bytes(version = 2), ANY_VERSION), Cause.NewerVersion),
    Triple("a MAIN record", ReadResult.Bytes(v1Bytes(network = 1), ANY_VERSION), Cause.UnsupportedNetwork),
)

/** What the phone answers in one step of a walk, named for the failure message. */
private val ANSWERS: List<Pair<String, (AppLaunch) -> Unit>> = listOf(
    "the owner" to { app: AppLaunch -> app.store.read = null },
    "a copy whose key derives to another wallet" to { app: AppLaunch ->
        app.store.read = null
        app.keys.fingerprint = OTHER_CHECK
    },
    "unreadable" to { app: AppLaunch -> app.store.read = ReadResult.Unreadable },
    "Cancelled" to { app: AppLaunch -> app.store.read = ReadResult.Cancelled },
    "Denied" to { app: AppLaunch -> app.store.read = ReadResult.Denied },
    "LockedOut" to { app: AppLaunch -> app.store.read = ReadResult.LockedOut },
    "Unavailable(TryLater)" to { app: AppLaunch -> app.store.read = ReadResult.Unavailable(Why.TryLater) },
)

/** A phone a walk can start on, fresh each call, with its name for the failure message. */
private fun startPhone(random: Random): Pair<String, FakeSecureStore> = when (random.nextInt(5)) {
    0 -> "saved" to savedPhone()
    1 -> "saved and backed up" to savedPhone(testRecord(backupConfirmed = true))
    2 -> "saved, met on a doubtful probe" to savedPhone().apply {
        marker = Marker.Absent
        probe = StoreProbe.Unavailable(Why.TryLater)
    }
    3 -> "a key gone" to FakeSecureStore(probe = StoreProbe.KeyGone, marker = Marker.Kept)
    else -> "a fresh install" to FakeSecureStore()
}

/** A phone where an earlier launch saved [record]. The store holds it under a kept marker, so this launch starts Locked. */
private fun savedPhone(record: WalletRecord = testRecord()): FakeSecureStore {
    val store = FakeSecureStore()
    assertIs<SaveResult.Written>(SavedWallet(store).save(record, WriteMode.Create), "the earlier launch's save")
    store.writes.clear()
    store.markerWrites.clear()
    return store
}

/** One launch of the app on a phone, wired as WalletGate will wire it, with one wallet under both the session and the gate. */
private class AppLaunch(val store: FakeSecureStore) {
    val keys = Keys()
    val sheets = Sheets(store)
    private val wallets = Wallets(OnePlaceShelf(sheets), restoreKey = keys.restoreKey)
    val session: OnboardingSession get() = wallets.at(Place.FIRST).session
    val gate = WalletGateState(wallets)
}

/**
 * A launch on a phone whose record a build before the guard file saved with [pending] as its guard (flag 8), and whose guard file is
 * [guards]. The record's words derive to [derivesTo], so any other fingerprint than the record's makes its unlock fail to install.
 */
private class OlderRecordLaunch(pending: UnconfirmedSend, val guards: FakePlainFile, derivesTo: String) {
    val store = FakeSecureStore(marker = Marker.Kept).also { it.write(oldRecordBytes(pending), WriteMode.Create) }
    val gate = WalletGateState(
        Wallets(
            OnePlaceShelf(store),
            restoreKey = { words, _, _, _ -> SoftwareAccountKey(words, derivesTo, "tpub-test", "m/48'/1'/0'/2'") },
            forgetScans = {},
            payments = PendingPayments(guards),
        ),
    )
}

/** Restores that never load the native library. Each answers [fingerprint] and is counted, because a restore is the derive an install runs. */
private class Keys {
    var fingerprint = TEST_CHECK
    var restores = 0
        private set
    val restoreKey: (List<String>, String, KeyNetwork, UInt) -> SoftwareAccountKey = { words, _, _, _ ->
        restores++
        SoftwareAccountKey(words, fingerprint, "tpub-test", "m/48'/1'/0'/2'")
    }
}

/** Each read is the OS owner sheet on a phone, so this counts them, and [hold] keeps one up until the test completes it. */
private class Sheets(private val store: FakeSecureStore) : SecureStore by store {
    var reads = 0
        private set
    var hold: CompletableDeferred<Unit>? = null

    override suspend fun read(reason: String): ReadResult {
        reads++
        hold?.await()
        return store.read(reason)
    }
}

/** One cold start, with what the phone holds, where the gate must begin and what it must say. */
private class Start(
    val case: String,
    val marker: Marker,
    val probe: StoreProbe,
    val boot: Boot,
    val notice: StartNotice?,
    val erase: EraseResult = EraseResult.Erased,
)

/** Nothing was installed, so the session holds no key that a screen could show or spend. */
private fun assertNothingInstalled(session: OnboardingSession, case: String) {
    assertTrue(session.words.isEmpty() && session.fingerprint.isEmpty() && session.passphrase.isEmpty() && session.xpub.isEmpty(), "a key is in the session, $case")
    assertEquals(SaveState.NotSaved, session.saved, "saved, $case")
}

/** backupConfirmed, imported, appLockEnabled, in that order. */
private fun WalletRecord.flags() = listOf(backupConfirmed, imported, appLockEnabled)

private fun OnboardingSession.flags() = listOf(backupConfirmed, imported, appLockEnabled)

/** A boot's variant, naming the edges the walks must cover. */
private fun kind(boot: Boot): String = when (boot) {
    Boot.Onboarding -> "Onboarding"
    Boot.Locked -> "Locked"
    is Boot.Broken -> "Broken"
    is Boot.Open -> "Open"
}

/** A result's name for a failure message. Unavailable and Broken are plain classes, so their own toString would not say why. */
private fun label(r: ReadResult): String = if (r is ReadResult.Unavailable) "Unavailable(${r.why})" else r.toString()

private fun label(r: UnlockResult?): String = when (r) {
    is UnlockResult.Unavailable -> "Unavailable(${r.why})"
    is UnlockResult.Broken -> "Broken(${r.cause})"
    is UnlockResult.Opened -> "Opened"
    else -> r.toString()
}
