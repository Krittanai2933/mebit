package com.mebit.app.feature.send

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.resources.Res
import com.mebit.app.resources.ic_close
import com.mebit.app.resources.mebit_logo
import com.mebit.app.ui.components.DebugLink
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbBorder
import com.mebit.app.ui.theme.MbBtcOrange
import com.mebit.app.ui.theme.MbChip
import com.mebit.app.ui.theme.MbDisabledFill
import com.mebit.app.ui.theme.MbNavy
import com.mebit.app.ui.theme.MbNavyInk
import com.mebit.app.ui.theme.MbPage
import com.mebit.app.ui.theme.MbProgress
import com.mebit.app.ui.theme.MbSlate
import com.mebit.app.ui.theme.MbWhite
import org.jetbrains.compose.resources.painterResource

private val FiatGold = Color(0xFFEEB42A)

/**
 * The two boards a wallet page serves and every value in which they differ, so a screen names its board and Home keeps its own values.
 * Home is Penpot 4.1 and 4.1.1, Detail is 3.7 and 3.7.1. Change a value only with its board.
 */
internal enum class WalletBoard(
    /** The eyebrow's white: 4.1 full, 3.7 at 72%. */
    val eyebrowAlpha: Float,
    /** The outlined pill's border white: 4.1 at 28%, 3.7 at 20%. */
    val outlineAlpha: Float,
    /** 3.7's faint ring at the navy block's right. */
    val ring: Boolean,
    /** A key's number badge. */
    val keyBadge: Color,
    /** 4.1.1's one key row is 63 dp with its content in the top 53. 3.7.1's rows are 48 dp, 3 dp under the divider and 12 dp over the card's edge. */
    val keyRowHeight: Dp,
    val keyRowBottom: Dp,
    val keyListTop: Dp,
    val keyListBottom: Dp,
) {
    Home(eyebrowAlpha = 1f, outlineAlpha = 0.28f, ring = false, keyBadge = MbChip, keyRowHeight = 63.dp, keyRowBottom = 10.dp, keyListTop = 0.dp, keyListBottom = 0.dp),
    Detail(eyebrowAlpha = 0.72f, outlineAlpha = 0.20f, ring = true, keyBadge = MbDisabledFill, keyRowHeight = 48.dp, keyRowBottom = 0.dp, keyListTop = 3.dp, keyListBottom = 12.dp),
}

/**
 * One numbered key of a policy card: its [name] over its [line], the line tagged [lineTag] for the verify recipes. [badge] takes the
 * number's place in the key's circle, as a hardware wallet's 4.1.5.1 draws its device's monogram there.
 */
internal data class KeyRowText(val name: String, val line: String, val lineTag: String, val badge: String? = null)

/**
 * The page under 4.1 and 3.7: the page ground tagged [tag], the navy block 350 dp tall that scrolls away with the content, [board]'s ring on
 * it, the status bar's navy strip once the block has scrolled up, and the debug link. [modifier] goes last on the outer box, where Home's pull sits.
 */
@Composable
internal fun WalletPage(
    tag: String,
    board: WalletBoard,
    onOpenCatalog: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(MbPage).testTag(tag).then(modifier)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // After the scroll, so the navy block is part of the content and scrolls away with it.
                .drawBehind {
                    drawRect(MbNavy, size = size.copy(height = 350.dp.toPx()))
                    if (board.ring) {
                        // 3.7 draws a 230 dp circle at x 300 and y 124 of a 402 dp screen, cut off by the navy block's end.
                        clipRect(bottom = 350.dp.toPx()) {
                            drawCircle(MbProgress.copy(alpha = 0.14f), radius = 115.dp.toPx(), center = Offset(size.width + 13.dp.toPx(), 239.dp.toPx()), style = Stroke(1.dp.toPx()))
                        }
                    }
                }
                .safeDrawingPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 4.dp),
            content = content,
        )
        // Once the navy block has scrolled up, the status bar still needs a navy ground for its white icons.
        Box(modifier = Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.safeDrawing).background(MbNavy))
        DebugLink(onClick = onOpenCatalog)
    }
}

/**
 * The navy block's content from the logo to the two pills, at the spacing 4.1 and 3.7 share. [goldLine] fills the 17 dp line under the
 * balance, which keeps its room when empty so nothing below it moves. [demo] adds Home's small marker after the balance. [onClose] draws
 * 3.7's X at the logo's right.
 */
