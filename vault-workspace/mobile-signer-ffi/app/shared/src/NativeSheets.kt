package com.mebit.app

import androidx.compose.runtime.Composable

/**
 * Closes the system sheets the app opened, the share sheet and the file and photo pickers, once a block covers the app
 * ([com.mebit.app.ui.components.LocalCovered]). The system draws them above the app's own screen, so the block cannot hide them as it
 * hides a Compose window. Composed once in MebitNavHost, where both blocks reach it. CoverableWindowsRuleTest allows a sheet to open
 * only through each platform's NativeSheets file. Android's file and photo pickers stay open: their activity-result launchers keep the
 * request code that would end them private.
 */
@Composable
expect fun CloseNativeSheetsWhileCovered()

/**
 * The one system sheet that is open, and its single answer: the user's, from the sheet's own callback, or a cancel when a block covers
 * the app first. Platform free, so the iOS sheets keep their answer-once rule under a JVM test.
 */
internal class OpenSheet<T : Any> {
    private var sheet: T? = null
    private var onCovered: () -> Unit = {}

    /** [sheet] is now on screen. [onCovered] tells its caller the user cancelled, and runs only if a block covers the app before it answers. */
    fun opened(sheet: T, onCovered: () -> Unit) {
        this.sheet = sheet
        this.onCovered = onCovered
    }

    /** Call first in each of [sheet]'s own callbacks. True once, when its answer is the one the caller hears; false after a cover closed it. */
    fun answered(sheet: T?): Boolean {
        if (sheet == null || sheet !== this.sheet) return false
        forget()
        return true
    }

    /** A block covers the app: [close] takes the open sheet off the screen, if one is open, and then its caller hears the cancel. */
    fun cover(close: (T) -> Unit) {
        val open = sheet ?: return
        val cancel = onCovered
        forget()
        close(open)
        cancel()
    }

    private fun forget() {
        sheet = null
        onCovered = {}
    }
}
