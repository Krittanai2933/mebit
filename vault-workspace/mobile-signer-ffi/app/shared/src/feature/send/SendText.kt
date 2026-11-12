package com.mebit.app.feature.send

import com.mebit.app.data.FiatQuote
import com.mebit.app.data.bahtText
import com.mebit.app.data.formatBtcCompact

/** [sats] as the baht line under an amount, such as `≈ ฿18,010`. Null when there is no price to give it, and the screen hides the line. */
internal fun fiatLine(sats: Long, quote: FiatQuote?): String? = bahtText(sats, quote)?.let { "≈ $it" }

/** The fee a payment pays, on the Review rows and the fee question: sats first, then the baht when there is a price, such as `2,760 sat · ฿99`. */
internal fun realFeeText(feeSats: Long, quote: FiatQuote?): String =
    listOfNotNull("${thousands(feeSats)} sat", bahtText(feeSats, quote)).joinToString(" · ")

/** Review's คงเหลือ: what [balanceSats] leaves once [totalSats] goes, never below nothing, and a dash while either is unknown. */
internal fun keptText(balanceSats: Long?, totalSats: Long?): String =
    if (balanceSats != null && totalSats != null) "${formatBtcCompact((balanceSats - totalSats).coerceAtLeast(0))} BTC" else "—"

/** The fee on the slip: baht first, then the rate, such as `฿41 · 8 sat/vB`. Without a price the sats take the baht's place. */
internal fun slipFeeText(feeSats: Long, satPerVb: Long, quote: FiatQuote?): String =
    "${bahtText(feeSats, quote) ?: "${thousands(feeSats)} sat"} · $satPerVb sat/vB"
