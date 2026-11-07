package com.mebit.app.feature.send

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.QrScanSheet
import com.mebit.app.data.AmountEntry
import com.mebit.app.data.AmountUnit
import com.mebit.app.data.FeeTile
import com.mebit.app.data.LiveFeeTiers
import com.mebit.app.data.OnboardingSession
import com.mebit.app.data.FiatQuote
import com.mebit.app.data.QuoteCache
import com.mebit.app.data.Replacement
import com.mebit.app.data.SendDraft
import com.mebit.app.data.SendIssue
import com.mebit.app.data.SingleSigWallet
import com.mebit.app.data.bahtText
import com.mebit.app.data.enterDestination
import com.mebit.app.data.formatBtcCompact
import com.mebit.app.data.rememberFiatQuote
import com.mebit.app.data.scanOutcome
import com.mebit.app.data.sendIssue
import com.mebit.app.rememberCameraAvailable
import com.mebit.app.ui.components.KeyboardDismissBox
import com.mebit.app.ui.components.MebitScreen
import com.mebit.app.ui.components.PrimaryCta
import com.mebit.app.ui.components.keepsKeyboard
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbBorder
import com.mebit.app.ui.theme.MbBtcOrange
import com.mebit.app.ui.theme.MbChip
import com.mebit.app.ui.theme.MbEyebrow
import com.mebit.app.ui.theme.MbNavy
import com.mebit.app.ui.theme.MbNavyInk
import com.mebit.app.ui.theme.MbSlate
import com.mebit.app.ui.theme.MbWhite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.isValidAddress

private val Placeholder = Color(0xFFB4BAC4)
private val SelectedFee = Color(0xFFFFF8EF)

/**
 * Penpot 4.4 Send, with 4.4.1 (address filled), 4.4.3 (amount in baht), 4.4.4 (send all), 4.4.5 (send all in baht),
 * 4.4.6 (insufficient funds) and 4.4.7 (invalid address) as states of it. The scan chip opens [QrScanSheet], which has
 * no board (open item 59), and hides on a device without a camera. The ฿ unit toggle, the baht lines and the fee
 * prices in baht need a price from [quotes]. Without one the screen is BTC and sats only. A balance that could not be
 * fetched closes review with a panel of its own, which has no board.
 */
