package com.mebit.app

import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberScreenLockSettingsOpener(): (() -> Unit)? {
    val context = LocalContext.current
    return remember(context) {
        val open: () -> Unit = {
            // The screen-lock picker itself, or the Security page on a phone whose Settings has no picker. Starting it rather than
            // asking resolveActivity first, which on Android 11 and later answers only for packages the manifest queries.
            // A phone with neither page leaves the button doing nothing, not crashing.
            runCatching {
                try {
                    context.startActivity(Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD))
                } catch (_: ActivityNotFoundException) {
                    context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                }
            }
        }
        open
    }
}
