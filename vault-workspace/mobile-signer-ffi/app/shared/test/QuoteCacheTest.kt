package com.mebit.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class QuoteCacheTest {
    private var now = 1_000_000L
    private var fetches = 0
    private var answer: Double? = 2_600_000.0
    private val cache = QuoteCache(
        fetch = {
            fetches++
            answer?.let { FiatQuote(it, now) }
        },
        nowSeconds = { now },
    )

    @Test
    fun aQuoteUnderAMinuteIsReturnedWithoutAFetch() = runBlocking {
        val first = cache.fresh()
        now += 59
        assertSame(first, cache.fresh())
        assertEquals(1, fetches)
    }

    @Test
    fun aQuoteOfAMinuteIsFetchedAgain() = runBlocking {
        cache.fresh()
        now += 60
        answer = 2_700_000.0
        assertEquals(2_700_000.0, cache.fresh()?.thbPerBtc)
        assertEquals(2, fetches)
    }

    @Test
    fun aFailedRefetchKeepsTheOldQuoteForFiveMinutesThenReturnsNull() = runBlocking {
        val old = cache.fresh()
        answer = null
        now += 4 * 60
        assertSame(old, cache.fresh())
        now += 59
        assertSame(old, cache.fresh())
        now += 1
        assertNull(cache.fresh())
        now += 2 * 60
        assertNull(cache.fresh())
        assertEquals(5, fetches)
    }

    @Test
    fun aFailedFetchIsNotKeptSoTheNextCallTriesAgain() = runBlocking {
        answer = null
        assertNull(cache.fresh())
        assertNull(cache.fresh())
        assertEquals(2, fetches)
        answer = 2_600_000.0
        assertEquals(2_600_000.0, cache.fresh()?.thbPerBtc)
        assertEquals(3, fetches)
    }

    @Test
    fun twoCallersAtTheSameTimeShareOneFetch() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val slow = QuoteCache(
            fetch = {
                fetches++
                gate.await()
                FiatQuote(2_600_000.0, now)
            },
            nowSeconds = { now },
        )
        val first = async(start = CoroutineStart.UNDISPATCHED) { slow.fresh() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { slow.fresh() }
        gate.complete(Unit)
        withTimeout(5_000) { assertSame(first.await(), second.await()) }
        assertEquals(1, fetches)
    }

    @Test
    fun aClockSetBackDoesNotKeepAQuoteFresh() = runBlocking {
        cache.fresh()
        now -= 3_600
        assertNotNull(cache.fresh())
        assertEquals(2, fetches)
        answer = null
        now -= 3_600
        assertNull(cache.fresh())
    }
}
