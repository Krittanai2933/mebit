package com.mebit.app.feature.send

import com.mebit.app.Place
import com.mebit.app.data.AddRefusal
import com.mebit.app.data.Cause
import com.mebit.app.data.UnlockResult
import com.mebit.app.data.addRefusalLine
import com.mebit.app.data.unopenedNotice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SeveralWalletsLinesTest {
    @Test
    fun theRemoveRowNeedsTwoWalletsAndThenHasThreeStates() {
        assertEquals(RemoveRow.Hidden, removeRow(1, saved = true, usesKey = false, vaultName = null))
        assertEquals(RemoveRow.Hidden, removeRow(1, saved = true, usesKey = true, vaultName = "Vault A"), "one wallet left shows nothing, even in a vault")
        assertEquals(RemoveRow.Offered, removeRow(2, saved = true, usesKey = false, vaultName = null))
        assertEquals(RemoveRow.InVault("Vault A"), removeRow(2, saved = true, usesKey = true, vaultName = "Vault A"))
        assertEquals(RemoveRow.Unreadable, removeRow(3, saved = true, usesKey = null, vaultName = null))
    }

    @Test
    fun aWalletThatIsNotSavedShowsNoRemoveRow() {
        assertEquals(RemoveRow.Hidden, removeRow(2, saved = false, usesKey = false, vaultName = null), "its words are only in memory")
    }

    @Test
    fun theBlockedLinesNameTheVaultOrSayTheBookCouldNotBeRead() {
        assertEquals("ลบไม่ได้ · กุญแจนี้ใช้อยู่ใน Vault A", removeBlockedLine(RemoveRow.InVault("Vault A")))
        assertEquals("ลบไม่ได้ในตอนนี้ · อ่านรายการ Vault ไม่ได้", removeBlockedLine(RemoveRow.Unreadable))
        assertNull(removeBlockedLine(RemoveRow.Offered))
        assertNull(removeBlockedLine(RemoveRow.Hidden))
    }

    @Test
    fun theConfirmNamesTheWalletInItsTitleAndItsReason() {
        assertEquals("ลบกระเป๋า 2 ออกจากเครื่องนี้ไหม", removeSheetTitle("กระเป๋า 2"))
        assertEquals("ลบกระเป๋า 2 ออกจากเครื่องนี้", removeReason("กระเป๋า 2"))
    }

    @Test
    fun eachRefusalHasItsOwnLine() {
        assertEquals("เครื่องนี้เก็บกระเป๋าได้สูงสุด 10 ใบ", addRefusalLine(AddRefusal.Full))
        assertEquals("เครื่องนี้ยังบันทึกกระเป๋าไม่ได้ ตั้งรหัสล็อกหน้าจอก่อน จึงจะเพิ่มกระเป๋าได้", addRefusalLine(AddRefusal.NotSavedHere))
        assertEquals("ตั้งค่ากระเป๋าที่ยังไม่เสร็จก่อน จึงจะเพิ่มกระเป๋าใหม่ได้", addRefusalLine(AddRefusal.SetupUnfinished))
        assertEquals("เปิดกระเป๋าบางใบไม่ได้ในตอนนี้ ปิดแล้วเปิดแอปใหม่ก่อนเพิ่มกระเป๋า", addRefusalLine(AddRefusal.Unopened))
    }

    @Test
    fun theUnopenedNoticeNamesEachPlaceOrIsAbsent() {
        assertNull(unopenedNotice(emptyMap()))
        val broken = UnlockResult.Broken(Cause.Corrupt)
        assertEquals(
            "เปิด กระเป๋า 2, กระเป๋า 4 ไม่ได้ในตอนนี้ กระเป๋าอื่นใช้ได้ตามปกติ",
            unopenedNotice(linkedMapOf(Place.parse(2)!! to broken, Place.parse(4)!! to broken)),
        )
    }
}
