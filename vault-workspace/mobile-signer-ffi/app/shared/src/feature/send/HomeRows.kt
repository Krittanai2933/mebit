package com.mebit.app.feature.send

import com.mebit.app.data.SingleSigWallet.WalletMode
import com.mebit.app.data.formatBtcCompact
import com.mebit.app.formatThaiDateTimeShort
import com.mebit.ffi.signer.HotScan
import com.mebit.ffi.signer.HotTx
import kotlin.math.abs

/** The confirmations the boards count to. The detail's six segments fill up to it, and a row counts blocks up to it. */
internal const val CONFIRMATION_TARGET = 6u

/** Confirmations from which a row says only that it is confirmed. The board shows a count up to 6 and a bare "confirmed" after it, so 7 is a guess. */
private val SETTLED_CONFIRMATIONS = CONFIRMATION_TARGET + 1u

/** How many rows Home shows before it points to the full history. */
private const val HOME_ROW_LIMIT = 4

/** One line of the recent transactions on Home, already worded. [tx] is the transaction it shows, which a tap opens. [received] picks the icon and the colour of the amount. */
internal data class HomeTxRow(val tx: HotTx, val received: Boolean, val title: String, val date: String, val amount: String, val status: String)

/**
 * The first [limit] of [history] as Home rows, in the same order.
 * The amount is the net change of the wallet, so a send includes its fee, and it keeps a sign: a plus for money in, a real minus (U+2212) for money out.
 * A row shows no counterparty name or txid, because the wallet knows no name and the id means nothing to the user. The detail behind a tap shows the txid.
 * [timeZoneId] is an IANA id for the date, and null means the device's zone.
 */
internal fun homeRows(history: List<HotTx>, limit: Int = HOME_ROW_LIMIT, timeZoneId: String? = null): List<HomeTxRow> =
    history.take(limit).map { tx ->
        val received = tx.netSats >= 0
        val blockTime = tx.blockTime
        HomeTxRow(
            tx = tx,
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

/** True when there are more rows than the four the box on Home shows whole, which is when Home offers the link to the full history. */
internal fun homeHasMoreHistory(history: List<HotTx>): Boolean = history.size > HOME_ROW_LIMIT

/**
 * The Home balance line: the amount in BTC once the chain answered, and a dash while it is unknown or loading.
 * Four places when they hold the whole amount, every place that matters when they do not, so the line never hides sats.
 */
internal fun homeBalanceText(sats: Long, known: Boolean, loading: Boolean): String =
    if (known && !loading) formatBtcCompact(sats) + " BTC" else "—"

/** True while a live wallet's scan stopped short and Home asks again by itself, after [scanContinueDelayMillis]. */
internal fun scanContinues(mode: WalletMode, scan: HotScan): Boolean = mode == WalletMode.Live && scan == HotScan.CONTINUING

/** The calm line under Home's dash while the scan goes on by itself, or null. The copy is a draft, because no board shows this state yet. */
internal fun homeScanLine(mode: WalletMode, scan: HotScan): String? =
    "กำลังค้นหาประวัติของกระเป๋า · จะค้นต่อให้อัตโนมัติ".takeIf { scanContinues(mode, scan) }

/**
 * How long Home waits before it asks again after the [asked]th ask in a row whose scan stopped short. It is a minute, then twice
 * as long each time, and never more than a quarter of an hour. A host that refused with its request limit is left alone for the
 * wait it asked for, or a minute, so the first ask finds it or the next host free, and the doubling spares a host that keeps refusing.
 */
internal fun scanContinueDelayMillis(asked: Int): Long = (60_000L shl asked.coerceIn(0, 4)).coerceAtMost(15 * 60_000L)
