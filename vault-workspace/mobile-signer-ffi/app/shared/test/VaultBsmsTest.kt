package com.mebit.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// 2-of-2 and 1-of-1 are not presets, so these call the builders a wizard plan uses directly. The saved format is what they pin.
class VaultBsmsTest {
    @Test
    fun exportUsesKeyOriginAndTheFirstCosignerRoundTrips() {
        val keys = listOf(savedKey("k1", "ABCD0123", tpubA), savedKey("k2", "00ff00aa", tpubB))
        val descriptor = walletDescriptor(2, keys)
        val bsms = walletBsms(descriptor, keys, "tb1qexample")
        assertTrue(bsms.startsWith("BSMS 1.0\n"))
        assertEquals(descriptor, bsms.lines()[1])
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
        val keys = listOf(savedKey("k1", "", tpubA))
        val bsms = walletBsms(walletDescriptor(1, keys), keys, "tb1qexample")
        val descriptor = bsms.lines().first { it.startsWith("wsh(") }
        assertEquals("wsh(sortedmulti(1,$tpubA))", descriptor)
        assertTrue(bsms.contains("# export=file · not QR"))
    }
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
