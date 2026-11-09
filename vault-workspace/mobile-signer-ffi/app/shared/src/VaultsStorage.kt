package com.mebit.app

import androidx.compose.runtime.Composable

/** One small text saved by the OS's secret store and replaced whole. Never throws. */
internal interface VaultsStorage {
    /** The text, "" when nothing is saved, null when it could not be read this moment, which proves nothing about what is saved. Never shows a sheet. */
    fun read(): String?

    /** Replaces the text whole, so a reader finds the old text or the new. True when the new text is saved. Never prompts. */
    fun write(text: String): Boolean

    /** True when nothing is left saved. Never prompts. */
    fun delete(): Boolean
}

/** This platform's store for the saved vaults, or null where it has none, which leaves them in memory only. */
@Composable
internal expect fun rememberVaultsStorage(): VaultsStorage?
