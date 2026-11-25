package com.mebit.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.mebit.ffi.signer.KeyNetwork
import java.io.File

/** `noBackupFilesDir/scan` (testnet) or `noBackupFilesDir/main/scan`, which Auto Backup and device transfer leave out. The core or the guard file makes the folder at its first write. */
@Composable
internal actual fun rememberScanStateDir(network: KeyNetwork): String? {
    val context = LocalContext.current
    return remember(context, network) { File(context.noBackupFilesDir, scanFolder(network)).path }
}
