package com.mebit.app.feature.vault

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.ExportOutcome
import com.mebit.app.LARGEST_VERSION
import com.mebit.app.QrCode
import com.mebit.app.QrMatrix
import com.mebit.app.data.CreatedVault
import com.mebit.app.data.PART_INTERVAL_MS
import com.mebit.app.data.bsmsRecordOf
import com.mebit.app.data.descriptorFileName
import com.mebit.app.data.descriptorIn
import com.mebit.app.data.exportPicture
import com.mebit.app.data.partBars
import com.mebit.app.data.partCounterText
import com.mebit.app.data.partIndexAt
import com.mebit.app.encodeQr
import com.mebit.app.imageOfCard
import com.mebit.app.rememberSharer
import com.mebit.app.rememberTextFileExport
import com.mebit.app.safeShareName
import com.mebit.app.resources.Res
import com.mebit.app.resources.ic_warning
import com.mebit.app.resources.mebit_logo
import com.mebit.app.ui.components.MebitScreen
import com.mebit.app.ui.components.PrimaryCta
import com.mebit.app.ui.components.SecondaryCta
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbChip
import com.mebit.app.ui.theme.MbEyebrow
import com.mebit.app.ui.theme.MbIconWash
import com.mebit.app.ui.theme.MbNavy
import com.mebit.app.ui.theme.MbNavyInk
import com.mebit.app.ui.theme.MbBtcOrange
import com.mebit.app.ui.theme.MbPage
import com.mebit.app.ui.theme.MbWhite
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.walletDescriptorText
import com.mebit.ffi.signer.walletQrParts
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

/** Penpot 3.8's two tabs. */
private enum class ExportTab(val label: String, val tag: String) {
    Bsms("BSMS", "tab_export_bsms"),
    Descriptor("Descriptor", "tab_export_descriptor"),
}

private val BarGrey = Color(0xFFD7DEE5)

/**
 * Penpot 3.8 and 3.8.1: [vault]'s BIP-129 record as UR parts that change every [PART_INTERVAL_MS] ms, or its descriptor as one QR, a warning
 * that it holds every xpub, a file save and a share of one plain QR as a picture. Null [vault] only from the debug catalog.
 */
