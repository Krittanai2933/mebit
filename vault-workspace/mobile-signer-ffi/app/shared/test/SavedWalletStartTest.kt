package com.mebit.app.data

import com.mebit.app.EraseResult
import com.mebit.app.Marker
import com.mebit.app.StoreProbe
import com.mebit.app.Why
import com.mebit.app.WriteMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SavedWalletStartTest {
    @Test
    fun aDoubtfulFirstLaunchThenAnUnlockKeepsTheWalletAtTheNextLaunch() {
        // An orphan from a deleted install, met on a launch whose probe was in doubt. Once the user has unlocked it, a later launch must not take it for an orphan.
        for (doubt in listOf(Why.TryLater, Why.Other)) {
            val store = FakeSecureStore(marker = Marker.Absent)
            store.write(v1Bytes(), WriteMode.Create) // the orphan, written behind the marker's back
            store.probe = StoreProbe.Unavailable(doubt)
            val wallet = SavedWallet(store)
            assertNull(wallet.start().notice, "notice on the doubtful launch, $doubt")
            assertTrue(store.erases == 0 && store.markerWrites.isEmpty(), "the doubtful launch changed the store, $doubt")
            assertIs<UnlockResult.Opened>(wallet.openNow(), "the unlock, $doubt")
            store.probe = StoreProbe.Present // the doubt is gone
            assertNull(wallet.start().notice, "the next launch wiped the wallet the user had unlocked, $doubt")
            assertEquals(0, store.erases, "erases at the next launch, $doubt")
        }
    }

    @Test
    fun aKeptMarkerOnAPhoneThatNowHasNoPasscodeSaysTheWalletVanishedOnce() {
        // The OS deletes a passcode-set item when the passcode is removed, and the probe then says there is no screen lock, not empty.
        val store = FakeSecureStore(probe = StoreProbe.Unavailable(Why.NoScreenLock), marker = Marker.Kept)
        val wallet = SavedWallet(store)
        assertEquals(StartNotice.WalletVanished, wallet.start().notice)
        assertEquals(listOf(Marker.Empty), store.markerWrites, "marker writes")
        assertEquals(0, store.erases, "erases")
        assertNull(wallet.start().notice, "the notice came back")
    }

    @Test
    fun aMarkerThatCannotBeReadIsDoubtAndNeverWipes() {
        for (probe in EVERY_PROBE) {
            val store = FakeSecureStore(probe = probe, marker = Marker.Unreadable)
            assertNull(SavedWallet(store).start().notice, label(probe))
            assertTrue(store.erases == 0 && store.markerWrites.isEmpty(), "the store changed, ${label(probe)}")
        }
    }

    @Test
    fun twoDroppedMarkerWritesNeverLeaveAWalletThatTheNextLaunchWouldWipe() {
        // The first launch cannot write Empty and the save cannot write Kept. The wallet must not stay without a marker.
        val store = FakeSecureStore(marker = Marker.Absent, failMarker = setOf(1, 2))
        val wallet = SavedWallet(store)
        wallet.start()
        assertTrue(wallet.save(testRecord(), WriteMode.Create) is SaveResult.NotSaved, "a wallet was kept without a marker")
        assertEquals(StoreProbe.Empty, store.probe(), "the wallet stayed in the store")
        assertNull(wallet.start().notice, "the next launch wiped something")
        assertEquals(1, store.erases, "only the undo of the failed save may erase")
    }

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
        val store = FakeSecureStore(probe = StoreProbe.Present, marker = Marker.Absent, erase = failedErase(Why.TryLater))
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
        assertEquals(EraseResult.Erased, wallet.forget(), "forget")
        assertEquals(listOf(Marker.Kept, Marker.Empty), store.markerWrites, "after forget")
        store.erase = failedErase(Why.Other)
        assertIs<EraseResult.Failed>(wallet.forget(), "a failed erase")
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
