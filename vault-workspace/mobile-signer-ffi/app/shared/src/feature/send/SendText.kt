package com.mebit.app.feature.send

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.mebit.app.data.amountText
import com.mebit.app.data.BtcUnit
import com.mebit.app.data.FiatQuote
import com.mebit.app.data.bahtText
import com.mebit.app.data.formatBtcCompact

/**
 * Pure. How many characters open [address] before what is its own: a bech32 address's network, its "1" and its version (bc1q, tb1p,
 * bcrt1q), or a base58 address's version character.
 */
internal fun addressPrefixLength(address: String): Int {
    val network = listOf("bcrt1", "bc1", "tb1").firstOrNull { address.lowercase().startsWith(it) }
    return minOf(if (network != null) network.length + 1 else 1, address.length)
}

/** Pure. The places in [address] that Penpot 5.2 and 5.3.2 pick out: the first four characters after its prefix and the last six, the ends a person compares first. */
internal fun addressAccents(address: String): Set<Int> {
    val start = addressPrefixLength(address)
    return ((start until minOf(start + 4, address.length)) + (maxOf(start, address.length - 6) until address.length)).toSet()
}

/** [address] in groups of four, [perLine] groups a line or one run that wraps when null, its [addressAccents] in [accent] and the rest in [ink]. */
internal fun accentedAddress(address: String, ink: Color, accent: Color, perLine: Int? = null): AnnotatedString = buildAnnotatedString {
    val accents = addressAccents(address)
    address.forEachIndexed { i, c ->
        if (i > 0 && i % 4 == 0) append(if (perLine != null && i % (4 * perLine) == 0) '\n' else ' ')
        withStyle(SpanStyle(color = if (i in accents) accent else ink)) { append(c) }
    }
}

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