@Composable
fun S4SendScreen(
    wallet: SingleSigWallet,
    session: OnboardingSession,
    draft: SendDraft,
    quotes: QuoteCache,
    confirmRepost: suspend () -> Boolean,
    confirmReplacement: suspend (Replacement) -> Boolean,
    onContinue: () -> Unit,
    onReceive: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    val quote = rememberFiatQuote(quotes)
    val entry = remember(draft) { AmountEntry(draft) }
    val scope = rememberCoroutineScope()
    val repost = remember { RepostAttempt() }
    // A replacement held for its card is signed and nothing more, so leaving the screen lets it go.
    DisposableEffect(wallet) {
        onDispose { wallet.dropHeldReplacement() }
    }
    // Once per open of this screen. Rust answers from memory for five minutes, so a second open costs no network call.
    LaunchedEffect(draft) { entry.applyTiers(withContext(Dispatchers.Default) { wallet.feeTiers() }, wallet.spendableSats) }
    val addressOk = if (draft.destination.isEmpty()) null else isValidAddress(draft.destination, KeyNetwork.TEST)
    // The spendable balance, not the whole one: a receive still in the mempool cannot pay, and send all pays the spendable figure less the fee.
    val issue = sendIssue(addressOk, draft.amountSats, draft.feeSats, wallet.spendableSats, wallet.balanceKnown, wallet.unconfirmedSend != null)
    val enter = { text: String -> draft.enterDestination(text) { isValidAddress(it, KeyNetwork.TEST) } }
    val cameraAvailable = rememberCameraAvailable()
    var scanning by remember { mutableStateOf(false) }
    if (scanning) {
        QrScanSheet(
            onText = { scanned ->
                scanning = false
                enter(scanOutcome(scanned) { isValidAddress(it, KeyNetwork.TEST) }.text)
            },
            onClose = { scanning = false },
        )
        return
    }
    KeyboardDismissBox {
        MebitScreen(
            eyebrow = "SEND · กระเป๋าใช้จ่าย",
            title = null,
            // Back and รับ wait while a repost or a replacement runs, as Review's back does. The back chip stays so the header does not shift.
            onBack = onBack?.let(repost::unlessRunning),
            onOpenCatalog = onOpenCatalog,
            tag = "screen_send/s4",
            bottom = {
                Spacer(modifier = Modifier.height(12.dp))
                PrimaryCta("ตรวจสอบ", "cta_s4_review", onContinue, enabled = addressOk == true && draft.amountSats > 0 && issue == null)
                // The boards put a link under this button. Keep its room so the button sits where they put it.
                Spacer(modifier = Modifier.height(38.dp))
            },
        ) {
            ReceiveSendSwitch(sendSelected = true, onReceive = repost.unlessRunning(onReceive), onSend = {})
            Spacer(modifier = Modifier.height(20.dp))
            DestinationField(draft, invalid = issue == SendIssue.InvalidAddress, enter, onScan = if (cameraAvailable) ({ scanning = true }) else null)
            Spacer(modifier = Modifier.height(14.dp))
            AmountCard(wallet, entry, quote, short = issue is SendIssue.Insufficient)
            Spacer(modifier = Modifier.height(20.dp))
            Text("ค่าธรรมเนียมเครือข่าย", color = MbNavyInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp)
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (draft.tiers ?: LiveFeeTiers.DEFAULT).tiles(draft.typicalVsize).forEach { tile ->
                    FeeCard(tile, selected = tile.tier == draft.tier, quote, modifier = Modifier.weight(1f)) { entry.chooseTier(tile.tier, wallet.spendableSats) }
                }
            }
            if (draft.tiers?.live == false) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "ใช้ค่าธรรมเนียมโดยประมาณ เพราะดึงค่าจริงไม่ได้",
                    color = MbSlate,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 15.sp,
                    modifier = Modifier.testTag("fee_fallback"),
                )
            }
            when (issue) {
                SendIssue.InvalidAddress -> {
                    Spacer(modifier = Modifier.height(20.dp))
                    IssuePanel("INVALID ADDRESS", "ที่อยู่ไม่ถูกต้อง — ตรวจตัวอักษรและเครือข่าย (รองรับ Bitcoin เท่านั้น) แล้ววางใหม่")
                }
                SendIssue.BalanceUnavailable -> {
                    Spacer(modifier = Modifier.height(20.dp))
                    IssuePanel("BALANCE UNAVAILABLE", "ยังดึงยอดคงเหลือไม่ได้ จึงยังส่งไม่ได้ กลับไปหน้าแรกแล้วลองใหม่")
                }
                SendIssue.PaymentUnconfirmed -> {
                    Spacer(modifier = Modifier.height(20.dp))
                    IssuePanel("PAYMENT UNCONFIRMED", "การส่งครั้งก่อนยังยืนยันไม่ได้ รอสักครู่ แล้วเปิดหน้าแรกเพื่อตรวจยอดก่อนส่งอีกครั้ง")
                    UnconfirmedClearControl(wallet, session, draft, quote, repost, scope, confirmRepost, confirmReplacement)
                }
                is SendIssue.Insufficient -> {
                    Spacer(modifier = Modifier.height(20.dp))
                    IssuePanel("INSUFFICIENT FUNDS", "ยอดไม่พอ — ขาดอีก ${formatBtcCompact(issue.missingSats)} BTC (รวมค่าธรรมเนียม) ลดจำนวน หรือกดส่งทั้งหมด")
                }
                null -> Unit
            }
            // Outside the panel, which leaves with the guard the repost settled.
            RepostedNote(repost, wallet)
        }
    }
}

