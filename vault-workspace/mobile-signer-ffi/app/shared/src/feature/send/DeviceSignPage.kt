package com.mebit.app.feature.send

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
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
import com.mebit.app.data.ANSWER_REFUSED_LINE
import com.mebit.app.data.AnswerRefused
import com.mebit.app.data.BAD_ANSWER_NOTE
import com.mebit.app.data.CANNOT_REGISTER_NOTE
import com.mebit.app.data.FieldPairingCodes
import com.mebit.app.data.NOT_READY_NOTE
import com.mebit.app.data.REGISTRATION_DECLINED_LINE
import com.mebit.app.data.REGISTRATION_DECLINED_NOTE
import com.mebit.app.data.SEVERAL_DEVICES_LINE
import com.mebit.app.data.SEVERAL_DEVICES_NOTE
import com.mebit.app.data.SeveralDevices
import com.mebit.app.data.cannotRegisterLine
import com.mebit.app.data.fingerprintGroups
import com.mebit.app.data.formatBtc
import com.mebit.app.data.formatBtcCompact
import com.mebit.app.data.notReadyLine
import com.mebit.app.feature.keys.PairingCodeBlock
import com.mebit.app.ui.components.HardwareMark
import com.mebit.app.ui.components.MebitScreen
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
import com.mebit.ffi.signer.JadeRegistration
import com.mebit.ffi.signer.MobileSignerException
import com.mebit.ffi.signer.PairingCodes
import com.mebit.ffi.signer.SignSteps
import com.mebit.ffi.signer.SpendReview
import com.mebit.ffi.signer.WalletKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * How signing on the device went wrong: 5.3.2.1's refusal, 5.3.3.1's lost link, no device to reach, or a device that holds another key, and
 * the states of the approved proposal boards: several devices of the kind in range, a device not ready before it showed anything, an answer
 * mebit refused, a declined registration, and a Jade that cannot register the wallet.
 */
internal enum class DeviceFailure { Rejected, Lost, WrongKey, NotFound, BluetoothOff, Several, NotReady, BadAnswer, RegistrationDeclined, RegistrationRefused }

/** How far a device signature got, which decides what a refusal means: nothing shown yet, the Jade's registration screen, or the payment. */
internal enum class SignStep { Connecting, Registering, Showing }

/** Where Sign 4.5.3 to 4.5.5 and 5.3.2 to 5.7.3 are: reaching the device, the Jade registering the wallet, the device showing the payment, the device has signed it, or what went wrong at [Failed.at]. */
internal sealed interface SignStage {
    data object Connecting : SignStage

    data object Registering : SignStage

    data object OnDevice : SignStage

    data object Signed : SignStage

    data class Failed(val failure: DeviceFailure, val at: SignStep) : SignStage
}

/** One of the steps on the boards: a check, the step now, a step to come, or the step that failed (5.3.2.1's "!"). */
internal enum class StepMark { Done, Current, Pending, Failed }

/**
 * Pure. The marks of the steps at [stage]: connect, then the Jade's registration when [registers], then check on the device and confirm
 * on it. A failure ends the list at the step that failed, as the boards draw: the steps after it are not drawn.
 */
internal fun signMarks(stage: SignStage, registers: Boolean = false): List<StepMark> {
    val total = if (registers) 4 else 3
    val failedAt = (stage as? SignStage.Failed)?.at
    val now = when (stage) {
        SignStage.Connecting -> 0
        SignStage.Registering -> 1
        SignStage.OnDevice -> total - 2
        SignStage.Signed -> total
        is SignStage.Failed -> when (stage.at) {
            SignStep.Connecting -> 0
            SignStep.Registering -> 1
            SignStep.Showing -> total - 1
        }
    }
    if (failedAt != null) return List(now + 1) { if (it < now) StepMark.Done else StepMark.Failed }
    return List(total) { if (it < now) StepMark.Done else if (it == now) StepMark.Current else StepMark.Pending }
}

