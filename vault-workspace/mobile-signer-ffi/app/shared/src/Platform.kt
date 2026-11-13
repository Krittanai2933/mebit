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

/**
 * True only in a debug build launched with the fake device's switch: MEBIT_FAKE_DEVICE=1 in the app's environment on iOS
 * (SIMCTL_CHILD_MEBIT_FAKE_DEVICE=1 for xcrun simctl launch), or the launch intent's string extra MEBIT_FAKE_DEVICE set to 1 on Android.
 * The nav host then reaches a Jade or a Safe 7 through [com.mebit.app.data.FakeDevice] in place of Bluetooth.
 */
@Composable
expect fun rememberFakeDeviceSwitch(): Boolean

/**
 * The fake device's behaviour script, comma separated ([com.mebit.app.data.fakeScript] reads it), from MEBIT_FAKE_SCRIPT next to the
 * switch's own variable (SIMCTL_CHILD_MEBIT_FAKE_SCRIPT for xcrun simctl launch, a string extra on Android). Null without the switch.
 */
@Composable
expect fun rememberFakeDeviceScript(): String?
