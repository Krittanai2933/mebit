package com.mebit.app

import androidx.compose.runtime.Composable
import com.mebit.ffi.signer.KeyNetwork

/**
 * The Safe 7 pairing credentials, one per master fingerprint of a key a Safe 7 holds. Each is a secret, because the phone's half of the
 * pairing is inside. Never prompts, never throws and never logs bytes. A ByteArray it returns belongs to the caller, which zeroes it.
 */
internal interface Pairings {
    /** The credential kept for [fingerprint], or null when none is kept or it cannot be read now. */
    fun read(fingerprint: String): ByteArray?

    /** Keeps [credential] for [fingerprint] in place of an earlier one. True once it is kept. */
    fun write(fingerprint: String, credential: ByteArray): Boolean

    /** True once none is kept for [fingerprint]. */
    fun delete(fingerprint: String): Boolean

    /** True once none is kept at all. */
    fun deleteAll(): Boolean
}

/** The name a credential is kept under, `safe7.<fingerprint>`, or null for anything but 8 hex, so no outside text ever names a file. */
internal fun pairingName(fingerprint: String): String? = fingerprint.lowercase().takeIf { Regex("[0-9a-f]{8}").matches(it) }?.let { "safe7.$it" }

/** This platform's store for [network]'s Safe 7 pairing credentials, apart from the other network's. */
@Composable
internal expect fun rememberPairings(network: KeyNetwork): Pairings
