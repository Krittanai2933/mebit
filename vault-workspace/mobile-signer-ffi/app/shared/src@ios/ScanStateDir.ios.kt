@file:OptIn(ExperimentalForeignApi::class)

package com.mebit.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask

/**
 * Application Support/mebit/scan, marked to stay out of iCloud and computer backups, so a restored phone starts its scans
 * over from the chain. Null when the folder cannot be made or marked, and the core then keeps scans in memory only.
 */
@Composable
internal actual fun rememberScanStateDir(): String? = remember {
    val dir = (NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String) + "/mebit/scan"
    val made = NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
    dir.takeIf { made && NSURL.fileURLWithPath(dir).setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null) }
}
