package com.mebit.app.feature.send

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.mebit.app.data.OnboardingSession
import com.mebit.app.data.SendDraft
import com.mebit.app.data.SingleSigWallet
import com.mebit.app.ui.components.MebitScreen
import com.mebit.app.ui.components.PrimaryCta
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.mebit.app.data.formatBtc
import com.mebit.app.ui.components.BottomActions
import com.mebit.app.ui.components.IssueBanner
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbBorder
import com.mebit.app.ui.theme.MbNavy
import com.mebit.app.ui.theme.MbNavyInk
import com.mebit.app.ui.theme.MbWhite

@Composable
fun S5ReviewSignScreen(
    wallet: SingleSigWallet,
    session: OnboardingSession,
    draft: SendDraft,
    confirm: suspend () -> Boolean,
    onSigned: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var refused by remember { mutableStateOf(false) }
    // Kept local because Home sets wallet.lastError when its balance fetch fails, so it is stale when this screen opens.
    var failed by remember { mutableStateOf(false) }
    val total = draft.amountSats + draft.feeSats
    MebitScreen(
        eyebrow = "SEND · ตรวจสอบ",
        title = "ตรวจก่อนส่ง",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        subtitle = "ส่งแล้วยกเลิกไม่ได้ เทียบที่อยู่กับคนรับอีกครั้ง",
        tag = "screen_send/s5",
        bottom = {
            BottomActions(
                primaryLabel = when {
                    session.appLockEnabled -> "เซ็นด้วย Face ID"
                    wallet.live -> "เซ็นและส่ง"
                    else -> "เซ็นบนเครื่องนี้ (demo)"
                },
                primaryTag = "cta_s5_sign",
                onPrimary = {
                    scope.launch {
                        failed = false
                        // Face ID first when the app lock is on; nothing is signed on a refusal.
                        refused = !confirm()
                        if (!refused) {
                            failed = !wallet.broadcast(session, draft.amountSats, draft.feeSats, draft.destination)
                            if (!failed) onSigned()
                        }
                    }
                },
                linkLabel = "ยกเลิก",
                linkTag = "cta_s5_cancel",
                onLink = onBack,
                linkColor = MbBody,
            )
        },
    ) {
        Spacer(modifier = Modifier.height(27.dp))
        SendSummary(draft)
        Spacer(modifier = Modifier.height(14.dp))
        val rows = RoundedCornerShape(20.dp)
        Column(
            modifier = Modifier.fillMaxWidth().height(196.dp).clip(rows).background(MbWhite).border(1.dp, MbBorder, rows).padding(start = 16.dp, end = 16.dp, top = 6.dp),
        ) {
            ReviewRow("จาก", "กระเป๋าใช้จ่าย")
            ReviewDivider()
            ReviewRow("ค่าธรรมเนียม", "${thousands(draft.feeSats)} sat")
            ReviewDivider()
            ReviewRow("หักจากกระเป๋ารวม", "${formatBtc(total)} BTC", bold = true)
            ReviewDivider()
            ReviewRow("คงเหลือ", "${formatBtc((wallet.balanceSats - total).coerceAtLeast(0))} BTC")
        }
        reviewIssue(refused, failed)?.let {
            Spacer(modifier = Modifier.height(20.dp))
            IssueBanner("ส่งไม่สำเร็จ", it.message, it.tag)
        }
        if (total > wallet.balanceSats) {
            Spacer(modifier = Modifier.height(12.dp))
            Text("ยอดไม่พอ", color = MaterialTheme.colorScheme.error)
        }
    }
}

internal enum class ReviewIssue(val message: String, val tag: String) {
    SignRefused("ยืนยันตัวตนไม่สำเร็จ ลองอีกครั้ง · ยังไม่มีอะไรถูกเซ็นหรือส่ง", "sign_refused"),
    BroadcastFailed("ลองอีกครั้ง · ตรวจยอดคงเหลือก่อนส่งซ้ำ", "error"),
}

