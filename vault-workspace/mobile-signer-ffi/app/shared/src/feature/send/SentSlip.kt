package com.mebit.app.feature.send

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.data.FiatQuote
import com.mebit.app.data.QuoteCache
import com.mebit.app.data.formatBtcCompact
import com.mebit.app.data.rememberFiatQuote
import com.mebit.app.formatThaiDateTime
import com.mebit.app.imageOfCard
import com.mebit.app.rememberSharer
import com.mebit.app.resources.Res
import com.mebit.app.resources.ic_vault_row
import com.mebit.app.resources.ic_wallet_row
import com.mebit.app.resources.mebit_logo
import com.mebit.app.ui.components.DebugLink
import com.mebit.app.ui.theme.MbBtcOrange
import com.mebit.app.ui.theme.MbChip
import com.mebit.app.ui.theme.MbWhite
import com.mebit.app.ui.theme.amountFontFamily
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

internal val SlipPage = Color(0xFFEFF4FA)
internal val SlipNavy = Color(0xFF073453)
internal val SlipMuted = Color(0xFF687E90)
private val SlipFiat = Color(0xFF56748A)
private val SlipHairline = Color(0xFFEDF2F6)

/**
 * Penpot 4.6 Sent · slip, which 5.9 draws alike for a several-key wallet ([multiKey]): the card of [txid] from [walletName] to the whole
 * [destination], its date line (only once [sentAtSeconds] is set, after a send in this run) and baht line (only with a price), the "แชร์สลิป" button that shares the
 * card as a picture, and the link home. The tags are S6's on both screens, which [tag] tells apart.
 */
