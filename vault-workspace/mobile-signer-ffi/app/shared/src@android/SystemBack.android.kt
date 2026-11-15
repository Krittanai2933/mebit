package com.mebit.app

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

@Composable
actual fun SystemBack(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
}
