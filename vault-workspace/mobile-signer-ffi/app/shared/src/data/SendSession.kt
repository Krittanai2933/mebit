package com.mebit.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mebit.ffi.signer.HotSendResult
import com.mebit.ffi.signer.HotTx
import com.mebit.ffi.signer.HotWalletSnapshot
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.hotReceiveAddress
import com.mebit.ffi.signer.hotSend
import com.mebit.ffi.signer.hotWalletHistory
import com.mebit.ffi.signer.hotWalletSnapshot
import kotlin.time.Clock

/**
 * Single-sig hot wallet surface for Movie Page S*.
 * Live path uses BIP-84 + Esplora (hot_* UniFFI). Demo path keeps offline fixtures.
 *
 * [send], [snapshot], [fetchHistory] and [receiveAddressOf] are the hot_* calls. Tests pass fakes, so they need no native library or network.
 * [nowSeconds] is the phone clock in Unix seconds, which [lastSentAtSeconds] reads when a payment goes out.
 */
class SingleSigWallet(
    private val send: (words: List<String>, passphrase: String, network: KeyNetwork, destination: String, amountSats: ULong, feeSats: ULong) -> HotSendResult = ::hotSend,
    private val snapshot: (words: List<String>, passphrase: String, network: KeyNetwork) -> HotWalletSnapshot = ::hotWalletSnapshot,
    private val fetchHistory: (words: List<String>, passphrase: String, network: KeyNetwork) -> List<HotTx> = ::hotWalletHistory,
    private val nowSeconds: () -> Long = { Clock.System.now().epochSeconds },
    private val receiveAddressOf: (words: List<String>, passphrase: String, network: KeyNetwork, index: UInt) -> String = ::hotReceiveAddress,
) {
    /** What the wallet is. A real key is only ever [Live] or [Unavailable], so a real wallet never looks like the demo. */
    enum class WalletMode {
        /** No real key, fewer than 12 words. The offline fixture, whose payments are mocked. */
        Demo,

        /** A real key whose snapshot loaded. The balance and the address came from the chain. */
        Live,

        /** A real key whose snapshot failed. The balance is unknown and the address is derived from the key offline. */
        Unavailable,
    }

    /** The [WalletMode] the last [bindSession] left. [WalletMode.Demo] until the first bind. */
    var mode: WalletMode by mutableStateOf(WalletMode.Demo)
        private set
    var balanceSats: Long by mutableStateOf(0L)
        private set
    var receiveAddress: String by mutableStateOf("")
        private set
    var lastTxid: String by mutableStateOf("")
        private set

    /** When the last payment went out, in Unix seconds on the phone clock. 0 until a payment goes out, and again after [useDemoFixture]. */
    var lastSentAtSeconds: Long by mutableStateOf(0L)
        private set
    var lastError: String? by mutableStateOf(null)
        private set
    val live: Boolean get() = mode == WalletMode.Live

    /** False when the snapshot failed. Screens then show a dash, not [balanceSats], and no payment can start. */
    val balanceKnown: Boolean get() = mode != WalletMode.Unavailable

    /** True when [receiveAddress] belongs to the key, so money sent to it arrives. The demo's address is made up. */
    val addressIsReal: Boolean get() = mode != WalletMode.Demo && receiveAddress.isNotEmpty()

    /** Recent transactions of the wallet address in Esplora's order: mempool first, then confirmed newest first. First page only. Empty for the demo fixture and after a failed history call. */
    var history: List<HotTx> by mutableStateOf(emptyList())
        private set

    fun bindSession(session: OnboardingSession, network: KeyNetwork = KeyNetwork.TEST) {
        lastError = null
        if (session.words.size < 12) {
            useDemoFixture()
            return
        }
        try {
            val snap = snapshot(session.words, session.passphrase, network)
            receiveAddress = snap.receiveAddress
            balanceSats = snap.balanceSats.toLong()
            mode = WalletMode.Live
            // A history failure is not the wallet's, so its own try keeps it from changing the mode or reaching lastError.
            history = try {
                fetchHistory(session.words, session.passphrase, network)
            } catch (e: Exception) {
                emptyList()
            }
        } catch (e: Exception) {
            // Mode first, so no frame shows the cleared balance as a known one.
            mode = WalletMode.Unavailable
            lastError = e.message
            balanceSats = 0L
            history = emptyList()
            // The address needs only the key, so the wallet keeps its own address, never the demo's, while the chain is out of reach.
            receiveAddress = try {
                receiveAddressOf(session.words, session.passphrase, network, 0u)
            } catch (_: Exception) {
                ""
            }
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
            lastSentAtSeconds = nowSeconds()
            return true
        }
        val result = try {
            send(
                session.words,
                session.passphrase,
                network,
                destination,
                amountSats.toULong(),
                feeSats.toULong(),
            )
        } catch (e: Exception) {
            lastError = e.message
            return false
        }
        lastTxid = result.txid
        lastSentAtSeconds = nowSeconds()
        // Its own try: the payment is out once send returns, and reading it as failed invites a retry that pays twice.
        try {
            val snap = snapshot(session.words, session.passphrase, network)
            balanceSats = snap.balanceSats.toLong()
            receiveAddress = snap.receiveAddress
        } catch (e: Exception) {
        }
        return true
    }

    /** Offline fixture for catalog / no-seed CI. */
    fun useDemoFixture() {
        mode = WalletMode.Demo
        balanceSats = 1_250_000L
        receiveAddress = "tb1qmebitdemosinglesig0000000000000000recv"
        lastTxid = ""
        lastSentAtSeconds = 0L
        history = emptyList()
    }
}

