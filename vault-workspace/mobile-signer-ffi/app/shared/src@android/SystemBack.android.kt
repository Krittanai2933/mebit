package com.mebit.app

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun SystemBack(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
}

@Composable
actual fun rememberLeaveApp(): () -> Unit {
    val context = LocalContext.current
    return remember(context) { { (context as? Activity)?.moveTaskToBack(true); Unit } }
}
