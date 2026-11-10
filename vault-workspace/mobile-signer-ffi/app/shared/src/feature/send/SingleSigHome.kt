package com.mebit.app.feature.send

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.data.OnboardingSession
import com.mebit.app.data.QuoteCache
import com.mebit.app.data.SingleSigWallet
import com.mebit.app.data.SingleSigWallet.WalletMode
import com.mebit.app.data.rememberFiatQuote
import com.mebit.app.feature.onboarding.NotSavedNotice
import com.mebit.app.rememberIsDebugBuild
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
import com.mebit.ffi.signer.HotTx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource

private val FiatGold = Color(0xFFEEB42A)

// Four and a half rows at the 67 dp pitch, so a fifth row peeks out to show the box scrolls.
private val TxListMaxHeight = 301.5.dp

/**
 * Penpot 4.1 Home · single-sig. Left out: the settings gear and
 * the key-details link (no screens behind them yet), and the names in the transaction rows (the wallet knows no
 * counterparty, so a row says only รับ or ส่งออก).
 * A key whose balance could not be fetched shows a dash and a retry line in place of a number, and a key whose
 * first fetch is still running shows a dash and "กำลังดึงยอด…". The page scrolls, the navy block with it, and the transactions scroll
 * inside a box four and a half rows tall. A drag scrolls the box, then the page, before it can pull. The pull follows Organize 3.6.3 and 3.6.4 in place of Material's indicator.
 */
@Composable
fun S1HomeSingleSigScreen(
    wallet: SingleSigWallet,
    session: OnboardingSession,
    quotes: QuoteCache,
    onReceive: () -> Unit,
    onSend: () -> Unit,
    onVault: () -> Unit,
    onHistory: () -> Unit,
    onOpenTx: (HotTx) -> Unit,
    onOpenCatalog: () -> Unit,
    onChangePin: (() -> Unit)? = null,
    remove: () -> RemoveRow = { RemoveRow.Hidden },
    removeLine: String? = null,
    onRemove: () -> Unit = {},
) = SingleSigHome(wallet, session, quotes, onReceive, onSend, onVault, onHistory, onOpenTx, onOpenCatalog, tag = "screen_send/s1", onChangePin = onChangePin, remove = remove, removeLine = removeLine, onRemove = onRemove)

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
    onHistory: () -> Unit,
    onOpenTx: (HotTx) -> Unit,
    onOpenCatalog: () -> Unit,
    tag: String,
    onChangePin: (() -> Unit)? = null,
    remove: () -> RemoveRow = { RemoveRow.Hidden },
    removeLine: String? = null,
    onRemove: () -> Unit = {},
) {
    var removing by remember { mutableStateOf(false) }
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
    // A scan that stopped short goes on by itself, as its line under the dash promises, while Home is on screen. A bind that
    // starts in the wait restarts this effect, so the wait ends with no bind running.
    var asked by remember { mutableIntStateOf(0) }
    LaunchedEffect(wallet.mode, wallet.scan, binding) {
        if (!scanContinues(wallet.mode, wallet.scan)) {
            asked = 0
        } else if (!binding) {
            delay(scanContinueDelayMillis(asked))
            asked++
            retries++
        }
    }
    var keyDetails by remember { mutableStateOf(false) }
    val debugBuild = rememberIsDebugBuild()
    val quote = rememberFiatQuote(quotes)
    // A key of 12 or more words is a real one, yet the wallet says Demo until its first bind ends. Read from the session, this holds on the first frame, before any bind has started.
    val loading = session.words.size >= 12 && wallet.mode == WalletMode.Demo
    val rows = if (wallet.mode == WalletMode.Live) historyRows(wallet.history) else emptyList()
    val pull = remember { RowsPullState() }
    // Derived, so a drag recomposes the page only when one of these flips and not on every frame.
    val armed by remember { derivedStateOf { pull.distanceFraction > 1f } }
    val open by remember { derivedStateOf { pull.distanceFraction > 0f } }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MbPage)
            .testTag(tag)
            .pullToRefresh(
                isRefreshing = pulled,
                state = pull,
                onRefresh = {
                    pulled = true
                    retries = afterPull(retries, binding)
                },
            ),
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
            Text(session.walletName, color = MbWhite, fontSize = 19.sp, fontWeight = FontWeight.Medium, lineHeight = 25.sp)
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = homeBalanceText(wallet.balanceSats, wallet.balanceKnown, loading),
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
            // The board's baht line sits here, and so do the fetch note, the scan's calm line and the retry. The room stays when none shows, so nothing below it moves.
            val scanLine = homeScanLine(wallet.mode, wallet.scan)
            Box(modifier = Modifier.height(17.dp)) {
                when {
                    loading -> GoldLine("กำลังดึงยอด…")
                    scanLine != null -> Text(
                        scanLine,
                        color = MbWhite.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Light,
                        lineHeight = 17.sp,
                        maxLines = 1,
                        modifier = Modifier.testTag("ss_scan_line"),
                    )
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
            KeyStatus(backedUp = session.backupConfirmed, fingerprint = session.fingerprint, expanded = keyDetails, onToggle = { keyDetails = !keyDetails }, onChangePin = onChangePin, remove = remove, removeLine = removeLine, onRemove = { removing = true })
            Spacer(modifier = Modifier.height(14.dp))
            UpgradeCard(onVault)
            if (removing) {
                RemoveWalletSheet(
                    name = session.walletName,
                    backedUp = session.backupConfirmed,
                    onConfirm = {
                        removing = false
                        onRemove()
                    },
                    onCancel = { removing = false },
                )
            }
            NotSavedNotice(session, Modifier.padding(top = 12.dp))
            wallet.lastError?.let {
                Spacer(modifier = Modifier.height(12.dp))
                // Already plain Thai: every read error goes through readErrorThai, so none names the server or the library.
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.testTag("error"))
            }
            debugFailureLine(wallet.lastFailure, debugBuild)?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(it, color = MbBody, fontSize = 10.sp, lineHeight = 13.sp, modifier = Modifier.testTag("debug_failure"))
            }
            if (rows.isNotEmpty() || open || pulled) {
                Spacer(modifier = Modifier.height(24.dp))
                val hasMore = homeHasMoreHistory(wallet.history)
                val end = txHeaderEnd(pulled, armed, hasMore)
                Box {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ธุรกรรมล่าสุด", color = MbSlate, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 14.sp, modifier = Modifier.weight(1f).testTag("home_tx_label"))
                        if (hasMore) {
                            Text(
                                "ดูทั้งหมด",
                                color = MbNavy,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                lineHeight = 14.sp,
                                // Hidden, not removed, so the status over it moves nothing below.
                                modifier = (if (end == TxHeaderEnd.SeeAll) Modifier.clickable(role = Role.Button, onClick = onHistory) else Modifier.alpha(0f).clearAndSetSemantics { })
                                    .padding(vertical = 8.dp)
                                    .testTag("cta_s1_history"),
                            )
                        }
                    }
                    if (end == TxHeaderEnd.Armed || end == TxHeaderEnd.Refreshing) {
                        Row(
                            modifier = Modifier.matchParentSize().wrapContentHeight(align = Alignment.CenterVertically, unbounded = true),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (end == TxHeaderEnd.Refreshing) {
                                RefreshSpinner()
                                Spacer(modifier = Modifier.width(11.dp))
                            }
                            Text("กำลังอัปเดต…", color = MbBody, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, lineHeight = 13.2.sp, modifier = Modifier.testTag("home_refresh_status"))
                        }
                    }
                }
                PullGap(pull)
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = TxListMaxHeight).testTag("home_tx_list")) {
                    itemsIndexed(rows, key = { _, row -> row.tx.txid }) { index, row ->
                        HomeTxRowItem(row, Modifier.testTag("home_tx_$index")) { onOpenTx(row.tx) }
                    }
                }
            }
        }
        // Once the navy block has scrolled up, the status bar still needs a navy ground for its white icons.
        Box(modifier = Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.safeDrawing).background(MbNavy))
        DebugLink(onClick = onOpenCatalog)
    }
}

