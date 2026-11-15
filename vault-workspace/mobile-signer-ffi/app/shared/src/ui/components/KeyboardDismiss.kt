package com.mebit.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mebit.app.ui.theme.MbChip
import com.mebit.app.ui.theme.MbNavy

private class FieldTouch {
    var onField = false
}

private val LocalFieldTouch = staticCompositionLocalOf<FieldTouch?> { null }

/**
 * A touch that starts outside a [keepsKeyboard] field and ends as a tap or turns into a drag clears the focus and closes the
 * keyboard. It only watches, so a button or a fee card under the finger still gets the tap. While the keyboard is up a เสร็จ
 * chip sits on it, because the iOS decimal pad has no return key.
 */
@Composable
fun KeyboardDismissBox(content: @Composable () -> Unit) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val touch = remember { FieldTouch() }
    val dismiss = {
        keyboard?.hide() // on iOS clearing the focus alone leaves the keyboard up
        focus.clearFocus(force = true)
    }
    val keyboardUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Box(
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                // The parent sees the down first and the field marks it after, so the mark is read on a later event.
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                touch.onField = false
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    val dragged = (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                    if (!change.pressed || dragged) {
                        if (!touch.onField) dismiss()
                        break
                    }
                }
            }
        },
    ) {
        CompositionLocalProvider(LocalFieldTouch provides touch, content = content)
        if (keyboardUp) {
            Text(
                text = "เสร็จ",
                color = MbNavy,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .imePadding()
                    .padding(end = 12.dp, bottom = 8.dp)
                    .clip(CircleShape)
                    .background(MbChip)
                    .clickable(role = Role.Button, onClick = dismiss)
                    .padding(horizontal = 16.dp, vertical = 7.dp)
                    .testTag("cta_keyboard_done"),
            )
        }
    }
}

/** Marks a text field inside a [KeyboardDismissBox], so a touch on it keeps the keyboard up. */
fun Modifier.keepsKeyboard(): Modifier = composed {
    val touch = LocalFieldTouch.current
    if (touch == null) this else pointerInput(touch) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            touch.onField = true
        }
    }
}
