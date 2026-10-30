package com.mebit.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportSeedDraftTest {
    private val list = listOf("able", "about", "act", "action", "art", "diesel", "ocean", "zoo")

    private fun draft(): ImportSeedDraft = ImportSeedDraft(
        suggest = { prefix -> list.filter { it.startsWith(prefix) }.take(8) },
        lastWords = { listOf("art", "diesel") },
        check = { words ->
            val unknown = words.indexOfFirst { it !in list }
            when {
                unknown >= 0 -> SeedStatus.InvalidWord(unknown)
                words.last() != "art" -> SeedStatus.InvalidChecksum
                else -> SeedStatus.Valid
            }
        },
    )

    private fun fill(d: ImportSeedDraft, last: String = "art") {
        d.edit(List(23) { "ocean" }.joinToString(" ") + " $last")
    }

    @Test
    fun aPasteFillsEverySlotFromTheActiveOne() {
        val d = draft()
        fill(d)
        assertEquals(List(23) { "ocean" } + "art", d.words())
        assertEquals(23, d.active)
        assertEquals(SeedStatus.Valid, d.status)
        assertTrue(d.canImport)
    }

    @Test
    fun aPasteStartsAtTheActiveSlotAndStopsAtTheLast() {
        val d = draft()
        d.focus(22)
        d.edit("zoo zoo zoo")
        assertEquals("zoo", d.slots[22])
        assertEquals("zoo", d.slots[23])
        assertEquals(23, d.active)
    }

    @Test
    fun typingShowsMatchesAndTappingOneAdvances() {
        val d = draft()
        d.edit("ab")
        assertEquals(listOf("able", "about"), d.dropdown)
        d.accept("about")
        assertEquals("about", d.slots[0])
        assertEquals(1, d.active)
        assertEquals(emptyList(), d.dropdown)
    }

    @Test
    fun lateInputFromAFieldThatLostFocusIsIgnored() {
        val d = draft()
        d.edit("ocean ") // slot 1 done, slot 2 is active
        d.edit("tiger", index = 0) // one more keystroke from slot 1's field
        assertEquals("ocean", d.slots[0])
        assertEquals("", d.slots[1])
        d.edit("tiger", index = 1)
        assertEquals("tiger", d.slots[1])
    }

    @Test
    fun theFirstKeystrokeIntoAFilledSlotReplacesItsWord() {
        for ((typedInto, expected) in listOf("zart" to "z", "artz" to "z", "azrt" to "z", "z" to "z", "ar" to "ar")) {
            // caret at the start, at the end, in the middle, the field already replaced the word, a backspace
            val d = draft()
            fill(d) // slot 24 holds "art"
            d.focus(23)
            d.edit(typedInto)
            assertEquals(expected, d.slots[23], "typed into 'art' gave '$typedInto'")
        }
    }

    @Test
    fun onlyTheFirstKeystrokeReplaces() {
        val d = draft()
        fill(d)
        d.focus(23)
        d.edit("zart")
        d.edit("zo") // the field now holds "z"; the next key is a normal edit
        assertEquals("zo", d.slots[23])
    }

    @Test
    fun theKeyboardComingBackOnAFilledSlotArmsTheReplace() {
        val d = draft()
        fill(d)
        d.onFieldFocused()
        d.edit("qart")
        assertEquals("q", d.slots[23])
    }

    @Test
    fun anEmptySlotIsNotReplaced() {
        val d = draft()
        d.edit("oc")
        assertEquals("oc", d.slots[0])
        d.edit("oce")
        assertEquals("oce", d.slots[0])
    }

    @Test
    fun aWordFollowedByASpaceIsCommitted() {
        val d = draft()
        d.edit("ocean ")
        assertEquals("ocean", d.slots[0])
        assertEquals(1, d.active)
    }

    @Test
    fun aCompleteWordWithNoSiblingNeedsNoList() {
        val d = draft()
        d.edit("ocean")
        assertEquals(emptyList(), d.dropdown)
    }

    @Test
    fun nextTakesTheTypedWordBeforeALongerMatch() {
        val d = draft()
        d.edit("act")
        assertEquals(listOf("act", "action"), d.dropdown)
        d.next()
        assertEquals("act", d.slots[0])
        assertEquals(1, d.active)
    }

    @Test
    fun nextTakesTheFirstSuggestionForAPrefix() {
        val d = draft()
        d.edit("ab")
        d.next()
        assertEquals("able", d.slots[0])
    }

    @Test
    fun nextStaysOnAnEmptyOrUnknownSlot() {
        val d = draft()
        d.next()
        assertEquals(0, d.active)
        d.edit("qqq")
        d.next()
        assertEquals("qqq", d.slots[0])
        assertEquals(0, d.active)
    }

    @Test
    fun theLastSlotListsTheValidWordsOnlyOnceTheFirst23AreFilled() {
        val d = draft()
        d.focus(23)
        assertEquals(emptyList(), d.dropdown)
        d.focus(0)
        d.edit(List(23) { "ocean" }.joinToString(" "))
        d.focus(23)
        assertEquals(listOf("art", "diesel"), d.dropdown)
        d.edit("d")
        assertEquals(listOf("diesel"), d.dropdown)
    }

    @Test
    fun nextNeverPicksALastWordTheUserDidNotType() {
        val d = draft()
        d.edit(List(23) { "ocean" }.joinToString(" "))
        d.focus(23)
        d.next()
        assertEquals("", d.slots[23])
    }

    @Test
    fun nextOnTheLastSlotEndsTheEntryEvenWithAWrongWord() {
        val d = draft()
        d.edit(List(23) { "ocean" }.joinToString(" "))
        d.focus(23)
        d.edit("zzz") // no word and no candidate
        d.next()
        assertEquals("zzz", d.slots[23])
        assertTrue(d.committed)
        assertEquals(SeedStatus.InvalidWord(23), d.status)
    }

    @Test
    fun statusFollowsTheSlots() {
        val d = draft()
        assertEquals(SeedStatus.Incomplete, d.status)
        assertNull(d.flaggedSlot)

        fill(d, last = "diesel")
        assertEquals(SeedStatus.InvalidChecksum, d.status)
        assertEquals(23, d.flaggedSlot)
        assertFalse(d.canImport)

        d.focus(5)
        d.edit("nonsense")
        assertEquals(SeedStatus.InvalidWord(5), d.status)
        assertEquals(5, d.flaggedSlot)

        d.edit("ocean")
        d.focus(23)
        d.edit("art")
        assertEquals(SeedStatus.Valid, d.status)
        assertNull(d.flaggedSlot)
    }

    @Test
    fun aHalfTypedWordIsNotAnErrorWhileTheKeyboardIsUp() {
        val d = draft()
        d.edit(List(23) { "ocean" }.joinToString(" "))
        d.focus(23)
        d.edit("ar") // the start of "art", not a word yet
        d.typing = true
        assertEquals(SeedStatus.Incomplete, d.status)
        assertNull(d.flaggedSlot)

        d.typing = false // the keyboard went away with the word unfinished
        assertEquals(SeedStatus.InvalidWord(23), d.status)

        d.typing = true
        d.edit("qqq") // nothing starts with this: wrong already
        assertEquals(SeedStatus.InvalidWord(23), d.status)
    }

    @Test
    fun aTwentyFourthWordThatCannotBecomeAValidLastWordWarnsWhileTyped() {
        val d = draft()
        d.edit(List(23) { "ocean" }.joinToString(" "))
        d.focus(23)
        d.typing = true
        d.edit("a") // art starts with it: still a candidate
        assertEquals(SeedStatus.Incomplete, d.status)

        d.edit("ac") // the start of act, but no valid last word starts with it: board 1B.4.2
        assertEquals(SeedStatus.InvalidChecksum, d.status)
        assertEquals(23, d.flaggedSlot)

        d.edit("qqq") // not the start of any word
        assertEquals(SeedStatus.InvalidWord(23), d.status)

        d.edit("ac")
        d.typing = false // finished with a non-word: the word is the problem
        assertEquals(SeedStatus.InvalidWord(23), d.status)
    }

    @Test
    fun theKeyboardIsPutAwayWhenTheLastWordIsAccepted() {
        val d = draft()
        assertFalse(d.committed)
        fill(d)
        assertTrue(d.committed, "a paste that reaches slot 24 commits")

        d.edit("ar")
        assertFalse(d.committed, "typing again clears it")
        d.accept("art")
        assertTrue(d.committed)

        d.focus(3)
        assertFalse(d.committed)
        d.accept("ocean") // not the last slot
        assertFalse(d.committed)
    }

    @Test
    fun thePassphraseKeepsPrintableAsciiAndFlagsAnythingElse() {
        val d = draft()
        d.editPassphrase("Ab 1!~")
        assertEquals("Ab 1!~", d.passphrase)
        assertFalse(d.passphraseRejected)

        d.editPassphrase("passกุญแจ1")
        assertEquals("pass1", d.passphrase)
        assertTrue(d.passphraseRejected)

        d.editPassphrase("pass1")
        assertFalse(d.passphraseRejected)

        d.clearPassphrase()
        assertEquals("", d.passphrase)
    }
}
