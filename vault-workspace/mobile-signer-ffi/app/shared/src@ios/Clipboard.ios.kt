package com.mebit.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry

@OptIn(ExperimentalComposeUiApi::class)
actual fun plainTextClip(text: String): ClipEntry = ClipEntry.withPlainText(text)

@OptIn(ExperimentalComposeUiApi::class)
actual fun ClipEntry.plainText(): String? = getPlainText()
