package com.mebit.app.feature.send

import com.mebit.app.data.formatBtcCompact
import com.mebit.app.formatThaiDateTimeShort
import com.mebit.ffi.signer.HotTx
import kotlin.math.abs

/** Confirmations from which a row says only that it is confirmed. The board shows a count up to 6 and a bare "confirmed" after it, so 7 is a guess. */
private const val SETTLED_CONFIRMATIONS = 7u

/** How many rows Home shows before it points to the full history. */
private const val HOME_ROW_LIMIT = 4

/** One line of the recent transactions on Home, already worded. [received] picks the icon and the colour of the amount. */
internal data class HomeTxRow(val received: Boolean, val title: String, val date: String, val amount: String, val status: String)

/**
 * The first [limit] of [history] as Home rows, in the same order.
 * The amount is the net change of the wallet, so a send includes its fee, and it keeps a sign: a plus for money in, a real minus (U+2212) for money out.
 * A row has no counterparty name or txid, because the wallet knows no name and the id means nothing to the user.
 * [timeZoneId] is an IANA id for the date, and null means the device's zone.
 */
internal fun homeRows(history: List<HotTx>, limit: Int = HOME_ROW_LIMIT, timeZoneId: String? = null): List<HomeTxRow> =
    history.take(limit).map { tx ->
        val received = tx.netSats >= 0
        val blockTime = tx.blockTime
        HomeTxRow(
            received = received,
            title = if (received) "รับ" else "ส่งออก",
            date = when {
                blockTime != null -> formatThaiDateTimeShort(blockTime.toLong(), timeZoneId)
                tx.confirmations == 0u -> "กำลังรอบล็อก"
                // The server may confirm a transaction without a time the app can print, and "waiting for a block" would then contradict the status.
                else -> "—"
            },
            amount = (if (received) "+" else "−") + formatBtcCompact(abs(tx.netSats)),
            status = when {
                tx.confirmations == 0u -> "รอยืนยัน"
                tx.confirmations < SETTLED_CONFIRMATIONS -> "ยืนยัน ${tx.confirmations} บล็อก"
                else -> "ยืนยันแล้ว"
            },
        )
    }

/** Every loaded transaction as rows, newest first like Home, with no limit. */
internal fun historyRows(history: List<HotTx>, timeZoneId: String? = null): List<HomeTxRow> =
    homeRows(history, limit = history.size, timeZoneId = timeZoneId)

/** True when Home hides rows, which is when it offers the link to the full history. */
internal fun homeHasMoreHistory(history: List<HotTx>): Boolean = history.size > HOME_ROW_LIMIT

/**
 * The Home balance line: the amount in BTC once the chain answered, and a dash while it is unknown or loading.
 * Four places when they hold the whole amount, every place that matters when they do not, so the line never hides sats.
 */
internal fun homeBalanceText(sats: Long, known: Boolean, loading: Boolean): String =
    if (known && !loading) formatBtcCompact(sats) + " BTC" else "—"
