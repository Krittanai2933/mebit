package com.mebit.app.feature.onboarding

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import com.mebit.app.Why
import com.mebit.app.data.OnboardingSession
import com.mebit.app.data.SaveState

/**
 * One line, drawn like Home's error line, saying that this phone does not keep the session's key, or that its last flag or
 * payment-guard write failed. It renders nothing, so [modifier]'s padding takes no room either, unless the session holds a
 * key its store did not save or did not update: a staged or hardware-only session shows nothing. No Penpot board has it
 * (docs/04-open-items.md).
 */
@Composable
internal fun NotSavedNotice(session: OnboardingSession, modifier: Modifier = Modifier) {
    if (session.words.isEmpty()) return
    val text = saveNoticeText(session.saved, session.notSavedBecause, session.lastSaveFailed) ?: return
    Text(
        text = text,
        color = MaterialTheme.colorScheme.error,
        fontSize = 12.sp,
        modifier = modifier.testTag("not_saved_notice"),
    )
}

/** Pure. The notice for a session that holds a key, or null when the copy on the phone is current. */
internal fun saveNoticeText(saved: SaveState, why: Why?, lastSaveFailed: Boolean): String? = when {
    saved == SaveState.NotSaved -> notSavedText(why)
    saved == SaveState.Saved && lastSaveFailed -> "บันทึกสถานะล่าสุดไม่สำเร็จ จะลองใหม่เมื่อเปิดแอปอีกครั้ง"
    else -> null
}

/** Pure. What the notice says for [why] the key was not saved, null being a save that named no reason. */
internal fun notSavedText(why: Why?): String = when (why) {
    Why.NoScreenLock -> "ยังไม่ได้บันทึกกุญแจไว้ในเครื่อง · ตั้งรหัสล็อกหน้าจอ แล้วเปิดแอปอีกครั้ง"
    Why.NotWired, Why.OsTooOld -> "เครื่องนี้ยังบันทึกกุญแจไว้ไม่ได้ · จด 24 คำไว้ให้ดี"
    Why.TryLater, Why.Other, null -> "บันทึกกุญแจไว้ในเครื่องไม่สำเร็จ · จด 24 คำไว้ให้ดี"
}
