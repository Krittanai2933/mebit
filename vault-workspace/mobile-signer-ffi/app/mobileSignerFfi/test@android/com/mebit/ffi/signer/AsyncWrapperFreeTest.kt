package com.mebit.ffi.signer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Calls Gobley's uniffiRustCallAsync (patched by scripts/patch_bindings.py) with fake Rust lambdas and
// counts the frees. A second free of one future is a use after free in Rust and nothing would report it,
// so every path must free exactly once. The iOS function has the same text, and ffi.sh patches both.
private class Calls {
    val polls = AtomicInteger()
    val cancels = AtomicInteger()
    val frees = AtomicInteger()
}

private suspend fun Calls.callWrapper(
    poll: (Long, UniffiRustFutureContinuationCallback, Long) -> Unit = { _, resume, handle ->
        polls.incrementAndGet()
        resume.callback(handle, UNIFFI_RUST_FUTURE_POLL_READY)
    },
    complete: (Long, UniffiRustCallStatus) -> String = { _, _ -> "done" },
): String = uniffiRustCallAsync(
    rustFuture = 1L,
    pollFunc = poll,
    completeFunc = complete,
    freeFunc = { frees.incrementAndGet() },
    cancelFunc = { cancels.incrementAndGet() },
    liftFunc = { it },
    errorHandler = UniffiNullRustCallStatusErrorHandler,
)

class AsyncWrapperFreeTest {
    @Test
    fun aFinishedCallFreesTheFutureOnce() = runTest {
        val calls = Calls()
        assertEquals("done", calls.callWrapper())
        assertEquals(1, calls.polls.get())
        assertEquals(0, calls.cancels.get())
        assertEquals(1, calls.frees.get())
    }

    @Test
    fun aFailingCallFreesTheFutureOnce() = runTest {
        val calls = Calls()
        assertFailsWith<IllegalStateException> { calls.callWrapper(complete = { _, _ -> error("rust failed") }) }
        assertEquals(1, calls.frees.get())
    }

    @Test
    fun aCancelWhileRustIsPendingCancelsAndFreesOnce() = runTest {
        val calls = Calls()
        val polled = CompletableDeferred<Unit>()
        val job = launch {
            calls.callWrapper(poll = { _, _, _ ->
                calls.polls.incrementAndGet()
                polled.complete(Unit)
            })
        }
        polled.await()
        job.cancelAndJoin()
        assertEquals(1, calls.cancels.get())
        assertEquals(1, calls.frees.get())
    }

    @Test
    fun aCallFromACancelledCoroutineNeverPollsAndFreesOnce() = runTest {
        val calls = Calls()
        launch {
            cancel()
            assertFailsWith<CancellationException> { calls.callWrapper() }
        }.join()
        assertEquals(0, calls.polls.get())
        assertEquals(1, calls.frees.get())
    }

    // Every IO thread is held, so the call's block queues behind them and cannot start. The cancel lands
    // while it is queued, which was the second window of the leak.
    @Test
    fun aCancelWhileTheBlockWaitsForAnIoThreadNeverPollsAndFreesOnce() = runTest {
        val calls = Calls()
        val ioThreads = System.getProperty("kotlinx.coroutines.io.parallelism")?.toIntOrNull()
            ?: maxOf(64, Runtime.getRuntime().availableProcessors())
        val held = CountDownLatch(ioThreads)
        val release = CountDownLatch(1)
        val blockers = List(ioThreads) { launch(Dispatchers.IO) { held.countDown(); release.await() } }
        held.await()
        val job = launch { calls.callWrapper() }
        testScheduler.runCurrent()
        job.cancel()
        release.countDown()
        job.join()
        blockers.joinAll()
        assertEquals(0, calls.polls.get())
        assertEquals(1, calls.frees.get())
    }
}
