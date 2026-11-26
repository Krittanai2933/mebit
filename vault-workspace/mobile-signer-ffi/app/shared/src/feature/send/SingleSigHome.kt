package com.mebit.app.feature.send

import com.mebit.app.data.LocalBtcUnit
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
import com.mebit.app.data.THIS_PHONE_KEY_NAME
import com.mebit.app.data.fingerprintGroups
import com.mebit.app.data.hardwareSource
import com.mebit.app.data.keySourceName
import com.mebit.app.data.rememberFiatQuote
import com.mebit.app.feature.onboarding.AddressTypeRows
import com.mebit.app.feature.onboarding.NotSavedNotice
import com.mebit.app.rememberIsDebugBuild
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


@Composable
fun S1HomeSingleSigScreen(
    wallet: SingleSigWallet,
    session: OnboardingSession,
    quotes: QuoteCache,
    onReceive: () -> Unit,
    onSend: () -> Unit,
    onHistory: () -> Unit,
    onOpenTx: (HotTx) -> Unit,
    onOpenCatalog: () -> Unit,
    onClose: () -> Unit,
) = SingleSigHome(wallet, session, quotes, onReceive, onSend, onHistory, onOpenTx, onOpenCatalog, onClose, tag = "screen_send/s1")

/** The phone wallet's home, so the backup gate can show it dimmed behind its sheet under a tag of its own. */
@Composable
internal fun SingleSigHome(
    wallet: SingleSigWallet,
    session: OnboardingSession,
    quotes: QuoteCache,
    onReceive: () -> Unit,
    onSend: () -> Unit,
    onHistory: () -> Unit,
    onOpenTx: (HotTx) -> Unit,
    onOpenCatalog: () -> Unit,
    onClose: () -> Unit,
    tag: String,
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
    onClose = onClose,
    tag = tag,
) {
    var keyDetails by remember { mutableStateOf(false) }
    PolicyCard(
        board = WalletBoard.Home,
        dot = if (session.backupConfirmed) MbSuccess else MbBtcOrange,
        summary = "กุญแจ: $THIS_PHONE_KEY_NAME · " + if (session.backupConfirmed) "สำรองแล้ว" else "ยังไม่สำรอง",
        keys = listOf(KeyRowText(THIS_PHONE_KEY_NAME, "mebit บนเครื่องนี้ · " + session.fingerprint.chunked(4).joinToString(" ").ifBlank { "—" }, "key_fingerprint")),
        expanded = keyDetails,
        onToggle = { keyDetails = !keyDetails },
    )
    NotSavedNotice(session, Modifier.padding(top = 12.dp))
}

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
    onClose: () -> Unit,
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
        onClose = onClose,
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
                    logo = source,
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
    onClose: () -> Unit,
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
    val rows = if (wallet.mode == WalletMode.Live) historyRows(wallet.history, unit = LocalBtcUnit.current) else emptyList()
    val pull = remember { RowsPullState() }
    // Derived, so a drag recomposes the page only when one of these flips and not on every frame.
    val armed by remember { derivedStateOf { pull.distanceFraction > 1f } }
    val open by remember { derivedStateOf { pull.distanceFraction > 0f } }
    WalletPage(
        tag = tag,
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
            balance = homeBalanceText(wallet.balanceSats, wallet.balanceKnown, loading, LocalBtcUnit.current),
            balanceTag = "ss_balance",
            receiveTag = "cta_s1_receive",
            onReceive = onReceive,
            sendTag = "cta_s1_send",
            onSend = onSend,
            demo = wallet.mode == WalletMode.Demo && !loading,
            onClose = onClose,
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
            TxSection(
                title = "ธุรกรรมล่าสุด",
                tag = "home",
                hasMore = homeHasMoreHistory(wallet.history),
                historyTag = "cta_s1_history",
                onHistory = onHistory,
                status = txStatus(pulled, armed),
                pull = pull,
            ) {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = TxListMaxHeight).testTag("home_tx_list")) {
                    itemsIndexed(rows, key = { _, row -> row.tx.txid }) { index, row ->
                        HomeTxRowItem(row, Modifier.testTag("home_tx_$index")) { onOpenTx(row.tx) }
                    }
                }
            }
        }
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