/** A typical 1-input, 2-output P2WPKH payment. The fee tiers are rate times this until coin selection reports the real size. */
const val TYPICAL_VSIZE = 141L

enum class FeeTier(val label: String, val eta: String, val satPerVb: Long) {
    Slow("ช้า", "~1 ชม.", 4),
    Normal("ปกติ", "~30 นาที", 8),
    Fast("เร็ว", "~10 นาที", 15);

    val sats: Long get() = satPerVb * TYPICAL_VSIZE
}

/** "0.0050" to 500_000 sats. Null for anything that is not a positive amount of at most 21 million BTC with at most 8 decimals. */
fun parseBtc(text: String): Long? {
    val t = text.trim()
    if (!Regex("""\d*(\.\d{0,8})?""").matches(t)) return null
    val whole = t.substringBefore('.').ifEmpty { "0" }.toLongOrNull()?.takeIf { it <= 21_000_000 } ?: return null
    val fraction = t.substringAfter('.', "").padEnd(8, '0').toLong()
    return (whole * 100_000_000 + fraction).takeIf { it > 0 }
}

/** 500_000 sats to "0.00500000". Cut to [decimals] places, never rounded, so an amount is never shown larger than it is. */
fun formatBtc(sats: Long, decimals: Int = 8): String {
    val fraction = (sats % 100_000_000).toString().padStart(8, '0').take(decimals)
    return if (decimals == 0) "${sats / 100_000_000}" else "${sats / 100_000_000}.$fraction"
}

sealed interface SendIssue {
    data object InvalidAddress : SendIssue

    /** The balance could not be fetched, so no payment can be checked against it. */
    data object BalanceUnavailable : SendIssue
    data class Insufficient(val missingSats: Long) : SendIssue
}

/** What keeps a payment from review, if anything. [addressOk] is null while the address field is empty. [balanceKnown] is false when the balance could not be fetched. */
fun sendIssue(addressOk: Boolean?, amountSats: Long, feeSats: Long, balanceSats: Long, balanceKnown: Boolean = true): SendIssue? = when {
    addressOk == false -> SendIssue.InvalidAddress
    !balanceKnown -> SendIssue.BalanceUnavailable
    amountSats > 0 && amountSats + feeSats > balanceSats -> SendIssue.Insufficient(amountSats + feeSats - balanceSats)
    else -> null
}

class SendDraft {
    var amountSats: Long by mutableStateOf(0L)
        private set
    var feeSats: Long by mutableStateOf(FeeTier.Normal.sats)
        private set
    var tier: FeeTier by mutableStateOf(FeeTier.Normal)
        private set
    var destination: String by mutableStateOf("")
    var amountText: String by mutableStateOf("")
        private set
    var lastError: String? by mutableStateOf(null)
        private set

    /** A new payment: nothing typed, normal fee. */
    fun reset() {
        destination = ""
        amountText = ""
        amountSats = 0L
        chooseTier(FeeTier.Normal)
        lastError = null
    }

    /** Takes what the user typed in the amount field, in BTC. An empty or unreadable text means no amount yet. */
    fun setAmountBtc(text: String) {
        amountText = text
        amountSats = parseBtc(text) ?: 0L
    }

    /** Sets the amount to exactly [sats] and writes the BTC text from it, so 500_000 gives "0.005". Zero leaves the text empty. Not `setAmountSats`: that is the private setter's name on the JVM. */
    fun setAmountInSats(sats: Long) {
        amountSats = sats
        amountText = if (sats == 0L) "" else formatBtc(sats).trimEnd('0').trimEnd('.')
    }

    fun chooseTier(next: FeeTier) {
        tier = next
        feeSats = next.sats
    }

    fun setAmount(text: String): Boolean {
        lastError = null
        val v = text.toLongOrNull()
        if (v == null || v <= 0) {
            lastError = "จำนวนไม่ถูกต้อง"
            return false
        }
        amountSats = v
        amountText = formatBtc(v).trimEnd('0').trimEnd('.')
        return true
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
