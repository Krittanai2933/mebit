package com.mebit.app.feature.send

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.data.OnboardingSession
import com.mebit.app.data.QuoteCache
import com.mebit.app.data.SingleSigWallet
import com.mebit.app.data.SingleSigWallet.WalletMode
import com.mebit.app.data.formatBtc
import com.mebit.app.data.rememberFiatQuote
import com.mebit.app.feature.onboarding.NotSavedNotice
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
 * Penpot 4.1 Home · single-sig. Left out: the bottom tabs (the app has no tab navigation), the settings gear and
 * the key-details link (no screens behind them yet), and the names in the transaction rows (the wallet knows no
 * counterparty, so a row says only รับ or ส่งออก).
 * A key whose balance could not be fetched shows a dash and a retry line in place of a number, and a key whose
 * first fetch is still running shows a dash and "กำลังดึงยอด…". The whole page scrolls, the navy block with it,
 * so a short phone or a large font still reaches the rows.
 */
@Composable
fun S1HomeSingleSigScreen(
    wallet: SingleSigWallet,
    session: OnboardingSession,
    quotes: QuoteCache,
    onReceive: () -> Unit,
    onSend: () -> Unit,
    onVault: () -> Unit,
    onOpenCatalog: () -> Unit,
) = SingleSigHome(wallet, session, quotes, onReceive, onSend, onVault, onOpenCatalog, tag = "screen_send/s1")

/** The home itself, so the backup gate can show it dimmed behind its sheet under a tag of its own. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SingleSigHome(
    wallet: SingleSigWallet,
    session: OnboardingSession,
    quotes: QuoteCache,
    onReceive: () -> Unit,
    onSend: () -> Unit,
    onVault: () -> Unit,
    onOpenCatalog: () -> Unit,
    tag: String,
) {
    var retries by remember { mutableIntStateOf(0) }
    // One bind at a time, because each one blocks a thread until it ends and an older one could end last.
    var binding by remember { mutableStateOf(false) }
    var pulled by remember { mutableStateOf(false) }
    // The snapshot is a blocking network call: keep it off the main thread.
    LaunchedEffect(session.words, session.fingerprint, session.passphrase, retries) {
        binding = true
        withContext(Dispatchers.Default) { wallet.bindSession(session) }
        binding = false
        pulled = false
    }
    var keyDetails by remember { mutableStateOf(false) }
    val quote = rememberFiatQuote(quotes)
    // A key of 12 or more words is a real one, yet the wallet says Demo until its first bind ends. Read from the session, this holds on the first frame, before any bind has started.
    val loading = session.words.size >= 12 && wallet.mode == WalletMode.Demo
    val rows = if (wallet.mode == WalletMode.Live) homeRows(wallet.history) else emptyList()
    PullToRefreshBox(
        isRefreshing = pulled,
        onRefresh = {
            pulled = true
            retries = afterPull(retries, binding)
        },
        modifier = Modifier.fillMaxSize().background(MbPage).testTag(tag),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // After the scroll, so the navy block is part of the content and scrolls away with it.
                .drawBehind { drawRect(MbNavy, size = size.copy(height = 350.dp.toPx())) }
                .safeDrawingPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 4.dp),
        ) {
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
                    text = if (wallet.balanceKnown && !loading) formatBtc(wallet.balanceSats, 4) + " BTC" else "—",
                    color = MbWhite,
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 50.sp,
                    modifier = Modifier.testTag("ss_balance"),
                )
                if (wallet.mode == WalletMode.Demo && !loading) {
                    Text(" demo", color = MbWhite.copy(alpha = 0.6f), fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(bottom = 10.dp))
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            // The board's baht line sits here, and so do the fetch note and the retry. The room stays when none shows, so nothing below it moves.
            Box(modifier = Modifier.height(17.dp)) {
                when {
                    loading -> GoldLine("กำลังดึงยอด…")
                    !wallet.balanceKnown -> GoldLine(
                        text = "ดึงยอดไม่สำเร็จ · แตะเพื่อลองใหม่",
                        modifier = Modifier.clickable(role = Role.Button, enabled = !binding) { retries++ }.testTag("cta_s1_retry"),
                    )
                    wallet.mode == WalletMode.Live -> fiatLine(wallet.balanceSats, quote)?.let { GoldLine(it) }
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
            NotSavedNotice(session, Modifier.padding(top = 12.dp))
            wallet.lastError?.let {
                Spacer(modifier = Modifier.height(12.dp))
                // Already plain Thai: every read error goes through readErrorThai, so none names the server or the library.
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.testTag("error"))
            }
            if (rows.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                Text("ธุรกรรมล่าสุด", color = MbSlate, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 14.sp, modifier = Modifier.testTag("home_tx_label"))
                Spacer(modifier = Modifier.height(16.dp))
                rows.forEachIndexed { index, row -> HomeTxRowItem(row, Modifier.testTag("home_tx_$index")) }
            }
        }
        // Once the navy block has scrolled up, the status bar still needs a navy ground for its white icons.
        Box(modifier = Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.safeDrawing).background(MbNavy))
        DebugLink(onClick = onOpenCatalog)
    }
}

/** The retry count after a pull. A pull during a bind joins that bind, because a second one would block another thread and could end first. */
internal fun afterPull(retries: Int, binding: Boolean): Int = if (binding) retries else retries + 1

/** The board's gold 13 sp line under the balance. */
@Composable
private fun GoldLine(text: String, modifier: Modifier = Modifier) {
    Text(text, color = FiatGold, fontSize = 13.sp, fontWeight = FontWeight.Light, lineHeight = 17.sp, modifier = modifier)
}

/** One recent transaction: the arrow, the title over the date, and the amount over the status, on the board's 67 dp pitch. */
@Composable
private fun HomeTxRowItem(row: HomeTxRow, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().heightIn(min = 67.dp)) {
        Box(
            modifier = Modifier.padding(top = 5.dp).size(32.dp).background(if (row.received) MbIconWash else MbChip, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            TxArrow(down = row.received, color = if (row.received) MbEyebrow else MbNavy)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(row.title, color = MbNavyInk, fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 17.sp)
            Spacer(modifier = Modifier.height(5.dp))
            Text(row.date, color = MbSlate, fontSize = 10.5.sp, fontWeight = FontWeight.Light, lineHeight = 14.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(row.amount, color = if (row.received) MbSuccess else MbSlate, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp)
            Spacer(modifier = Modifier.height(5.dp))
            Text(row.status, color = MbSlate, fontSize = 10.sp, fontWeight = FontWeight.Light, lineHeight = 13.sp)
        }
    }
}

/** An arrow 12 by 14 dp with 2.4 dp strokes: pointing down for money in, and the same turned over for money out. */
@Composable
private fun TxArrow(down: Boolean, color: Color) {
    val stroke = 2.4.dp
    Canvas(Modifier.size(12.dp + stroke, 14.dp + stroke).rotate(if (down) 0f else 180f)) {
        // The box is inset by half a stroke, so the line never crosses the canvas edge.
        val inset = stroke.toPx() / 2
        val width = 12.dp.toPx()
        val height = 14.dp.toPx()
        val middle = inset + width / 2
        val tip = inset + height
        val arrow = Path().apply {
            moveTo(middle, inset)
            lineTo(middle, tip)
            moveTo(inset, tip - width / 2)
            lineTo(middle, tip)
            lineTo(inset + width, tip - width / 2)
        }
        drawPath(arrow, color, style = Stroke(stroke.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
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
