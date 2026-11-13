package com.mebit.app.feature.send

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.data.QuoteCache
import com.mebit.app.data.TxDetailLoad
import com.mebit.app.data.rememberFiatQuote
import com.mebit.app.rememberIsDebugBuild
import com.mebit.app.resources.Res
import com.mebit.app.resources.ic_chevron
import com.mebit.app.ui.components.BackChip
import com.mebit.app.ui.components.DebugLink
import com.mebit.app.ui.components.PrimaryCta
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbBorder
import com.mebit.app.ui.theme.MbChip
import com.mebit.app.ui.theme.MbEyebrow
import com.mebit.app.ui.theme.MbGold
import com.mebit.app.ui.theme.MbGreenWash
import com.mebit.app.ui.theme.MbIconWash
import com.mebit.app.ui.theme.MbNavy
import com.mebit.app.ui.theme.MbNavyInk
import com.mebit.app.ui.theme.MbPage
import com.mebit.app.ui.theme.MbSuccess
import com.mebit.app.ui.theme.MbWhite
import com.mebit.ffi.signer.HotTx
import com.mebit.ffi.signer.KeyNetwork
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource

/** How far the navy band runs on behind the top of the status card, which straddles its edge as on the board. */
private val STATUS_OVERLAP = 44.dp

/**
 * Board 3.6.2 for one transaction of the hot wallet. The row the tap carried draws at once, and the cards that need the inputs and
 * outputs appear when the explorer answers. Each open asks once, and leaving and opening again asks again. Left out for now: the
 * explorer link, share, the note, the time estimate, the sent time of a payment still waiting, and the unknown-outcome banner,
 * which no row can open because the guard clears as soon as a history lists its txid.
 */
