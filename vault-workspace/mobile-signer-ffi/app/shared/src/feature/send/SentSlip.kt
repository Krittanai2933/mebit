package com.mebit.app.feature.send

import com.mebit.app.ui.components.ShrinkText
import com.mebit.app.data.amountNumber
import com.mebit.app.data.LocalBtcUnit
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mebit.app.QrCode
import com.mebit.app.data.FiatQuote
import com.mebit.app.data.NodeSetting
import com.mebit.app.data.QuoteCache
import com.mebit.app.data.rememberFiatQuote
import com.mebit.app.data.txPageUrl
import com.mebit.app.formatThaiDateTime
import com.mebit.app.imageOfCard
import com.mebit.app.rememberSharer
import com.mebit.app.resources.Res
import com.mebit.app.resources.ic_slip_btc
import com.mebit.app.resources.ic_slip_wallet
import com.mebit.app.resources.ic_slip_watermark
import com.mebit.app.resources.mebit_logo_slip
import com.mebit.app.ui.components.DebugLink
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbBtcOrange
import com.mebit.app.ui.theme.MbEyebrow
import com.mebit.app.ui.theme.MbIconWash
import com.mebit.app.ui.theme.MbNavy
import com.mebit.app.ui.theme.MbPage
import com.mebit.app.ui.theme.MbSlate
import com.mebit.app.ui.theme.MbSuccess
import com.mebit.app.ui.theme.MbWarmTint
import com.mebit.app.ui.theme.MbWhite
import com.mebit.app.ui.theme.amountFontFamily
import com.mebit.ffi.signer.KeyNetwork
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

internal val SlipPage = MbPage
internal val SlipNavy = Color(0xFF073453)
internal val SlipMuted = Color(0xFF687E90)
private val SlipRule = Color(0xFFC9CFD8)

