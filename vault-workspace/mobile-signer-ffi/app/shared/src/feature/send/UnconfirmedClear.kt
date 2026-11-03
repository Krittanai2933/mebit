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
import com.mebit.app.data.SingleSigWallet
import com.mebit.app.ui.components.PrimaryCta
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbBorder
import com.mebit.app.ui.theme.MbNavy
import com.mebit.app.ui.theme.MbNavyInk
import com.mebit.app.ui.theme.MbWhite
import kotlinx.coroutines.delay

/**
 * The way out of a payment guard that nothing settles: a text link, offered once the guard is old enough by the phone clock
 * ([SingleSigWallet.unconfirmedClearOffered]), that opens an inline confirm naming the txid, whose confirm clears the guard in
 * memory and on the phone. Nothing here, so no screen that shows the guard is left showing it. The copy is a default awaiting
 * design review (docs/04-open-items.md).
 */
@Composable
internal fun UnconfirmedClearControl(wallet: SingleSigWallet, session: OnboardingSession) {
    val pending = wallet.unconfirmedSend ?: return
    var offered by remember(pending) { mutableStateOf(wallet.unconfirmedClearOffered()) }
    // The clock decides only when to offer the link, and a tick keeps that offer honest while the screen stays open.
    LaunchedEffect(pending) {
        while (!offered) {
            delay(1_000)
            offered = wallet.unconfirmedClearOffered()
        }
    }
    if (!offered) return
    var confirming by remember(pending) { mutableStateOf(false) }
    Spacer(modifier = Modifier.height(12.dp))
    if (!confirming) {
        LinkRow("ตรวจแล้วว่ายังไม่ออก ส่งใหม่ได้", "unconfirmed_clear") { confirming = true }
        return
    }
    val shape = RoundedCornerShape(16.dp)
    Column(modifier = Modifier.fillMaxWidth().clip(shape).background(MbWhite).border(1.dp, MbBorder, shape).padding(16.dp)) {
        Text("txid ${shorten(pending.txid, "…")}", color = MbNavyInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp, modifier = Modifier.testTag("unconfirmed_clear_txid"))
        Spacer(modifier = Modifier.height(6.dp))
        Text("กดต่อเมื่อตรวจใน explorer แล้วว่าไม่มีธุรกรรมนี้ ถ้าส่งไปแล้วจะจ่ายซ้ำ", color = MbNavyInk, fontSize = 14.sp, lineHeight = 20.sp)
        Spacer(modifier = Modifier.height(12.dp))
        PrimaryCta("ยืนยันส่งใหม่", "unconfirmed_clear_confirm", { wallet.clearUnconfirmedSend(session) }, height = 44.dp)
        Spacer(modifier = Modifier.height(4.dp))
        LinkRow("ยกเลิก", "unconfirmed_clear_cancel", color = MbBody) { confirming = false }
    }
}

/** The text link of BottomActions, on its own. */
@Composable
private fun LinkRow(label: String, tag: String, color: androidx.compose.ui.graphics.Color = MbNavy, onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().height(29.dp).clickable(role = Role.Button, onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}