@Composable
fun S8TxDetailScreen(
    tx: HotTx,
    detail: suspend (txid: String) -> TxDetailLoad.Done,
    walletName: String,
    quotes: QuoteCache,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
    tag: String = "screen_send/s8",
) {
    var retries by remember(tx.txid) { mutableIntStateOf(0) }
    var load by remember(tx.txid) { mutableStateOf<TxDetailLoad>(TxDetailLoad.Loading) }
    LaunchedEffect(tx.txid, retries) {
        load = TxDetailLoad.Loading
        load = detail(tx.txid)
    }
    val view = txDetailView(tx, load, rememberFiatQuote(quotes), KeyNetwork.TEST)
    val debugBuild = rememberIsDebugBuild()
    val clipboard = LocalClipboardManager.current
    // The copy button that says it copied, by its tag, one at a time.
    var copied by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copied) {
        if (copied != null) {
            delay(1_500)
            copied = null
        }
    }
    val copy = { tag: String, text: String ->
        clipboard.setText(AnnotatedString(text))
        copied = tag
    }
    var technical by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize().background(MbPage).testTag(tag)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .safeDrawingPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 16.dp),
        ) {
            Header(view, walletName, onBack)
            StatusCard(view.status)
            view.note?.let { note ->
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    note.text,
                    color = if (note.retry) MaterialTheme.colorScheme.error else MbBody,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.testTag(note.tag),
                )
                if (note.retry) {
                    Spacer(modifier = Modifier.height(6.dp))
                    LinkRow("ลองอีกครั้ง", "cta_s8_retry") { retries++ }
                }
            }
            debugFailureLine(view.debugText, debugBuild)?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(it, color = MbBody, fontSize = 10.sp, lineHeight = 13.sp, modifier = Modifier.testTag("debug_failure"))
            }
            if (view.date != null || view.addresses != null) {
                Spacer(modifier = Modifier.height(14.dp))
                DetailCard {
                    view.date?.let { date ->
                        Label("วันที่และเวลา")
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(date, color = MbNavyInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp, modifier = Modifier.testTag("tx_date"))
                    }
                    if (view.date != null && view.addresses != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        ReviewDivider()
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    view.addresses?.let { group ->
                        Label(group.label)
                        group.addresses.forEachIndexed { i, address ->
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    address.chunked(4).joinToString(" "),
                                    color = MbNavyInk,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    lineHeight = 20.sp,
                                    modifier = Modifier.weight(1f).testTag("tx_address_$i"),
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                CopyChip(copied == "cta_s8_copy_address_$i", "cta_s8_copy_address_$i") { copy("cta_s8_copy_address_$i", address) }
                            }
                        }
                    }
                }
            }
            view.cost?.let { cost ->
                Spacer(modifier = Modifier.height(14.dp))
                DetailCard(tag = "tx_cost") {
                    cost.fee?.let {
                        ReviewRow("ค่าธรรมเนียมเครือข่าย", it, tag = "tx_fee")
                        ReviewDivider()
                    }
                    cost.rate?.let {
                        ReviewRow("อัตราค่าธรรมเนียม", it, tag = "tx_rate")
                        ReviewDivider()
                    }
                    ReviewRow("รวมออกจากกระเป๋า", cost.total, bold = true, tag = "tx_total")
                    cost.change?.let {
                        ReviewDivider()
                        ReviewRow("เงินทอนกลับเข้ากระเป๋า", it, tag = "tx_change")
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            DetailCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f)) { Label("รหัสธุรกรรม (TXID)") }
                    CopyChip(copied == "cta_s8_copy_txid", "cta_s8_copy_txid") { copy("cta_s8_copy_txid", view.txid) }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(view.txid.chunked(8).joinToString(" "), color = MbNavyInk, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp, modifier = Modifier.testTag("tx_txid"))
            }
            view.technical?.let { tech ->
                Spacer(modifier = Modifier.height(14.dp))
                DetailCard {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { technical = !technical }.padding(vertical = 6.dp).testTag("cta_s8_technical"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("รายละเอียดทางเทคนิค", color = MbNavyInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp, modifier = Modifier.weight(1f))
                        if (technical) {
                            Text("ซ่อน", color = MbNavy, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        } else {
                            Image(painterResource(Res.drawable.ic_chevron), contentDescription = null, modifier = Modifier.size(width = 6.dp, height = 12.dp), colorFilter = ColorFilter.tint(MbEyebrow))
                        }
                    }
                    if (technical) {
                        Column(modifier = Modifier.testTag("tx_technical")) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Label("ขาเข้า")
                            tech.inputs.forEachIndexed { i, line -> TxoRow(line, "tx_input_$i") }
                            Spacer(modifier = Modifier.height(14.dp))
                            Label("ขาออก")
                            tech.outputs.forEachIndexed { i, line -> TxoRow(line, "tx_output_$i") }
                            Spacer(modifier = Modifier.height(10.dp))
                            ReviewDivider()
                            ReviewRow("ค่าธรรมเนียม (ขาเข้า − ขาออก)", tech.fee, tag = "tx_tech_fee")
                            ReviewDivider()
                            ReviewRow("อัตราค่าธรรมเนียม", tech.rate, tag = "tx_tech_rate")
                            ReviewDivider()
                            ReviewRow("ขนาด", tech.size, tag = "tx_size")
                            ReviewDivider()
                            ReviewRow("ขนาดเสมือน (vsize)", tech.vsize, tag = "tx_vsize")
                            ReviewDivider()
                            ReviewRow("แทนที่ได้ (RBF)", tech.rbf, tag = "tx_rbf")
                            ReviewDivider()
                            ReviewRow("บล็อก", tech.block, tag = "tx_block")
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            PrimaryCta("กลับ", "cta_s8_back", onClick = { onBack?.invoke() }, enabled = onBack != null)
            // Clears the debug link, which floats over the bottom inset.
            Spacer(modifier = Modifier.height(28.dp))
        }
        // Once the navy band has scrolled up, the status bar still needs a navy ground for its white icons.
        Box(modifier = Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.safeDrawing).background(MbNavy))
        DebugLink(onClick = onOpenCatalog)
    }
}

