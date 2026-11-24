package com.mebit.app.feature.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import com.mebit.app.ui.theme.MbEyebrow
import com.mebit.app.ui.theme.MbNavyInk
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.data.CreatedVault
import com.mebit.app.data.KeyringStore
import com.mebit.app.data.QuoteCache
import com.mebit.app.data.SavedKey
import com.mebit.app.data.Wallets
import com.mebit.app.data.fingerprintGroups
import com.mebit.app.data.keyLine
import com.mebit.app.data.rememberFiatQuote
import com.mebit.app.data.vaultKey
import com.mebit.app.data.xpubsIn
import com.mebit.app.feature.send.GoldLine
import com.mebit.app.feature.send.HISTORY_EMPTY_TEXT
import com.mebit.app.feature.send.HomeTxRowItem
import com.mebit.app.feature.send.KeyRowText
import com.mebit.app.feature.send.PolicyCard
import com.mebit.app.feature.send.WalletBoard
import com.mebit.app.feature.send.WalletHero
import com.mebit.app.feature.send.WalletPage
import com.mebit.app.feature.send.historyRows
import com.mebit.app.feature.send.vaultLine
import com.mebit.app.ui.components.MebitScreen
import com.mebit.app.ui.theme.MbBorder
import com.mebit.app.ui.theme.MbSlate
import com.mebit.app.ui.theme.MbSuccess
import com.mebit.ffi.signer.HotTx
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.runtime.derivedStateOf
import com.mebit.app.feature.send.HISTORY_UNKNOWN_TEXT
import com.mebit.app.feature.send.RowsPullState
import com.mebit.app.feature.send.TxListMaxHeight
import com.mebit.app.feature.send.TxSection
import com.mebit.app.feature.send.homeHasMoreHistory
import com.mebit.app.feature.send.txStatus

/**
 * Penpot 3.7 Wallet detail, and 3.7.1 with the policy card open: [vault]'s name, balance and baht on the navy block with รับ, ส่ง and a
 * close X, the m-of-n card that opens into the wallet's numbered keys, and its transactions. The balance is [Wallets.vaultSats] and the
 * rows [Wallets.vaultTxs], both from one read this screen asks for by [Wallets.readVaultDetail]'s rules, and the keys come from [keyring].
 * A row has no tap yet, so it never opens the one-key S8. No board draws a null [vault], which the catalog can open with an empty book.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultDetailScreen(
    vault: CreatedVault?,
    wallets: Wallets,
    keyring: KeyringStore,
    quotes: QuoteCache,
    onReceive: () -> Unit,
    onSend: () -> Unit,
    onExport: () -> Unit,
    onHistory: () -> Unit,
    onOpenTx: (HotTx) -> Unit,
    onClose: () -> Unit,
    onOpenCatalog: () -> Unit,
) {
    if (vault == null) {
        MebitScreen(eyebrow = "WALLET DETAIL", title = "รายละเอียด Wallet", onBack = onClose, onOpenCatalog = onOpenCatalog, tag = "screen_vaults/detail") {
            Text("ยังไม่ได้เลือก Wallet", color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("detail_none"))
        }
        return
    }
    // True on the first frame, so no frame calls a figure unknown before its read has begun. Keyed on the address, as the read is.
    var reading by remember(vault.address) { mutableStateOf(true) }
    LaunchedEffect(vault.address) {
        wallets.readVaultDetail(vault)
        reading = false
    }
    // A pull reads again, even inside the minute a read stays fresh. One that comes while the first read runs is let go: that read is the refresh.
    var pulled by remember(vault.address) { mutableStateOf(false) }
    LaunchedEffect(vault.address, pulled) {
        if (pulled) {
            wallets.readVaultDetail(vault, force = true)
            pulled = false
        }
    }
    val pull = remember { RowsPullState() }
    // Derived, so a drag recomposes the page only when it flips and not on every frame.
    val armed by remember { derivedStateOf { pull.distanceFraction > 1f } }
    var keysOpen by remember { mutableStateOf(false) }
    val sats = wallets.vaultSats(vault)
    val line = vaultLine(vault, sats, rememberFiatQuote(quotes))
    val history = wallets.vaultTxs(vault)
    WalletPage(
        tag = "screen_vaults/detail",
        board = WalletBoard.Detail,
        onOpenCatalog = onOpenCatalog,
        modifier = Modifier.pullToRefresh(isRefreshing = pulled, state = pull, onRefresh = { if (!reading) pulled = true }),
    ) {
        WalletHero(
            board = WalletBoard.Detail,
            eyebrow = vaultEyebrow(vault),
            name = vault.name,
            balance = line.balance,
            balanceTag = "vault_balance",
            receiveTag = "cta_vault_receive",
            onReceive = onReceive,
            sendTag = "cta_vault_send",
            onSend = onSend,
            onClose = onClose,
        ) {
            vaultHeroLine(sats, line.baht, reading)?.let { GoldLine(it, Modifier.testTag("vault_hero_line")) }
        }
        Spacer(modifier = Modifier.height(58.dp))
        PolicyCard(
            board = WalletBoard.Detail,
            dot = MbSuccess,
            summary = "ต้องเซ็น ${vault.m} จาก ${vault.n} กุญแจ",
            keys = vaultKeyRows(vault, keyring.keys),
            expanded = keysOpen,
            onToggle = { keysOpen = !keysOpen },
            footer = { ExportRow(onExport) },
        )
        // 3.7 puts ธุรกรรม 26 dp under the card and the first row's title 16 dp under its line.
        Spacer(modifier = Modifier.height(26.dp))
        TxSection(
            title = "ธุรกรรม",
            tag = "vault",
            hasMore = homeHasMoreHistory(history.orEmpty()),
            historyTag = "cta_vault_history",
            onHistory = onHistory,
            status = txStatus(pulled, armed),
            pull = pull,
        ) {
            val note = vaultTxNote(history, reading)
            if (note != null) {
                Text(note, color = MbSlate, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.testTag("vault_tx_note"))
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = TxListMaxHeight).testTag("vault_tx_list")) {
                    itemsIndexed(historyRows(history.orEmpty()), key = { _, row -> row.tx.txid }) { i, row ->
                        HomeTxRowItem(row, Modifier.txDivider().testTag("vault_tx_$i")) { onOpenTx(row.tx) }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * 3.7.1's backup row, under the keys of the open card and a divider 20 dp in from its edges. The app keeps no export date, so its line
 * says only that the file holds no seed.
 */
