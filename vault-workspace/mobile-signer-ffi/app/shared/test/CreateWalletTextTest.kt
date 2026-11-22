package com.mebit.app.feature.vault

import com.mebit.app.data.KeySourceLabel
import com.mebit.app.data.Preset
import com.mebit.app.data.SavedKey
import com.mebit.app.data.keyLine
import com.mebit.app.navigation.MovieRoute
import com.mebit.ffi.signer.KeyNetwork
import kotlin.test.Test
import kotlin.test.assertEquals

class CreateWalletTextTest {
    @Test
    fun theCounterReadsPickedOverN() {
        assertEquals("เลือกแล้ว 2 / 3 ใบ", pickCountLine(2, 3))
        assertEquals("เลือกแล้ว 0 / 5 ใบ", pickCountLine(0, 5))
    }

    @Test
    fun theDuplicateLineNamesTheSavedWallet() {
        assertEquals("Wallet นี้อยู่ในเครื่องนี้แล้ว · Vault 1", duplicateLine("Vault 1"))
    }

    @Test
    fun theReviewLinesNameMNAndTheKeysThatMayBeLost() {
        assertEquals("Wallet 2 · 2-of-3", reviewTitle("Wallet 2", Preset.TwoOfThree))
        assertEquals("Native SegWit · testnet · ต้องเซ็น 3 จาก 5 กุญแจ", reviewSub(Preset.ThreeOfFive))
        assertEquals(
            listOf(
                "ต้องใช้กุญแจ 2 จาก 3 ใบเซ็นร่วมกัน",
                "กุญแจหายได้ถึง 1 ใบ เงินยังปลอดภัย",
                "กุญแจ: มือถือ, Jade, Trezor",
                "กุญแจสำรอง mebit: ไม่ใช้",
            ),
            reviewRows(Preset.TwoOfThree, listOf("มือถือ", "Jade", "Trezor")),
        )
        assertEquals("กุญแจหายได้ถึง 2 ใบ เงินยังปลอดภัย", reviewRows(Preset.ThreeOfFive, emptyList())[1])
    }

    @Test
    fun theAddressPreviewKeepsTheFirstThreeAndLastTwoGroups() {
        assertEquals(
            listOf("tb1q", "8f3k", "2pxa", "…", "9wq7", "7k2m"),
            addressPreview("tb1q8f3k2pxaqqqqqqqqqqqqqqqqqqqqqqqq9wq77k2m"),
        )
        assertEquals(listOf("tb1q", "abcd", "efgh"), addressPreview("tb1qabcdefgh"))
    }

    @Test
    fun theCreatedSubtitleShortensTheFirstAddress() {
        assertEquals("Native SegWit · tb1q…7k2m (ที่อยู่แรก)", createdSubtitle("tb1qxyzabcdefghij7k2m"))
    }

    @Test
    fun theScriptRowsTakeTheirPrefixesFromAddressTypeOnEachNetwork() {
        assertEquals("BIP48 · P2WSH · เริ่มด้วย tb1q", MultisigScript.NativeSegwit.detail(KeyNetwork.TEST))
        assertEquals("BIP48 · P2SH-P2WSH · เริ่มด้วย 2", MultisigScript.NestedSegwit.detail(KeyNetwork.TEST))
        assertEquals("BIP48 · P2WSH · เริ่มด้วย bc1q", MultisigScript.NativeSegwit.detail(KeyNetwork.MAIN))
        assertEquals("BIP48 · P2SH-P2WSH · เริ่มด้วย 3", MultisigScript.NestedSegwit.detail(KeyNetwork.MAIN))
        assertEquals(listOf("Nested SegWit", "Native SegWit"), MultisigScript.entries.map { it.label })
    }

    @Test
    fun theBsmsFileKeepsAShareableNameAndItsExtension() {
        assertEquals("Wallet_2.bsms", bsmsFileName("Wallet 2"))
        assertEquals("Wallet_10.bsms", bsmsFileName("Wallet 10"))
    }

    @Test
    fun aKeysLineIsItsSourceThenItsFingerprintInGroupsOfFour() {
        fun key(source: KeySourceLabel, fingerprint: String) = SavedKey("id", "name", fingerprint, "xpub", "m/48'/1'/0'/2'", source, verified = true)
        assertEquals("mebit บนเครื่องนี้ · 3a9f 21c4", keyLine(key(KeySourceLabel.ThisDevice, "3A9F21C4")))
        assertEquals("mebit บนเครื่องอื่น · 00ff 00aa", keyLine(key(KeySourceLabel.MebitOther, "00ff00aa")))
        assertEquals("Jade · 7f2c a91b", keyLine(key(KeySourceLabel.Jade, "7f2ca91b")))
        assertEquals("Trezor Safe 7 · c04d 88e1", keyLine(key(KeySourceLabel.TrezorSafe7, "c04d88e1")))
    }

    @Test
    fun theWizardRoutesCarryPenpotsStepsAndTitles() {
        val steps = listOf(MovieRoute.Policy, MovieRoute.ChooseKeys, MovieRoute.ScriptType, MovieRoute.Review)
        steps.forEachIndexed { i, route -> assertEquals("POLICY · ขั้น ${i + 1} / 4", route.eyebrow, route.name) }
        assertEquals(
            listOf("Wallet นี้ใช้กุญแจแบบไหน", "เลือกกุญแจมาประกอบ", "เลือกรูปแบบ Multisig", "ตรวจก่อนสร้าง"),
            steps.map { it.titleTh },
        )
        assertEquals("คงเกณฑ์ M-of-N แล้วเลือกสคริปต์ที่ใช้กับ Wallet", MovieRoute.ScriptType.bodyTh)
        assertEquals("vault/script-type", MovieRoute.ScriptType.path)
        assertEquals("WALLET CREATED", MovieRoute.VaultCreated.eyebrow)
        assertEquals("Wallet พร้อมใช้งาน", MovieRoute.VaultCreated.titleTh)
    }
}
