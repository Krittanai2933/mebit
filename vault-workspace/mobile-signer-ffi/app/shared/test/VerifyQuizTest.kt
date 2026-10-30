package com.mebit.app.data

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VerifyQuizTest {
    private val words = List(24) { "w$it" }

    @Test
    fun askForThreeDistinctPositionsInOrder() {
        repeat(50) { seed ->
            val positions = verifyQuestions(words, Random(seed)).map { it.position }
            assertEquals(3, positions.size)
            assertEquals(positions.distinct().sorted(), positions)
        }
    }

    @Test
    fun everyQuestionOffersItsAnswerAmongThreeDistinctWords() {
        repeat(50) { seed ->
            verifyQuestions(words, Random(seed)).forEach { q ->
                assertEquals(words[q.position], q.answer)
                assertEquals(3, q.options.size)
                assertEquals(3, q.options.distinct().size)
                assertTrue(q.answer in q.options)
            }
        }
    }

    @Test
    fun aRepeatedWordIsNeverOfferedTwice() {
        val repeated = List(24) { if (it % 2 == 0) "same" else "w$it" }
        repeat(50) { seed ->
            verifyQuestions(repeated, Random(seed)).forEach { assertEquals(it.options.distinct(), it.options) }
        }
    }

    @Test
    fun tooFewWordsAskNothing() {
        assertEquals(emptyList(), verifyQuestions(listOf("a", "b")))
    }
}
