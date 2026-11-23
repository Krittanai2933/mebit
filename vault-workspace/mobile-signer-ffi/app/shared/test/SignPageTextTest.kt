package com.mebit.app.feature.send

import com.mebit.app.feature.onboarding.LockWords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The words and marks of Sign on iPhone (Penpot 5.3.1, 5.4.1, 5.3.1.1) and the details card's address (5.3.2). */
class SignPageTextTest {
    private val iphone = LockWords("Face ID")
    private val android = LockWords(null)

    @Test
    fun onIphoneThePageNamesFaceIdAsTheBoardsDo() {
        val asking = phoneSignText(PhoneSignState.Asking, iphone)
        assertEquals("ตรวจสอบบนอุปกรณ์", asking.title)
        assertEquals("โปรดตรวจยอดเงินและที่อยู่ปลายทางบนหน้าจอนี้ให้ตรงกับรายการด้านล่างทุกตัวอักษร ก่อนยืนยันด้วย Face ID", asking.subtitle)
        assertEquals("ยืนยันด้วย Face ID", iphone.signStep)
        val failed = phoneSignText(PhoneSignState.OwnerFailed, iphone)
        assertEquals("Face ID ไม่ผ่าน", failed.title)
        assertEquals("สแกนใบหน้าไม่ตรง ลองอีกครั้ง หรือยกเลิกการเซ็น", failed.subtitle)
        assertEquals("ไม่ผ่าน", failed.pill)
    }

    @Test
    fun onAndroidThePageNamesNoMethodBecauseTheSystemPromptChoosesIt() {
        assertTrue(phoneSignText(PhoneSignState.Asking, android).subtitle.endsWith("ก่อนยืนยันตัวตน"))
        assertEquals("ยืนยันตัวตน", android.signStep)
        val failed = phoneSignText(PhoneSignState.OwnerFailed, android)
        assertEquals("ยืนยันตัวตนไม่ผ่าน", failed.title)
        assertEquals("ยืนยันตัวตนไม่ผ่าน ลองอีกครั้ง หรือยกเลิกการเซ็น", failed.subtitle)
    }

    @Test
    fun aSignedKeyShowsTheConfirmedBoardAndNoBar() {
        val signed = phoneSignText(PhoneSignState.Signed, iphone)
        assertEquals("ยืนยันบนอุปกรณ์แล้ว", signed.title)
        assertEquals(SIGNED_SUBTITLE, signed.subtitle)
        assertNull(signed.bar)
    }

    @Test
    fun aSignatureNotMadeSaysTheSendsLineAndThatNothingLeft() {
        val failed = phoneSignText(PhoneSignState.NotSigned("เซ็นไม่สำเร็จ ลองใหม่อีกครั้ง"), iphone)
        assertEquals("เซ็นไม่สำเร็จ", failed.title)
        assertEquals("เซ็นไม่สำเร็จ ลองใหม่อีกครั้ง", failed.subtitle)
        assertEquals("ยังไม่เซ็น เงินยังไม่ถูกส่ง", failed.bar)
    }

    @Test
    fun theStepsMarkTheCheckNowThenAllDoneOrTheLastFailed() {
        assertEquals(listOf(StepMark.Done, StepMark.Current, StepMark.Pending), phoneSignMarks(PhoneSignState.Asking))
        assertEquals(List(3) { StepMark.Done }, phoneSignMarks(PhoneSignState.Signed))
        assertEquals(listOf(StepMark.Done, StepMark.Done, StepMark.Failed), phoneSignMarks(PhoneSignState.OwnerFailed))
        assertEquals(listOf(StepMark.Done, StepMark.Done, StepMark.Failed), phoneSignMarks(PhoneSignState.NotSigned("x")))
    }

    @Test
    fun thePillSpinsOnlyWhileTheSignerIsAsked() {
        assertTrue(signPill(signed = false, failedPill = null).spinning)
        assertEquals("รอยืนยัน", signPill(signed = false, failedPill = null).label)
        val signed = signPill(signed = true, failedPill = null)
        assertEquals("ยืนยันแล้ว", signed.label)
        assertFalse(signed.spinning)
        val failed = signPill(signed = false, failedPill = "ไม่ผ่าน")
        assertEquals("ไม่ผ่าน", failed.label)
        assertFalse(failed.spinning)
    }

    @Test
    fun theAddressBreaksAfterFiveGroupsAsTheBoardDoes() {
        assertEquals("bc1q 9h7a 2kxv 7tq3 5zcd\nw0lf p8xr 4e8s m4kz", addressLines("bc1q9h7a2kxv7tq35zcdw0lfp8xr4e8sm4kz"))
        val p2wsh = "tb1q" + "x".repeat(58)
        assertEquals(listOf(5, 5, 5, 1), addressLines(p2wsh).lines().map { it.split(" ").size })
    }
}
