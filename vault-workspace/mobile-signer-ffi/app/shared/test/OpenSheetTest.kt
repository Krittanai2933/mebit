package com.mebit.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenSheetTest {
    private val heard = mutableListOf<String>()
    private val closed = mutableListOf<String>()
    private val slot = OpenSheet<String>()

    @Test
    fun aCoverClosesTheOpenSheetAndItsCallerHearsOneCancel() {
        slot.opened("picker") { heard += "cancel" }
        slot.cover { closed += it }
        assertEquals(listOf("picker"), closed)
        assertEquals(listOf("cancel"), heard)
        // The sheet's own callback after the cover, as UIKit may send on a dismiss, is dropped.
        assertFalse(slot.answered("picker"))
        slot.cover { closed += it }
        assertEquals(listOf("picker"), closed)
        assertEquals(listOf("cancel"), heard)
    }

    @Test
    fun theUsersAnswerWinsAndALaterCoverHasNothingToClose() {
        slot.opened("share") { heard += "cancel" }
        assertTrue(slot.answered("share"))
        assertFalse(slot.answered("share"))
        slot.cover { closed += it }
        assertEquals(emptyList(), closed)
        assertEquals(emptyList(), heard)
    }

    @Test
    fun onlyTheOpenSheetsCallbackCounts() {
        slot.opened("second") { heard += "cancel second" }
        assertFalse(slot.answered("first"))
        assertFalse(slot.answered(null))
        slot.cover { closed += it }
        assertEquals(listOf("second"), closed)
        assertEquals(listOf("cancel second"), heard)
    }

    @Test
    fun aCoverWithNoSheetOpenDoesNothing() {
        slot.cover { closed += it }
        assertEquals(emptyList(), closed)
    }
}