/**
 * How far the rows are pulled open, in Material's threshold units: 0 at rest and 1 where letting go refreshes. A refresh shows in the
 * header (3.6.4), so it closes the gap to 0 and ignores the snaps Material's node makes after every scroll while it holds the offset at
 * the threshold. On release past the threshold the node calls onRefresh and animateToHidden at once, then recomposition calls animateToThreshold.
 */
private class RowsPullState : PullToRefreshState {
    private val fraction = Animatable(0f)
    private var refreshing = false
    override val distanceFraction: Float get() = fraction.value
    override val isAnimating: Boolean get() = fraction.isRunning
    override suspend fun snapTo(targetValue: Float) { if (!refreshing) fraction.snapTo(targetValue) }
    override suspend fun animateToThreshold() { refreshing = true; fraction.animateTo(0f) }
    override suspend fun animateToHidden() { refreshing = false; fraction.animateTo(0f) }
}

/** The right end of the transactions header: 3.6.4's spinner while a pull refreshes, 3.6.3's text once the pull would refresh, else the link. */
internal enum class TxHeaderEnd { None, SeeAll, Armed, Refreshing }

internal fun txHeaderEnd(refreshing: Boolean, armed: Boolean, hasMore: Boolean): TxHeaderEnd = when {
    refreshing -> TxHeaderEnd.Refreshing
    armed -> TxHeaderEnd.Armed
    hasMore -> TxHeaderEnd.SeeAll
    else -> TxHeaderEnd.None
}

