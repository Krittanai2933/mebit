package com.mebit.app.feature.send

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.data.SingleSigWallet
import com.mebit.app.data.SingleSigWallet.WalletMode
import com.mebit.app.ui.components.MebitScreen
import com.mebit.app.ui.theme.MbSlate

internal const val HISTORY_EMPTY_TEXT = "ยังไม่มีธุรกรรม"

/** Every loaded transaction as the Home row. The rows do not react to a tap yet, and the detail screen (board 3.6.2) will hang its tap on [HomeTxRowItem]. */
@Composable
fun S7HistoryScreen(
    wallet: SingleSigWallet,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
) {
    val rows = if (wallet.mode == WalletMode.Live) historyRows(wallet.history) else emptyList()
    MebitScreen(
        eyebrow = "HISTORY",
        title = "ประวัติธุรกรรม",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = "screen_send/s7",
    ) {
        if (rows.isEmpty()) {
            Text(HISTORY_EMPTY_TEXT, color = MbSlate, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.testTag("history_empty"))
        } else {
            Column { rows.forEachIndexed { index, row -> HomeTxRowItem(row, Modifier.testTag("history_tx_$index")) } }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}
