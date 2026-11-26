package com.mebit.app.feature.keys

import com.mebit.ffi.signer.KeyNetwork
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import com.mebit.app.ui.components.BottomActions
import com.mebit.app.ui.theme.MbBtcOrange
import com.mebit.app.ui.theme.MbPage
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import com.mebit.app.data.fingerprintGroups
import com.mebit.app.data.hardwareName
import com.mebit.app.data.keyBadge
import com.mebit.app.data.keyLine
import com.mebit.app.data.keyringOrder
import com.mebit.app.resources.Res
import com.mebit.app.resources.ic_jade_mark
import com.mebit.app.resources.ic_key
import com.mebit.app.resources.ic_mebit_mark
import com.mebit.app.resources.ic_qr
import com.mebit.app.resources.ic_trezor_mark
import com.mebit.app.resources.mebit_logo_on_light
import com.mebit.app.ui.components.MenuAction
import com.mebit.app.ui.components.TotalLine
import com.mebit.app.ui.theme.MbAmberInk
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbBorder
import com.mebit.app.ui.theme.MbChip
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
import androidx.compose.foundation.layout.heightIn
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
import com.mebit.app.ui.components.MebitScreen
import com.mebit.app.ui.components.OptionCard
import com.mebit.app.ui.components.PlusButton
import com.mebit.app.ui.components.SettingsGear
import com.mebit.app.ui.components.PrimaryCta
import com.mebit.app.ui.components.SecondaryCta
import org.jetbrains.compose.resources.painterResource

/** Longest key name Confirm Key takes, so a pasted paragraph cannot stretch the keyring rows. */
private const val KEY_NAME_MAX = 40

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
            leading = { KeyLogo(KeySourceLabel.ThisDevice, 24.dp) },
            onClick = onThisDevice,
        )
        Spacer(modifier = Modifier.height(11.dp))
        OptionCard(
            title = "mebit บนเครื่องอื่น",
            body = "จับคู่ผ่าน Bluetooth แล้วดึง xpub จากเครื่องนั้นมา",
            tag = "cta_add_mebit_other",
            leading = { Image(painterResource(Res.drawable.ic_qr), contentDescription = null, modifier = Modifier.size(24.dp)) },
            onClick = onMebitOther,
        )
        Spacer(modifier = Modifier.height(11.dp))
        OptionCard(
            title = "Hardware wallet",
            body = "อุปกรณ์แยกที่เก็บกุญแจไว้ออฟไลน์",
            tag = "cta_add_hardware",
            chips = listOf("Jade", "Trezor Safe 7"),
            chipsUnderIcon = true,
            leading = { Image(painterResource(Res.drawable.ic_key), contentDescription = null, modifier = Modifier.size(24.dp)) },
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
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
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
            // The refusal line sits under the buttons, where the keyboard would cover it. On iOS clearing the focus alone leaves the keyboard up.
            keyboard?.hide()
            focus.clearFocus(force = true)
            if (store.startMebitOtherFromPaste(paste)) onPaired()
        })
        if (store.network == KeyNetwork.TEST) {
            Spacer(modifier = Modifier.height(8.dp))
            SecondaryCta("จำลองจับคู่", "cta_pair_demo", {
                store.startMebitOtherMock()
                if (store.pending != null) onPaired()
            })
        }
        store.lastError?.let {
            Spacer(modifier = Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("error"))
        }
    }
}

