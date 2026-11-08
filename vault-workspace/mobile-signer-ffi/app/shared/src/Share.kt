package com.mebit.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection

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

/** Pixels of page colour around a card in a shared picture, so the card's rounded corners show the page and not transparency. */
internal const val SHARE_MARGIN = 32

/** The shared picture's size in pixels for a card of size [card]. */
internal fun shareFrameSize(card: IntSize) = IntSize(card.width + 2 * SHARE_MARGIN, card.height + 2 * SHARE_MARGIN)

/** Fills the scope with [page] and draws [card] [SHARE_MARGIN] pixels in from the top left. */
internal fun DrawScope.drawShareFrame(page: Color, card: DrawScope.() -> Unit) {
    drawRect(page)
    translate(SHARE_MARGIN.toFloat(), SHARE_MARGIN.toFloat(), card)
}

/** Shares the card recorded in [card] as a PNG on [page], drawn through [frame]. Throws what [encodePng] and [image] throw. */
internal suspend fun Sharer.imageOfCard(card: GraphicsLayer, frame: GraphicsLayer, page: Color, name: String) {
    frame.record(density = Density(1f), layoutDirection = LayoutDirection.Ltr, size = shareFrameSize(card.size)) {
        drawShareFrame(page) { drawLayer(card) }
    }
    image(frame.toImageBitmap().encodePng(), name)
}
