package com.mebit.app.feature.send

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.data.DescribedWallet
import com.mebit.app.data.DevicePayment
import com.mebit.app.data.FieldPairingCodes
import com.mebit.app.data.QuoteCache
import com.mebit.app.data.SendDraft
import com.mebit.app.data.fingerprintGroups
import com.mebit.app.data.formatBtc
import com.mebit.app.data.formatBtcCompact
import com.mebit.app.data.rememberFiatQuote
import com.mebit.app.feature.keys.PairingCodeBlock
import com.mebit.app.feature.vault.deviceWord
import com.mebit.app.ui.components.BottomActions
import com.mebit.app.ui.components.HardwareMark
import com.mebit.app.ui.components.IssueBanner
import com.mebit.app.ui.components.MebitScreen
import com.mebit.app.ui.components.PrimaryCta
import com.mebit.app.ui.components.SecondaryCta
import com.mebit.app.ui.theme.MbAmberInk
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbBorder
import com.mebit.app.ui.theme.MbChip
import com.mebit.app.ui.theme.MbEyebrow
import com.mebit.app.ui.theme.MbGreenWash
import com.mebit.app.ui.theme.MbIconWash
import com.mebit.app.ui.theme.MbNavy
import com.mebit.app.ui.theme.MbNavyInk
import com.mebit.app.ui.theme.MbSlate
import com.mebit.app.ui.theme.MbSuccess
import com.mebit.app.ui.theme.MbWhite
import com.mebit.ffi.signer.BleException
import com.mebit.ffi.signer.FoundWallet
import com.mebit.ffi.signer.MobileSignerException
import com.mebit.ffi.signer.SignSteps
import com.mebit.ffi.signer.SpendReview
import com.mebit.ffi.signer.WalletKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Pure. What a drafted payment pays, all its outputs to others: the amount Review, Sign and the device show. */
internal fun SpendReview.paidSats(): Long = payments.sumOf { it.sats.toLong() }

/**
 * Penpot 4.5.2 Review & Sign of a Jade or Safe 7 one-key wallet: Review 4.5 over the payment wallet_draft builds as Review opens, with
 * "เซ็นบน Jade" or "เซ็นบน Trezor" in place of Face ID. Nothing is signed here: the button opens Sign ([onSign]), which takes the draft
 * to the device. A draft that is refused, or a payment that may be out, says why where Review 4.5 says it, and the button stays off.
 */
@Composable
fun HardwareReviewScreen(
    wallet: DescribedWallet,
    draft: SendDraft,
    quotes: QuoteCache,
    onSign: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    val quote = rememberFiatQuote(quotes)
    var retries by remember { mutableIntStateOf(0) }
    var drafting by remember { mutableStateOf(true) }
    val request = draft.request
    LaunchedEffect(request, retries) {
        drafting = true
        wallet.draft(request.destination, if (request.sendAll) null else request.amountSats, request.rateSatVb)
        drafting = false
    }
    val review = wallet.payment?.review
    // The draft Review built goes with it, so a Sign it never reached cannot sign it later.
    val leave = onBack?.let { back ->
        {
            wallet.dropPayment()
            back()
        }
    }
    ReviewPage(
        tag = "screen_hw/review",
        onBack = leave,
        onOpenCatalog = onOpenCatalog,
        amountSats = review?.paidSats() ?: draft.amountSats,
        sendAll = draft.isSendAll,
        // Send all before its draft: the Send screen's one-coin estimate, which the draft replaces with what leaves. Open item 76.
        estimate = draft.isSendAll && review == null,
        destination = draft.destination,
        from = wallet.wallet.name,
        fee = if (drafting) "กำลังคำนวณ…" else review?.let { realFeeText(it.feeSats.toLong(), quote) } ?: "—",
        totalSats = review?.let { it.paidSats() + it.feeSats.toLong() },
        balanceSats = wallet.balanceSats.takeIf { wallet.balanceKnown },
        quote = quote,
        bottom = {
            BottomActions(
                primaryLabel = "เซ็นบน ${deviceWord(wallet.wallet.kind)}",
                primaryTag = "cta_hw_review_sign",
                onPrimary = onSign,
                linkLabel = "ยกเลิก",
                linkTag = "cta_hw_review_cancel",
                onLink = leave,
                enabled = !drafting && wallet.payment is DevicePayment.Drafted && wallet.unconfirmedSend == null,
                linkColor = MbBody,
            )
        },
    ) {
        val notDrafted = if (!drafting && review == null) wallet.lastError else null
        reviewIssue(refused = false, failed = false, unconfirmedSend = wallet.unconfirmedSend != null, notQuoted = notDrafted)?.let {
            Spacer(modifier = Modifier.height(20.dp))
            IssueBanner(it.title, it.message, it.tag)
            if (it.retry) {
                Spacer(modifier = Modifier.height(6.dp))
                LinkRow("ลองอีกครั้ง", "cta_hw_review_redraft") { retries++ }
            }
        }
    }
}

