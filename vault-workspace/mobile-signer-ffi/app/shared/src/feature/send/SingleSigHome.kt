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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.data.DescribedWallet
import com.mebit.app.data.HomeSource
import com.mebit.app.data.OnboardingSession
import com.mebit.app.data.QuoteCache
import com.mebit.app.data.SingleSigWallet
import com.mebit.app.data.SingleSigWallet.WalletMode
import com.mebit.app.data.fingerprintGroups
import com.mebit.app.data.hardwareMonogram
import com.mebit.app.data.hardwareSource
import com.mebit.app.data.keySourceName
import com.mebit.app.data.rememberFiatQuote
import com.mebit.app.feature.onboarding.AddressTypeRows
import com.mebit.app.feature.onboarding.NotSavedNotice
import com.mebit.app.rememberIsDebugBuild
import com.mebit.app.resources.Res
import com.mebit.app.resources.ic_chevron
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbSlate
import com.mebit.app.ui.theme.MbChip
import com.mebit.app.ui.theme.MbBorder
import com.mebit.app.ui.theme.MbBtcOrange
import com.mebit.app.ui.theme.MbEyebrow
import com.mebit.app.ui.theme.MbIconWash
import com.mebit.app.ui.theme.MbNavy
import com.mebit.app.ui.theme.MbNavyInk
import com.mebit.app.ui.theme.MbSuccess
import com.mebit.app.ui.theme.MbWhite
import com.mebit.ffi.signer.HotTx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource

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
) = SingleSigHome(wallet, session, quotes, onReceive, onSend, onVault, onHistory, onOpenTx, onOpenCatalog, tag = "screen_send/s1", onChangePin = onChangePin)

/** The phone wallet's home, so the backup gate can show it dimmed behind its sheet under a tag of its own. */
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
) = OneKeyHome(
    wallet = wallet,
    walletName = session.walletName,
    // A key of 12 or more words is a real one, yet the wallet says Demo until its first bind ends. Read from the session, this holds on the first frame, before any bind has started.
    loading = session.words.size >= 12 && wallet.mode == WalletMode.Demo,
    bindKey = listOf(session.words, session.fingerprint, session.passphrase),
    // The snapshot is a blocking network call: keep it off the main thread.
    bind = { withContext(Dispatchers.Default) { wallet.bindSession(session) } },
    quotes = quotes,
    onReceive = onReceive,
    onSend = onSend,
    onHistory = onHistory,
    onOpenTx = onOpenTx,
    onOpenCatalog = onOpenCatalog,
    tag = tag,
) {
    var keyDetails by remember { mutableStateOf(false) }
    PolicyCard(
        board = WalletBoard.Home,
        dot = if (session.backupConfirmed) MbSuccess else MbBtcOrange,
        summary = "กุญแจ: iPhone ของฉัน · " + if (session.backupConfirmed) "สำรองแล้ว" else "ยังไม่สำรอง",
        keys = listOf(KeyRowText("iPhone ของฉัน", "mebit บนเครื่องนี้ · " + session.fingerprint.chunked(4).joinToString(" ").ifBlank { "—" }, "key_fingerprint")),
        expanded = keyDetails,
        onToggle = { keyDetails = !keyDetails },
    ) {
        // A link under the key's row where the session Spending PIN guards spending, which no board has yet.
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
    }
    Spacer(modifier = Modifier.height(14.dp))
    UpgradeCard(onVault)
    NotSavedNotice(session, Modifier.padding(top = 12.dp))
}

/**
 * Penpot 4.1.5 Home of a one-key hardware wallet, and 4.1.5.1 with its key details open: the phone wallet's home over [wallet], with the
 * key on the device in the key card and no upgrade card, since a Jade wallet's upgrade would add a Jade to a Jade wallet. [keyName] is
 * the Keyring's name of its key ([com.mebit.app.data.hardwareKeyName]).
 */
@Composable
fun HardwareHomeScreen(
    wallet: DescribedWallet,
    keyName: String,
    quotes: QuoteCache,
    onReceive: () -> Unit,
    onSend: () -> Unit,
    onHistory: () -> Unit,
    onOpenTx: (HotTx) -> Unit,
    onOpenCatalog: () -> Unit,
) {
    val hw = wallet.wallet
    OneKeyHome(
        wallet = wallet,
        walletName = hw.name,
        // Demo only until the first refresh ends: the device's key is a real one from the start.
        loading = wallet.mode == WalletMode.Demo,
        bindKey = hw.descriptor,
        bind = { wallet.refresh() },
        quotes = quotes,
        onReceive = onReceive,
        onSend = onSend,
        onHistory = onHistory,
        onOpenTx = onOpenTx,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_hw/home",
    ) {
        var keyDetails by remember { mutableStateOf(false) }
        val source = hardwareSource(hw.kind)
        PolicyCard(
            board = WalletBoard.Home,
            dot = MbSuccess,
            summary = "กุญแจ: $keyName · อยู่บนอุปกรณ์",
            keys = listOf(
                KeyRowText(
                    keyName,
                    "${keySourceName(source)} · ${AddressTypeRows.first { it.type == hw.addressType }.label} · ${fingerprintGroups(hw.fingerprint)}",
                    "key_fingerprint",
                    badge = hardwareMonogram(source),
                ),
            ),
            expanded = keyDetails,
            onToggle = { keyDetails = !keyDetails },
        )
    }
}

