package com.mebit.app.feature.send

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.data.CreatedVault
import com.mebit.app.ui.components.PrimaryCta
import com.mebit.app.ui.components.SecondaryCta
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbNavyInk
import com.mebit.app.ui.theme.MbWhite

/** What Penpot 4.1.1.1 shows under the key row for removing the wallet. */
sealed interface RemoveRow {
    data object Hidden : RemoveRow
    data object Offered : RemoveRow
    data class InVault(val vaultName: String) : RemoveRow
    data object Unreadable : RemoveRow
}

/** Pure. Removing needs a [saved] wallet, because an unsaved one's words are only in memory, another wallet to remain, a key no vault uses, and a book that could be read ([usesKey] null). */
internal fun removeRow(listedWallets: Int, saved: Boolean, usesKey: Boolean?, vaultName: String?): RemoveRow = when {
    listedWallets < 2 || !saved -> RemoveRow.Hidden
    usesKey == null -> RemoveRow.Unreadable
    usesKey -> RemoveRow.InVault(vaultName ?: "Wallet")
    else -> RemoveRow.Offered
}

internal fun removeBlockedLine(row: RemoveRow): String? = when (row) {
    is RemoveRow.InVault -> inUseLine(row.vaultName)
    RemoveRow.Unreadable -> "ลบไม่ได้ในตอนนี้ · อ่านรายการ Wallet ไม่ได้"
    RemoveRow.Hidden, RemoveRow.Offered -> null
}

/** Penpot 4.1.1.3's line for a key a several-key wallet uses, shared by a wallet's removal and the Keyring's delete. */
private fun inUseLine(walletNames: String) = "ลบไม่ได้ · กุญแจนี้ใช้อยู่ใน $walletNames"

/** Pure. The Keyring's refusal for the key [fingerprint], naming every several-key wallet that uses it. */
internal fun keyInUseLine(vaults: List<CreatedVault>, fingerprint: String?): String =
    inUseLine(vaults.filter { v -> v.keyFingerprints.any { it.equals(fingerprint, ignoreCase = true) } }.joinToString(", ") { it.name }.ifEmpty { "Wallet" })

internal const val REMOVE_ROW_LABEL = "ลบกระเป๋านี้ออกจากเครื่อง"

internal const val REMOVE_FAILED_LINE = "ลบกระเป๋าไม่สำเร็จ ลองอีกครั้ง"

internal fun removeSheetTitle(name: String) = "ลบ$name ออกจากเครื่องนี้ไหม"

internal fun removeReason(name: String) = "ลบ$name ออกจากเครื่องนี้"

/** Penpot 4.1.1.2, in the style of the share sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoveWalletSheet(name: String, backedUp: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MbWhite,
        modifier = Modifier.testTag("dialog_remove_wallet"),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text(removeSheetTitle(name), color = MbNavyInk, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "กระเป๋าจะถูกลบออกจากเครื่องนี้ · มีแค่ 24 คำที่นำกลับมาได้" +
                    if (backedUp) "" else "\nกระเป๋านี้ยังไม่ได้สำรอง 24 คำ ถ้าลบตอนนี้จะกู้คืนไม่ได้",
                color = MbBody,
                fontSize = 13.sp,
                lineHeight = 19.5.sp,
            )
            Spacer(modifier = Modifier.height(16.dp))
            PrimaryCta("ลบออกจากเครื่องนี้", "cta_remove_confirm", onConfirm)
            Spacer(modifier = Modifier.height(10.dp))
            SecondaryCta("ยกเลิก", "cta_remove_cancel", onCancel)
        }
    }
}