/** How signing on the device went wrong: 5.3.2.1's refusal, 5.3.3.1's lost link, or no device to reach, or a device that holds another key. */
internal enum class DeviceFailure { Rejected, Lost, WrongKey, NotFound, BluetoothOff }

/** Where Sign 4.5.3 to 4.5.5 is: reaching the device, the device showing the payment, the device has signed it, or what went wrong. */
internal sealed interface SignStage {
    data object Connecting : SignStage

    data object OnDevice : SignStage

    data object Signed : SignStage

    /** [shown] is whether the device had shown the payment, which says whether the reach failed or the confirm. */
    data class Failed(val failure: DeviceFailure, val shown: Boolean) : SignStage
}

/** One of the three steps on the boards: a check, the step now, a step to come, or the step that failed (5.3.2.1's "!"). */
internal enum class StepMark { Done, Current, Pending, Failed }

/** Pure. The marks of 4.5.3's three steps, connect, check on the device and confirm on it, at [stage]. */
internal fun signMarks(stage: SignStage): List<StepMark> = when (stage) {
    SignStage.Connecting -> listOf(StepMark.Current, StepMark.Pending, StepMark.Pending)
    SignStage.OnDevice -> listOf(StepMark.Done, StepMark.Current, StepMark.Pending)
    SignStage.Signed -> listOf(StepMark.Done, StepMark.Done, StepMark.Done)
    is SignStage.Failed -> if (stage.shown) listOf(StepMark.Done, StepMark.Done, StepMark.Failed) else listOf(StepMark.Failed, StepMark.Pending, StepMark.Pending)
}

/** Pure. Which failure [error] is. The core refusing what the device sent back keeps no signature, as a refusal on the device does. */
internal fun deviceFailure(error: Throwable): DeviceFailure = when (error) {
    is BleException.Refused, is MobileSignerException -> DeviceFailure.Rejected
    is BleException.WrongKey, is BleException.WrongDevice -> DeviceFailure.WrongKey
    is BleException.NotFound -> DeviceFailure.NotFound
    is BleException.BluetoothOff, is BleException.PermissionDenied, is BleException.Unsupported -> DeviceFailure.BluetoothOff
    else -> DeviceFailure.Lost
}

/** What a failed sign shows: the title, the line under it, the device card's pill, and the banner in the place of the details. */
internal class DeviceFailureText(val title: String, val subtitle: String?, val pill: String, val kicker: String, val line: String)

/**
 * Pure. 5.3.2.1's words for a refusal and 5.3.3.1's for a lost link, naming the device by [word]. No board draws the other three: a device
 * of another key takes T14's title, no device in range 2.3.1's line, Bluetooth off 2.3.2's banner, each with 5.3.3.1's pill or 5.3.2.1's.
 */
internal fun deviceFailureText(failure: DeviceFailure, word: String): DeviceFailureText = when (failure) {
    DeviceFailure.Rejected -> DeviceFailureText(
        "อุปกรณ์ปฏิเสธการเซ็น",
        "คุณกดปฏิเสธบน $word หรืออุปกรณ์ไม่รับรายการนี้ ไม่มีลายเซ็นถูกบันทึก",
        "ปฏิเสธ",
        "DEVICE REJECTED",
        "$word ปฏิเสธรายการ — ไม่มีลายเซ็นถูกบันทึก เงินยังไม่ถูกส่ง ตรวจรายการแล้วลองอีกครั้งได้",
    )
    DeviceFailure.Lost -> DeviceFailureText(
        "เชื่อมต่ออุปกรณ์หลุด",
        "Bluetooth หลุดระหว่างรอยืนยัน ย้าย $word ให้ใกล้มือถือ แล้วลองใหม่",
        "หลุด",
        "CONNECTION LOST",
        "หมดเวลารอการยืนยันจาก $word — ไม่มีลายเซ็นถูกบันทึก เงินยังไม่ถูกส่ง",
    )
    DeviceFailure.WrongKey -> DeviceFailureText("Fingerprint ไม่ตรง", null, "ปฏิเสธ", "WRONG DEVICE", "ไม่มีลายเซ็นถูกบันทึก เงินยังไม่ถูกส่ง")
    DeviceFailure.NotFound -> DeviceFailureText("เชื่อมต่ออุปกรณ์หลุด", null, "หลุด", "CONNECTION LOST", "ไม่พบ Jade หรือ Trezor Safe 7 ใกล้ ๆ · เปิดอุปกรณ์แล้วกดค้นหาอีกครั้ง")
    DeviceFailure.BluetoothOff -> DeviceFailureText(
        "เชื่อมต่ออุปกรณ์หลุด",
        null,
        "หลุด",
        "BLUETOOTH OFF",
        "มือถือยังมองไม่เห็นอุปกรณ์ เปิด Bluetooth ในตั้งค่า แล้วกลับมาลองใหม่ ไม่มีข้อมูลถูกส่งออก",
    )
}

