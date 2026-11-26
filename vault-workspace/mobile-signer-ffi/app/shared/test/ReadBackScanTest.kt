package com.mebit.app.feature.onboarding

import com.mebit.app.BiometricResult
import com.mebit.app.Why
import com.mebit.app.data.ANY_VERSION
import com.mebit.app.data.Cause
import com.mebit.app.data.PUBLIC_WORDS_12
import com.mebit.app.data.UnlockResult
import com.mebit.app.data.WalletRecord
import com.mebit.ffi.signer.KeyNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReadBackScanTest {
    @Test
    fun onlyAVerifiedCopyTurnsTheLockOn() {
        assertEquals(BiometricResult.Success, readBackScan(UnlockResult.Verified))
    }

    @Test
    fun aCancelARefusedFaceOrCodeAndALockoutAreTheScansOwn() {
        assertEquals(BiometricResult.Cancelled, readBackScan(UnlockResult.Cancelled))
        assertEquals(BiometricResult.Failed, readBackScan(UnlockResult.Denied))
        assertEquals(BiometricResult.LockedOut, readBackScan(UnlockResult.LockedOut))
    }

    @Test
    fun aPhoneThatCannotReadItsCopyNowIsUnavailable() {
        for (why in Why.entries) {
            assertIs<BiometricResult.Unavailable>(readBackScan(UnlockResult.Unavailable(why)), "$why")
        }
    }

    @Test
    fun aCopyThatCanNeverOpenIsUnavailableSoTheUserCanGoOnWithoutALock() {
        for (cause in Cause.entries) {
            assertIs<BiometricResult.Unavailable>(readBackScan(UnlockResult.Broken(cause)), "$cause")
        }
    }

    @Test
    fun theLinkGoesOnWithoutALockOnTheEnableStepWhateverTheScanRead() {
        for (givenUp in listOf(false, true)) {
            assertTrue(goesOnWithoutLock(enable = true, allowPasscode = true, copyGivenUp = givenUp), "givenUp=$givenUp")
        }
    }

    @Test
    fun theUnlockScreenNeverGoesOnWithoutALock() {
        for (allowPasscode in listOf(false, true)) for (givenUp in listOf(false, true)) {
            assertFalse(goesOnWithoutLock(enable = false, allowPasscode = allowPasscode, copyGivenUp = givenUp), "link=$allowPasscode givenUp=$givenUp")
        }
    }

    @Test
    fun aPrimaryTapWhoseReadBackGaveTheCopyUpGoesOnWithoutALock() {
        // The phone's lock is fine, the copy is what failed, and a second scan would only turn on a lock the next launch cannot keep.
        assertTrue(goesOnWithoutLock(enable = true, allowPasscode = false, copyGivenUp = true))
    }

    @Test
    fun aPrimaryTapThatKeptTheCopyStaysOnTheScreen() {
        // A try-later read-back, a save still owed or a key gone among them, keeps the copy, and a plain scan reads no copy back.
        // Both show the line and wait for another tap or the link, so App Lock is never left off unless the owner chooses the link.
        assertFalse(goesOnWithoutLock(enable = true, allowPasscode = false, copyGivenUp = false))
        assertFalse(scanOpens(readBackScan(UnlockResult.Unavailable(Why.TryLater)), enable = true, allowPasscode = false, copyGivenUp = false))
    }

    @Test
    fun aCopyNobodyComparedNeverTurnsTheLockOn() {
        val record = WalletRecord(PUBLIC_WORDS_12, "", KeyNetwork.TEST, "00000000", backupConfirmed = false, imported = false, appLockEnabled = false)
        assertIs<BiometricResult.Unavailable>(readBackScan(UnlockResult.Opened(record, ANY_VERSION)))
    }
}
