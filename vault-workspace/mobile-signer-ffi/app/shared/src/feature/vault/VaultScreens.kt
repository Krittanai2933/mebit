package com.mebit.app.feature.vault

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mebit.app.Place
import com.mebit.app.data.CustodyPolicy
import com.mebit.app.data.KeyringStore
import com.mebit.app.data.SavedVaults
import com.mebit.app.data.VaultDraft
import com.mebit.app.data.VaultsNotice
import com.mebit.app.feature.send.SpendingLine
import com.mebit.app.resources.Res
import com.mebit.app.resources.mebit_logo_dark
import com.mebit.app.ui.components.InfoCard
import com.mebit.app.ui.components.MebitScreen
import com.mebit.app.ui.components.PrimaryCta
import com.mebit.app.ui.components.SecondaryCta
import com.mebit.app.ui.theme.MbBorder
import com.mebit.app.ui.theme.MbBtcOrange
import com.mebit.app.ui.theme.MbNavy
import com.mebit.app.ui.theme.MbNavyInk
import com.mebit.app.ui.theme.MbSlate
import com.mebit.app.ui.theme.MbWhite
import org.jetbrains.compose.resources.painterResource

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

/**
 * Penpot 3.5: every individual wallet, then the vaults. Left out: the total line, because the app cannot read a vault's balance, so a
 * vault shows a dash. The two buttons under the list are two different things: a wallet is added through Add Key, a Vault is composed from keys.
 */
@Composable
fun VaultListScreen(
    draft: VaultDraft,
    vaults: SavedVaults,
    spending: List<SpendingLine>,
    unopenedNotice: String?,
    onOpenSpending: (Place) -> Unit,
    onAddWallet: () -> Unit,
    onOpenKeyring: () -> Unit,
    onOpenDetail: () -> Unit,
    onCreate: () -> Unit,
    onOpenCatalog: () -> Unit,
) {
    MebitScreen(
        eyebrow = null,
        title = "Vault ของคุณ",
        onBack = null,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_vaults",
        headerContent = {
            Image(painterResource(Res.drawable.mebit_logo_dark), contentDescription = "mebit", modifier = Modifier.size(width = 74.dp, height = 19.dp))
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "คลังกุญแจ",
                color = MbNavy,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(role = Role.Button, onClick = onOpenKeyring).testTag("cta_vaults_keyring"),
            )
        },
    ) {
        VaultsNotice(vaults)
        unopenedNotice?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.testTag("wallets_unopened"))
            Spacer(modifier = Modifier.height(12.dp))
        }
        spending.forEach { line ->
            WalletListCard(
                kind = "SINGLE-SIG",
                name = line.name,
                detail = "กุญแจเดียว · iPhone ของฉัน",
                balance = line.balance,
                dark = false,
                tag = if (line.place == Place.FIRST) "card_spending" else "card_spending_${line.place.number}",
                onClick = { onOpenSpending(line.place) },
            )
            Spacer(modifier = Modifier.height(11.dp))
        }
        draft.created.forEach { vault ->
            WalletListCard(
                kind = "MULTISIG ${vault.m}-of-${vault.n}",
                name = vault.name,
                detail = "ต้องเซ็น ${vault.m} จาก ${vault.n} กุญแจ",
                balance = "—",
                dark = true,
                tag = "cta_open_${vault.id}",
                onClick = {
                    draft.selectVault(vault.id)
                    onOpenDetail()
                },
            )
            Spacer(modifier = Modifier.height(11.dp))
        }
        if (spending.isEmpty() && draft.created.isEmpty()) {
            Text("ยังไม่มี Vault", modifier = Modifier.testTag("vaults_empty"))
        }
        Spacer(modifier = Modifier.height(23.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            ListButton("เพิ่มกระเป๋า", "cta_add_wallet", onAddWallet)
            ListButton("ประกอบ Vault ใหม่", "cta_new_vault", onCreate)
        }
    }
}

@Composable
private fun RowScope.ListButton(label: String, tag: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = Modifier.weight(1f).widthIn(max = 175.dp).height(52.dp).clip(shape).background(MbWhite).border(1.5.dp, MbNavy, shape).clickable(role = Role.Button, onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = MbNavy, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun WalletListCard(kind: String, name: String, detail: String, balance: String, dark: Boolean, tag: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val ink = if (dark) MbWhite else MbNavyInk
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(105.dp)
            .shadow(4.dp, shape, clip = false)
            .clip(shape)
            .background(if (dark) MbNavy else MbWhite)
            .border(1.dp, if (dark) MbNavy else MbBorder, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag),
    ) {
        Box(Modifier.offset(x = 18.dp, y = 21.dp).size(8.dp).background(if (dark) MbBtcOrange else MbNavy, RoundedCornerShape(4.dp)))
        Text(kind, color = if (dark) MbWhite.copy(alpha = 0.72f) else MbSlate, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.offset(x = 32.dp, y = 16.dp))
        Text(name, color = ink, fontSize = 17.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.offset(x = 32.dp, y = 39.dp))
        Text(detail, color = if (dark) MbWhite.copy(alpha = 0.66f) else MbSlate, fontSize = 11.5.sp, fontWeight = FontWeight.Light, modifier = Modifier.offset(x = 32.dp, y = 70.dp))
        Text(balance, color = ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopEnd).padding(end = 18.dp, top = 70.dp))
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
