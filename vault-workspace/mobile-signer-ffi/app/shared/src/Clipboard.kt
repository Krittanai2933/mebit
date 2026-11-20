package com.mebit.app

import androidx.compose.ui.platform.ClipEntry

/** [text] as a plain-text entry for [androidx.compose.ui.platform.Clipboard.setClipEntry]. */
expect fun plainTextClip(text: String): ClipEntry

/** The plain text this clipboard entry holds, or null when it holds none. */
expect fun ClipEntry.plainText(): String?
