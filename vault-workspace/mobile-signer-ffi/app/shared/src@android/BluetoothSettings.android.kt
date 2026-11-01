package com.mebit.app

import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberBluetoothSettingsOpener(): () -> Unit {
    val context = LocalContext.current
    return remember(context) {
        val open: () -> Unit = {
            // A device without that settings page leaves the button doing nothing, not crashing.
            runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        }
        open
    }
}
