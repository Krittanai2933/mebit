package com.mebit.app.feature.vault

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.ExportOutcome
import com.mebit.app.data.CreatedVault
import com.mebit.app.data.bsmsRecordOf
import com.mebit.app.data.descriptorFileName
import com.mebit.app.data.descriptorIn
import com.mebit.app.rememberTextFileExport
import com.mebit.app.ui.components.MebitScreen
import com.mebit.app.ui.components.PrimaryCta
import com.mebit.app.ui.components.SecondaryCta
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbBorder
import com.mebit.app.ui.theme.MbChip
import com.mebit.app.ui.theme.MbEyebrow
import com.mebit.app.ui.theme.MbIconWash
import com.mebit.app.ui.theme.MbNavy
import com.mebit.app.ui.theme.MbNavyInk
import com.mebit.app.ui.theme.MbWhite
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.walletDescriptorText

/** Penpot 3.8's two tabs. */
private enum class ExportTab(val label: String, val tag: String) {
    Bsms("BSMS", "tab_export_bsms"),
    Descriptor("Descriptor", "tab_export_descriptor"),
}

/**
 * Penpot 3.8 and 3.8.1: [vault]'s BIP-129 record or its descriptor in a scrollable monospace box, a warning that the file holds every xpub,
 * and share and save. File only: the boards' QR is not built (open item). Null [vault] only from the debug catalog.
 */
@Composable
fun WalletExportScreen(vault: CreatedVault?, onBack: (() -> Unit)?, onOpenCatalog: () -> Unit) {
    var tab by remember { mutableStateOf(ExportTab.Bsms) }
    var failed by remember { mutableStateOf(false) }
    val export = rememberTextFileExport { outcome -> failed = outcome is ExportOutcome.Failed }
    // Native calls that read the stored descriptor: null when the core refuses it.
    val bsms = remember(vault?.bsms) { vault?.let { bsmsRecordOf(it) } }
    val descriptor = remember(vault?.bsms) { vault?.let { v -> runCatching { walletDescriptorText(descriptorIn(v.bsms), KeyNetwork.TEST) }.getOrNull() } }
    val text = if (tab == ExportTab.Bsms) bsms else descriptor
    val fileName = vault?.let { if (tab == ExportTab.Bsms) bsmsFileName(it.name) else descriptorFileName(it.name) }
    MebitScreen(
        eyebrow = "EXPORT · ${vault?.name.orEmpty()}",
        title = "ส่งออก Wallet",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_vaults/export",
    ) {
        if (vault == null) {
            Text("ยังไม่ได้เลือก Wallet", color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("export_none"))
            return@MebitScreen
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExportTab.entries.forEach { t ->
                val on = t == tab
                Box(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (on) MbNavy else MbChip)
                        .clickable(role = Role.Tab) { tab = t }
                        .padding(horizontal = 18.dp)
                        .testTag(t.tag),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(t.label, color = if (on) MbWhite else MbNavy, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        val shape = RoundedCornerShape(14.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .clip(shape)
                .background(MbWhite)
                .border(1.dp, MbBorder, shape)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
        ) {
            Text(
                text ?: BSMS_EXPORT_FAILED,
                color = MbNavyInk,
                fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 16.sp,
                modifier = Modifier.testTag("export_text"),
            )
        }
        if (tab == ExportTab.Descriptor) {
            Spacer(modifier = Modifier.height(8.dp))
            Text("ใช้ได้ทั้งรับเงินและเงินทอน (BIP-389)", color = MbBody, fontSize = 12.sp, fontWeight = FontWeight.Light, modifier = Modifier.testTag("export_note"))
        }
        Spacer(modifier = Modifier.height(14.dp))
        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MbIconWash).padding(16.dp).testTag("export_warning"),
        ) {
            Text("ไฟล์นี้ไม่มี seed แต่มี xpub ของทุกกุญแจ", color = MbNavyInk, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, lineHeight = 18.sp)
            Spacer(modifier = Modifier.height(6.dp))
            Text("ใครได้ไฟล์นี้ไปจะเห็นยอดเงินและประวัติ", color = MbEyebrow, fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp)
        }
        if (failed) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(BSMS_EXPORT_FAILED, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.testTag("error"))
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) {
                PrimaryCta("แชร์", "cta_export_share", { if (text != null && fileName != null) export.share(fileName, text) }, enabled = text != null)
            }
            Box(Modifier.weight(1f)) {
                SecondaryCta("บันทึกไฟล์", "cta_export_save", { if (text != null && fileName != null) export.save(fileName, text) })
            }
        }
    }
}
