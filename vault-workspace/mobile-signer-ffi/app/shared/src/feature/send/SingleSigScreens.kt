package com.mebit.app.feature.send

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.mebit.app.data.FiatQuote
import com.mebit.app.feature.onboarding.LockWords
import com.mebit.app.getPlatform
import com.mebit.app.data.OnboardingSession
import com.mebit.app.data.QuoteCache
import com.mebit.app.data.SendDraft
import com.mebit.app.data.SingleSigWallet
import com.mebit.app.data.SingleSigWallet.WalletMode
import com.mebit.app.data.needsConfirmation
import com.mebit.app.data.rememberFiatQuote
import com.mebit.ffi.signer.PreparedSend
import com.mebit.app.ui.components.MebitScreen
import com.mebit.app.ui.components.PrimaryCta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import com.mebit.app.data.formatBtcCompact
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
    quotes: QuoteCache,
    confirm: suspend () -> Boolean,
    onSigned: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val quote = rememberFiatQuote(quotes)
    var refused by remember { mutableStateOf(false) }
    // Kept local because Home sets wallet.lastError when its balance fetch fails, so it is stale when this screen opens.
    var failed by remember { mutableStateOf(false) }
    val attempt = remember { SignAttempt() }
    // Back and cancel wait while a payment is in flight, because leaving would hide one that still goes out. The back chip stays so the header does not shift.
    val leave = onBack?.let { back -> { if (!attempt.running) back() } }
    // A payment held for the fee question is signed and nothing more, so leaving the screen lets it go and none waits here stale.
    DisposableEffect(wallet) { onDispose { wallet.dropHeldSend() } }
    val total = draft.amountSats + draft.feeSats
    val question = wallet.feeQuestion(draft)

    /** One attempt: [step] signs or posts and answers whether the payment is out. A payment held for the fee question is neither out nor failed. */
    fun attemptTo(step: suspend () -> Boolean) {
        if (!attempt.begin()) return
        scope.launch {
            failed = false
            val out = step()
            if (out) onSigned()
            attempt.end(paid = out)
        }
    }
    MebitScreen(
        eyebrow = "SEND · ตรวจสอบ",
        title = "ตรวจก่อนส่ง",
        onBack = leave,
        onOpenCatalog = onOpenCatalog,
        subtitle = "ส่งแล้วยกเลิกไม่ได้ เทียบที่อยู่กับคนรับอีกครั้ง",
        tag = "screen_send/s5",
        bottom = {
            BottomActions(
                primaryLabel = when {
                    attempt.running -> "กำลังส่ง…"
                    session.appLockEnabled -> LockWords(getPlatform().biometricName).signCta
                    wallet.mode == WalletMode.Demo -> "เซ็นบนเครื่องนี้ (demo)"
                    else -> "เซ็นและส่ง"
                },
                primaryTag = "cta_s5_sign",
                onPrimary = {
                    attemptTo {
                        // Face ID first when the app lock is on; nothing is signed on a refusal.
                        refused = !confirm()
                        if (refused) return@attemptTo false
                        failed = !withContext(Dispatchers.Default) { wallet.prepare(session, draft.amountSats, draft.rateSatVb, draft.destination, sendAll = draft.isSendAll) }
                        if (failed) return@attemptTo false
                        // A fee far above the estimate waits for the card below, which posts or lets the payment go. The demo mock held nothing and is out.
                        if (wallet.feeQuestion(draft) != null) return@attemptTo false
                        if (wallet.heldSend == null) return@attemptTo true
                        failed = !withContext(Dispatchers.Default) { wallet.post(session) }
                        !failed
                    }
                },
                linkLabel = "ยกเลิก",
                linkTag = "cta_s5_cancel",
                onLink = leave,
                enabled = !attempt.running && wallet.balanceKnown && wallet.unconfirmedSend == null && wallet.heldSend == null,
                linkColor = MbBody,
            )
        },
    ) {
        Spacer(modifier = Modifier.height(27.dp))
        SendSummary(draft, quote)
        Spacer(modifier = Modifier.height(14.dp))
        val rows = RoundedCornerShape(20.dp)
        Column(
            modifier = Modifier.fillMaxWidth().height(196.dp).clip(rows).background(MbWhite).border(1.dp, MbBorder, rows).padding(start = 16.dp, end = 16.dp, top = 6.dp),
        ) {
            ReviewRow("จาก", "กระเป๋าใช้จ่าย")
            ReviewDivider()
            ReviewRow("ค่าธรรมเนียม", reviewFeeText(draft.feeSats, quote))
            ReviewDivider()
            ReviewRow("หักจากกระเป๋ารวม", "${formatBtcCompact(total)} BTC", bold = true)
            ReviewDivider()
            ReviewRow("คงเหลือ", "${formatBtcCompact((wallet.balanceSats - total).coerceAtLeast(0))} BTC")
        }
        if (question != null) {
            HighFeeConfirm(
                question,
                sendAll = draft.isSendAll,
                quote = quote,
                onConfirm = {
                    attemptTo {
                        failed = !withContext(Dispatchers.Default) { wallet.post(session) }
                        !failed
                    }
                },
                onCancel = { wallet.dropHeldSend() },
            )
        }
        reviewIssue(refused, failed, wallet.unconfirmedSend != null)?.let {
            Spacer(modifier = Modifier.height(20.dp))
            IssueBanner(it.title, it.message, it.tag)
        }
        UnconfirmedClearControl(wallet, session)
        if (total > wallet.spendableSats) {
            Spacer(modifier = Modifier.height(12.dp))
            Text("ยอดไม่พอ", color = MaterialTheme.colorScheme.error)
        }
    }
}

