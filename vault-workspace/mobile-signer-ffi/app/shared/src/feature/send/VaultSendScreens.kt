package com.mebit.app.feature.send

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.QrScanSheet
import com.mebit.app.SystemBack
import com.mebit.app.data.AmountEntry
import com.mebit.app.data.BUSY
import com.mebit.app.data.KeySigner
import com.mebit.app.data.QuoteCache
import com.mebit.app.data.Repost
import com.mebit.app.data.SavedKey
import com.mebit.app.data.SendIssue
import com.mebit.app.data.SingleSigWallet.UnconfirmedSend
import com.mebit.app.data.VaultBusy
import com.mebit.app.data.VaultPayment
import com.mebit.app.data.VaultSend
import com.mebit.app.data.VaultSendState
import com.mebit.app.data.acceptsScan
import com.mebit.app.data.enterDestination
import com.mebit.app.data.formatBtc
import com.mebit.app.data.formatBtcCompact
import com.mebit.app.data.keyMonogram
import com.mebit.app.data.rememberFiatQuote
import com.mebit.app.data.sendIssue
import com.mebit.app.data.xpubsIn
import com.mebit.app.feature.vault.deviceWord
import com.mebit.app.feature.vault.vaultKeyRows
import com.mebit.app.formatThaiTime
import com.mebit.app.getPlatform
import com.mebit.app.rememberCameraAvailable
import com.mebit.app.ui.components.BottomActions
import com.mebit.app.ui.components.KeyboardDismissBox
import com.mebit.app.ui.components.MebitScreen
import com.mebit.app.ui.components.PrimaryCta
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbBorder
import com.mebit.app.ui.theme.MbBtcOrange
import com.mebit.app.ui.theme.MbChip
import com.mebit.app.ui.theme.MbEyebrow
import com.mebit.app.ui.theme.MbGreenWash
import com.mebit.app.ui.theme.MbIconWash
import com.mebit.app.ui.theme.MbNavy
import com.mebit.app.ui.theme.MbNavyInk
import com.mebit.app.ui.theme.MbSlate
import com.mebit.app.ui.theme.MbSuccess
import com.mebit.app.ui.theme.MbWhite
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.isValidAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SummaryGold = Color(0xFFEEB42A)

/**
 * Penpot 5.1 Send · wallet: Penpot 4.4's form for a several-key wallet, priced at its own size, with what it can spend
 * ([VaultSend.spendableSats]) and 5.1's orange banner of how many keys must sign. ตรวจสอบ prepares the unsigned payment off the main thread
 * ([VaultSend.prepare]) and opens 5.2. While the wallet's guard is set, PaymentUnconfirmed's panel shows with [VaultGuardControl]. A null
 * [send], as the route catalog or a wallet the core cannot read leaves it, shows one line and nothing to send.
 */