/**
 * Penpot 5.9.2 Sent · wallet · slip mood, which every sent screen draws: the warm card of [txid] from [walletName] to the whole
 * [destination], its date line (only once [sentAtSeconds] is set, after a send in this run), the amount with its baht (only with a
 * price), the fee at the picked rate, and a QR of the txid's page on [network] at the explorer of [node], the node the user set. Under it the
 * "แชร์สลิป" button that shares the card as a picture, and the link home. The tags are S6's on every screen, which [tag] tells apart.
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
    node: NodeSetting,
    network: KeyNetwork,
    quotes: QuoteCache,
    onHome: () -> Unit,
    onOpenCatalog: () -> Unit,
) {
    val quote = rememberFiatQuote(quotes)
    val card = rememberGraphicsLayer()
    val frame = rememberGraphicsLayer()
    val sharer = rememberSharer()
    val scope = rememberCoroutineScope()
    Box(modifier = Modifier.fillMaxSize().background(SlipPage).testTag(tag)) {
        Column(
            // Scrolls so the share button and the link home cannot fall off a screen shorter than the card plus both buttons (open item 48).
            // The bottom padding is the debug link's height (about 24 dp of Thai text at 11 sp) plus 8 dp, so the link home sits clear of it when the column ends at the screen bottom.
            modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 32.dp),
        ) {
            Slip(txid, sentAtSeconds, amountSats, feeSats, rateSatVb, destination, quote, walletName, node, network, card)
            Spacer(modifier = Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .fillMaxWidth()
                    .height(54.dp)
                    .shadow(12.dp, CircleShape, ambientColor = MbNavy, spotColor = MbNavy)
                    .clip(CircleShape)
                    .background(MbNavy)
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
                Text("แชร์สลิป", color = MbWhite, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier.fillMaxWidth().height(29.dp).clickable(role = Role.Button, onClick = onHome).testTag("cta_s6_home"),
                contentAlignment = Alignment.Center,
            ) {
                Text("กลับหน้าหลัก", color = MbNavy, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
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
    node: NodeSetting,
    network: KeyNetwork,
    layer: GraphicsLayer,
) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(14.dp, shape, clip = false, ambientColor = MbNavy.copy(alpha = 0.12f), spotColor = MbNavy.copy(alpha = 0.12f))
            // Recorded between the shadow and the clip: the picture keeps the rounded corners and leaves the shadow out.
            .drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            }
            .clip(shape)
            .background(Brush.linearGradient(0f to MbWhite, 0.55f to MbWarmTint, 1f to MbIconWash)),
    ) {
        Watermark(Modifier.align(Alignment.TopCenter).padding(top = 172.dp))
        Column(modifier = Modifier.padding(horizontal = 22.dp).padding(top = 18.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 6.dp).size(40.dp).background(MbSuccess, CircleShape), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(24.dp)) {
                        val tick = Path().apply {
                            moveTo(20.dp.toPx(), 6.dp.toPx())
                            lineTo(9.dp.toPx(), 17.dp.toPx())
                            lineTo(4.dp.toPx(), 12.dp.toPx())
                        }
                        drawPath(tick, MbWhite, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("ส่งสำเร็จ", color = MbNavy, fontSize = 22.sp, fontWeight = FontWeight.Medium, lineHeight = 28.sp)
                    if (sentAtSeconds > 0) {
                        // One line beside the logo: a 393 pt iPhone leaves the date about 163 dp, where 17 sp wraps the "น.".
                        ShrinkText(formatThaiDateTime(sentAtSeconds), color = MbBody, fontSize = 17.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 4.dp).testTag("slip_date"))
                    }
                }
                Image(painterResource(Res.drawable.mebit_logo_slip), contentDescription = "mebit", modifier = Modifier.padding(top = 10.dp).size(width = 100.dp, height = 24.dp))
            }
            SlipRule(top = 16.dp, bottom = 14.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).background(MbNavy, CircleShape).border(3.dp, MbWhite, CircleShape), contentAlignment = Alignment.Center) {
                    Image(painterResource(Res.drawable.ic_slip_wallet), contentDescription = null, modifier = Modifier.size(24.dp))
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(walletName, color = MbNavy, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp, modifier = Modifier.testTag("slip_from"))
                    Text("กระเป๋าใน mebit", color = MbBody, fontSize = 16.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Box(Modifier.padding(vertical = 10.dp).width(48.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(24.dp)) {
                    val stroke = 2.dp.toPx()
                    val tip = Offset(12.dp.toPx(), 19.dp.toPx())
                    drawLine(MbSlate, Offset(12.dp.toPx(), 2.dp.toPx()), tip, stroke, StrokeCap.Round)
                    drawLine(MbSlate, Offset(19.dp.toPx(), 12.dp.toPx()), tip, stroke, StrokeCap.Round)
                    drawLine(MbSlate, Offset(5.dp.toPx(), 12.dp.toPx()), tip, stroke, StrokeCap.Round)
                }
            }
            Row {
                Box(Modifier.size(48.dp).background(MbBtcOrange, CircleShape), contentAlignment = Alignment.Center) {
                    Image(painterResource(Res.drawable.ic_slip_btc), contentDescription = null, modifier = Modifier.size(width = 17.dp, height = 30.dp))
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(groupedLines(destination, 6), color = MbNavy, fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 22.sp, modifier = Modifier.testTag("slip_to"))
                    Text("ที่อยู่ปลายทาง", color = MbBody, fontSize = 16.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text("จำนวน:", color = MbBody, fontSize = 17.sp, lineHeight = 21.sp)
            Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.CenterEnd) {
                val unit = LocalBtcUnit.current
                // The unit and baht are in em of the 32 sp number, so they shrink with it when a long amount does not fit.
                ShrinkText(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(fontFamily = amountFontFamily(), fontWeight = FontWeight.Bold)) { append(amountNumber(amountSats, unit)) }
                        withStyle(SpanStyle(fontSize = 0.5.em, fontWeight = FontWeight.Medium)) { append(" ${unit.label}") }
                        slipBaht(amountSats, quote)?.let { baht ->
                            withStyle(SpanStyle(color = MbBody, fontSize = 0.47.em)) { append(" ≈ $baht") }
                        }
                    },
                    color = MbNavy,
                    fontSize = 32.sp,
                    lineHeight = 39.sp,
                    modifier = Modifier.testTag("send_amount"),
                )
            }
            SlipRule(top = 4.dp, bottom = 16.dp)
            Text("ค่าธรรมเนียม:", color = MbBody, fontSize = 17.sp, lineHeight = 21.sp)
            Text(
                slipFeeText(feeSats, rateSatVb, quote),
                color = MbNavy,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 25.sp,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("slip_fee"),
            )
            SlipRule(top = 4.dp, bottom = 14.dp)
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                // The tile is the QR's quiet zone too: its four light modules on each side fill the board's white margin.
                Box(Modifier.size(68.dp).clip(RoundedCornerShape(8.dp)).background(MbWhite)) {
                    if (txid.isNotEmpty()) QrCode(node.txPageUrl(txid, network), Modifier.fillMaxSize().testTag("slip_qr"), dark = MbNavy)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text("สแกนเพื่อตรวจสอบรายการ", color = MbNavy, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp)
                    Text("ดูธุรกรรมนี้บน blockchain", color = MbBody, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
            Text(
                text = "ออกโดย mebit · ตรวจสอบได้บน blockchain",
                color = MbSlate,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
        }
    }
}

/** The board's embossed mark: the cream mark at 70 %, a white highlight up and left of it and an amber shade down and right. */
@Composable
private fun Watermark(modifier: Modifier) {
    val mark = painterResource(Res.drawable.ic_slip_watermark)
    // shortcut: the board blurs the highlight by 1 px and the shade by 3 px; these are sharp. Blur them if the edge shows on a phone.
    Box(modifier.size(300.dp).alpha(0.7f)) {
        Image(mark, contentDescription = null, colorFilter = ColorFilter.tint(MbWhite.copy(alpha = 0.75f)), modifier = Modifier.offset((-1.5).dp, (-1.5).dp))
        Image(mark, contentDescription = null, colorFilter = ColorFilter.tint(MbEyebrow.copy(alpha = 0.22f)), modifier = Modifier.offset(1.5.dp, 2.dp))
        Image(mark, contentDescription = null)
    }
}

@Composable
private fun SlipRule(top: Dp, bottom: Dp) {
    Box(modifier = Modifier.padding(top = top, bottom = bottom).fillMaxWidth().height(2.dp).background(SlipRule))
}

/** "tb1qw5…jzsx": first four, last four. Short strings stay whole. */
internal fun shorten(text: String, dots: String): String =
    if (text.length <= 10) text else text.take(4) + " " + dots + " " + text.takeLast(4)
