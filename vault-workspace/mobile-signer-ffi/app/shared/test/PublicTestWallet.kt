package com.mebit.app.data

/**
 * The documented public BIP-39 test phrase for all-zero entropy, so no test holds a real wallet. Constants only: nothing
 * prints or logs them, and a failing assertion names its case instead of these words.
 */
internal val PUBLIC_WORDS_12: List<String> = List(11) { "abandon" } + "about"

/** The 24-word form of the same public test phrase. */
internal val PUBLIC_WORDS_24: List<String> = List(23) { "abandon" } + "art"
