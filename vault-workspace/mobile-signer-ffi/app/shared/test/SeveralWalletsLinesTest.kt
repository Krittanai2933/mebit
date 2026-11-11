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
