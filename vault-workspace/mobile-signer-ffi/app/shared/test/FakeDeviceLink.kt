package com.mebit.app.data

import com.mebit.ffi.signer.BleException
import com.mebit.ffi.signer.HwAccountKey
import com.mebit.ffi.signer.PairingCodes
import com.mebit.ffi.signer.SignSteps
import com.mebit.ffi.signer.SpendReview
import com.mebit.ffi.signer.WalletInfo

/**
 * A device behind a [DeviceLink] that holds the key [fingerprint]. [calls] lists what reached it, in order. [fresh] is what a connect
 * returns, a code pairing's new credential. With [asksCode] a connect asks [PairingCodes] first. [failAt] names the step that fails
 * as a lost link: "connect", "unlock", "accountXpub" or "sign". [sign] answers a signWalletPsbt, once the steps heard showing.
 */
internal class FakeDeviceLink(
    var fingerprint: String = PUBLIC_FINGERPRINT,
    var fresh: ByteArray? = null,
    var asksCode: Boolean = false,
    var failAt: String? = null,
    var sign: suspend (descriptor: String, psbt: String, unsignedTxid: String) -> SpendReview = { _, _, _ -> error("no signature scripted") },
) : DeviceLink {
    val calls = mutableListOf<String>()

    /** The array connect was offered, to check the caller zeroed it, and a copy taken as it arrived. */
    var offered: ByteArray? = null
    var offeredAtConnect: ByteArray? = null
    var code: String? = null

    override suspend fun connect(codes: PairingCodes, pairing: ByteArray?): ByteArray? {
        calls += "connect"
        offered = pairing
        offeredAtConnect = pairing?.copyOf()
        if (asksCode) code = codes.pairingCode()
        failIf("connect")
        return fresh
    }

    override suspend fun unlock() {
        calls += "unlock"
        failIf("unlock")
    }

    override suspend fun accountXpub(derivationPath: String): HwAccountKey {
        calls += "accountXpub $derivationPath"
        failIf("accountXpub")
        return HwAccountKey("tpub-$fingerprint", fingerprint, derivationPath)
    }

    override suspend fun signWalletPsbt(descriptor: String, psbtBase64: String, unsignedTxid: String, steps: SignSteps): SpendReview {
        calls += "signWalletPsbt $unsignedTxid"
        failIf("sign")
        steps.showing()
        return sign(descriptor, psbtBase64, unsignedTxid)
    }

    override suspend fun info(): WalletInfo = error("not a call of this app's hardware wallets")

    override suspend fun disconnect() {
        calls += "disconnect"
    }

    override fun close() {
        calls += "close"
    }

    private fun failIf(step: String) {
        if (failAt == step) throw BleException.Disconnected("lost at $step")
    }
}
