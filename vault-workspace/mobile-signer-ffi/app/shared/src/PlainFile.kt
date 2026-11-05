package com.mebit.app

/** One small file in app storage that is only ever replaced whole. Never throws. */
internal interface PlainFile {
    /** What the file holds: empty when there is no file, null when there is one that could not be read. */
    fun read(): String?

    /** Replaces the file whole, so a reader finds the old text or the new, never half. True when the new text is on the phone. */
    fun write(text: String): Boolean

    /** Best effort. */
    fun delete()
}

/** The file at [path], in the folder [rememberScanStateDir] gives. */
internal expect fun plainFile(path: String): PlainFile