/** The address field with its chips. The board puts the 32 x 30 scan chip left of วาง; a null [onScan] (no camera) leaves it out. */
@Composable
private fun DestinationField(draft: SendDraft, invalid: Boolean, enter: (String) -> Unit, onScan: (() -> Unit)?) {
    val clipboard = LocalClipboardManager.current
    val shape = RoundedCornerShape(18.dp)
    val ink = if (invalid) MbEyebrow else MbNavyInk
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp)
            .clip(shape)
            .background(MbWhite)
            .border(if (invalid) 1.5.dp else 1.dp, if (invalid) MbBtcOrange else MbBorder, shape)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (invalid) "ที่อยู่ไม่ถูกต้อง" else "ส่งไปที่",
                color = if (invalid) MbEyebrow else MbSlate,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 16.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            BasicTextField(
                value = draft.destination,
                onValueChange = enter,
                textStyle = TextStyle(color = ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
                maxLines = 2,
                decorationBox = { inner ->
                    Box {
                        if (draft.destination.isEmpty()) {
                            Text("วางที่อยู่ปลายทาง", color = Placeholder, fontSize = 15.5.sp, lineHeight = 20.sp)
                        }
                        inner()
                    }
                },
                modifier = Modifier.fillMaxWidth().keepsKeyboard().testTag("s4_address"),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        if (onScan != null) {
            Box(
                modifier = Modifier
                    .size(width = 32.dp, height = 30.dp)
                    .clip(CircleShape)
                    .background(MbChip)
                    .clickable(role = Role.Button, onClick = onScan)
                    .testTag("s4_scan"),
                contentAlignment = Alignment.Center,
            ) {
                Box(modifier = Modifier.size(14.dp).border(1.5.dp, MbNavy, RoundedCornerShape(3.dp)), contentAlignment = Alignment.Center) {
                    Box(modifier = Modifier.size(5.dp).background(MbNavy))
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = "วาง",
            color = MbNavy,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(CircleShape)
                .background(MbChip)
                .clickable(role = Role.Button) { clipboard.getText()?.text?.let(enter) }
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .testTag("s4_paste"),
        )
    }
}

@Composable
private fun AmountCard(wallet: SingleSigWallet, entry: AmountEntry, quote: FiatQuote?, short: Boolean) {
    val shape = RoundedCornerShape(22.dp)
    val amount = TextStyle(color = MbNavyInk, fontSize = 44.sp, fontWeight = FontWeight.Bold, lineHeight = 56.sp)
    val baht = entry.inBaht(quote)
    val text = entry.fieldText(quote)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(168.dp)
            .clip(shape)
            .background(MbWhite)
            .border(if (short) 1.5.dp else 1.dp, if (short) MbBtcOrange else MbBorder, shape),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Spacer(modifier = Modifier.height(14.dp))
            Text("จำนวน", color = MbSlate, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, lineHeight = 16.sp)
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                BasicTextField(
                    value = text,
                    onValueChange = { if (baht) entry.typeBaht(it, quote) else entry.typeBtc(it) },
                    textStyle = amount,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = if (baht) KeyboardType.Number else KeyboardType.Decimal),
                    visualTransformation = if (baht) ThousandsTransformation else VisualTransformation.None,
                    decorationBox = { inner ->
                        Box {
                            if (text.isEmpty()) Text(if (baht) "0" else "0.0000", style = amount.copy(color = Placeholder))
                            inner()
                        }
                    },
                    modifier = Modifier.width(IntrinsicSize.Min).widthIn(min = 40.dp).keepsKeyboard().testTag("s4_amount"),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (baht) "THB" else "BTC", color = MbSlate, fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp, modifier = Modifier.padding(bottom = 12.dp))
            }
            // The other unit's line has its room even when it shows nothing, so the divider keeps its place.
            Box(modifier = Modifier.fillMaxWidth().height(36.dp)) {
                entry.otherLine(quote)?.let {
                    Text(it, color = MbEyebrow, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp).testTag("s4_fiat"))
                }
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MbBorder))
            Spacer(modifier = Modifier.height(11.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = (if (wallet.balanceKnown) entry.caption(wallet.spendableSats, quote) else "ใช้ได้ —") + if (short) " · ไม่พอ" else "",
                    color = if (short) MbEyebrow else MbBody,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 17.sp,
                    modifier = Modifier.testTag("s4_available"),
                )
                Text(
                    text = "ส่งทั้งหมด",
                    color = MbNavy,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 17.sp,
                    modifier = Modifier.clickable(role = Role.Button) { entry.sendAll(wallet.spendableSats) }.testTag("s4_send_all"),
                )
            }
        }
        if (quote != null) {
            UnitToggle(baht, onPick = { entry.pickUnit(it, quote) }, modifier = Modifier.align(Alignment.TopEnd).padding(top = 14.dp, end = 16.dp))
        }
    }
}

