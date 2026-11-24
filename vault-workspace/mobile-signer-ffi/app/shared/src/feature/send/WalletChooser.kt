package com.mebit.app.feature.send

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.Place
import com.mebit.app.data.CreatedVault
import com.mebit.app.data.FiatQuote
import com.mebit.app.data.HardwareWallet
import com.mebit.app.feature.vault.WalletListCard
import com.mebit.app.ui.components.MebitScreen

private val ChooserMuted = Color(0xFF687E90)

/** One wallet the user owns. A later wallet is one more row, not another screen. */
sealed interface WalletChoice {
    data class Spending(val place: Place) : WalletChoice

    /** A one-key wallet whose key is on a Jade or a Safe 7. */
    data class Hardware(val wallet: HardwareWallet) : WalletChoice
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

/**
 * The order of every list of wallets, the Wallet list's and the รับ/ส่ง chooser's, as the user set it on 12 Nov 2026: this phone's
 * wallets in place order, then the Jade and Trezor wallets, then the several-key wallets, each in the order it was made.
 */
internal fun walletChoices(spending: List<Place>, hardware: List<HardwareWallet>, vaults: List<CreatedVault>): List<WalletChoice> =
    spending.map { WalletChoice.Spending(it) } + hardware.map { WalletChoice.Hardware(it) } + vaults.map { WalletChoice.Vault(it) }

/** One wallet's card on the chooser: Home's Wallet list card without its ⋯. */
data class ChoiceCard(val name: String, val detail: String, val balance: String)

/**
 * Penpot 4.7: the wallets to pick from, as Home's Wallet list draws them, and a tap opens that wallet's Receive (4.9, 4.7.1). The bar's
 * orange button opens it. [send] is the Send chooser, which no screen opens since 4.7 dropped the รับ / ส่ง switch.
 */
@Composable
fun ChooseWalletScreen(
    send: Boolean,
    choices: List<WalletChoice>,
    card: (WalletChoice) -> ChoiceCard,
    onChoose: (WalletChoice) -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    MebitScreen(
        eyebrow = if (send) "SEND · เลือกกระเป๋า" else "รับ / ส่ง · เลือกกระเป๋า",
        title = "เลือกกระเป๋า",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = if (send) "screen_wallets/choose-send" else "screen_wallets/choose-receive",
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("กระเป๋าของคุณ", color = ChooserMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("${choices.size} ใบ", color = ChooserMuted, fontSize = 13.sp)
        }
        Spacer(modifier = Modifier.height(18.dp))
        choices.forEachIndexed { i, choice ->
            if (i > 0) Spacer(modifier = Modifier.height(12.dp))
            val c = card(choice)
            val tag = when (choice) {
                is WalletChoice.Spending -> if (choice.place == Place.FIRST) "choose_spending" else "choose_spending_${choice.place.number}"
                is WalletChoice.Hardware -> "choose_hw_${choice.wallet.id}"
                is WalletChoice.Vault -> "choose_vault_${choice.vault.id}"
            }
            WalletListCard(
                kind = if (choice is WalletChoice.Vault) "MULTISIG ${choice.vault.m}-of-${choice.vault.n}" else "SINGLE-SIG",
                name = c.name,
                detail = c.detail,
                balance = c.balance,
                dark = choice is WalletChoice.Vault,
                badge = null,
                tag = tag,
                balanceTag = "${tag}_balance",
                onClick = { onChoose(choice) },
            )
        }
    }
}
