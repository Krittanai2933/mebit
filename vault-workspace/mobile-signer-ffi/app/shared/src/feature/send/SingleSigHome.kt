package com.mebit.app.feature.send

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.data.OnboardingSession
import com.mebit.app.data.SingleSigWallet
import com.mebit.app.data.formatBtc
import com.mebit.app.resources.Res
import com.mebit.app.resources.ic_chevron
import com.mebit.app.resources.mebit_logo
import com.mebit.app.ui.components.DebugLink
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbSlate
import com.mebit.app.ui.theme.MbChip
import com.mebit.app.ui.theme.MbBorder
import com.mebit.app.ui.theme.MbBtcOrange
import com.mebit.app.ui.theme.MbEyebrow
import com.mebit.app.ui.theme.MbIconWash
import com.mebit.app.ui.theme.MbNavy
import com.mebit.app.ui.theme.MbNavyInk
import com.mebit.app.ui.theme.MbPage
import com.mebit.app.ui.theme.MbSuccess
import com.mebit.app.ui.theme.MbWhite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource

private val FiatGold = Color(0xFFEEB42A)

/**
 * Penpot 4.1 Home · single-sig. Left out: the recent transactions (the hot wallet exports no history), the
 * bottom tabs (the app has no tab navigation), the settings gear and the key-details link (no screens behind
 * them yet), and the baht line unless the balance is zero, the one amount known in baht without a price feed.
 */
@Composable
fun S1HomeSingleSigScreen(
    wallet: SingleSigWallet,
    session: OnboardingSession,
    onReceive: () -> Unit,
    onSend: () -> Unit,
    onVault: () -> Unit,
    onOpenCatalog: () -> Unit,
) = SingleSigHome(wallet, session, onReceive, onSend, onVault, onOpenCatalog, tag = "screen_send/s1")

/** The home itself, so the backup gate can show it dimmed behind its sheet under a tag of its own. */
@Composable
internal fun SingleSigHome(
    wallet: SingleSigWallet,
    session: OnboardingSession,
    onReceive: () -> Unit,
    onSend: () -> Unit,
    onVault: () -> Unit,
    onOpenCatalog: () -> Unit,
    tag: String,
) {
    // The snapshot is a blocking network call: keep it off the main thread.
    LaunchedEffect(session.words, session.fingerprint, session.passphrase) {
        withContext(Dispatchers.Default) { wallet.bindSession(session) }
    }
    var keyDetails by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize().background(MbPage).testTag(tag)) {
        Box(modifier = Modifier.fillMaxWidth().height(350.dp).background(MbNavy))
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp).padding(top = 4.dp)) {
            Image(painterResource(Res.drawable.mebit_logo), contentDescription = "mebit", modifier = Modifier.size(width = 98.dp, height = 26.dp))
            Spacer(modifier = Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(MbBtcOrange, CircleShape))
                Spacer(modifier = Modifier.width(6.dp))
                Text("SINGLE-SIG · กุญแจเดียว", color = MbWhite, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, lineHeight = 13.sp)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text("กระเป๋าใช้จ่าย", color = MbWhite, fontSize = 19.sp, fontWeight = FontWeight.Medium, lineHeight = 25.sp)
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = formatBtc(wallet.balanceSats, 4) + " BTC",
                    color = MbWhite,
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 50.sp,
                    modifier = Modifier.testTag("ss_balance"),
                )
                if (!wallet.live) {
                    Text(" demo", color = MbWhite.copy(alpha = 0.6f), fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(bottom = 10.dp))
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            // The board's baht line sits here: only an empty wallet can say it, so the room stays either way.
            Box(modifier = Modifier.height(17.dp)) {
                if (wallet.live && wallet.balanceSats == 0L) {
                    Text("≈ ฿0", color = FiatGold, fontSize = 13.sp, fontWeight = FontWeight.Light, lineHeight = 17.sp)
                }
            }
            Spacer(modifier = Modifier.height(9.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(20.dp)) {
                HomeAction("รับ", filled = true, tag = "cta_s1_receive", modifier = Modifier.weight(1f), onClick = onReceive)
                HomeAction("ส่ง", filled = false, tag = "cta_s1_send", modifier = Modifier.weight(1f), onClick = onSend)
            }
            Spacer(modifier = Modifier.height(58.dp))
            KeyStatus(backedUp = session.backupConfirmed, fingerprint = session.fingerprint, expanded = keyDetails, onToggle = { keyDetails = !keyDetails })
            Spacer(modifier = Modifier.height(14.dp))
            UpgradeCard(onVault)
            wallet.lastError?.let {
                Spacer(modifier = Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.testTag("error"))
            }
        }
        DebugLink(onClick = onOpenCatalog)
    }
}

