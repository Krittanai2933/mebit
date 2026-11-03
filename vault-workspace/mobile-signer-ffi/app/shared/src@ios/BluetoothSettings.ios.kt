package com.mebit.app

import androidx.compose.runtime.Composable

@Composable
actual fun rememberBluetoothSettingsOpener(): () -> Unit = rememberAppSettingsOpener()