@Composable
fun SentSlip(
    tag: String,
    txid: String,
    sentAtSeconds: Long,
    amountSats: Long,
    feeSats: Long,
    rateSatVb: Long,
    destination: String,
    walletName: String,
    multiKey: Boolean,
    quotes: QuoteCache,
    onHome: () -> Unit,
    onOpenCatalog: () -> Unit,
) {
    val quote = rememberFiatQuote(quotes)
    val card = rememberGraphicsLayer()
    val frame = rememberGraphicsLayer()
    val sharer = rememberSharer()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }
    Box(modifier = Modifier.fillMaxSize().background(SlipPage).testTag(tag)) {
        Column(
            // Scrolls so the share button and the link home cannot fall off a screen shorter than the card plus both buttons (open item 48).
            // The bottom padding is the debug link's height (about 24 dp of Thai text at 11 sp) plus 8 dp, so the link home sits clear of it when the column ends at the screen bottom.
            modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = 54.dp, bottom = 32.dp),
        ) {
            Slip(txid, sentAtSeconds, amountSats, feeSats, rateSatVb, destination, quote, walletName, multiKey, card)
            Spacer(modifier = Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .clip(CircleShape)
                        .background(SlipNavy)
                        .clickable(role = Role.Button) {
                            if (txid.isNotEmpty()) {
                                clipboard.setText(AnnotatedString(txid))
                                copied = true
                            }
                        }
                        .testTag("cta_slip_copy_txid"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (copied) "คัดลอกแล้ว" else "คัดลอก TXID", color = MbWhite, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .clip(CircleShape)
                        .background(MbWhite)
                        .border(1.5.dp, SlipNavy, CircleShape)
                        .clickable(role = Role.Button) {
                            scope.launch {
                                // shortcut: a share that fails does nothing, because the board has no error for it and a crash right after a payment is worse. Add one when it does.
                                try {
                                    val name = if (txid.isEmpty()) "mebit-slip" else "mebit-slip-" + txid.take(8)
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
                    Text("แชร์สลิป", color = SlipNavy, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                }
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
private fun Slip(
    txid: String,
    sentAtSeconds: Long,
    amountSats: Long,
    feeSats: Long,
    rateSatVb: Long,
    destination: String,
    quote: FiatQuote?,
    walletName: String,
    multiKey: Boolean,
    layer: GraphicsLayer,
) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(14.dp, shape, clip = false)
            // Recorded between the shadow and the clip: the picture keeps the rounded corners and leaves the shadow out.
            .drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            }
            .clip(shape)
            .background(MbWhite),
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(SlipNavy).padding(horizontal = 24.dp).padding(top = 20.dp, bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(Res.drawable.mebit_logo), contentDescription = "mebit", modifier = Modifier.size(width = 92.dp, height = 24.dp))
                Spacer(modifier = Modifier.weight(1f))
                Text("สลิปส่ง Bitcoin", color = Color(0xFFDCEAF4), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text("ส่งสำเร็จ", color = MbWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 27.sp)
            if (sentAtSeconds > 0) {
                Text(formatThaiDateTime(sentAtSeconds), color = Color(0xFFDCEAF4), fontSize = 12.5.sp, fontWeight = FontWeight.Medium, lineHeight = 17.sp, modifier = Modifier.testTag("slip_date"))
            }
        }
        Column(modifier = Modifier.padding(horizontal = 24.dp).padding(top = 20.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(MbChip, CircleShape), contentAlignment = Alignment.Center) {
                    Image(
                        painterResource(if (multiKey) Res.drawable.ic_vault_row else Res.drawable.ic_wallet_row),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(SlipNavy),
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(walletName, color = SlipNavy, fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp, modifier = Modifier.testTag("slip_from"))
                    Text(if (multiKey) "mebit · กระเป๋าหลายกุญแจ" else "mebit · กระเป๋า 1 กุญแจ", color = SlipMuted, fontSize = 12.5.sp, lineHeight = 17.sp)
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text("↓", color = SlipMuted, fontSize = 18.sp, modifier = Modifier.padding(start = 15.dp))
            Spacer(modifier = Modifier.height(6.dp))
            Row {
                Box(Modifier.size(44.dp).background(MbBtcOrange, CircleShape), contentAlignment = Alignment.Center) {
                    Text("₿", color = MbWhite, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text("ที่อยู่ Bitcoin", color = SlipNavy, fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp)
                    addressTypeLine(destination)?.let {
                        Text(it, color = SlipMuted, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.testTag("slip_address_type"))
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(groupedLines(destination, 6), color = SlipNavy, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.testTag("slip_to"))
                }
            }
            SlipHairline()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("จำนวน:", color = SlipMuted, fontSize = 13.5.sp, lineHeight = 19.sp)
                Spacer(modifier = Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = formatBtcCompact(amountSats) + " BTC",
                        color = SlipNavy,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = amountFontFamily(),
                        lineHeight = 44.sp,
                        modifier = Modifier.testTag("send_amount"),
                    )
                    fiatLine(amountSats, quote)?.let {
                        Text(it, color = SlipFiat, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp, modifier = Modifier.testTag("slip_fiat"))
                    }
                }
            }
            SlipHairline()
            // The fee the signed transaction pays, at the rate the user chose. PreparedSend carries no size, so the rate is the chosen one, not fee over size.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ค่าธรรมเนียม:", color = SlipMuted, fontSize = 13.5.sp, lineHeight = 19.sp)
                Spacer(modifier = Modifier.weight(1f))
                Text(slipFeeText(feeSats, quote), color = SlipNavy, fontSize = 14.sp, fontWeight = FontWeight.Bold, lineHeight = 19.sp, modifier = Modifier.testTag("slip_fee"))
                Text("  ·  $rateSatVb sat/vB", color = SlipMuted, fontSize = 12.sp, lineHeight = 19.sp)
            }
            SlipHairline()
            Row(verticalAlignment = Alignment.Top) {
                Text("TXID:", color = SlipMuted, fontSize = 13.5.sp, lineHeight = 19.sp)
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    groupedLines(txid.ifEmpty { "—" }, 8),
                    color = SlipNavy,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.testTag("s6_txid"),
                )
            }
            SlipHairline()
            Text(
                text = "ตรวจสอบได้บน blockchain · ออกโดย mebit",
                color = SlipMuted,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SlipHairline() {
    Box(modifier = Modifier.padding(vertical = 14.dp).fillMaxWidth().height(1.dp).background(SlipHairline))
}

/** "tb1qw5…jzsx": first four, last four. Short strings stay whole. */
internal fun shorten(text: String, dots: String): String =
    if (text.length <= 10) text else text.take(4) + " " + dots + " " + text.takeLast(4)