@Composable
private fun ExportRow(onClick: () -> Unit) {
    Box(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(1.dp).background(MbBorder))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 20.dp, end = 19.dp, top = 10.dp, bottom = 12.dp)
            .testTag("cta_vault_export"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("สำรองไฟล์ BSMS", color = MbNavyInk, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp)
            Spacer(modifier = Modifier.height(3.dp))
            Text("ไม่มี seed", color = MbSlate, fontSize = 11.sp, fontWeight = FontWeight.Light, lineHeight = 15.sp)
        }
        Text("ส่งออก ›", color = MbEyebrow, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp)
    }
}

/** 3.7's rule under each row: 1 dp of [MbBorder] from the title's left edge to the row's end, 57 dp under the title's top. */
private fun Modifier.txDivider(): Modifier = drawBehind {
    drawRect(MbBorder, topLeft = Offset(44.dp.toPx(), 57.dp.toPx()), size = Size(size.width - 44.dp.toPx(), 1.dp.toPx()))
}

/** 3.7's eyebrow, "MULTISIG 2-OF-3". */
internal fun vaultEyebrow(vault: CreatedVault): String = "MULTISIG ${vault.m}-OF-${vault.n}"

/**
 * Where a key the keyring cannot name came from. A draft: no board draws it. It never says the key is not on this phone, because the
 * keyring can leave out a key the phone holds.
 */
internal const val UNKNOWN_KEY_SOURCE = "ไม่ทราบที่มา"

/**
 * One row per key of [vault], in its BSMS order, which is the order the keys were picked in and the order [CreatedVault.keyFingerprints]
 * keeps, so a key's number never moves. A key takes a keyring key's name and line ("Jade · 7f2c a91b") only when both the fingerprint and
 * the xpub at its place in the BSMS match that key, because one seed at another account path shares the master fingerprint. A BSMS whose
 * keys cannot be read therefore names no key, never a wrong one. A key the keyring does not name (an unlisted wallet's own key, a book
 * that could not be read, an older book) keeps its row as "กุญแจ N" over [UNKNOWN_KEY_SOURCE] and its fingerprint. Always n rows.
 */
internal fun vaultKeyRows(vault: CreatedVault, keyring: List<SavedKey>): List<KeyRowText> {
    val xpubs = xpubsIn(vault.bsms)
    return vault.keyFingerprints.mapIndexed { i, fingerprint ->
        val key = vaultKey(vault, i, xpubs.getOrNull(i), keyring)
        KeyRowText(
            name = key?.label ?: "กุญแจ ${i + 1}",
            line = key?.let(::keyLine) ?: "$UNKNOWN_KEY_SOURCE · ${fingerprintGroups(fingerprint)}",
            lineTag = "vault_key_line_$i",
        )
    }
}

/**
 * The gold line under the balance: [baht], the chooser's line for the known balance, which is none without a price as on Home;
 * "กำลังดึงยอด…" while the first read is out; and nothing otherwise, so an unknown balance is the dash alone, as on its card and the choosers.
 */
internal fun vaultHeroLine(sats: Long?, baht: String?, reading: Boolean): String? = when {
    sats != null -> baht
    reading -> "กำลังดึงยอด…"
    else -> null
}

/** Drafts: no board draws these two states. */
internal const val VAULT_TX_READING = "กำลังดึงธุรกรรม…"

/**
 * The line the transactions show in place of rows, or null when they show rows. Loading while the read is out with nothing to show,
 * unknown when no read could ask (the balance did not complete) or the history failed, and empty only when the core answered an empty
 * list, so a wallet whose history is unknown is never said to have none.
 */
internal fun vaultTxNote(history: List<HotTx>?, reading: Boolean): String? = when {
    history == null && reading -> VAULT_TX_READING
    history == null -> HISTORY_UNKNOWN_TEXT
    history.isEmpty() -> HISTORY_EMPTY_TEXT
    else -> null
}