@Composable
fun VaultSendScreen(
    send: VaultSend?,
    quotes: QuoteCache,
    confirmRepost: suspend () -> Boolean,
    otherGuardKeys: () -> List<String>,
    onReview: () -> Unit,
    onReceive: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    if (send == null) {
        MebitScreen(eyebrow = "SEND · WALLET", title = "ส่งจาก Wallet", onBack = onBack, onOpenCatalog = onOpenCatalog, tag = "screen_send/m1") {
            Text(NO_SEND, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("m1_none"))
        }
        return
    }
    val draft = send.draft
    val quote = rememberFiatQuote(quotes)
    val entry = remember(send) { AmountEntry(draft) }
    val scope = rememberCoroutineScope()
    // Until the first read lands nothing is known, and an unknown balance is not yet one that could not be read.
    var loading by remember(send) { mutableStateOf(true) }
    LaunchedEffect(send) {
        val tiers = withContext(Dispatchers.Default) { send.load() }
        entry.applyTiers(tiers, send.spendableSats ?: 0L)
        loading = false
    }
    val spendable = send.spendableSats
    val addressOk = if (draft.destination.isEmpty()) null else isValidAddress(draft.destination, KeyNetwork.TEST)
    val issue = if (loading) {
        SendIssue.InvalidAddress.takeIf { addressOk == false }
    } else {
        sendIssue(addressOk, draft.amountSats, draft.feeSats, spendable ?: 0L, spendable != null, send.guard != null)
    }
    val enter = { text: String -> draft.enterDestination(text) { isValidAddress(it, KeyNetwork.TEST) } }
    val cameraAvailable = rememberCameraAvailable()
    var scanning by remember { mutableStateOf(false) }
    if (scanning) {
        QrScanSheet(
            accepts = { acceptsScan(it) { address -> isValidAddress(address, KeyNetwork.TEST) } },
            onAddress = { scanned ->
                scanning = false
                enter(scanned)
            },
            onClose = { scanning = false },
        )
        return
    }
    KeyboardDismissBox {
        MebitScreen(
            eyebrow = "SEND · ${send.vault.name}",
            title = null,
            // Back and รับ wait while a call runs, so no screen is left with a payment half made.
            onBack = onBack?.let { back -> { if (send.busy == null) back() } },
            onOpenCatalog = onOpenCatalog,
            tag = "screen_send/m1",
            bottom = {
                Spacer(modifier = Modifier.height(12.dp))
                PrimaryCta(
                    if (send.busy == VaultBusy.Preparing) "กำลังตรวจสอบ…" else "ตรวจสอบ",
                    "cta_m1_review",
                    { scope.launch { if (withContext(Dispatchers.Default) { send.prepare() }) onReview() } },
                    enabled = !loading && send.busy == null && addressOk == true && draft.amountSats > 0 && issue == null,
                )
                Spacer(modifier = Modifier.height(38.dp))
            },
        ) {
            ReceiveSendSwitch(sendSelected = true, onReceive = { if (send.busy == null) onReceive() }, onSend = {})
            Spacer(modifier = Modifier.height(20.dp))
            SendFormFields(draft, entry, quote, spendable != null, spendable ?: 0L, issue, enter, onScan = if (cameraAvailable) ({ scanning = true }) else null)
            Spacer(modifier = Modifier.height(16.dp))
            SignaturesBanner(send.vault.m, send.vault.n)
            when (issue) {
                SendIssue.InvalidAddress -> {
                    Spacer(modifier = Modifier.height(20.dp))
                    IssuePanel("INVALID ADDRESS", "ที่อยู่ไม่ถูกต้อง — ตรวจตัวอักษรและเครือข่าย (รองรับ Bitcoin เท่านั้น) แล้ววางใหม่")
                }
                SendIssue.BalanceUnavailable -> {
                    Spacer(modifier = Modifier.height(20.dp))
                    IssuePanel("BALANCE UNAVAILABLE", BALANCE_UNKNOWN)
                }
                SendIssue.PaymentUnconfirmed -> {
                    Spacer(modifier = Modifier.height(20.dp))
                    IssuePanel("PAYMENT UNCONFIRMED", PAYMENT_UNCONFIRMED)
                    VaultGuardControl(send, confirmRepost, otherGuardKeys)
                }
                is SendIssue.Insufficient -> {
                    Spacer(modifier = Modifier.height(20.dp))
                    IssuePanel("INSUFFICIENT FUNDS", "ยอดไม่พอ — ขาดอีก ${formatBtcCompact(issue.missingSats)} BTC (รวมค่าธรรมเนียม) ลดจำนวน หรือกดส่งทั้งหมด")
                }
                null -> Unit
            }
            send.lastError?.let {
                Spacer(modifier = Modifier.height(12.dp))
                Text(it, color = MbEyebrow, fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp, modifier = Modifier.testTag("m1_error"))
            }
        }
    }
}

/** 5.1's banner: a dot per key, filled for the m that must sign, and "ต้องเซ็น m จาก n กุญแจก่อนส่ง". */
@Composable
private fun SignaturesBanner(m: Int, n: Int) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp).clip(shape).background(MbIconWash).padding(horizontal = 16.dp).testTag("m1_banner"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(n) { i ->
            val dot = Modifier.size(9.dp).clip(CircleShape)
            Box(if (i < m) dot.background(MbBtcOrange) else dot.border(1.5.dp, MbBtcOrange, CircleShape))
            Spacer(modifier = Modifier.width(4.dp))
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(signaturesLine(m, n), color = MbEyebrow, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp)
    }
}

