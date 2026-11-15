package com.mebit.app.feature.onboarding

import com.mebit.app.Why
import com.mebit.app.data.SaveState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotSavedNoticeTest {
    @Test
    fun aSavedWalletWhoseLastWriteFailedIsToldItWillBeTriedAgain() {
        assertEquals("บันทึกสถานะล่าสุดไม่สำเร็จ จะลองใหม่เมื่อเปิดแอปอีกครั้ง", saveNoticeText(SaveState.Saved, null, lastSaveFailed = true))
        assertNull(saveNoticeText(SaveState.Saved, null, lastSaveFailed = false), "a saved wallet with nothing failed")
        assertNull(saveNoticeText(SaveState.Staged, null, lastSaveFailed = true), "a staged import has no copy to update")
        assertEquals(notSavedText(Why.TryLater), saveNoticeText(SaveState.NotSaved, Why.TryLater, lastSaveFailed = true), "an unsaved wallet keeps its own line")
    }

    @Test
    fun aPhoneWithNoScreenLockIsToldToSetOneAndOpenTheAppAgain() {
        assertEquals("ยังไม่ได้บันทึกกุญแจไว้ในเครื่อง · ตั้งรหัสล็อกหน้าจอ แล้วเปิดแอปอีกครั้ง", notSavedText(Why.NoScreenLock))
    }

    @Test
    fun aPhoneThatCannotSaveYetIsToldToWriteTheWordsDown() {
        for (why in listOf(Why.NotWired, Why.OsTooOld)) {
            assertEquals("เครื่องนี้ยังบันทึกกุญแจไว้ไม่ได้ · จด 24 คำไว้ให้ดี", notSavedText(why), "$why")
        }
    }

    @Test
    fun aSaveThatFailedIsToldToWriteTheWordsDown() {
        for (why in listOf(Why.TryLater, Why.Other, null)) {
            assertEquals("บันทึกกุญแจไว้ในเครื่องไม่สำเร็จ · จด 24 คำไว้ให้ดี", notSavedText(why), "$why")
        }
    }
}
