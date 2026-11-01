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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.data.FeeTier
import com.mebit.app.data.SendDraft
import com.mebit.app.data.SendIssue
import com.mebit.app.data.SingleSigWallet
import com.mebit.app.data.enterDestination
import com.mebit.app.data.formatBtc
import com.mebit.app.data.sendIssue
import com.mebit.app.ui.components.MebitScreen
import com.mebit.app.ui.components.PrimaryCta
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbBorder
import com.mebit.app.ui.theme.MbBtcOrange
import com.mebit.app.ui.theme.MbChip
import com.mebit.app.ui.theme.MbEyebrow
import com.mebit.app.ui.theme.MbNavy
import com.mebit.app.ui.theme.MbNavyInk
import com.mebit.app.ui.theme.MbSlate
import com.mebit.app.ui.theme.MbWhite
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.isValidAddress

private val Placeholder = Color(0xFFB4BAC4)
private val SelectedFee = Color(0xFFFFF8EF)

/**
 * Penpot 4.4 Send, with 4.4.1 (address filled), 4.4.4 (send all), 4.4.6 (insufficient funds) and 4.4.7
 * (invalid address) as states of it. Left out on purpose: the QR scan button and the ฿ unit toggle (no
 * camera in the MVP), and every baht figure (no price feed): fees show in sats where the board shows ฿.
 */
@Composable
fun S4SendScreen(
    wallet: SingleSigWallet,
    draft: SendDraft,
    onContinue: () -> Unit,
    onReceive: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    val addressOk = if (draft.destination.isEmpty()) null else isValidAddress(draft.destination, KeyNetwork.TEST)
    val issue = sendIssue(addressOk, draft.amountSats, draft.feeSats, wallet.balanceSats)
    MebitScreen(
        eyebrow = "SEND · กระเป๋าใช้จ่าย",
        title = null,
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_send/s4",
        bottom = {
            Spacer(modifier = Modifier.height(12.dp))
            PrimaryCta("ตรวจสอบ", "cta_s4_review", onContinue, enabled = addressOk == true && draft.amountSats > 0 && issue == null)
            // The boards put a link under this button. Keep its room so the button sits where they put it.
            Spacer(modifier = Modifier.height(38.dp))
        },
    ) {
        ReceiveSendSwitch(sendSelected = true, onReceive = onReceive, onSend = {})
        Spacer(modifier = Modifier.height(20.dp))
        DestinationField(draft, invalid = issue == SendIssue.InvalidAddress)
        Spacer(modifier = Modifier.height(14.dp))
        AmountCard(wallet, draft, short = issue is SendIssue.Insufficient)
        Spacer(modifier = Modifier.height(20.dp))
        Text("ค่าธรรมเนียมเครือข่าย", color = MbNavyInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp)
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FeeTier.entries.forEach { tier ->
                FeeCard(tier, selected = tier == draft.tier, modifier = Modifier.weight(1f)) { draft.chooseTier(tier) }
            }
        }
        when (issue) {
            SendIssue.InvalidAddress -> {
                Spacer(modifier = Modifier.height(20.dp))
                IssuePanel("INVALID ADDRESS", "ที่อยู่ไม่ถูกต้อง — ตรวจตัวอักษรและเครือข่าย (รองรับ Bitcoin เท่านั้น) แล้ววางใหม่")
            }
            is SendIssue.Insufficient -> {
                Spacer(modifier = Modifier.height(20.dp))
                IssuePanel("INSUFFICIENT FUNDS", "ยอดไม่พอ — ขาดอีก ${formatBtc(issue.missingSats, 4)} BTC (รวมค่าธรรมเนียม) ลดจำนวน หรือกดส่งทั้งหมด")
            }
            null -> Unit
        }
    }
}

@Composable
private fun DestinationField(draft: SendDraft, invalid: Boolean) {
    val clipboard = LocalClipboardManager.current
    val enter = { text: String -> draft.enterDestination(text) { isValidAddress(it, KeyNetwork.TEST) } }
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
                modifier = Modifier.fillMaxWidth().testTag("s4_address"),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
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
private fun AmountCard(wallet: SingleSigWallet, draft: SendDraft, short: Boolean) {
    val shape = RoundedCornerShape(22.dp)
    val amount = TextStyle(color = MbNavyInk, fontSize = 44.sp, fontWeight = FontWeight.Bold, lineHeight = 56.sp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(168.dp)
            .clip(shape)
            .background(MbWhite)
            .border(if (short) 1.5.dp else 1.dp, if (short) MbBtcOrange else MbBorder, shape)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(modifier = Modifier.height(14.dp))
        Text("จำนวน", color = MbSlate, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, lineHeight = 16.sp)
        Spacer(modifier = Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            BasicTextField(
                value = draft.amountText,
                onValueChange = { if (Regex("""\d{0,8}(\.\d{0,8})?""").matches(it)) draft.setAmountBtc(it) },
                textStyle = amount,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                decorationBox = { inner ->
                    Box {
                        if (draft.amountText.isEmpty()) Text("0.0000", style = amount.copy(color = Placeholder))
                        inner()
                    }
                },
                modifier = Modifier.width(IntrinsicSize.Min).widthIn(min = 40.dp).testTag("s4_amount"),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("BTC", color = MbSlate, fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp, modifier = Modifier.padding(bottom = 12.dp))
        }
        // The board's baht line sits here: no price feed, so the room stays and the divider keeps its place.
        Spacer(modifier = Modifier.height(36.dp))
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MbBorder))
        Spacer(modifier = Modifier.height(11.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = "ใช้ได้ ${formatBtc(wallet.balanceSats, 4)} BTC" + if (short) " · ไม่พอ" else "",
                color = if (short) MbEyebrow else MbBody,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 17.sp,
            )
            Text(
                text = "ส่งทั้งหมด",
                color = MbNavy,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 17.sp,
                modifier = Modifier
                    .clickable(role = Role.Button) {
                        val all = (wallet.balanceSats - draft.feeSats).coerceAtLeast(0)
                        draft.setAmountBtc(if (all == 0L) "" else formatBtc(all).trimEnd('0').trimEnd('.'))
                    }
                    .testTag("s4_send_all"),
            )
        }
    }
}

@Composable
private fun FeeCard(tier: FeeTier, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
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
        Text("${thousands(tier.sats)} sat", color = if (selected) MbEyebrow else MbNavyInk, fontSize = 15.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp)
        Spacer(modifier = Modifier.height(1.dp))
        Text("${tier.satPerVb} sat/vB", color = MbSlate, fontSize = 10.5.sp, fontWeight = FontWeight.Medium, lineHeight = 14.sp)
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
