package com.mebit.app.feature.send

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.Place
import com.mebit.app.data.CreatedVault
import com.mebit.app.data.FiatQuote
import com.mebit.app.resources.Res
import com.mebit.app.resources.ic_chevron
import com.mebit.app.resources.ic_vault_row
import com.mebit.app.resources.ic_wallet_row
import com.mebit.app.ui.components.MebitScreen
import com.mebit.app.ui.theme.MbNavy
import com.mebit.app.ui.theme.MbNavyInk
import com.mebit.app.ui.theme.MbWhite
import org.jetbrains.compose.resources.painterResource

private val ChooserMuted = Color(0xFF687E90)
private val ChooserBorder = Color(0xFFDDE5EE)
private val SpendingTile = Color(0xFFEAF2F8)
private val SpendingTileInk = Color(0xFF174C70)
private val VaultTile = Color(0xFFFFF1DD)
private val VaultTileInk = Color(0xFFBD6B12)
private val ChooserChevron = Color(0xFF6C8394)
private val ChooserFiat = Color(0xFF7A8B99)
private val NoteFill = Color(0xFFEAF1F7)
private val NoteBadge = Color(0xFFD8E6F0)
private val NoteBadgeInk = Color(0xFF315D7C)
private val NoteTitle = Color(0xFF244B66)

/** One wallet the user owns. A later wallet is one more row, not another screen. */
sealed interface WalletChoice {
    data class Spending(val place: Place) : WalletChoice
    data class Vault(val vault: CreatedVault) : WalletChoice
}

/** One wallet's row on the chooser, with its own name, its balance and the baht under it. */
data class WalletLine(val name: String, val balance: String, val baht: String?)

/**
 * A vault's row. Its balance and baht once [knownSats] is known, as a one-key row shows them, and a dash with no baht until then.
 * Penpot 4.2 and 4.7 draw "0.8420 BTC" over "≈ ฿3,033,100".
 */
internal fun vaultLine(vault: CreatedVault, knownSats: Long?, quote: FiatQuote?): WalletLine =
    WalletLine(vault.name, homeBalanceText(knownSats ?: 0L, known = knownSats != null, loading = false), knownSats?.let { fiatLine(it, quote) })

/** Every individual wallet in place order, then the vaults in the order they were made. */
internal fun walletChoices(spending: List<Place>, vaults: List<CreatedVault>): List<WalletChoice> =
    spending.map { WalletChoice.Spending(it) } + vaults.map { WalletChoice.Vault(it) }

/** Penpot 4.2 and 4.7: the wallets to pick from before a Receive or Send, each with its own balance. */
@Composable
fun ChooseWalletScreen(
    send: Boolean,
    choices: List<WalletChoice>,
    line: (WalletChoice) -> WalletLine,
    onChoose: (WalletChoice) -> Unit,
    onSwitch: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    MebitScreen(
        eyebrow = if (send) "SEND · เลือกกระเป๋า" else "RECEIVE · เลือกกระเป๋า",
        title = null,
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = if (send) "screen_wallets/choose-send" else "screen_wallets/choose-receive",
    ) {
        ReceiveSendSwitch(sendSelected = send, onReceive = if (send) onSwitch else ({}), onSend = if (send) ({}) else onSwitch)
        Spacer(modifier = Modifier.height(24.dp))
        Text("เลือกกระเป๋า", color = MbNavyInk, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, lineHeight = 42.sp)
        Spacer(modifier = Modifier.height(6.dp))
        Text(if (send) "ต้องการส่ง Bitcoin จากกระเป๋าไหน?" else "ต้องการรับ Bitcoin เข้ากระเป๋าไหน?", color = ChooserMuted, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(20.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("กระเป๋าของคุณ", color = ChooserMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("${choices.size} ใบ", color = ChooserMuted, fontSize = 13.sp)
        }
        Spacer(modifier = Modifier.height(12.dp))
        choices.forEachIndexed { i, choice ->
            if (i > 0) Spacer(modifier = Modifier.height(16.dp))
            when (choice) {
                is WalletChoice.Spending -> line(choice).let { l ->
                    WalletCard(
                        name = l.name,
                        policy = "Single-sig · กุญแจ 1 ใบ",
                        balance = l.balance,
                        baht = l.baht,
                        vault = false,
                        tag = if (choice.place == Place.FIRST) "choose_spending" else "choose_spending_${choice.place.number}",
                        onClick = { onChoose(choice) },
                    )
                }
                is WalletChoice.Vault -> line(choice).let { l ->
                    WalletCard(
                        name = l.name,
                        policy = "Multisig · ใช้กุญแจ ${choice.vault.m} จาก ${choice.vault.n} ใบ",
                        balance = l.balance,
                        baht = l.baht,
                        vault = true,
                        tag = "choose_vault_${choice.vault.id}",
                        onClick = { onChoose(choice) },
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        NoteCard(
            title = if (send) "ส่งจากกระเป๋าที่เลือก" else "รับเข้ากระเป๋าที่เลือก",
            body = if (send) "ยอดคงเหลือและกุญแจที่ใช้เซ็นจะแยกตามกระเป๋า" else "แต่ละกระเป๋ามีที่อยู่รับ Bitcoin ของตัวเอง",
        )
    }
}

@Composable
private fun WalletCard(name: String, policy: String, balance: String, baht: String?, vault: Boolean, tag: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 156.dp)
            .shadow(4.dp, shape, clip = false)
            .clip(shape)
            .background(MbWhite)
            .border(1.dp, ChooserBorder, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag),
    ) {
        Box(
            modifier = Modifier.offset(x = 16.dp, y = 16.dp).size(48.dp).clip(RoundedCornerShape(15.dp)).background(if (vault) VaultTile else SpendingTile),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(if (vault) Res.drawable.ic_vault_row else Res.drawable.ic_wallet_row),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                colorFilter = ColorFilter.tint(if (vault) VaultTileInk else SpendingTileInk),
            )
        }
        Column(modifier = Modifier.offset(x = 78.dp, y = 19.dp)) {
            Text(name, color = MbNavy, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(3.dp))
            Text(policy, color = ChooserMuted, fontSize = 12.sp)
        }
        Image(
            painter = painterResource(Res.drawable.ic_chevron),
            contentDescription = null,
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 26.dp, top = 32.dp).size(width = 10.dp, height = 16.dp),
            colorFilter = ColorFilter.tint(ChooserChevron),
        )
        Column(modifier = Modifier.offset(x = 20.dp, y = 87.dp)) {
            Text(balance, color = MbNavy, fontSize = 25.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("${tag}_balance"))
            if (baht != null) Text(baht, color = ChooserFiat, fontSize = 12.sp)
        }
    }
}

@Composable
private fun NoteCard(title: String, body: String) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp).clip(RoundedCornerShape(18.dp)).background(NoteFill).padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(modifier = Modifier.size(28.dp).background(NoteBadge, CircleShape), contentAlignment = Alignment.Center) {
            Text("i", color = NoteBadgeInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = NoteTitle, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = ChooserMuted, fontSize = 12.sp)
        }
    }
}
