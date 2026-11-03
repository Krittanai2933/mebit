package com.mebit.app

import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
    override val biometricName: String? = null
}

actual fun getPlatform(): Platform = AndroidPlatform()

@Composable
actual fun rememberIsDebugBuild(): Boolean {
    val context = LocalContext.current
    return remember(context) { isDebuggable(context.applicationInfo.flags) }
}

/** Pure. Whether [flags], an app's `ApplicationInfo.flags`, carry the debuggable bit a debug build type sets. */
internal fun isDebuggable(flags: Int): Boolean = flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
