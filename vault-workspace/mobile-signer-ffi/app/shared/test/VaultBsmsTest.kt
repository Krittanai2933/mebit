package com.mebit.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultBsmsTest {
    @Test
    fun exportUsesKeyOriginAndTheFirstCosignerRoundTrips() {
        val store = keyring(fingerprintA = "ABCD0123", fingerprintB = "00ff00aa")
        val draft = VaultDraft()
        assertTrue(draft.setMn(2, 2))
        draft.toggleKey("k1")
        draft.toggleKey("k2")
        assertTrue(draft.buildReview(store))
        assertNull(draft.lastError)
        val bsms = draft.bsms
        assertTrue(bsms.startsWith("BSMS 1.0\n"))
        assertTrue(bsms.contains("wsh(sortedmulti(2,[abcd0123/48'/1'/0'/2']$tpubA,[00ff00aa/48'/1'/0'/2']$tpubB))"))
        assertTrue(bsms.contains("# export=file · not QR"))
        assertFalse(bsms.contains("demo", ignoreCase = true))
        assertFalse(bsms.contains("placeholder", ignoreCase = true))
        assertFalse(bsms.contains("mock", ignoreCase = true))
        assertFalse(bsms.contains("TODO"))

        val other = KeyringStore()
        assertTrue(other.startMebitOtherFromPaste(bsms))
        assertEquals("abcd0123", other.pending?.fingerprint)
        assertEquals(tpubA, other.pending?.xpub)
        assertEquals("m/48'/1'/0'/2'", other.pending?.derivationPath)
        assertEquals(KeySourceLabel.MebitOther, other.pending?.source)
    }

    @Test
    fun unknownFingerprintStaysABareXpub() {
        val store = KeyringStore()
        store.keys.add(savedKey("k1", fingerprint = "", xpub = tpubA))
        val draft = VaultDraft()
        assertTrue(draft.setMn(1, 1))
        draft.toggleKey("k1")
        assertTrue(draft.buildReview(store))
        val descriptor = draft.bsms.lines().first { it.startsWith("wsh(") }
        assertEquals("wsh(sortedmulti(1,$tpubA))", descriptor)
        assertTrue(draft.bsms.contains("# export=file · not QR"))
    }
}

private fun keyring(fingerprintA: String, fingerprintB: String): KeyringStore {
    val store = KeyringStore()
    store.keys.add(savedKey("k1", fingerprintA, tpubA))
    store.keys.add(savedKey("k2", fingerprintB, tpubB))
    return store
}

private fun savedKey(id: String, fingerprint: String, xpub: String) = SavedKey(
    id = id,
    label = id,
    fingerprint = fingerprint,
    xpub = xpub,
    derivationPath = "m/48'/1'/0'/2'",
    source = KeySourceLabel.ThisDevice,
    verified = true,
)