/** 5.1's banner text. */
internal fun signaturesLine(m: Int, n: Int): String = "ต้องเซ็น $m จาก $n กุญแจก่อนส่ง"

/**
 * The ways out of a several-key wallet's guard that no history settled, as [UnconfirmedClearControl] offers a one-key wallet's: post the
 * guard's own bytes again behind [confirmRepost], and once the guard is old enough, or at once for a damaged file, a clear the user confirms
 * after checking the explorer. A damaged file's clear leaves the other wallets' guards ([otherGuardKeys]) damaged. The one-key wallet's copy.
 */
@Composable
private fun VaultGuardControl(send: VaultSend, confirmRepost: suspend () -> Boolean, otherGuardKeys: () -> List<String>) {
    val pending = send.guard ?: return
    val scope = rememberCoroutineScope()
    var offered by remember(pending) { mutableStateOf(send.clearOffered()) }
    // The clock decides only when to offer the clear, and a tick keeps that offer honest while the screen stays open.
    LaunchedEffect(pending) {
        while (!offered) {
            delay(1_000)
            offered = send.clearOffered()
        }
    }
    var outcome by remember(pending) { mutableStateOf<Repost?>(null) }
    if (pending is UnconfirmedSend && pending.hex != null) {
        val copy = retryCopy(pending)
        Spacer(modifier = Modifier.height(12.dp))
        PrimaryCta(
            if (send.busy == VaultBusy.Posting) copy.running else copy.label,
            "unconfirmed_repost",
            { scope.launch { if (confirmRepost()) outcome = withContext(Dispatchers.Default) { send.repostGuarded() } } },
            enabled = send.busy == null,
            height = 44.dp,
        )
        when (outcome) {
            Repost.Unknown -> GuardNote(copy.unknown, "unconfirmed_repost_unknown")
            Repost.Refused -> GuardNote(copy.refused, "unconfirmed_repost_refused")
            Repost.Failed -> GuardNote(copy.failed, "unconfirmed_repost_failed")
            Repost.Out, null -> Unit
        }
    }
    if (!offered || send.busy != null) return
    var confirming by remember(pending) { mutableStateOf(false) }
    var stuck by remember(pending) { mutableStateOf(false) }
    Spacer(modifier = Modifier.height(12.dp))
    if (!confirming) {
        LinkRow("ตรวจแล้วว่ายังไม่ออก ส่งใหม่ได้", "unconfirmed_clear") { confirming = true }
        return
    }
    val shape = RoundedCornerShape(16.dp)
    Column(modifier = Modifier.fillMaxWidth().clip(shape).background(MbWhite).border(1.dp, MbBorder, shape).padding(16.dp)) {
        val text = clearConfirm(pending)
        Text(text.named, color = MbNavyInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp, modifier = Modifier.testTag(text.tag))
        Spacer(modifier = Modifier.height(6.dp))
        Text(text.warning, color = MbNavyInk, fontSize = 14.sp, lineHeight = 20.sp)
        Spacer(modifier = Modifier.height(12.dp))
        PrimaryCta("ยืนยันส่งใหม่", "unconfirmed_clear_confirm", { stuck = !send.clearGuard(otherGuardKeys()) }, height = 44.dp)
        if (stuck) GuardNote("ล้างสถานะการส่งครั้งก่อนไม่สำเร็จ ลองใหม่ภายหลัง", "unconfirmed_clear_failed")
        Spacer(modifier = Modifier.height(4.dp))
        LinkRow("ยกเลิก", "unconfirmed_clear_cancel", color = MbBody) { confirming = false }
    }
}

@Composable
private fun GuardNote(line: String, tag: String) {
    Spacer(modifier = Modifier.height(6.dp))
    Text(line, color = MbEyebrow, fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 17.sp, modifier = Modifier.testTag(tag))
}

