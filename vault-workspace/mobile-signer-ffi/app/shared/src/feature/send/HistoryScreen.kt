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
import com.mebit.app.data.HomeSource
import com.mebit.app.data.SingleSigWallet.WalletMode
import com.mebit.app.ui.components.MebitScreen
import com.mebit.app.ui.theme.MbSlate
import com.mebit.ffi.signer.HotTx

internal const val HISTORY_EMPTY_TEXT = "ยังไม่มีธุรกรรม"

/** Every loaded transaction as the Home row. A tap on a row opens it on the detail screen (board 3.6.2) through [onOpenTx]. */
@Composable
fun S7HistoryScreen(
    wallet: HomeSource,
    onOpenTx: (HotTx) -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
    tag: String = "screen_send/s7",
) {
    val rows = if (wallet.mode == WalletMode.Live) historyRows(wallet.history) else emptyList()
    MebitScreen(
        eyebrow = "HISTORY",
        title = "ประวัติธุรกรรม",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = tag,
    ) {
        if (rows.isEmpty()) {
            Text(HISTORY_EMPTY_TEXT, color = MbSlate, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.testTag("history_empty"))
        } else {
            Column { rows.forEachIndexed { index, row -> HomeTxRowItem(row, Modifier.testTag("history_tx_$index")) { onOpenTx(row.tx) } } }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}
