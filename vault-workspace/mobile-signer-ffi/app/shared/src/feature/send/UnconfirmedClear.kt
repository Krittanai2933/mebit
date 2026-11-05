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
import com.mebit.app.data.OnboardingSession
import com.mebit.app.data.Repost
import com.mebit.app.data.SingleSigWallet
import com.mebit.app.ui.components.PrimaryCta
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbBorder
import com.mebit.app.ui.theme.MbEyebrow
import com.mebit.app.ui.theme.MbNavy
import com.mebit.app.ui.theme.MbNavyInk
import com.mebit.app.ui.theme.MbWhite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The ways out of a payment guard that nothing settles. First ส่งรายการเดิมอีกครั้ง, offered at once when the guard keeps the signed
 * bytes, which posts the same payment again through [onRepost] and says how the last one ended ([repost]). Then a text link, offered
 * once the guard is old enough by the phone clock, or at once for a damaged guard file ([SingleSigWallet.unconfirmedClearOffered]),
 * that opens an inline confirm naming the txid, or saying the record is damaged, whose confirm clears the guard in memory and on the
 * phone. While a repost runs the link waits. Nothing here, so no screen that shows the guard is left showing it. A clear the phone
 * could not finish keeps the guard and says so under the confirm. The copy is a default awaiting design review (docs/04-open-items.md).
 */
@Composable
internal fun UnconfirmedClearControl(wallet: SingleSigWallet, session: OnboardingSession, repost: RepostAttempt, onRepost: (txid: String) -> Unit) {
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
        PrimaryCta(if (repost.running) "กำลังส่งรายการเดิม…" else "ส่งรายการเดิมอีกครั้ง", "unconfirmed_repost", { onRepost(pending.txid) }, enabled = !repost.running, height = 44.dp)
        val (line, tag) = when (repost.done?.takeIf { it.txid == pending.txid }?.outcome) {
            Repost.Unknown -> "ยังไม่ทราบผลเหมือนเดิม ส่งรายการเดิมอีกครั้งภายหลังได้" to "unconfirmed_repost_unknown"
            Repost.Refused -> "เครือข่ายไม่รับรายการเดิม อาจยืนยันไปแล้วหรือเหรียญถูกใช้ไปแล้ว ตรวจประวัติในหน้าแรกหรือใน explorer ก่อนล้างสถานะ" to "unconfirmed_repost_refused"
            Repost.Failed -> "ส่งรายการเดิมไม่สำเร็จ ลองใหม่ภายหลัง" to "unconfirmed_repost_failed"
            Repost.Out, null -> null to null
        }
        if (line != null && tag != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(line, color = MbEyebrow, fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 17.sp, modifier = Modifier.testTag(tag))
        }
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

/** What a screen shows once a repost on it put the payment out. The guard and its controls are gone, so this line says where it went. */
@Composable
internal fun RepostedNote(repost: RepostAttempt) {
    val txid = repost.outTxid ?: return
    Spacer(modifier = Modifier.height(12.dp))
    Text("ส่งรายการเดิมออกไปแล้ว · txid ${shorten(txid, "…")}", color = MbNavyInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp, modifier = Modifier.testTag("unconfirmed_repost_out"))
}

/** How one repost on a screen ended, with the [txid] of the guard it posted again. */
internal data class Reposted(val txid: String, val outcome: Repost)

/**
 * One repost at a time on a screen. A tap while one runs starts nothing, so a double tap posts once, and a refused owner check
 * posts nothing. The screen owns it, so its result outlives the guard it settled.
 */
internal class RepostAttempt {
    var running by mutableStateOf(false)
        private set

    /** The last repost that passed the owner check, or null. */
    var done: Reposted? by mutableStateOf(null)
        private set

    /** The txid of the payment a repost on this screen put out, or null. */
    val outTxid: String? get() = done?.takeIf { it.outcome == Repost.Out }?.txid

    /** Asks the owner through [confirm], the check that signing asks, and only then runs [post] off the main thread for the guard [txid]. */
    suspend fun run(txid: String, confirm: suspend () -> Boolean, post: () -> Repost) {
        if (running) return
        running = true
        try {
            if (confirm()) done = Reposted(txid, withContext(Dispatchers.Default) { post() })
        } finally {
            running = false
        }
    }
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
