package com.mebit.app

import androidx.compose.runtime.Composable

/**
 * Runs [onBack] in place of the system Back while the caller is on screen: Android's Back button or gesture. iOS has no Back button and no
 * screen of this app takes the edge swipe, so there it does nothing.
 */
@Composable
expect fun SystemBack(onBack: () -> Unit)

/** Moves the app to the background, as Home does, and leaves its state alone. Android only: iOS has no Back and the result does nothing. */
@Composable
expect fun rememberLeaveApp(): () -> Unit
