package com.mebit.app.feature.send

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mebit.app.data.CreatedVault
import com.mebit.app.data.KeySourceLabel
import com.mebit.app.data.KeyringStore
import com.mebit.app.data.MultisigSendDraft
import com.mebit.app.data.TESTNET_MULTISIG_ACCOUNT
import com.mebit.app.ui.components.MebitScreen
import com.mebit.app.ui.components.PrimaryCta
import com.mebit.app.ui.components.SecondaryCta
import com.mebit.ffi.signer.PairingCodes
import com.mebit.ffi.signer.WalletKind
import com.mebit.ffi.signer.WalletLink
import com.mebit.ffi.signer.scanWallets
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        subtitle = vault?.let { "ต้องเซ็น ${it.m} จาก ${it.n} กุญแจก่อนส่ง" },
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
        Text("จำนวน · ${draft.amountSats} sats")
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
        subtitle = "ใช้กุญแจดอกไหนก็ได้ $m ดอก",
        tag = "screen_send/m2",
    ) {
        Text(
            text = "ได้แล้ว ${draft.signedFingerprints.size} / $m",
            modifier = Modifier.testTag("m2_progress"),
        )
        Text(draft.psbtBase64.take(48) + "…", style = MaterialTheme.typography.bodySmall)
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
                        SecondaryCta("เซ็นบนเครื่องนี้", "cta_sign_soft_${key.id}", {
                            if (draft.policyAllowsSign()) draft.markSigned(key.fingerprint)
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
        draft.lastError?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
fun M3SignOnJadeScreen(
    draft: MultisigSendDraft,
    onSigned: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busyMsg by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    MebitScreen(
        eyebrow = "SIGN ON JADE",
        title = "เซ็นบน Jade",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        subtitle = "Bluetooth · WalletLink.signPsbt · ตรวจปลายทางก่อนเซ็น",
        tag = "screen_send/m3",
    ) {
        Text("PSBT · ${draft.psbtBase64.take(64)}…", style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.height(20.dp))
        PrimaryCta("เซ็นบน Jade (BLE)", "cta_m3_sign_live", {
            scope.launch {
                busyMsg = "กำลังสแกน…"
                try {
                    if (!draft.policyAllowsSign()) {
                        busyMsg = draft.lastError
                        return@launch
                    }
                    val found = scanWallets(WalletKind.JADE).firstOrNull()
                        ?: error("ไม่พบ Jade")
                    val link = WalletLink(found)
                    try {
                        link.connect(object : PairingCodes {
                            override suspend fun pairingCode(): String? = null
                        })
                        link.unlock()
                        val account = link.accountXpub(TESTNET_MULTISIG_ACCOUNT)
                        val signed = link.signPsbt(draft.psbtBase64, account.masterFingerprint)
                        draft.setSignedPsbt(signed)
                        draft.markSigned(account.masterFingerprint)
                        busyMsg = null
                        onSigned()
                    } finally {
                        withContext(NonCancellable) { runCatching { link.disconnect() } }
                        link.close()
                    }
                } catch (e: Exception) {
                    busyMsg = e.message
                }
            }
        })
        Spacer(modifier = Modifier.height(8.dp))
        SecondaryCta("จำลองเซ็นสำเร็จ (CI)", "cta_m3_signed", {
            draft.markSigned("JADEDEMO")
            onSigned()
        })
        busyMsg?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("m3_status"))
        }
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
        subtitle = "ลายเซ็นครบแล้ว · ${draft.signedFingerprints.size} / $m",
        tag = "screen_send/m4",
    ) {
        Text(draft.signedFingerprints.joinToString(), style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.height(12.dp))
        if (draft.broadcastTxid.isNotBlank()) {
            Text("txid · ${draft.broadcastTxid}", modifier = Modifier.testTag("m4_txid"))
        }
        Spacer(modifier = Modifier.height(20.dp))
        PrimaryCta("Broadcast", "cta_m4_broadcast", {
            if (draft.broadcastMock(m)) onBroadcast()
        }, enabled = draft.readyToBroadcast(m))
        draft.lastError?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}
