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
    fun aDebugBuildShowsTheRustTextAsItCame() {
        assertEquals(FAILURE, debugFailureLine(FAILURE, debugBuild = true))
    }

    @Test
    fun aLoadThatFailedNothingShowsNoLineInEitherBuild() {
        assertNull(debugFailureLine(null, debugBuild = true))
        assertNull(debugFailureLine(null, debugBuild = false))
    }
}
