package com.mebit.app.feature.send

import com.mebit.app.VaultsStorage
import com.mebit.app.data.FakeVaultsStorage
import com.mebit.app.data.KeySourceLabel
import com.mebit.app.data.SavedVaults
import com.mebit.app.data.TEST_CHECK
import com.mebit.app.data.VaultOwners
import com.mebit.app.ui.components.DeleteItem
import kotlin.test.Test
import kotlin.test.assertEquals

class DeleteMenuLinesTest {
    private val unreadable = DeleteItem.Blocked("ลบไม่ได้ในตอนนี้ · อ่านรายการ Wallet ไม่ได้")

    @Test
    fun aKeyIsOfferedOnlyWhenItIsNoWalletsOwnKeyAndNoSeveralKeyWalletUsesIt() {
        assertEquals(DeleteItem.Offered, keyDeleteItem(ownWalletName = null, usingWallets = emptyList()))
        assertEquals(
            DeleteItem.Blocked("ลบไม่ได้ · กุญแจนี้ใช้อยู่ใน Wallet 1, Wallet ครอบครัว"),
            keyDeleteItem(ownWalletName = null, usingWallets = listOf("Wallet 1", "Wallet ครอบครัว")),
            "every several-key wallet that uses it, by name",
        )
        assertEquals(unreadable, keyDeleteItem(ownWalletName = null, usingWallets = null))
    }

    @Test
    fun aWalletsOwnKeyAlwaysPointsToTheWalletPage() {
        val own = DeleteItem.Blocked("ลบไม่ได้ · เป็นกุญแจของ กระเป๋าใช้จ่าย · ลบที่หน้า Wallet แทน")
        assertEquals(own, keyDeleteItem(ownWalletName = "กระเป๋าใช้จ่าย", usingWallets = emptyList()))
        assertEquals(own, keyDeleteItem(ownWalletName = "กระเป๋าใช้จ่าย", usingWallets = listOf("Wallet ครอบครัว")), "even while a several-key wallet uses it")
        assertEquals(own, keyDeleteItem(ownWalletName = "กระเป๋าใช้จ่าย", usingWallets = null), "and while the book cannot be judged")
    }

    @Test
    fun aOneKeyWalletIsOfferedOnlyWhenAnotherRemainsItIsSavedTheBookIsReadAndNoVaultUsesItsKey() {
        assertEquals(DeleteItem.Offered, walletDeleteItem(listedWallets = 2, saved = true, usesKey = false, vaultName = null))
        assertEquals(
            DeleteItem.Blocked("ลบไม่ได้ · กุญแจนี้ใช้อยู่ใน Wallet ครอบครัว"),
            walletDeleteItem(listedWallets = 2, saved = true, usesKey = true, vaultName = "Wallet ครอบครัว"),
        )
        assertEquals(
            DeleteItem.Blocked("ลบไม่ได้ · ต้องมีกระเป๋าอย่างน้อย 1 ใบในเครื่อง"),
            walletDeleteItem(listedWallets = 1, saved = false, usesKey = true, vaultName = "Wallet ครอบครัว"),
            "the last wallet says so first, whatever else holds",
        )
        assertEquals(
            DeleteItem.Blocked("ลบไม่ได้ · กระเป๋านี้ยังไม่ได้บันทึกในเครื่อง"),
            walletDeleteItem(listedWallets = 2, saved = false, usesKey = null, vaultName = null),
            "its words are only in memory",
        )
        assertEquals(unreadable, walletDeleteItem(listedWallets = 3, saved = true, usesKey = null, vaultName = null))
    }

    @Test
    fun aSeveralKeyWalletIsOfferedOnlyWhileItsBookIsSavedAndCanBeJudged() {
        val notSaved = DeleteItem.Blocked("ลบไม่ได้ · Wallet นี้ยังไม่ได้บันทึกในเครื่อง")
        val wallet = { VaultOwners(listOf(TEST_CHECK), everyPlaceOpened = true) }
        fun item(storage: VaultsStorage?, owners: () -> VaultOwners?) = vaultDeleteItem(SavedVaults(storage, owners).apply { sync() }.saving)
        assertEquals(notSaved, item(null, wallet), "no store, as on Android 10 and older")
        assertEquals(notSaved, item(FakeVaultsStorage(), { null }), "no saved wallet, as on a phone with no screen lock")
        assertEquals(unreadable, item(FakeVaultsStorage("not a book"), wallet), "a damaged book")
        assertEquals(DeleteItem.Offered, item(FakeVaultsStorage(), wallet), "a healthy book")
    }