/**
 * The home of a one-key wallet, whichever signs its payments. [bind] reads [wallet] again, and runs whenever [bindKey] changes, on a retry
 * and on a pull. [keyCard] is what sits between the buttons and the transactions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OneKeyHome(
    wallet: HomeSource,
    walletName: String,
    loading: Boolean,
    bindKey: Any,
    bind: suspend () -> Unit,
    quotes: QuoteCache,
    onReceive: () -> Unit,
    onSend: () -> Unit,
    onHistory: () -> Unit,
    onOpenTx: (HotTx) -> Unit,
    onOpenCatalog: () -> Unit,
    tag: String,
    keyCard: @Composable ColumnScope.() -> Unit,
) {
    var retries by remember { mutableIntStateOf(0) }
    // One bind at a time, because each one blocks a thread until it ends and an older one could end last.
    var binding by remember { mutableStateOf(false) }
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(bindKey, retries) {
        binding = true
        bind()
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
    val debugBuild = rememberIsDebugBuild()
    val quote = rememberFiatQuote(quotes)
    val rows = if (wallet.mode == WalletMode.Live) historyRows(wallet.history) else emptyList()
    val pull = remember { RowsPullState() }
    // Derived, so a drag recomposes the page only when one of these flips and not on every frame.
    val armed by remember { derivedStateOf { pull.distanceFraction > 1f } }
    val open by remember { derivedStateOf { pull.distanceFraction > 0f } }
    WalletPage(
        tag = tag,
        board = WalletBoard.Home,
        onOpenCatalog = onOpenCatalog,
        modifier = Modifier.pullToRefresh(
            isRefreshing = pulled,
            state = pull,
            onRefresh = {
                pulled = true
                retries = afterPull(retries, binding)
            },
        ),
    ) {
        WalletHero(
            board = WalletBoard.Home,
            eyebrow = "SINGLE-SIG · กุญแจเดียว",
            name = walletName,
            balance = homeBalanceText(wallet.balanceSats, wallet.balanceKnown, loading),
            balanceTag = "ss_balance",
            receiveTag = "cta_s1_receive",
            onReceive = onReceive,
            sendTag = "cta_s1_send",
            onSend = onSend,
            demo = wallet.mode == WalletMode.Demo && !loading,
        ) {
            // The board's baht line sits here, and so do the fetch note, the scan's calm line and the retry.
            val scanLine = homeScanLine(wallet.mode, wallet.scan)
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
        Spacer(modifier = Modifier.height(58.dp))
        keyCard()
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

private val serverAddress = Regex("""https?://[^/\s)]+|\b\d{1,3}(?:\.\d{1,3}){3}(?::\d+)?\b""")

/**
 * The Rust text under the error line, which names the request that failed, with every server's address shown as <host>,
 * because a screenshot of a debug build must not give away the private node's host. A debug build only: a release build
 * shows the Thai line alone, as before.
 */
internal fun debugFailureLine(failure: String?, debugBuild: Boolean): String? =
    failure?.takeIf { debugBuild }?.replace(serverAddress, "<host>")

/**
 * One recent transaction: the arrow, the title over the date, and the amount over the status, on the board's 67 dp pitch.
 * A tap anywhere on it calls [onClick]. With no [onClick] the row is not a button, and a screen reader reads it as one item.
 */
@Composable
internal fun HomeTxRowItem(row: HomeTxRow, modifier: Modifier = Modifier, onClick: (() -> Unit)?) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 67.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier.semantics(mergeDescendants = true) {}),
    ) {
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
            Text("ยกระดับเป็น Wallet หลายกุญแจ", color = MbNavyInk, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, lineHeight = 19.sp)
            Text("เพิ่ม Jade หรือ Trezor · ย้ายเหรียญให้ในครั้งเดียว", color = MbEyebrow, fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 15.sp)
        }
        Image(painterResource(Res.drawable.ic_chevron), contentDescription = null, modifier = Modifier.size(width = 6.dp, height = 12.dp), colorFilter = ColorFilter.tint(MbEyebrow))
    }
}
