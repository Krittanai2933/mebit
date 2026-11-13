package com.mebit.app.data

import com.mebit.ffi.signer.AddressType
import com.mebit.ffi.signer.BleException
import com.mebit.ffi.signer.FoundWallet
import com.mebit.ffi.signer.HwAccountKey
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.PairingCodes
import com.mebit.ffi.signer.SpendReview
import com.mebit.ffi.signer.WalletInfo
import com.mebit.ffi.signer.WalletKind
import com.mebit.ffi.signer.restoreSoftwareMultisigKey
import com.mebit.ffi.signer.walletSignWithWords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * The documented public BIP-39 test phrase for all-zero entropy, which the fake device holds, as the user's own test Jade and Safe 7 do.
 * Constants only: nothing prints or logs them, and a failing assertion names its case instead of these words.
 */
internal val PUBLIC_WORDS_12: List<String> = List(11) { "abandon" } + "about"

/** The master fingerprint of [PUBLIC_WORDS_12] with no passphrase. */
internal const val PUBLIC_FINGERPRINT = "73c5da0a"

/** One account of [PUBLIC_WORDS_12] as a hardware wallet reads it: the tpub at the type's testnet account path, and receive address 0. */
internal class PublicAccount(val tpub: String, val receive0: String)

/**
 * [PUBLIC_WORDS_12]'s accounts at m/44', 49', 84' and 86' /1'/0', derived outside this app with rust-bitcoin 0.32 and bip39 2.2. The 84'
 * and 86' ones are the published BIP-84 and BIP-86 test vectors' testnet form.
 */
internal val PUBLIC_ACCOUNTS: Map<AddressType, PublicAccount> = mapOf(
    AddressType.LEGACY to PublicAccount(
        "tpubDC5FSnBiZDMmhiuCmWAYsLwgLYrrT9rAqvTySfuCCrgsWz8wxMXUS9Tb9iVMvcRbvFcAHGkMD5Kx8koh4GquNGNTfohfk7pgjhaPCdXpoba",
        "mkpZhYtJu2r87Js3pDiWJDmPte2NRZ8bJV",
    ),
    AddressType.NESTED_SEGWIT to PublicAccount(
        "tpubDD7tXK8KeQ3YY83yWq755fHY2JW8Ha8Q765tknUM5rSvjPcGWfUppDFMpQ1ScziKfW3ZNtZvAD7M3u7bSs7HofjTD3KP3YxPK7X6hwV8Rk2",
        "2Mww8dCYPUpKHofjgcXcBCEGmniw9CoaiD2",
    ),
    AddressType.NATIVE_SEGWIT to PublicAccount(
        "tpubDC8msFGeGuwnKG9Upg7DM2b4DaRqg3CUZa5g8v2SRQ6K4NSkxUgd7HsL2XVWbVm39yBA4LAxysQAm397zwQSQoQgewGiYZqrA9DsP4zbQ1M",
        "tb1q6rz28mcfaxtmd6v789l9rrlrusdprr9pqcpvkl",
    ),
    AddressType.TAPROOT to PublicAccount(
        "tpubDDfvzhdVV4unsoKt5aE6dcsNsfeWbTgmLZPi8LQDYU2xixrYemMfWJ3BaVneH3u7DBQePdTwhpybaKRU95pi6PMUtLPBJLVQRpzEnjfjZzX",
        "tb1p8wpt9v4frpf3tkn0srd97pksgsxc5hs52lafxwru9kgeephvs7rqlqt9zj",
    ),
)

/**
 * Whether this build holds the fake device at all. A release APK's R8 takes it as false (androidApp/proguard-rules.pro), so the fake and
 * its phrase are left out of it.
 */
internal fun fakeDeviceAllowed(): Boolean = true

/**
 * A stand-in for a Jade and a Safe 7 on a simulator or an emulator, which have no Bluetooth: one device of each kind in range, each holding
 * [PUBLIC_WORDS_12]. It gives the account key at every path the app reads, signs a wallet's payment with that key through
 * wallet_sign_with_words, and pairs as a Safe 7 does: it asks for its code (any six digits) until it holds this phone's pairing, then
 * connects with the pairing kept for it. It holds the pairing in memory, so after a relaunch it has forgot this phone (2.3.6). Only a
 * debug build launched with the switch builds one ([com.mebit.app.rememberFakeDeviceSwitch]); it goes behind the link factory and the scan
 * that Bluetooth uses.
 */
internal class FakeDevice {
    private var paired: ByteArray? = null

    suspend fun scan(kind: WalletKind): List<FoundWallet> = listOf(FoundWallet(kind, "fake-${kind.name.lowercase()}", if (kind == WalletKind.JADE) "Fake Jade" else "Fake Safe 7"))

    fun open(found: FoundWallet): DeviceLink = Link(found.kind)

    private inner class Link(private val kind: WalletKind) : DeviceLink {
        override suspend fun connect(codes: PairingCodes, pairing: ByteArray?): ByteArray? {
            if (kind == WalletKind.JADE) return null
            val known = paired
            if (pairing != null && known != null && pairing.contentEquals(known)) return null
            codes.pairingCode() ?: throw BleException.PairingCancelled("no code was typed")
            return Random.nextBytes(32).also { paired = it.copyOf() }
        }

        override suspend fun unlock() {}

        override suspend fun accountXpub(derivationPath: String): HwAccountKey {
            val xpub = PUBLIC_ACCOUNTS.entries.firstOrNull { oneKeyPath(it.key) == derivationPath }?.value?.tpub
                ?: if (derivationPath == TESTNET_MULTISIG_ACCOUNT) {
                    withContext(Dispatchers.Default) { restoreSoftwareMultisigKey(PUBLIC_WORDS_12, "", KeyNetwork.TEST, 0u).xpub }
                } else {
                    throw BleException.Refused("the fake device reads no key at $derivationPath")
                }
            return HwAccountKey(xpub, PUBLIC_FINGERPRINT, derivationPath)
        }

        override suspend fun signWalletPsbt(descriptor: String, psbtBase64: String, unsignedTxid: String): SpendReview {
            // As long as a person takes to read the payment on a device, so a check sees 4.5.3 before 4.5.5.
            delay(3_000)
            return withContext(Dispatchers.Default) { walletSignWithWords(descriptor, KeyNetwork.TEST, psbtBase64, unsignedTxid, PUBLIC_WORDS_12, "") }
        }

        override suspend fun signPsbt(psbtBase64: String, signerFingerprint: String): String = throw BleException.Refused("not a call of the fake device")

        override suspend fun info(): WalletInfo = throw BleException.Refused("not a call of the fake device")

        override suspend fun disconnect() {}

        override fun close() {}
    }
}
