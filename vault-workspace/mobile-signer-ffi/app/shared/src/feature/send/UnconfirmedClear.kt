package com.mebit.app.feature.send

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.data.FiatQuote
import com.mebit.app.data.OnboardingSession
import com.mebit.app.data.Replacement
import com.mebit.app.data.Repost
import com.mebit.app.data.SendDraft
import com.mebit.app.data.SingleSigWallet
import com.mebit.app.data.formatBtcCompact
import com.mebit.app.ui.components.PrimaryCta
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbBorder
import com.mebit.app.ui.theme.MbEyebrow
import com.mebit.app.ui.theme.MbNavy
import com.mebit.app.ui.theme.MbNavyInk
import com.mebit.app.ui.theme.MbWhite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The ways out of a payment guard that nothing settles. First ส่งรายการเดิมอีกครั้ง, offered at once when the guard keeps the signed
 * bytes, which posts the same payment again behind [confirmRepost] and says how the last one ended ([repost]). Then the replacements
 * of [ReplacementControls]. Then a text link, offered
 * once the guard is old enough by the phone clock, or at once for a damaged guard file ([SingleSigWallet.unconfirmedClearOffered]),
 * that opens an inline confirm naming the txid, or saying the record is damaged, whose confirm clears the guard in memory and on the
 * phone. While a repost or a replacement runs the link waits. Nothing here, so no screen that shows the guard is left showing it. A
 * clear the phone could not finish keeps the guard and says so under the confirm. Every post runs in the screen's [scope], so its
 * result outlives the guard it settled. The copy is a default awaiting design review (docs/04-open-items.md).
 */
