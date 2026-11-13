package com.mebit.app.data

import com.mebit.app.Pairings
import com.mebit.ffi.signer.BleException
import com.mebit.ffi.signer.FoundWallet
import com.mebit.ffi.signer.HwAccountKey
import com.mebit.ffi.signer.PairingCodes
import com.mebit.ffi.signer.WalletKind
import com.mebit.ffi.signer.WalletLink
import com.mebit.ffi.signer.WalletLinkInterface
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Drops the Safe 7 credential kept for [fingerprint] once the saved book has no key and no hardware wallet with that fingerprint. */
internal fun SavedVaults.forgetUnusedPairing(pairings: Pairings?, fingerprint: String) {
    if (pairings != null && !current.usesFingerprint(fingerprint)) pairings.delete(fingerprint)
}

/** A link [HardwareLink] can drive: [WalletLink]'s calls, then close. */
internal interface DeviceLink : WalletLinkInterface, AutoCloseable

/** The Bluetooth link to [found]. */
internal fun bleLink(found: FoundWallet): DeviceLink = BleLink(WalletLink(found))

private class BleLink(private val link: WalletLink) : DeviceLink, WalletLinkInterface by link {
    override fun close() = link.close()
}

/**
 * The one way to reach a Jade or a Safe 7. [use] connects, offering the Safe 7 credential kept for the expected key, unlocks (a Jade asks
 * its PIN on the device and needs the internet), reads the account key at a path, refuses a device whose key is not the expected one
 * before anything else shows on it, keeps a new credential under the expected key, runs the job, and always disconnects and closes.
 * A credential is kept only for a key the book holds, so a new key's goes to the job, to keep once Confirm Key saves the key. [open]
 * makes the link, so tests need no Bluetooth.
 */
internal class HardwareLink(private val pairings: Pairings?, private val open: (FoundWallet) -> DeviceLink = ::bleLink) {
    /** [pairing] is a new Safe 7 credential when no key was expected, which the job may copy and [use] zeroes once the job returns. */
    class Opened(val link: WalletLinkInterface, val key: HwAccountKey, val pairing: ByteArray? = null)

    /** Whether a credential is kept for [fingerprint], so a code the Safe 7 then asks for means it forgot this phone. */
    fun keeps(fingerprint: String): Boolean = pairings?.read(fingerprint)?.also { it.fill(0) } != null

    /**
     * Runs [job] on [found], reached as above, with the key it holds at [path]. Throws what the link throws, and [BleException.WrongKey]
     * when [expect] is set and the device holds another key. A pairing that fails or is cancelled never drops a kept credential.
     */
    suspend fun <T> use(found: FoundWallet, path: String, expect: String?, codes: PairingCodes, job: suspend (Opened) -> T): T {
        val link = open(found)
        try {
            val kept = if (found.kind == WalletKind.TREZOR_SAFE7) expect?.let { pairings?.read(it) } else null
            val fresh = try {
                link.connect(codes, kept)
            } finally {
                kept?.fill(0)
            }
            try {
                link.unlock()
                val key = link.accountXpub(path)
                if (expect != null && !key.masterFingerprint.equals(expect, ignoreCase = true)) {
                    throw BleException.WrongKey("this device holds the key ${key.masterFingerprint}, not $expect")
                }
                if (expect != null && fresh != null) {
                    pairings?.write(key.masterFingerprint, fresh)
                    fresh.fill(0)
                }
                return job(Opened(link, key, fresh.takeIf { expect == null }))
            } finally {
                fresh?.fill(0)
            }
        } finally {
            withContext(NonCancellable) { runCatching { link.disconnect() } }
            link.close()
        }
    }
}
