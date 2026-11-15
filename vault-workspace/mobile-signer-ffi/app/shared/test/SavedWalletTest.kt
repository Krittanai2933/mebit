package com.mebit.app.data

import com.mebit.app.Marker
import com.mebit.app.ReadResult
import com.mebit.app.StoreProbe
import com.mebit.app.Why
import com.mebit.app.WriteMode
import com.mebit.app.WriteResult
import com.mebit.ffi.signer.KeyNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SavedWalletTest {
    @Test
    fun aNewWalletWhoseMarkerCannotBeWrittenIsNotKeptButAReplaceIsStillWritten() {
        val store = FakeSecureStore(failMarker = setOf(1))
        assertTrue(SavedWallet(store).save(testRecord(), WriteMode.Create) is SaveResult.NotSaved, "a new wallet without its marker")
        assertEquals(1, store.erases, "the wallet it wrote was not undone")
        assertEquals(StoreProbe.Empty, store.probe(), "the wallet stayed")
        val kept = FakeSecureStore(failMarker = setOf(2)) // the Replace cannot rewrite a marker that is already Kept
        val wallet = SavedWallet(kept)
        val first = assertIs<SaveResult.Written>(wallet.save(testRecord(), WriteMode.Create), "the first save")
        assertIs<SaveResult.Written>(wallet.save(testRecord(backupConfirmed = true), WriteMode.Replace(first.version)), "a Replace whose marker write failed")
        assertEquals(0, kept.erases, "a Replace erased the wallet")
    }

    @Test
    fun aConflictWritesNoMarkerAndErasesNothing() {
        val store = FakeSecureStore(write = WriteResult.Conflict)
        assertEquals(SaveResult.Conflict, SavedWallet(store).save(testRecord(), WriteMode.Replace(ANY_VERSION)))
        assertEquals(1, store.writes.size, "writes")
        assertTrue(store.markerWrites.isEmpty() && store.erases == 0, "a Conflict touched the marker or erased")
    }

    @Test
    fun openCarriesTheVersionOfTheBytesItRead() {
        val store = FakeSecureStore()
        val wallet = SavedWallet(store)
        val written = assertIs<SaveResult.Written>(wallet.save(testRecord(), WriteMode.Create), "save")
        val first = assertIs<UnlockResult.Opened>(wallet.openNow(), "first open").version
        assertEquals(written.version, first, "the version save returned")
        assertEquals(first, assertIs<UnlockResult.Opened>(wallet.openNow(), "second open").version, "two opens with no write between")
        assertIs<SaveResult.Written>(wallet.save(testRecord(backupConfirmed = true), WriteMode.Replace(first)), "the Replace")
        assertNotEquals(first, assertIs<UnlockResult.Opened>(wallet.openNow(), "open after a write").version, "a write between")
    }

    @Test
    fun anOpenedWalletIsMarkedKeptSoNoLaterLaunchCanTakeItForAnOrphan() {
        val store = FakeSecureStore(marker = Marker.Absent)
        store.write(v1Bytes(), WriteMode.Create)
        assertIs<UnlockResult.Opened>(SavedWallet(store).openNow(), "the unlock")
        assertEquals(listOf(Marker.Kept), store.markerWrites, "marker writes after an unlock")
    }

    /** What each read must become, from the design's table. No else branch, so a new ReadResult does not compile here until it is decided. */
    private fun expected(read: ReadResult): String = when (read) {
        is ReadResult.Bytes -> "Opened"
        ReadResult.NotFound -> "Broken(Missing)"
        ReadResult.Cancelled -> "Cancelled"
        ReadResult.Denied -> "Denied"
        ReadResult.LockedOut -> "LockedOut"
        ReadResult.KeyGone -> "Broken(KeyGone)"
        ReadResult.Unreadable -> "Broken(Corrupt)"
        is ReadResult.Unavailable -> "Unavailable(${read.why})"
    }

    private fun shown(result: UnlockResult): String = when (result) {
        is UnlockResult.Opened -> "Opened"
        is UnlockResult.Broken -> "Broken(${result.cause})"
        is UnlockResult.Unavailable -> "Unavailable(${result.why})"
        else -> result.toString()
    }

    @Test
    fun everyReadResultMapsToExactlyOneUnlockResult() {
        val reads = listOf(
            ReadResult.Bytes(v1Bytes(), ANY_VERSION), ReadResult.NotFound, ReadResult.Cancelled, ReadResult.Denied,
            ReadResult.LockedOut, ReadResult.KeyGone, ReadResult.Unreadable,
        ) + Why.entries.map { ReadResult.Unavailable(it) }
        for (read in reads) {
            val case = expected(read)
            val store = FakeSecureStore(read = read)
            assertEquals(case, shown(SavedWallet(store).openNow()), case)
            assertTrue(store.writes.isEmpty() && store.erases == 0, "open wrote or erased, $case")
            // An unlock is the one answer that marks the wallet as the owner's, so a later launch cannot take it for an orphan.
            assertEquals(if (case == "Opened") listOf(Marker.Kept) else emptyList(), store.markerWrites, "marker writes after open, $case")
        }
    }

    @Test
    fun bytesAreZeroedAfterDecode() {
        val reads = listOf(
            "Opened" to v1Bytes(),
            "Broken(Corrupt)" to v1Bytes().copyOf(20),
            "Broken(NewerVersion)" to v1Bytes(version = 2),
            "Broken(UnsupportedNetwork)" to v1Bytes(network = 1),
        )
        for ((outcome, bytes) in reads) {
            val store = FakeSecureStore(read = ReadResult.Bytes(bytes, ANY_VERSION))
            assertEquals(outcome, shown(SavedWallet(store).openNow()), outcome)
            assertTrue(store.served.single().isZeroed(), "bytes left after $outcome")
        }
    }

    @Test
    fun saveZeroesItsEncodedBytes() {
        val answers = listOf(
            "Written" to WriteResult.Written(ANY_VERSION),
            "AlreadyExists" to WriteResult.AlreadyExists,
            "Unavailable" to WriteResult.Unavailable(Why.TryLater),
        )
        for ((name, answer) in answers) {
            val store = FakeSecureStore(write = answer)
            SavedWallet(store).save(testRecord(), WriteMode.Create)
            val write = store.writes.single()
            assertFalse(write.bytes.isZeroed(), "the store got no record, $name")
            assertTrue(write.handed.isZeroed(), "bytes left after $name")
        }
    }

    @Test
    fun mainnetIsRefusedOnSaveAndOnOpen() {
        val store = FakeSecureStore()
        val saved = SavedWallet(store).save(testRecord(network = KeyNetwork.MAIN), WriteMode.Create)
        assertEquals(Why.Other, assertIs<SaveResult.NotSaved>(saved, "save MAIN").why)
        assertTrue(store.writes.isEmpty() && store.markerWrites.isEmpty(), "save MAIN wrote")
        val opened = SavedWallet(FakeSecureStore(read = ReadResult.Bytes(v1Bytes(network = 1), ANY_VERSION))).openNow()
        assertEquals(Cause.UnsupportedNetwork, assertIs<UnlockResult.Broken>(opened, "open MAIN").cause)
    }

    @Test
    fun createNeverOverwrites() {
        val store = FakeSecureStore(write = WriteResult.AlreadyExists)
        assertEquals(SaveResult.AlreadyExists, SavedWallet(store).save(testRecord(), WriteMode.Create))
        assertEquals(listOf(WriteMode.Create), store.writes.map { it.mode })
        assertTrue(store.markerWrites.isEmpty() && store.erases == 0, "something else was written")
    }

    @Test
    fun aNewerRecordIsNeverReplaced() {
        val store = FakeSecureStore(read = ReadResult.Bytes(v1Bytes(version = 2), ANY_VERSION))
        assertEquals(Cause.NewerVersion, assertIs<UnlockResult.Broken>(SavedWallet(store).openNow(), "newer").cause)
        assertTrue(store.writes.isEmpty() && store.erases == 0 && store.markerWrites.isEmpty(), "open changed the store")
    }

    @Test
    fun everyWriteResultMapsToExactlyOneSaveResult() {
        assertIs<SaveResult.Written>(SavedWallet(FakeSecureStore()).save(testRecord(), WriteMode.Create))
        val taken = FakeSecureStore(write = WriteResult.AlreadyExists)
        assertEquals(SaveResult.AlreadyExists, SavedWallet(taken).save(testRecord(), WriteMode.Create))
        assertEquals(SaveResult.Conflict, SavedWallet(FakeSecureStore(write = WriteResult.Conflict)).save(testRecord(), WriteMode.Replace(ANY_VERSION)))
        for (why in Why.entries) {
            val result = SavedWallet(FakeSecureStore(write = WriteResult.Unavailable(why))).save(testRecord(), WriteMode.Replace(ANY_VERSION))
            assertEquals(why, assertIs<SaveResult.NotSaved>(result, "Unavailable($why)").why, "Unavailable($why)")
        }
    }

    @Test
    fun aFailedWriteErasesNothingAndKeepsTheSavedCopy() {
        val store = FakeSecureStore(failWrite = 2)
        val wallet = SavedWallet(store)
        val first = assertIs<SaveResult.Written>(wallet.save(testRecord(), WriteMode.Create))
        val failed = wallet.save(testRecord(backupConfirmed = true), WriteMode.Replace(first.version))
        assertEquals(Why.Other, assertIs<SaveResult.NotSaved>(failed, "second write").why)
        assertEquals(0, store.erases)
        assertEquals(listOf(Marker.Kept), store.markerWrites)
        assertFalse(assertIs<UnlockResult.Opened>(wallet.openNow(), "the first copy").record.backupConfirmed, "the failed write replaced the copy")
    }
}
