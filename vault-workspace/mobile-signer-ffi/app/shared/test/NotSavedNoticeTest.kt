package com.mebit.app.feature.onboarding

import com.mebit.app.Why
import kotlin.test.Test
import kotlin.test.assertEquals

class NotSavedNoticeTest {
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