/** The step labels on the boards, naming the device by [word]; the Jade's registration is the second step while [registers]. */
internal fun signStepLabels(word: String, registers: Boolean): List<String> =
    listOfNotNull("เชื่อมต่อผ่าน Bluetooth", "ลงทะเบียน Wallet บน Jade".takeIf { registers }, "ตรวจรายการบนหน้าจอ $word", "กดยืนยันบนอุปกรณ์")

/**
 * Pure. Which failure [error] is, at step [at]. A device's refusal and a refusal of the device's answer never share a line: Refused is the
 * device saying no, so its step decides, the payment declined while it showed, the registration declined, or not ready before anything
 * showed. A Protocol error before the device showed anything is a device that is not ready, a locked Jade for one. After that Rust refusing
 * the signed PSBT (Protocol), the send refusing Rust's answer ([AnswerRefused]) and the core refusing to finish it
 * ([MobileSignerException]) are the device's answer refused, at any step.
 */
internal fun deviceFailure(error: Throwable, at: SignStep): DeviceFailure = when (error) {
    is BleException.Refused -> when (at) {
        SignStep.Connecting -> DeviceFailure.NotReady
        SignStep.Registering -> DeviceFailure.RegistrationDeclined
        SignStep.Showing -> DeviceFailure.Rejected
    }
    is BleException.Protocol -> if (at == SignStep.Connecting) DeviceFailure.NotReady else DeviceFailure.BadAnswer
    is AnswerRefused, is MobileSignerException -> DeviceFailure.BadAnswer
    is BleException.RegistrationRefused -> DeviceFailure.RegistrationRefused
    is SeveralDevices -> DeviceFailure.Several
    is BleException.WrongKey, is BleException.WrongDevice -> DeviceFailure.WrongKey
    is BleException.NotFound -> DeviceFailure.NotFound
    is BleException.BluetoothOff, is BleException.PermissionDenied, is BleException.Unsupported -> DeviceFailure.BluetoothOff
    else -> DeviceFailure.Lost
}

/**
 * What a failed sign shows: the title, the line under it, the device card's pill, and what takes the place of the details. A [kicker] makes
 * that a card under the English kicker (5.3.2.1, 5.3.3.1), none makes it the warning bar of the proposal boards.
 */
internal class DeviceFailureText(val title: String, val subtitle: String?, val pill: String, val kicker: String?, val line: String)

/**
 * Pure. The boards' words for each failure, naming the device by [word] and the Jade's wallet by [registrationName]: 5.3.2.1's for a refusal
 * and 5.3.3.1's for a lost link, the approved proposal boards' for the five others. No board draws a device of another key, no device in
 * range or Bluetooth off: they take T14's title, 2.3.1's line and 2.3.2's banner, each with 5.3.3.1's pill or 5.3.2.1's.
 */
internal fun deviceFailureText(failure: DeviceFailure, word: String, registrationName: String = ""): DeviceFailureText = when (failure) {
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
    DeviceFailure.Several -> DeviceFailureText("เจออุปกรณ์มากกว่าหนึ่งเครื่อง", SEVERAL_DEVICES_LINE, "2 เครื่อง", null, SEVERAL_DEVICES_NOTE)
    DeviceFailure.NotReady -> DeviceFailureText("$word ยังไม่พร้อม", notReadyLine(word), "ยังไม่พร้อม", null, NOT_READY_NOTE)
    DeviceFailure.BadAnswer -> DeviceFailureText("mebit ไม่นับลายเซ็นนี้", ANSWER_REFUSED_LINE, "ไม่นับ", null, BAD_ANSWER_NOTE)
    DeviceFailure.RegistrationDeclined -> DeviceFailureText("ยังไม่ได้ลงทะเบียน Wallet", REGISTRATION_DECLINED_LINE, "ปฏิเสธ", null, REGISTRATION_DECLINED_NOTE)
    DeviceFailure.RegistrationRefused -> DeviceFailureText("ลงทะเบียนบน Jade ไม่ได้", cannotRegisterLine(registrationName), "ไม่ได้", null, CANNOT_REGISTER_NOTE)
}

