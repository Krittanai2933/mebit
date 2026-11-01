package com.mebit.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyringVerificationTest {
    private fun staged(fingerprint: String = "abcd0123"): KeyringStore = KeyringStore().also {
        assertTrue(it.startMebitOtherFromPaste("$fingerprint\n$tpubA\nm/48'/1'/0'/2'"))
    }

    @Test
    fun typedMatchSavesTheActualStagedKeyWithItsEditedName() {
        val store = staged()
        assertTrue(store.confirmFingerprint("ABCD0123", "  กุญแจบ้าน  "))
        val key = store.keys.single()
        assertEquals("กุญแจบ้าน", key.label)
        assertEquals("abcd0123", key.fingerprint)
        assertEquals(tpubA, key.xpub)
        assertEquals(KeySourceLabel.MebitOther, key.source)
        assertTrue(key.verified)
        assertNull(store.pending)
    }

    @Test
    fun groupedObservationIgnoresWhitespaceWithoutChangingHexDigits() {
        val store = staged()
        assertTrue(store.confirmFingerprint("  ABCD 0123\n"))
        assertEquals("abcd0123", store.keys.single().fingerprint)
        assertFalse(store.keys.single().isMock)
        val wrong = staged()
        assertFalse(wrong.confirmFingerprint("abcd 0124"))
        assertTrue(wrong.keys.isEmpty())
    }

    @Test
    fun blankNameKeepsTheStagedName() {
        val store = staged()
        val name = store.pending!!.label
        assertTrue(store.confirmFingerprint("abcd0123", "  "))
        assertEquals(name, store.keys.single().label)
    }

    @Test
    fun wrongObservationDoesNotSave() {
        val store = staged()
        assertFalse(store.confirmFingerprint("00000000"))
        assertTrue(store.keys.isEmpty())
        assertNull(store.pending)
        assertTrue(store.lastMismatch)
    }

    @Test
    fun explicitDiscardNeverSavesEvenWhenFingerprintIsDeadbeef() {
        val store = staged("deadbeef")
        store.discardPending()
        assertTrue(store.keys.isEmpty())
        assertNull(store.pending)
        assertFalse(store.confirmFingerprint("DEADBEEF"))
        assertTrue(store.keys.isEmpty())
    }

    @Test
    fun pastedAndHardwareKeysDefaultToReal() {
        val pasted = staged().pending!!
        assertFalse(pasted.isMock)
        assertFalse(pasted.copy(source = KeySourceLabel.Jade).isMock)
        assertFalse(pasted.copy(source = KeySourceLabel.TrezorSafe7).isMock)
    }
}
