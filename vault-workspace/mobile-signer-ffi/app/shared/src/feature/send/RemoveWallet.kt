package com.mebit.app.feature.send

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import com.mebit.app.data.BookSaving
import com.mebit.app.data.KeySourceLabel
import com.mebit.app.ui.components.DeleteItem
import com.mebit.app.ui.components.PrimaryCta
import com.mebit.app.ui.components.SecondaryCta
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbNavyInk
import com.mebit.app.ui.theme.MbWhite

/**
 * Pure. Penpot 2.1.A1 to A3's item for a key. A wallet's own key ([ownWalletName]) goes with its wallet, so its line points there
 * whatever else holds. [usingWallets] names the several-key wallets that use the key, and is null while the book cannot be judged.
 */
internal fun keyDeleteItem(ownWalletName: String?, usingWallets: List<String>?): DeleteItem = when {
    ownWalletName != null -> DeleteItem.Blocked("ลบไม่ได้ · เป็นกุญแจของ $ownWalletName · ลบที่หน้า Wallet แทน")
    usingWallets == null -> DeleteItem.Blocked(BOOK_UNREADABLE_LINE)
    usingWallets.isNotEmpty() -> DeleteItem.Blocked(inUseLine(usingWallets.joinToString(", ")))
    else -> DeleteItem.Offered
}

/**
 * Pure. Penpot 1.8.A1 and A2's item for a one-key wallet. Removing needs another wallet to remain, a [saved] wallet, because an unsaved
 * one's words are only in memory, a book that could be read ([usesKey] null), and a key no several-key wallet uses.
 */
internal fun walletDeleteItem(listedWallets: Int, saved: Boolean, usesKey: Boolean?, vaultName: String?): DeleteItem = when {
    listedWallets < 2 -> DeleteItem.Blocked("ลบไม่ได้ · ต้องมีกระเป๋าอย่างน้อย 1 ใบในเครื่อง")
    !saved -> DeleteItem.Blocked("ลบไม่ได้ · กระเป๋านี้ยังไม่ได้บันทึกในเครื่อง")
    usesKey == null -> DeleteItem.Blocked(BOOK_UNREADABLE_LINE)
    usesKey -> DeleteItem.Blocked(inUseLine(vaultName ?: "Wallet"))
    else -> DeleteItem.Offered
}

/** Pure. Penpot 1.8.A3's item. A several-key wallet may be removed only while its book is saved and can be judged, as an unsaved one-key wallet may not be. */
internal fun vaultDeleteItem(saving: BookSaving): DeleteItem = when (saving) {
    BookSaving.MemoryOnly -> DeleteItem.Blocked("ลบไม่ได้ · Wallet นี้ยังไม่ได้บันทึกในเครื่อง")
    BookSaving.Unjudged -> DeleteItem.Blocked(BOOK_UNREADABLE_LINE)
    BookSaving.Ready -> DeleteItem.Offered
}

private const val BOOK_UNREADABLE_LINE = "ลบไม่ได้ในตอนนี้ · อ่านรายการ Wallet ไม่ได้"

/** Penpot 4.1.1.3's line for a key a several-key wallet uses, shared by a wallet's removal and the Keyring's delete. */
private fun inUseLine(walletNames: String) = "ลบไม่ได้ · กุญแจนี้ใช้อยู่ใน $walletNames"

internal const val REMOVE_FAILED_LINE = "ลบกระเป๋าไม่สำเร็จ ลองอีกครั้ง"

/** The title of every delete and remove sheet, for a key, a one-key wallet or a several-key wallet. */
internal fun removeSheetTitle(name: String) = "${deleteWords(name)} ออกจากเครื่องนี้ไหม"

/** The reason the owner check shows before a wallet leaves this phone. */
internal fun removeReason(name: String) = "${deleteWords(name)} ออกจากเครื่องนี้"

/** "ลบ" and [name], spaced before a Latin letter or a digit ("ลบ Wallet 2") and joined before Thai ("ลบกระเป๋า 2"). */
private fun deleteWords(name: String): String {
    // Below U+0250 are the Latin letters and the ASCII digits. Thai starts at U+0E00.
    val latin = name.firstOrNull()?.let { it.isLetterOrDigit() && it.code < 0x250 } == true
    return if (latin) "ลบ $name" else "ลบ$name"
}

/** Pure. Penpot 2.1.9's line: where the key still lives once it leaves this phone, so the user knows it can come back. */
internal fun deleteKeyBody(source: KeySourceLabel): String = when (source) {
    KeySourceLabel.Jade -> "Jade ยังเก็บกุญแจนี้ไว้ · เพิ่มกลับมาได้ทุกเมื่อจากปุ่ม + ในคลังกุญแจ"
    KeySourceLabel.TrezorSafe7 -> "Trezor ยังเก็บกุญแจนี้ไว้ · เพิ่มกลับมาได้ทุกเมื่อจากปุ่ม + ในคลังกุญแจ"
    KeySourceLabel.MebitOther -> "เครื่องนั้นยังเก็บกุญแจนี้ไว้ · เพิ่มกลับมาได้ทุกเมื่อจากปุ่ม + ในคลังกุญแจ"
    KeySourceLabel.ThisDevice -> "กุญแจจะถูกลบออกจากคลังกุญแจในเครื่องนี้"
}

/** Pure. Penpot 1.8.9's line: the coins stay on the network, and the BSMS file and [m] of the [n] keys, all still on the Keyring, spend them. */
internal fun removeVaultBody(m: Int, n: Int) = "เหรียญยังอยู่บนเครือข่าย · ใช้จ่ายได้ด้วยไฟล์ BSMS กับกุญแจ $m จาก $n · กุญแจทั้ง $n ยังอยู่ในคลังกุญแจ"

/** Penpot 4.1.1.2's sheet, which every delete from a card's menu confirms in: [title], [body], then [buttons]. A swipe down or a tap outside is [onCancel]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConfirmSheet(tag: String, title: String, body: String, onCancel: () -> Unit, buttons: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MbWhite,
        modifier = Modifier.testTag(tag),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text(title, color = MbNavyInk, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(12.dp))
            Text(body, color = MbBody, fontSize = 13.sp, lineHeight = 19.5.sp)
            Spacer(modifier = Modifier.height(16.dp))
            buttons()
        }
    }
}

/** Penpot 1.8.8, the one-key wallet's confirm. */
@Composable
internal fun RemoveWalletSheet(name: String, backedUp: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit) {
    ConfirmSheet(
        tag = "dialog_remove_wallet",
        title = removeSheetTitle(name),
        body = "กระเป๋าจะถูกลบออกจากเครื่องนี้ · มีแค่ 24 คำที่นำกลับมาได้" +
            if (backedUp) "" else "\nกระเป๋านี้ยังไม่ได้สำรอง 24 คำ ถ้าลบตอนนี้จะกู้คืนไม่ได้",
        onCancel = onCancel,
    ) {
        PrimaryCta("ลบออกจากเครื่องนี้", "cta_remove_confirm", onConfirm)
        Spacer(modifier = Modifier.height(10.dp))
        SecondaryCta("ยกเลิก", "cta_remove_cancel", onCancel)
    }
}
