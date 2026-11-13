package com.mebit.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PairingsTest {
    @Test
    fun aCredentialIsNamedByItsFingerprintAndNothingElseNamesOne() {
        assertEquals("safe7.73c5da0a", pairingName("73c5da0a"))
        assertEquals("safe7.73c5da0a", pairingName("73C5DA0A"), "one key, one credential, whatever the case")
        for (outside in listOf("", "73c5da0", "73c5da0a0", "../../x", "73c5da0g", "safe7.73c5da0a")) {
            assertNull(pairingName(outside), "[$outside]")
        }
    }
}
