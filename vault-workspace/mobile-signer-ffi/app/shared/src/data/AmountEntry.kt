package com.mebit.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mebit.app.feature.send.fiatLine
import com.mebit.app.feature.send.thousands
import kotlin.math.floor

private const val SATS_PER_BTC = 100_000_000.0
private const val MAX_SATS = 21_000_000.0 * SATS_PER_BTC
private const val MAX_BAHT_DIGITS = 9
private val BtcTyping = Regex("""\d{0,8}(\.\d{0,8})?""")

/** Whole [baht] to sats at [thbPerBtc], rounded half up. Null when that is more than 21 million BTC. */
fun bahtToSats(baht: Long, thbPerBtc: Double): Long? {
    val sats = floor(baht * SATS_PER_BTC / thbPerBtc + 0.5)
    return if (sats in 0.0..MAX_SATS) sats.toLong() else null
}

/** [sats] as whole baht at [thbPerBtc], rounded half up. The number [bahtText] prints. */
fun satsToBahtRounded(sats: Long, thbPerBtc: Double): Long = floor(sats * thbPerBtc / SATS_PER_BTC + 0.5).toLong()

/** [sats] as whole baht at [thbPerBtc], cut down. Where the screen says what the wallet holds, it never shows more than that. */
fun satsToBahtFloor(sats: Long, thbPerBtc: Double): Long = floor(sats * thbPerBtc / SATS_PER_BTC).toLong()

/** 500_000 sats to "0.0050" and 3_118_872 to "0.03118872": at least 4 decimals and up to 8, zeros past the fourth cut, never rounded up. */
fun formatBtcCompact(sats: Long): String {
    val decimals = formatBtc(sats).substringAfter('.').trimEnd('0').length.coerceAtLeast(4)
    return formatBtc(sats, decimals)
}

/** The unit the amount card types and shows the amount in. */
enum class AmountUnit { Btc, Thb }

/**
 * What the Send screen's amount card takes and shows, in BTC or in baht.
 * [SendDraft.amountSats] is the one truth. The BTC text and the baht text are two renderings of it, so picking a unit never changes the sats.
 * Baht needs a price. Given no `quote`, a pick of baht and a typed baht amount are ignored, and everything shows in BTC.
 * The screen creates one per visit, so the unit is BTC again whenever the screen opens and the amount stays.
 */
class AmountEntry(private val draft: SendDraft) {
    /** The unit picked on the toggle. */
    var unit: AmountUnit by mutableStateOf(AmountUnit.Btc)
        private set

    /** [SendDraft.isSendAll]: true while the amount is what "ส่งทั้งหมด" set and is above zero. */
    val isSendAll: Boolean get() = draft.isSendAll

    private fun bahtQuote(quote: FiatQuote?): FiatQuote? = if (unit == AmountUnit.Thb) quote else null

    /** True when the card shows baht: baht is picked and there is a price. */
    fun inBaht(quote: FiatQuote?): Boolean = bahtQuote(quote) != null

    /** Picks the unit. Baht needs a price, so without one the pick is ignored. */
    fun pickUnit(next: AmountUnit, quote: FiatQuote?) {
        if (next == AmountUnit.Thb && quote == null) return
        unit = next
    }

    /** Takes the BTC field's text. Anything but digits with at most 8 decimals is ignored, so the field keeps its old text. */
    fun typeBtc(text: String) {
        if (!BtcTyping.matches(text)) return
        draft.setAmountBtc(text)
    }

    /**
     * Takes the baht field's digits. Anything but at most 9 digits, or an amount above 21 million BTC, is ignored, so the field keeps its old text.
     * The sats are the baht at the price, rounded half up.
     */
    fun typeBaht(text: String, quote: FiatQuote?) {
        if (quote == null || !text.all { it in '0'..'9' }) return
        val digits = text.trimStart('0')
        if (digits.length > MAX_BAHT_DIGITS) return
        val sats = bahtToSats(digits.toLongOrNull() ?: 0L, quote.thbPerBtc) ?: return
        draft.setAmountInSats(sats)
    }

    /** Sets the amount to the balance minus the fee estimate, whichever unit is shown. It stays send all until something else sets the amount, and the core then sets the real amount. */
    fun sendAll(balanceSats: Long) {
        draft.setSendAll((balanceSats - draft.feeSats).coerceAtLeast(0))
    }

    /** Picks the fee tier. While the amount is send all, it is worked out again for the new fee. */
    fun chooseTier(tier: FeeTier, balanceSats: Long) {
        val keepAll = draft.sendAllMarked
        draft.chooseTier(tier)
        if (keepAll) sendAll(balanceSats)
    }

    /** Takes the fetched rates. While the amount is send all, it is worked out again for the new fee, as [chooseTier] does. */
    fun applyTiers(tiers: LiveFeeTiers, balanceSats: Long) {
        val keepAll = draft.sendAllMarked
        draft.takeTiers(tiers)
        if (keepAll) sendAll(balanceSats)
    }

    /**
     * The amount field's text: the BTC text as typed, or in baht the digits of the sats, empty for zero.
     * Baht rounds half up, as the baht line does, so what was typed in baht reads back as typed. Send all cuts down.
     */
    fun fieldText(quote: FiatQuote?): String {
        val priced = bahtQuote(quote) ?: return draft.amountText
        val sats = draft.amountSats
        // shortcut: baht typed reads back as typed only up to 100,000,000 baht per bitcoin, where a sat is worth at most a baht. Above that, keep the typed text.
        return when {
            sats == 0L -> ""
            isSendAll -> satsToBahtFloor(sats, priced.thbPerBtc).toString()
            else -> satsToBahtRounded(sats, priced.thbPerBtc).toString()
        }
    }

    /** The gold line under the amount: the other unit, such as `≈ ฿18,010` or `≈ 0.0050 BTC`. Null for no amount or, in BTC, no price. */
    fun otherLine(quote: FiatQuote?): String? = when {
        draft.amountSats == 0L -> null
        bahtQuote(quote) != null -> "≈ ${formatBtcCompact(draft.amountSats)} BTC"
        else -> fiatLine(draft.amountSats, quote)
    }

    /** The caption under the amount: what the wallet holds in the unit shown, cut down in baht, or the note that the amount is send all. */
    fun caption(balanceSats: Long, quote: FiatQuote?): String {
        if (isSendAll) return "ส่งยอดทั้งหมด · หักค่าธรรมเนียมแล้ว"
        val priced = bahtQuote(quote) ?: return "ใช้ได้ ${formatBtcCompact(balanceSats)} BTC"
        return "ใช้ได้ ฿${thousands(satsToBahtFloor(balanceSats, priced.thbPerBtc))}"
    }
}
