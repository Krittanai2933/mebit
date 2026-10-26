package com.mebit.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Mocked single-sig chain surface for Movie Page S* (no live Electrum yet). */
class SingleSigWallet {
    var balanceSats: Long by mutableStateOf(1_250_000L)
        private set
    var receiveAddress: String by mutableStateOf("tb1qmebitdemosinglesig0000000000000000recv")
        private set
    var lastTxid: String by mutableStateOf("")
        private set

    fun refreshMock() {
        receiveAddress = "tb1qmebitdemosinglesig" + (1000..9999).random()
    }

    fun broadcastMock(amountSats: Long, feeSats: Long): Boolean {
        if (amountSats + feeSats > balanceSats) return false
        balanceSats -= amountSats + feeSats
        lastTxid = "mocktxid_" + balanceSats.toString(16) + "_" + amountSats.toString(16)
        return true
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
    var psbtHex: String by mutableStateOf("cHNidP8BAmock_psbt_demo")
        private set
    var lastError: String? by mutableStateOf(null)
        private set
    var broadcastTxid: String by mutableStateOf("")
        private set

    fun resetSignatures() {
        signedFingerprints.clear()
        broadcastTxid = ""
        lastError = null
    }

    fun markSigned(fingerprint: String) {
        val fp = fingerprint.uppercase()
        if (fp !in signedFingerprints) signedFingerprints.add(fp)
    }

    fun signaturesNeeded(m: Int): Int = (m - signedFingerprints.size).coerceAtLeast(0)

    fun readyToBroadcast(m: Int): Boolean = signedFingerprints.size >= m

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
