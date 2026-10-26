package com.mebit.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MebitOtherPasteTest {
    @Test
    fun plainLinesStageOneMebitKey() {
        val store = KeyringStore()
        assertTrue(
            store.startMebitOtherFromPaste(
                """
                ABCD0123
                $tpubA
                m/48'/1'/0'/2'
                """.trimIndent(),
            ),
        )
        val pending = store.pending
        assertEquals("abcd0123", pending?.fingerprint)
        assertEquals(tpubA, pending?.xpub)
        assertEquals("m/48'/1'/0'/2'", pending?.derivationPath)
        assertEquals(KeySourceLabel.MebitOther, pending?.source)
        assertNull(store.lastError)
        assertTrue(store.confirmFingerprint("ABCD0123"))
        assertEquals(1, store.keys.size)
        assertTrue(store.keys.single().verified)
        assertEquals(KeySourceLabel.MebitOther, store.keys.single().source)
    }

    @Test
    fun plainPasteRequiresFingerprintXpubAndPath() {
        val store = KeyringStore()
        assertEquals(false, store.startMebitOtherFromPaste("abcd0123\n$tpubA"))
        assertNull(store.pending)
        assertNotNull(store.lastError)
    }

    @Test
    fun privateKeyIsRejected() {
        val store = KeyringStore()
        val extendedPrivate = "tprv" + tpubA.removePrefix("tpub")
        assertEquals(
            false,
            store.startMebitOtherFromPaste("abcd0123\n$extendedPrivate\nm/48'/1'/0'/2'"),
        )
        assertNull(store.pending)
        assertEquals("อย่าวาง private key", store.lastError)
    }

    @Test
    fun vpubAndHardenedHNormalize() {
        val store = KeyringStore()
        val vpub = "vpub" + tpubA.removePrefix("tpub")
        assertTrue(store.startMebitOtherFromPaste("abcd0123\n$vpub\nm/48h/1h/0h/2h"))
        assertEquals(vpub, store.pending?.xpub)
        assertEquals("m/48'/1'/0'/2'", store.pending?.derivationPath)
    }

    @Test
    fun bareBsmsUsesTheFingerprintLine() {
        val store = KeyringStore()
        assertTrue(store.startMebitOtherFromPaste(bareBsms(tpubA)))
        assertEquals("abcd0123", store.pending?.fingerprint)
        assertEquals(tpubA, store.pending?.xpub)
        assertEquals("m/48'/1'/0'/2'", store.pending?.derivationPath)
        assertEquals(KeySourceLabel.MebitOther, store.pending?.source)
    }

    @Test
    fun originBsmsStagesTheFirstCosigner() {
        val store = KeyringStore()
        val text = """
            BSMS 1.0
            wsh(sortedmulti(2,[abcd0123/48'/1'/0'/2']$tpubA,[00ff00aa/48h/1h/0h/2h]$tpubB))
            abcd0123 m/48'/1'/0'/2'
            00ff00aa m/48'/1'/0'/2'
            /0/*,/1/*
            # export=file · not QR
        """.trimIndent()
        assertTrue(store.startMebitOtherFromPaste(text))
        assertEquals("abcd0123", store.pending?.fingerprint)
        assertEquals(tpubA, store.pending?.xpub)
        assertEquals("m/48'/1'/0'/2'", store.pending?.derivationPath)
    }

    @Test
    fun originWithoutFingerprintLinesStillParses() {
        val store = KeyringStore()
        val text = """
            BSMS 1.0
            wsh(sortedmulti(1,[abcd0123/48'/1'/0'/2']$tpubA/0/*))
            /0/*,/1/*
        """.trimIndent()
        assertTrue(store.startMebitOtherFromPaste(text))
        assertEquals("abcd0123", store.pending?.fingerprint)
        assertEquals(tpubA, store.pending?.xpub)
        assertEquals("m/48'/1'/0'/2'", store.pending?.derivationPath)
    }

    @Test
    fun mismatchedOriginAndFingerprintLineFails() {
        val store = KeyringStore()
        val text = """
            BSMS 1.0
            wsh(sortedmulti(1,[abcd0123/48'/1'/0'/2']$tpubA))
            ffffffff m/48'/1'/0'/2'
        """.trimIndent()
        assertEquals(false, store.startMebitOtherFromPaste(text))
        assertNull(store.pending)
        assertNotNull(store.lastError)
    }

    @Test
    fun badPasteClearsAPreviousPendingKey() {
        val store = KeyringStore()
        assertTrue(store.startMebitOtherFromPaste("abcd0123\n$tpubA\nm/48'/1'/0'/2'"))
        assertEquals(false, store.startMebitOtherFromPaste("not a key"))
        assertNull(store.pending)
        assertNotNull(store.lastError)
    }
}

internal const val tpubA =
    "tpubDFkN51vYF36W4Yfn3wGv5fpmRo3ok7vZZjc1gmRJjumq33L776e6GkP4HGdCVjDqYiBahXCrXQKja8aUZ2xovQNS8WkF46MdY7TLHJLYD7H"
internal const val tpubB =
    "tpubDF41CiHSj6Y4iSH7syioTxCr9cvMXLXvz4sGzvg4MSYfnid68mGMStcADFnKGnYmwL5GJ6ZzqvypiZaHs3AefpGuDrzMa56PYHVELc4crdQ"

private fun bareBsms(xpub: String): String = """
    BSMS 1.0
    wsh(sortedmulti(1,$xpub))
    abcd0123 m/48'/1'/0'/2'
    /0/*,/1/*
    # export=file · not QR
""".trimIndent()
