package com.mebit.app

import androidx.compose.runtime.Composable

/** How a Face ID (or device passcode) check ended. */
sealed interface BiometricResult {
    data object Success : BiometricResult

    /** The face did not match, or the passcode was wrong. The user may try again. */
    data object Failed : BiometricResult

    data object Cancelled : BiometricResult

    /** Too many failed scans: only the device passcode unlocks now. */
    data object LockedOut : BiometricResult

    /** No Face ID or passcode is set up on this device, or this platform has no implementation. */
    data class Unavailable(val reason: String) : BiometricResult
}

interface Biometrics {
    /**
     * Face ID only when [allowPasscode] is false. With it true the device passcode is accepted
     * too, which is what "use the 6-digit code instead" means: the phone's own code, not an app PIN.
     */
    suspend fun authenticate(reason: String, allowPasscode: Boolean = false): BiometricResult

    /**
     * Never prompts. True while the phone's own owner check, a passcode or a secure screen lock on Android 11 or later, guards
     * this one. Then the gates ask only when the user turned App Lock on. False on a phone with neither, read live each time,
     * so a lock removed while the app runs counts at the next gate.
     */
    fun available(): Boolean
}

/** This platform's owner check. Composable because the Android sheet needs the Activity. */
@Composable
expect fun rememberBiometrics(): Biometrics
