package com.mebit.app.feature.keys

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import com.mebit.app.data.fingerprintGroups
import com.mebit.app.data.hardwareName
import com.mebit.app.data.keyBadge
import com.mebit.app.data.keyLine
import com.mebit.app.data.keyMonogram
import com.mebit.app.data.keyringOrder
import com.mebit.app.data.keyringSubtitle
import com.mebit.app.getPlatform
import com.mebit.app.ui.theme.MbAmberInk
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbBorder
import com.mebit.app.ui.theme.MbChip
import com.mebit.app.ui.theme.MbEyebrow
import com.mebit.app.ui.theme.MbGreenWash
import com.mebit.app.ui.theme.MbIconWash
import com.mebit.app.ui.theme.MbNavy
import com.mebit.app.ui.theme.MbMuted
import com.mebit.app.ui.theme.MbNavyInk
import com.mebit.app.ui.theme.MbSuccess
import com.mebit.app.ui.theme.MbSlate
import com.mebit.app.ui.theme.MbWhite
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import com.mebit.app.data.KeySourceLabel
import com.mebit.app.data.KeyringStore
import com.mebit.app.data.SavedKey
import com.mebit.app.feature.send.ConfirmSheet
import com.mebit.app.feature.send.deleteKeyBody
import com.mebit.app.feature.send.removeSheetTitle
import com.mebit.app.navigation.MovieRoute
import com.mebit.app.ui.components.CardDeleteMenu
import com.mebit.app.ui.components.DeleteItem
import com.mebit.app.ui.components.HardwareMark
import com.mebit.app.ui.components.MebitScreen
import com.mebit.app.ui.components.OptionCard
import com.mebit.app.ui.components.PhoneMark
import com.mebit.app.ui.components.PlusButton
import com.mebit.app.ui.components.TwoPhonesMark
import com.mebit.app.ui.components.PrimaryCta
import com.mebit.app.ui.components.SecondaryCta

/** Longest key name Confirm Key takes, so a pasted paragraph cannot stretch the keyring rows. */
private const val KEY_NAME_MAX = 40

/** The grey Penpot 2.4.1 gives the "ไม่ตรง" link, which the theme has no token for. */
private val MismatchInk = Color(0xFF545454)

/** Penpot 2.4.1's name suggestion: 24 dp high, where the shared [com.mebit.app.ui.components.HwChip] is 28. */
@Composable
private fun NameChip(label: String, onPick: () -> Unit) {
    Box(
        Modifier.height(24.dp).clip(CircleShape).background(MbChip).clickable(role = Role.Button, onClick = onPick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = MbMuted, fontSize = 10.5.sp, fontWeight = FontWeight.Medium, lineHeight = 14.sp)
    }
}

/** Penpot 2.2: one list of four cards in the board's order. The first and last add a wallet, the other two a key for a Vault. [line] says why a refused wallet add did not start. */
@Composable
fun AddKeyScreen(
    line: String?,
    onThisDevice: () -> Unit,
    onMebitOther: () -> Unit,
    onHardware: () -> Unit,
    onImport: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    MebitScreen(
        eyebrow = MovieRoute.AddKey.eyebrow,
        title = MovieRoute.AddKey.titleTh,
        subtitle = "กุญแจคือสิ่งที่ใช้เซ็นอนุมัติการใช้จ่าย",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_keys/add",
    ) {
        // The board's cards start at y 226, 27 dp under where the shared scaffold ends a subtitle.
        Spacer(modifier = Modifier.height(27.dp))
        OptionCard(
            title = "mebit บนเครื่องนี้",
            body = "สร้าง seed ใหม่เก็บใน secure enclave ของเครื่อง",
            tag = "cta_add_this_device",
            leading = { PhoneMark() },
            onClick = onThisDevice,
        )
        Spacer(modifier = Modifier.height(11.dp))
        OptionCard(
            title = "mebit บนเครื่องอื่น",
            body = "จับคู่ผ่าน Bluetooth แล้วดึง xpub จากเครื่องนั้นมา",
            tag = "cta_add_mebit_other",
            leading = { TwoPhonesMark() },
            onClick = onMebitOther,
        )
        Spacer(modifier = Modifier.height(11.dp))
        OptionCard(
            title = "Hardware wallet",
            body = "อุปกรณ์แยกที่เก็บกุญแจไว้ออฟไลน์",
            tag = "cta_add_hardware",
            chips = listOf("Jade", "Trezor Safe 7"),
            leading = { HardwareMark() },
            onClick = onHardware,
        )
        Spacer(modifier = Modifier.height(13.dp))
        OptionCard(
            title = "นำเข้ากระเป๋าเดิม",
            body = "ใช้ 24 คำที่คุณจดไว้",
            tag = "cta_add_import",
            leading = { Text("24", color = MbNavy, fontSize = 14.sp, fontWeight = FontWeight.Bold) },
            onClick = onImport,
        )
        line?.let {
            Spacer(modifier = Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.testTag("error"))
        }
    }
}