/**
 * Penpot 4.5.3 Sign on Jade and 4.5.4 Sign on Trezor, then 4.5.5 once the device signed, and 5.3.2.1's and 5.3.3.1's shapes when it did
 * not. It looks for the wallet's device ([nearest]) and has it sign Review's draft as soon as it opens, through
 * [DescribedWallet.sign], which refuses a device of another key before anything shows on it. A Safe 7 that asks for its code gets the field
 * of 2.3.4, or 2.3.6's when this phone kept a pairing it forgot ([pairingKept]). "ส่งธุรกรรม" posts the signed payment, and [onSent] then
 * shows the slip; until then nothing is out, and Back or "ยกเลิกการเซ็น" lets the payment go. A post that ends unknown keeps its guard, and
 * under its banner come the retry of the kept bytes behind [confirmRepost] and the clear, as under a phone wallet's on Review 4.5.
 */
@Composable
fun HardwareSignScreen(
    wallet: DescribedWallet,
    keyName: String,
    nearest: suspend () -> FoundWallet,
    pairingKept: Boolean,
    draft: SendDraft,
    confirmRepost: suspend () -> Boolean,
    onSent: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    val hw = wallet.wallet
    val word = deviceWord(hw.kind)
    val scope = rememberCoroutineScope()
    val repost = remember { RepostAttempt() }
    var stage by remember { mutableStateOf(if (wallet.payment is DevicePayment.Signed) SignStage.Signed else SignStage.Connecting) }
    var job by remember { mutableStateOf<Job?>(null) }
    var code by remember { mutableStateOf("") }
    var asked by remember { mutableStateOf(false) }
    var posting by remember { mutableStateOf(false) }
    var postFailed by remember { mutableStateOf(false) }
    val codes = remember {
        FieldPairingCodes(jade = hw.kind == WalletKind.JADE, read = { code }, consume = { code = "" }, onAsked = { asked = true })
    }
    val steps = remember {
        object : SignSteps {
            override suspend fun registering() {}

            override suspend fun showing() {
                asked = false
                stage = SignStage.OnDevice
            }
        }
    }
    val start: () -> Unit = {
        job?.cancel()
        stage = SignStage.Connecting
        code = ""
        asked = false
        job = scope.launch {
            stage = try {
                val signed = wallet.sign(nearest(), codes, steps)
                if (signed) SignStage.Signed else SignStage.Failed(DeviceFailure.Lost, shown = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SignStage.Failed(deviceFailure(e), shown = stage == SignStage.OnDevice)
            }
        }
    }
    // Only a drafted payment goes to the device; the debug catalog opens this screen with none.
    LaunchedEffect(Unit) { if (wallet.payment is DevicePayment.Drafted) start() }
    val review = wallet.payment?.review
    // Back waits while the payment or its kept bytes are posted, because leaving would hide one that still goes out.
    val leave = onBack?.let { back ->
        {
            if (!posting && !repost.running) {
                job?.cancel()
                back()
            }
        }
    }
    val failed = stage as? SignStage.Failed
    val failure = failed?.let { deviceFailureText(it.failure, word) }
    MebitScreen(
        eyebrow = if (failed != null) "SIGN · ล้มเหลว" else "SIGN · ${hw.name}",
        title = when {
            failure != null -> failure.title
            stage == SignStage.Signed -> "ยืนยันบนอุปกรณ์แล้ว"
            else -> "ตรวจสอบบนอุปกรณ์"
        },
        subtitle = when {
            failure != null -> failure.subtitle
            stage == SignStage.Signed -> "$word เซ็นแล้ว ตรวจรายการด้านล่างอีกครั้ง ส่งแล้วยกเลิกไม่ได้"
            else -> "โปรดตรวจยอดเงินและที่อยู่ปลายทางบนหน้าจอ $word ให้ตรงกับรายการด้านล่างทุกตัวอักษร ก่อนกดยืนยันบนอุปกรณ์"
        },
        onBack = leave,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_hw/sign",
        bottom = {
            Spacer(modifier = Modifier.height(12.dp))
            when {
                stage == SignStage.Signed -> PrimaryCta(
                    if (posting) "กำลังส่ง…" else "ส่งธุรกรรม",
                    "cta_hw_send",
                    {
                        if (!posting) {
                            posting = true
                            postFailed = false
                            scope.launch {
                                val out = wallet.post()
                                posting = false
                                if (out) onSent() else postFailed = true
                            }
                        }
                    },
                    enabled = !posting && wallet.payment is DevicePayment.Signed && wallet.unconfirmedSend == null,
                )
                failed != null -> {
                    PrimaryCta("ลองอีกครั้ง", "cta_hw_sign_retry", start, enabled = wallet.payment is DevicePayment.Drafted)
                    Spacer(modifier = Modifier.height(16.dp))
                    SecondaryCta("ยกเลิกการเซ็น", "cta_hw_sign_cancel", { leave?.invoke() })
                }
                else -> SecondaryCta("ยกเลิกการเซ็น", "cta_hw_sign_cancel", { leave?.invoke() })
            }
            Spacer(modifier = Modifier.height(38.dp))
        },
    ) {
        Spacer(modifier = Modifier.height(26.dp))
        DeviceCard(keyName, hw.fingerprint, stage, failure?.pill)
        if (asked && hw.kind == WalletKind.TREZOR_SAFE7 && stage == SignStage.Connecting) {
            Spacer(modifier = Modifier.height(16.dp))
            PairingCodeBlock(forgot = pairingKept, code = code, onCode = { code = it })
        }
        Spacer(modifier = Modifier.height(12.dp))
        StepsCard(listOf("เชื่อมต่อผ่าน Bluetooth", "ตรวจรายการบนหน้าจอ $word", "กดยืนยันบนอุปกรณ์"), signMarks(stage))
        Spacer(modifier = Modifier.height(12.dp))
        if (failure != null) {
            FailureBanner(failure.kicker, failure.line)
        } else {
            review?.let { MatchCard(it, hw.name) }
        }
        if (postFailed) {
            reviewIssue(refused = false, failed = !repost.wentOut, unconfirmedSend = wallet.unconfirmedSend != null, failure = wallet.lastError)?.let {
                Spacer(modifier = Modifier.height(12.dp))
                IssueBanner(it.title, it.message, it.tag)
            }
        }
        if (!posting) UnconfirmedClearControl(wallet, draft, repost, scope, confirmRepost)
        RepostedNote(repost, wallet)
    }
}

/** 4.5.3's card of the key that signs: the device's tile, the key's name, its fingerprint, and the state pill on the right. */
@Composable
private fun DeviceCard(keyName: String, fingerprint: String, stage: SignStage, failedPill: String?) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier.fillMaxWidth().height(76.dp).clip(shape).background(MbWhite).border(1.dp, MbBorder, shape).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(MbChip), contentAlignment = Alignment.Center) { HardwareMark() }
        Spacer(modifier = Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(keyName, color = MbNavyInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp, maxLines = 1)
            Text("Bluetooth · fingerprint ${fingerprintGroups(fingerprint)}", color = MbSlate, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, modifier = Modifier.testTag("hw_sign_fingerprint"))
        }
        val (label, ink, fill) = when {
            failedPill != null -> Triple(failedPill, MbEyebrow, MbIconWash)
            stage == SignStage.Signed -> Triple("ยืนยันแล้ว", MbSuccess, MbGreenWash)
            else -> Triple("รอยืนยัน", MbNavy, MbChip)
        }
        Row(Modifier.height(26.dp).clip(CircleShape).background(fill).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, lineHeight = 16.sp, modifier = Modifier.testTag("hw_sign_status"))
        }
    }
}

