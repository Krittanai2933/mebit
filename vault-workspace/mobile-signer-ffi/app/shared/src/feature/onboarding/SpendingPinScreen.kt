package com.mebit.app.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.data.OnboardingSession
import com.mebit.app.data.PIN_LENGTH
import com.mebit.app.data.PinAsk
import com.mebit.app.data.PinEntry
import com.mebit.app.data.PinProblem
import com.mebit.app.data.PinStep
import com.mebit.app.data.SpendingPin
import com.mebit.app.hideWhen
import com.mebit.app.rememberCaptureGuard
import com.mebit.app.ui.components.MebitScreen
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbBorder
import com.mebit.app.ui.theme.MbChip
import com.mebit.app.ui.theme.MbEyebrow
import com.mebit.app.ui.theme.MbNavy
import com.mebit.app.ui.theme.MbNavyInk
import com.mebit.app.ui.theme.MbPage
import com.mebit.app.ui.theme.MbPick
import com.mebit.app.ui.theme.MbSlate
import com.mebit.app.ui.theme.MbWhite
import kotlin.time.Duration
import kotlinx.coroutines.delay

// Drafted copy with no Penpot board (open item 79), the user's draft lines and the common-PIN, mismatch and erase lines.
private const val SET_TITLE = "ตั้ง PIN 6 หลักสำหรับส่งเงิน"
private const val REPEAT_TITLE = "ใส่ PIN อีกครั้ง"
private const val FORGOT = "ลืม PIN? ปิดแอปแล้วนำเข้า 24 คำอีกครั้ง"

/** The line under the dots for [problem]. */
internal fun pinProblemText(problem: PinProblem): String = when (problem) {
    PinProblem.Wrong -> "PIN ไม่ถูกต้อง"
    PinProblem.Common -> "PIN นี้เดาง่ายเกินไป ลองตัวเลขชุดอื่น"
    PinProblem.Mismatch -> "PIN ไม่ตรงกัน ตั้งใหม่อีกครั้ง"
}

/** How long until the pad takes digits again, in seconds under a minute and in whole minutes from a minute up, each rounded up. */
internal fun pinWaitText(left: Duration): String {
    val seconds = (left.inWholeMilliseconds + 999) / 1000
    return if (seconds < 60) "ลองใหม่ได้ในอีก $seconds วินาที" else "ลองใหม่ได้ในอีก ${(seconds + 59) / 60} นาที"
}

/**
 * The Spending PIN step of onboarding. A phone with no owner check sets its PIN here, typed twice, where App Lock would be and
 * before Home (open item 79). No Penpot board yet. The create flow shows step 3 of 3 and no back chip, as App Lock does.
 */
@Composable
fun SpendingPinScreen(
    session: OnboardingSession,
    onDone: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    // With a PIN already set, which only the catalog reaches, this is a change and asks the current PIN first.
    val entry = remember(session) { PinEntry(session.pin, change = true) }
    val creating = !session.imported
    MebitScreen(
        eyebrow = if (creating) "SECURITY · ขั้น 3 / 3" else null,
        title = stepTitle(entry.step, checkTitle = "เปลี่ยน PIN"),
        subtitle = "กันคนอื่นหยิบเครื่องคุณไปเปิดแอปหรือส่งเงิน",
        onBack = if (creating) null else onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_onboarding/spending-pin",
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        PinPad(entry, session.pin, onDigit = { if (entry.type(it)) onDone() })
    }
}

/**
 * The PIN sheet a gate opens ([com.mebit.app.data.OwnerCheck.asking]) over the screen it guards. It covers the screen and swallows
 * its taps until the PIN passes or the user cancels, and the 60-second lock's sheet has no cancel.
 */
@Composable
fun PinSheet(ask: PinAsk, pin: SpendingPin) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MbNavyInk.copy(alpha = 0.45f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .testTag("pin_sheet"),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(MbPage)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 12.dp),
        ) {
            Text(
                stepTitle(ask.entry.step, checkTitle = ask.title),
                color = MbNavyInk,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 26.sp,
                modifier = Modifier.testTag("pin_title"),
            )
            Spacer(modifier = Modifier.height(20.dp))
            PinPad(ask.entry, pin, onDigit = ask::type)
            if (ask.cancellable) {
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(29.dp)
                        .clickable(role = Role.Button, onClick = ask::cancel)
                        .testTag("pin_cancel"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("ยกเลิก", color = MbBody, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (ask.entry.step == PinStep.Check) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    FORGOT,
                    color = MbBody,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().testTag("pin_forgot"),
                )
            }
        }
    }
}

private fun stepTitle(step: PinStep, checkTitle: String) = when (step) {
    PinStep.Check -> checkTitle
    PinStep.New -> SET_TITLE
    PinStep.Repeat -> REPEAT_TITLE
}

/** The dots, the line under them and the keypad, behind the capture guard of the words screens. */
@Composable
private fun PinPad(entry: PinEntry, pin: SpendingPin, onDigit: (Char) -> Unit) {
    val guard = rememberCaptureGuard()
    // The wait runs on the monotonic clock, not in Compose state, so the pad reads it a few times a second.
    var left by remember { mutableStateOf(pin.waitLeft()) }
    LaunchedEffect(pin) {
        while (true) {
            left = pin.waitLeft()
            delay(200)
        }
    }
    val waiting = entry.step == PinStep.Check && left > Duration.ZERO
    Column(modifier = Modifier.fillMaxWidth().hideWhen(guard), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.semantics { contentDescription = "${entry.typed} / $PIN_LENGTH" }.testTag("pin_dots"),
        ) {
            repeat(PIN_LENGTH) { i ->
                val filled = i < entry.typed
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(if (filled) MbNavy else MbWhite)
                        .border(1.5.dp, if (filled) MbNavy else MbPick, CircleShape),
                )
            }
        }
        // Two lines' room, so the keypad stays where it is whatever they say.
        Column(modifier = Modifier.height(48.dp).padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            entry.problem?.let { PadLine(pinProblemText(it), "pin_" + it.name.lowercase()) }
            if (waiting) PadLine(pinWaitText(left), "pin_wait")
        }
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("123", "456", "789").forEach { row ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { digit -> PadKey(digit.toString(), "pin_key_$digit", enabled = !waiting) { onDigit(digit) } }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Spacer(modifier = Modifier.weight(1f))
                PadKey("0", "pin_key_0", enabled = !waiting) { onDigit('0') }
                PadKey("ลบ", "pin_erase", enabled = true, onClick = entry::erase)
            }
        }
    }
}

@Composable
private fun PadLine(text: String, tag: String) {
    Text(text, color = MbEyebrow, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp, modifier = Modifier.testTag(tag))
}

@Composable
private fun RowScope.PadKey(label: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .weight(1f)
            .height(56.dp)
            .clip(shape)
            .background(if (enabled) MbWhite else MbChip)
            .border(1.dp, MbBorder, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) MbNavyInk else MbSlate,
            fontSize = if (label.length == 1) 24.sp else 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
