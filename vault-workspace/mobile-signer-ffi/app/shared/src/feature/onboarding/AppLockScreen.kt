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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.BiometricResult
import com.mebit.app.Biometrics
import com.mebit.app.getPlatform
import com.mebit.app.data.OnboardingSession
import com.mebit.app.data.SaveState
import com.mebit.app.resources.Res
import com.mebit.app.resources.face_id
import com.mebit.app.resources.ic_check
import com.mebit.app.ui.components.BottomActions
import com.mebit.app.ui.components.MebitScreen
import com.mebit.app.ui.theme.MbBorder
import com.mebit.app.ui.theme.MbEyebrow
import com.mebit.app.ui.theme.MbGreenWash
import com.mebit.app.ui.theme.MbNavy
import com.mebit.app.ui.theme.MbNavyInk
import com.mebit.app.ui.theme.MbSlate
import com.mebit.app.ui.theme.MbWhite
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

/** [Enable] is onboarding step 1B.5. [Unlock] is the screen shown on return after a minute away. */
enum class LockMode { Enable, Unlock }

private const val ATTEMPTS = 5

/**
 * Penpot Organize 1B.5 / 1B.5.1 / 1B.5.2. Face ID is real on iOS (`Biometrics`). The text button
 * is the phone's own passcode. Where nothing can scan (Android today, a simulator without Face ID)
 * onboarding continues without a lock, and the unlock screen never lets go on its own.
 */
@Composable
fun AppLockScreen(
    session: OnboardingSession,
    biometrics: Biometrics,
    mode: LockMode,
    onDone: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    var attemptsLeft by remember { mutableIntStateOf(ATTEMPTS) }
    var failed by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val enable = mode == LockMode.Enable
    val words = LockWords(getPlatform().biometricName)

    fun scan(allowPasscode: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            val reason = if (enable) "เปิดล็อกแอป mebit" else "ปลดล็อก mebit"
            // A saved wallet's one prompt reads its copy back, so a copy that cannot open shows up while the words can still be written down.
            val readBack = enable && session.saved == SaveState.Saved
            val result = if (readBack) {
                readBackScan(session.proveSavedCopy(reason))
            } else {
                biometrics.authenticate(reason = reason, allowPasscode = allowPasscode)
            }
            busy = false
            when (result) {
                BiometricResult.Success -> {
                    if (enable) session.markAppLockEnabled()
                    onDone()
                }
                BiometricResult.Failed -> {
                    // iOS reports a failure after two non-matching scans, and locks Face ID out after five.
                    attemptsLeft -= 2
                    failed = true
                }
                BiometricResult.LockedOut -> {
                    attemptsLeft = 0
                    failed = true
                }
                BiometricResult.Cancelled -> Unit
                is BiometricResult.Unavailable ->
                    // Nothing can secure the app here, or the saved copy just failed its read-back: onboarding goes on without a lock, unlocking does not.
                    if (goesOnWithoutLock(enable, allowPasscode, readBack, session.saved)) onDone() else unavailable = true
            }
        }
    }

    // The unlock screen asks for Face ID as soon as it shows.
    LaunchedEffect(mode) {
        if (mode == LockMode.Unlock) scan(allowPasscode = false)
    }

    val passcodeOnly = failed && attemptsLeft <= 0
    // Boards 1.3.1 and 1.6 count the create flow's steps and have no back chip. An import has neither.
    val creating = enable && !session.imported
    MebitScreen(
        eyebrow = if (!creating) null else if (failed) words.failedEyebrow else "SECURITY · ขั้น 3 / 3",
        title = when {
            failed -> words.failedTitle
            enable -> words.enableTitle
            else -> "ปลดล็อก mebit"
        },
        subtitle = when {
            failed -> words.failedSubtitle(ATTEMPTS - maxOf(attemptsLeft, 0))
            enable -> "กันคนอื่นหยิบเครื่องคุณไปเปิดแอปหรือส่งเงิน"
            else -> words.unlockSubtitle
        },
        onBack = if (creating) null else onBack,
        onOpenCatalog = onOpenCatalog,
        tag = if (enable) "screen_onboarding/app-lock" else "screen_unlock",
        bottom = {
            BottomActions(
                primaryLabel = when {
                    passcodeOnly -> "ใช้รหัส 6 หลัก"
                    failed -> "ลองอีกครั้ง"
                    enable -> words.enableCta
                    else -> words.unlockCta
                },
                primaryTag = if (enable) "cta_enable_lock" else "cta_unlock",
                onPrimary = { scan(allowPasscode = passcodeOnly) },
                linkLabel = "ใช้รหัส 6 หลักแทน",
                linkTag = if (enable) "cta_skip_lock" else "cta_unlock_passcode",
                onLink = { scan(allowPasscode = true) },
                enabled = !busy,
            )
        },
    ) {
        Spacer(modifier = Modifier.height(65.dp))
        FaceIdTile(failed = failed, label = words.tileLabel)
        Spacer(modifier = Modifier.height(50.dp))
        when {
            failed -> FailedBanner(attemptsLeft = maxOf(attemptsLeft, 0))
            unavailable -> Text(
                text = words.unavailable(enable),
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp,
                modifier = Modifier.testTag("lock_unavailable"),
            )
            enable -> LockScope()
        }
    }
}

@Composable
internal fun FaceIdTile(failed: Boolean, label: String) {
    val shape = RoundedCornerShape(36.dp)
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(132.dp)
                .dropShadow(shape, Shadow(radius = 40.dp, color = MbNavy, spread = (-18).dp, offset = DpOffset(0.dp, 22.dp), alpha = 0.55f))
                .clip(shape)
                .background(MbNavy)
                .then(if (failed) Modifier.border(3.dp, MbEyebrow, shape) else Modifier)
                .testTag("face_id_tile"),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(Res.drawable.face_id), contentDescription = label, modifier = Modifier.size(64.dp))
        }
    }
}

@Composable
internal fun FailedBanner(attemptsLeft: Int) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MbWhite)
            .border(1.dp, Color(0xFFDFDFDF), shape)
            .height(96.dp)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp)
            .testTag("lock_failed"),
    ) {
        Text("FACE ID · FAILED", color = Color(0xFFF8981C), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "ยังไม่ปลดล็อกแอป คุณลองได้อีก $attemptsLeft ครั้ง ก่อนต้องใช้รหัส 6 หลัก",
            color = Color(0xFF1E1E1E),
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun LockScope() {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp)
            .clip(shape)
            .background(MbWhite)
            .border(1.dp, MbBorder, shape)
            .padding(start = 16.dp, end = 16.dp, top = 9.dp),
    ) {
        ScopeRow("ทุกครั้งที่เปิดแอป", "ปิดแอปเกิน 1 นาทีต้องสแกนใหม่")
        Box(modifier = Modifier.padding(start = 38.dp, top = 8.dp, bottom = 7.dp).fillMaxWidth().height(1.dp).background(MbBorder))
        ScopeRow("ทุกครั้งที่เซ็นส่งเงิน", "ถึงแอปเปิดอยู่ก็ต้องสแกนก่อนเซ็น")
    }
}

@Composable
private fun ScopeRow(title: String, meta: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.padding(top = 6.dp).size(26.dp).clip(CircleShape).background(MbGreenWash)) {
            Image(painterResource(Res.drawable.ic_check), contentDescription = null, modifier = Modifier.size(26.dp))
        }
        Column {
            Text(title, color = MbNavyInk, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
            Text(meta, color = MbSlate, fontSize = 12.5.sp)
        }
    }
}
