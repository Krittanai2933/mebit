package com.mebit.app.feature.send

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.mebit.app.data.CreatedVault
import com.mebit.app.data.KeySourceLabel
import com.mebit.app.data.KeyringStore
import com.mebit.app.data.MultisigSendDraft
import com.mebit.app.ui.components.MebitScreen
import com.mebit.app.ui.components.PrimaryCta
import com.mebit.app.ui.components.SecondaryCta

@Composable
fun M1SendVaultScreen(
    vault: CreatedVault?,
    draft: MultisigSendDraft,
    onContinue: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    MebitScreen(
        eyebrow = "SEND · VAULT",
        title = "ส่งจาก Vault",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_send/m1",
    ) {
        if (vault == null) {
            Text("ยังไม่มี Vault · สร้างก่อน", color = MaterialTheme.colorScheme.error)
            return@MebitScreen
        }
        Text("${vault.name} · ${vault.m}-of-${vault.n}", style = MaterialTheme.typography.bodyLarge)
        Text(vault.address, style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.height(12.dp))
        Text("ถึง · ${draft.destination}")
        Text("จำนวน · ${draft.amountSats} sats (demo)")
        Text("ต้องเซ็น ${vault.m} จาก ${vault.n}", color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(20.dp))
        PrimaryCta("ต่อไป · เก็บลายเซ็น", "cta_m1_collect", {
            draft.resetSignatures()
            onContinue()
        })
    }
}

@Composable
fun M2CollectSignaturesScreen(
    vault: CreatedVault?,
    store: KeyringStore,
    draft: MultisigSendDraft,
    onSignJade: () -> Unit,
    onReady: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    val m = vault?.m ?: 2
    MebitScreen(
        eyebrow = "COLLECT SIGNATURES",
        title = "เก็บลายเซ็น",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_send/m2",
    ) {
        Text(
            text = "ได้แล้ว ${draft.signedFingerprints.size} / $m · PSBT mock",
            modifier = Modifier.testTag("m2_progress"),
        )
        Text(draft.psbtHex, style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.height(12.dp))
        val vaultKeys = vault?.keyFingerprints.orEmpty().mapNotNull { fp ->
            store.keys.find { it.fingerprint.equals(fp, ignoreCase = true) }
        }
        vaultKeys.forEach { key ->
            val done = key.fingerprint.uppercase() in draft.signedFingerprints
            Text(
                "${key.label} · ${key.fingerprint.take(8)}" + if (done) " ✓" else "",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!done) {
                when (key.source) {
                    KeySourceLabel.Jade, KeySourceLabel.TrezorSafe7 ->
                        SecondaryCta("เซ็นบน ${key.source.name} (BLE)", "cta_sign_hw_${key.id}", onSignJade)
                    else ->
                        SecondaryCta("เซ็นบนเครื่องนี้ (mock)", "cta_sign_soft_${key.id}", {
                            draft.markSigned(key.fingerprint)
                        })
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        if (vaultKeys.isEmpty()) {
            Text("ไม่มีกุญแจของ Vault ในคลัง — ใช้ demo soft-sign", color = MaterialTheme.colorScheme.error)
            SecondaryCta("จำลองลายเซ็น #1", "cta_sign_demo_1", { draft.markSigned("DEMO0001") })
            SecondaryCta("จำลองลายเซ็น #2", "cta_sign_demo_2", { draft.markSigned("DEMO0002") })
        }
        Spacer(modifier = Modifier.height(16.dp))
        PrimaryCta(
            "พร้อมส่งธุรกรรม",
            "cta_m2_ready",
            onReady,
            enabled = draft.readyToBroadcast(m),
        )
    }
}

@Composable
fun M3SignOnJadeScreen(
    draft: MultisigSendDraft,
    onSigned: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    MebitScreen(
        eyebrow = "SIGN ON JADE",
        title = "เซ็นบน Jade",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_send/m3",
    ) {
        Text(
            text = "Bluetooth เท่านั้น · demo ไม่เปิดลิงก์ BLE จริงบน simulator",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text("PSBT · ${draft.psbtHex}", style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.height(20.dp))
        PrimaryCta("จำลองเซ็นสำเร็จบน Jade", "cta_m3_signed", {
            draft.markSigned("JADEDEMO")
            onSigned()
        })
    }
}

@Composable
fun M4ReadyToBroadcastScreen(
    vault: CreatedVault?,
    draft: MultisigSendDraft,
    onBroadcast: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    val m = vault?.m ?: 2
    MebitScreen(
        eyebrow = "READY TO BROADCAST",
        title = "พร้อมส่งธุรกรรม",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_send/m4",
    ) {
        Text("ลายเซ็นครบแล้ว · ${draft.signedFingerprints.size} / $m")
        Text(draft.signedFingerprints.joinToString(), style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.height(12.dp))
        if (draft.broadcastTxid.isNotBlank()) {
            Text("txid · ${draft.broadcastTxid}", modifier = Modifier.testTag("m4_txid"))
        }
        Spacer(modifier = Modifier.height(20.dp))
        PrimaryCta("Broadcast (mock)", "cta_m4_broadcast", {
            if (draft.broadcastMock(m)) onBroadcast()
        }, enabled = draft.readyToBroadcast(m))
        draft.lastError?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}
