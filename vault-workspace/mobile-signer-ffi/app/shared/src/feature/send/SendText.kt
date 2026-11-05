package com.mebit.app.feature.send

import com.mebit.app.Why
import com.mebit.app.data.FiatQuote
import com.mebit.app.data.SaveState
import com.mebit.app.data.bahtText

/** [sats] as the baht line under an amount, such as `≈ ฿18,010`. Null when there is no price to give it, and the screen hides the line. */
internal fun fiatLine(sats: Long, quote: FiatQuote?): String? = bahtText(sats, quote)?.let { "≈ $it" }

/** The fee on the Review rows, an estimate until the coins are chosen: sats first, then the baht when there is a price, such as `≈ 1,128 sat · ฿41`. */
internal fun reviewFeeText(feeSats: Long, quote: FiatQuote?): String = "≈ " + realFeeText(feeSats, quote)

/** The fee a signed transaction pays, on the fee question: sats first, then the baht when there is a price, such as `2,760 sat · ฿99`. */
internal fun realFeeText(feeSats: Long, quote: FiatQuote?): String =
    listOfNotNull("${thousands(feeSats)} sat", bahtText(feeSats, quote)).joinToString(" · ")

/** The fee on the slip: baht first, then the rate, such as `฿41 · 8 sat/vB`. Without a price the sats take the baht's place. */
internal fun slipFeeText(feeSats: Long, satPerVb: Long, quote: FiatQuote?): String =
    "${bahtText(feeSats, quote) ?: "${thousands(feeSats)} sat"} · $satPerVb sat/vB"

/**
 * Why this phone cannot send at all, for Send to say before the user starts, or null. Only a real key the store refused for a
 * reason that lasts, because a payment posts only after its pay-twice guard is saved. A TryLater save may still pass at the post.
 */
internal fun cannotSendText(words: Int, saved: SaveState, why: Why?): String? =
    if (words < 12 || saved != SaveState.NotSaved) null else when (why) {
        Why.NoScreenLock -> "เครื่องนี้ไม่มีรหัสล็อกหน้าจอ จึงยังส่งเงินไม่ได้"
        Why.OsTooOld, Why.NotWired -> "เครื่องนี้ยังส่งเงินไม่ได้ เพราะบันทึกกุญแจในเครื่องไม่ได้"
        Why.TryLater, Why.Other, null -> null
    }
