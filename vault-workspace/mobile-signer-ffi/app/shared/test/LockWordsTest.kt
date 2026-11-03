package com.mebit.app.feature.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LockWordsTest {
    private val ios = LockWords("Face ID")
    private val android = LockWords(null)

    @Test
    fun iosKeepsTheFaceIdWordsOfTheBoards() {
        assertEquals("Face ID ไม่ผ่าน", ios.failedTitle)
        assertEquals("SECURITY · Face ID ไม่ผ่าน", ios.failedEyebrow)
        assertEquals("ล็อกแอปด้วย Face ID", ios.enableTitle)
        assertEquals("เปิดใช้ Face ID", ios.enableCta)
        assertEquals("ปลดล็อกด้วย Face ID", ios.unlockCta)
        assertEquals("สแกนใบหน้าเพื่อเปิดแอปต่อ", ios.unlockSubtitle)
        assertEquals("เซ็นด้วย Face ID", ios.signCta)
        assertEquals("สแกนใบหน้าไม่ตรง 2 ครั้ง ลองอีกครั้ง หรือใช้รหัส 6 หลักแทน", ios.failedSubtitle(2))
        assertEquals("เครื่องนี้ยังไม่ได้ตั้ง Face ID หรือรหัสผ่านเครื่อง · ใช้ปุ่มด้านล่างเพื่อไปต่อโดยไม่ล็อก", ios.unavailable(enable = true))
        assertEquals("ปลดล็อกไม่ได้ ตรวจการตั้งค่า Face ID และรหัสผ่านเครื่อง", ios.unavailable(enable = false))
        assertEquals("ใช้รหัส 6 หลัก", ios.codeCta)
        assertEquals("ใช้รหัส 6 หลักแทน", ios.codeLink)
        assertEquals("FACE ID · FAILED", ios.bannerEyebrow)
        assertEquals("ยังไม่ปลดล็อกแอป คุณลองได้อีก 3 ครั้ง ก่อนต้องใช้รหัส 6 หลัก", ios.triesLeft(3))
    }

    @Test
    fun onlyIosShowsTheFaceIdGlyph() {
        assertTrue(ios.faceGlyph)
        assertFalse(android.faceGlyph)
    }

    @Test
    fun androidNamesNoMethodBecauseThePromptChoosesIt() {
        assertEquals("ยืนยันตัวตนเพื่อปลดล็อก", android.unlockCta)
        val every = listOf(
            android.failedTitle, android.failedEyebrow, android.enableTitle, android.enableCta, android.unlockCta,
            android.unlockSubtitle, android.signCta, android.tileLabel, android.failedSubtitle(2), android.failedSubtitle(5),
            android.unavailable(enable = true), android.unavailable(enable = false),
            android.codeCta, android.codeLink, android.bannerEyebrow, android.triesLeft(3),
        )
        for (line in every) {
            for (method in listOf("Face ID", "FACE ID", "ใบหน้า", "ลายนิ้วมือ", "สแกน", "6 หลัก")) {
                assertFalse(line.contains(method), "$line names $method")
            }
        }
    }
}
