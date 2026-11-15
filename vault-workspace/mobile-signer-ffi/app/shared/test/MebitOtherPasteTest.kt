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
    fun hardenedHNormalizes() {
        val store = KeyringStore()
        assertTrue(store.startMebitOtherFromPaste("abcd0123\n$tpubA\nm/48h/1h/0h/2h"))
        assertEquals(tpubA, store.pending?.xpub)
        assertEquals("m/48'/1'/0'/2'", store.pending?.derivationPath)
    }

    @Test
    fun anotherBip48AccountIsStaged() {
        val store = KeyringStore()
        assertTrue(store.startMebitOtherFromPaste("$PUBLIC_FINGERPRINT\n$tpub48Account5\nm/48'/1'/5'/2'"))
        assertEquals("m/48'/1'/5'/2'", store.pending?.derivationPath)
        assertNull(store.lastError)
    }

    @Test
    fun singleKeyStandardsAreRefusedWithTheBipTheyCameFrom() {
        for ((bip, xpub) in singleKeyAccounts) {
            assertEquals(
                "$NOT_FOR_MULTISIG · m/$bip'/1'/0' เป็น path ของกระเป๋ากุญแจเดียวตาม BIP$bip · ต้องเป็น m/48'/1'/N'/2' ตาม BIP48",
                refusal("$PUBLIC_FINGERPRINT\n$xpub\nm/$bip'/1'/0'"),
            )
        }
    }

    @Test
    fun aSingleKeyPrefixIsRefusedWithItsStandard() {
        for ((prefix, bip) in listOf("vpub" to "84", "zpub" to "84", "upub" to "49", "ypub" to "49")) {
            assertEquals(
                "$NOT_FOR_MULTISIG · $prefix เป็นกุญแจของกระเป๋ากุญแจเดียวตาม BIP$bip · ต้องเป็น tpub ที่ m/48'/1'/N'/2' ตาม BIP48",
                refusal("abcd0123\n$prefix${tpubA.removePrefix("tpub")}\nm/48'/1'/0'/2'"),
            )
        }
    }

    @Test
    fun theNestedScriptTypeIsRefused() {
        assertEquals(
            "$NOT_FOR_MULTISIG · m/48'/1'/0'/1' ลงท้ายด้วย 1' คือ Nested SegWit (P2SH-P2WSH) · ต้องลงท้ายด้วย 2' คือ Native SegWit (P2WSH)",
            refusal("$PUBLIC_FINGERPRINT\n$tpub48Nested\nm/48'/1'/0'/1'"),
        )
    }

    @Test
    fun aMainnetPathOrKeyIsRefused() {
        assertEquals(
            "$NOT_FOR_MULTISIG · ต้องเป็น m/48'/1'/N'/2' ตาม BIP48 แต่กุญแจนี้อยู่ที่ m/48'/0'/0'/2'",
            refusal("$PUBLIC_FINGERPRINT\n$tpub48Mainnet\nm/48'/0'/0'/2'"),
        )
        assertEquals(
            "$NOT_FOR_MULTISIG · xpub เป็นกุญแจ mainnet · แอปนี้ใช้ tpub ของ testnet ที่ m/48'/1'/N'/2'",
            refusal("abcd0123\nxpub${tpubA.removePrefix("tpub")}\nm/48'/1'/0'/2'"),
        )
    }

    @Test
    fun anUnhardenedPathIsRefused() {
        assertEquals(
            "$NOT_FOR_MULTISIG · ต้องเป็น m/48'/1'/N'/2' ตาม BIP48 แต่กุญแจนี้อยู่ที่ m/48/1/0/2",
            refusal("abcd0123\n$tpubA\nm/48/1/0/2"),
        )
    }

    @Test
    fun aKeyFromAnotherLevelIsRefusedUnderABip48Path() {
        val sitsElsewhere = "$NOT_FOR_MULTISIG · xpub นี้ไม่ได้อยู่ที่ m/48'/1'/0'/2' · คัดลอก xpub และ path จากกุญแจดอกเดียวกัน"
        assertEquals(sitsElsewhere, refusal("$PUBLIC_FINGERPRINT\n${singleKeyAccounts.getValue("84")}\nm/48'/1'/0'/2'"))
        assertEquals(sitsElsewhere, refusal("$PUBLIC_FINGERPRINT\n$tpub48Nested\nm/48'/1'/0'/2'"))
    }

    @Test
    fun aBsmsWhoseCosignerIsASingleKeyIsRefused() {
        val text = """
            BSMS 1.0
            wsh(sortedmulti(1,[$PUBLIC_FINGERPRINT/84'/1'/0']${singleKeyAccounts.getValue("84")}))
            /0/*,/1/*
        """.trimIndent()
        assertEquals(
            "$NOT_FOR_MULTISIG · m/84'/1'/0' เป็น path ของกระเป๋ากุญแจเดียวตาม BIP84 · ต้องเป็น m/48'/1'/N'/2' ตาม BIP48",
            refusal(text),
        )
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

private const val NOT_FOR_MULTISIG = "ใช้กุญแจนี้สร้าง Wallet แบบ Multisig (Native SegWit) ไม่ได้"

// The public BIP-39 test phrase (all-zero entropy) at each path the BIP48 rule tells apart.
private const val PUBLIC_FINGERPRINT = "73c5da0a"
private const val tpub48Account5 =
    "tpubDDwkwXcK3eeY7aJvGSa8FBeReVpm31sNot7bokA6fhQsavU1YHYAn5GWs9eUS1sAUqbMA6HyJsTWCwyWQKtuFV9wJmwWch6FZPTP2Lzqwjo"
private const val tpub48Nested =
    "tpubDFH9dgzveyD8yHQb8VrpG8FYAuwcLMHMje2CCcbBo1FpaGzYVtJeYYxcYgRqSTta5utUFts8nPPHs9C2bqoxrey5jia6Dwf9mpwrPq7YvcJ"
private const val tpub48Mainnet =
    "tpubDE7sgmL5juFR6q7SM3sF3tVQvee83rJZxZnaxD8fWwXLCwdKMn3jTsgKoahBKgaKK3WQEB37pQFC7JxotgjMuvyJZJAKpHsDYRqjpxt8BYQ"
private val singleKeyAccounts = mapOf(
    "44" to "tpubDC5FSnBiZDMmhiuCmWAYsLwgLYrrT9rAqvTySfuCCrgsWz8wxMXUS9Tb9iVMvcRbvFcAHGkMD5Kx8koh4GquNGNTfohfk7pgjhaPCdXpoba",
    "49" to "tpubDD7tXK8KeQ3YY83yWq755fHY2JW8Ha8Q765tknUM5rSvjPcGWfUppDFMpQ1ScziKfW3ZNtZvAD7M3u7bSs7HofjTD3KP3YxPK7X6hwV8Rk2",
    "84" to "tpubDC8msFGeGuwnKG9Upg7DM2b4DaRqg3CUZa5g8v2SRQ6K4NSkxUgd7HsL2XVWbVm39yBA4LAxysQAm397zwQSQoQgewGiYZqrA9DsP4zbQ1M",
    "86" to "tpubDDfvzhdVV4unsoKt5aE6dcsNsfeWbTgmLZPi8LQDYU2xixrYemMfWJ3BaVneH3u7DBQePdTwhpybaKRU95pi6PMUtLPBJLVQRpzEnjfjZzX",
)

/** The paste is refused: nothing is staged, and the line the screen shows is returned. */
private fun refusal(text: String): String? = KeyringStore().run {
    assertEquals(false, startMebitOtherFromPaste(text))
    assertNull(pending)
    lastError
}

private fun bareBsms(xpub: String): String = """
    BSMS 1.0
    wsh(sortedmulti(1,$xpub))
    abcd0123 m/48'/1'/0'/2'
    /0/*,/1/*
    # export=file · not QR
""".trimIndent()
