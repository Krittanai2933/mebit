package com.mebit.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.hotReceiveAddress
import com.mebit.ffi.signer.hotSend
import com.mebit.ffi.signer.hotWalletSnapshot

/**
 * Single-sig hot wallet surface for Movie Page S*.
 * Live path uses BIP-84 + Esplora (hot_* UniFFI). Demo path keeps offline fixtures.
 */
class SingleSigWallet {
    var balanceSats: Long by mutableStateOf(0L)
        private set
    var receiveAddress: String by mutableStateOf("")
        private set
    var lastTxid: String by mutableStateOf("")
        private set
    var lastError: String? by mutableStateOf(null)
        private set
    var live: Boolean by mutableStateOf(false)
        private set

    fun bindSession(session: OnboardingSession, network: KeyNetwork = KeyNetwork.TEST) {
        lastError = null
        if (session.words.size < 12) {
            useDemoFixture()
            return
        }
        try {
            val snap = hotWalletSnapshot(session.words, network)
            receiveAddress = snap.receiveAddress
            balanceSats = snap.balanceSats.toLong()
            live = true
        } catch (e: Exception) {
            lastError = e.message
            useDemoFixture()
        }
    }

    fun refreshReceive(session: OnboardingSession, index: UInt = 0u, network: KeyNetwork = KeyNetwork.TEST) {
        lastError = null
        if (session.words.size < 12) {
            receiveAddress = "tb1qmebitdemosinglesig" + (1000..9999).random()
            return
        }
        try {
            receiveAddress = hotReceiveAddress(session.words, network, index)
            live = true
        } catch (e: Exception) {
            lastError = e.message
        }
    }

    fun broadcast(
        session: OnboardingSession,
        amountSats: Long,
        feeSats: Long,
        destination: String,
        network: KeyNetwork = KeyNetwork.TEST,
    ): Boolean {
        lastError = null
        if (session.words.size < 12 || !live) {
            if (amountSats + feeSats > balanceSats) {
                lastError = "ยอดไม่พอ"
                return false
            }
            balanceSats -= amountSats + feeSats
            lastTxid = "mocktxid_" + balanceSats.toString(16) + "_" + amountSats.toString(16)
            return true
        }
        return try {
            val result = hotSend(
                session.words,
                network,
                destination,
                amountSats.toULong(),
                feeSats.toULong(),
            )
            lastTxid = result.txid
            val snap = hotWalletSnapshot(session.words, network)
            balanceSats = snap.balanceSats.toLong()
            receiveAddress = snap.receiveAddress
            true
        } catch (e: Exception) {
            lastError = e.message
            false
        }
    }

    /** Offline fixture for catalog / no-seed CI. */
    fun useDemoFixture() {
        live = false
        balanceSats = 1_250_000L
        receiveAddress = "tb1qmebitdemosinglesig0000000000000000recv"
        lastTxid = ""
    }

    fun refreshMock() {
        receiveAddress = "tb1qmebitdemosinglesig" + (1000..9999).random()
    }
}

class SendDraft {
    var amountSats: Long by mutableStateOf(50_000L)
    var feeSats: Long by mutableStateOf(500L)
    var destination: String by mutableStateOf("tb1qdestinationdemo000000000000000000000")
    var lastError: String? by mutableStateOf(null)
        private set

    fun setAmount(text: String): Boolean {
        lastError = null
        val v = text.toLongOrNull()
        if (v == null || v <= 0) {
            lastError = "จำนวนไม่ถูกต้อง"
            return false
        }
        amountSats = v
        return true
    }

    fun setFeePreset(sats: Long) {
        feeSats = sats
    }
}

class MultisigSendDraft {
    var amountSats: Long by mutableStateOf(100_000L)
    var destination: String by mutableStateOf("tb1qvaultdestinationdemo000000000000000")
    val signedFingerprints = mutableStateListOf<String>()
    var psbtBase64: String by mutableStateOf("")
        private set
    var lastError: String? by mutableStateOf(null)
        private set
    var broadcastTxid: String by mutableStateOf("")
        private set

    fun resetSignatures() {
        signedFingerprints.clear()
        broadcastTxid = ""
        lastError = null
        // Placeholder PSBT body until vault PSBT builder lands; HW sign still goes through WalletLink.
        psbtBase64 = "cHNidP8BAF4CAAAAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/////wAAAAA="
    }

    fun markSigned(fingerprint: String) {
        val fp = fingerprint.uppercase()
        if (fp !in signedFingerprints) signedFingerprints.add(fp)
    }

    fun setSignedPsbt(base64: String) {
        psbtBase64 = base64
    }

    fun signaturesNeeded(m: Int): Int = (m - signedFingerprints.size).coerceAtLeast(0)

    fun readyToBroadcast(m: Int): Boolean = signedFingerprints.size >= m

    /** Destination + amount gate before HW sign (vault-core real-PSBT policy is Term-2). */
    fun policyAllowsSign(): Boolean {
        if (destination.isBlank() || amountSats <= 0L) {
            lastError = "ปลายทางหรือจำนวนไม่ถูกต้อง"
            return false
        }
        return true
    }

    fun broadcastMock(m: Int): Boolean {
        if (!readyToBroadcast(m)) {
            lastError = "ลายเซ็นยังไม่ครบ"
            return false
        }
        val n = signedFingerprints.size
        broadcastTxid = "mockvaulttx_${n}_${amountSats.toString(16)}"
        return true
    }
}