/** A refusal wins over a failed broadcast, because nothing was signed. */
internal fun reviewIssue(refused: Boolean, failed: Boolean): ReviewIssue? = when {
    refused -> ReviewIssue.SignRefused
    failed -> ReviewIssue.BroadcastFailed
    else -> null
}

/** Penpot 4.5: what leaves, in the first 4 decimals, and where to. The baht line is left out (no price feed). */
@Composable
private fun SendSummary(draft: SendDraft) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(168.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MbNavy)
            .padding(horizontal = 22.dp)
            .testTag("send_summary"),
    ) {
        Spacer(modifier = Modifier.height(20.dp))
        Text("ส่ง", color = MbWhite.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, lineHeight = 16.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(formatBtc(draft.amountSats, 4), color = MbWhite, fontSize = 38.sp, fontWeight = FontWeight.Bold, lineHeight = 50.sp, modifier = Modifier.testTag("send_amount"))
            Spacer(modifier = Modifier.width(8.dp))
            Text("BTC", color = MbWhite.copy(alpha = 0.8f), fontSize = 15.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp, modifier = Modifier.padding(bottom = 5.dp))
        }
        // The board's baht line sits here: no price feed, so the room stays.
        Spacer(modifier = Modifier.height(34.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ถึง", color = MbWhite.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, lineHeight = 16.sp)
            Spacer(modifier = Modifier.width(18.dp))
            Text(groupedAddress(draft.destination), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp, modifier = Modifier.testTag("send_to"))
        }
    }
}

private val AddressGold = Color(0xFFEEB42A)

/** "tb1q 9h7a 2kxv 7tq3 … 4e8s m4kz": four-letter groups from each end, the middle left out, the ends picked out in gold. */
internal fun groupedAddress(address: String): AnnotatedString = buildAnnotatedString {
    val long = address.length > 24
    val head = (if (long) address.take(16) else address).chunked(4)
    val tail = if (long) address.takeLast(8).chunked(4) else emptyList()
    fun group(text: String, gold: Boolean) = withStyle(SpanStyle(color = if (gold) AddressGold else Color.White)) { append(text) }
    head.forEachIndexed { i, g ->
        if (i > 0) append(' ')
        group(g, gold = i in 1..2)
    }
    if (long) {
        group(" … ", gold = false)
        tail.forEachIndexed { i, g ->
            if (i > 0) append(' ')
            group(g, gold = true)
        }
    }
}

@Composable
private fun ReviewRow(label: String, value: String, bold: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().height(45.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MbBody, fontSize = 13.5.sp, lineHeight = 19.sp)
        Text(value, color = MbNavyInk, fontSize = 14.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold, lineHeight = 19.sp)
    }
}

@Composable
private fun ReviewDivider() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MbBorder))
}

@Composable
fun S6SentScreen(
    wallet: SingleSigWallet,
    draft: SendDraft,
    variant: String,
    onHome: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    if (variant == "slip") {
        SentSlip(wallet, draft, onHome, onOpenCatalog)
        return
    }
    val eyebrow = when (variant) {
        "slip" -> "SENT · SLIP"
        "block" -> "SENT · BLOCK"
        "poster" -> "SENT · POSTER"
        else -> "SENT"
    }
    MebitScreen(
        eyebrow = eyebrow,
        title = "ส่งสำเร็จ",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        subtitle = "เหรียญออกจากกระเป๋าของคุณแล้ว",
        tag = "screen_send/s6_$variant",
    ) {
        Text("txid · ${wallet.lastTxid}", modifier = Modifier.testTag("s6_txid"))
        Text("${draft.amountSats} sats → ${draft.destination.take(20)}…")
        Spacer(modifier = Modifier.height(12.dp))
        Text("รูปแบบแชร์ · $variant", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(20.dp))
        PrimaryCta("กลับหน้า single-sig", "cta_s6_home", onHome)
    }
}
