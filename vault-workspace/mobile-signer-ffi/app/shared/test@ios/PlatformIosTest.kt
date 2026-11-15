package com.mebit.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformIosTest {
    @Test
    fun `platform name is reported`() {
        assertTrue(getPlatform().name.isNotBlank())
    }

    @Test
    fun `a key made on an iPhone reads iOS on the keyring as Penpot draws it`() {
        assertEquals("iOS", getPlatform().phoneMonogram)
    }

    @Test
    fun `a test binary is a debug build so the catalog gate opens for the verify harness`() {
        assertTrue(isDebugBinary())
    }
}
