package com.mebit.app

import platform.UIKit.UIDevice

class IOSPlatform : Platform {
    override val name: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
    override val biometricName: String? = "Face ID"
}

actual fun getPlatform(): Platform = IOSPlatform()
