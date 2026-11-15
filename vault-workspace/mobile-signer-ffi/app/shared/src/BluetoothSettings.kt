package com.mebit.app

import androidx.compose.runtime.Composable

/**
 * Opens the system page where Bluetooth is switched on. iOS has no public page for that, so it opens this app's
 * own page in Settings, where its Bluetooth access lives. Composable because Android reaches a Context only through
 * [androidx.compose.ui.platform.LocalContext].
 */
@Composable
expect fun rememberBluetoothSettingsOpener(): () -> Unit
