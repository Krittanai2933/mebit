package com.mebit.app.feature.onboarding

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.ui.components.MebitScreen
import com.mebit.app.ui.components.PrimaryCta
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbNavyInk

/** iOS has no link into Face ID & Passcode, so the screen lists the way there. */
private val IOS_STEPS = listOf(
    "เปิดแอป การตั้งค่า",
    "แตะ Face ID และรหัส (หรือ Touch ID และรหัส)",
    "แตะ เปิดใช้รหัส แล้วตั้งรหัส",
    "กลับมาที่ mebit แอปจะไปต่อเอง",
)

@Composable
internal fun NoScreenLockScreen(openSettings: (() -> Unit)?) {
    MebitScreen(
        eyebrow = null,
        title = "ตั้งล็อกหน้าจอก่อนใช้ mebit",
        subtitle = "mebit ใช้ล็อกหน้าจอของเครื่อง (Face ID ลายนิ้วมือ หรือรหัส) ป้องกันกระเป๋าและการส่งเงิน เครื่องนี้ยังไม่ได้ตั้งล็อกหน้าจอ",
        onBack = null,
        onOpenCatalog = {},
        tag = "screen_no_screen_lock",
        bottom = openSettings?.let { open ->
            {
                Spacer(modifier = Modifier.height(12.dp))
                PrimaryCta("เปิดการตั้งค่าความปลอดภัย", "cta_open_security_settings", open)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "ตั้งล็อกหน้าจอแล้วกลับมาที่ mebit แอปจะไปต่อเอง",
                    color = MbBody,
                    fontSize = 13.5.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().testTag("no_lock_come_back"),
                )
            }
        },
    ) {
        if (openSettings == null) {
            Spacer(modifier = Modifier.height(28.dp))
            IOS_STEPS.forEachIndexed { i, step ->
                if (i > 0) Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth().testTag("no_lock_step_${i + 1}")) {
                    Text("${i + 1}.", color = MbNavyInk, fontSize = 13.5.sp, lineHeight = 20.sp, modifier = Modifier.width(22.dp))
                    Text(step, color = MbBody, fontSize = 13.5.sp, lineHeight = 20.sp)
                }
            }
        }
    }
}
