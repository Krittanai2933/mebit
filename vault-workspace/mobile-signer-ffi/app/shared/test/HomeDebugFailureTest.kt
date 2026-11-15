package com.mebit.app.feature.send

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val FAILURE = "snapshot: invalid PSBT json: esplora get: http://node.internal:8889/api/address/tb1qexample: Network Error: connection reset by peer, gave up after 3 attempts"

class HomeDebugFailureTest {
    @Test
    fun aReleaseBuildShowsNoRustText() {
        assertNull(debugFailureLine(FAILURE, debugBuild = false))
    }

    @Test
    fun aDebugBuildShowsTheRustTextWithTheServerHidden() {
        assertEquals(
            "snapshot: invalid PSBT json: esplora get: <host>/api/address/tb1qexample: Network Error: connection reset by peer, gave up after 3 attempts",
            debugFailureLine(FAILURE, debugBuild = true),
        )
    }

    @Test
    fun noServerAddressOrPortIsLeftWhenEveryHostFailed() {
        val everyHost = "history: esplora get: no testnet4 host is reachable (http://192.0.2.10:8889/api: Connection Failed; " +
            "https://blockstream.info/testnet4/api: HTTP status 429; https://mempool.space/testnet4/api/block-height/0: No route to host; 192.0.2.11:50001 refused)"
        val line = debugFailureLine(everyHost, debugBuild = true)!!
        for (gone in listOf("192.0.2", "8889", "50001", "blockstream.info", "mempool.space")) {
            assertEquals(false, gone in line, "$gone is still in: $line")
        }
        assertEquals(
            "history: esplora get: no testnet4 host is reachable (<host>/api: Connection Failed; <host>/testnet4/api: HTTP status 429; " +
                "<host>/testnet4/api/block-height/0: No route to host; <host> refused)",
            line,
        )
    }

    @Test
    fun aLoadThatFailedNothingShowsNoLineInEitherBuild() {
        assertNull(debugFailureLine(null, debugBuild = true))
        assertNull(debugFailureLine(null, debugBuild = false))
    }
}