/** The BTC / ฿ switch in the amount card's corner, drawn to the boards' 70 x 30 pill with a white 34 x 24 pill behind the chosen unit. */
@Composable
private fun UnitToggle(baht: Boolean, onPick: (AmountUnit) -> Unit, modifier: Modifier) {
    Box(modifier = modifier.size(width = 70.dp, height = 30.dp).clip(CircleShape).background(MbChip)) {
        Box(
            modifier = Modifier
                .offset(x = if (baht) 33.dp else 3.dp, y = 3.dp)
                .size(width = 34.dp, height = 24.dp)
                .clip(CircleShape)
                .background(MbWhite),
        )
        UnitSegment("BTC", active = !baht, start = 5.dp, tag = "s4_unit_btc") { onPick(AmountUnit.Btc) }
        UnitSegment("฿", active = baht, start = 35.dp, tag = "s4_unit_thb") { onPick(AmountUnit.Thb) }
    }
}

@Composable
private fun UnitSegment(label: String, active: Boolean, start: Dp, tag: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .offset(x = start)
            .size(30.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (active) MbNavy else MbSlate, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Shows the baht field's bare digits with a comma every three places. The field keeps the digits, so typing and backspace work on what was typed. */
private object ThousandsTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val shown = groupDigits(text.text)
        return TransformedText(
            AnnotatedString(shown),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int) = caretInGrouped(shown, offset)
                override fun transformedToOriginal(offset: Int) = caretInDigits(shown, offset)
            },
        )
    }
}

/** "18010" to "18,010". It works on the digits as written, so a leading zero keeps its place. */
internal fun groupDigits(digits: String): String = digits.reversed().chunked(3).joinToString(",").reversed()

/** Where a caret after [digitsBefore] digits sits in [grouped], after a comma that follows them. */
internal fun caretInGrouped(grouped: String, digitsBefore: Int): Int {
    var seen = 0
    var at = 0
    while (at < grouped.length && seen < digitsBefore) {
        if (grouped[at] != ',') seen++
        at++
    }
    return if (at < grouped.length && grouped[at] == ',') at + 1 else at
}

/** How many digits lie before the caret at [offset] in a grouped text. */
internal fun caretInDigits(grouped: String, offset: Int): Int = offset - grouped.take(offset).count { it == ',' }

@Composable
private fun FeeCard(tile: FeeTile, selected: Boolean, quote: FiatQuote?, modifier: Modifier, onClick: () -> Unit) {
    val tier = tile.tier
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .height(96.dp)
            .clip(shape)
            .background(if (selected) SelectedFee else MbWhite)
            .border(if (selected) 2.dp else 1.dp, if (selected) MbBtcOrange else MbBorder, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("fee_" + tier.name.lowercase()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(tier.label, color = MbNavyInk, fontSize = 14.sp, fontWeight = FontWeight.Bold, lineHeight = 19.sp)
        Spacer(modifier = Modifier.height(1.dp))
        Text(tier.eta, color = MbBody, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, lineHeight = 15.sp)
        Spacer(modifier = Modifier.height(10.dp))
        Text(bahtText(tile.feeSats, quote) ?: "${thousands(tile.feeSats)} sat", color = if (selected) MbEyebrow else MbNavyInk, fontSize = 15.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp)
        Spacer(modifier = Modifier.height(1.dp))
        Text("${tile.satPerVb} sat/vB", color = MbSlate, fontSize = 10.5.sp, fontWeight = FontWeight.Medium, lineHeight = 14.sp)
    }
}

@Composable
private fun IssuePanel(label: String, message: String) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MbWhite)
            .border(1.dp, MbBorder, shape)
            .padding(16.dp),
    ) {
        Text(label, color = MbEyebrow, fontSize = 11.sp, fontWeight = FontWeight.Bold, lineHeight = 15.sp)
        Spacer(modifier = Modifier.height(6.dp))
        Text(message, color = MbNavyInk, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.testTag("send_issue"))
    }
}

/** 1128 to "1,128". */
internal fun thousands(n: Long): String = n.toString().reversed().chunked(3).joinToString(",").reversed()
