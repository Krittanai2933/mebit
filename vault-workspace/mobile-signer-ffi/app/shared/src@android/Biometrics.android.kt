package com.mebit.app

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * The OS owner sheet with no crypto object: a strong biometric or the screen lock, whatever [allowPasscode] says, because the
 * framework refuses a cancel button on a biometric-only sheet and a phone with only a PIN would have nothing to answer with.
 */
private class AndroidBiometrics(private val context: Context) : Biometrics {
    override suspend fun authenticate(reason: String, allowPasscode: Boolean): BiometricResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return BiometricResult.Unavailable("OsTooOld")
        return androidBiometricOutcome(askOwner(context, reason, crypto = null))
    }
}

/** The sheet needs this Context (the Activity), as the store's read does. */
@Composable
actual fun rememberBiometrics(): Biometrics {
    val context = LocalContext.current
    return remember(context) { AndroidBiometrics(context) }
}

/**
 * The sheet's answer (null for a pass, else its error code) as App Lock hears it, through the store's own code mapping. A wrong
 * attempt is retried inside the sheet, so nothing is Failed, and five wrong PINs arrive as Cancelled.
 */
internal fun androidBiometricOutcome(errorCode: Int?): BiometricResult = when (errorCode?.let(::androidPromptOutcome)) {
    null -> BiometricResult.Success
    ReadResult.Cancelled -> BiometricResult.Cancelled
    ReadResult.LockedOut -> BiometricResult.LockedOut
    else -> BiometricResult.Unavailable("prompt error $errorCode")
}
