package com.mebit.app.feature.onboarding

import com.mebit.app.BiometricResult
import com.mebit.app.Why
import com.mebit.app.data.Cause
import com.mebit.app.data.PUBLIC_WORDS_12
import com.mebit.app.data.UnlockResult
import com.mebit.app.data.WalletRecord
import com.mebit.ffi.signer.KeyNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
    fun aCopyNobodyComparedNeverTurnsTheLockOn() {
        val record = WalletRecord(PUBLIC_WORDS_12, "", KeyNetwork.TEST, "00000000", backupConfirmed = false, imported = false, appLockEnabled = false)
        assertIs<BiometricResult.Unavailable>(readBackScan(UnlockResult.Opened(record)))
    }
}
