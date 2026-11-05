@file:OptIn(ExperimentalForeignApi::class)

package com.mebit.app

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataWithBytes
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * Written atomically, as the marker file is: a temporary file renamed into place. The folder, not the file, is what stays out of
 * backups, because a rename puts a new file in place that would not carry a mark set on the old one.
 */
internal actual fun plainFile(path: String): PlainFile = object : PlainFile {
    override fun read(): String? =
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) "" else NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null)

    override fun write(text: String): Boolean {
        val bytes = text.encodeToByteArray()
        val data = if (bytes.isEmpty()) NSData() else bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong()) }
        return data.writeToFile(path, atomically = true)
    }

    override fun delete() {
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }
}