/**
 * One run of a device's signature, the state both sign screens draw: the [stage], whether the Jade registers the wallet this run
 * ([registers], from the first [SignSteps.registering]), and a Safe 7's code field ([asked], [code]). [start] runs [run] in [scope], which
 * returns true once the signature counts, and reads a failure with the step it came at. A new [start] cancels the one before it.
 */
internal class DeviceSignRun(
    private val scope: CoroutineScope,
    val kind: WalletKind,
    initial: SignStage = SignStage.Connecting,
    registersAtOpen: Boolean = false,
    private val run: suspend (PairingCodes, SignSteps) -> Boolean = { _, _ -> false },
) {
    var stage: SignStage by mutableStateOf(initial)
        private set
    var registers: Boolean by mutableStateOf(registersAtOpen)
        private set
    var asked: Boolean by mutableStateOf(false)
        private set
    var code: String by mutableStateOf("")

    private var step = SignStep.Connecting
    private var job: Job? = null
    private val codes = FieldPairingCodes(jade = kind == WalletKind.JADE, read = { code }, consume = { code = "" }, onAsked = { asked = true })
    private val steps = object : SignSteps {
        override suspend fun registering() {
            asked = false
            registers = true
            step = SignStep.Registering
            stage = SignStage.Registering
        }

        override suspend fun showing() {
            asked = false
            step = SignStep.Showing
            stage = SignStage.OnDevice
        }
    }

    fun start() {
        job?.cancel()
        stage = SignStage.Connecting
        registers = false
        step = SignStep.Connecting
        code = ""
        asked = false
        job = scope.launch {
            stage = try {
                if (run(codes, steps)) SignStage.Signed else SignStage.Failed(DeviceFailure.Lost, step)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SignStage.Failed(deviceFailure(e, step), step)
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }
}

/** What the Jade registers, with the account path the key's signature is read at: 5.3.2.0's details to match. */
internal class SignRegistration(val info: JadeRegistration, val derivation: String)

/**
 * The page of Penpot 4.5.3 to 4.5.5 for a one-key wallet and 5.3.2 to 5.7.3 for a key of a several-key wallet, drawn from [run]: the
 * device's card, the steps, and under them the details the device must show the same ([review], the change going to [changeTo]), the
 * Jade's registration ([registration]) while it registers, or the failure's card or bar. A Safe 7 that asks for its code gets the field of
 * 2.3.4, or 2.3.6's when this phone kept a pairing it forgot ([pairingKept]). The caller gives the [bottom] buttons and what goes [below].
 */
@Composable
internal fun DeviceSignPage(
    run: DeviceSignRun,
    word: String,
    keyName: String,
    fingerprint: String,
    eyebrow: String,
    signedSubtitle: String,
    review: SpendReview?,
    changeTo: String,
    registration: SignRegistration? = null,
    pairingKept: Boolean = false,
    tag: String,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
    bottom: @Composable ColumnScope.() -> Unit,
    below: @Composable ColumnScope.() -> Unit = {},
) {
    val stage = run.stage
    val failed = stage as? SignStage.Failed
    val failure = failed?.let { deviceFailureText(it.failure, word, registration?.info?.name.orEmpty()) }
    MebitScreen(
        eyebrow = if (failed != null) "SIGN · ล้มเหลว" else eyebrow,
        title = when {
            failure != null -> failure.title
            stage == SignStage.Signed -> "ยืนยันบนอุปกรณ์แล้ว"
            stage == SignStage.Registering -> "ลงทะเบียน Wallet บน $word"
            else -> "ตรวจสอบบนอุปกรณ์"
        },
        subtitle = when {
            failure != null -> failure.subtitle
            stage == SignStage.Signed -> signedSubtitle
            stage == SignStage.Registering -> "ครั้งแรกที่ $word เซ็นให้ Wallet นี้ $word จะขอบันทึก Wallet ไว้ ตรวจชื่อและกุญแจบน $word ให้ตรงกับด้านล่าง แล้วกดยืนยันบนอุปกรณ์"
            else -> "โปรดตรวจยอดเงินและที่อยู่ปลายทางบนหน้าจอ $word ให้ตรงกับรายการด้านล่างทุกตัวอักษร ก่อนกดยืนยันบนอุปกรณ์"
        },
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = tag,
        bottom = bottom,
    ) {
        Spacer(modifier = Modifier.height(26.dp))
        DeviceCard(keyName, fingerprint, stage, failure?.pill)
        if (run.asked && run.kind == WalletKind.TREZOR_SAFE7 && stage == SignStage.Connecting) {
            Spacer(modifier = Modifier.height(16.dp))
            PairingCodeBlock(forgot = pairingKept, code = run.code, onCode = { run.code = it })
        }
        Spacer(modifier = Modifier.height(12.dp))
        val marks = signMarks(stage, run.registers)
        StepsCard(signStepLabels(word, run.registers).take(marks.size), marks)
        Spacer(modifier = Modifier.height(12.dp))
        when {
            failure?.kicker != null -> FailureBanner(failure.kicker, failure.line)
            failure != null -> WarningBar(failure.line)
            stage == SignStage.Registering && registration != null -> RegistrationCard(registration, word)
            review != null -> MatchCard(review, changeTo)
        }
        below()
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

/** The steps, each with its [StepMark]: a green check, the navy number now, a grey number to come, or the orange "!". */
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
                    ).then(
                        when (mark) {
                            StepMark.Pending -> Modifier.border(1.dp, MbBorder, CircleShape)
                            StepMark.Failed -> Modifier.border(1.5.dp, MbEyebrow, CircleShape)
                            else -> Modifier
                        },
                    ),
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

/** 5.3.2.0's card in the place of the details while a Jade registers the wallet: its name, m of n, each signer's fingerprint in the Jade's order, and the derivation. */
@Composable
private fun RegistrationCard(registration: SignRegistration, word: String) {
    val info = registration.info
    val n = info.signers.size
    val shape = RoundedCornerShape(24.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(MbWhite).border(1.dp, MbBorder, shape).padding(horizontal = 16.dp, vertical = 14.dp).testTag("hw_sign_registration")) {
        Text("รายละเอียดที่ต้องตรงกับ $word", color = MbBody, fontSize = 12.sp, fontWeight = FontWeight.Bold, lineHeight = 16.sp)
        Spacer(modifier = Modifier.height(10.dp))
        MatchRow("ชื่อ", info.name, "hw_sign_reg_name")
        Spacer(modifier = Modifier.height(8.dp))
        MatchRow("แบบ", "${info.threshold} of $n · Sorted: Yes", "hw_sign_reg_form")
        info.signers.forEachIndexed { i, fingerprint ->
            Spacer(modifier = Modifier.height(8.dp))
            MatchRow("ผู้เซ็น ${i + 1}/$n", fingerprintGroups(fingerprint), "hw_sign_reg_signer_$i")
        }
        Spacer(modifier = Modifier.height(8.dp))
        MatchRow("Derivation", registration.derivation, "hw_sign_reg_derivation")
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

/** The proposal boards' "Warning banner": 362 by 46, radius 14, an orange triangle and one line of 13 sp semibold. */
@Composable
private fun WarningBar(line: String) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().height(46.dp).clip(shape).background(MbIconWash).padding(horizontal = 16.dp).testTag("hw_sign_failed"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WarningIcon(MbEyebrow)
        Spacer(modifier = Modifier.width(8.dp))
        Text(line, color = MbEyebrow, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp, maxLines = 1, modifier = Modifier.testTag("hw_sign_failed_line"))
    }
}

/** An 18 dp warning triangle with its bar and dot, in [color]. */
@Composable
private fun WarningIcon(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val w = size.width
        val h = size.height
        val triangle = Path().apply {
            moveTo(w * 0.5f, h * 0.14f)
            lineTo(w * 0.95f, h * 0.86f)
            lineTo(w * 0.05f, h * 0.86f)
            close()
        }
        drawPath(triangle, color, style = stroke)
        drawLine(color, Offset(w * 0.5f, h * 0.4f), Offset(w * 0.5f, h * 0.6f), strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(color, radius = 0.9.dp.toPx(), center = Offset(w * 0.5f, h * 0.73f))
    }
}
