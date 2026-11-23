package com.mebit.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** [text] as a plain-text entry for [androidx.compose.ui.platform.Clipboard.setClipEntry]. */
expect fun plainTextClip(text: String): ClipEntry

/** The plain text this clipboard entry holds, or null when it holds none. */
expect fun ClipEntry.plainText(): String?

/**
 * The copy buttons of one screen. [copied] is the text [copy] last put on the clipboard, from the moment [put] returns until 1.5 s later,
 * so a button that compares its own text with it says คัดลอกแล้ว only once its copy landed.
 */
class Copier(private val scope: CoroutineScope, private val put: suspend (String) -> Unit) {
    var copied by mutableStateOf<String?>(null)
        private set

    fun copy(text: String) {
        scope.launch {
            put(text)
            copied = text
            delay(1_500)
            // A later copy owns the label now, and its own timer clears it.
            if (copied == text) copied = null
        }
    }
}

/** A [Copier] that puts plain text on the system clipboard. */
@Composable
fun rememberCopier(): Copier {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return remember(clipboard, scope) { Copier(scope) { clipboard.setClipEntry(plainTextClip(it)) } }
}