/**
 * Penpot 2.4 Save Key, and 2.4.1 when a one-key wallet's key came from Key Source. The card of where the key came from, its name, which
 * the user may change, and its fingerprint. With [save] it is the last step of a one-key wallet (Wallet Created comes next): the tap saves
 * the wallet of the key a device just read, under the name typed here, and [save] answers null once it is saved, or the line that says why not.
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
    val saveKey: () -> Unit = {
        when {
            pending == null -> Unit
            // A key the book could not save stays here, with the book's line under the cards.
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
    }
    MebitScreen(
        eyebrow = if (pending == null || pending.source.isHardware()) "NEW KEY · HARDWARE" else "NEW KEY · MEBIT",
        title = "บันทึกกุญแจ",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = tag,
        bottom = pending?.let { p ->
            {
                BottomActions("บันทึกกุญแจ", "cta_verify_match", saveKey, "ยกเลิก (ไม่บันทึก)", "cta_verify_mismatch", {
                    store.discardPending()
                    onReconnect(p.source)
                }, enabled = !saving)
            }
        },
    ) {
        if (pending == null) {
            Text("ไม่มีกุญแจรอบันทึก · กลับไปเพิ่มกุญแจ", color = MaterialTheme.colorScheme.error)
            return@MebitScreen
        }
        // MebitScreen starts content 54 dp under the title's top and the board starts the device card 58 dp under it.
        Spacer(Modifier.height(4.dp))
        val (deviceName, deviceLine) = keyArrival(pending.source, pending.isMock)
        Row(
            Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(20.dp)).background(MbWhite).border(1.dp, MbBorder, RoundedCornerShape(20.dp)).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MbChip), contentAlignment = Alignment.Center) {
                KeyLogo(pending.source, 22.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(deviceName, color = MbNavyInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(deviceLine, color = MbSlate, fontSize = 11.sp, fontWeight = FontWeight.Light, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("verify_key_source"))
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.heightIn(min = 24.dp).clip(CircleShape).background(MbGreenWash).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                Text("✓ อ่านกุญแจแล้ว", color = MbSuccess, fontSize = 10.5.sp, fontWeight = FontWeight.Medium, lineHeight = 14.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MbWhite).border(1.dp, MbBorder, RoundedCornerShape(24.dp)).padding(20.dp),
        ) {
            Text("ชื่อกุญแจ (ตั้งเองได้)", color = MbSlate, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 14.sp)
            Spacer(Modifier.height(9.dp))
            BasicTextField(
                value = label,
                onValueChange = { label = it.take(KEY_NAME_MAX) },
                singleLine = true,
                textStyle = TextStyle(color = MbNavyInk, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                cursorBrush = SolidColor(MbBtcOrange),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "ชื่อกุญแจ" }.testTag("verify_key_name"),
                decorationBox = { field ->
                    Box(
                        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp)).background(MbPage).border(1.dp, MbBorder, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) { field() }
                },
            )
        }
        Spacer(Modifier.height(16.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MbWhite).border(1.dp, MbBorder, RoundedCornerShape(20.dp))
                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 20.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Fingerprint", color = MbBody, fontSize = 12.5.sp, fontWeight = FontWeight.Light, lineHeight = 17.sp)
                Text(fingerprintGroups(pending.fingerprint), color = MbNavyInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp, modifier = Modifier.testTag("expected_fp"))
            }
            Spacer(Modifier.height(10.dp))
            Text("รหัสประจำกุญแจนี้ ใช้จำกุญแจในไฟล์ BSMS และแอปอื่น", color = MbSlate, fontSize = 11.sp, fontWeight = FontWeight.Light, lineHeight = 15.sp)
        }
        saveLine?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("verify_save_failed"))
        }
        store.lastError?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("error"))
        }
    }
}

private fun KeySourceLabel.isHardware() = this == KeySourceLabel.Jade || this == KeySourceLabel.TrezorSafe7

/** Save Key's device card for a key from [source]: the name of where it came from, and how it came. */
internal fun keyArrival(source: KeySourceLabel, isMock: Boolean): Pair<String, String> {
    val name = when (source) {
        KeySourceLabel.ThisDevice -> "mebit · เครื่องนี้"
        KeySourceLabel.MebitOther -> "mebit · เครื่องอื่น"
        KeySourceLabel.Jade, KeySourceLabel.TrezorSafe7 -> hardwareName(source)
    }
    val line = when {
        isMock -> "จำลอง · ไม่ได้เชื่อมต่ออุปกรณ์จริง"
        source.isHardware() -> "เชื่อมต่อผ่าน Bluetooth"
        source == KeySourceLabel.MebitOther -> "นำเข้าจาก xpub หรือ BSMS"
        else -> "สร้างบนเครื่องนี้"
    }
    return name to line
}

/**
 * Penpot 2.1, in 6.2's order and subtitle while a key is unconfirmed. 6.2's two buttons and its "ไปที่ Wallet" text are not built.
 * Each key's ⋯ holds [deleteItem]'s item, and an offered delete asks first (2.1.9). A key whose 24 words are on this phone and whose
 * backup is unconfirmed also gets [backup]'s "สำรองกุญแจ 24 คำ" above it, an item no board draws yet.
 */
