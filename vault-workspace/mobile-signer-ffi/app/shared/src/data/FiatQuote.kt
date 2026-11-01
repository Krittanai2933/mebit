package com.mebit.app.data

import com.mebit.app.feature.send.thousands
import com.mebit.ffi.signer.fetchBtcThb
import kotlin.math.floor
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val FRESH_SECONDS = 60L
private const val USABLE_SECONDS = 300L

/** Baht for one bitcoin, and the phone clock in Unix seconds when the price was asked for. */
class FiatQuote(val thbPerBtc: Double, val fetchedAtSeconds: Long)

/**
 * [sats] as whole baht, rounded half up, with thousands separators, such as `฿18,010`.
 * Zero needs no price, so it gives `฿0` even without a quote. Any other amount without a quote gives null and the caller hides the line.
 * [sats] is never negative, the caller owns any sign. The screens add the `≈ ` prefix themselves.
 */
fun bahtText(sats: Long, quote: FiatQuote?): String? {
    if (sats == 0L) return "฿0"
    if (quote == null) return null
    // Double, so that a large balance times the price cannot overflow Long.
    val baht = floor(sats * quote.thbPerBtc / 100_000_000.0 + 0.5).toLong()
    return "฿${thousands(baht)}"
}

/**
 * The newest [FiatQuote]. A quote under a minute old answers without a fetch, and callers that arrive together share one fetch.
 * [fetch] returns null when the price source fails and must not throw. A failed fetch is not kept, so the next call tries again,
 * and the quote before it keeps answering until it is five minutes old.
 */
class QuoteCache(
    private val fetch: suspend () -> FiatQuote?,
    private val nowSeconds: () -> Long,
) {
    private val lock = Mutex()
    private var held: FiatQuote? = null

    suspend fun fresh(): FiatQuote? = lock.withLock {
        val old = held
        if (old != null && old.isYoungerThan(FRESH_SECONDS)) return@withLock old
        val fetched = fetch()
        if (fetched != null) held = fetched
        fetched ?: old?.takeIf { it.isYoungerThan(USABLE_SECONDS) }
    }

    // A quote dated in the future means the clock was set back, so it counts as old.
    private fun FiatQuote.isYoungerThan(seconds: Long) = (nowSeconds() - fetchedAtSeconds) in 0L until seconds
}

/** A [QuoteCache] over the Rust price export, which blocks, so it runs on [Dispatchers.Default]. */
fun liveQuoteCache() = QuoteCache(
    fetch = {
        withContext(Dispatchers.Default) {
            runCatching { fetchBtcThb() }.getOrNull()?.let { FiatQuote(it.thbPerBtc, it.fetchedAt.toLong()) }
        }
    },
    nowSeconds = { Clock.System.now().epochSeconds },
)