/** The gap under the header that the pull opens, with 3.6.3's badge centred in it. */
@Composable
private fun PullGap(pull: RowsPullState) {
    val fraction = pull.distanceFraction
    Box(modifier = Modifier.fillMaxWidth().height(16.dp + 64.dp * fraction), contentAlignment = Alignment.Center) {
        if (fraction > 0f) {
            val grown = minOf(fraction, 1f)
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .graphicsLayer { alpha = grown; scaleX = grown; scaleY = grown }
                    .background(MbWhite, CircleShape)
                    .border(0.75.dp, MbBorder, CircleShape)
                    .testTag("home_pull_badge"),
                contentAlignment = Alignment.Center,
            ) {
                PullArc(boxSize = 24.dp, circle = 18.dp, sweep = 270f * grown)
            }
        }
    }
}

/** 3.6.4's spinner, turning once a second. The turn is read in the layer, so it redraws without recomposing the page. */
@Composable
private fun RefreshSpinner() {
    val turn by rememberInfiniteTransition().animateFloat(0f, 360f, infiniteRepeatable(tween(1_000, easing = LinearEasing)))
    PullArc(boxSize = 18.dp, circle = 13.5.dp, sweep = 270f, modifier = Modifier.graphicsLayer { rotationZ = turn })
}

/** The track circle with the navy arc on it, clockwise from 12 o'clock. */
@Composable
private fun PullArc(boxSize: Dp, circle: Dp, sweep: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.size(boxSize)) {
        val stroke = 2.5.dp.toPx()
        val diameter = circle.toPx()
        val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
        val arcSize = Size(diameter, diameter)
        drawArc(MbBorder, 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
        drawArc(MbNavy, -90f, sweep, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** The retry count after a pull. A pull during a bind joins that bind, because a second one would block another thread and could end first. */
internal fun afterPull(retries: Int, binding: Boolean): Int = if (binding) retries else retries + 1

/** The Rust text under the error line, which names the request that failed. A debug build only: a release build shows the Thai line alone, as before. */
internal fun debugFailureLine(failure: String?, debugBuild: Boolean): String? = failure?.takeIf { debugBuild }

/** The board's gold 13 sp line under the balance. */
@Composable
internal fun GoldLine(text: String, modifier: Modifier = Modifier) {
    Text(text, color = FiatGold, fontSize = 13.sp, fontWeight = FontWeight.Light, lineHeight = 17.sp, modifier = modifier)
}

/**
 * One recent transaction: the arrow, the title over the date, and the amount over the status, on the board's 67 dp pitch.
 * A tap anywhere on it calls [onClick].
 */
@Composable
internal fun HomeTxRowItem(row: HomeTxRow, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier = modifier.fillMaxWidth().heightIn(min = 67.dp).clickable(role = Role.Button, onClick = onClick)) {
        TxDirectionIcon(row.received, Modifier.padding(top = 5.dp))
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

/**
 * The 32 dp circle with the direction arrow, on the rows and on the detail's header. The colours follow board 3.6 Vault Detail, where
 * money in is the navy arrow on the pale wash and money out is the amber arrow on the warm wash. Board S1 Home draws them the other
 * way round, and the user chose 3.6. See open item 69.
 */
@Composable
internal fun TxDirectionIcon(received: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(32.dp).background(if (received) MbChip else MbIconWash, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        TxArrow(down = received, color = if (received) MbNavy else MbEyebrow)
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

/**
 * Penpot 4.1 closed and 4.1.1 open: the same card, with the key's row under a divider. [onChangePin] adds a link under the key's
 * row where the session Spending PIN guards spending, which no board has yet.
 */
@Composable
private fun KeyStatus(backedUp: Boolean, fingerprint: String, expanded: Boolean, onToggle: () -> Unit, onChangePin: (() -> Unit)?, remove: () -> RemoveRow, removeLine: String?, onRemove: () -> Unit) {
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
            onChangePin?.let {
                Text(
                    text = "เปลี่ยน PIN",
                    color = MbNavy,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(start = 62.dp, bottom = 14.dp).clickable(role = Role.Button, onClick = it).testTag("cta_change_pin"),
                )
            }
            // Read here, so each open of the details judges the book again, because a sync that finds the same book invalidates nothing.
            val removeState = remove()
            if (removeState != RemoveRow.Hidden) {
                Box(modifier = Modifier.padding(horizontal = 22.dp).fillMaxWidth().height(1.dp).background(MbBorder))
                val blocked = removeBlockedLine(removeState)
                if (blocked == null) {
                    Box(modifier = Modifier.fillMaxWidth().height(48.dp).clickable(role = Role.Button, onClick = onRemove).testTag("cta_remove_wallet"), contentAlignment = Alignment.Center) {
                        Text(REMOVE_ROW_LABEL, color = MbEyebrow, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Text(blocked, color = MbSlate, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp).testTag("remove_blocked"))
                }
                removeLine?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag("remove_failed"))
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
