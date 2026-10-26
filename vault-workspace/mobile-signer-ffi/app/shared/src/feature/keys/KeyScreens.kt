package com.mebit.app.feature.keys

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
    MebitScreen(
        eyebrow = "PAIR DEVICE",
        title = "จับคู่เครื่องอื่น",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_keys/pair-mebit",
    ) {
        Text(
            text = "Mock จนกว่า docs/04 #26 ตัดสินใจช่องทาง · กดจำลองจับคู่แล้วไปยืนยัน fingerprint",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(20.dp))
        PrimaryCta("จำลองจับคู่สำเร็จ", "cta_pair_mock", {
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
fun HardwareWalletScreen(
    store: KeyringStore,
    onEnrolled: () -> Unit,
    onUnsupported: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    MebitScreen(
        eyebrow = "HARDWARE WALLET",
        title = "เชื่อมต่ออุปกรณ์",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_keys/hardware",
    ) {
        Text(
            text = "Jade และ Trezor Safe 7 · Bluetooth เท่านั้น (ไม่มีกล้อง/QR) · หน้านี้เป็น demo enrollment เมื่อยังไม่มีอุปกรณ์จริง",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(20.dp))
        PrimaryCta("จำลอง Jade", "cta_hw_jade", {
            store.startHardwareMock(KeySourceLabel.Jade)
            if (store.pending != null) onEnrolled()
        })
        Spacer(modifier = Modifier.height(8.dp))
        PrimaryCta("จำลอง Trezor Safe 7", "cta_hw_trezor", {
            store.startHardwareMock(KeySourceLabel.TrezorSafe7)
            if (store.pending != null) onEnrolled()
        })
        Spacer(modifier = Modifier.height(8.dp))
        SecondaryCta("จำลองรุ่นอื่น (Unsupported)", "cta_hw_unsupported", onUnsupported)
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
    onMismatch: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    val pending = store.pending
    var observed by remember(pending?.fingerprint) { mutableStateOf("") }
    MebitScreen(
        eyebrow = "VERIFY KEY",
        title = "ยืนยัน fingerprint",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_keys/verify",
    ) {
        if (pending == null) {
            Text("ไม่มีกุญแจรอยืนยัน · กลับไปเพิ่มกุญแจ", color = MaterialTheme.colorScheme.error)
            return@MebitScreen
        }
        Text(
            text = "คาดหวัง · ${pending.fingerprint}",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag("expected_fp"),
        )
        Text(
            text = pending.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = observed,
            onValueChange = { observed = it },
            modifier = Modifier.fillMaxWidth().testTag("observed_fp"),
            label = { Text("fingerprint ที่อ่านได้") },
        )
        Spacer(modifier = Modifier.height(12.dp))
        PrimaryCta("ตรง · บันทึกเข้าคลัง", "cta_verify_match", {
            if (store.confirmFingerprint(observed.trim())) onMatched() else onMismatch()
        })
        Spacer(modifier = Modifier.height(8.dp))
        SecondaryCta("เติมค่าที่ถูกต้อง (demo)", "cta_fill_expected", {
            observed = pending.fingerprint
        })
        Spacer(modifier = Modifier.height(8.dp))
        SecondaryCta("จำลองไม่ตรง", "cta_verify_mismatch", {
            if (!store.confirmFingerprint("DEADBEEF")) onMismatch()
        })
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
fun T13UnsupportedModelScreen(
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    MebitScreen(
        eyebrow = "UNSUPPORTED MODEL",
        title = "รุ่นนี้ไม่รองรับ",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_critical/t13",
    ) {
        Text(
            text = "mebit รองรับเฉพาะ Trezor Safe 7 (และ Jade) · รุ่นอื่นถูกปฏิเสธที่ชั้น BLE (WrongDevice)",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(20.dp))
        PrimaryCta("กลับ", "cta_t13_back", { onBack?.invoke() })
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
