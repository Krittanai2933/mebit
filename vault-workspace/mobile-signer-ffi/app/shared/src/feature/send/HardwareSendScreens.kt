package com.mebit.app.feature.send

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mebit.app.data.DescribedWallet
import com.mebit.app.data.DevicePayment
import com.mebit.app.data.QuoteCache
import com.mebit.app.data.SendDraft
import com.mebit.app.data.rememberFiatQuote
import com.mebit.app.feature.vault.deviceWord
import com.mebit.app.ui.components.BottomActions
import com.mebit.app.ui.components.IssueBanner
import com.mebit.app.ui.components.PrimaryCta
import com.mebit.app.ui.components.SecondaryCta
import com.mebit.app.ui.theme.MbBody
import com.mebit.ffi.signer.FoundWallet
import com.mebit.ffi.signer.SpendReview
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
    val scope = rememberCoroutineScope()
    val repost = remember { RepostAttempt() }
    val run = remember {
        DeviceSignRun(scope, hw.kind, if (wallet.payment is DevicePayment.Signed) SignStage.Signed else SignStage.Connecting) { codes, steps ->
            wallet.sign(nearest(), codes, steps)
        }
    }
    var posting by remember { mutableStateOf(false) }
    var postFailed by remember { mutableStateOf(false) }
    // Only a drafted payment goes to the device; the debug catalog opens this screen with none.
    LaunchedEffect(Unit) { if (wallet.payment is DevicePayment.Drafted) run.start() }
    // Back waits while the payment or its kept bytes are posted, because leaving would hide one that still goes out.
    val leave = onBack?.let { back ->
        {
            if (!posting && !repost.running) {
                run.cancel()
                back()
            }
        }
    }
    DeviceSignPage(
        run = run,
        word = deviceWord(hw.kind),
        keyName = keyName,
        fingerprint = hw.fingerprint,
        eyebrow = "SIGN · ${hw.name}",
        signedSubtitle = "${deviceWord(hw.kind)} เซ็นแล้ว ตรวจรายการด้านล่างอีกครั้ง ส่งแล้วยกเลิกไม่ได้",
        review = wallet.payment?.review,
        changeTo = hw.name,
        pairingKept = pairingKept,
        tag = "screen_hw/sign",
        onBack = leave,
        onOpenCatalog = onOpenCatalog,
        bottom = {
            Spacer(modifier = Modifier.height(12.dp))
            when {
                run.stage == SignStage.Signed -> PrimaryCta(
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
                run.stage is SignStage.Failed -> {
                    PrimaryCta("ลองอีกครั้ง", "cta_hw_sign_retry", run::start, enabled = wallet.payment is DevicePayment.Drafted)
                    Spacer(modifier = Modifier.height(16.dp))
                    SecondaryCta("ยกเลิกการเซ็น", "cta_hw_sign_cancel", { leave?.invoke() })
                }
                else -> SecondaryCta("ยกเลิกการเซ็น", "cta_hw_sign_cancel", { leave?.invoke() })
            }
            Spacer(modifier = Modifier.height(38.dp))
        },
    ) {
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