@Composable
internal fun UnconfirmedClearControl(
    wallet: SingleSigWallet,
    session: OnboardingSession,
    draft: SendDraft,
    quote: FiatQuote?,
    repost: RepostAttempt,
    scope: CoroutineScope,
    confirmRepost: suspend () -> Boolean,
    confirmReplacement: suspend (Replacement) -> Boolean,
) {
    val pending = wallet.unconfirmedSend ?: return
    var offered by remember(pending) { mutableStateOf(wallet.unconfirmedClearOffered()) }
    // The clock decides only when to offer the link, and a tick keeps that offer honest while the screen stays open.
    LaunchedEffect(pending) {
        while (!offered) {
            delay(1_000)
            offered = wallet.unconfirmedClearOffered()
        }
    }
    if (pending is SingleSigWallet.UnconfirmedSend && pending.hex != null) {
        Spacer(modifier = Modifier.height(12.dp))
        PrimaryCta(
            if (repost.step == Step.Retry) "กำลังส่งรายการเดิม…" else "ส่งรายการเดิมอีกครั้ง",
            "unconfirmed_repost",
            { repost.start(scope, pending.txid, confirmRepost, draft) { wallet.repost(session) } },
            enabled = !repost.running,
            height = 44.dp,
        )
        val (line, tag) = when (repost.done?.takeIf { it.txid == pending.txid }?.outcome) {
            Repost.Unknown -> "ยังไม่ทราบผลเหมือนเดิม ส่งรายการเดิมอีกครั้งภายหลังได้" to "unconfirmed_repost_unknown"
            Repost.Refused -> "เครือข่ายไม่รับรายการเดิม อาจยืนยันไปแล้วหรือเหรียญถูกใช้ไปแล้ว ตรวจประวัติในหน้าแรกหรือใน explorer ก่อนล้างสถานะ" to "unconfirmed_repost_refused"
            Repost.Failed -> "ส่งรายการเดิมไม่สำเร็จ ลองใหม่ภายหลัง" to "unconfirmed_repost_failed"
            Repost.Out, null -> null to null
        }
        if (line != null && tag != null) Note(line, tag)
        ReplacementControls(wallet, session, draft, quote, repost, scope, confirmReplacement, pending)
    }
    if (!offered || repost.running) return
    var confirming by remember(pending) { mutableStateOf(false) }
    var stuck by remember(pending) { mutableStateOf(false) }
    Spacer(modifier = Modifier.height(12.dp))
    if (!confirming) {
        LinkRow("ตรวจแล้วว่ายังไม่ออก ส่งใหม่ได้", "unconfirmed_clear") { confirming = true }
        return
    }
    val shape = RoundedCornerShape(16.dp)
    Column(modifier = Modifier.fillMaxWidth().clip(shape).background(MbWhite).border(1.dp, MbBorder, shape).padding(16.dp)) {
        val (named, tag) = when (pending) {
            is SingleSigWallet.UnconfirmedSend -> "txid ${shorten(pending.txid, "…")}" to "unconfirmed_clear_txid"
            SingleSigWallet.DamagedGuard -> "บันทึกการส่งครั้งก่อนเสียหาย ไม่ทราบ txid" to "unconfirmed_clear_damaged"
        }
        Text(named, color = MbNavyInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp, modifier = Modifier.testTag(tag))
        Spacer(modifier = Modifier.height(6.dp))
        Text("กดต่อเมื่อตรวจใน explorer แล้วว่าไม่มีธุรกรรมนี้ ถ้าส่งไปแล้วจะจ่ายซ้ำ", color = MbNavyInk, fontSize = 14.sp, lineHeight = 20.sp)
        Spacer(modifier = Modifier.height(12.dp))
        PrimaryCta("ยืนยันส่งใหม่", "unconfirmed_clear_confirm", { stuck = !wallet.clearUnconfirmedSend(session) }, height = 44.dp)
        if (stuck) {
            Spacer(modifier = Modifier.height(6.dp))
            Text("ล้างสถานะการส่งครั้งก่อนไม่สำเร็จ ลองใหม่ภายหลัง", color = MbEyebrow, fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 17.sp, modifier = Modifier.testTag("unconfirmed_clear_failed"))
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinkRow("ยกเลิก", "unconfirmed_clear_cancel", color = MbBody) { confirming = false }
    }
}

/**
 * The replacements of the guard [pending], each spending every coin of it, so a chain pays at most once. Which links show is
 * [RepostAttempt.offers]. A tap asks the owner through [confirmReplacement] and signs at the fast rate off the main thread. The card
 * that follows shows what it pays, and its confirm posts it with no second owner check, as the high-fee card does. Each step shows a
 * running line, and the last post here a line of how it ended, under the guard it started from or the one it left.
 */
@Composable
private fun ReplacementControls(
    wallet: SingleSigWallet,
    session: OnboardingSession,
    draft: SendDraft,
    quote: FiatQuote?,
    repost: RepostAttempt,
    scope: CoroutineScope,
    confirmReplacement: suspend (Replacement) -> Boolean,
    pending: SingleSigWallet.UnconfirmedSend,
) {
    repost.replaced?.takeIf { pending.txid == it.from || pending.txid == it.txid }?.let { last ->
        val copy = last.kind.copy
        when (last.outcome) {
            Repost.Unknown -> Note(copy.unknown, "replacement_unknown")
            Repost.Refused -> Note(copy.refused, "replacement_refused")
            Repost.Failed -> Note(copy.failed, "replacement_failed")
            Repost.Out -> Unit
        }
    }
    repost.signFailure?.takeIf { it.from == pending.txid }?.let { Note(it.line, "replacement_not_signed") }
    when (val step = repost.step) {
        is Step.Sign -> Note(step.kind.copy.signing, "replacement_signing")
        is Step.Post -> Note(step.kind.copy.posting, "replacement_posting")
        Step.Retry, null -> Unit
    }
    if (repost.running) return
    val held = wallet.heldReplacement?.takeIf { it.replaces.txid == pending.txid }
    if (held != null) {
        ReplacementCard(held, quote, onConfirm = { repost.postReplacement(scope, held, draft) { wallet.postReplacement(session) } }, onDrop = wallet::dropHeldReplacement)
        return
    }
    for (kind in repost.offers(pending)) {
        val label = if (kind == Replacement.Cancel && pending.holdsCancel) "ยกเลิกอีกครั้งด้วยค่าธรรมเนียมที่สูงขึ้น" else kind.copy.link
        Spacer(modifier = Modifier.height(12.dp))
        LinkRow(label, kind.copy.tag) {
            repost.startReplacement(scope, kind, pending.txid, { confirmReplacement(kind) }) {
                if (wallet.prepareReplacement(session, kind, wallet.feeTiers().fast)) null else wallet.lastError ?: "เตรียมรายการใหม่ไม่สำเร็จ ลองใหม่ภายหลัง"
            }
        }
    }
}

/** The card of a signed replacement before its post. It shows what it pays, its new fee and what the user must know, with the confirm and a way back. */
@Composable
private fun ReplacementCard(held: SingleSigWallet.HeldReplacement, quote: FiatQuote?, onConfirm: () -> Unit, onDrop: () -> Unit) {
    val amount = "${formatBtcCompact(held.prepared.amountSats.toLong())} BTC"
    val copy = held.kind.copy
    Spacer(modifier = Modifier.height(12.dp))
    val shape = RoundedCornerShape(16.dp)
    Column(modifier = Modifier.fillMaxWidth().clip(shape).background(MbWhite).border(1.dp, MbBorder, shape).padding(16.dp).testTag("replacement_card")) {
        when (held.kind) {
            Replacement.FeeBump -> ReviewRow("ผู้รับเดิม", amount, tag = "replacement_amount")
            Replacement.Cancel -> Text("คืน $amount เข้ากระเป๋านี้", color = MbNavyInk, fontSize = 14.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp, modifier = Modifier.testTag("replacement_amount"))
        }
        ReviewDivider()
        ReviewRow("ค่าธรรมเนียมใหม่", realFeeText(held.prepared.feeSats.toLong(), quote), tag = "replacement_fee")
        Spacer(modifier = Modifier.height(6.dp))
        Text(copy.warning, color = MbNavyInk, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.testTag("replacement_warning"))
        Spacer(modifier = Modifier.height(12.dp))
        PrimaryCta(copy.confirm, "replacement_confirm", onConfirm, height = 44.dp)
        Spacer(modifier = Modifier.height(4.dp))
        LinkRow(copy.drop, "replacement_drop", color = MbBody, onClick = onDrop)
    }
}

/** The words of one [Replacement] on the screens, a draft awaiting design review. [out] comes before the txid in the note. */
private class ReplacementCopy(
    val link: String,
    val tag: String,
    val signing: String,
    val posting: String,
    val warning: String,
    val confirm: String,
    val drop: String,
    val unknown: String,
    val refused: String,
    val failed: String,
    val out: String,
)

private val Replacement.copy: ReplacementCopy
    get() = when (this) {
        Replacement.FeeBump -> FEE_BUMP_COPY
        Replacement.Cancel -> CANCEL_COPY
    }

private val FEE_BUMP_COPY = ReplacementCopy(
    link = "เพิ่มค่าธรรมเนียมแล้วส่งใหม่",
    tag = "unconfirmed_bump",
    signing = "กำลังเตรียมรายการค่าธรรมเนียมใหม่…",
    posting = "กำลังส่งด้วยค่าธรรมเนียมใหม่…",
    warning = "รายการนี้ใช้เหรียญชุดเดียวกับรายการเดิม จึงออกได้เพียงรายการเดียว",
    confirm = "ส่งด้วยค่าธรรมเนียมนี้",
    drop = "ยกเลิก",
    unknown = "ยังไม่ทราบผลของรายการค่าธรรมเนียมใหม่ ส่งรายการเดิมอีกครั้งภายหลังได้",
    refused = "เครือข่ายไม่รับรายการค่าธรรมเนียมใหม่ รายการเดิมอาจยืนยันไปแล้ว ตรวจประวัติในหน้าแรกหรือใน explorer ก่อนล้างสถานะ",
    failed = "ส่งรายการค่าธรรมเนียมใหม่ไม่สำเร็จ ลองใหม่ภายหลัง",
    out = "ส่งด้วยค่าธรรมเนียมใหม่แล้ว",
)

private val CANCEL_COPY = ReplacementCopy(
    link = "ยกเลิกรายการนี้",
    tag = "unconfirmed_cancel",
    signing = "กำลังเตรียมรายการยกเลิก…",
    posting = "กำลังส่งรายการยกเลิก…",
    warning = "ถ้ารายการเดิมยืนยันก่อน การยกเลิกจะไม่สำเร็จ ผู้รับอาจเห็นรายการเดิมแล้วระหว่างรอ",
    confirm = "ยกเลิกด้วยค่าธรรมเนียมนี้",
    drop = "ไม่ยกเลิก",
    unknown = "ยังไม่ทราบผลของรายการยกเลิก ส่งรายการเดิมอีกครั้งภายหลังได้",
    refused = "เครือข่ายไม่รับรายการยกเลิก รายการเดิมอาจยืนยันไปแล้ว ตรวจประวัติในหน้าแรกหรือใน explorer",
    failed = "ส่งรายการยกเลิกไม่สำเร็จ ลองใหม่ภายหลัง",
    out = "ส่งรายการยกเลิกแล้ว รอยืนยันในบล็อก ระหว่างนี้ยังส่งรายการใหม่ไม่ได้",
)

/** One line under a control, in the colour of the screen's warnings. */
@Composable
private fun Note(line: String, tag: String) {
    Spacer(modifier = Modifier.height(6.dp))
    Text(line, color = MbEyebrow, fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 17.sp, modifier = Modifier.testTag(tag))
}

/**
 * What a screen shows once a post on it put a transaction out. After a repost or a fee bump the guard and its controls are gone, so
 * this line says where the payment went. A cancel that is out keeps its guard until a block holds it, so the line says to wait.
 */
@Composable
internal fun RepostedNote(repost: RepostAttempt, wallet: SingleSigWallet) {
    val waiting = (wallet.unconfirmedSend as? SingleSigWallet.UnconfirmedSend)?.takeIf { it.holdsCancel }?.txid
    val replaced = repost.replaced?.takeIf { it.outcome == Repost.Out }
    val (line, txid, tag) = when {
        replaced != null -> Triple(replaced.kind.copy.out, replaced.txid, "replacement_out")
        repost.outTxid == null -> return
        repost.outTxid == waiting -> Triple(CANCEL_COPY.out, waiting, "replacement_out")
        else -> Triple("ส่งรายการเดิมออกไปแล้ว", repost.outTxid, "unconfirmed_repost_out")
    }
    Spacer(modifier = Modifier.height(12.dp))
    Text("$line · txid ${shorten(txid.orEmpty(), "…")}", color = MbNavyInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp, modifier = Modifier.testTag(tag))
}

/** How one repost on a screen ended, with the [txid] of the guard it posted again. */
internal data class Reposted(val txid: String, val outcome: Repost)

/** How the post of a [kind] replacement on a screen ended. [from] is the guard it replaced and [txid] its own. */
internal data class Replaced(val kind: Replacement, val from: String, val txid: String, val outcome: Repost)

/** Why the core signed no replacement of the guard [from], in the wallet's Thai. */
internal data class SignFailure(val from: String, val line: String)

/** What runs on a screen, a repost of the guard's own bytes or the signing or the post of a replacement. */
internal sealed interface Step {
    data object Retry : Step

    data class Sign(val kind: Replacement) : Step

    data class Post(val kind: Replacement) : Step
}

/**
 * One repost or replacement at a time on a screen. A tap while one runs starts nothing, so a double tap posts once, and a refused
 * owner check posts and signs nothing. The screen owns it, so its result outlives the guard it settled.
 */
internal class RepostAttempt {
    /** What runs now, or null. */
    var step: Step? by mutableStateOf(null)
        private set

    val running: Boolean get() = step != null

    /** The last repost that passed the owner check, or null. */
    var done: Reposted? by mutableStateOf(null)
        private set

    /** The last replacement posted here, or null. */
    var replaced: Replaced? by mutableStateOf(null)
        private set

    /** The last replacement the core would not sign here, or null. */
    var signFailure: SignFailure? by mutableStateOf(null)
        private set

    /** The txid of the payment a repost on this screen put out, or null. */
    val outTxid: String? get() = done?.takeIf { it.outcome == Repost.Out }?.txid

    /** True once a repost or a replacement here put a transaction out. */
    val wentOut: Boolean get() = outTxid != null || replaced?.outcome == Repost.Out

    /** Whether the node last refused the bytes of the guard [txid] here, on a repost of them or on the post of the replacement that made them. */
    fun refused(txid: String): Boolean =
        done?.let { it.txid == txid && it.outcome == Repost.Refused } == true || replaced?.let { it.txid == txid && it.outcome == Repost.Refused } == true

    /**
     * The replacements this screen offers for the guard [pending], in the order of their links. A cancel at once on a chain with no
     * cancel, because a retry could put out the payment the user wants to cancel. A fee bump only once the node refused the guard's
     * bytes here, because a refusal is when a higher fee helps. On a chain that holds a cancel, only another cancel, on that same gate.
     */
    fun offers(pending: SingleSigWallet.UnconfirmedSend): List<Replacement> = when {
        pending.hex == null -> emptyList()
        pending.holdsCancel -> listOfNotNull(Replacement.Cancel.takeIf { refused(pending.txid) })
        refused(pending.txid) -> listOf(Replacement.FeeBump, Replacement.Cancel)
        else -> listOf(Replacement.Cancel)
    }

    /**
     * Asks the owner through [confirm], the check signing asks, because this signs a new transaction, and only then signs a [kind]
     * replacement of the guard [from] off the main thread through [sign]. [sign] holds it for the card and answers null, or answers the
     * line that says why not.
     */
    fun startReplacement(scope: CoroutineScope, kind: Replacement, from: String, confirm: suspend () -> Boolean, sign: () -> String?): Job = scope.launch {
        if (running) return@launch
        step = Step.Sign(kind)
        signFailure = null
        try {
            if (confirm()) withContext(Dispatchers.Default) { sign() }?.let { signFailure = SignFailure(from, it) }
        } finally {
            step = null
        }
    }

    /**
     * Posts [held] through [post] off the main thread, with no second owner check because the user passed one to sign it. One that is
     * out drops [draft] in the same step, as [start] does, because a second review of that payment would pay twice.
     */
    fun postReplacement(scope: CoroutineScope, held: SingleSigWallet.HeldReplacement, draft: SendDraft, post: () -> Repost): Job = scope.launch {
        if (running) return@launch
        step = Step.Post(held.kind)
        try {
            val outcome = withContext(Dispatchers.Default) { post().also { if (it == Repost.Out) draft.reset() } }
            replaced = Replaced(held.kind, held.replaces.txid, held.prepared.txid, outcome)
        } finally {
            step = null
        }
    }

    /** Asks the owner through [confirm], the check that signing asks, and only then runs [post] off the main thread for the guard [txid]. */
    suspend fun run(txid: String, confirm: suspend () -> Boolean, post: () -> Repost) {
        if (running) return
        step = Step.Retry
        try {
            if (confirm()) done = Reposted(txid, withContext(Dispatchers.Default) { post() })
        } finally {
            step = null
        }
    }

    /**
     * [run] in [scope], for Send and Review. A repost that puts the payment out drops [draft] in the same step off the main thread
     * that learns it is out, because the draft may hold that payment and a second review of it would pay twice. Leaving the screen
     * cancels [scope], which can keep the post from starting but never keeps the draft of a payment that went out.
     */
    fun start(scope: CoroutineScope, txid: String, confirm: suspend () -> Boolean, draft: SendDraft, post: () -> Repost): Job =
        scope.launch { run(txid, confirm) { post().also { if (it == Repost.Out) draft.reset() } } }

    /** [go], unless a repost runs here, because leaving the screen meanwhile would hide a payment that may still go out. */
    fun unlessRunning(go: () -> Unit): () -> Unit = { if (!running) go() }
}

/** The text link of BottomActions, on its own. */
@Composable
internal fun LinkRow(label: String, tag: String, color: androidx.compose.ui.graphics.Color = MbNavy, onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().height(29.dp).clickable(role = Role.Button, onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}
