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
import com.mebit.app.androidReadRefusal
import com.mebit.ffi.signer.HotScan
import com.mebit.ffi.signer.HotWalletSnapshot
import com.mebit.ffi.signer.SoftwareAccountKey
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Twelve test words for wallet [n]. Never a real wallet, and never printed. */
private fun words(n: Int): List<String> = List(11) { "abandon" } + "word$n"

private fun check(words: List<String>): String = words.joinToString(" ").hashCode().toUInt().toString(16).padStart(8, '0')

/** Places over [FakeShelf] whose one-place read runs [androidReadRefusal] first, as KeystorePlace.read does, so a place with no file answers NotFound before any sheet. */
private class AndroidLikeShelf(val inner: FakeShelf) : SecureShelf {
    override fun store(place: Place): SecureStore = object : SecureStore by inner.store(place) {
        override suspend fun read(reason: String): ReadResult {
            androidReadRefusal(30, inner.places.getValue(place).probe == StoreProbe.Present, aliasPresent = true)?.let { return it }
            return inner.store(place).read(reason)
        }
    }

    override suspend fun readEvery(reason: String): EveryRead = inner.readEvery(reason)

    override fun eraseEvery(): Boolean = inner.eraseEvery()
}

class WalletRemovalAndroidTest {
    @Test
    fun aWalletWhoseFileWasNeverWrittenIsNotRemoved(): Unit = runBlocking {
        val fake = FakeShelf()
        assertIs<SaveResult.Written>(SavedWallet(fake.places.getValue(Place.FIRST)).save(testRecord(words = words(1), fingerprint = check(words(1))), WriteMode.Create))
        val wallets = Wallets(
            AndroidLikeShelf(fake),
            forgetBook = {},
            forgetScans = {},
            newKey = { _, _, _ -> SoftwareAccountKey(words(2), check(words(2)), "tpub", "m/48'/1'/0'/2'") },
            restoreKey = { w, _, _, _ -> SoftwareAccountKey(w, check(w), "tpub", "m/48'/1'/0'/2'") },
            newChain = { SingleSigWallet(snapshot = { _, _, n, _ -> HotWalletSnapshot("tb1q", 0uL, 0uL, n, HotScan.COMPLETE) }, fetchHistory = { _, _, _, _ -> emptyList() }) },
        )
        assertIs<Every.Open>(wallets.unlockEvery("test"))
        val book = SavedVaults(FakeVaultsStorage(), owners = { wallets.vaultOwners() }, deriveAddress = { _, _ -> "addr" }).apply { sync() }
        val w = wallets.beginAdd()!!
        fake.places.getValue(w.place).write = WriteResult.Unavailable(Why.Other) // KeystorePlace.write: an IOException from AtomicFile
        w.session.generateThisDevice() // Key Created shows these words next
        assertEquals(WalletRemoval.NotErased, wallets.remove(w, book, "test") {}, "a key held only in memory is not dropped by Remove")
        assertEquals(words(2), w.session.words)
        assertTrue(w in wallets.listed)
    }
}
