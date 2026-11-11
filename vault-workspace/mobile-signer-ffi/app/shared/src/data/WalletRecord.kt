package com.mebit.app.data

import com.mebit.ffi.signer.AddressType
import com.mebit.ffi.signer.KeyNetwork

/**
 * The one record D1 keeps on the phone: key material and the three flags that drive routing. Nothing the chain can tell us is
 * in it (balance, address, history, wallet mode), and neither is the pay-twice guard, which [PendingPayments] keeps in a plain file.
 * Not a data class on purpose: no generated toString, copy or componentN can print the words.
 */
class WalletRecord(
    val words: List<String>,
    val passphrase: String,
    val network: KeyNetwork,
    /** Master fingerprint derived from [words] and [passphrase] at save time. A check value, not a cache: a load derives the key again and compares. */
    val fingerprint: String,
    val backupConfirmed: Boolean,
    val imported: Boolean,
    val appLockEnabled: Boolean,
    /** How the hot wallet's addresses look. A record saved before the choice existed reads as Native SegWit. */
    val addressType: AddressType = AddressType.NATIVE_SEGWIT,
    /** False while the user has not confirmed [addressType] on the Address Type screen. A record saved before this field reads as confirmed. */
    val addressTypeConfirmed: Boolean = true,
    /**
     * The fingerprints [words] gave this wallet earlier in its first setup, before a passphrase changed its key, so the next launch knows a
     * book still saved under one of them is this wallet's. A record saved before this field reads as none.
     */
    val earlierFingerprints: List<String> = emptyList(),
) {
    init {
        require(words.size in BIP39_SIZES) { "word count" } // no record without a key, and the message names no word
    }

    override fun toString() = "WalletRecord(redacted)"

    companion object {
        /** The word counts BIP-39 defines. */
        val BIP39_SIZES = setOf(12, 15, 18, 21, 24)
    }
}
