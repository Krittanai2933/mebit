@file:OptIn(ExperimentalForeignApi::class)

package com.mebit.app

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH

internal actual fun sha256(bytes: ByteArray): ByteArray {
    val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
    // addressOf needs an element to point at, and CC_SHA256 reads nothing when the length is 0.
    (if (bytes.isEmpty()) ByteArray(1) else bytes).usePinned { input ->
        digest.usePinned { output -> CC_SHA256(input.addressOf(0), bytes.size.toUInt(), output.addressOf(0).reinterpret()) }
    }
    return digest
}
