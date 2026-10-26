package com.mebit.app.feature.vault

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mebit.app.data.CustodyPolicy
import com.mebit.app.data.KeyringStore
import com.mebit.app.data.VaultDraft
import com.mebit.app.ui.components.InfoCard
import com.mebit.app.ui.components.MebitScreen
import com.mebit.app.ui.components.PrimaryCta
import com.mebit.app.ui.components.SecondaryCta

@Composable
fun PolicyScreen(
    draft: VaultDraft,
    onContinue: () -> Unit,
    onDecaying: () -> Unit,
    onInvalidMn: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    var draftM by remember { mutableStateOf(draft.m.toString()) }
    var draftN by remember { mutableStateOf(draft.n.toString()) }
    MebitScreen(
        eyebrow = "POLICY · 1/3",
        title = "Vault นี้ใช้กุญแจแบบไหน",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        subtitle = "personal_custody เท่านั้นใน Movie Page · decaying / timelock เป็น critical boards",
        tag = "screen_vault/policy",
    ) {
        FilterChip(
            selected = draft.policy == CustodyPolicy.PersonalCustody,
            onClick = { draft.policy = CustodyPolicy.PersonalCustody },
            label = { Text("personal_custody") },
            modifier = Modifier.testTag("policy_personal"),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text("M-of-N", style = MaterialTheme.typography.titleMedium)
        Text("M = $draftM · N = $draftN", modifier = Modifier.testTag("mn_display"))
        Spacer(modifier = Modifier.height(8.dp))
        PrimaryCta("ตั้ง 2-of-3", "cta_mn_2_3", {
            draftM = "2"; draftN = "3"
            draft.setMn(2, 3)
        })
        Spacer(modifier = Modifier.height(8.dp))
        SecondaryCta("ตั้ง 3-of-2 (invalid)", "cta_mn_invalid", {
            if (!draft.setMn(3, 2)) onInvalidMn()
        })
        Spacer(modifier = Modifier.height(8.dp))
        SecondaryCta("decaying multisig", "cta_policy_decaying", onDecaying)
        Spacer(modifier = Modifier.height(20.dp))
        PrimaryCta("ต่อไป · เลือกกุญแจ", "cta_policy_next", {
            val m = draftM.toIntOrNull() ?: draft.m
            val n = draftN.toIntOrNull() ?: draft.n
            if (draft.setMn(m, n)) onContinue() else onInvalidMn()
        })
    }
}

@Composable
fun ChooseKeysScreen(
    draft: VaultDraft,
    store: KeyringStore,
    onContinue: () -> Unit,
    onCountMismatch: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    val verified = store.keys.filter { it.verified }
    MebitScreen(
        eyebrow = "POLICY · 2/3",
        title = "เลือกกุญแจ",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        subtitle = "เลือก ${draft.n} ดอกสำหรับ ${draft.m}-of-${draft.n} · เลือกแล้ว ${draft.selectedIds.size}",
        tag = "screen_vault/choose-keys",
    ) {
        verified.forEach { key ->
            val selected = key.id in draft.selectedIds
            FilterChip(
                selected = selected,
                onClick = { draft.toggleKey(key.id) },
                label = { Text(key.label + " · " + key.fingerprint.take(8)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("pick_${key.id}"),
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        if (verified.isEmpty()) {
            Text("ยังไม่มีกุญแจ verified ในคลัง", color = MaterialTheme.colorScheme.error)
        }
        Spacer(modifier = Modifier.height(16.dp))
        PrimaryCta("ต่อไป · ตรวจก่อนสร้าง", "cta_choose_next", {
            if (draft.selectionComplete()) onContinue() else onCountMismatch()
        })
        draft.lastError?.let {
            Spacer(modifier = Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("error"))
        }
    }
}

@Composable
fun ReviewScreen(
    draft: VaultDraft,
    store: KeyringStore,
    onCreated: () -> Unit,
    onBackendError: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    val ready = remember(draft.selectedIds.toList(), draft.m, draft.n) {
        draft.buildReview(store)
    }
    MebitScreen(
        eyebrow = "POLICY · 3/3",
        title = "ตรวจก่อนสร้าง",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        subtitle = "${draft.m}-of-${draft.n} · ${draft.policy.name}",
        tag = "screen_vault/review",
    ) {
        Text(
            text = if (ready) "address · ${draft.address}" else "descriptor ยังไม่พร้อม",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("review_address"),
        )
        Spacer(modifier = Modifier.height(20.dp))
        PrimaryCta("สร้าง Vault", "cta_create_vault", {
            if (draft.commit(store)) onCreated() else onBackendError()
        }, enabled = ready)
        Spacer(modifier = Modifier.height(8.dp))
        SecondaryCta("จำลอง backend error", "cta_force_t18", {
            if (!draft.commit(store, forceFail = true)) onBackendError()
        })
        draft.lastError?.let {
            Spacer(modifier = Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("error"))
        }
    }
}

@Composable
fun VaultCreatedScreen(
    draft: VaultDraft,
    onOpenVaults: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    val vault = draft.created.lastOrNull()
    MebitScreen(
        eyebrow = "VAULT CREATED",
        title = "Vault พร้อมใช้งาน",
        subtitle = vault?.let { "${it.m}-of-${it.n} · ที่อยู่แรก" } ?: "ที่อยู่แรกจะแสดงหลังสร้าง Vault",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_vault/created",
    ) {
        InfoCard(
            eyebrow = "VAULT HEALTH CHECK",
            title = "ตรวจสุขภาพ Vault ทุก 90 วัน",
            body = "เราจะเตือนให้ทดลองเซ็นด้วยกุญแจทุกใบ เพื่อยืนยันว่ายังใช้งานได้จริง",
        )
        Spacer(modifier = Modifier.height(12.dp))
        InfoCard(
            eyebrow = "BACKUP / EXPORT",
            title = "สำรองข้อมูล Vault",
            body = "ส่งออกเป็นไฟล์ BSMS (ไม่มี seed) เก็บไว้นอกแอปเพื่อกู้คืน",
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "ส่งออก BSMS เป็นไฟล์ · ไม่ใช่ QR",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("bsms_file_notice"),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(vault?.address ?: "—", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("created_address"))
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = vault?.bsms ?: "",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("bsms_body"),
        )
        Spacer(modifier = Modifier.height(20.dp))
        PrimaryCta("เข้าใช้งาน Vault", "cta_open_vaults", onOpenVaults)
    }
}

@Composable
fun VaultListScreen(
    draft: VaultDraft,
    onOpenDetail: () -> Unit,
    onCreate: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    MebitScreen(
        eyebrow = "VAULTS",
        title = "Vault ของคุณ",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        subtitle = "personal_custody",
        tag = "screen_vaults",
    ) {
        if (draft.created.isEmpty()) {
            Text("ยังไม่มี Vault", modifier = Modifier.testTag("vaults_empty"))
        } else {
            draft.created.forEach { vault ->
                Text(vault.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${vault.m}-of-${vault.n} · ${vault.address.take(16)}…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                SecondaryCta("เปิด ${vault.name}", "cta_open_${vault.id}", {
                    draft.selectVault(vault.id)
                    onOpenDetail()
                })
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        PrimaryCta("สร้าง Vault ใหม่", "cta_new_vault", onCreate)
    }
}

@Composable
fun VaultDetailScreen(
    draft: VaultDraft,
    onReceive: () -> Unit,
    onSend: () -> Unit,
    onActions: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    val vault = draft.selectedVault()
    MebitScreen(
        eyebrow = "VAULT DETAIL",
        title = vault?.name ?: "รายละเอียด Vault",
        subtitle = vault?.let { "${it.m}-of-${it.n} · personal_custody" },
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_vaults/detail",
    ) {
        if (vault == null) {
            Text("ยังไม่ได้เลือก Vault", color = MaterialTheme.colorScheme.error)
            return@MebitScreen
        }
        Text(vault.address, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("detail_address"))
        Spacer(modifier = Modifier.height(8.dp))
        Text("keys · ${vault.keyFingerprints.joinToString()}", style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.height(20.dp))
        PrimaryCta("รับ", "cta_vault_receive", onReceive)
        Spacer(modifier = Modifier.height(8.dp))
        SecondaryCta("ส่ง (multisig)", "cta_vault_send", onSend)
        Spacer(modifier = Modifier.height(8.dp))
        SecondaryCta("การกระทำ · BSMS", "cta_vault_actions", onActions)
    }
}

@Composable
fun T20VaultActionsScreen(
    draft: VaultDraft,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    val vault = draft.selectedVault()
    MebitScreen(
        eyebrow = "VAULT ACTIONS",
        title = "การกระทำของ Vault",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_critical/t20",
    ) {
        InfoCard(
            eyebrow = "VAULT HEALTH CHECK",
            title = "ตรวจสุขภาพ Vault",
            body = if (vault != null) "health · ok (demo)" else "health · n/a",
            modifier = Modifier.testTag("t20_health"),
        )
        Spacer(modifier = Modifier.height(12.dp))
        InfoCard(
            eyebrow = "BACKUP / EXPORT",
            title = "ส่งออกไฟล์ BSMS",
            body = vault?.bsms ?: "ยังไม่มี BSMS — สร้าง Vault ก่อน",
            modifier = Modifier.testTag("t20_bsms"),
        )
        Spacer(modifier = Modifier.height(20.dp))
        PrimaryCta("กลับ", "cta_t20_back", { onBack?.invoke() })
    }
}

@Composable
fun T16PolicyDecayingScreen(
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    MebitScreen(
        eyebrow = "POLICY · ADVANCED",
        title = "decaying multisig",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        subtitle = "mutually exclusive กับ timelock · ไม่อยู่ใน personal_custody MVP",
        tag = "screen_critical/t16-decaying",
    ) {
        PrimaryCta("กลับ", "cta_t16_decaying_back", { onBack?.invoke() })
    }
}

@Composable
fun T16InvalidMnScreen(
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    MebitScreen(
        eyebrow = "POLICY",
        title = "M / N ไม่ถูกต้อง",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_critical/t16-mn",
    ) {
        Text("ต้อง 1 ≤ M ≤ N", color = MaterialTheme.colorScheme.error)
        Spacer(modifier = Modifier.height(20.dp))
        PrimaryCta("กลับ", "cta_t16_mn_back", { onBack?.invoke() })
    }
}

@Composable
fun T17CountMismatchScreen(
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    MebitScreen(
        eyebrow = "COMPOSE VAULT",
        title = "จำนวนกุญแจไม่ตรง",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_critical/t17",
    ) {
        Text("เลือกให้ครบตาม N", color = MaterialTheme.colorScheme.error)
        Spacer(modifier = Modifier.height(20.dp))
        PrimaryCta("กลับ", "cta_t17_back", { onBack?.invoke() })
    }
}

@Composable
fun T18BackendErrorScreen(
    onRetry: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    MebitScreen(
        eyebrow = "CONFIRM VAULT",
        title = "สร้าง Vault ไม่สำเร็จ",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        subtitle = "ลองอีกครั้ง",
        tag = "screen_critical/t18",
    ) {
        PrimaryCta("ลองใหม่", "cta_t18_retry", onRetry)
    }
}
