package com.mebit.app.feature.send

import com.mebit.app.data.FiatQuote
import com.mebit.app.data.bahtText

/** [sats] as the baht line under an amount, such as `≈ ฿18,010`. Null when there is no price to give it, and the screen hides the line. */
internal fun fiatLine(sats: Long, quote: FiatQuote?): String? = bahtText(sats, quote)?.let { "≈ $it" }

/** The fee on the Review rows, an estimate until the coins are chosen: sats first, then the baht when there is a price, such as `≈ 1,128 sat · ฿41`. */
internal fun reviewFeeText(feeSats: Long, quote: FiatQuote?): String =
    "≈ " + listOfNotNull("${thousands(feeSats)} sat", bahtText(feeSats, quote)).joinToString(" · ")

/** The fee on the slip: baht first, then the rate, such as `฿41 · 8 sat/vB`. Without a price the sats take the baht's place. */
internal fun slipFeeText(feeSats: Long, satPerVb: Long, quote: FiatQuote?): String =
    "${bahtText(feeSats, quote) ?: "${thousands(feeSats)} sat"} · $satPerVb sat/vB"
