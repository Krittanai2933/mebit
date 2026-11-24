package com.mebit.app

import android.hardware.biometrics.BiometricPrompt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/**
 * What App Lock hears from the Android owner prompt, fed the prompt's answer: null for a pass, else its error code. The prompt
 * itself is proven on an emulator, because the host JVM has none.
 */
class AndroidBiometricsTest {
    @Test
    fun aPassIsSuccess() {
        assertEquals(BiometricResult.Success, androidBiometricOutcome(null))
    }

    @Test
    fun cancelSystemCancelAndTheNegativeButtonAreCancelled() {
        // 10 is also what five wrong PINs arrive as, so the unlock screen must not ask again by itself.
        listOf(BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED, BiometricPrompt.BIOMETRIC_ERROR_CANCELED, 13).forEach { code ->
            assertEquals(BiometricResult.Cancelled, androidBiometricOutcome(code), "code $code")
        }
    }

    @Test
    fun aLockoutIsLockedOut() {
        listOf(BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT, BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT).forEach { code ->
            assertEquals(BiometricResult.LockedOut, androidBiometricOutcome(code), "code $code")
        }
    }

    @Test
    fun nothingEnrolledAndNoScreenLockAreUnavailable() {
        listOf(BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS, BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL).forEach { code ->
            assertIs<BiometricResult.Unavailable>(androidBiometricOutcome(code), "code $code")
        }
    }

    @Test
    fun anyOtherErrorIsUnavailableAndNoErrorIsAPassOrAFailedScan() {
        listOf(BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE, BiometricPrompt.BIOMETRIC_ERROR_TIMEOUT, 0, 999).forEach { code ->
            assertIs<BiometricResult.Unavailable>(androidBiometricOutcome(code), "code $code")
        }
        // A wrong finger or PIN is retried inside the sheet, so nothing that reaches the app counts as a failed scan.
        (0..20).forEach { code ->
            assertNotEquals(BiometricResult.Success, androidBiometricOutcome(code), "code $code")
            assertNotEquals(BiometricResult.Failed, androidBiometricOutcome(code), "code $code")
        }
    }
}
