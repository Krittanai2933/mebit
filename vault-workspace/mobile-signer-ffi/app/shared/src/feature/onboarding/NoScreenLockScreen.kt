package com.mebit.app.feature.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.resources.Res
import com.mebit.app.resources.ic_padlock
import com.mebit.app.ui.components.MebitScreen
import com.mebit.app.ui.components.PrimaryCta
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbBorder
import com.mebit.app.ui.theme.MbNavy
import com.mebit.app.ui.theme.MbNavyInk
import com.mebit.app.ui.theme.MbWhite
import org.jetbrains.compose.resources.painterResource

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
                // Above the button: below it, the line sat at the window's bottom edge on a POCO X3 NFC with MIUI's button bar.
                Text(
                    text = "ตั้งเสร็จแล้วกลับมาที่ mebit แอปจะไปต่อเอง",
                    color = MbBody,
                    fontSize = 13.5.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().testTag("no_lock_come_back"),
                )
                Spacer(modifier = Modifier.height(14.dp))
                PrimaryCta("ตั้งล็อกหน้าจอ", "cta_open_security_settings", open)
            }
        },
    ) {
        Spacer(modifier = Modifier.height(NavyTileTopGap))
        NavyTile(tag = "no_lock_tile") {
            Image(painterResource(Res.drawable.ic_padlock), contentDescription = null, modifier = Modifier.size(132.dp))
        }
        if (openSettings == null) {
            Spacer(modifier = Modifier.height(52.dp))
            IosSteps()
        }
    }
}

@Composable
private fun IosSteps() {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = Modifier.fillMaxWidth().clip(shape).background(MbWhite).border(1.dp, MbBorder, shape).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IOS_STEPS.forEachIndexed { i, step ->
            Row(
                modifier = Modifier.fillMaxWidth().testTag("no_lock_step_${i + 1}"),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.size(24.dp).background(MbNavy, CircleShape), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", color = MbWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(step, color = MbNavyInk, fontSize = 14.sp, lineHeight = 20.3.sp)
            }
        }
    }
}
