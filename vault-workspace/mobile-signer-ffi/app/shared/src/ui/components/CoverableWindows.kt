package com.mebit.app.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * Whether a block covers the app: the no-screen-lock block (WalletGate) or the App Lock (MebitNavHost). A popup, sheet or dialog is a
 * window of its own and would draw above either block, so every one opens through the wrappers below. CoverableWindowsRuleTest fails
 * on any other call.
 */
val LocalCovered = compositionLocalOf { false }

@Composable
private fun UnlessCovered(close: () -> Unit, window: @Composable () -> Unit) {
    if (LocalCovered.current) LaunchedEffect(Unit) { close() } else window()
}

@Composable
fun CoverablePopup(
    popupPositionProvider: PopupPositionProvider,
    onDismissRequest: () -> Unit,
    properties: PopupProperties = PopupProperties(),
    content: @Composable () -> Unit,
) = UnlessCovered(onDismissRequest) { Popup(popupPositionProvider, onDismissRequest, properties, content) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoverableSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    modifier: Modifier = Modifier,
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    content: @Composable ColumnScope.() -> Unit,
) = UnlessCovered(onDismissRequest) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = shape,
        containerColor = containerColor,
        scrimColor = scrimColor,
        dragHandle = dragHandle,
        content = content,
    )
}

/** [hideWhileCovered]: kept and shown again once the cover lifts, for a dialog only its own buttons may close. Otherwise closed. */
@Composable
fun CoverableDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    hideWhileCovered: Boolean = false,
    content: @Composable () -> Unit,
) = UnlessCovered(if (hideWhileCovered) ({}) else onDismissRequest) { Dialog(onDismissRequest, properties, content) }
