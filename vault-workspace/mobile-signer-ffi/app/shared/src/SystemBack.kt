package com.mebit.app

import androidx.compose.runtime.Composable

/**
 * Runs [onBack] in place of the system Back while the caller is on screen: Android's Back button or gesture. iOS has no Back button and no
 * screen of this app takes the edge swipe, so there it does nothing.
 */
@Composable
expect fun SystemBack(onBack: () -> Unit)
