package com.mebit.app.data

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** What the 24 slots add up to. */
sealed interface SeedStatus {
    data object Incomplete : SeedStatus
    data object Valid : SeedStatus
    data class InvalidWord(val index: Int) : SeedStatus
    data object InvalidChecksum : SeedStatus
}

/**
 * The Import Seed screen's state: 24 word slots, the one being typed, its dropdown, and an
 * optional English-only passphrase. The BIP-39 knowledge (word list, checksum) is passed in,
 * so this class runs in a host-JVM test without the native library. Nothing here is logged.
 */
class ImportSeedDraft(
    private val suggest: (String) -> List<String>,
    private val lastWords: (List<String>) -> List<String>,
    private val check: (List<String>) -> SeedStatus,
) {
    val slots = mutableStateListOf<String>().apply { repeat(SLOTS) { add("") } }

    var active by mutableIntStateOf(0)
        private set

    var passphrase by mutableStateOf("")
        private set

    /** True after the last passphrase edit dropped a character that is not printable ASCII. */
    var passphraseRejected by mutableStateOf(false)
        private set

    /** The screen sets this while a slot has the keyboard: a half-typed word is not an error yet. */
    var typing by mutableStateOf(false)

    /** True once the 24th word was accepted: the screen puts the keyboard away so the result shows. */
    var committed by mutableStateOf(false)
        private set

    // Set when a slot that already holds a word is entered: the first keystroke replaces that word,
    // wherever the caret landed. Words are short and picked from a list, so this beats editing letters.
    private var replaceNext = false

    /** The suggestions under the active slot: matches while typing, the valid 24th words on the last slot. */
    val dropdown: List<String> by derivedStateOf {
        val text = slots[active]
        if (active == LAST) {
            val first = slots.take(LAST)
            if (first.any { it.isEmpty() }) emptyList() else lastWords(first).filter { it.startsWith(text) }
        } else if (text.isEmpty()) {
            emptyList()
        } else {
            val matches = suggest(text)
            // A word that is already complete and has no longer sibling needs no list.
            if (matches == listOf(text)) emptyList() else matches
        }
    }

    val status: SeedStatus by derivedStateOf {
        when {
            slots.any { it.isEmpty() } -> SeedStatus.Incomplete
            // The 24th word is the exception: once no valid last word starts with what is typed, it is wrong already.
            typing && isHalfTyped(slots[active]) && !(active == LAST && dropdown.isEmpty()) -> SeedStatus.Incomplete
            else -> check(slots.toList()).let { verdict ->
                // Board 1B.4.2: the start of a word that cannot finish a valid phrase is the checksum's verdict.
                if (typing && verdict == SeedStatus.InvalidWord(LAST) && isHalfTyped(slots[LAST])) SeedStatus.InvalidChecksum else verdict
            }
        }
    }

    /** The slot to paint as wrong: the unknown word, or the 24th on a checksum failure. */
    val flaggedSlot: Int? by derivedStateOf {
        when (val s = status) {
            is SeedStatus.InvalidWord -> s.index
            SeedStatus.InvalidChecksum -> LAST
            else -> null
        }
    }

    val canImport: Boolean get() = status == SeedStatus.Valid

    fun words(): List<String> = slots.toList()

    fun focus(index: Int) {
        committed = false
        active = index.coerceIn(0, LAST)
        replaceNext = slots[active].isNotEmpty()
    }

    /** The input gained the keyboard (a tap on it, after it had been put away). */
    fun onFieldFocused() {
        replaceNext = slots[active].isNotEmpty()
    }

    /**
     * Text typed or pasted into slot [index], which must still be the active one: a field that has
     * just lost focus can deliver one more keystroke, and that must not land in the next slot.
     * A single word is kept as typed. Several words, or a word followed by a space, fill the
     * slots from the active one on.
     */
    fun edit(raw: String, index: Int = active) {
        if (index != active) return
        committed = false
        var text = raw
        if (replaceNext) {
            replaceNext = false
            text = typedOverWord(slots[active], raw)
        }
        val words = text.lowercase().split(WHITESPACE).filter { it.isNotEmpty() }
        val endsWithSpace = text.isNotEmpty() && text.last().isWhitespace()
        if (words.isEmpty()) {
            slots[active] = ""
        } else if (words.size == 1 && !endsWithSpace) {
            slots[active] = words[0]
        } else {
            var i = active
            for (word in words) {
                if (i > LAST) break
                slots[i++] = word
            }
            active = minOf(i, LAST)
            committed = i > LAST
        }
    }

    /** A tapped dropdown row. */
    fun accept(word: String) {
        slots[active] = word
        if (active < LAST) active += 1 else committed = true
        replaceNext = !committed && slots[active].isNotEmpty()
    }

    /**
     * The keyboard's Next. It takes the typed word if it is complete, else the highlighted first
     * suggestion. An empty slot is never filled for the user, so a wrong 24th word is never picked
     * silently. On a slot before the last, text that is no word stays put. On the last slot Next
     * ends the entry with whatever was typed, so the verdict (a warning or the button) shows.
     */
    fun next() {
        val text = slots[active]
        if (text.isEmpty()) return
        val options = dropdown.ifEmpty { if (active == LAST) emptyList() else suggest(text) }
        val pick = options.firstOrNull { it == text } ?: options.firstOrNull()
        accept(pick ?: if (active == LAST) text else return)
    }

    /** Keeps printable ASCII only: the passphrase field is English-only. */
    fun editPassphrase(raw: String) {
        val kept = raw.filter { it in ' '..'~' }
        passphraseRejected = kept.length != raw.length
        passphrase = kept
    }

    fun clearPassphrase() {
        passphrase = ""
        passphraseRejected = false
    }

    // The field shows `old` with something typed into it. If `old` is still all there, what was
    // typed is the new word. If the field already replaced or shortened it, take the field's text.
    private fun typedOverWord(old: String, raw: String): String {
        val prefix = old.commonPrefixWith(raw).length
        val suffix = old.drop(prefix).commonSuffixWith(raw.drop(prefix)).length
        return if (prefix + suffix >= old.length && raw.length > old.length) raw.substring(prefix, raw.length - suffix) else raw
    }

    // Some word starts with this text, but it is not a whole word yet.
    private fun isHalfTyped(text: String): Boolean {
        val matches = suggest(text)
        return matches.isNotEmpty() && text !in matches
    }

    private companion object {
        const val SLOTS = 24
        const val LAST = SLOTS - 1
        val WHITESPACE = Regex("\\s+")
    }
}
