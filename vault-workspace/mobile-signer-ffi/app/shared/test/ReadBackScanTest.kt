package com.mebit.app.feature.onboarding

import com.mebit.app.BiometricResult
import com.mebit.app.Why
import com.mebit.app.data.Cause
import com.mebit.app.data.PUBLIC_WORDS_12
import com.mebit.app.data.SaveState
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
        for (readBack in listOf(false, true)) for (saved in SaveState.entries) {
            assertTrue(goesOnWithoutLock(enable = true, allowPasscode = true, readBack = readBack, savedAfter = saved), "readBack=$readBack saved=$saved")
        }
    }

    @Test
    fun theUnlockScreenNeverGoesOnWithoutALock() {
        for (allowPasscode in listOf(false, true)) for (readBack in listOf(false, true)) for (saved in SaveState.entries) {
            assertFalse(goesOnWithoutLock(enable = false, allowPasscode = allowPasscode, readBack = readBack, savedAfter = saved), "link=$allowPasscode readBack=$readBack saved=$saved")
        }
    }

    @Test
    fun aPrimaryTapWhoseReadBackFoundTheCopyUnusableGoesOnWithoutALock() {
        // The phone's lock is fine, the copy is what failed, and a second scan would only turn on a lock the next launch cannot keep.
        assertTrue(goesOnWithoutLock(enable = true, allowPasscode = false, readBack = true, savedAfter = SaveState.NotSaved))
    }

    @Test
    fun aPrimaryTapThatReadNothingOrLeftTheCopySavedStaysOnTheScreen() {
        // A try-later read keeps the wallet saved, and a plain scan reads no copy back. Both show the line and wait for the link.
        assertFalse(goesOnWithoutLock(enable = true, allowPasscode = false, readBack = true, savedAfter = SaveState.Saved))
        assertFalse(goesOnWithoutLock(enable = true, allowPasscode = false, readBack = false, savedAfter = SaveState.NotSaved))
    }

    @Test
    fun aCopyNobodyComparedNeverTurnsTheLockOn() {
        val record = WalletRecord(PUBLIC_WORDS_12, "", KeyNetwork.TEST, "00000000", backupConfirmed = false, imported = false, appLockEnabled = false)
        assertIs<BiometricResult.Unavailable>(readBackScan(UnlockResult.Opened(record)))
    }
}