    @Test
    fun aHardwareWalletIsOfferedOnTheSameRuleAsASeveralKeyWallet() {
        val notSaved = DeleteItem.Blocked("ลบไม่ได้ · กระเป๋านี้ยังไม่ได้บันทึกในเครื่อง")
        val wallet = { VaultOwners(listOf(TEST_CHECK), everyPlaceOpened = true) }
        fun item(storage: VaultsStorage?, owners: () -> VaultOwners?) = hardwareDeleteItem(SavedVaults(storage, owners).apply { sync() }.saving)
        assertEquals(notSaved, item(FakeVaultsStorage(), { null }), "no saved wallet")
        assertEquals(unreadable, item(FakeVaultsStorage("not a book"), wallet), "a damaged book")
        assertEquals(unreadable, item(FakeVaultsStorage().apply { unreadable = true }, wallet), "a book not read yet")
        assertEquals(DeleteItem.Offered, item(FakeVaultsStorage(), wallet), "a healthy book")
    }

    @Test
    fun theSheetsSpaceLatinAndDigitNamesFromTheVerbAndJoinThaiOnes() {
        assertEquals("ลบกระเป๋า 2 ออกจากเครื่องนี้ไหม", removeSheetTitle("กระเป๋า 2"))
        assertEquals("ลบกระเป๋า 2 ออกจากเครื่องนี้", removeReason("กระเป๋า 2"))
        assertEquals("ลบ Wallet 2 ออกจากเครื่องนี้ไหม", removeSheetTitle("Wallet 2"), "a several-key wallet")
        assertEquals("ลบ Wallet 2 ออกจากเครื่องนี้", removeReason("Wallet 2"))
        assertEquals("ลบ Trezor เครื่องเก่า ออกจากเครื่องนี้ไหม", removeSheetTitle("Trezor เครื่องเก่า"), "a key")
        assertEquals("ลบกุญแจบ้าน ออกจากเครื่องนี้ไหม", removeSheetTitle("กุญแจบ้าน"))
        assertEquals("ลบ 2 กุญแจ ออกจากเครื่องนี้ไหม", removeSheetTitle("2 กุญแจ"))
    }

    @Test
    fun theDeleteKeySheetSaysWhereTheKeyStillLives() {
        assertEquals("Jade ยังเก็บกุญแจนี้ไว้ · เพิ่มกลับมาได้ทุกเมื่อจากปุ่ม + ในคลังกุญแจ", deleteKeyBody(KeySourceLabel.Jade))
        assertEquals("Trezor ยังเก็บกุญแจนี้ไว้ · เพิ่มกลับมาได้ทุกเมื่อจากปุ่ม + ในคลังกุญแจ", deleteKeyBody(KeySourceLabel.TrezorSafe7))
        assertEquals("เครื่องนั้นยังเก็บกุญแจนี้ไว้ · เพิ่มกลับมาได้ทุกเมื่อจากปุ่ม + ในคลังกุญแจ", deleteKeyBody(KeySourceLabel.MebitOther))
        assertEquals("กุญแจจะถูกลบออกจากคลังกุญแจในเครื่องนี้", deleteKeyBody(KeySourceLabel.ThisDevice))
    }

    @Test
    fun theSeveralKeySheetSaysTheCoinsStayAndWhatSpendsThem() {
        assertEquals(
            "เหรียญยังอยู่บนเครือข่าย · ใช้จ่ายได้ด้วยไฟล์ BSMS กับกุญแจ 2 จาก 3 · กุญแจทั้ง 3 ยังอยู่ในคลังกุญแจ",
            removeVaultBody(m = 2, n = 3),
        )
    }
}
