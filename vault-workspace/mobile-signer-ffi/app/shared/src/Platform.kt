package com.mebit.app

import androidx.compose.runtime.Composable

interface Platform {
    val name: String

    /** The check this platform's own prompt performs, or null where the system chooses the method and the app names none. */
    val biometricName: String?

    /** The text on the Keyring tile of a key made on this phone. Penpot 2.1 draws "iOS", so Android says "AND", a guess (docs/04). */
    val phoneMonogram: String
}

expect fun getPlatform(): Platform

/**
 * True only in a debug build. The route catalog and its link exist in no release build: the catalog reaches Backup Words and
 * Receive past their gates. Composable because Android reads the app's own flags through the Context.
 */
@Composable
expect fun rememberIsDebugBuild(): Boolean