@Composable
fun PairMebitOtherScreen(
    store: KeyringStore,
    onPaired: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    var paste by remember { mutableStateOf("") }
    MebitScreen(
        eyebrow = "PAIR DEVICE",
        title = "จับคู่เครื่องอื่น",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_keys/pair-mebit",
    ) {
        Text(
            text = "วาง fingerprint, xpub และ path จากเครื่องอื่น หรือวาง BSMS 1.0",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = paste,
            onValueChange = { paste = it },
            modifier = Modifier.fillMaxWidth().testTag("pair_paste"),
            minLines = 4,
            label = { Text("ข้อความจากเครื่องอื่น") },
        )
        Spacer(modifier = Modifier.height(12.dp))
        PrimaryCta("นำเข้าจากข้อความ", "cta_pair_paste", {
            if (store.startMebitOtherFromPaste(paste)) onPaired()
        })
        Spacer(modifier = Modifier.height(8.dp))
        SecondaryCta("จำลองจับคู่", "cta_pair_demo", {
            store.startMebitOtherMock()
            if (store.pending != null) onPaired()
        })
        store.lastError?.let {
            Spacer(modifier = Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("error"))
        }
    }
}

/**
 * Penpot 2.4.1 Confirm Key, one tap. With [save] it is the last step of a one-key wallet (Wallet Created comes next): the tap saves the
 * wallet of the key a device just read, under the name typed here, and [save] answers null once it is saved, or the line that says why not.
 */
@Composable
fun VerifyKeyScreen(
    store: KeyringStore,
    onMatched: () -> Unit,
    onMismatch: (KeySourceLabel) -> Unit,
    onReconnect: (KeySourceLabel) -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
    save: (suspend (label: String) -> String?)? = null,
    tag: String = "screen_keys/verify",
) {
    val pending = store.pending
    var label by remember(pending) { mutableStateOf(pending?.label.orEmpty()) }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var saveLine by remember(pending) { mutableStateOf<String?>(null) }
    val nameFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    MebitScreen(
        eyebrow = "VERIFY KEY",
        title = null,
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = tag,
    ) {
        if (pending == null) {
            Text("ไม่มีกุญแจรอยืนยัน · กลับไปเพิ่มกุญแจ", color = MaterialTheme.colorScheme.error)
            return@MebitScreen
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).background(MbSuccess, CircleShape).clearAndSetSemantics {},
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", color = MbWhite, fontSize = 20.sp)
            }
            Spacer(Modifier.width(14.dp))
            Text("ยืนยัน fingerprint", color = MbNavyInk, fontSize = 28.sp, fontWeight = FontWeight.Medium, modifier = Modifier.testTag("title"))
        }
        Spacer(Modifier.height(21.dp))
        val hardware = pending.source == KeySourceLabel.Jade || pending.source == KeySourceLabel.TrezorSafe7
        val source = when (pending.source) {
            KeySourceLabel.ThisDevice -> "mebit · เครื่องนี้"
            KeySourceLabel.MebitOther -> "mebit · เครื่องอื่น"
            KeySourceLabel.Jade, KeySourceLabel.TrezorSafe7 -> hardwareName(pending.source)
        } + when {
            pending.isMock -> " · จำลอง"
            hardware -> " · Bluetooth"
            else -> ""
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MbWhite)
                .border(1.dp, MbBorder, RoundedCornerShape(24.dp)).padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 32.dp),
        ) {
            Text("ชื่อกุญแจ (ตั้งเองได้)", color = MbSlate, fontSize = 10.5.sp, lineHeight = 14.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = label,
                    onValueChange = { label = it.take(KEY_NAME_MAX) },
                    singleLine = true,
                    textStyle = TextStyle(color = MbNavyInk, fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f).focusRequester(nameFocus).semantics { contentDescription = "ชื่อกุญแจ" }.testTag("verify_key_name"),
                )
                TextButton(
                    onClick = { nameFocus.requestFocus(); keyboard?.show() },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("verify_edit_name"),
                ) {
                    Text("แก้ไข", color = MbNavy, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(MbNavy))
            Spacer(Modifier.height(12.dp))
            // Penpot 2.4.1: the first chip is the name the key arrived with, two fit on the first row.
            val suggestions = listOf(pending.label, "กุญแจบ้าน", "กุญแจสำรอง ตู้เซฟ").distinct()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestions.take(2).forEach { suggestion -> NameChip(suggestion) { label = suggestion } }
                }
                suggestions.drop(2).forEach { suggestion -> NameChip(suggestion) { label = suggestion } }
            }
            Spacer(Modifier.height(7.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(MbBorder))
            Spacer(Modifier.height(15.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("แหล่งที่มา", color = MbSlate, fontSize = 12.5.sp, lineHeight = 16.sp)
                // The board ends both values 20 dp inside the card's edge.
                Text(source, color = MbNavyInk, fontSize = 12.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(end = 20.dp).testTag("verify_key_source"))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Fingerprint", color = MbSlate, fontSize = 12.5.sp, lineHeight = 16.sp)
                Text(
                    fingerprintGroups(pending.fingerprint),
                    color = MbNavyInk,
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(end = 20.dp).testTag("expected_fp"),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "เทียบ fingerprint กับที่แสดงบน${if (hardware && !pending.isMock) "อุปกรณ์" else "แหล่งกุญแจ"}ให้ตรงกันก่อนกดยืนยัน",
                color = MbMuted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(MbChip).padding(14.dp),
            )
        }
        Spacer(Modifier.height(22.dp))
        PrimaryCta(if (hardware && !pending.isMock) "ตรงกับที่อุปกรณ์แสดง" else "ตรงกับที่แหล่งกุญแจแสดง", "cta_verify_match", {
            when {
                // A key the book could not save stays here, with the book's line under the buttons.
                save == null -> if (store.confirmFingerprint(pending.fingerprint, label)) onMatched() else if (store.lastMismatch) onMismatch(pending.source)
                // One save at a time, so a double tap cannot save two wallets.
                !saving -> scope.launch {
                    saving = true
                    try {
                        saveLine = save(label.trim().ifEmpty { pending.label })
                    } finally {
                        saving = false
                    }
                    if (saveLine == null) onMatched()
                }
            }
        }, enabled = !saving)
        saveLine?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("verify_save_failed"))
        }
        TextButton(
            onClick = {
                store.discardPending()
                onReconnect(pending.source)
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("cta_verify_mismatch"),
        ) {
            Text("ไม่ตรง · กลับไปเชื่อมต่อ (ไม่บันทึก)", color = MismatchInk, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        store.lastError?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("error"))
        }
    }
}

