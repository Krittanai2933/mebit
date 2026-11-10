package com.mebit.app.feature.send

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.data.FiatQuote
import com.mebit.app.data.QuoteCache
import com.mebit.app.data.SendDraft
import com.mebit.app.data.SingleSigWallet
import com.mebit.app.data.formatBtcCompact
import com.mebit.app.data.rememberFiatQuote
import com.mebit.app.formatThaiDateTime
import com.mebit.app.imageOfCard
import com.mebit.app.rememberSharer
import com.mebit.app.resources.Res
import com.mebit.app.resources.ic_tick_white
import com.mebit.app.resources.mebit_logo
import com.mebit.app.ui.components.DebugLink
import com.mebit.app.ui.theme.MbSuccess
import com.mebit.app.ui.theme.MbWhite
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

internal val SlipPage = Color(0xFFEFF4FA)
internal val SlipNavy = Color(0xFF073453)
internal val SlipMuted = Color(0xFF687E90)
private val SlipFiat = Color(0xFF56748A)
private val SlipHairline = Color(0xFFEDF2F6)

/**
 * Penpot 4.6 Sent · slip: the card, its date line (only after a send in this run) and baht line (only with a price),
 * the "แชร์สลิป" button that shares the card as a picture, and the link home.
 */
@Composable
fun SentSlip(wallet: SingleSigWallet, draft: SendDraft, quotes: QuoteCache, walletName: String, onHome: () -> Unit, onOpenCatalog: () -> Unit) {
    val quote = rememberFiatQuote(quotes)
    val card = rememberGraphicsLayer()
    val frame = rememberGraphicsLayer()
    val sharer = rememberSharer()
    val scope = rememberCoroutineScope()
    Box(modifier = Modifier.fillMaxSize().background(SlipPage).testTag("screen_send/s6_slip")) {
        Column(
            // Scrolls so the share button and the link home cannot fall off a screen shorter than the card plus both buttons (open item 48).
            // The bottom padding is the debug link's height (about 24 dp of Thai text at 11 sp) plus 8 dp, so the link home sits clear of it when the column ends at the screen bottom.
            modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = 54.dp, bottom = 32.dp),
        ) {
            Slip(wallet, draft, quote, walletName, card)
            Spacer(modifier = Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(SlipNavy)
                    .clickable(role = Role.Button) {
                        scope.launch {
                            // shortcut: a share that fails does nothing, because the board has no error for it and a crash right after a payment is worse. Add one when it does.
                            try {
                                val name = if (wallet.lastTxid.isEmpty()) "mebit-slip" else "mebit-slip-" + wallet.lastTxid.take(8)
                                sharer.imageOfCard(card, frame, SlipPage, name)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                            }
                        }
                    }
                    .testTag("cta_s6_share"),
                contentAlignment = Alignment.Center,
            ) {
                Text("แชร์สลิป", color = MbWhite, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(9.dp))
            Box(
                modifier = Modifier.fillMaxWidth().height(29.dp).clickable(role = Role.Button, onClick = onHome).testTag("cta_s6_home"),
                contentAlignment = Alignment.Center,
            ) {
                Text("กลับหน้าหลัก", color = SlipNavy, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        DebugLink(onClick = onOpenCatalog)
    }
}

@Composable
private fun Slip(wallet: SingleSigWallet, draft: SendDraft, quote: FiatQuote?, walletName: String, layer: GraphicsLayer) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(592.dp)
            .shadow(14.dp, shape, clip = false)
            // Recorded between the shadow and the clip: the picture keeps the rounded corners and leaves the shadow out.
            .drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            }
            .clip(shape)
            .background(MbWhite),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(100.dp).background(SlipNavy).padding(horizontal = 24.dp).padding(bottom = 36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(painterResource(Res.drawable.mebit_logo), contentDescription = "mebit", modifier = Modifier.size(width = 92.dp, height = 24.dp))
            Spacer(modifier = Modifier.weight(1f))
            Text("สลิปส่ง Bitcoin", color = Color(0xFFDCEAF4), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = 70.dp)
                .size(60.dp)
                .background(MbSuccess, CircleShape)
                .border(5.dp, MbWhite, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(Res.drawable.ic_tick_white), contentDescription = null, modifier = Modifier.size(34.dp))
        }
        Text(
            text = "ส่งสำเร็จ",
            color = SlipNavy,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 27.sp,
            modifier = Modifier.align(Alignment.TopCenter).offset(y = 143.dp),
        )
        if (wallet.lastSentAtSeconds > 0) {
            Text(
                text = formatThaiDateTime(wallet.lastSentAtSeconds),
                color = SlipMuted,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 17.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().offset(y = 172.dp).testTag("slip_date"),
            )
        }
        Text(
            text = formatBtcCompact(wallet.lastAmountSats) + " BTC",
            color = SlipNavy,
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 56.sp,
            modifier = Modifier.align(Alignment.TopCenter).offset(y = 202.dp).testTag("send_amount"),
        )
        fiatLine(wallet.lastAmountSats, quote)?.let {
            Text(
                text = it,
                color = SlipFiat,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().offset(y = 255.dp).testTag("slip_fiat"),
            )
        }
        Canvas(modifier = Modifier.offset(x = 24.dp, y = 297.dp).width(306.dp).height(2.dp)) {
            drawLine(
                color = Color(0xFFD6E1E9),
                start = Offset(0f, 1f),
                end = Offset(size.width, 1f),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 9.6.dp.toPx())),
            )
        }
        SlipRow("จาก", walletName, top = 320.dp)
        SlipHairline(354.dp)
        SlipRow("ถึง", shorten(draft.destination, "…"), top = 360.dp, tag = "slip_to")
        SlipHairline(394.dp)
        // The fee the signed transaction pays, at the rate the user chose. PreparedSend carries no size, so the rate is the chosen one, not fee over size.
        SlipRow("ค่าธรรมเนียม", slipFeeText(wallet.lastFeeSats, draft.rateSatVb, quote), top = 400.dp)
        SlipHairline(434.dp)
        Text("สถานะ", color = SlipMuted, fontSize = 13.5.sp, lineHeight = 19.sp, modifier = Modifier.offset(x = 24.dp, y = 440.dp))
        Row(
            modifier = Modifier
                .offset(x = 207.dp, y = 439.dp)
                .width(130.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFFEDF4FA))
                .padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).background(Color(0xFFE8BB33), CircleShape))
            Spacer(modifier = Modifier.width(8.dp))
            Text("รอยืนยันในบล็อก", color = SlipNavy, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp)
        }
        SlipHairline(474.dp)
        SlipRow("TXID", wallet.lastTxid.ifEmpty { "—" }.let { shorten(it, "...") }, top = 480.dp, tag = "s6_txid")
        SlipHairline(532.dp)
        Text(
            text = "ตรวจสอบได้บน blockchain · ออกโดย mebit",
            color = SlipMuted,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().offset(y = 550.dp),
        )
    }
}

@Composable
private fun SlipRow(label: String, value: String, top: Dp, tag: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().offset(y = top).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, color = SlipMuted, fontSize = 13.5.sp, lineHeight = 19.sp)
        Spacer(modifier = Modifier.weight(1f))
        Text(value, color = SlipNavy, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp, modifier = if (tag != null) Modifier.testTag(tag) else Modifier)
    }
}

@Composable
private fun SlipHairline(top: Dp) {
    Box(modifier = Modifier.offset(x = 24.dp, y = top).fillMaxWidth().padding(end = 48.dp).height(1.dp).background(SlipHairline))
}

/** "tb1qw5…jzsx": first four, last four. Short strings stay whole. */
internal fun shorten(text: String, dots: String): String =
    if (text.length <= 10) text else text.take(4) + " " + dots + " " + text.takeLast(4)
