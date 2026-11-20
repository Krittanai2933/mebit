package com.mebit.app

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry

actual fun plainTextClip(text: String): ClipEntry = ClipEntry(ClipData.newPlainText("plain text", text))

actual fun ClipEntry.plainText(): String? = clipData.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