/**
 * Penpot 2.1, in 6.2's order and subtitle while a key is unconfirmed. 6.2's two buttons and its "ไปที่ Wallet" text are not built.
 * Each key's ⋯ holds [deleteItem]'s item, and an offered delete asks first (2.1.9).
 */
@Composable
fun KeyringScreen(
    store: KeyringStore,
    deleteItem: (SavedKey) -> DeleteItem,
    onAddKey: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    val keys = keyringOrder(store.keys)
    val phone = remember { getPlatform().phoneMonogram }
    var deleting by remember { mutableStateOf<SavedKey?>(null) }
    var deleteFailed by remember { mutableStateOf(false) }
    MebitScreen(
        eyebrow = MovieRoute.Keyring.eyebrow,
        // The title is drawn below so the + shares its row, as on the board.
        title = null,
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_keys/keyring",
    ) {
        Spacer(modifier = Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(MovieRoute.Keyring.titleTh, color = MbNavyInk, fontSize = 28.sp, fontWeight = FontWeight.Medium, lineHeight = 34.sp, modifier = Modifier.weight(1f).testTag("title"))
            // Penpot 2.1 ends the + 1 dp inside the screen's padding, where 1.8 ends it 9 dp past.
            PlusButton("cta_keyring_add", "เพิ่มกุญแจ", onAddKey, Modifier.padding(end = 1.dp))
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            keyringSubtitle(keys),
            color = MbBody,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Light,
            lineHeight = 20.sp,
            modifier = Modifier.testTag(if (keys.isEmpty()) "keyring_empty" else "keyring_subtitle"),
        )
        if (deleteFailed) {
            Spacer(modifier = Modifier.height(8.dp))
            Text("ลบกุญแจไม่สำเร็จ ลองอีกครั้ง", color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.testTag("delete_failed"))
        }
        Spacer(modifier = Modifier.height(29.dp))
        keys.forEach { key ->
            KeyCard(key, phone, deleteItem(key), onOpen = { deleteFailed = false }) { deleting = key }
            Spacer(modifier = Modifier.height(10.dp))
        }
    }
    deleting?.let { key ->
        // A delete refused now, because a wallet started using the key or the save failed, leaves the key on the list and says so.
        DeleteKeySheet(key, onConfirm = { deleting = null; deleteFailed = !store.tryDelete(key.id) }, onCancel = { deleting = null })
    }
}