@Composable
fun WalletExportScreen(vault: CreatedVault?, onBack: (() -> Unit)?, onOpenCatalog: () -> Unit) {
    var tab by remember { mutableStateOf(ExportTab.Bsms) }
    var failed by remember { mutableStateOf(false) }
    val export = rememberTextFileExport { outcome -> failed = outcome is ExportOutcome.Failed }
    val sharer = rememberSharer()
    val scope = rememberCoroutineScope()
    val pictureLayer = rememberGraphicsLayer()
    val frame = rememberGraphicsLayer()
    // Native calls that read the stored descriptor: null when the core refuses it.
    val bsms = remember(vault?.bsms) { vault?.let { bsmsRecordOf(it) } }
    val descriptor = remember(vault?.bsms) { vault?.let { v -> runCatching { walletDescriptorText(descriptorIn(v.bsms), KeyNetwork.TEST) }.getOrNull() } }
    val text = if (tab == ExportTab.Bsms) bsms else descriptor
    val fileName = vault?.let { if (tab == ExportTab.Bsms) bsmsFileName(it.name) else descriptorFileName(it.name) }
    // The BSMS tab's parts, each encoded once. Without the UR call the record would be one plain QR.
    val partTexts = remember(bsms) { bsms?.let { runCatching { walletQrParts(it) }.getOrNull()?.takeIf { p -> p.isNotEmpty() } ?: listOf(it) } }
    val partMatrices = remember(partTexts) { partTexts?.map { runCatching { encodeQr(it, LARGEST_VERSION, alphanumeric = true) }.getOrNull() } }
    val descriptorMatrix = remember(descriptor) { descriptor?.let { runCatching { encodeQr(it, LARGEST_VERSION) }.getOrNull() } }
    val parts: List<QrMatrix> = if (tab == ExportTab.Bsms) partMatrices.orEmpty().takeIf { m -> m.none { it == null } }?.filterNotNull().orEmpty() else listOfNotNull(descriptorMatrix)
    val count = parts.size
    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(tab, count) {
        elapsed = 0
        while (count > 1) {
            delay(PART_INTERVAL_MS)
            elapsed += PART_INTERVAL_MS
        }
    }
    val index = partIndexAt(elapsed, count)
    val picture = vault?.let { exportPicture(it.name, text) }
    val pictureMatrix = remember(picture?.qrText) { picture?.let { runCatching { encodeQr(it.qrText, LARGEST_VERSION) }.getOrNull() } }
    MebitScreen(
        eyebrow = "EXPORT · ${vault?.name.orEmpty()}",
        title = null,
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_vaults/export",
        bottom = {
            Spacer(modifier = Modifier.height(12.dp))
            if (failed) {
                Text(BSMS_EXPORT_FAILED, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.testTag("error"))
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (vault != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) {
                        PrimaryCta("บันทึกไฟล์", "cta_export_save", { if (text != null && fileName != null) export.save(fileName, text) }, enabled = text != null)
                    }
                    Box(Modifier.weight(1f)) {
                        SecondaryCta(
                            "แชร์",
                            "cta_export_share",
                            {
                                if (pictureMatrix != null && picture != null) {
                                    scope.launch {
                                        // shortcut: a share that fails does nothing, as Receive's does. Add a line when the board has one.
                                        try {
                                            sharer.imageOfCard(pictureLayer, frame, MbPage, "mebit-wallet-" + safeShareName(vault.name))
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (_: Exception) {
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        },
    ) {
        if (vault == null) {
            Text("ยังไม่ได้เลือก Wallet", color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("export_none"))
            return@MebitScreen
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(999.dp)).background(MbChip).padding(4.dp),
        ) {
            ExportTab.entries.forEach { t ->
                val on = t == tab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (on) MbNavy else MbChip)
                        .clickable(role = Role.Tab) { tab = t }
                        .testTag(t.tag),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(t.label, color = if (on) MbWhite else MbNavy, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MbWhite).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (count > 0) {
                QrCode(parts[index], Modifier.fillMaxWidth().height(280.dp).testTag("export_qr"), dark = MbNavy)
            } else {
                Text(BSMS_EXPORT_FAILED, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("export_qr_failed"))
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (tab == ExportTab.Bsms) {
                partCounterText(index, count)?.let { counter ->
                    Text(counter, color = MbNavyInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("export_counter"))
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("export_bars")) {
                        partBars(index, count).forEach { on ->
                            Box(Modifier.size(width = 28.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(if (on) MbBtcOrange else BarGrey))
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("ให้อีกเครื่องสแกนค้างไว้จนครบทุกส่วน", color = MbBody, fontSize = 12.sp, fontWeight = FontWeight.Light, textAlign = TextAlign.Center, modifier = Modifier.testTag("export_note"))
                }
            } else {
                Text("QR เดียวใช้ได้ทั้งรับเงินและเงินทอน (BIP-389)", color = MbBody, fontSize = 12.sp, fontWeight = FontWeight.Light, textAlign = TextAlign.Center, modifier = Modifier.testTag("export_note"))
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MbIconWash).padding(16.dp).testTag("export_warning"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(painterResource(Res.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text("QR นี้ไม่มี seed แต่มี xpub ของทุกกุญแจ", color = MbNavyInk, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, lineHeight = 18.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text("ใครสแกนได้จะเห็นยอดเงินและประวัติ", color = MbEyebrow, fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp)
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (picture != null && pictureMatrix != null) {
            SharePicture(picture.name, pictureMatrix, picture.warning, pictureLayer)
        }
    }
}

/**
 * The card [imageOfCard] shares: one plain QR of the tab's text, the wallet's name and the warning line, at Receive's 354 dp. It takes no
 * space and is only recorded into [layer], never drawn to the screen.
 */
@Composable
private fun SharePicture(name: String, matrix: QrMatrix, warning: String, layer: GraphicsLayer) {
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
        Box(modifier = Modifier.fillMaxWidth().height(84.dp).background(MbNavy), contentAlignment = Alignment.Center) {
            Image(painterResource(Res.drawable.mebit_logo), contentDescription = null, modifier = Modifier.size(width = 129.dp, height = 34.dp))
        }
        Spacer(modifier = Modifier.height(16.dp))
        QrCode(matrix, Modifier.size(322.dp), dark = MbNavy)
        Text(name, color = MbNavyInk, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(modifier = Modifier.height(8.dp))
        Text(warning, color = MbEyebrow, fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(modifier = Modifier.height(20.dp))
    }
}
