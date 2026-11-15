package com.mebit.app

import android.content.pm.ApplicationInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformAndroidTest {
    @Test
    fun `platform name is reported`() {
        assertTrue(getPlatform().name.isNotBlank())
    }

    @Test
    fun `a key made on an android phone reads AND on the keyring`() {
        assertEquals("AND", getPlatform().phoneMonogram)
    }

    @Test
    fun `only the debuggable flag opens the catalog gate`() {
        assertTrue(isDebuggable(ApplicationInfo.FLAG_DEBUGGABLE), "a debug build")
        assertTrue(isDebuggable(ApplicationInfo.FLAG_DEBUGGABLE or ApplicationInfo.FLAG_HAS_CODE), "a debug build among other flags")
        assertFalse(isDebuggable(0), "a release build")
        assertFalse(isDebuggable(ApplicationInfo.FLAG_HAS_CODE or ApplicationInfo.FLAG_ALLOW_BACKUP), "a release build with other flags")
    }
}
