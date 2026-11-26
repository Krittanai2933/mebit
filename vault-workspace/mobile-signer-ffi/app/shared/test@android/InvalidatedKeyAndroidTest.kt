package com.mebit.app.data

import com.mebit.app.EveryRead
import com.mebit.app.Place
import com.mebit.app.ReadResult
import com.mebit.app.SecureShelf
import com.mebit.app.SecureStore
import com.mebit.app.StoreProbe
import com.mebit.app.Why
import com.mebit.app.WriteMode
import com.mebit.app.WriteResult
import com.mebit.app.androidInitOutcome
import com.mebit.app.androidKeyMustBeRemade
import com.mebit.app.androidProbeOutcome
import com.mebit.app.navigation.Boot
import com.mebit.app.navigation.WalletGateState
import com.mebit.ffi.signer.HotScan
import com.mebit.ffi.signer.HotWalletSnapshot
import com.mebit.ffi.signer.SoftwareAccountKey
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Twelve test words for wallet [n]. Never a real wallet, and never printed. */
private fun words(n: Int): List<String> = List(11) { "abandon" } + "word$n"

private fun check(words: List<String>): String = words.joinToString(" ").hashCode().toUInt().toString(16).padStart(8, '0')

/**
 * The Keystore of an API 30 phone over [FakeShelf]'s files. [failure] is what taking the private half throws, null for a working key.
 * Probe, read and write ask the shelf's real rules in KeystoreShelf's order. A write the rules send to a new key drops the old one
 * ([remade]) and answers TryLater, as the background generation does, and the next write seals.
 */
private class Api30Shelf(val inner: FakeShelf) : SecureShelf {
    var failure: Throwable? = null
    var remade = 0
        private set

    override fun store(place: Place): SecureStore = object : SecureStore by inner.store(place) {
        override fun probe(): StoreProbe =
            androidProbeOutcome(fileExists = inner.places.values.any { it.probe == StoreProbe.Present }, aliasPresent = true, privateHalfFailure = failure, deviceSecure = true)

        override suspend fun read(reason: String): ReadResult = failure?.let(::androidInitOutcome) ?: inner.store(place).read(reason)

        override fun write(bytes: ByteArray, mode: WriteMode): WriteResult {
            if (androidKeyMustBeRemade(certificatePresent = true, privateHalfFailure = failure)) {
                failure = null
                remade++
                return WriteResult.Unavailable(Why.TryLater)
            }
            return inner.store(place).write(bytes, mode)
        }
    }

    override suspend fun readEvery(reason: String): EveryRead =
        failure?.let { f -> EveryRead.Found(inner.places.filterValues { it.probe == StoreProbe.Present }.mapValues { androidInitOutcome(f) }) } ?: inner.readEvery(reason)

    override fun eraseEvery(): Boolean = inner.eraseEvery()
}

/** What API 30's getKey throws once removing the screen lock invalidated the key: the invalidation's message and no cause. */
private fun api30Invalidated() = UnrecoverableKeyException("User changed or deleted their auth credentials")

private fun walletsOver(shelf: SecureShelf) = Wallets(
    shelf,
    forgetBook = {},
    forgetScans = {},
    restoreKey = { w, _, _, _ -> SoftwareAccountKey(w, check(w), "tpub", "m/48'/1'/0'/2'") },
    newChain = { SingleSigWallet(snapshot = { _, _, n, _ -> HotWalletSnapshot("tb1q", 0uL, 0uL, n, HotScan.COMPLETE) }, fetchHistory = { _, _, _, _ -> emptyList() }) },
)

/** A phone with wallets 1 and 3 saved at places 1 and 3. */
private fun phoneWithTwo(): Api30Shelf {
    val fake = FakeShelf()
    for (n in listOf(1, 3)) {
        val place = Place.parse(n)!!
        assertIs<SaveResult.Written>(SavedWallet(fake.places.getValue(place)).save(testRecord(words = words(n), fingerprint = check(words(n))), WriteMode.Create))
    }
    return Api30Shelf(fake)
}

class InvalidatedKeyAndroidTest {
    // A lock removed and set again during one Settings visit: the gate's return finds the alias with a dead key and saves every wallet again.
    @Test
    fun aReturnThatFindsTheKeyInvalidatedSavesEveryWalletAgainUnderANewKey(): Unit = runBlocking {
        val shelf = phoneWithTwo()
        val wallets = walletsOver(shelf)
        assertIs<Every.Open>(wallets.unlockEvery("test"))
        val places = wallets.all.map { it.place }
        assertFalse(wallets.lockRead(lockPresent = true), "a working key owes no save")
        assertTrue(places.all { shelf.inner.places.getValue(it).writes.size == 1 }, "a return with a working key wrote")

        shelf.failure = api30Invalidated()
        assertTrue(wallets.lockRead(lockPresent = true), "no save owed while the new key is made")
        assertEquals(1, shelf.remade, "the invalidated key was not replaced")
        assertFalse(wallets.lockRead(lockPresent = true), "a save still owed once the new key is ready")
        for (place in places) {
            assertEquals(listOf("Create", "Replace"), shelf.inner.places.getValue(place).modes(), "$place's writes")
        }
        assertTrue(wallets.listed.all { it.session.saved == SaveState.Saved }, "a wallet is not saved")
        val cold = walletsOver(shelf)
        assertIs<Every.Open>(cold.unlockEvery("test"))
        assertEquals(places.map { check(words(it.number)) }, cold.listed.map { it.session.fingerprint }, "a cold start does not open the same wallets")
    }

    // A key that fails for another reason, as API 30's getKey on a locked phone, is never replaced and owes no save.
    @Test
    fun aReturnThatFindsTheKeyFailingForAnotherReasonWritesNothing(): Unit = runBlocking {
        val shelf = phoneWithTwo()
        val wallets = walletsOver(shelf)
        assertIs<Every.Open>(wallets.unlockEvery("test"))
        shelf.failure = UnrecoverableKeyException("Failed to obtain information about key").apply { initCause(KeyStoreException("LOCKED")) }
        assertFalse(wallets.lockRead(lockPresent = true))
        assertEquals(0, shelf.remade, "a key that is not invalidated was replaced")
        assertTrue(wallets.all.all { shelf.inner.places.getValue(it.place).writes.size == 1 }, "a return wrote")
    }

    // The lock removed and set back while the app was not running: no wallet is in memory, so the cold start shows recovery, not try later.
    @Test
    fun aColdStartBesideAnInvalidatedKeyShowsTheRecoveryScreen() {
        val shelf = phoneWithTwo()
        shelf.failure = api30Invalidated()
        val gate = WalletGateState(walletsOver(shelf))
        assertEquals(Cause.KeyGone, assertIs<Boot.Broken>(gate.boot, "the cold start").cause)
        assertEquals(0, shelf.remade, "a cold start replaced the key")
    }
}
