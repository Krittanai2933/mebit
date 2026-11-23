package com.mebit.app.feature.send

import androidx.compose.foundation.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.mebit.app.data.KeySigner
import com.mebit.app.data.NOT_SIGNED
import com.mebit.app.data.SavedKey
import com.mebit.app.data.THIS_PHONE_KEY_NAME
import com.mebit.app.data.VaultSend
import com.mebit.app.feature.onboarding.LockWords
import com.mebit.app.feature.vault.vaultKeyRows
import com.mebit.app.getPlatform
import com.mebit.app.resources.Res
import com.mebit.app.resources.ic_sign_phone
import com.mebit.app.ui.components.MebitScreen
import com.mebit.ffi.signer.SpendReview
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

/**
 * Penpot 5.3.1 Sign on iPhone, "ลายเซ็นที่ k จาก m", for the phone key [signer] of the payment [send] holds, then 5.4.1 once it signed and
 * 5.3.1.1's words when the owner check failed. As a device asks on its own screen, the page asks the owner ([confirmSign]) by itself once
 * it is on screen, and signs through [VaultSend.sign], the one way a phone key signs. ดำเนินการต่อ ([onContinue]) returns to 5.2,
 * ลองอีกครั้ง asks again, and Back or ยกเลิกการเซ็น leave. A key the payment no longer needs, or a send another signature holds, returns to 5.2
 * at once ([onBack]), which says why. A [sample] shows one state of the boards with their sample data and does nothing: the debug catalog.
 */
