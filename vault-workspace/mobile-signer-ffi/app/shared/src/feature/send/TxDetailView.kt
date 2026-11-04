package com.mebit.app.feature.send

import com.mebit.app.data.FiatQuote
import com.mebit.app.data.TxDetailLoad
import com.mebit.app.data.formatBtcCompact
import com.mebit.app.data.readErrorThai
import com.mebit.app.formatThaiDateTime
import com.mebit.ffi.signer.HotTx
import com.mebit.ffi.signer.HotTxDetail
import com.mebit.ffi.signer.HotTxo
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.TxoOwner
import kotlin.math.abs

/** Board 3.6.2 for one transaction, already worded. A null card is a card the screen does not draw at all. */
internal data class TxDetailView(
    val received: Boolean,
    val title: String,
    val amount: String,
    val amountNote: String?,
    val fiat: String?,
    val testnet: Boolean,
    val status: TxStatusView,
    val date: String?,
    val addresses: TxAddresses?,
    val cost: TxCostView?,
    val txid: String,
    val technical: TxTechnicalView?,
    val note: TxNote?,
    val debugText: String?,
)

/** [confirmed] picks the badge colours. [filled] is how many of the six segments are green, and null when the card draws no segments. */
internal data class TxStatusView(val confirmed: Boolean, val title: String, val line: String, val filled: Int?, val hint: String?)

/** [addresses] are raw, and the screen groups them by four. */
internal data class TxAddresses(val label: String, val addresses: List<String>)

internal data class TxCostView(val fee: String, val rate: String, val total: String, val change: String?)

internal data class TxTechnicalView(
    val inputs: List<TxoLine>,
    val outputs: List<TxoLine>,
    val fee: String,
    val rate: String,
    val size: String,
    val vsize: String,
    val rbf: String,
    val block: String,
)

internal data class TxoLine(val address: String, val amount: String, val tag: String?)

/** The line under the status card while the detail is not there. [retry] offers ลองอีกครั้ง. */
internal data class TxNote(val text: String, val tag: String, val retry: Boolean)

/** The row the catalog opens before any tap, so the screen always has something to draw. The txid is "mebitdemo" in hex, padded. */
internal val DEMO_TX = HotTx("6d6562697464656d6f" + "0".repeat(46), -1_801_240L, 3u, 1_787_036_520uL)

/**
 * [row] is what the tapped row drew, shown at once. Once [load] is [TxDetailLoad.Loaded] its fresh row replaces it and the cards
 * that need the inputs and outputs appear. A send then shows what reached others, and until then the net with its fee, labelled so.
 * [network] is the one the detail was asked on. [timeZoneId] is an IANA id for the date, and null means the device's zone.
 */
