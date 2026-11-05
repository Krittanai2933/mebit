package com.mebit.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File

/** `noBackupFilesDir/scan`, which Auto Backup and device transfer leave out. The core makes the folder at its first write. */
@Composable
internal actual fun rememberScanStateDir(): String? {
    val context = LocalContext.current
    return remember(context) { File(context.noBackupFilesDir, "scan").path }
}