internal enum class ReviewIssue(val title: String, val message: String, val tag: String) {
    SignRefused("ส่งไม่สำเร็จ", "ยืนยันตัวตนไม่สำเร็จ ลองอีกครั้ง · ยังไม่มีอะไรถูกเซ็นหรือส่ง", "sign_refused"),
    BroadcastFailed("ส่งไม่สำเร็จ", "ลองอีกครั้ง · ตรวจยอดคงเหลือก่อนส่งซ้ำ", "error"),
    // Not "failed", because the payment may be out.
    BroadcastUnknown("ยังไม่ทราบผลการส่ง", "ไม่ทราบว่าการส่งล่าสุดออกไปแล้วหรือยัง อย่าส่งซ้ำ · เปิดหน้าแรกเพื่อตรวจยอดคงเหลือและรายการ", "send_unknown"),
}

/**
 * One signing attempt at a time on Review. A tap while one runs, or after one paid, starts nothing, so a double tap
 * cannot pay twice. A refusal or a failed broadcast frees the button for another try.
 */
internal class SignAttempt {
    var running by mutableStateOf(false)
        private set

    /** True when the caller may start an attempt now. */
    fun begin(): Boolean {
        if (running) return false
        running = true
        return true
    }

    /** [paid] keeps the button locked: the screen is left a moment later and a queued tap must not pay again. */
    fun end(paid: Boolean) {
        running = paid
    }
}

/** The held payment when it needs the user's word before the post ([needsConfirmation] against what the screen showed), else null. */
private fun SingleSigWallet.feeQuestion(draft: SendDraft): PreparedSend? =
    heldSend?.prepared?.takeIf { needsConfirmation(it, draft) }

/**
 * The question the Review screen asks when the signed payment's fee is far above the estimate it showed: the real fee, what leaves and
 * the total, a confirm that posts and a cancel that lets the payment go, nothing posted and no guard written until the confirm. The
 * shape of the clear control in UnconfirmedClear.kt. There is no board; the copy is a default awaiting design review (docs/04-open-items.md item 77).
 */
@Composable
private fun HighFeeConfirm(held: PreparedSend, sendAll: Boolean, quote: FiatQuote?, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val feeSats = held.feeSats.toLong()
    val amountSats = held.amountSats.toLong()
    Spacer(modifier = Modifier.height(12.dp))
    val shape = RoundedCornerShape(16.dp)
    Column(modifier = Modifier.fillMaxWidth().clip(shape).background(MbWhite).border(1.dp, MbBorder, shape).padding(16.dp).testTag("high_fee")) {
        Text("ค่าธรรมเนียมจริงสูงกว่าที่แสดง", color = MbNavyInk, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, lineHeight = 18.sp)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            if (sendAll) "กระเป๋านี้ต้องรวมหลายเหรียญ ธุรกรรมจึงใหญ่กว่าปกติ จำนวนที่ส่งลดลงจากที่แสดงเพราะหักค่าธรรมเนียมจริงแล้ว" else "กระเป๋านี้ต้องรวมหลายเหรียญ ธุรกรรมจึงใหญ่กว่าปกติ ตรวจค่าธรรมเนียมก่อนส่ง",
            color = MbNavyInk,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        Spacer(modifier = Modifier.height(6.dp))
        ReviewRow("ค่าธรรมเนียมจริง", realFeeText(feeSats, quote), tag = "high_fee_fee")
        ReviewDivider()
        ReviewRow(if (sendAll) "ส่งจริง" else "ส่ง", "${formatBtcCompact(amountSats)} BTC", tag = "high_fee_amount")
        ReviewDivider()
        ReviewRow("หักจากกระเป๋ารวม", "${formatBtcCompact(amountSats + feeSats)} BTC", bold = true, tag = "high_fee_total")
        Spacer(modifier = Modifier.height(12.dp))
        PrimaryCta("ส่งด้วยค่าธรรมเนียมนี้", "high_fee_confirm", onConfirm, height = 44.dp)
        Spacer(modifier = Modifier.height(4.dp))
        LinkRow("ยกเลิก", "high_fee_cancel", color = MbBody, onClick = onCancel)
    }
}