/**
 * Penpot 5.2 Collect Signatures for the payment [VaultSend.prepare] holds: the navy card of what leaves and where, "เซ็นแล้ว x / m" over m
 * bars, a row per key of the wallet in BSMS order, the checklist of what the payment does, and ต่อไป ตรวจสอบ, on only at
 * [VaultSend.readyToBroadcast], which opens the review that posts ([onReview]), with "ต้องเซ็นอีก k ดอก" under it. A row this phone signs
 * ([KeySigner.Phone]) has a filled เซ็น that asks the owner ([confirmSign]) and then Rust, a signed key shows 5.8's green เซ็นแล้ว ✓ and the
 * time this phone signed it, a Jade's or Safe 7's key ([KeySigner.Device]) an outlined เซ็น that opens its sign screen ([onSignOnDevice]),
 * its signed row reading "เซ็นบน Jade" or "เซ็นบน Trezor" with the time, any other key an outlined เซ็น that does nothing, with its reason
 * under its line, and a key the payment no longer needs ([VaultSend.needsKey]) no เซ็น at all. Once ready the bars turn green beside ครบแล้ว ส่งได้เลย, as 5.8 draws. A null [send],
 * or one with nothing prepared, shows one line.
 */
@Composable
fun CollectSignaturesScreen(
    send: VaultSend?,
    signers: List<KeySigner>,
    keyring: List<SavedKey>,
    quotes: QuoteCache,
    confirmSign: suspend () -> Boolean,
    onSignOnDevice: (KeySigner.Device) -> Unit,
    onReview: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    val payment = send?.payment
    if (send == null || payment == null) {
        MebitScreen(eyebrow = "SEND · WALLET", title = "เก็บลายเซ็น", onBack = onBack, onOpenCatalog = onOpenCatalog, tag = "screen_send/m2") {
            Text(NO_PAYMENT, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("m2_none"))
        }
        return
    }
    val vault = send.vault
    val quote = rememberFiatQuote(quotes)
    val scope = rememberCoroutineScope()
    val signed = send.signedFingerprints.size
    val needed = send.signaturesNeeded
    val ready = send.readyToBroadcast()
    MebitScreen(
        eyebrow = "SEND · ${vault.name}",
        title = "เก็บลายเซ็น",
        onBack = onBack?.let { back -> { if (send.busy == null) back() } },
        onOpenCatalog = onOpenCatalog,
        tag = "screen_send/m2",
        bottom = {
            Spacer(modifier = Modifier.height(12.dp))
            PrimaryCta("ต่อไป ตรวจสอบ", "cta_m2_review", onReview, enabled = ready && send.busy == null)
            Box(modifier = Modifier.fillMaxWidth().height(38.dp), contentAlignment = Alignment.Center) {
                if (needed > 0) Text("ต้องเซ็นอีก $needed ดอก", color = MbSlate, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.testTag("m2_needed"))
            }
        },
    ) {
        Spacer(modifier = Modifier.height(20.dp))
        PaymentCard(payment, quote)
        Spacer(modifier = Modifier.height(22.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("เซ็นแล้ว $signed / ${vault.m}", color = MbNavyInk, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("m2_progress"))
            Spacer(modifier = Modifier.weight(1f))
            if (ready) {
                Text("ครบแล้ว ส่งได้เลย", color = MbSuccess, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.testTag("m2_ready"))
            } else {
                Text("ใช้กุญแจดอกไหนก็ได้ ${vault.m} ดอก", color = MbSlate, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(vault.m) { i ->
                Box(modifier = Modifier.weight(1f).height(6.dp).clip(CircleShape).background(if (ready) MbSuccess else if (i < signed) MbNavy else MbChip))
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        KeyRows(send, signers, keyring) { signer ->
            when (signer) {
                is KeySigner.Phone -> scope.launch { send.sign(signer, confirmSign) }
                is KeySigner.Device -> onSignOnDevice(signer)
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        Checklist(payment, quote)
        // A device's request outlives the sign screen the user left, and holds the send until the device answers.
        val holding = (send.busy as? VaultBusy.Signing)?.keyIndex
        if (holding != null && signers.getOrNull(holding) is KeySigner.Device) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(BUSY, color = MbSlate, fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp, modifier = Modifier.testTag("m2_busy"))
        }
        send.lastError?.let {
            Spacer(modifier = Modifier.height(12.dp))
            Text(it, color = MbEyebrow, fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp, modifier = Modifier.testTag("m2_error"))
        }
    }
}

/** 5.2's navy card: "ส่ง", the amount in BTC with its baht beside it when there is a price, and "ถึง" the address's ends. */
@Composable
private fun PaymentCard(payment: VaultPayment, quote: com.mebit.app.data.FiatQuote?) {
    val amount = payment.amountSats.toLong()
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MbNavy).padding(horizontal = 20.dp, vertical = 16.dp).testTag("m2_summary"),
    ) {
        Text("ส่ง", color = MbWhite.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, lineHeight = 16.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(formatBtcCompact(amount), color = MbWhite, fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 38.sp, modifier = Modifier.testTag("m2_amount"))
            Spacer(modifier = Modifier.width(6.dp))
            Text("BTC", color = MbWhite.copy(alpha = 0.8f), fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 5.dp))
            Spacer(modifier = Modifier.weight(1f))
            fiatLine(amount, quote)?.let { Text(it, color = SummaryGold, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp).testTag("m2_fiat")) }
        }
        Text("ถึง ${shorten(payment.destination, "…")}", color = MbWhite.copy(alpha = 0.85f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp, modifier = Modifier.testTag("m2_to"))
    }
}

/**
 * 5.2's white card: a row per key of the wallet, in the order [signers] gives, which is the BSMS order. A signed key's line ends with the time
 * this phone signed it, and a key the payment no longer needs ([VaultSend.needsKey]) reads ไม่ต้องใช้รอบนี้ with no เซ็น, as 5.8 draws.
 */
@Composable
private fun KeyRows(send: VaultSend, signers: List<KeySigner>, keyring: List<SavedKey>, onSign: (KeySigner.Signable) -> Unit) {
    val vault = send.vault
    val texts = vaultKeyRows(vault, keyring)
    val xpubs = xpubsIn(vault.bsms)
    val phone = remember { getPlatform().phoneMonogram }
    val shape = RoundedCornerShape(20.dp)
    Column(modifier = Modifier.fillMaxWidth().clip(shape).background(MbWhite).border(1.dp, MbBorder, shape).padding(horizontal = 14.dp)) {
        signers.forEachIndexed { i, signer ->
            if (i > 0) Box(modifier = Modifier.fillMaxWidth().padding(start = 52.dp).height(1.dp).background(MbBorder))
            val source = keyring.find { it.xpub == xpubs.getOrNull(signer.index) && it.fingerprint.equals(vault.keyFingerprints.getOrNull(signer.index), ignoreCase = true) }?.source
            val signed = vault.keyFingerprints.getOrNull(signer.index)?.uppercase() in send.signedFingerprints
            val needed = !signed && send.needsKey(signer.index)
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("m2_key_${signer.index}"), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(MbIconWash), contentAlignment = Alignment.Center) {
                    Text(source?.let { keyMonogram(it, phone) } ?: "${signer.index + 1}", color = if (signer is KeySigner.Phone) MbNavy else MbEyebrow, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    val text = texts.getOrNull(signer.index)
                    Text(text?.name.orEmpty(), color = MbNavyInk, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (signed && signer is KeySigner.Device) "เซ็นบน ${deviceWord(signer.kind)}" else if (signed || needed) text?.line.orEmpty() else "ไม่ต้องใช้รอบนี้",
                            color = MbSlate,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false).testTag("m2_key_line_${signer.index}"),
                        )
                        // A Text of its own, so a long line is cut before the time is.
                        val at = vault.keyFingerprints.getOrNull(signer.index)?.let { send.signedAt[it.uppercase()] }
                        if (signed && at != null) Text(" · ${formatThaiTime(at)}", color = MbSlate, fontSize = 11.sp, maxLines = 1, modifier = Modifier.testTag("m2_signed_at_${signer.index}"))
                    }
                    if (needed && signer is KeySigner.Elsewhere) {
                        Text(signer.reason, color = MbBody, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.testTag("m2_key_reason_${signer.index}"))
                    }
                }
                if (signed || needed) {
                    Spacer(modifier = Modifier.width(8.dp))
                    SignButton(signer, signed, signing = send.busy == VaultBusy.Signing(signer.index), idle = send.busy == null, onSign)
                }
            }
        }
    }
}

