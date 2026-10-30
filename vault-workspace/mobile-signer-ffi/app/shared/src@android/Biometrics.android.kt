package com.mebit.app

// shortcut: no BiometricPrompt yet. MainActivity is a ComponentActivity and the prompt needs a
// FragmentActivity plus androidx.biometric, so Android reports Unavailable and the lock stays off.
private object AndroidBiometrics : Biometrics {
    override suspend fun authenticate(reason: String, allowPasscode: Boolean): BiometricResult =
        BiometricResult.Unavailable("biometrics are not wired on Android yet")
}

actual fun platformBiometrics(): Biometrics = AndroidBiometrics
