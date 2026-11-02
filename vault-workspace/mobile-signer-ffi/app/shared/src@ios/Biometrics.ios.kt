package com.mebit.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.coroutines.resume
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSError
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAErrorAppCancel
import platform.LocalAuthentication.LAErrorAuthenticationFailed
import platform.LocalAuthentication.LAErrorBiometryLockout
import platform.LocalAuthentication.LAErrorSystemCancel
import platform.LocalAuthentication.LAErrorUserCancel
import platform.LocalAuthentication.LAErrorUserFallback
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics

@OptIn(ExperimentalForeignApi::class)
private class IosBiometrics : Biometrics {
    override suspend fun authenticate(reason: String, allowPasscode: Boolean): BiometricResult {
        val context = LAContext()
        val policy =
            if (allowPasscode) LAPolicyDeviceOwnerAuthentication else LAPolicyDeviceOwnerAuthenticationWithBiometrics
        // The app has its own "use the passcode" button, so the Face ID prompt hides the system one.
        context.localizedFallbackTitle = ""
        val blocked = memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            if (context.canEvaluatePolicy(policy, error.ptr)) null else error.value
        }
        if (blocked != null) return outcome(blocked)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { context.invalidate() }
            context.evaluatePolicy(policy, localizedReason = reason) { ok, error ->
                continuation.resume(if (ok) BiometricResult.Success else outcome(error))
            }
        }
    }

    private fun outcome(error: NSError?): BiometricResult = when (error?.code) {
        LAErrorAuthenticationFailed -> BiometricResult.Failed
        LAErrorUserCancel, LAErrorSystemCancel, LAErrorAppCancel, LAErrorUserFallback -> BiometricResult.Cancelled
        LAErrorBiometryLockout -> BiometricResult.LockedOut
        // Not enrolled, no passcode, no hardware: nothing to scan with.
        else -> BiometricResult.Unavailable(error?.localizedDescription ?: "unavailable")
    }
}

@Composable
actual fun rememberBiometrics(): Biometrics = remember { IosBiometrics() }
