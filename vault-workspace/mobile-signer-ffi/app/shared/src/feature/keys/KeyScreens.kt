package com.mebit.app.feature.keys

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.mebit.app.ui.theme.MbBorder
import com.mebit.app.ui.theme.MbChip
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
import androidx.compose.runtime.setValue
import com.mebit.app.data.KeySourceLabel
import com.mebit.app.data.KeyringStore
import com.mebit.app.ui.components.MebitScreen
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

@Composable
fun AddKeyScreen(
    store: KeyringStore,
    onThisDevice: () -> Unit,
    onMebitOther: () -> Unit,
    onHardware: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    MebitScreen(
        eyebrow = "ADD KEY",
        title = "เพิ่มกุญแจ",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_keys/add",
    ) {
        Text(
            text = "สามทาง · มือถือนี้ · mebit เครื่องอื่น · hardware wallet (BLE)",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(20.dp))
        PrimaryCta("mebit บนเครื่องนี้", "cta_add_this_device", {
            store.startThisDevice()
            if (store.pending != null) onThisDevice()
        })
        Spacer(modifier = Modifier.height(8.dp))
        SecondaryCta("mebit เครื่องอื่น", "cta_add_mebit_other", onMebitOther)
        Spacer(modifier = Modifier.height(8.dp))
        SecondaryCta("Hardware wallet", "cta_add_hardware", onHardware)
        store.lastError?.let {
            Spacer(modifier = Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("error"))
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

@Composable
fun VerifyKeyScreen(
    store: KeyringStore,
    onMatched: () -> Unit,
    onMismatch: (KeySourceLabel) -> Unit,
    onReconnect: (KeySourceLabel) -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    val pending = store.pending
    var label by remember(pending) { mutableStateOf(pending?.label.orEmpty()) }
    val nameFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    MebitScreen(
        eyebrow = "VERIFY KEY",
        title = null,
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_keys/verify",
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
            if (store.confirmFingerprint(pending.fingerprint, label)) onMatched() else onMismatch(pending.source)
        })
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

@Composable
fun KeyringScreen(
    store: KeyringStore,
    onAddKey: () -> Unit,
    onFirstVault: () -> Unit,
    onDeleteBlocked: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    MebitScreen(
        eyebrow = "KEYRING",
        title = "คลังกุญแจ",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_keys/keyring",
    ) {
        if (store.keys.isEmpty()) {
            Text(
                text = "ยังไม่มีกุญแจในคลัง",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag("keyring_empty"),
            )
        } else {
            store.keys.forEach { key ->
                Text(
                    text = key.label + if (key.verified) " · verified" else " · unverified",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("key_${key.id}"),
                )
                Text(
                    text = key.fingerprint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                SecondaryCta("ลบ ${key.label}", "cta_delete_${key.id}", {
                    if (!store.tryDelete(key.id)) onDeleteBlocked()
                })
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        PrimaryCta("เพิ่มกุญแจ", "cta_keyring_add", onAddKey)
        if (store.verifiedCount() >= 1) {
            Spacer(modifier = Modifier.height(8.dp))
            SecondaryCta("สร้าง Vault", "cta_keyring_vault", onFirstVault)
        }
        store.lastError?.let {
            Spacer(modifier = Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("error"))
        }
    }
}

@Composable
fun KeyringFirstVaultScreen(
    store: KeyringStore,
    onContinuePolicy: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    MebitScreen(
        eyebrow = "NEW VAULT",
        title = "รวมกุญแจสำหรับ Vault",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_keys/keyring-first-vault",
    ) {
        Text(
            text = "แนะนำ 3 ดอกสำหรับ 2-of-3 · personal_custody · มี verified ${store.verifiedCount()} ดอก",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        store.keys.filter { it.verified }.forEach { key ->
            Text("• ${key.label} · ${key.fingerprint}", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(modifier = Modifier.height(20.dp))
        PrimaryCta(
            "ต่อไป · นโยบาย Vault",
            "cta_to_policy",
            onContinuePolicy,
            enabled = store.verifiedCount() >= 1,
        )
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
        Text("เพิ่มกุญแจก่อนสร้าง Vault", style = MaterialTheme.typography.bodyLarge)
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
        Text("ยืนยัน fingerprint ก่อนใช้ใน Vault", style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(20.dp))
        PrimaryCta("ไปยืนยัน", "cta_t15_verify", onVerify)
    }
}

@Composable
fun T15DeleteBlockedScreen(
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    MebitScreen(
        eyebrow = "KEYRING",
        title = "ลบกุญแจไม่ได้",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_critical/t15-delete",
    ) {
        Text(
            text = "ยังถูกใช้ใน Vault · ลบ Vault หรือถอดกุญแจออกจากนโยบายก่อน",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(20.dp))
        PrimaryCta("กลับ", "cta_t15_delete_back", { onBack?.invoke() })
    }
}
