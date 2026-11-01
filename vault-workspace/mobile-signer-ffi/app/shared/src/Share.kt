package com.mebit.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

/** Hands text or a PNG image to the system share sheet. Get one from [rememberSharer]. */
interface Sharer {
    /** Opens the share sheet with [text] as plain text. */
    fun text(text: String)

    /**
     * Opens the share sheet with [png], the bytes of a PNG such as [encodePng] returns.
     * Android shares it as the file `safeShareName(name).png`. iOS shares it as an image, so that Save Image can put
     * it in Photos, and the app that receives it picks its own name.
     */
    fun image(png: ByteArray, name: String)
}

/** The system share sheet. Composable because Android reaches a Context only through [androidx.compose.ui.platform.LocalContext]. */
@Composable
expect fun rememberSharer(): Sharer

/** This bitmap as PNG bytes. Throws [IllegalStateException] when the platform encoder fails. */
expect fun ImageBitmap.encodePng(): ByteArray

private val unsafeNameChar = Regex("[^A-Za-z0-9._-]")

/**
 * [name] as a safe file name: each character outside `A-Z a-z 0-9 . _ -` becomes `_`, a leading dot becomes `_` so the
 * file is never hidden and never a `.` or `..` path piece, and the result keeps at most 60 characters.
 * Only an empty [name] gives `mebit-share`. A name made only of other characters stays as underscores.
 */
internal fun safeShareName(name: String): String =
    name.take(60)
        .replace(unsafeNameChar, "_")
        .replaceFirstChar { if (it == '.') '_' else it }
        .ifEmpty { "mebit-share" }
