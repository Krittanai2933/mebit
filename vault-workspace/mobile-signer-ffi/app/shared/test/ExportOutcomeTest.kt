package com.mebit.app

import kotlin.test.Test
import kotlin.test.assertEquals

class ExportOutcomeTest {
    @Test
    fun aChoiceWithoutAnErrorIsDoneAndNoChoiceIsCancelled() {
        assertEquals(ExportOutcome.Done, exportOutcome(chose = true, error = null))
        assertEquals(ExportOutcome.Cancelled, exportOutcome(chose = false, error = null))
    }

    @Test
    fun anErrorWinsWhetherOrNotTheUserChose() {
        assertEquals(ExportOutcome.Failed("x"), exportOutcome(chose = true, error = "x"))
        assertEquals(ExportOutcome.Failed("x"), exportOutcome(chose = false, error = "x"))
    }
}
