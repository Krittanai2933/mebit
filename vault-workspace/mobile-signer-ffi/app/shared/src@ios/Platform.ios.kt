package com.mebit.app

import androidx.compose.runtime.Composable
import kotlin.experimental.ExperimentalNativeApi
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIDevice

class IOSPlatform : Platform {
    override val name: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
    override val biometricName: String? = "Face ID"
    override val phoneMonogram: String = "iOS"
}

actual fun getPlatform(): Platform = IOSPlatform()

@Composable
actual fun rememberIsDebugBuild(): Boolean = isDebugBinary()

/** Whether this framework was linked as a debug binary, which is what an Xcode Debug configuration builds. */
@OptIn(ExperimentalNativeApi::class)
internal fun isDebugBinary(): Boolean = kotlin.native.Platform.isDebugBinary

@Composable
actual fun rememberFakeDeviceSwitch(): Boolean = isDebugBinary() && NSProcessInfo.processInfo.environment["MEBIT_FAKE_DEVICE"] == "1"

@Composable
actual fun rememberFakeDeviceScript(): String? = if (rememberFakeDeviceSwitch()) NSProcessInfo.processInfo.environment["MEBIT_FAKE_SCRIPT"] as? String else null
