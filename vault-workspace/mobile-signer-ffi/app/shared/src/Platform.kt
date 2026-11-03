package com.mebit.app

interface Platform {
    val name: String

    /** The check this platform's own prompt performs, or null where the system chooses the method and the app names none. */
    val biometricName: String?
}

expect fun getPlatform(): Platform
