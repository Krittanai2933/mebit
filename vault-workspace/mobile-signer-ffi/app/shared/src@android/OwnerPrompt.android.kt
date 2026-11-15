package com.mebit.app

import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * One OS owner check: the framework sheet with a strong biometric or the screen lock (PIN, pattern, password), on the main
 * thread. Null when the owner passed, else the sheet's `BiometricPrompt` error code. A wrong finger or PIN is retried inside
 * the sheet and never reaches here. Cancelling the caller takes the sheet down. Needs API 30, which every caller checks.
 * The store reads through it with [crypto]; the `Biometrics` actual (step 7) can call it without one.
 */
internal suspend fun askOwner(context: Context, title: String, crypto: BiometricPrompt.CryptoObject?): Int? = withContext(Dispatchers.Main) {
    suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (continuation.isActive) continuation.resume(null)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (continuation.isActive) continuation.resume(errorCode)
            }
        }
        // No negative button: the framework refuses one when the screen lock is allowed.
        val prompt = BiometricPrompt.Builder(context).setTitle(title).setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL).build()
        if (crypto != null) {
            prompt.authenticate(crypto, signal, context.mainExecutor, callback)
        } else {
            prompt.authenticate(signal, context.mainExecutor, callback)
        }
    }
}
