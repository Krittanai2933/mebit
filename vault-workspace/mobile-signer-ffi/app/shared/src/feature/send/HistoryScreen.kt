package com.mebit.app.feature.send

import com.mebit.app.data.LocalBtcUnit
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

/** For a wallet whose history no read answered, so it is never said to have none. */
internal const val HISTORY_UNKNOWN_TEXT = "ยังดึงธุรกรรมไม่ได้ในตอนนี้"

/** What the history screen lists for a one-key wallet: its transactions once a refresh read them, else none yet. */
internal fun HomeSource.shownHistory(): List<HotTx> = if (mode == WalletMode.Live) history else emptyList()

/**
 * Every loaded transaction as the Home row, or [HISTORY_UNKNOWN_TEXT] when [history] is null. A tap on a row opens it on the detail
 * screen through [onOpenTx].
 */
@Composable
fun S7HistoryScreen(
    history: List<HotTx>?,
    onOpenTx: (HotTx) -> Unit,
    onBack: (() -> Unit)?,
    onOpenCatalog: () -> Unit,
    tag: String = "screen_send/s7",
) {
    val rows = historyRows(history.orEmpty(), unit = LocalBtcUnit.current)
    MebitScreen(
        eyebrow = "HISTORY",
        title = "ประวัติธุรกรรม",
        onBack = onBack,
        onOpenCatalog = onOpenCatalog,
        tag = tag,
    ) {
        if (rows.isEmpty()) {
            Text(if (history == null) HISTORY_UNKNOWN_TEXT else HISTORY_EMPTY_TEXT, color = MbSlate, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.testTag("history_empty"))
        } else {
            Column { rows.forEachIndexed { index, row -> HomeTxRowItem(row, Modifier.testTag("history_tx_$index")) { onOpenTx(row.tx) } } }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}