internal fun txDetailView(row: HotTx, load: TxDetailLoad, quote: FiatQuote?, network: KeyNetwork, timeZoneId: String? = null): TxDetailView {
    val detail = (load as? TxDetailLoad.Loaded)?.detail
    val tx = detail?.tx ?: row
    val received = tx.netSats >= 0
    val net = abs(tx.netSats)
    val (others, ownOutputs) = detail?.outputs.orEmpty().partition { it.owner == TxoOwner.OTHER }
    // A self-send reached nobody, so it keeps the net and says the fee is in it.
    val reached = others.sumOf { it.valueSats }.toLong().takeIf { !received && it > 0 }
    val amountSats = reached ?: net
    return TxDetailView(
        received = received,
        title = if (received) "รับ" else "ส่งออก",
        amount = btc(amountSats),
        amountNote = "รวมค่าธรรมเนียม".takeIf { !received && reached == null },
        fiat = fiatLine(amountSats, quote)?.let { "มูลค่าตอนนี้ $it" },
        testnet = network == KeyNetwork.TEST,
        status = status(tx, detail, received),
        date = when {
            tx.confirmations == 0u -> null
            // The server may confirm a transaction without a time the app can print, as on the rows.
            else -> tx.blockTime?.let { formatThaiDateTime(it.toLong(), timeZoneId) } ?: "—"
        },
        addresses = detail?.let {
            if (received) TxAddresses("เข้าที่อยู่ของคุณ", ownOutputs.mapNotNull { o -> o.address }) else TxAddresses("ส่งไปที่", others.mapNotNull { o -> o.address })
        }?.takeIf { it.addresses.isNotEmpty() },
        cost = detail?.takeUnless { received }?.let {
            val change = ownOutputs.sumOf { o -> o.valueSats }.toLong()
            TxCostView(realFeeText(it.feeSats.toLong(), quote), rate(it), btc(net), change.takeIf { c -> c > 0 }?.let(::btc))
        },
        txid = row.txid,
        technical = detail?.let {
            TxTechnicalView(
                inputs = it.inputs.map { i -> txoLine(i, if (i.owner == TxoOwner.OTHER) null else OWN) },
                outputs = it.outputs.map { o -> txoLine(o, outputTag(o.owner, received)) },
                fee = "${thousands(it.feeSats.toLong())} sat",
                rate = rate(it),
                size = "${thousands(it.sizeBytes.toLong())} B",
                vsize = "${thousands(it.vsize.toLong())} vB",
                rbf = if (it.signalsRbf) "ส่งสัญญาณ" else "ไม่ส่งสัญญาณ",
                block = it.blockHeight?.let { h -> "บล็อกที่ ${thousands(h.toLong())}" } ?: "ยังไม่อยู่ในบล็อก",
            )
        },
        note = when (load) {
            TxDetailLoad.Loading -> TxNote("กำลังโหลดรายละเอียด…", "tx_loading", retry = false)
            TxDetailLoad.NotFound -> TxNote("ไม่พบธุรกรรมนี้ใน explorer ตอนนี้", "tx_not_found", retry = true)
            is TxDetailLoad.Failed -> TxNote(readErrorThai(load.rustText), "tx_failed", retry = true)
            is TxDetailLoad.Loaded, TxDetailLoad.RowOnly -> null
        },
        debugText = (load as? TxDetailLoad.Failed)?.rustText,
    )
}

private const val OWN = "ของกระเป๋านี้"

private fun btc(sats: Long) = formatBtcCompact(sats) + " BTC"

/** Up to [CONFIRMATION_TARGET] the card counts blocks on six segments and says when it is usually final. Past it, the count alone. */
private fun status(tx: HotTx, detail: HotTxDetail?, received: Boolean): TxStatusView {
    val count = tx.confirmations
    if (count == 0u) {
        return TxStatusView(
            confirmed = false,
            title = "ยังไม่ยืนยัน",
            line = "รอยืนยันในบล็อก" + (detail?.let { " · จ่าย ${rate(it)}" } ?: ""),
            filled = null,
            hint = "ไม่ต้องทำอะไร และอย่าส่งซ้ำ".takeUnless { received },
        )
    }
    return TxStatusView(
        confirmed = true,
        title = "ยืนยันแล้ว",
        line = "ยืนยัน ${thousands(count.toLong())} บล็อก" + (detail?.blockHeight?.let { " · บล็อกที่ ${thousands(it.toLong())}" } ?: ""),
        filled = count.toInt().takeIf { count <= CONFIRMATION_TARGET },
        hint = "ปกติถือว่าแน่นอนเมื่อครบ $CONFIRMATION_TARGET บล็อก".takeIf { count < CONFIRMATION_TARGET },
    )
}

/** The fee over the virtual size, cut to one decimal and never rounded up, such as `4.2 sat/vB`. */
private fun rate(detail: HotTxDetail): String {
    val tenths = detail.feeSats * 10u / detail.vsize
    return "${thousands((tenths / 10u).toLong())}.${tenths % 10u} sat/vB"
}

private fun outputTag(owner: TxoOwner, received: Boolean): String? = when {
    owner == TxoOwner.OTHER -> "ปลายทาง".takeUnless { received }
    owner == TxoOwner.CHANGE && !received -> "เงินทอน · $OWN"
    else -> OWN
}

private fun txoLine(txo: HotTxo, tag: String?) =
    TxoLine(txo.address?.let { shorten(it, "…") } ?: "ไม่มีที่อยู่", btc(txo.valueSats.toLong()), tag)
