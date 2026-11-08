package com.mebit.app.feature.send

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.QrCode
import com.mebit.app.data.OnboardingSession
import com.mebit.app.data.QuoteCache
import com.mebit.app.data.SingleSigWallet
import com.mebit.app.data.SingleSigWallet.WalletMode
import com.mebit.app.imageOfCard
import com.mebit.app.rememberSharer
import com.mebit.app.resources.Res
import com.mebit.app.resources.ic_warning
import com.mebit.app.resources.mebit_logo
import com.mebit.app.resources.mebit_logo_dark
import com.mebit.app.ui.components.BottomActions
import com.mebit.app.ui.components.MebitScreen
import com.mebit.app.ui.components.PrimaryCta
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbBorder
import com.mebit.app.ui.theme.MbBtcOrange
import com.mebit.app.ui.theme.MbEyebrow
import com.mebit.app.ui.theme.MbGold
import com.mebit.app.ui.theme.MbIconWash
import com.mebit.app.ui.theme.MbNavy
import com.mebit.app.ui.theme.MbNavyInk
import com.mebit.app.ui.theme.MbPage
import com.mebit.app.ui.theme.MbWhite
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

private val AddressGoldInk = Color(0xFFC2700F)
private val Grabber = Color(0xFFC9CFD8)
private val SharePictureBadge = Color(0xFFEDF4FA)
private val ShareMuted = Color(0xFF687586)
private val ShareCardFill = Color(0xFFF6F8FA)
private val ShareTileFill = Color(0xFFEAF1F5)
private val SheetHandle = Color(0xFFD7DEE5)

/**
 * Penpot 4.2: tapping รับ before the backup shows the home dimmed behind a sheet. Backed up, there is
 * nothing to ask, so it goes straight on to the address.
 */
@Composable
fun S2ReceiveBackupGateScreen(
    wallet: SingleSigWallet,
    session: OnboardingSession,
    quotes: QuoteCache,
    onBackedUp: () -> Unit,
    onGoBackup: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    LaunchedEffect(session.backupConfirmed) { if (session.backupConfirmed) onBackedUp() }
    Box(modifier = Modifier.fillMaxSize().testTag("screen_send/s2")) {
        SingleSigHome(wallet, session, quotes, onReceive = {}, onSend = {}, onVault = {}, onHistory = {}, onOpenTx = {}, onOpenCatalog = {}, tag = "s2_home_behind")
        // The scrim swallows every tap, and a tap on it closes the sheet like the cancel link does.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MbNavyInk.copy(alpha = 0.5f))
                .pointerInput(Unit) { detectTapGestures { onBack?.invoke() } },
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(404.dp)
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(MbWhite)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(horizontal = 20.dp),
        ) {
            Box(modifier = Modifier.padding(top = 10.dp).align(Alignment.CenterHorizontally).size(width = 40.dp, height = 5.dp).background(Grabber, CircleShape))
            Spacer(modifier = Modifier.height(21.dp))
            Box(modifier = Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(MbIconWash), contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.size(width = 22.dp, height = 26.dp).border(2.dp, AddressGoldInk, RoundedCornerShape(4.dp)))
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text("สำรองกุญแจก่อนรับเงิน", color = MbNavyInk, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 29.sp)
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "ถ้าเครื่องหายตอนยังไม่ได้สำรอง เงินที่รับเข้ามาจะหายไปด้วย และ mebit กู้ให้ไม่ได้ จด 24 คำลงกระดาษ ใช้เวลาราว 5 นาที",
                color = MbBody,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            Spacer(modifier = Modifier.weight(1f))
            BottomActions("สำรองตอนนี้", "cta_s2_backup", onGoBackup, "ยกเลิก", "cta_s2_cancel", onBack, linkColor = MbBody)
            // The sheet runs to the screen edge: clear the home indicator and the usual 16 dp.
            Spacer(modifier = Modifier.height(50.dp))
        }
    }
}

/**
 * Penpot 4.3 Receive. The address is the first one the chain has not used, so it changes after money arrives at it,
 * and the board's line about a fresh address shows only for a live wallet (C6, see open item 60). Left out: the logo
 * over the QR (it would cover data modules, and each one spends error correction), and QR and share for the demo
 * wallet (money sent to its made-up address is lost). A real key whose balance could not be fetched keeps them,
 * because its address is derived from the key offline and is index 0, which the wallet still sees and spends. With
 * no address at all, copy is off too. แชร์ opens Penpot's "4.3 Share Choice" sheet: the address as text, or the board
 * "4.3 Share QR" as a picture (QR and address, no fresh-address note), so a friend can scan it and check the address by eye.
 */
