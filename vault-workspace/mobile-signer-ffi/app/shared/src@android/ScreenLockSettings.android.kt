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
            // Starting the intent rather than asking resolveActivity first: on Android 11 and later that sees only packages the manifest
            // queries and the ones the system makes visible, which differ by device.
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
