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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mebit.app.Place
import com.mebit.app.data.SavedVaults
import com.mebit.app.data.VaultDraft
import com.mebit.app.data.VaultsNotice
import com.mebit.app.data.OnboardingSession
import com.mebit.app.data.formatBtcCompact
import com.mebit.app.feature.onboarding.NotSavedNotice
import com.mebit.app.feature.send.homeBalanceText
import com.mebit.app.navigation.MovieRoute
import com.mebit.app.resources.Res
import com.mebit.app.resources.mebit_logo_dark
import com.mebit.app.ui.components.InfoCard
import com.mebit.app.ui.components.MebitScreen
import com.mebit.app.ui.components.PlusButton
import com.mebit.app.ui.components.PrimaryCta
import com.mebit.app.ui.components.SecondaryCta
import com.mebit.app.ui.theme.MbBody
import com.mebit.app.ui.theme.MbBorder
import com.mebit.app.ui.theme.MbBtcOrange
import com.mebit.app.ui.theme.MbEyebrow
import com.mebit.app.ui.theme.MbIconWash
import com.mebit.app.ui.theme.MbNavy
import com.mebit.app.ui.theme.MbNavyInk
import com.mebit.app.ui.theme.MbSlate
import com.mebit.app.ui.theme.MbWhite
import org.jetbrains.compose.resources.painterResource

/** A one-key wallet's card on Penpot 1.8. [known] is false until its balance is read, so the card shows a dash and the total adds no guess. */
data class SpendingCard(val place: Place, val name: String, val sats: Long, val known: Boolean, val backedUp: Boolean) {
    /** Penpot 1.3.3 and 1.8 put this tag at the card's top right while the words are not backed up. It is only a label, and the card still opens Home. */
    val backupTag: String? get() = if (backedUp) null else "ยังไม่ได้สำรอง"
}

/** A card's amount, every sat that matters, and a dash until [known]. The card carries no unit. */
internal fun cardBalanceText(sats: Long, known: Boolean): String = if (known) formatBtcCompact(sats) else "—"

/**
 * Penpot 1.8's total adds up every one-key wallet's balance, and is a dash while any is unknown, a wallet that did not open this run
 * included ([walletUnopened]), so part of the money never reads as all of it. Null, so no line, while a vault is listed, because the app
 * cannot read a vault's balance, and while no wallet is listed or waiting to open.
 */
internal fun spendingTotal(cards: List<SpendingCard>, vaultListed: Boolean, walletUnopened: Boolean): String? = when {
    vaultListed -> null
    walletUnopened -> "—"
    cards.isEmpty() -> null
    else -> homeBalanceText(cards.sumOf { it.sats }, cards.all { it.known }, loading = false)
}

/**
 * Penpot 1.8 Wallet list: the title, the + that opens Policy, the total, one card per one-key wallet with its backup tag, then one card
 * per vault. No back chip, no Keyring link, no buttons under the list. Kept though no board draws them: the vaults notice, the line for
 * places that did not open, and [inUse]'s not-saved line, which tells the user straight after setup that this phone does not hold the key.
 */
@Composable
fun VaultListScreen(
    draft: VaultDraft,
    vaults: SavedVaults,
    spending: List<SpendingCard>,
    inUse: OnboardingSession,
    unopenedNotice: String?,
    onOpenSpending: (Place) -> Unit,
    onOpenDetail: () -> Unit,
    onCreate: () -> Unit,
    onOpenCatalog: () -> Unit,
) {
    MebitScreen(
        eyebrow = null,
        // The title is drawn below so the + shares its row, as on the board.
        title = null,
        onBack = null,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_vaults",
        headerContent = {
            Image(painterResource(Res.drawable.mebit_logo_dark), contentDescription = "mebit", modifier = Modifier.size(width = 74.dp, height = 19.dp))
        },
    ) {
        Spacer(modifier = Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(MovieRoute.VaultList.titleTh, color = MbNavyInk, fontSize = 28.sp, fontWeight = FontWeight.Medium, lineHeight = 34.sp, modifier = Modifier.weight(1f).testTag("title"))
            // Penpot 1.8 puts the + 29 dp from the screen's right edge, so 9 dp past the screen's own padding.
            PlusButton("cta_vaults_add", "สร้าง Wallet", onCreate, Modifier.padding(end = 9.dp))
        }
        spendingTotal(spending, vaultListed = draft.created.isNotEmpty(), walletUnopened = unopenedNotice != null)?.let { TotalLine(it) }
        Spacer(modifier = Modifier.height(16.dp))
        VaultsNotice(vaults)
        unopenedNotice?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.testTag("wallets_unopened"))
            Spacer(modifier = Modifier.height(12.dp))
        }
        NotSavedNotice(inUse, Modifier.padding(bottom = 12.dp))
        spending.forEach { card ->
            WalletListCard(
                kind = "SINGLE-SIG",
                name = card.name,
                detail = "กุญแจเดียว · iPhone ของฉัน",
                balance = cardBalanceText(card.sats, card.known),
                dark = false,
                badge = card.backupTag,
                tag = if (card.place == Place.FIRST) "card_spending" else "card_spending_${card.place.number}",
                onClick = { onOpenSpending(card.place) },
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
                badge = null,
                tag = "cta_open_${vault.id}",
                onClick = {
                    draft.selectVault(vault.id)
                    onOpenDetail()
                },
            )
            Spacer(modifier = Modifier.height(11.dp))
        }
        if (spending.isEmpty() && draft.created.isEmpty()) {
            Text("ยังไม่มี Wallet", modifier = Modifier.testTag("vaults_empty"))
        }
    }
}

/** "รวมทั้งหมด" in Light 13.5 MbBody, then [total] in SemiBold 14.5 MbNavyInk. */
@Composable
private fun TotalLine(total: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp).testTag("vaults_total")) {
        Text("รวมทั้งหมด", color = MbBody, fontSize = 13.5.sp, fontWeight = FontWeight.Light)
        Text(total, color = MbNavyInk, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** [badge] is a pill 24 tall at the card's top right, 13 from its top and 16 from its right edge. The card merges its children, so the pill has no test tag. */
@Composable
private fun WalletListCard(kind: String, name: String, detail: String, balance: String, dark: Boolean, badge: String?, tag: String, onClick: () -> Unit) {
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
        if (badge != null) {
            Box(
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 16.dp, top = 13.dp).height(24.dp).clip(CircleShape).background(MbIconWash).padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(badge, color = MbEyebrow, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
            }
        }
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