@Composable
private fun HomeAction(label: String, filled: Boolean, tag: String, modifier: Modifier, onClick: () -> Unit) {
    val pill = RoundedCornerShape(999.dp)
    Box(
        modifier = modifier
            .height(50.dp)
            .clip(pill)
            .background(if (filled) MbWhite else MbWhite.copy(alpha = 0.12f))
            .then(if (filled) Modifier else Modifier.border(1.dp, MbWhite.copy(alpha = 0.28f), pill))
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (filled) MbNavy else MbWhite, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Penpot 4.1 closed and 4.1.1 open: the same card, with the key's row under a divider. */
@Composable
private fun KeyStatus(backedUp: Boolean, fingerprint: String, expanded: Boolean, onToggle: () -> Unit) {
    val card = RoundedCornerShape(16.dp)
    Column(modifier = Modifier.fillMaxWidth().clip(card).background(MbWhite).border(1.dp, MbBorder, card).testTag("key_status")) {
        Row(modifier = Modifier.fillMaxWidth().height(52.dp).padding(start = 15.dp, end = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(if (backedUp) MbSuccess else MbBtcOrange, CircleShape))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "กุญแจ: iPhone ของฉัน · " + if (backedUp) "สำรองแล้ว" else "ยังไม่สำรอง",
                color = MbBody,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (expanded) "ซ่อน" else "รายละเอียด",
                color = MbNavy,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 15.sp,
                modifier = Modifier.clickable(role = Role.Button, onClick = onToggle).padding(bottom = 8.dp).testTag("key_details_toggle"),
            )
        }
        if (expanded) {
            Box(modifier = Modifier.padding(horizontal = 22.dp).fillMaxWidth().height(1.dp).background(MbBorder))
            Row(modifier = Modifier.fillMaxWidth().height(63.dp).padding(start = 22.dp, end = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(28.dp).background(MbChip, CircleShape), contentAlignment = Alignment.Center) {
                    Text("1", color = MbNavyInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("iPhone ของฉัน", color = MbNavyInk, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp)
                    Text(
                        text = "mebit บนเครื่องนี้ · " + fingerprint.chunked(4).joinToString(" ").ifBlank { "—" },
                        color = MbSlate,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Light,
                        lineHeight = 15.sp,
                        modifier = Modifier.testTag("key_fingerprint"),
                    )
                }
            }
        }
    }
}

@Composable
private fun UpgradeCard(onClick: () -> Unit) {
    val card = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(70.dp)
            .clip(card)
            .background(MbIconWash)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp)
            .testTag("cta_s1_vault"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp), modifier = Modifier.width(46.dp)) {
            Box(Modifier.size(8.dp).background(MbBtcOrange, CircleShape))
            Box(Modifier.size(8.dp).background(MbBtcOrange, CircleShape))
            Box(Modifier.size(8.dp).border(1.5.dp, MbEyebrow, CircleShape))
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("ยกระดับเป็น Vault หลายกุญแจ", color = MbNavyInk, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, lineHeight = 19.sp)
            Text("เพิ่ม Jade หรือ Trezor · ย้ายเหรียญให้ในครั้งเดียว", color = MbEyebrow, fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 15.sp)
        }
        Image(painterResource(Res.drawable.ic_chevron), contentDescription = null, modifier = Modifier.size(width = 6.dp, height = 12.dp), colorFilter = ColorFilter.tint(MbEyebrow))
    }
}