@Composable
fun KeyringScreen(
    store: KeyringStore,
    deleteItem: (SavedKey) -> DeleteItem,
    backup: (SavedKey) -> (() -> Unit)? = { null },
    onAddKey: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
    onSettings: () -> Unit = {},
) {
    val keys = keyringOrder(store.keys)
    var deleting by remember { mutableStateOf<SavedKey?>(null) }
    var deleteFailed by remember { mutableStateOf(false) }
    MebitScreen(
        eyebrow = null,
        // The title is drawn below so the + shares its row, as on the board.
        title = null,
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_keys/keyring",
        // Penpot 2.1's header is 3.6's: the logo, the gear over the +, both ending 29 dp from the screen's right edge, and the total line.
        headerContent = {
            Image(painterResource(Res.drawable.mebit_logo_on_light), contentDescription = "mebit", modifier = Modifier.size(width = 99.5.dp, height = 26.dp))
        },
        trailing = { SettingsGear(onSettings, Modifier.padding(end = 9.dp)) },
    ) {
        Spacer(modifier = Modifier.height(15.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(MovieRoute.Keyring.titleTh, color = MbNavyInk, fontSize = 28.sp, fontWeight = FontWeight.Medium, lineHeight = 34.sp, modifier = Modifier.weight(1f).testTag("title"))
            PlusButton("cta_keyring_add", "เพิ่มกุญแจ", onAddKey, Modifier.padding(end = 9.dp))
        }
        if (keys.isEmpty()) {
            Text("ยังไม่มีกุญแจในคลัง", color = MbBody, fontSize = 13.5.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(top = 4.dp).testTag("keyring_empty"))
        } else {
            TotalLine("${keys.size} กุญแจ", "keyring_total")
        }
        Spacer(modifier = Modifier.height(25.dp))
        if (deleteFailed) {
            Text("ลบกุญแจไม่สำเร็จ ลองอีกครั้ง", color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.testTag("delete_failed"))
            Spacer(modifier = Modifier.height(12.dp))
        }
        keys.forEach { key ->
            KeyCard(key, deleteItem(key), backup(key), onOpen = { deleteFailed = false }) { deleting = key }
            Spacer(modifier = Modifier.height(10.dp))
        }
    }
    deleting?.let { key ->
        // A delete refused now, because a wallet started using the key or the save failed, leaves the key on the list and says so.
        DeleteKeySheet(key, onConfirm = { deleting = null; deleteFailed = !store.tryDelete(key.id) }, onCancel = { deleting = null })
    }
}

@Composable
internal fun KeyLogo(source: KeySourceLabel, height: Dp) {
    val (logo, widthPerHeight, scale) = when (source) {
        KeySourceLabel.ThisDevice, KeySourceLabel.MebitOther -> Triple(Res.drawable.ic_mebit_mark, 1f, 23.56f / 24f)
        KeySourceLabel.Jade -> Triple(Res.drawable.ic_jade_mark, 1f, 1f)
        KeySourceLabel.TrezorSafe7 -> Triple(Res.drawable.ic_trezor_mark, 17f / 24.355f, 1f)
    }
    Image(painterResource(logo), contentDescription = null, modifier = Modifier.height(height * scale).aspectRatio(widthPerHeight))
}

/**
 * Penpot 2.1's key card, 62 dp high. Its texts merge into one node tagged `key_<id>`, which opens nothing. The ⋯ is that node's
 * sibling, not its child, so it stays an element of its own on both platforms.
 */
@Composable
private fun KeyCard(key: SavedKey, item: DeleteItem, onBackup: (() -> Unit)?, onOpen: () -> Unit, onDelete: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
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
            Box(Modifier.offset(x = 16.dp, y = 12.dp).size(38.dp).clip(RoundedCornerShape(12.dp)).background(MbChip), contentAlignment = Alignment.Center) {
                KeyLogo(key.source, 21.dp)
            }
            // 132 dp at the end is the ⋯, the pill and their gaps, so the texts are 164 dp wide on the board's 402 dp screen and end in … before the pill on a narrower one.
            Text(key.label, color = MbNavyInk, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(start = 66.dp, top = 10.dp, end = 132.dp))
            Text(keyLine(key), color = MbSlate, fontSize = 11.sp, fontWeight = FontWeight.Light, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(start = 66.dp, top = 31.dp, end = 132.dp))
            Box(
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 46.dp, top = 19.dp).size(width = 72.dp, height = 24.dp).clip(CircleShape).background(if (key.verified) MbGreenWash else MbIconWash),
                // Centred, as Penpot 2.1 draws both labels.
                contentAlignment = Alignment.Center,
            ) {
                Text(keyBadge(key.verified), color = if (key.verified) MbSuccess else MbAmberInk, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
            }
        }
        // The dots end 18 dp inside the card's right edge, centred in their 44 dp target.
        CardDeleteMenu(
            key.id,
            "ลบกุญแจ",
            item,
            onDark = false,
            onDelete = onDelete,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 5.dp),
            onOpen = onOpen,
            extra = onBackup?.let { MenuAction("สำรองกุญแจ 24 คำ", "menu_backup_${key.id}", it) },
        )
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
