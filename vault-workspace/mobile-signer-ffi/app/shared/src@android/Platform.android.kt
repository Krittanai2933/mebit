package com.mebit.app

import android.os.Build

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
    override val biometricName: String? = null
}

actual fun getPlatform(): Platform = AndroidPlatform()