/**
 * Penpot 2.1's key card, 62 dp high. Its texts merge into one node tagged `key_<id>`, which opens nothing. The ⋯ is that node's
 * sibling, not its child, so it stays an element of its own on both platforms.
 */
@Composable
private fun KeyCard(key: SavedKey, phone: String, item: DeleteItem, onOpen: () -> Unit, onDelete: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val hardware = key.source == KeySourceLabel.Jade || key.source == KeySourceLabel.TrezorSafe7
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(62.dp)
            .shadow(4.dp, shape, clip = false)
            .clip(shape)
            .background(MbWhite)
            .border(1.dp, MbBorder, shape),
    ) {
        Box(Modifier.matchParentSize().semantics(mergeDescendants = true) {}.testTag("key_${key.id}")) {
            Box(Modifier.offset(x = 16.dp, y = 12.dp).size(38.dp).clip(RoundedCornerShape(12.dp)).background(MbIconWash), contentAlignment = Alignment.Center) {
                Text(keyMonogram(key.source, phone), color = if (hardware) MbEyebrow else MbNavy, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
            }
            // 132 dp at the end is the ⋯, the pill and their gaps, so the texts are 164 dp wide on the board's 402 dp screen and end in … before the pill on a narrower one.
            Text(key.label, color = MbNavyInk, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(start = 66.dp, top = 10.dp, end = 132.dp))
            Text(keyLine(key), color = MbSlate, fontSize = 11.sp, fontWeight = FontWeight.Light, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(start = 66.dp, top = 31.dp, end = 132.dp))
            Box(
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 46.dp, top = 19.dp).size(width = 72.dp, height = 24.dp).clip(CircleShape).background(if (key.verified) MbGreenWash else MbIconWash),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(keyBadge(key.verified), color = if (key.verified) MbSuccess else MbAmberInk, fontSize = 10.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 10.dp))
            }
        }
        // The dots end 18 dp inside the card's right edge, centred in their 44 dp target.
        CardDeleteMenu(key.id, "ลบกุญแจ", item, onDark = false, onDelete = onDelete, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 5.dp), onOpen = onOpen)
    }
}

/** Penpot 2.1.9. No owner check: the Keyring holds only a key's public half, and the device that made the key keeps it. */
@Composable
private fun DeleteKeySheet(key: SavedKey, onConfirm: () -> Unit, onCancel: () -> Unit) {
    ConfirmSheet(tag = "dialog_delete_key", title = removeSheetTitle(key.label), body = deleteKeyBody(key.source), onCancel = onCancel) {
        PrimaryCta("ลบกุญแจ", "cta_delete_key_confirm", onConfirm)
        Spacer(modifier = Modifier.height(10.dp))
        SecondaryCta("ยกเลิก", "cta_delete_key_cancel", onCancel)
    }
}

@Composable
fun T14VerifyMismatchScreen(
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    MebitScreen(
        eyebrow = "VERIFY KEY",
        title = "Fingerprint ไม่ตรง",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_critical/t14",
    ) {
        Text(
            text = "ไม่บันทึกเข้าคลัง · ตรวจสายตาบนอุปกรณ์แล้วลองใหม่",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("mismatch_message"),
        )
        Spacer(modifier = Modifier.height(20.dp))
        PrimaryCta("กลับ", "cta_t14_back", { onBack?.invoke() })
    }
}

@Composable
fun T15KeyringEmptyScreen(
    onAddKey: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    MebitScreen(
        eyebrow = "KEYRING",
        title = "ยังไม่มีกุญแจในคลัง",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_critical/t15-empty",
    ) {
        Text("เพิ่มกุญแจก่อนสร้าง Wallet", style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(20.dp))
        PrimaryCta("เพิ่มกุญแจ", "cta_t15_add", onAddKey)
    }
}

@Composable
fun T15UnverifiedFirstScreen(
    onVerify: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    MebitScreen(
        eyebrow = "KEYRING",
        title = "กุญแจยังไม่ยืนยัน",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_critical/t15-unverified",
    ) {
        Text("ยืนยัน fingerprint ก่อนใช้ใน Wallet", style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(20.dp))
        PrimaryCta("ไปยืนยัน", "cta_t15_verify", onVerify)
    }
}
