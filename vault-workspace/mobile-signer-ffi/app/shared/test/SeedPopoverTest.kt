package com.mebit.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeedPopoverTest {
    @Test
    fun theFirstFourMatchesShowAndTheRestAreCounted() {
        // Board 1B.2: "ab" lists abandon, ability, able, about and "+4".
        val window = suggestionWindow(listOf("abandon", "ability", "able", "about", "above", "absent", "absorb", "abstract"))
        assertEquals(listOf("abandon", "ability", "able", "about"), window.shown)
        assertEquals(4, window.more)
    }

    @Test
    fun fourOrFewerMatchesShowEveryOneWithNoCount() {
        // Board 1B.2.2: "zo" lists zone and zoo only.
        assertEquals(SuggestionWindow(listOf("zone", "zoo"), 0), suggestionWindow(listOf("zone", "zoo")))
        assertEquals(SuggestionWindow(listOf("a", "b", "c", "d"), 0), suggestionWindow(listOf("a", "b", "c", "d")))
        assertEquals(SuggestionWindow(emptyList(), 0), suggestionWindow(emptyList()))
    }

    @Test
    fun theValidLastWordsUseTheSameWindow() {
        // Board 1B.3: art, diesel, false, kite and "+4".
        val window = suggestionWindow(listOf("art", "diesel", "false", "kite", "organ", "ready", "surface", "trouble"))
        assertEquals(listOf("art", "diesel", "false", "kite"), window.shown)
        assertEquals(4, window.more)
    }

    @Test
    fun thePopoverOpensUnderItsSlotInEachColumn() {
        // Card-relative tops from boards 1B.2.1 (slot 4), 1B.2.2 (slot 11) and 1B.2.3 (slot 18); the card is at y 236.
        assertEquals(PopoverPlacement(column = 0, top = 394 - 236, opensUp = false), popoverPlacement(slot = 3, rows = 4))
        assertEquals(PopoverPlacement(column = 1, top = 358 - 236, opensUp = false), popoverPlacement(slot = 10, rows = 2))
        assertEquals(PopoverPlacement(column = 2, top = 322 - 236, opensUp = false), popoverPlacement(slot = 17, rows = 4))
    }

    @Test
    fun theLastSlotsPopoverOpensUpward() {
        // Board 1B.3.1: slot 24's four rows end 4dp above the slot and cover rows 20 to 23.
        assertEquals(PopoverPlacement(column = 2, top = 372 - 236, opensUp = true), popoverPlacement(slot = 23, rows = 4))
    }

    @Test
    fun aShortListUnderALowSlotStillOpensDownward() {
        assertFalse(popoverPlacement(slot = 5, rows = 2).opensUp)
        assertTrue(popoverPlacement(slot = 5, rows = 4).opensUp)
    }

    @Test
    fun noPopoverLeavesTheCardOrCoversItsOwnSlot() {
        for (slot in 0 until 24) {
            for (rows in 1..4) {
                val placement = popoverPlacement(slot, rows)
                val slotTop = 16 + 36 * (slot % 8)
                val bottom = placement.top + 32 * rows
                val where = "slot ${slot + 1} with $rows rows"
                assertEquals(slot / 8, placement.column, where)
                assertTrue(placement.top >= 0 && bottom <= 357, "$where leaves the card: ${placement.top}..$bottom")
                assertTrue(placement.top >= slotTop + 30 || bottom <= slotTop, "$where covers its slot")
            }
        }
    }
}
