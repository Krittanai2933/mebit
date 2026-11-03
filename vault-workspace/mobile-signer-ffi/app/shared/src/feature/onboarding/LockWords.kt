package com.mebit.app.feature.onboarding

/**
 * The words of the lock screens and the sign button. [biometric] is the name of the check the platform's own prompt performs
 * (iOS: Face ID), or null where the system prompt chooses the method (Android), so no method is named there.
 */
internal class LockWords(private val biometric: String?) {
    private val named = biometric != null

    val failedTitle = if (named) "$biometric ไม่ผ่าน" else "ยืนยันตัวตนไม่ผ่าน"
    val failedEyebrow = "SECURITY · $failedTitle"
    val enableTitle = if (named) "ล็อกแอปด้วย $biometric" else "ล็อกแอปด้วยการยืนยันตัวตน"
    val enableCta = if (named) "เปิดใช้ $biometric" else "เปิดใช้การล็อกแอป"
    val unlockCta = if (named) "ปลดล็อกด้วย $biometric" else "ยืนยันตัวตนเพื่อปลดล็อก"
    val unlockSubtitle = if (named) "สแกนใบหน้าเพื่อเปิดแอปต่อ" else "ยืนยันตัวตนเพื่อเปิดแอปต่อ"
    val signCta = if (named) "เซ็นด้วย $biometric" else "ยืนยันตัวตนเพื่อเซ็น"
    val tileLabel = biometric ?: "ยืนยันตัวตน"

    /** The tile shows the Face ID glyph only where the check is Face ID, and a neutral shield elsewhere. */
    val faceGlyph = named

    fun failedSubtitle(misses: Int) =
        (if (named) "สแกนใบหน้าไม่ตรง $misses ครั้ง" else "ยืนยันตัวตนไม่ผ่าน $misses ครั้ง") + " ลองอีกครั้ง หรือใช้รหัส 6 หลักแทน"

    fun unavailable(enable: Boolean) = when {
        enable && named -> "เครื่องนี้ยังไม่ได้ตั้ง $biometric หรือรหัสผ่านเครื่อง · ใช้ปุ่มด้านล่างเพื่อไปต่อโดยไม่ล็อก"
        enable -> "เครื่องนี้ยังไม่ได้ตั้งการยืนยันตัวตนหรือรหัสผ่านเครื่อง · ใช้ปุ่มด้านล่างเพื่อไปต่อโดยไม่ล็อก"
        named -> "ปลดล็อกไม่ได้ ตรวจการตั้งค่า $biometric และรหัสผ่านเครื่อง"
        else -> "ปลดล็อกไม่ได้ ตรวจการตั้งค่าการยืนยันตัวตนและรหัสผ่านเครื่อง"
    }
}
