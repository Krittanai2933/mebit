package com.mebit.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent

/** The capture protection a seed screen may claim, which is only what its platform really does. */
enum class CaptureClaim { BlocksScreenshots, HidesWhileRecording, None }

/** A seed screen's protection now: the [claim] it may show, whether the words are [hidden], and whether the user took a screenshot. */
class CaptureGuard(val claim: CaptureClaim, val hidden: Boolean, val screenshotTaken: Boolean)

/** Protects the screen that calls it until it leaves the composition. */
@Composable
expect fun rememberCaptureGuard(): CaptureGuard

fun Modifier.hideWhen(guard: CaptureGuard): Modifier = if (guard.hidden) drawWithContent {} else this

/** Counts the holders of one protection. [acquire] is true for the first, [release] for the last, and a release with none held does nothing. */
class HolderCount {
    private var holders = 0

    fun acquire(): Boolean = ++holders == 1

    fun release(): Boolean = holders > 0 && --holders == 0
}
