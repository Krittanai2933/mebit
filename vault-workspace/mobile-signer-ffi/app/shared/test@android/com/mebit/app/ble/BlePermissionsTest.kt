package com.mebit.app.ble

import kotlin.test.Test
import kotlin.test.assertEquals

class BlePermissionsTest {
    @Test
    fun `api 30 asks for fine location only`() {
        assertEquals(listOf("android.permission.ACCESS_FINE_LOCATION"), blePermissions(30))
    }

    @Test
    fun `api 31 and 36 ask for scan and connect`() {
        val scanAndConnect = listOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT")
        assertEquals(scanAndConnect, blePermissions(31))
        assertEquals(scanAndConnect, blePermissions(36))
    }
}
