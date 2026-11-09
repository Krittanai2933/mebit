package com.mebit.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeyringMockProvenanceTest {
    @Test
    fun generatedMocksAreExplicitlyMarkedForDemoVerification() {
        val store = KeyringStore()
        store.startMebitOtherMock()
        assertTrue(store.pending!!.isMock)
        listOf(KeySourceLabel.Jade, KeySourceLabel.TrezorSafe7).forEach { source ->
            store.startHardwareMock(source)
            assertEquals(source, store.pending!!.source)
            assertTrue(store.pending!!.isMock)
            assertTrue(store.confirmFingerprint(store.pending!!.fingerprint, "กุญแจบ้าน"))
            assertTrue(store.keys.last().isMock)
            assertEquals("กุญแจบ้าน · จำลอง", store.keys.last().label)
            assertEquals("จำลอง", pairedDevice(store.keys)?.status)
        }
        store.startHardwareMock(KeySourceLabel.Jade)
        assertTrue(store.confirmFingerprint(store.pending!!.fingerprint))
        assertEquals("Jade · จำลอง", store.keys.last().label)
    }

    @Test
    fun aKeyMadeOnThisDeviceIsAStandIn() {
        val store = KeyringStore()
        store.startThisDevice()
        assertEquals(KeySourceLabel.ThisDevice, store.pending!!.source)
        assertTrue(store.pending!!.isMock)
        assertTrue(store.confirmFingerprint(store.pending!!.fingerprint))
        assertTrue(store.keys.last().isMock)
        assertTrue(store.keys.last().label.endsWith(" · จำลอง"), store.keys.last().label)
    }
}
