package com.mebit.app.feature.send

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.data.OnboardingSession
import com.mebit.app.data.SingleSigWallet
import com.mebit.app.resources.Res
import com.mebit.app.resources.ic_warning
import com.mebit.app.ui.components.BottomActions
import com.mebit.app.ui.components.MebitScreen
import com.mebit.app.ui.components.PrimaryCta
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbBorder
import com.mebit.app.ui.theme.MbEyebrow
import com.mebit.app.ui.theme.MbIconWash
import com.mebit.app.ui.theme.MbNavyInk
import com.mebit.app.ui.theme.MbWhite
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource

private val AddressGoldInk = Color(0xFFC2700F)
private val Grabber = Color(0xFFC9CFD8)

/**
 * Penpot 4.2: tapping รับ before the backup shows the home dimmed behind a sheet. Backed up, there is
 * nothing to ask, so it goes straight on to the address.
 */
@Composable
fun S2ReceiveBackupGateScreen(
    wallet: SingleSigWallet,
    session: OnboardingSession,
    onBackedUp: () -> Unit,
    onGoBackup: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    LaunchedEffect(session.backupConfirmed) { if (session.backupConfirmed) onBackedUp() }
    Box(modifier = Modifier.fillMaxSize().testTag("screen_send/s2")) {
        SingleSigHome(wallet, session, onReceive = {}, onSend = {}, onVault = {}, onOpenCatalog = {}, tag = "s2_home_behind")
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
 * Penpot 4.3 Receive. Left out: the QR block (no encoder here, and a drawing that only looks like a code
 * would be worse than none), the share button (no share sheet) and the line about a fresh address every
 * time (the hot wallet watches and spends the first address only, so it does not rotate).
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
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
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
            PrimaryCta(
                label = if (copied) "คัดลอกแล้ว" else "คัดลอกที่อยู่",
                tag = "cta_s3_copy",
                onClick = {
                    clipboard.setText(AnnotatedString(wallet.receiveAddress))
                    copied = true
                },
            )
            Spacer(modifier = Modifier.height(38.dp))
        },
    ) {
        ReceiveSendSwitch(sendSelected = false, onReceive = {}, onSend = onSend)
        Spacer(modifier = Modifier.height(20.dp))
        val card = RoundedCornerShape(24.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(4.dp, card, clip = false)
                .clip(card)
                .background(MbWhite)
                .border(1.dp, MbBorder, card)
                .padding(horizontal = 32.dp, vertical = 28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = groupedFull(wallet.receiveAddress),
                color = MbNavyInk,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 24.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("ss_receive_address"),
            )
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
                Text("ถ้าเลือก BEP20 หรือ ERC20 เหรียญจะไม่เข้ากระเป๋านี้", color = MbEyebrow, fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 15.sp)
            }
        }
    }
}

/** The whole address in four-letter groups, the second and the last two picked out in gold, as the board has it. */
private fun groupedFull(address: String): AnnotatedString = buildAnnotatedString {
    val groups = address.chunked(4)
    groups.forEachIndexed { i, group ->
        if (i > 0) append(' ')
        val gold = i == 1 || i >= groups.size - 2 && groups.size > 4
        withStyle(SpanStyle(color = if (gold) AddressGoldInk else MbNavyInk)) { append(group) }
    }
}
