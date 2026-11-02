package com.mebit.app

import androidx.compose.runtime.Composable

/**
 * The Android store until the Keystore one replaces it (design step 6). It holds nothing and never prompts, so the app keeps
 * the wallet in memory only, exactly as before, and the reinstall rule sees Unsupported and never wipes.
 * shortcut: no wallet survives a restart on Android, until the Keystore store ships.
 */
private object NotWired : SecureStore {
    override fun probe(): StoreProbe = StoreProbe.Unavailable(Why.NotWired)

    override suspend fun read(reason: String): ReadResult = ReadResult.Unavailable(Why.NotWired)

    override fun write(bytes: ByteArray, mode: WriteMode): WriteResult = WriteResult.Unavailable(Why.NotWired)

    override fun erase(): EraseResult = EraseResult.Erased

    override fun marker(): Marker = Marker.Unsupported

    override fun writeMarker(marker: Marker): Boolean = true
}

/** The Android store, which holds nothing yet. */
@Composable
internal actual fun rememberSecureStore(): SecureStore = NotWired
