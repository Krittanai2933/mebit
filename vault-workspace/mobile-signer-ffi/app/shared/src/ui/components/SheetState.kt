package com.mebit.app.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable

/** A modal sheet's state that opens straight to full height and never stops half open. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberFullSheetState(): SheetState =
    rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
