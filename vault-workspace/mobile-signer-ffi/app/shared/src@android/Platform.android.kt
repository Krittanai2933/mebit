package com.mebit.app

import android.app.Activity
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
    override val biometricName: String? = null
    override val phoneMonogram: String = "AND"
}

actual fun getPlatform(): Platform = AndroidPlatform()

@Composable
actual fun rememberIsDebugBuild(): Boolean {
    val context = LocalContext.current
    return remember(context) { isDebuggable(context.applicationInfo.flags) }
}

/** Pure. Whether [flags], an app's `ApplicationInfo.flags`, carry the debuggable bit a debug build type sets. */
internal fun isDebuggable(flags: Int): Boolean = flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

@Composable
actual fun rememberFakeDeviceSwitch(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
        isDebuggable(context.applicationInfo.flags) && activity?.intent?.getStringExtra("MEBIT_FAKE_DEVICE") == "1"
    }
}

@Composable
actual fun rememberTestMode(): Boolean {
    val context = LocalContext.current
    val fakeDevice = rememberFakeDeviceSwitch()
    return remember(context, fakeDevice) {
        val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
        fakeDevice || isDebuggable(context.applicationInfo.flags) && activity?.intent?.getStringExtra("MEBIT_TEST_MODE") == "1"
    }
}

@Composable
actual fun rememberFakeDeviceScript(): String? {
    val context = LocalContext.current
    val on = rememberFakeDeviceSwitch()
    return remember(context, on) {
        val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
        if (on) activity?.intent?.getStringExtra("MEBIT_FAKE_SCRIPT") else null
    }
}