/** A payment that may be out ([unconfirmedSend]) wins over everything, because sending again could pay twice. A refusal wins over a failed broadcast, because nothing was signed. */
internal fun reviewIssue(refused: Boolean, failed: Boolean, unconfirmedSend: Boolean = false): ReviewIssue? = when {
    unconfirmedSend -> ReviewIssue.BroadcastUnknown
    refused -> ReviewIssue.SignRefused
    failed -> ReviewIssue.BroadcastFailed
    else -> null
}

/** Penpot 4.5: what leaves, in the first 4 decimals, its baht when there is a price, and where to. */
@Composable
private fun SendSummary(draft: SendDraft, quote: FiatQuote?) {
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
        // Send all: the core spends every coin and takes the real fee off the amount, so the amount here is the one-coin estimate. Open item 76.
        Text(if (draft.isSendAll) "ส่งทั้งหมด · จำนวนโดยประมาณ" else "ส่ง", color = MbWhite.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, lineHeight = 16.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text((if (draft.isSendAll) "≈ " else "") + formatBtcCompact(draft.amountSats), color = MbWhite, fontSize = 38.sp, fontWeight = FontWeight.Bold, lineHeight = 50.sp, modifier = Modifier.testTag("send_amount"))
            Spacer(modifier = Modifier.width(8.dp))
            Text("BTC", color = MbWhite.copy(alpha = 0.8f), fontSize = 15.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp, modifier = Modifier.padding(bottom = 5.dp))
        }
        // The room stays when there is no price, so the card keeps its height.
        Box(modifier = Modifier.fillMaxWidth().height(34.dp)) {
            fiatLine(draft.amountSats, quote)?.let {
                Text(it, color = SummaryGold, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp, modifier = Modifier.testTag("send_fiat"))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ถึง", color = MbWhite.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, lineHeight = 16.sp)
            Spacer(modifier = Modifier.width(18.dp))
            Text(groupedAddress(draft.destination), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp, modifier = Modifier.testTag("send_to"))
        }
    }
}

private val SummaryGold = Color(0xFFEEB42A)

/** "tb1q 9h7a 2kxv 7tq3 … 4e8s m4kz": four-letter groups from each end, the middle left out, the ends picked out in gold. */
internal fun groupedAddress(address: String): AnnotatedString = buildAnnotatedString {
    val long = address.length > 24
    val head = (if (long) address.take(16) else address).chunked(4)
    val tail = if (long) address.takeLast(8).chunked(4) else emptyList()
    fun group(text: String, gold: Boolean) = withStyle(SpanStyle(color = if (gold) SummaryGold else Color.White)) { append(text) }
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
internal fun ReviewRow(label: String, value: String, bold: Boolean = false, tag: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().height(45.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MbBody, fontSize = 13.5.sp, lineHeight = 19.sp)
        Text(value, color = MbNavyInk, fontSize = 14.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold, lineHeight = 19.sp, modifier = tag?.let { Modifier.testTag(it) } ?: Modifier)
    }
}

@Composable
internal fun ReviewDivider() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MbBorder))
}

@Composable
fun S6SentScreen(
    wallet: SingleSigWallet,
    draft: SendDraft,
    quotes: QuoteCache,
    variant: String,
    onHome: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    if (variant == "slip") {
        SentSlip(wallet, draft, quotes, onHome, onOpenCatalog)
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
