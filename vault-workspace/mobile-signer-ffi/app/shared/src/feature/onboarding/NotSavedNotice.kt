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
 * One line, drawn like Home's error line, saying that this phone does not keep the session's key. It renders nothing, so
 * [modifier]'s padding takes no room either, unless the session holds a key its store did not save: a saved, staged or
 * hardware-only session shows nothing. No Penpot board has it (docs/04-open-items.md).
 */
@Composable
internal fun NotSavedNotice(session: OnboardingSession, modifier: Modifier = Modifier) {
    if (session.words.isEmpty() || session.saved != SaveState.NotSaved) return
    Text(
        text = notSavedText(session.notSavedBecause),
        color = MaterialTheme.colorScheme.error,
        fontSize = 12.sp,
        modifier = modifier.testTag("not_saved_notice"),
    )
}

/** Pure. What the notice says for [why] the key was not saved, null being a save that named no reason. */
internal fun notSavedText(why: Why?): String = when (why) {
    Why.NoScreenLock -> "ยังไม่ได้บันทึกกุญแจไว้ในเครื่อง · ตั้งรหัสล็อกหน้าจอ แล้วเปิดแอปอีกครั้ง"
    Why.NotWired, Why.OsTooOld -> "เครื่องนี้ยังบันทึกกุญแจไว้ไม่ได้ · จด 24 คำไว้ให้ดี"
    Why.TryLater, Why.Other, null -> "บันทึกกุญแจไว้ในเครื่องไม่สำเร็จ · จด 24 คำไว้ให้ดี"
}