/** The navy top: back, the title and the network, the wallet, the direction, the amount and its value now. */
@Composable
private fun Header(view: TxDetailView, walletName: String, onBack: (() -> Unit)?) {
    Column(
        modifier = Modifier.fillMaxWidth().drawBehind {
            // Out to the screen's sides, up past the status bar, and down behind the top of the status card.
            val side = 20.dp.toPx()
            val above = 200.dp.toPx()
            drawRect(MbNavy, topLeft = Offset(-side, -above), size = Size(size.width + 2 * side, above + size.height + STATUS_OVERLAP.toPx()))
        },
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                BackChip(onClick = onBack, dark = true)
                Spacer(modifier = Modifier.width(12.dp))
            }
            Text("รายละเอียดธุรกรรม", color = MbWhite, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp, modifier = Modifier.weight(1f))
            if (view.testnet) {
                Text(
                    "TESTNET",
                    color = MbGold,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp,
                    modifier = Modifier.border(1.dp, MbGold, RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 3.dp).testTag("tx_testnet"),
                )
            }
        }
        Spacer(modifier = Modifier.height(18.dp))
        Text("$walletName · Bitcoin", color = MbWhite.copy(alpha = 0.7f), fontSize = 12.sp, lineHeight = 16.sp)
        Spacer(modifier = Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("tx_direction")) {
            TxDirectionIcon(view.received)
            Spacer(modifier = Modifier.width(10.dp))
            Text(view.title, color = MbWhite, fontSize = 15.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(view.amount, color = MbWhite, fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 44.sp, modifier = Modifier.testTag("tx_amount"))
        // The room stays when neither line shows, so nothing below moves when the price or the detail arrives.
        Column(modifier = Modifier.height(38.dp)) {
            view.amountNote?.let { Text(it, color = MbWhite.copy(alpha = 0.7f), fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.testTag("tx_amount_note")) }
            view.fiat?.let { GoldLine(it, Modifier.testTag("tx_fiat")) }
        }
        Spacer(modifier = Modifier.height(6.dp))
    }
}

/** Confirmed or not, the count and the block, six segments toward [CONFIRMATION_TARGET], and what to expect. */
@Composable
private fun StatusCard(status: TxStatusView) {
    val shape = RoundedCornerShape(18.dp)
    Column(modifier = Modifier.fillMaxWidth().shadow(4.dp, shape, clip = false).clip(shape).background(MbWhite).border(1.dp, MbBorder, shape).padding(16.dp).testTag("tx_status")) {
        Text(
            status.title,
            color = if (status.confirmed) MbSuccess else MbEyebrow,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 15.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(if (status.confirmed) MbGreenWash else MbIconWash)
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .testTag("tx_status_title"),
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(status.line, color = MbNavyInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp, modifier = Modifier.testTag("tx_confirmations"))
        status.filled?.let { filled ->
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "ยืนยัน $filled จาก $CONFIRMATION_TARGET บล็อก" }.testTag("tx_progress"),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                repeat(CONFIRMATION_TARGET.toInt()) { i ->
                    Box(modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (i < filled) MbSuccess else MbBorder))
                }
            }
        }
        status.hint?.let {
            Spacer(modifier = Modifier.height(10.dp))
            Text(it, color = MbBody, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.testTag("tx_status_hint"))
        }
    }
}

@Composable
private fun DetailCard(tag: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = Modifier.fillMaxWidth().clip(shape).background(MbWhite).border(1.dp, MbBorder, shape).padding(16.dp).then(tag?.let { Modifier.testTag(it) } ?: Modifier),
        content = content,
    )
}

@Composable
private fun Label(text: String) {
    Text(text, color = MbBody, fontSize = 12.sp, lineHeight = 16.sp)
}

/** Copies on a tap and says so for a moment, as Receive's copy button does. */
@Composable
private fun CopyChip(copied: Boolean, tag: String, onClick: () -> Unit) {
    Text(
        if (copied) "คัดลอกแล้ว" else "คัดลอก",
        color = MbNavy,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(MbChip).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp).testTag(tag),
    )
}

/** One input or output of the technical section: the short address, the amount, and whose it is under them. */
@Composable
private fun TxoRow(line: TxoLine, tag: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(tag)) {
        Row {
            Text(line.address, color = MbNavyInk, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
            Text(line.amount, color = MbNavyInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp)
        }
        line.tag?.let { Text(it, color = MbEyebrow, fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 15.sp) }
    }
}
