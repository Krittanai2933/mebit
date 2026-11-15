#!/usr/bin/env python3
"""Fixes the cancellation leak in Gobley 0.3.7's generated `uniffiRustCallAsync`.

Usage: patch_bindings.py <generated .kt file>...

The generated function frees an async call's Rust future in a `finally` inside
`withContext(Dispatchers.IO)`. The caller has already made the Rust call by then, because it
evaluates its arguments first, and the future owns a strong reference to the Rust object.
`withContext` throws without running its block when the caller is cancelled first, either
already or while the block waits for the IO dispatcher. The `finally` never runs, so the future
and the object it holds leak. A link the object owns then stays up until the object is dropped,
which a leaked object never is (inferred from the code, not observed on a device).

The patch moves that `finally` outside `withContext`. `withContext` returns or throws only after
its block has ended or was never started, so the one free runs exactly once on every path.
`rust_future_free` consumes the future's handle, so a second free would be a use-after-free.
UniFFI's own Kotlin template frees the future on the caller's side the same way.

It replaces the whole function, exactly once per file. If the text is not there exactly once
(another Gobley, another UniFFI), it changes nothing and exits 1, so the patch cannot drift
silently. An upgrade of Gobley fails the version pin in ffi.sh first. Then check whether the new
generated function still leaks, and update or delete this script. test_patch_bindings.py runs
the fail-closed cases, and ffi.sh runs it in preflight.
"""
import sys

OLD = """internal suspend fun<T, F, E: kotlin.Exception> uniffiRustCallAsync(
    rustFuture: Long,
    pollFunc: (Long, UniffiRustFutureContinuationCallback, Long) -> Unit,
    completeFunc: (Long, UniffiRustCallStatus) -> F,
    freeFunc: (Long) -> Unit,
    cancelFunc: (Long) -> Unit,
    liftFunc: (F) -> T,
    errorHandler: UniffiRustCallStatusErrorHandler<E>
): T {
    return withContext(Dispatchers.IO) {
        try {
            do {
                val pollResult = suspendCancellableCoroutine<Byte> { continuation ->
                    val handle = uniffiContinuationHandleMap.insert(continuation)
                    continuation.invokeOnCancellation {
                        cancelFunc(rustFuture)
                    }
                    pollFunc(
                        rustFuture,
                        uniffiRustFutureContinuationCallbackCallback,
                        handle
                    )
                }
            } while (pollResult != UNIFFI_RUST_FUTURE_POLL_READY);

            return@withContext liftFunc(
                uniffiRustCallWithError(errorHandler) { status -> completeFunc(rustFuture, status) }
            )
        } finally {
            freeFunc(rustFuture)
        }
    }
}
"""

NEW = """internal suspend fun<T, F, E: kotlin.Exception> uniffiRustCallAsync(
    rustFuture: Long,
    pollFunc: (Long, UniffiRustFutureContinuationCallback, Long) -> Unit,
    completeFunc: (Long, UniffiRustCallStatus) -> F,
    freeFunc: (Long) -> Unit,
    cancelFunc: (Long) -> Unit,
    liftFunc: (F) -> T,
    errorHandler: UniffiRustCallStatusErrorHandler<E>
): T {
    // Patched by scripts/patch_bindings.py. Gobley freed the future in a finally inside withContext.
    // withContext throws without running its block when the caller is cancelled first (already, or
    // while the block waits for Dispatchers.IO), so that finally never ran and the future, with the
    // object it holds, leaked. withContext returns or throws only after its block has ended or was
    // never started, so freeing here runs exactly once on every path.
    try {
        return withContext(Dispatchers.IO) {
            do {
                val pollResult = suspendCancellableCoroutine<Byte> { continuation ->
                    val handle = uniffiContinuationHandleMap.insert(continuation)
                    continuation.invokeOnCancellation {
                        cancelFunc(rustFuture)
                    }
                    pollFunc(
                        rustFuture,
                        uniffiRustFutureContinuationCallbackCallback,
                        handle
                    )
                }
            } while (pollResult != UNIFFI_RUST_FUTURE_POLL_READY);

            return@withContext liftFunc(
                uniffiRustCallWithError(errorHandler) { status -> completeFunc(rustFuture, status) }
            )
        }
    } finally {
        freeFunc(rustFuture)
    }
}
"""


def main(paths):
    if not paths:
        sys.exit("usage: patch_bindings.py <generated .kt file>...")
    texts = {}
    for path in paths:
        with open(path, encoding="utf-8", newline="") as handle:
            texts[path] = handle.read()
        found = texts[path].count(OLD)
        if found != 1:
            sys.exit("%s: expected the Gobley 0.3.7 uniffiRustCallAsync exactly once, found %d" % (path, found))
    for path, text in texts.items():
        with open(path, "w", encoding="utf-8", newline="") as handle:
            handle.write(text.replace(OLD, NEW))


if __name__ == "__main__":
    main(sys.argv[1:])