/** A row's เซ็น: filled navy for a key this phone signs, outlined for a device's key, which opens its sign screen, and outlined and inert for any other, and 5.8's green เซ็นแล้ว ✓ once Rust found the key signed. */
@Composable
private fun SignButton(signer: KeySigner, signed: Boolean, signing: Boolean, idle: Boolean, onSign: (KeySigner.Signable) -> Unit) {
    val shape = RoundedCornerShape(17.dp)
    if (signed) {
        Box(modifier = Modifier.height(34.dp).clip(shape).background(MbGreenWash).padding(horizontal = 17.dp).testTag("m2_signed_${signer.index}"), contentAlignment = Alignment.Center) {
            Text("เซ็นแล้ว ✓", color = MbSuccess, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
        }
        return
    }
    val base = Modifier.size(width = 64.dp, height = 34.dp).clip(shape)
    val phone = signer as? KeySigner.Phone
    val look = when (signer) {
        is KeySigner.Phone -> base.background(MbNavy).clickable(enabled = idle, role = Role.Button) { onSign(signer) }
        is KeySigner.Device -> base.border(1.5.dp, MbNavy, shape).clickable(enabled = idle, role = Role.Button) { onSign(signer) }
        is KeySigner.Elsewhere -> base.border(1.5.dp, MbNavy, shape).alpha(0.4f)
    }
    Box(modifier = look.testTag("cta_m2_sign_${signer.index}"), contentAlignment = Alignment.Center) {
        Text(if (signing) "…" else "เซ็น", color = if (phone != null) MbWhite else MbNavy, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/** 5.2's grey card: the destination's ends, the change back to the wallet when there is any, and the fee in sats and baht. */
@Composable
private fun Checklist(payment: VaultPayment, quote: com.mebit.app.data.FiatQuote?) {
    val shape = RoundedCornerShape(16.dp)
    Column(modifier = Modifier.fillMaxWidth().clip(shape).background(MbChip).padding(horizontal = 16.dp, vertical = 10.dp)) {
        CheckRow("ปลายทาง", shorten(payment.destination, "…"), "m2_check_destination")
        if (payment.changeSats > 0uL) CheckRow("เงินทอนกลับเข้า Wallet", "${formatBtc(payment.changeSats.toLong())} BTC", "m2_check_change")
        CheckRow("ค่าธรรมเนียม", realFeeText(payment.feeSats.toLong(), quote), "m2_check_fee")
    }
}

@Composable
private fun CheckRow(label: String, value: String, tag: String) {
    Row(modifier = Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(MbSuccess), contentAlignment = Alignment.Center) {
            Text("✓", color = MbWhite, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(label, color = MbBody, fontSize = 13.sp)
        Spacer(modifier = Modifier.weight(1f))
        Text(value, color = MbNavyInk, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag(tag))
    }
}

/**
 * The page after 5.2, drawn as Penpot 4.5 draws a one-key payment's review ([ReviewPage]): what leaves and where to, from this wallet, the
 * fee, what leaves it in all, and what it keeps of [balanceSats], the balance of its last read, a dash while unknown. ส่งธุรกรรม finishes and
 * posts the payment ([VaultSend.broadcast]) and is on only at [VaultSend.readyToBroadcast], with no owner check of its own because each
 * signature had one. ยกเลิก and Back return to 5.2 with the signatures kept. A refusal, an unknown outcome and the second tap that posts the
 * same bytes again read as they did on 5.2. Once the payment is out it calls [onSent] once. A null [send], or one with nothing prepared,
 * shows one line.
 */
@Composable
fun VaultReviewScreen(
    send: VaultSend?,
    balanceSats: Long?,
    quotes: QuoteCache,
    onSent: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    val state = send?.state
    LaunchedEffect(state) { if (state is VaultSendState.Sent) onSent() }
    val payment = send?.payment
    if (send == null || payment == null) {
        MebitScreen(eyebrow = "SEND · ตรวจสอบ", title = "ตรวจก่อนส่ง", onBack = onBack, onOpenCatalog = onOpenCatalog, tag = "screen_send/m8") {
            Text(NO_REVIEW, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("m8_none"))
        }
        return
    }
    val quote = rememberFiatQuote(quotes)
    val scope = rememberCoroutineScope()
    // Back and ยกเลิก wait while the post runs, so no screen is left with a payment half out.
    val leave = onBack?.let { back -> { if (send.busy == null) back() } }
    val amount = payment.amountSats.toLong()
    val fee = payment.feeSats.toLong()
    ReviewPage(
        tag = "screen_send/m8",
        onBack = leave,
        onOpenCatalog = onOpenCatalog,
        amountSats = amount,
        sendAll = send.draft.isSendAll,
        estimate = false,
        destination = payment.destination,
        from = send.vault.name,
        fee = realFeeText(fee, quote),
        totalSats = amount + fee,
        balanceSats = balanceSats,
        quote = quote,
        bottom = {
            BottomActions(
                primaryLabel = if (send.busy == VaultBusy.Posting) "กำลังส่ง…" else "ส่งธุรกรรม",
                primaryTag = "cta_m8_send",
                onPrimary = { scope.launch { withContext(Dispatchers.Default) { send.broadcast() } } },
                linkLabel = "ยกเลิก",
                linkTag = "cta_m8_cancel",
                onLink = leave,
                enabled = send.readyToBroadcast() && send.busy == null,
                linkColor = MbBody,
            )
        },
    ) {
        send.lastError?.let {
            Spacer(modifier = Modifier.height(12.dp))
            Text(it, color = MbEyebrow, fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp, modifier = Modifier.testTag("m8_error"))
        }
    }
}

/**
 * Penpot 5.9 Sent · wallet, S6's slip ([SentSlip]) for the payment this send put out: when the node took it, the amount and its baht, from
 * this wallet, to its destination, the fee at the rate picked on 5.1, the txid, แชร์สลิป, and กลับหน้าหลัก ([onHome]), which the system Back
 * also does. Only a payment that went out has a slip, so anything else shows one line.
 */
@Composable
fun VaultSentScreen(send: VaultSend?, quotes: QuoteCache, onHome: () -> Unit, onBack: (() -> Unit)?, onOpenCatalog: () -> Unit) {
    val sent = send?.state as? VaultSendState.Sent
    if (send == null || sent == null) {
        MebitScreen(eyebrow = "SENT · WALLET", title = "สลิปส่ง Bitcoin", onBack = onBack, onOpenCatalog = onOpenCatalog, tag = "screen_send/m9") {
            Text(NO_SLIP, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("m9_none"))
        }
        return
    }
    SystemBack(onHome)
    SentSlip(
        tag = "screen_send/m9",
        txid = sent.payment.txid,
        sentAtSeconds = sent.atSeconds,
        amountSats = sent.payment.amountSats.toLong(),
        feeSats = sent.payment.feeSats.toLong(),
        rateSatVb = send.draft.rateSatVb,
        destination = sent.payment.destination,
        walletName = send.vault.name,
        quotes = quotes,
        onHome = onHome,
        onOpenCatalog = onOpenCatalog,
    )
}

/** Draft copy, no board draws these. */
private const val NO_SEND = "ยังส่งจาก Wallet นี้ไม่ได้ · กลับไปหน้าก่อน"
internal const val NO_PAYMENT = "ยังไม่มีรายการให้เซ็น · กลับไปหน้าก่อน"
private const val NO_REVIEW = "ยังไม่มีรายการให้ส่ง · กลับไปหน้าก่อน"
private const val NO_SLIP = "ยังไม่มีรายการที่ส่งออก · กลับไปหน้าก่อน"
private const val BALANCE_UNKNOWN = "ยังดึงยอดที่ใช้ได้ของ Wallet นี้ไม่ได้ จึงยังส่งไม่ได้ ลองใหม่ภายหลัง"
private const val PAYMENT_UNCONFIRMED = "การส่งครั้งก่อนจาก Wallet นี้ยังยืนยันไม่ได้ เปิดรายละเอียด Wallet เพื่อตรวจรายการก่อนส่งอีกครั้ง"
