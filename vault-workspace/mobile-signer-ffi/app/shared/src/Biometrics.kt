package com.mebit.app

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
}

expect fun platformBiometrics(): Biometrics
