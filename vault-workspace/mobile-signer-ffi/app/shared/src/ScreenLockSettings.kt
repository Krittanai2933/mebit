package com.mebit.app

import androidx.compose.runtime.Composable

/**
 * Opens the system page where the screen lock is set. Null on iOS, which has no public link to Face ID & Passcode, so the
 * screen that asks for a lock lists the steps instead. Composable because Android reaches a Context only through
 * [androidx.compose.ui.platform.LocalContext].
 */
@Composable
expect fun rememberScreenLockSettingsOpener(): (() -> Unit)?
