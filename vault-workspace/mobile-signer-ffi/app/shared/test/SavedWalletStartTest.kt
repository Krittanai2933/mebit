package com.mebit.app.data

import com.mebit.app.EraseResult
import com.mebit.app.Marker
import com.mebit.app.StoreProbe
import com.mebit.app.Why
import com.mebit.app.WriteMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SavedWalletStartTest {
    @Test
    fun wipeOrphanErasesWritesEmptyAndReportsTheNoticeOnce() {
        for (orphan in listOf(StoreProbe.Present, StoreProbe.KeyGone)) {
            val case = label(orphan)
            val store = FakeSecureStore(probe = orphan, marker = Marker.Absent)
            val wallet = SavedWallet(store)
            val first = wallet.start()
            assertEquals(StartNotice.OrphanWiped, first.notice, case)
            assertEquals(StoreProbe.Empty, first.probe, "probe after the wipe, $case")
            assertEquals(1, store.erases, case)
            assertEquals(listOf(Marker.Empty), store.markerWrites, case)
            val next = wallet.start()
            assertNull(next.notice, "the notice came back, $case")
            assertEquals(1, store.erases, "erased again, $case")
            assertEquals(listOf(Marker.Empty), store.markerWrites, "marker written again, $case")
        }
    }

    @Test
    fun vanishedWritesEmptyAndReportsTheNotice() {
        val store = FakeSecureStore(probe = StoreProbe.Empty, marker = Marker.Kept)
        val wallet = SavedWallet(store)
        val first = wallet.start()
        assertEquals(StartNotice.WalletVanished, first.notice)
        assertEquals(StoreProbe.Empty, first.probe)
        assertEquals(listOf(Marker.Empty), store.markerWrites)
        assertEquals(0, store.erases)
        assertNull(wallet.start().notice, "the notice came back")
    }

    @Test
    fun aFailedEraseKeepsTheWalletAndStopsTryingToWipeIt() {
        val store = FakeSecureStore(probe = StoreProbe.Present, marker = Marker.Absent, erase = EraseResult.Failed(Why.TryLater))
        val wallet = SavedWallet(store)
        val start = wallet.start()
        assertNull(start.notice)
        assertEquals(StoreProbe.Present, start.probe)
        assertEquals(listOf(Marker.Kept), store.markerWrites, "the failed wipe must end in a kept marker")
        wallet.start()
        assertEquals(1, store.erases, "the next launch tried the wipe again")
    }

    @Test
    fun saveWritesTheKeptMarkerAndForgetWritesEmpty() {
        val store = FakeSecureStore(marker = Marker.Empty)
        val wallet = SavedWallet(store)
        wallet.save(testRecord(), WriteMode.Create)
        assertEquals(listOf(Marker.Kept), store.markerWrites, "after save")
        assertTrue(wallet.forget(), "forget")
        assertEquals(listOf(Marker.Kept, Marker.Empty), store.markerWrites, "after forget")
        store.erase = EraseResult.Failed(Why.Other)
        assertFalse(wallet.forget(), "a failed erase")
        assertEquals(listOf(Marker.Kept, Marker.Empty), store.markerWrites, "after a failed erase")
    }

    @Test
    fun androidUnsupportedMarkerNeverChangesAnything() {
        for (probe in EVERY_PROBE) {
            val store = FakeSecureStore(probe = probe, marker = Marker.Unsupported)
            val start = SavedWallet(store).start()
            assertSame(probe, start.probe, label(probe))
            assertNull(start.notice, label(probe))
            assertTrue(store.erases == 0 && store.markerWrites.isEmpty(), "the store changed, ${label(probe)}")
        }
    }

    @Test
    fun aFirstLaunchWithNothingSavedWritesEmpty() {
        val store = FakeSecureStore(probe = StoreProbe.Empty, marker = Marker.Absent)
        val start = SavedWallet(store).start()
        assertNull(start.notice)
        assertEquals(listOf(Marker.Empty), store.markerWrites)
        assertEquals(0, store.erases)
    }

    @Test
    fun aWalletUnderAnEmptyMarkerIsMarkedKeptNeverWiped() {
        val store = FakeSecureStore(probe = StoreProbe.Present, marker = Marker.Empty)
        val start = SavedWallet(store).start()
        assertEquals(StoreProbe.Present, start.probe)
        assertNull(start.notice)
        assertEquals(listOf(Marker.Kept), store.markerWrites)
        assertEquals(0, store.erases)
    }

    @Test
    fun aDoubtfulProbeOnAFreshInstallErasesNothingAndWritesNoMarker() {
        for (why in Why.entries) {
            val store = FakeSecureStore(probe = StoreProbe.Unavailable(why), marker = Marker.Absent)
            val start = SavedWallet(store).start()
            assertNull(start.notice, "$why")
            assertTrue(store.erases == 0 && store.markerWrites.isEmpty(), "the store changed, $why")
        }
    }
}