@Composable
fun S3ReceiveScreen(
    wallet: SingleSigWallet,
    session: OnboardingSession,
    onSend: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val sharer = rememberSharer()
    val scope = rememberCoroutineScope()
    val pictureLayer = rememberGraphicsLayer()
    val frame = rememberGraphicsLayer()
    var choosing by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }
    val copyLabel = if (copied) "คัดลอกแล้ว" else "คัดลอกที่อยู่"
    val copy = {
        clipboard.setText(AnnotatedString(wallet.receiveAddress))
        copied = true
    }
    val share = { send: suspend () -> Unit ->
        choosing = false
        scope.launch {
            // shortcut: a share that fails does nothing, because the board has no error for it and a crash on a tap is worse. Add one when it does.
            try {
                send()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }
    MebitScreen(
        eyebrow = "RECEIVE · กระเป๋าใช้จ่าย",
        title = null,
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_send/s3",
        bottom = {
            Spacer(modifier = Modifier.height(12.dp))
            if (wallet.addressIsReal) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.weight(1f)) {
                        PrimaryCta(label = copyLabel, tag = "cta_s3_copy", onClick = copy, height = 52.dp)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .clip(CircleShape)
                            .background(MbWhite)
                            .border(1.5.dp, MbNavy, CircleShape)
                            .clickable(role = Role.Button) { choosing = true }
                            .testTag("cta_s3_share"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("แชร์", color = MbNavy, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                // 40 dp, not the 38 under the single 54 dp button: this row is 2 dp shorter and the board puts its top at y 732.
                Spacer(modifier = Modifier.height(40.dp))
            } else {
                PrimaryCta(label = copyLabel, tag = "cta_s3_copy", onClick = copy, enabled = wallet.receiveAddress.isNotEmpty())
                Spacer(modifier = Modifier.height(38.dp))
            }
        },
    ) {
        ReceiveSendSwitch(sendSelected = false, onReceive = {}, onSend = onSend)
        Spacer(modifier = Modifier.height(20.dp))
        val card = RoundedCornerShape(24.dp)
        val cardLook = Modifier
            .shadow(4.dp, card, clip = false)
            .clip(card)
            .background(MbWhite)
            .border(1.dp, MbBorder, card)
        if (wallet.addressIsReal) {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(min = 335.dp).then(cardLook).padding(horizontal = 32.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                QrCode(
                    text = wallet.receiveAddress,
                    modifier = Modifier.size(196.dp).semantics { contentDescription = "QR ของที่อยู่รับเงิน" }.testTag("ss_receive_qr"),
                    dark = MbNavy,
                )
                Spacer(modifier = Modifier.height(18.8.dp))
                ReceiveAddress(wallet.receiveAddress)
                if (wallet.mode == WalletMode.Live) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "ที่อยู่ใหม่ทุกครั้งเพื่อความเป็นส่วนตัว ที่อยู่เก่ายังรับได้",
                        color = MbBody,
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("receive_fresh_note"),
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier.fillMaxWidth().then(cardLook).padding(horizontal = 32.dp, vertical = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                ReceiveAddress(wallet.receiveAddress)
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(16.dp)).background(MbIconWash).padding(horizontal = 16.dp).testTag("network_warning"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(painterResource(Res.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text("เลือกเครือข่าย Bitcoin (BTC) เท่านั้น", color = MbNavyInk, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, lineHeight = 18.sp)
                Spacer(modifier = Modifier.height(4.8.dp))
                Text("ถ้าเลือก BEP20 หรือ ERC20 เหรียญจะไม่เข้ากระเป๋านี้", color = MbEyebrow, fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 15.sp)
            }
        }
        if (wallet.addressIsReal) SharePicture(wallet.receiveAddress, pictureLayer)
    }
    if (choosing) {
        ShareChoiceSheet(
            onDismiss = { choosing = false },
            onAddress = { share { sharer.text(wallet.receiveAddress) } },
            onQr = { share { sharer.imageOfCard(pictureLayer, frame, MbPage, "mebit-receive-" + wallet.receiveAddress.takeLast(8)) } },
        )
    }
}

/** Penpot 4.3 Share Choice: what to send, the address as text or the QR as a picture. Tapping the scrim, dragging down or going back shares nothing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareChoiceSheet(onDismiss: () -> Unit, onAddress: () -> Unit, onQr: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MbWhite,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        scrimColor = MbNavyInk.copy(alpha = 0.33f),
        dragHandle = null,
        modifier = Modifier.testTag("sheet_share_choice"),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Box(modifier = Modifier.padding(top = 12.dp).align(Alignment.CenterHorizontally).size(width = 50.dp, height = 4.dp).background(SheetHandle, RoundedCornerShape(2.dp)))
            Spacer(modifier = Modifier.height(8.dp))
            Text("แชร์อะไรดี?", color = MbNavy, fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 24.3.sp)
            Spacer(modifier = Modifier.height(3.7.dp))
            Text("เลือกสิ่งที่ต้องการส่งต่อ", color = ShareMuted, fontSize = 12.sp, lineHeight = 16.2.sp)
            Spacer(modifier = Modifier.height(9.8.dp))
            ShareOption("Aa", 12.sp, "Address", "แชร์เป็นข้อความ", "cta_s3_share_text", onAddress)
            Spacer(modifier = Modifier.height(10.dp))
            ShareOption("QR", 10.sp, "QR Code", "แชร์เป็นรูปภาพ", "cta_s3_share_qr", onQr)
            // The board's 90 dp below the cards includes the 34 pt home indicator, which the sheet's own insets clear.
            Spacer(modifier = Modifier.height(56.dp))
        }
    }
}

@Composable
private fun ShareOption(tile: String, tileSize: TextUnit, title: String, detail: String, tag: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(ShareCardFill)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(ShareTileFill).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
            Text(tile, color = MbNavy, fontSize = tileSize, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(title, color = MbNavyInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.9.sp)
            Spacer(modifier = Modifier.height(6.dp))
            Text(detail, color = ShareMuted, fontSize = 11.5.sp, lineHeight = 15.5.sp)
        }
    }
}

@Composable
private fun ReceiveAddress(address: String) {
    Text(
        text = groupedFull(address),
        color = MbNavyInk,
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 24.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.testTag("ss_receive_address"),
    )
}

/**
 * Penpot "4.3 Share QR · picture": the card [Sharer.imageOfCard] shares, laid out at the board's 354 dp. It takes no space and is
 * only recorded into [layer], never drawn to the screen, so the screen's own card and fresh-address note stay as they are.
 */
@Composable
private fun SharePicture(address: String, layer: GraphicsLayer) {
    Column(
        modifier = Modifier
            .layout { measurable, constraints ->
                val width = 354.dp.roundToPx()
                val placeable = measurable.measure(Constraints(minWidth = width, maxWidth = width))
                layout(0, 0) { placeable.place(0, 0) }
            }
            .drawWithContent { layer.record { this@drawWithContent.drawContent() } }
            .clearAndSetSemantics {}
            .clip(RoundedCornerShape(24.dp))
            .background(MbWhite),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(84.dp).background(SlipNavy), contentAlignment = Alignment.Center) {
            Image(painterResource(Res.drawable.mebit_logo), contentDescription = null, modifier = Modifier.size(width = 129.dp, height = 34.dp))
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.height(28.dp).background(SharePictureBadge, RoundedCornerShape(14.dp)).padding(start = 8.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(18.dp).background(MbBtcOrange, CircleShape), contentAlignment = Alignment.Center) {
                Text("₿", color = MbWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text("Bitcoin", color = SlipNavy, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        QrCode(address, Modifier.size(256.dp), dark = MbNavy)
        Text(
            text = "สแกน QR เพื่อส่ง Bitcoin",
            color = AddressGoldInk,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 23.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = groupedFull(address, SlipNavy),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 22.4.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(modifier = Modifier.height(20.dp))
        Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(MbGold))
        Row(modifier = Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(Res.drawable.mebit_logo_dark), contentDescription = null, modifier = Modifier.size(width = 69.dp, height = 18.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text("Bitcoin network only | รับเฉพาะเครือข่าย Bitcoin", color = SlipMuted, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, lineHeight = 15.5.sp)
        }
    }
}

/** The whole address in four-letter groups, the second and the last two picked out in gold, as the board has it. */
private fun groupedFull(address: String, ink: Color = MbNavyInk): AnnotatedString = buildAnnotatedString {
    val groups = address.chunked(4)
    groups.forEachIndexed { i, group ->
        if (i > 0) append(' ')
        val gold = i == 1 || i >= groups.size - 2 && groups.size > 4
        withStyle(SpanStyle(color = if (gold) AddressGoldInk else ink)) { append(group) }
    }
}