@Composable
fun SignOnPhoneScreen(
    send: VaultSend?,
    signer: KeySigner.Phone?,
    keyring: List<SavedKey>,
    confirmSign: suspend () -> Boolean,
    sample: PhoneSample?,
    onContinue: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    val words = remember { LockWords(getPlatform().biometricName) }
    if (sample != null) {
        PhoneSignPage(sample.state, words, THIS_PHONE_KEY_NAME, "SIGN · ลายเซ็นที่ 1 จาก 2", SAMPLE_REVIEW, SAMPLE_WALLET, onBack, onOpenCatalog, {}, onContinue)
        return
    }
    val payment = send?.payment
    if (send == null || signer == null || payment == null) {
        MebitScreen(eyebrow = "SIGN", title = "ตรวจสอบบนอุปกรณ์", onBack = onBack, onOpenCatalog = onOpenCatalog, tag = PHONE_SIGN_TAG) {
            Text(NO_PAYMENT, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("m3_none"))
        }
        return
    }
    val vault = send.vault
    // k is fixed when the screen opens, as on a device's page.
    val k = remember { send.signedFingerprints.size + 1 }
    var state by remember { mutableStateOf<PhoneSignState>(PhoneSignState.Asking) }
    val scope = rememberCoroutineScope()
    val attempt: () -> Unit = {
        if (!send.needsKey(signer.index) || send.busy != null) {
            onBack?.invoke()
        } else {
            state = PhoneSignState.Asking
            scope.launch {
                // What the owner answered, so a refusal before the check, or a send claimed meanwhile, returns to 5.2 and does not read as one.
                var owner: Boolean? = null
                val signed = send.sign(signer) { confirmSign().also { owner = it } }
                when {
                    signed -> state = PhoneSignState.Signed
                    owner == false -> state = PhoneSignState.OwnerFailed
                    owner == true && send.busy == null -> state = PhoneSignState.NotSigned(send.lastError ?: NOT_SIGNED)
                    else -> onBack?.invoke()
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        // The page is on screen behind the owner's prompt, as a device's screen shows the payment while it asks.
        delay(OWNER_PROMPT_DELAY_MILLIS)
        attempt()
    }
    val keyName = vaultKeyRows(vault, keyring)[signer.index].name
    PhoneSignPage(state, words, keyName, "SIGN · ลายเซ็นที่ $k จาก ${vault.m}", payment.review, vault.name, onBack, onOpenCatalog, attempt, onContinue)
}

/** Where 5.3.1 is: the owner asked, the key signed (5.4.1), the owner check failed (5.3.1.1), or the signature not made, with its Thai line. */
internal sealed interface PhoneSignState {
    data object Asking : PhoneSignState

    data object Signed : PhoneSignState

    data object OwnerFailed : PhoneSignState

    data class NotSigned(val line: String) : PhoneSignState
}

/** The words of 5.3.1 at one state: the title, the line under it, the pill once it failed, and the bar that takes the details' place. */
internal class PhoneSignText(val title: String, val subtitle: String, val pill: String?, val bar: String?)

/** Pure. 5.3.1's, 5.4.1's and 5.3.1.1's words at [state], with the owner check named by [words]: Face ID on iOS, the system's own elsewhere. */
internal fun phoneSignText(state: PhoneSignState, words: LockWords): PhoneSignText = when (state) {
    PhoneSignState.Asking -> PhoneSignText("ตรวจสอบบนอุปกรณ์", "โปรดตรวจยอดเงินและที่อยู่ปลายทางบนหน้าจอนี้ให้ตรงกับรายการด้านล่างทุกตัวอักษร ก่อน${words.signStep}", null, null)
    PhoneSignState.Signed -> PhoneSignText("ยืนยันบนอุปกรณ์แล้ว", SIGNED_SUBTITLE, null, null)
    PhoneSignState.OwnerFailed -> PhoneSignText(words.failedTitle, words.signFailedSubtitle, "ไม่ผ่าน", NOTHING_SIGNED)
    is PhoneSignState.NotSigned -> PhoneSignText("เซ็นไม่สำเร็จ", state.line, "ไม่สำเร็จ", NOTHING_SIGNED)
}

/** Pure. 5.3.1's three steps: prepared, check this screen, then the owner check, the step now as the boards mark it at [state]. */
internal fun phoneSignMarks(state: PhoneSignState): List<StepMark> = when (state) {
    PhoneSignState.Asking -> listOf(StepMark.Done, StepMark.Current, StepMark.Pending)
    PhoneSignState.Signed -> List(3) { StepMark.Done }
    PhoneSignState.OwnerFailed, is PhoneSignState.NotSigned -> listOf(StepMark.Done, StepMark.Done, StepMark.Failed)
}

@Composable
private fun PhoneSignPage(
    state: PhoneSignState,
    words: LockWords,
    keyName: String,
    eyebrow: String,
    review: SpendReview,
    walletName: String,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
) {
    val text = phoneSignText(state, words)
    val signed = state == PhoneSignState.Signed
    SignFrame(
        eyebrow = if (text.bar != null) "SIGN · ล้มเหลว" else eyebrow,
        title = text.title,
        subtitle = text.subtitle,
        tile = { Image(painterResource(Res.drawable.ic_sign_phone), contentDescription = null) },
        keyName = keyName,
        keyLine = "เครื่องนี้ · ${words.tileLabel}",
        pill = signPill(signed, text.pill),
        steps = listOf("เตรียมเซ็นบนเครื่องนี้", "ตรวจรายการบนหน้าจอนี้", words.signStep),
        marks = phoneSignMarks(state),
        tag = PHONE_SIGN_TAG,
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        bottom = { SignButtons(signed, text.bar != null, onRetry, onContinue, { onBack?.invoke() }) },
    ) {
        if (text.bar != null) WarningBar(text.bar) else MatchCard(review, walletName)
    }
}

/**
 * One state of Sign on iPhone with the boards' sample data, for the debug catalog: a screen to compare with its board without a payment.
 * [tag] is the catalog entry's test tag suffix.
 */
enum class PhoneSample(val tag: String, val label: String, internal val state: PhoneSignState) {
    Asking("phone_asking", "5.3.1 Sign on iPhone", PhoneSignState.Asking),
    Confirmed("phone_confirmed", "5.4.1 Sign on iPhone · confirmed", PhoneSignState.Signed),
    OwnerFailed("phone_failed", "5.3.1.1 Face ID failed", PhoneSignState.OwnerFailed),
}

internal const val PHONE_SIGN_TAG = "screen_send/m3_phone"

/** 5.3.1.1's bar: nothing was signed, so nothing left. */
private const val NOTHING_SIGNED = "ยังไม่เซ็น เงินยังไม่ถูกส่ง"

/** Long enough for the page to be drawn behind the owner's prompt. */
private const val OWNER_PROMPT_DELAY_MILLIS = 300L
