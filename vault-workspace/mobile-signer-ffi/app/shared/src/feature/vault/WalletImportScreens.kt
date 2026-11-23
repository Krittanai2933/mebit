package com.mebit.app.feature.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.data.ImportReview
import com.mebit.app.data.importEyebrow
import com.mebit.app.feature.send.ConfirmSheet
import com.mebit.app.rememberCopier
import com.mebit.app.ui.components.MebitScreen
import com.mebit.app.ui.components.PrimaryCta
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbBorder
import com.mebit.app.ui.theme.MbChip
import com.mebit.app.ui.theme.MbDisabledInk
import com.mebit.app.ui.theme.MbNavy
import com.mebit.app.ui.theme.MbNavyInk
import com.mebit.app.ui.theme.MbSuccess
import com.mebit.app.ui.theme.MbWhite

/** Penpot 3.9: what the + on the Wallet list opens. */
@Composable
internal fun AddWalletSheet(onNew: () -> Unit, onImport: () -> Unit, onCancel: () -> Unit) {
    ConfirmSheet(tag = "dialog_add_wallet", title = "เพิ่ม Wallet", body = "สร้างใหม่ หรือเปิด Wallet หลายกุญแจที่มีอยู่แล้ว", onCancel = onCancel) {
        SheetRow("สร้าง Wallet ใหม่", "เลือกนโยบายและกุญแจ", "cta_add_new", onClick = onNew)
        Spacer(modifier = Modifier.height(10.dp))
        SheetRow("นำเข้า Wallet หลายกุญแจ", "จากไฟล์ BSMS, QR หรือ descriptor", "cta_add_import", onClick = onImport)
    }
}

/** Penpot 3.9.1: pick a file, or scan a QR (Penpot 3.9.2). */
@Composable
internal fun ImportWalletSheet(onPick: () -> Unit, onScan: () -> Unit, onCancel: () -> Unit) {
    ConfirmSheet(
        tag = "dialog_import_wallet",
        title = "นำเข้า Wallet หลายกุญแจ",
        body = "ตรวจไฟล์ก่อนสร้าง · รองรับ wsh(sortedmulti) บน testnet",
        onCancel = onCancel,
    ) {
        SheetRow("เลือกไฟล์ BSMS/Descriptor", "ไฟล์ .bsms หรือไฟล์ descriptor จากแอปนี้หรือแอปอื่น", "cta_import_pick", onClick = onPick)
        Spacer(modifier = Modifier.height(10.dp))
        SheetRow("สแกน QR", "BSMS หรือ descriptor ภาพเดียวหรือหลายส่วน", "row_import_scan", onClick = onScan)
    }
}

@Composable
private fun SheetRow(title: String, sub: String?, tag: String, enabled: Boolean = true, badge: String? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MbBorder, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) MbNavyInk else MbDisabledInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (sub != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(sub, color = MbBody, fontSize = 12.sp, fontWeight = FontWeight.Light, lineHeight = 16.sp)
            }
        }
        if (badge != null) {
            Box(Modifier.clip(CircleShape).background(MbChip).padding(horizontal = 10.dp, vertical = 4.dp)) {
                Text(badge, color = MbDisabledInk, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
            }
        } else if (enabled) {
            Text("›", color = MbBody, fontSize = 20.sp)
        }
    }
}

/**
 * Penpot 3.9.3: the card of the wallet in the file, one row per check [review] ran, the first address to compare with the app the file came
 * from, and the button that saves it. [line] is a refusal from the save, shown over the button.
 */
@Composable
fun WalletImportScreen(
    fileName: String,
    review: ImportReview,
    address: String,
    line: String?,
    onImport: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    val copier = rememberCopier()
    val shape = RoundedCornerShape(20.dp)
    MebitScreen(
        eyebrow = importEyebrow(fileName),
        title = "ตรวจก่อนนำเข้า",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_vaults/import",
        bottom = {
            Spacer(modifier = Modifier.height(12.dp))
            line?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.testTag("error"))
                Spacer(modifier = Modifier.height(8.dp))
            }
            PrimaryCta("นำเข้า Wallet", "cta_import_wallet", onImport)
        },
    ) {
        Column(Modifier.fillMaxWidth().clip(shape).background(MbNavy).padding(18.dp).testTag("import_card")) {
            Text("WALLET ที่จะนำเข้า", color = MbWhite.copy(alpha = 0.72f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(review.title, color = MbWhite, fontSize = 17.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("import_title"))
            Spacer(modifier = Modifier.height(4.dp))
            Text(review.sub, color = MbWhite.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.Light, modifier = Modifier.testTag("import_sub"))
        }
        Spacer(modifier = Modifier.height(14.dp))
        review.rows.forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp).testTag(row.tag), verticalAlignment = Alignment.CenterVertically) {
                if (row.ticked) {
                    Box(Modifier.size(18.dp).background(MbSuccess, CircleShape)) {
                        Tick(Modifier.offset(x = 5.dp, y = 6.dp).size(8.dp, 6.dp))
                    }
                } else {
                    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(6.dp).background(MbDisabledInk, CircleShape)) }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(row.text, color = if (row.ticked) MbNavyInk else MbBody, fontSize = 13.5.sp)
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text("ที่อยู่แรก · เทียบกับแอปต้นทาง", color = MbBody, fontSize = 12.sp, fontWeight = FontWeight.Light)
        Spacer(modifier = Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                review.firstAddress,
                color = MbNavyInk,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 21.sp,
                modifier = Modifier.weight(1f).testTag("import_address"),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MbChip)
                    .clickable(role = Role.Button) { copier.copy(address) }
                    .padding(horizontal = 14.dp)
                    .testTag("cta_import_copy"),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (copier.copied == address) "คัดลอกแล้ว" else "คัดลอก", color = MbNavy, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}
