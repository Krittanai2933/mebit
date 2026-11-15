package com.mebit.app.data

// Penpot Organize > 1B.1 to 1B.3.1, in dp. The seed card holds 3 columns of 8 slots, numbered down each column.
/** Slots in one column of the seed card. */
const val SEED_ROWS = 8

/** The seed card's padding: the first slot's top and left edge. */
const val SEED_CARD_PAD = 16

/** A slot's height. */
const val SEED_SLOT = 30

/** From one slot's top to the next one's: a slot and a 6dp gap. */
const val SEED_ROW_PITCH = 36

/** The seed card's height: 8 rows of slots, then the Passphrase option row. */
const val SEED_CARD_HEIGHT = 357

/** A row of the suggestion popover. */
const val POPOVER_ROW = 32

private const val POPOVER_GAP = 4
private const val POPOVER_MAX_ROWS = 4

/** The rows the suggestion popover shows, and how many more matches its "+N" label counts. */
data class SuggestionWindow(val shown: List<String>, val more: Int)

/** Where the suggestion popover sits: its column, its top in dp from the seed card's top, and whether it opens upward. */
data class PopoverPlacement(val column: Int, val top: Int, val opensUp: Boolean)

/** The popover's rows for [words], the matches or the valid last words. */
fun suggestionWindow(words: List<String>): SuggestionWindow =
    SuggestionWindow(words.take(POPOVER_MAX_ROWS), maxOf(0, words.size - POPOVER_MAX_ROWS))

/** The popover for slot [slot] (0 to 23) holding [rows] rows. It opens upward when below would pass the card's bottom. */
fun popoverPlacement(slot: Int, rows: Int): PopoverPlacement {
    val slotTop = SEED_CARD_PAD + SEED_ROW_PITCH * (slot % SEED_ROWS)
    val height = POPOVER_ROW * rows
    val below = slotTop + SEED_SLOT + POPOVER_GAP
    val up = below + height > SEED_CARD_HEIGHT
    return PopoverPlacement(slot / SEED_ROWS, if (up) slotTop - POPOVER_GAP - height else below, up)
}
