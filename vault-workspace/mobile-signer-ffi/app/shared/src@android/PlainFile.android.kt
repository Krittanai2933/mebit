package com.mebit.app

import android.util.AtomicFile
import java.io.File
import java.io.IOException

/** Through [AtomicFile], as the Keystore store writes its wallet file, so a write that dies midway leaves the old text. */
internal actual fun plainFile(path: String): PlainFile = object : PlainFile {
    private val file = AtomicFile(File(path))

    override fun read(): String? {
        // AtomicFile restores its backup when a write died midway, so a backup alone is a file too.
        if (!file.baseFile.exists() && !File("$path.bak").exists()) return ""
        return try {
            file.readFully().decodeToString()
        } catch (e: IOException) {
            null
        }
    }

    override fun write(text: String): Boolean {
        file.baseFile.parentFile?.mkdirs() // the core makes the folder only at its first scan file
        val out = try {
            file.startWrite()
        } catch (e: IOException) {
            return false
        }
        try {
            out.write(text.encodeToByteArray())
        } catch (e: IOException) {
            file.failWrite(out)
            return false
        }
        file.finishWrite(out)
        // finishWrite logs a failed sync, close or rename instead of throwing, so only a read-back proves the text is there.
        return read() == text
    }

    override fun delete(): Boolean {
        file.delete()
        // AtomicFile.delete reports nothing, so a read, which counts a backup left behind as the file, says what is left.
        return read() == ""
    }
}
