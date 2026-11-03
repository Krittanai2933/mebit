package com.mebit.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The copy counter that every store, and the fake, share the Replace rule through. Pure. */
class CopyCounterTest {
    @Test
    fun aCreateIsNeverRefusedWhateverTheCounterSays() {
        val copies = CopyCounter()
        repeat(4) {
            assertNull(copies.refuse(WriteMode.Create), "after $it advances")
            copies.advance()
        }
    }

    @Test
    fun aReplaceOfTheCurrentCopyGoesThroughAndOfAnyEarlierOneIsAConflict() {
        val copies = CopyCounter()
        val earlier = mutableListOf<StoreVersion>()
        repeat(4) {
            assertNull(copies.refuse(WriteMode.Replace(copies.current)), "the current copy after $it advances")
            earlier.forEach { v -> assertEquals(WriteResult.Conflict, copies.refuse(WriteMode.Replace(v)), "an earlier copy after $it advances") }
            earlier += copies.current
            copies.advance()
        }
    }

    @Test
    fun advanceReturnsTheNewCurrentAndNoEarlierVersionEverEqualsIt() {
        val copies = CopyCounter()
        val earlier = mutableListOf(copies.current)
        repeat(10) {
            val next = copies.advance()
            assertEquals(next, copies.current, "advance $it")
            assertTrue(earlier.none { it == next }, "an earlier version came back at advance $it")
            earlier += next
        }
    }
}
