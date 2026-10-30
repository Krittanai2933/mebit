package com.mebit.app.data

import kotlin.random.Random

/** One backup check: which word sits at [position] (0-based), picked from [options]. */
data class VerifyQuestion(val position: Int, val options: List<String>, val answer: String)

/** Three distinct positions in order, each with the right word and up to two other words from the phrase. */
fun verifyQuestions(words: List<String>, random: Random = Random): List<VerifyQuestion> {
    if (words.size < 3) return emptyList()
    return words.indices.shuffled(random).take(3).sorted().map { position ->
        val answer = words[position]
        val others = words.filter { it != answer }.distinct().shuffled(random).take(2)
        VerifyQuestion(position, (others + answer).shuffled(random), answer)
    }
}
