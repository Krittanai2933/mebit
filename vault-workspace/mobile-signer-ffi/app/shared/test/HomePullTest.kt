package com.mebit.app.feature.send

import kotlin.test.Test
import kotlin.test.assertEquals

class HomePullTest {
    @Test
    fun aPullWithNoBindRunningStartsOne() {
        assertEquals(4, afterPull(retries = 3, binding = false))
    }

    @Test
    fun aPullDuringABindStartsNoSecondOne() {
        assertEquals(3, afterPull(retries = 3, binding = true))
    }
}