@Composable
internal fun WalletHero(
    board: WalletBoard,
    eyebrow: String,
    name: String,
    balance: String,
    balanceTag: String,
    receiveTag: String,
    onReceive: () -> Unit,
    sendTag: String,
    onSend: () -> Unit,
    demo: Boolean = false,
    onClose: (() -> Unit)? = null,
    goldLine: @Composable BoxScope.() -> Unit,
) {
    // The header keeps the logo's 26 dp, so the X's larger target moves nothing below it.
    Box(modifier = Modifier.fillMaxWidth().height(26.dp)) {
        Image(painterResource(Res.drawable.mebit_logo), contentDescription = "mebit", modifier = Modifier.size(width = 98.dp, height = 26.dp))
        onClose?.let { CloseButton(it) }
    }
    Spacer(modifier = Modifier.height(22.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(MbBtcOrange, CircleShape))
        Spacer(modifier = Modifier.width(6.dp))
        Text(eyebrow, color = MbWhite.copy(alpha = board.eyebrowAlpha), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, lineHeight = 13.sp)
    }
    Spacer(modifier = Modifier.height(16.dp))
    Text(name, color = MbWhite, fontSize = 19.sp, fontWeight = FontWeight.Medium, lineHeight = 25.sp)
    Spacer(modifier = Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = balance,
            color = MbWhite,
            fontSize = 38.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 50.sp,
            modifier = Modifier.testTag(balanceTag),
        )
        if (demo) {
            Text(" demo", color = MbWhite.copy(alpha = 0.6f), fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(bottom = 10.dp))
        }
    }
    Spacer(modifier = Modifier.height(2.dp))
    Box(modifier = Modifier.height(17.dp), content = goldLine)
    Spacer(modifier = Modifier.height(9.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        HeroPill("รับ", filled = true, board = board, tag = receiveTag, modifier = Modifier.weight(1f), onClick = onReceive)
        HeroPill("ส่ง", filled = false, board = board, tag = sendTag, modifier = Modifier.weight(1f), onClick = onSend)
    }
}

/**
 * 3.7's close X: the 18 dp X with its right edge at the content's and its top 6 dp below the logo's, read as one button "ปิด", in a 48 dp
 * target centred on it.
 */
@Composable
private fun BoxScope.CloseButton(onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            // requiredSize centres the 48 dp target on the 26 dp header, 11 dp above its top, so this puts its centre on the X's.
            .offset(x = 15.dp, y = 2.dp)
            .requiredSize(48.dp)
            .clickable(role = Role.Button, onClick = onClose)
            .semantics { contentDescription = "ปิด" }
            .testTag("cta_vault_close"),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(Res.drawable.ic_close), contentDescription = null, modifier = Modifier.size(18.dp))
    }
}

/** The board's gold 13 sp line under the balance. */
@Composable
internal fun GoldLine(text: String, modifier: Modifier = Modifier) {
    Text(text, color = FiatGold, fontSize = 13.sp, fontWeight = FontWeight.Light, lineHeight = 17.sp, modifier = modifier)
}

@Composable
private fun HeroPill(label: String, filled: Boolean, board: WalletBoard, tag: String, modifier: Modifier, onClick: () -> Unit) {
    val pill = RoundedCornerShape(999.dp)
    Box(
        modifier = modifier
            .height(50.dp)
            .clip(pill)
            .background(if (filled) MbWhite else MbWhite.copy(alpha = 0.12f))
            .then(if (filled) Modifier else Modifier.border(1.dp, MbWhite.copy(alpha = board.outlineAlpha), pill))
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (filled) MbNavy else MbWhite, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Penpot 4.1 and 3.7 closed, 4.1.1 and 3.7.1 open: the white card with a [dot] and a [summary], whose "รายละเอียด" and "ซ่อน" open and close
 * one numbered row per key under a divider, in [keys]' order from 1. [board] sets the rows' geometry. [footer] follows the rows while open.
 */
@Composable
internal fun PolicyCard(
    board: WalletBoard,
    dot: Color,
    summary: String,
    keys: List<KeyRowText>,
    expanded: Boolean,
    onToggle: () -> Unit,
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    val card = RoundedCornerShape(16.dp)
    Column(modifier = Modifier.fillMaxWidth().clip(card).background(MbWhite).border(1.dp, MbBorder, card).testTag("key_status")) {
        Row(modifier = Modifier.fillMaxWidth().height(52.dp).padding(start = 15.dp, end = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(dot, CircleShape))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = summary,
                color = MbBody,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.weight(1f).testTag("key_status_line"),
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
            Column(modifier = Modifier.padding(top = board.keyListTop, bottom = board.keyListBottom)) {
                keys.forEachIndexed { i, key ->
                    Row(
                        modifier = Modifier.fillMaxWidth().height(board.keyRowHeight).padding(start = 22.dp, end = 22.dp, bottom = board.keyRowBottom),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.size(28.dp).background(board.keyBadge, CircleShape), contentAlignment = Alignment.Center) {
                            val badge = key.badge ?: "${i + 1}"
                            Text(badge, color = MbNavyInk, fontSize = if (badge.length > 2) 10.sp else 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(key.name, color = MbNavyInk, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp)
                            Text(
                                text = key.line,
                                color = MbSlate,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Light,
                                lineHeight = 15.sp,
                                modifier = Modifier.testTag(key.lineTag),
                            )
                        }
                    }
                }
            }
            footer()
        }
    }
}
