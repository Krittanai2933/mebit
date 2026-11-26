package com.mebit.app.data

import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.WalletFileFormat
import com.mebit.ffi.signer.WalletFileRead
import com.mebit.ffi.signer.WalletImport
import com.mebit.ffi.signer.WalletKey
import com.mebit.ffi.signer.WalletShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WalletImportTextTest {
    private fun fp(i: Int) = "aaaa000$i"
    private val keys = (1..3).map { WalletKey(fp(it), "m/48'/1'/0'/2'", "tpub$it") }
    private val descriptor = "wsh(sortedmulti(2,${keys.joinToString(",") { "[${it.fingerprint}/48'/1'/0'/2']${it.xpub}" }}))"
    private fun wallet(format: WalletFileFormat = WalletFileFormat.BSMS) =
        WalletImport(descriptor, WalletShape(2u, keys, "tb1qfirstaddress", 0uL), format, null)

    private fun saved(id: String, source: KeySourceLabel, i: Int, mock: Boolean = false, xpub: String = "tpub$i") =
        SavedKey(id, "k$i", fp(i), xpub, "m/48'/1'/0'/2'", source, true, isMock = mock)

    private fun draft(book: SavedVaults = SavedVaults.memoryOnly()) = CreateWalletDraft(book)

    @Test
    fun theNameIsTheFilesWithoutItsExtensionAndUnderscoresAsSpaces() {
        assertEquals("Family vault", walletNameFromFile("Family_vault.bsms"))
        assertEquals("a.b", walletNameFromFile("a.b.txt"))
        assertEquals("plain", walletNameFromFile("plain"))
        assertEquals("", walletNameFromFile(".bsms"))
        assertEquals("", walletNameFromFile("  "))
        assertEquals("", walletNameFromFile("_.txt"))
    }

    @Test
    fun theStoredTextKeepsTheDescriptorOnLineTwoAndReadsBackLikeACreatedWallet() {
        val text = importedBsms(wallet())
        assertEquals(descriptor, descriptorIn(text))
        assertEquals(keys.map { it.xpub }, xpubsIn(text))
        assertEquals(listOf("BSMS 1.0", descriptor), text.lines().take(2))
        assertEquals(keys.map { "${it.fingerprint} ${it.path}" }, text.lines().drop(2).take(3))
        assertEquals("/0/*,/1/*", text.lines()[5])
        assertTrue(text.lines()[6].endsWith("receive[0]=tb1qfirstaddress"))
    }

    @Test
    fun theReviewNamesTheWalletAndTicksEveryCheck() {
        val review = importReview(wallet(), "Family vault", emptyList(), KeyNetwork.TEST)
        assertEquals("Family vault · 2-of-3", review.title)
        assertEquals("Native SegWit · testnet · ต้องเซ็น 2 จาก 3 กุญแจ", review.sub)
        assertEquals(
            listOf("อ่านไฟล์ BSMS 1.0 ได้", "เครือข่าย testnet ตรงกับแอป", "รูปแบบ wsh(sortedmulti) · 2-of-3", "กุญแจ 3 ดอก ไม่ซ้ำกัน", "ไม่มีกุญแจของเครื่องนี้"),
            review.rows.map { it.text },
        )
        assertEquals(listOf(true, true, true, true, false), review.rows.map { it.ticked })
        assertEquals("tb1q firs tadd ress", review.firstAddress)
    }

    @Test
    fun theMainnetReviewChangesOnlyTheNetworkWord() {
        val review = importReview(wallet(), "Family vault", emptyList(), KeyNetwork.MAIN)
        assertEquals("Native SegWit · mainnet · ต้องเซ็น 2 จาก 3 กุญแจ", review.sub)
        assertEquals("เครือข่าย mainnet ตรงกับแอป", review.rows[1].text)
    }

    @Test
    fun aDescriptorFileIsReadAsADescriptor() {
        assertEquals("อ่าน descriptor ได้", importReview(wallet(WalletFileFormat.DESCRIPTOR), "x", emptyList(), KeyNetwork.TEST).rows.first().text)
        assertEquals("อ่านไฟล์ BSMS 1.0 ได้", importReview(wallet(WalletFileFormat.APP_BSMS), "x", emptyList(), KeyNetwork.TEST).rows.first().text)
    }

    @Test
    fun onlyAKeyOfThisPhoneWithTheSameXpubIsThisPhonesKey() {
        val phone = saved("p", KeySourceLabel.ThisDevice, 2)
        val row = { keyring: List<SavedKey> -> importReview(wallet(), "x", keyring, KeyNetwork.TEST).rows.last() }
        assertEquals(ReviewRow("มีกุญแจของเครื่องนี้ · aaaa 0002", true, "import_row_mine"), row(listOf(phone)))
        assertEquals(false, row(listOf(saved("p", KeySourceLabel.ThisDevice, 2, xpub = "other"))).ticked, "same fingerprint, other xpub")
        assertEquals(false, row(listOf(saved("j", KeySourceLabel.Jade, 2))).ticked, "a device key is not this phone")
    }

    @Test
    fun everyRefusalHasItsWords() {
        val test = KeyNetwork.TEST
        assertNull(problemOf(WalletFileRead.Read(wallet()), test))
        // 3.9.4's text, which replaced the one adapted from open item 110 (3).
        assertEquals(
            ImportProblem("เครือข่ายไม่ตรงกัน", "Wallet นี้อยู่บนเครือข่าย mainnet (xpub)\nแต่แอปนี้ตั้งไว้ที่ testnet (tpub)\nโปรดเปลี่ยนเครือข่ายในหน้าตั้งค่า แล้วลองใหม่"),
            problemOf(WalletFileRead.WrongNetwork, test),
        )
        assertEquals(
            ImportProblem("เครือข่ายไม่ตรงกัน", "Wallet นี้อยู่บนเครือข่าย testnet (tpub)\nแต่แอปนี้ตั้งไว้ที่ mainnet (xpub)\nโปรดเปลี่ยนเครือข่ายในหน้าตั้งค่า แล้วลองใหม่"),
            problemOf(WalletFileRead.WrongNetwork, KeyNetwork.MAIN),
        )
        assertEquals(ImportProblem("ที่อยู่แรกไม่ตรงกับกุญแจ", "ไฟล์นี้อาจถูกแก้ไข mebit จึงไม่นำเข้า"), problemOf(WalletFileRead.AddressMismatch("a", "b"), test))
        assertEquals(ImportProblem("อ่านไฟล์นี้ไม่ได้", "ต้องเป็นไฟล์ BSMS 1.0 หรือ descriptor"), problemOf(WalletFileRead.Unreadable("why"), test))
        assertEquals(
            ImportProblem("รูปแบบนี้ยังไม่รองรับ", "รองรับเฉพาะ wsh(sortedmulti(…))\nไฟล์นี้เป็น tr(…)"),
            problemOf(WalletFileRead.Unsupported("tr"), test),
        )
    }

    @Test
    fun whatRustFoundBecomesOneThaiLine() {
        assertEquals("ไฟล์นี้เป็น wsh(multi(…))", unsupportedLine("wsh(multi)"))
        assertEquals("ไฟล์นี้เป็น sh(wsh(…))", unsupportedLine("sh(wsh)"))
        assertEquals("ไฟล์นี้เป็น wpkh(…)", unsupportedLine("wpkh"))
        assertEquals("มีกุญแจจาก phrase เดียวกันสองดอก", unsupportedLine("duplicate keys"))
        assertEquals("มีกุญแจจาก phrase เดียวกันสองดอก", unsupportedLine("two keys from one phrase"))
        assertEquals("มีกุญแจเกิน 15 ดอก", unsupportedLine("more than 15 keys"))
        listOf("key suffix /5/*", "mixed key suffixes", "path restriction /0/*").forEach {
            assertEquals("เส้นทางกุญแจแบบนี้ยังไม่รองรับ", unsupportedLine(it), it)
        }
    }

    @Test
    fun importingSavesThroughTheCreateFlowsBookWithTheFilesNameAndTheKeyringsIds() {
        val d = draft()
        val keyring = listOf(saved("key_b", KeySourceLabel.Jade, 2, mock = true))
        val added = assertIs<ImportSaved.Added>(d.import(wallet(), "Family_vault.bsms", keyring)).vault
        assertEquals("vault_1", added.id)
        assertEquals("Family vault", added.name)
        assertEquals(2, added.m)
        assertEquals(3, added.n)
        assertEquals("tb1qfirstaddress", added.address)
        assertEquals(keys.map { it.fingerprint }, added.keyFingerprints)
        assertEquals(listOf("", "key_b", ""), added.keyIds)
        assertTrue(added.hasMockKey)
        assertEquals(descriptor, descriptorIn(added.bsms))
    }

    @Test
    fun aBlankFileNameGetsTheNextWalletNumber() {
        val d = draft()
        assertEquals("Wallet 1", d.importName(".bsms"))
        assertEquals("Wallet 1", assertIs<ImportSaved.Added>(d.import(wallet(), ".bsms", emptyList())).vault.name)
        assertEquals("Wallet 2", d.importName(""))
    }

    @Test
    fun theSameKeysInAnyOrderAreAlreadyInTheApp() {
        val d = draft()
        assertIs<ImportSaved.Added>(d.import(wallet(), "one.bsms", emptyList()))
        val reordered = wallet().let { it.copy(shape = it.shape.copy(keys = it.shape.keys.reversed())) }
        assertEquals(ImportSaved.Duplicate("one"), d.import(reordered, "two.bsms", emptyList()))
        assertEquals(ImportSaved.Duplicate("one"), d.import(wallet(), "x", emptyList()), "a second tap saves nothing")
        assertEquals("Wallet นี้อยู่ในแอปแล้ว · one", alreadyInAppLine("one"))
        val other = wallet().let { it.copy(shape = it.shape.copy(needed = 3u)) }
        assertIs<ImportSaved.Added>(d.import(other, "three.bsms", emptyList()))
    }
}
