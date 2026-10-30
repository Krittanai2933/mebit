package com.mebit.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import java.util.WeakHashMap

// FLAG_SECURE is per window, so each window counts its own holders.
private val secureHolders = WeakHashMap<Window, HolderCount>()

@Composable
actual fun rememberCaptureGuard(): CaptureGuard {
    val window = LocalContext.current.findActivity()?.window
        ?: return CaptureGuard(CaptureClaim.None, hidden = false, screenshotTaken = false)
    DisposableEffect(window) {
        val holders = secureHolders.getOrPut(window, ::HolderCount)
        if (holders.acquire()) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (holders.release()) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    return CaptureGuard(CaptureClaim.BlocksScreenshots, hidden = false, screenshotTaken = false)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
