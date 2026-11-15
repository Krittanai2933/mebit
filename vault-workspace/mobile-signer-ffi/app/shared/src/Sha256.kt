package com.mebit.app

/** The SHA-256 digest of [bytes], from the platform's own implementation. */
internal expect fun sha256(bytes: ByteArray): ByteArray
