package com.mebit.app.feature.send

import com.mebit.app.data.amountText
import com.mebit.app.data.BtcUnit
import com.mebit.app.data.FiatQuote
import com.mebit.app.data.bahtText
import com.mebit.app.data.formatBtcCompact

/** [sats] as the baht line under an amount, such as `≈ ฿18,010`. Null when there is no price to give it, and the screen hides the line. */
internal fun fiatLine(sats: Long, quote: FiatQuote?): String? = bahtText(sats, quote)?.let { "≈ $it" }

/** The fee a payment pays, on the Review rows and the fee question: sats first, then the baht when there is a price, such as `2,760 sat · ฿99`. */
internal fun realFeeText(feeSats: Long, quote: FiatQuote?): String =
    listOfNotNull("${thousands(feeSats)} sat", bahtText(feeSats, quote)).joinToString(" · ")

/** Review's คงเหลือ: what [balanceSats] leaves once [totalSats] goes, never below nothing, and a dash while either is unknown. */
internal fun keptText(balanceSats: Long?, totalSats: Long?, unit: BtcUnit = BtcUnit.Btc): String =
    if (balanceSats != null && totalSats != null) amountText((balanceSats - totalSats).coerceAtLeast(0), unit) else "—"

/** The fee on the slip, such as `1,128 sat ≈ ฿41`; just the sats without a price. The rate sits beside it. */
internal fun slipFeeText(feeSats: Long, quote: FiatQuote?): String =
    listOfNotNull("${thousands(feeSats)} sat", bahtText(feeSats, quote)).joinToString(" ≈ ")

/** [text] in groups of four, [perLine] groups to a line, such as `bc1q xy2k gdyg\njrsq tzq2`. */
internal fun groupedLines(text: String, perLine: Int): String =
    text.chunked(4).chunked(perLine).joinToString("\n") { it.joinToString(" ") }

/** What the address itself proves about its type, or null when it proves nothing worth naming. */
internal fun addressTypeLine(address: String): String? {
    val a = address.lowercase()
    return when {
        Regex("^(bc|tb|bcrt)1q").containsMatchIn(a) -> "Native SegWit"
        Regex("^(bc|tb|bcrt)1p").containsMatchIn(a) -> "Taproot"
        else -> null
    }
}
