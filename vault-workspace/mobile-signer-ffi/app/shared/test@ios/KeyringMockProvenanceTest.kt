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
        }
        store.startHardwareMock(KeySourceLabel.Jade)
        assertTrue(store.confirmFingerprint(store.pending!!.fingerprint))
        assertEquals("Jade · จำลอง", store.keys.last().label)
    }
}