/** The three steps, each with its [StepMark]: a green check, the navy number now, a grey number to come, or the orange "!". */
@Composable
private fun StepsCard(steps: List<String>, marks: List<StepMark>) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(MbWhite).border(1.dp, MbBorder, shape).padding(horizontal = 16.dp, vertical = 10.dp).testTag("hw_sign_steps"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        steps.zip(marks).forEachIndexed { i, (step, mark) ->
            Row(Modifier.height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(22.dp).clip(CircleShape).background(
                        when (mark) {
                            StepMark.Done -> MbGreenWash
                            StepMark.Current -> MbNavy
                            StepMark.Pending -> MbWhite
                            StepMark.Failed -> MbIconWash
                        },
                    ).then(if (mark == StepMark.Pending) Modifier.border(1.dp, MbBorder, CircleShape) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    when (mark) {
                        StepMark.Done -> Check(MbSuccess)
                        StepMark.Failed -> Text("!", color = MbEyebrow, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        else -> Text("${i + 1}", color = if (mark == StepMark.Current) MbWhite else MbSlate, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    step,
                    color = when (mark) {
                        StepMark.Failed -> MbEyebrow
                        StepMark.Pending -> MbSlate
                        else -> MbNavyInk
                    },
                    fontSize = 14.sp,
                    fontWeight = if (mark == StepMark.Current) FontWeight.Bold else FontWeight.Medium,
                    lineHeight = 19.sp,
                )
            }
        }
    }
}

/** A check 9 by 7 dp in [color], for a step that is done. */
@Composable
private fun Check(color: Color) {
    Canvas(Modifier.size(9.dp, 7.dp)) {
        val tick = Path().apply {
            moveTo(0f, size.height * 0.5f)
            lineTo(size.width * 0.35f, size.height)
            lineTo(size.width, 0f)
        }
        drawPath(tick, color, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** 4.5.3's details the device must show the same: the amount, the address whole in groups of four, the change and the fee. */
@Composable
private fun MatchCard(review: SpendReview, walletName: String) {
    val shape = RoundedCornerShape(24.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(MbWhite).border(1.dp, MbBorder, shape).padding(horizontal = 16.dp, vertical = 14.dp).testTag("hw_sign_details")) {
        Text("รายละเอียดที่ต้องตรงกับอุปกรณ์", color = MbBody, fontSize = 12.sp, fontWeight = FontWeight.Bold, lineHeight = 16.sp)
        Spacer(modifier = Modifier.height(10.dp))
        MatchRow("ยอดที่ส่ง", "${formatBtcCompact(review.paidSats())} BTC", "hw_sign_amount")
        Spacer(modifier = Modifier.height(10.dp))
        Text("ที่อยู่ปลายทาง", color = MbBody, fontSize = 13.sp, lineHeight = 18.sp)
        review.payments.forEach { out ->
            Text(
                out.address.chunked(4).joinToString(" "),
                color = MbNavyInk,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                lineHeight = 22.sp,
                modifier = Modifier.padding(top = 4.dp).testTag("hw_sign_address"),
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(MbBorder))
        Spacer(modifier = Modifier.height(10.dp))
        if (review.changeSats > 0u) {
            MatchRow("เงินทอน → $walletName", "${formatBtc(review.changeSats.toLong())} BTC", "hw_sign_change")
            Spacer(modifier = Modifier.height(8.dp))
        }
        MatchRow("ค่าธรรมเนียม", "${thousands(review.feeSats.toLong())} sat", "hw_sign_fee")
    }
}

@Composable
private fun MatchRow(label: String, value: String, tag: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MbBody, fontSize = 13.sp, lineHeight = 18.sp)
        Text(value, color = MbNavyInk, fontSize = 14.sp, fontWeight = FontWeight.Bold, lineHeight = 19.sp, modifier = Modifier.testTag(tag))
    }
}

/** 5.3.2.1's and 5.3.3.1's banner in the place of the details: the English kicker in amber, then the line. */
@Composable
private fun FailureBanner(kicker: String, line: String) {
    val shape = RoundedCornerShape(24.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(MbWhite).border(1.dp, MbBorder, shape).padding(horizontal = 16.dp, vertical = 16.dp).testTag("hw_sign_failed")) {
        Text(kicker, color = MbAmberInk, fontSize = 11.sp, fontWeight = FontWeight.Bold, lineHeight = 14.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(line, color = MbNavyInk, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.testTag("hw_sign_failed_line"))
    }
}
