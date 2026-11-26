package com.mebit.app.feature.send

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.data.BookSaving
import com.mebit.app.data.KeySourceLabel
import com.mebit.app.ui.components.CoverableSheet
import com.mebit.app.ui.components.DeleteItem
import com.mebit.app.ui.components.PrimaryCta
import com.mebit.app.ui.components.SecondaryCta
import com.mebit.app.ui.components.rememberFullSheetState
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbNavyInk
import com.mebit.app.ui.theme.MbWhite
import com.mebit.ffi.signer.WalletKind

/**
 * Pure. Penpot 2.1.A1 to A4's item for a key. Every wallet that still uses the key is named: its own one-key wallet ([ownWalletName]) first,
 * then the several-key wallets in [usingWallets], which is null while the book cannot be judged. Penpot 2.1.A2 to A4 are the in-use lines.
 */
internal fun keyDeleteItem(ownWalletName: String?, usingWallets: List<String>?): DeleteItem {
    if (ownWalletName == null && usingWallets == null) return DeleteItem.Blocked(BOOK_UNREADABLE_LINE)
    val names = listOfNotNull(ownWalletName) + usingWallets.orEmpty()
    return if (names.isEmpty()) DeleteItem.Offered else DeleteItem.Blocked(inUseLine(names, "ยังใช้กุญแจนี้อยู่"))
}

/**
 * Pure. Penpot 1.8.A1 and A2's item for a one-key wallet. Removing needs another wallet to remain, a [saved] wallet, because an unsaved
 * one's words are only in memory, a book that could be read ([usingVaults] not null), and a key no several-key wallet uses.
 */
internal fun walletDeleteItem(listedWallets: Int, saved: Boolean, usingVaults: List<String>?): DeleteItem = when {
    listedWallets < 2 -> DeleteItem.Blocked("ลบไม่ได้ · ต้องมีกระเป๋าอย่างน้อย 1 ใบในเครื่อง")
    !saved -> DeleteItem.Blocked(WALLET_NOT_SAVED_LINE)
    usingVaults == null -> DeleteItem.Blocked(BOOK_UNREADABLE_LINE)
    usingVaults.isNotEmpty() -> DeleteItem.Blocked(inUseLine(usingVaults, "ยังใช้กุญแจเดียวกันอยู่"))
    else -> DeleteItem.Offered
}

/** Pure. Penpot 1.8.A3's item. A several-key wallet may be removed only while its book is saved and can be judged, as an unsaved one-key wallet may not be. */
internal fun vaultDeleteItem(saving: BookSaving): DeleteItem = when (saving) {
    BookSaving.MemoryOnly -> DeleteItem.Blocked("ลบไม่ได้ · Wallet นี้ยังไม่ได้บันทึกในเครื่อง")
    BookSaving.Unjudged -> DeleteItem.Blocked(BOOK_UNREADABLE_LINE)
    BookSaving.Ready -> DeleteItem.Offered
}

/**
 * Pure. A Jade or Safe 7 one-key wallet's item, on the same rule as a several-key wallet's, since both live in the book: offered while it
 * is saved and can be judged. Its key stays on the device, so no backup and no count of the phone's wallets holds it back.
 */
internal fun hardwareDeleteItem(saving: BookSaving): DeleteItem = when (saving) {
    BookSaving.MemoryOnly -> DeleteItem.Blocked(WALLET_NOT_SAVED_LINE)
    BookSaving.Unjudged -> DeleteItem.Blocked(BOOK_UNREADABLE_LINE)
    BookSaving.Ready -> DeleteItem.Offered
}

private const val WALLET_NOT_SAVED_LINE = "ลบไม่ได้ · กระเป๋านี้ยังไม่ได้บันทึกในเครื่อง"

private const val BOOK_UNREADABLE_LINE = "ลบไม่ได้ในตอนนี้ · อ่านรายการ Wallet ไม่ได้"

/**
 * Pure. Penpot 1.8.A2 and 2.1.A2 to A4's in-use lines, shared by a wallet's removal and the Keyring's delete: the [names] and the [verb],
 * then what to remove first. Penpot draws one and two names. Three or more extend its pattern.
 */
private fun inUseLine(names: List<String>, verb: String): String {
    val joined = if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " และ " + names.last()
    val next = when (names.size) {
        1 -> "ให้${deleteWords(names[0])} ออกก่อน"
        2 -> "ให้ลบทั้งสอง Wallet ออกก่อน"
        else -> "ให้ลบทั้ง ${names.size} Wallet ออกก่อน"
    }
    return "$joined $verb\nถ้าจะลบ $next"
}

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
    CoverableSheet(
        onDismissRequest = onCancel,
        sheetState = rememberFullSheetState(),
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

/**
 * Pure. A Jade or Safe 7 one-key wallet's confirm line, which no board draws: 1.8.8's words that the wallet leaves this phone, then
 * 2.1.9's that the device still keeps the key, since the device and not 24 words on this phone brings the wallet back.
 */
internal fun removeHardwareBody(kind: WalletKind): String =
    "กระเป๋าจะถูกลบออกจากเครื่องนี้ · " + when (kind) {
        WalletKind.JADE -> "Jade ยังเก็บกุญแจนี้ไว้"
        WalletKind.TREZOR_SAFE7 -> "Trezor ยังเก็บกุญแจนี้ไว้"
    }

/** Penpot 1.8.8's confirm for a Jade or Safe 7 one-key wallet, in the phone wallet's sheet. The owner check comes after its confirm. */
@Composable
internal fun RemoveHardwareSheet(name: String, kind: WalletKind, onConfirm: () -> Unit, onCancel: () -> Unit) {
    ConfirmSheet(tag = "dialog_remove_wallet", title = removeSheetTitle(name), body = removeHardwareBody(kind), onCancel = onCancel) {
        PrimaryCta("ลบออกจากเครื่องนี้", "cta_remove_confirm", onConfirm)
        Spacer(modifier = Modifier.height(10.dp))
        SecondaryCta("ยกเลิก", "cta_remove_cancel", onCancel)
    }
}
