package com.mebit.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mebit.ffi.signer.FeeTiers
import com.mebit.ffi.signer.HotSendResult
import com.mebit.ffi.signer.HotTx
import com.mebit.ffi.signer.HotWalletSnapshot
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.MobileSignerException
import com.mebit.ffi.signer.PreparedSend
import com.mebit.ffi.signer.hotBroadcast
import com.mebit.ffi.signer.hotFeeTiers
import com.mebit.ffi.signer.hotPrepareSendAll
import com.mebit.ffi.signer.hotPrepareSendAtRate
import com.mebit.ffi.signer.hotReceiveAddress
import com.mebit.ffi.signer.hotWalletHistory
import com.mebit.ffi.signer.hotWalletSnapshot
import kotlin.time.Clock

/**
 * Single-sig hot wallet surface for Movie Page S*.
 * Live path uses BIP-84 + Esplora (hot_* UniFFI). Demo path keeps offline fixtures.
 *
 * [prepare], [post], [snapshot], [fetchHistory], [receiveAddressOf] and [fetchFeeTiers] are the hot_* calls. Tests pass fakes, so they need no native library or network.
 * [nowSeconds] is the phone clock in Unix seconds, which [lastSentAtSeconds] reads when a payment goes out.
 */
class SingleSigWallet(
    private val prepare: (words: List<String>, passphrase: String, network: KeyNetwork, destination: String, amountSats: ULong, feeRateSatVb: ULong, maxFeeSats: ULong) -> PreparedSend = ::hotPrepareSendAtRate,
    private val prepareAll: (words: List<String>, passphrase: String, network: KeyNetwork, destination: String, feeRateSatVb: ULong, maxFeeSats: ULong) -> PreparedSend = ::hotPrepareSendAll,
    private val post: (network: KeyNetwork, hex: String, txid: String) -> HotSendResult = ::hotBroadcast,
    private val snapshot: (words: List<String>, passphrase: String, network: KeyNetwork) -> HotWalletSnapshot = ::hotWalletSnapshot,
    private val fetchHistory: (words: List<String>, passphrase: String, network: KeyNetwork) -> List<HotTx> = ::hotWalletHistory,
    private val nowSeconds: () -> Long = { Clock.System.now().epochSeconds },
    private val receiveAddressOf: (words: List<String>, passphrase: String, network: KeyNetwork, index: UInt) -> String = ::hotReceiveAddress,
    private val fetchFeeTiers: (network: KeyNetwork) -> FeeTiers = ::hotFeeTiers,
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

    /** A signed payment that may be out: its post began and has not been answered with a refusal or an explorer that lists it. [txid] is the one hot_prepare_send_at_rate computed. [sinceSeconds] is the phone clock when the post began. */
    data class UnconfirmedSend(val txid: String, val sinceSeconds: Long)

    /** The [WalletMode] the last [bindSession] left. [WalletMode.Demo] until the first bind. */
    var mode: WalletMode by mutableStateOf(WalletMode.Demo)
        private set
    var balanceSats: Long by mutableStateOf(0L)
        private set
    var receiveAddress: String by mutableStateOf("")
        private set
    var lastTxid: String by mutableStateOf("")
        private set

    /** The fee the last payment that went out pays, in sats: the signed transaction's, not the Send screen's estimate. 0 until a payment goes out. */
    var lastFeeSats: Long by mutableStateOf(0L)
        private set

    /** What the last payment that went out pays its destination, in sats: the signed transaction's, which for send all is the balance less the real fee. 0 until a payment goes out. */
    var lastAmountSats: Long by mutableStateOf(0L)
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

    /**
     * The payment that may be out, or null. [broadcast] sets it and saves it before the post, and while it is set [broadcast] refuses, because the same payment could go out twice. It clears when the post returns or the node refuses, when a [bindSession] loads a [history] that holds its txid, when the user clears it through [clearUnconfirmedSend] after checking the explorer, and in [useDemoFixture]. Never by the clock. [lastTxid] and [lastSentAtSeconds] stay for payments that went out.
     * The session saves it with the wallet ([OnboardingSession.unconfirmedSend]), so a wallet built after a restart takes it back at its first bind and clearing it here clears the saved copy too.
     */
    var unconfirmedSend: UnconfirmedSend? by mutableStateOf(null)
        private set

    fun bindSession(session: OnboardingSession, network: KeyNetwork = KeyNetwork.TEST) {
        lastError = null
        if (session.words.size < 12) {
            useDemoFixture()
            return
        }
        // A wallet built after a restart has no guard of its own yet, so it takes the one the session restored from the saved wallet.
        if (unconfirmedSend == null) unconfirmedSend = session.unconfirmedSend
        try {
            val snap = snapshot(session.words, session.passphrase, network)
            receiveAddress = snap.receiveAddress
            balanceSats = snap.balanceSats.toLong()
            mode = WalletMode.Live
            // A history failure is not the wallet's, so its own try keeps it from changing the mode or reaching lastError.
            val loaded = try {
                fetchHistory(session.words, session.passphrase, network)
            } catch (e: Exception) {
                null
            }
            history = loaded ?: emptyList()
            // Only a list that names the txid can settle it: the empty list of a failed call says nothing, and neither does the clock.
            if (loaded != null && unconfirmedSend?.let { pending -> loaded.any { it.txid == pending.txid } } == true) clearUnconfirmedSend(session)
        } catch (e: Exception) {
            // Mode first, so no frame shows the cleared balance as a known one.
            mode = WalletMode.Unavailable
            lastError = readErrorThai(e.message)
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

    /** The fee tiers for the Send screen. Blocks on the network for a few seconds at most, so call it off the main thread. The fixed tiers when the bridge fails, as when Rust cannot be reached. */
    fun feeTiers(network: KeyNetwork = KeyNetwork.TEST): LiveFeeTiers =
        try {
            LiveFeeTiers.of(fetchFeeTiers(network))
        } catch (e: Exception) {
            LiveFeeTiers.DEFAULT
        }

    /**
     * Signs and posts [amountSats] to [destination] at [feeRateSatVb]. The fee the transaction pays is its size times the rate, and the
     * core refuses before signing when that is more than twice the estimate the screens showed (at least 300 sats), so a payment never
     * costs more than the user could see. [lastFeeSats] holds the fee paid once the payment is out.
     * With [sendAll] the core spends every coin and pays the destination the balance less the real fee, so [amountSats] is only the
     * estimate the screens showed and [lastAmountSats] holds what left.
     */
    fun broadcast(
        session: OnboardingSession,
        amountSats: Long,
        feeRateSatVb: Long,
        destination: String,
        network: KeyNetwork = KeyNetwork.TEST,
        sendAll: Boolean = false,
    ): Boolean {
        lastError = null
        val feeSats = feeRateSatVb * TYPICAL_VSIZE
        if (unconfirmedSend != null) {
            lastError = "การส่งครั้งก่อนยังยืนยันไม่ได้ จึงยังส่งใหม่ไม่ได้"
            return false
        }
        // A real key never takes the mock below, which would show a made-up payment as sent.
        if (session.words.size >= 12 && !live) {
            lastError = "ยังดึงยอดคงเหลือไม่ได้ จึงยังส่งไม่ได้"
            return false
        }
        if (session.words.size < 12 || !live) {
            if (amountSats + feeSats > balanceSats) {
                lastError = "ยอดไม่พอ"
                return false
            }
            balanceSats -= amountSats + feeSats
            lastTxid = "mocktxid_" + balanceSats.toString(16) + "_" + amountSats.toString(16)
            lastFeeSats = feeSats
            lastAmountSats = amountSats
            lastSentAtSeconds = nowSeconds()
            return true
        }
        val prepared = try {
            if (sendAll) {
                prepareAll(session.words, session.passphrase, network, destination, feeRateSatVb.toULong(), maxFeeSats(feeSats).toULong())
            } else {
                prepare(session.words, session.passphrase, network, destination, amountSats.toULong(), feeRateSatVb.toULong(), maxFeeSats(feeSats).toULong())
            }
        } catch (e: Exception) {
            lastError = sendErrorThai(e.message)
            return false
        }
        // The guard reaches the phone before the post, so an app killed during the post still refuses this payment after the restart.
        val pending = UnconfirmedSend(prepared.txid, nowSeconds())
        unconfirmedSend = pending
        if (!session.recordUnconfirmedSend(pending.txid, pending.sinceSeconds)) {
            clearUnconfirmedSend(session)
            lastError = "บันทึกสถานะการส่งไม่ได้ จึงยังไม่ส่ง ลองใหม่ภายหลัง"
            return false
        }
        try {
            post(network, prepared.hex, prepared.txid)
        } catch (e: MobileSignerException.BroadcastUnknown) {
            // The guard stays. Plain Thai, because Home prints lastError raw until its next bind clears it, and the Rust text is English.
            lastError = "ยังไม่ทราบว่าการส่งออกไปแล้วหรือยัง อย่าส่งซ้ำ"
            return false
        } catch (e: MobileSignerException.InvalidPsbtJson) {
            // The node refused the transaction, or hot_broadcast refused before it posted anything: nothing is out and the guard goes.
            clearUnconfirmedSend(session)
            lastError = sendErrorThai(e.message)
            return false
        } catch (e: Exception) {
            // Any other failure could have come after the post, so the guard stays and the payment reads as unknown.
            lastError = "ยังไม่ทราบว่าการส่งออกไปแล้วหรือยัง อย่าส่งซ้ำ"
            return false
        }
        clearUnconfirmedSend(session)
        lastTxid = prepared.txid
        lastFeeSats = prepared.feeSats.toLong()
        lastAmountSats = prepared.amountSats.toLong()
        lastSentAtSeconds = nowSeconds()
        // Its own try: the payment is out once the post returns, and reading it as failed invites a retry that pays twice.
        try {
            val snap = snapshot(session.words, session.passphrase, network)
            balanceSats = snap.balanceSats.toLong()
            receiveAddress = snap.receiveAddress
        } catch (e: Exception) {
        }
        return true
    }

    /** The user checked the explorer and found no such transaction. Clears the guard here and the saved copy, so a new payment can start. */
    fun clearUnconfirmedSend(session: OnboardingSession) {
        unconfirmedSend = null
        session.clearUnconfirmedSend()
    }

    /** Whether the screen may offer the clear control now, by [offersUnconfirmedClear]. False without a guard. */
    fun unconfirmedClearOffered(): Boolean = unconfirmedSend?.let { offersUnconfirmedClear(it.sinceSeconds, nowSeconds()) } ?: false

    /** Offline fixture for catalog / no-seed CI. */
    fun useDemoFixture() {
        mode = WalletMode.Demo
        balanceSats = 1_250_000L
        receiveAddress = "tb1qmebitdemosinglesig0000000000000000recv"
        lastTxid = ""
        lastFeeSats = 0L
        lastAmountSats = 0L
        lastSentAtSeconds = 0L
        history = emptyList()
        unconfirmedSend = null
    }
}

/** How old a guard must be, by the phone clock, before the screen offers the control that clears it. */
const val UNCONFIRMED_CLEAR_OFFER_SECONDS = 120L

/**
 * Whether to offer the clear control for a guard set at [sinceSeconds] when the clock reads [nowSeconds]. The clock decides only
 * the offer, never a clear: a clock moved forward shows the control early, and one moved back would hide it for as long as it was
 * moved, so a negative age shows it too.
 */
fun offersUnconfirmedClear(sinceSeconds: Long, nowSeconds: Long): Boolean = (nowSeconds - sinceSeconds).let { it >= UNCONFIRMED_CLEAR_OFFER_SECONDS || it < 0 }

/**
 * The Thai line for an error a hot_* call threw, matched on the Rust message's prefix or shape, so no English reaches a screen.
 * Each row names the Rust text it answers. `esplora post:` is the node's refusal with its reason, not a network failure.
 */
fun sendErrorThai(message: String?): String {
    // The bindings print every Rust refusal as "invalid PSBT json: {text}", so the rows below match the text after it.
    val m = message.orEmpty().removePrefix("invalid PSBT json: ")
    return when {
        m.startsWith("amount ") && m.endsWith("dust limit") -> "จำนวนน้อยกว่าขั้นต่ำที่เครือข่ายรับ (546 sat)"
        m.startsWith("fee ") && " is more than the amount " in m -> "ค่าธรรมเนียมสูงกว่าจำนวนที่ส่ง เลือกระดับที่ต่ำลง"
        m.startsWith("fee ") && " would be more than the " in m -> "ค่าธรรมเนียมจริงสูงกว่าที่แสดง ยังไม่ได้ส่ง ลองใหม่"
        m.startsWith("insufficient funds") -> "ยอดที่ยืนยันแล้วไม่พอ รอให้เงินที่เพิ่งได้รับยืนยันก่อน"
        m.startsWith("change below dust") -> "เงินทอนน้อยเกินไป ส่งทั้งหมดหรือเปลี่ยนจำนวน"
        // hot_prepare_send_all: the balance less the real fee would be dust.
        m.startsWith("the whole balance ") -> "ยอดทั้งหมดหลังหักค่าธรรมเนียมน้อยกว่าขั้นต่ำที่เครือข่ายรับ (546 sat)"
        m.startsWith("fee ") && " is below " in m -> "ค่าธรรมเนียมต่ำเกินไปสำหรับธุรกรรมนี้ เลือกระดับที่สูงขึ้น"
        m.startsWith("fee ") && " is above " in m -> "ค่าธรรมเนียมสูงผิดปกติ ตรวจจำนวนอีกครั้ง"
        m.startsWith("esplora scan:") -> "ประวัติของกระเป๋านี้ผิดปกติ ตรวจกับ explorer ก่อนส่ง"
        m.startsWith("esplora utxo:") || m.startsWith("esplora balance:") -> "ข้อมูลจาก explorer ไม่ตรงกัน ลองใหม่ภายหลัง"
        m == "amount plus fee does not fit u64" -> "จำนวนผิดปกติ"
        m.startsWith("broadcast:") || m.startsWith("esplora post:") -> "ส่งไม่สำเร็จ"
        // The host throttles unauthenticated clients (Blockstream: 700 requests an hour per IP), after the retries or while probing hosts.
        m.startsWith("esplora get") && "429" in m -> "explorer จำกัดจำนวนการเรียกชั่วคราว รอสักครู่แล้วลองใหม่"
        m.startsWith("esplora get") -> "ติดต่อ explorer ไม่ได้ ลองใหม่ภายหลัง"
        else -> SEND_FALLBACK
    }
}

private const val SEND_FALLBACK = "ส่งไม่สำเร็จ ลองใหม่ภายหลัง"

/** [sendErrorThai] for a failed read of the wallet: its generic fallback would say a send failed when only a fetch did. */
fun readErrorThai(message: String?): String =
    sendErrorThai(message).takeUnless { it == SEND_FALLBACK } ?: "ดึงข้อมูลกระเป๋าไม่สำเร็จ ลองใหม่ภายหลัง"

/** A typical 1-input, 2-output P2WPKH payment. The screens show rate times this as the estimate; the core charges rate times the real size. */
const val TYPICAL_VSIZE = 141L

/** The most the core may charge for a payment whose estimate is [estimateSats]: twice it, and at least 300 sats so a 1 sat/vB estimate still leaves room for a second coin. */
fun maxFeeSats(estimateSats: Long): Long = maxOf(estimateSats * 2, 300L)

/** The three tiers of board 4.4. [satPerVb] is the board's fixed rate, which [LiveFeeTiers.DEFAULT] uses until the live rates arrive or when they cannot be fetched. */
enum class FeeTier(val label: String, val eta: String, val satPerVb: Long) {
    Slow("ช้า", "~1 ชม.", 4),
    Normal("ปกติ", "~30 นาที", 8),
    Fast("เร็ว", "~10 นาที", 15);

    /** The board's estimate: the fixed rate times the typical size. */
    val sats: Long get() = satPerVb * TYPICAL_VSIZE
}

/** The sat/vB of each tier. [live] is true when they came from the node's view of the mempool and false for the fixed board rates. */
data class LiveFeeTiers(val slow: Long, val normal: Long, val fast: Long, val live: Boolean) {
    fun rate(tier: FeeTier): Long = when (tier) {
        FeeTier.Slow -> slow
        FeeTier.Normal -> normal
        FeeTier.Fast -> fast
    }

    /** The fee the screens show for [tier], in sats: the rate times the typical size. */
    fun estimate(tier: FeeTier): Long = rate(tier) * TYPICAL_VSIZE

    /** The three tiles of the Send screen, in board order. */
    fun tiles(): List<FeeTile> = FeeTier.entries.map { FeeTile(it, rate(it), estimate(it)) }

    companion object {
        val DEFAULT = LiveFeeTiers(FeeTier.Slow.satPerVb, FeeTier.Normal.satPerVb, FeeTier.Fast.satPerVb, live = false)

        /** The Rust answer as the screens use it. Rust already caps a rate at 2,000 and never answers 0. */
        fun of(tiers: FeeTiers) = LiveFeeTiers(tiers.slowSatVb.toLong(), tiers.normalSatVb.toLong(), tiers.fastSatVb.toLong(), tiers.live)
    }
}

/** One fee tile: the tier's board copy, its rate and the estimate in sats. */
data class FeeTile(val tier: FeeTier, val satPerVb: Long, val feeSats: Long)

/** "0.0050" to 500_000 sats. Null for anything that is not a positive amount of at most 21 million BTC with at most 8 decimals. */
fun parseBtc(text: String): Long? {
    val t = text.trim()
    if (!Regex("""\d*(\.\d{0,8})?""").matches(t)) return null
    val whole = t.substringBefore('.').ifEmpty { "0" }.toLongOrNull()?.takeIf { it <= 21_000_000 } ?: return null
    val fraction = t.substringAfter('.', "").padEnd(8, '0').toLong()
    return (whole * 100_000_000 + fraction).takeIf { it > 0 }
}

/**
 * 500_000 sats to "0.00500000". Cut to [decimals] places, never rounded, so an amount is never shown larger than it is. An amount
 * under 1 BTC whose cut would read as zero keeps its digits up to the first one that is not, so 9_999 at 4 places is "0.00009", never "0.0000".
 */
fun formatBtc(sats: Long, decimals: Int = 8): String {
    val whole = sats / 100_000_000
    val digits = (sats % 100_000_000).toString().padStart(8, '0')
    val shown = if (whole == 0L && sats != 0L && digits.take(decimals).all { it == '0' }) digits.indexOfFirst { it != '0' } + 1 else decimals
    return if (shown == 0) "$whole" else "$whole.${digits.take(shown)}"
}

sealed interface SendIssue {
    data object InvalidAddress : SendIssue

    /** The balance could not be fetched, so no payment can be checked against it. */
    data object BalanceUnavailable : SendIssue

    /** An earlier payment may be out ([SingleSigWallet.unconfirmedSend]), so a new one could pay twice. */
    data object PaymentUnconfirmed : SendIssue
    data class Insufficient(val missingSats: Long) : SendIssue
}

/** What keeps a payment from review, if anything. [addressOk] is null while the address field is empty. [balanceKnown] is false when the balance could not be fetched. [unconfirmedSend] is true while an earlier payment may be out. */
fun sendIssue(addressOk: Boolean?, amountSats: Long, feeSats: Long, balanceSats: Long, balanceKnown: Boolean = true, unconfirmedSend: Boolean = false): SendIssue? = when {
    addressOk == false -> SendIssue.InvalidAddress
    !balanceKnown -> SendIssue.BalanceUnavailable
    unconfirmedSend -> SendIssue.PaymentUnconfirmed
    amountSats > 0 && amountSats + feeSats > balanceSats -> SendIssue.Insufficient(amountSats + feeSats - balanceSats)
    else -> null
}

class SendDraft {
    var amountSats: Long by mutableStateOf(0L)
        private set

    /** The estimate for [tier]: its rate times [TYPICAL_VSIZE]. What the Send and Review screens show and what the balance check uses. */
    var feeSats: Long by mutableStateOf(FeeTier.Normal.sats)
        private set
    var tier: FeeTier by mutableStateOf(FeeTier.Normal)
        private set

    /** The rates the tiles show: null until the Send screen's fetch answers, and then what it answered. [reset] keeps them, so a payment after another opens with the last known rates while the fetch runs again. */
    var tiers: LiveFeeTiers? by mutableStateOf(null)
        private set

    /** The rate of [tier] in sat/vB, which the broadcast pays. */
    val rateSatVb: Long get() = (tiers ?: LiveFeeTiers.DEFAULT).rate(tier)

    /** True while the amount is what [setSendAll] set, zero included, so a fee change can work it out again. Any other amount ends it. */
    var sendAllMarked: Boolean by mutableStateOf(false)
        private set

    /** True while the amount is send all and above zero. The payment then spends every coin and the core sets the amount from the real fee, so [amountSats] is an estimate. */
    val isSendAll: Boolean get() = sendAllMarked && amountSats > 0
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
        sendAllMarked = false
        amountText = text
        amountSats = parseBtc(text) ?: 0L
    }

    /** Sets the amount to exactly [sats] and writes the BTC text from it, so 500_000 gives "0.005". Zero leaves the text empty. Not `setAmountSats`: that is the private setter's name on the JVM. */
    fun setAmountInSats(sats: Long) {
        sendAllMarked = false
        amountSats = sats
        amountText = if (sats == 0L) "" else formatBtc(sats).trimEnd('0').trimEnd('.')
    }

    /** [setAmountInSats] with the amount marked as send all. */
    fun setSendAll(sats: Long) {
        setAmountInSats(sats)
        sendAllMarked = true
    }

    fun chooseTier(next: FeeTier) {
        tier = next
        feeSats = (tiers ?: LiveFeeTiers.DEFAULT).estimate(next)
    }

    /** Takes the fetched rates and works the estimate for the chosen tier out again. Not `setTiers`: that is the private setter's name on the JVM. */
    fun takeTiers(next: LiveFeeTiers) {
        tiers = next
        chooseTier(tier)
    }

    fun setAmount(text: String): Boolean {
        lastError = null
        val v = text.toLongOrNull()
        if (v == null || v <= 0) {
            lastError = "จำนวนไม่ถูกต้อง"
            return false
        }
        sendAllMarked = false
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
