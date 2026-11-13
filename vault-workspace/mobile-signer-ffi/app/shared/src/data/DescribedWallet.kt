package com.mebit.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import com.mebit.app.data.SingleSigWallet.DamagedGuard
import com.mebit.app.data.SingleSigWallet.UnconfirmedSend
import com.mebit.app.data.SingleSigWallet.Unsettled
import com.mebit.app.data.SingleSigWallet.WalletMode
import com.mebit.ffi.signer.FeeTiers
import com.mebit.ffi.signer.FoundWallet
import com.mebit.ffi.signer.HotScan
import com.mebit.ffi.signer.HotSendResult
import com.mebit.ffi.signer.HotTx
import com.mebit.ffi.signer.HotTxDetail
import com.mebit.ffi.signer.HotWalletSnapshot
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.PairingCodes
import com.mebit.ffi.signer.PreparedSend
import com.mebit.ffi.signer.SpendReview
import com.mebit.ffi.signer.WalletBalance
import com.mebit.ffi.signer.WalletShape
import com.mebit.ffi.signer.hotBroadcast
import com.mebit.ffi.signer.hotFeeTiers
import com.mebit.ffi.signer.walletBalance
import com.mebit.ffi.signer.walletDescribe
import com.mebit.ffi.signer.walletDraft
import com.mebit.ffi.signer.walletFinish
import com.mebit.ffi.signer.walletHistory
import com.mebit.ffi.signer.walletSignWithWords
import com.mebit.ffi.signer.walletSnapshot
import com.mebit.ffi.signer.walletTxDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * What Home 4.1, Receive, Send and the slip 4.6 read of a one-key wallet, whichever signs its payments: [SingleSigWallet] for the
 * phone's own key, [DescribedWallet] for a Jade or a Safe 7. Read state only; each says how to refresh it and how to pay.
 */
interface HomeSource {
    /** [WalletMode.Demo] until the first refresh ends (and for the phone's offline fixture), then Live or Unavailable. */
    val mode: WalletMode
    val scan: HotScan
    val balanceSats: Long
    val spendableSats: Long

    /** False until a refresh read the whole wallet: screens show a dash, not [balanceSats], and no payment can start. */
    val balanceKnown: Boolean
    val receiveAddress: String
    val addressIsReal: Boolean
    val history: List<HotTx>
    val lastError: String?
    val lastFailure: String?
    val unconfirmedSend: Unsettled?
    val lastTxid: String
    val lastFeeSats: Long
    val lastAmountSats: Long
    val lastSentAtSeconds: Long

    fun unconfirmedClearOffered(): Boolean

    fun boundWithin(seconds: Long): Boolean
}

/**
 * The wallet_* calls, the fee tiers and the post, for every wallet the book describes by a descriptor: a Jade or Safe 7 one-key
 * wallet ([DescribedWallet], [HardwareWallets]) and a several-key wallet ([VaultSend], [Wallets]' reads of one). Tests pass fakes,
 * so they need no native library and no network.
 */
internal class DescribedCore(
    val snapshot: (String, KeyNetwork) -> HotWalletSnapshot = { d, n -> walletSnapshot(d, n) },
    val history: (String, KeyNetwork) -> List<HotTx> = { d, n -> walletHistory(d, n) },
    val detail: (String, KeyNetwork, String) -> HotTxDetail? = { d, n, txid -> walletTxDetail(d, n, txid) },
    val draft: (String, KeyNetwork, String, ULong?, ULong) -> SpendReview = { d, n, to, sats, rate -> walletDraft(d, n, to, sats, rate) },
    val finish: (String, KeyNetwork, String, String) -> PreparedSend = { d, n, psbt, txid -> walletFinish(d, n, psbt, txid) },
    val feeTiers: (KeyNetwork) -> FeeTiers = { hotFeeTiers(it) },
    val postHex: (KeyNetwork, String, String) -> HotSendResult = { n, hex, txid -> hotBroadcast(n, hex, txid) },
    val describe: (String, KeyNetwork) -> WalletShape = { d, n -> walletDescribe(d, n) },
    /** A balance from the scan alone, with no coin list: the wallet list's figure and a several-key wallet's detail. */
    val balance: (String, KeyNetwork) -> WalletBalance = { d, n -> walletBalance(d, n) },
    /** (descriptor, network, psbt, the unsigned txid the user was shown, words, passphrase). */
    val signWithWords: (String, KeyNetwork, String, String, List<String>, String) -> SpendReview =
        { d, n, psbt, txid, words, passphrase -> walletSignWithWords(d, n, psbt, txid, words, passphrase) },
)

/** A payment of a [DescribedWallet], from Review to the post. */
sealed interface DevicePayment {
    /** The payment as wallet_draft built it, and as the device will show it. */
    val review: SpendReview

    /** Review 4.5's payment. Nothing is signed. */
    data class Drafted(override val review: SpendReview) : DevicePayment

    /** The device signed it and the core finished it into [prepared], the bytes [DescribedWallet.post] sends. */
    data class Signed(override val review: SpendReview, val prepared: PreparedSend) : DevicePayment
}

/**
 * The chain side of a one-key hardware wallet: what [SingleSigWallet] is to a phone wallet, with the device signing in place of the
 * phone's key, and no fee bump or cancel. Every call that reaches Rust or the network suspends and runs on [io], off the main thread.
 * The pay-twice guard is the phone's one guard file, under [HardwareWallet.guardKey]. [others] gives every other wallet's guard key,
 * for a clear of a damaged guard file, null while one could go unnamed.
 */
class DescribedWallet internal constructor(
    val wallet: HardwareWallet,
    private val payments: PendingPayments,
    private val link: HardwareLink,
    private val others: () -> List<String>?,
    private val core: DescribedCore = DescribedCore(),
    private val nowSeconds: () -> Long = { Clock.System.now().epochSeconds },
    private val io: CoroutineDispatcher = Dispatchers.Default,
) : HomeSource, GuardExits {
    override var mode: WalletMode by mutableStateOf(WalletMode.Demo)
        private set
    override var scan: HotScan by mutableStateOf(HotScan.COMPLETE)
        private set
    override var balanceSats: Long by mutableStateOf(0L)
        private set
    override var spendableSats: Long by mutableStateOf(0L)
        private set

    /** Receive address 0, which the book checked against the key at open, until a refresh names the next unused one. */
    override var receiveAddress: String by mutableStateOf(wallet.address)
        private set
    override var history: List<HotTx> by mutableStateOf(emptyList())
        private set
    override var lastError: String? by mutableStateOf(null)
        private set

    /** The Rust text of what the last refresh could not load, for a debug build's line. */
    override var lastFailure: String? by mutableStateOf(null)
        private set

    /** The payment that may be out, as [SingleSigWallet.unconfirmedSend] is for a phone wallet: set before the post, so no second payment can start. */
    override var unconfirmedSend: Unsettled? by mutableStateOf(null)
        private set
    override var lastTxid: String by mutableStateOf("")
        private set
    override var lastFeeSats: Long by mutableStateOf(0L)
        private set
    override var lastAmountSats: Long by mutableStateOf(0L)
        private set
    override var lastSentAtSeconds: Long by mutableStateOf(0L)
        private set

    /** The payment between Review and the post, or null. Nothing of it is guarded or out. */
    var payment: DevicePayment? by mutableStateOf(null)
        private set

    override val balanceKnown: Boolean get() = mode == WalletMode.Live && scan == HotScan.COMPLETE

    /** Every address comes from the key, so money sent to it arrives. */
    override val addressIsReal: Boolean get() = receiveAddress.isNotEmpty()

    /** Counts the drops of [payment], so a draft or a sign that returns after [dropPayment] holds nothing. */
    private var paymentEpoch = 0

    /** Runs Home's refresh and a list's refresh one after the other, so an older one can never end last. */
    private val refreshLock = Mutex()

    private var lastBoundSeconds: Long? = null

    override fun boundWithin(seconds: Long): Boolean = lastBoundSeconds?.let { nowSeconds() - it in 0..seconds } == true

    override fun unconfirmedClearOffered(): Boolean = clearOffered(unconfirmedSend, nowSeconds())

    /**
     * Snapshot, then history, as [SingleSigWallet.bindSession] reads a phone wallet. A wallet with no guard takes the one the phone keeps
     * under its key, and a history that lists that payment settles it. A failed snapshot leaves receive address 0 and no figures.
     */
    suspend fun refresh(): Unit = refreshLock.withLock {
        withContext(io) {
            lastError = null
            lastFailure = null
            if (unconfirmedSend == null) unconfirmedSend = payments.of(wallet.guardKey)
            try {
                show(core.snapshot(wallet.descriptor, KeyNetwork.TEST))
                // The history can speak only for the guard held as it begins to load.
                val judged = unconfirmedSend as? UnconfirmedSend
                // A history failure is not the wallet's, so its own try keeps it from changing the mode or reaching lastError.
                val loaded = try {
                    core.history(wallet.descriptor, KeyNetwork.TEST)
                } catch (e: Exception) {
                    lastFailure = "history: ${e.message ?: e}"
                    null
                }
                history = loaded ?: emptyList()
                if (loaded != null && judged?.settledBy(loaded) == true) clearUnconfirmedSend(judged)
            } catch (e: Exception) {
                // Mode first, so no frame shows the cleared balance as a known one.
                mode = WalletMode.Unavailable
                lastError = readErrorThai(e.message)
                lastFailure = "snapshot: ${e.message ?: e}"
                balanceSats = 0L
                spendableSats = 0L
                history = emptyList()
                receiveAddress = wallet.address
            }
            lastBoundSeconds = nowSeconds()
        }
    }

    /** Takes [snap]'s figures and status in one snapshot of the state, so no frame pairs a new partial sum with an old complete status. */
    private fun show(snap: HotWalletSnapshot) = Snapshot.withMutableSnapshot {
        scan = snap.scan
        receiveAddress = snap.receiveAddress
        balanceSats = snap.balanceSats.toLong()
        spendableSats = snap.spendableSats.toLong()
        mode = WalletMode.Live
        lastError = scanErrorThai(snap.scan)
    }

    /** What the detail screen knows of [txid] beyond its row. Writes no wallet state, because the screen owns the answer. */
    suspend fun detail(txid: String): TxDetailLoad.Done = withContext(io) {
        try {
            core.detail(wallet.descriptor, KeyNetwork.TEST, txid)?.let { TxDetailLoad.Loaded(it) } ?: TxDetailLoad.NotFound
        } catch (e: Exception) {
            TxDetailLoad.Failed(e.message ?: e.toString())
        }
    }

    /** The Send screen's fee tiers, the fixed ones when the network cannot be reached. */
    suspend fun feeTiers(): LiveFeeTiers = withContext(io) {
        try {
            LiveFeeTiers.of(core.feeTiers(KeyNetwork.TEST))
        } catch (e: Exception) {
            LiveFeeTiers.DEFAULT
        }
    }

    /**
     * Builds the unsigned payment of [amountSats] to [destination], or of the whole spendable balance when [amountSats] is null, at
     * [rateSatVb], and holds it in [payment] for Review in place of one held. Signs and posts nothing. False with a Thai [lastError]
     * while an earlier payment may be out, while the balance is not known, or when the core refuses, and false with no error when
     * [dropPayment] ran meanwhile.
     */
    suspend fun draft(destination: String, amountSats: Long?, rateSatVb: Long): Boolean {
        lastError = null
        payment = null
        val epoch = paymentEpoch
        if (unconfirmedSend != null) {
            lastError = EARLIER_UNSETTLED
            return false
        }
        if (!balanceKnown) {
            lastError = BALANCE_UNKNOWN
            return false
        }
        val review = try {
            withContext(io) { core.draft(wallet.descriptor, KeyNetwork.TEST, destination, amountSats?.toULong(), rateSatVb.toULong()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = sendErrorThai(e.message)
            return false
        }
        if (epoch != paymentEpoch) return false
        payment = DevicePayment.Drafted(review)
        return true
    }

    /**
     * Has the device [found] sign the drafted payment. [HardwareLink] refuses a device that does not hold this wallet's key before
     * anything shows on it, signWalletPsbt shows the payment the draft named by its unsigned txid, and wallet_finish, given that same
     * txid, makes the bytes [post] sends. True once [payment] is [DevicePayment.Signed]. False, with nothing asked of the device, when
     * no payment is drafted, and false when [dropPayment] ran meanwhile. Throws the link's BleException and the core's refusal, and the
     * payment stays drafted. [onShown] runs once the device holds this wallet's key and is about to show the payment, for Sign 4.5.3.
     */
    suspend fun sign(found: FoundWallet, codes: PairingCodes, onShown: () -> Unit = {}): Boolean {
        val drafted = payment as? DevicePayment.Drafted ?: return false
        val epoch = paymentEpoch
        val review = drafted.review
        val signed = link.use(found, wallet.path, expect = wallet.fingerprint, codes) {
            onShown()
            it.link.signWalletPsbt(wallet.descriptor, review.psbt, review.unsignedTxid)
        }
        val prepared = withContext(io) { core.finish(wallet.descriptor, KeyNetwork.TEST, signed.psbt, review.unsignedTxid) }
        if (epoch != paymentEpoch) return false
        payment = DevicePayment.Signed(review, prepared)
        return true
    }

    /** Lets the payment go without posting it, a draft or a sign still running included. Nothing of it was guarded or posted. */
    fun dropPayment() {
        paymentEpoch++
        payment = null
    }

    /**
     * Posts the signed payment once the user confirmed it, through [postWithGuard] under this wallet's guard key. Once it is out,
     * [lastTxid], [lastFeeSats], [lastAmountSats] and [lastSentAtSeconds] hold what the slip shows. False with a Thai [lastError]
     * whenever it is not out, and false with nothing posted when no payment is signed.
     */
    suspend fun post(): Boolean = withContext(io) {
        lastError = null
        // Another payment may be out: this one must not go, and that payment's guard must not be overwritten.
        if (unconfirmedSend != null) {
            lastError = EARLIER_UNSETTLED
            return@withContext false
        }
        val prepared = (payment as? DevicePayment.Signed)?.prepared ?: return@withContext false
        payment = null
        val pending = UnconfirmedSend(prepared.txid, nowSeconds(), prepared.hex.takeIf { txidOf(it) == prepared.txid })
        val posted = postWithGuard(
            pending,
            prepared.hex,
            KeyNetwork.TEST,
            core.postHex,
            claim = { send -> payments.claim(wallet.guardKey, send).also { if (it == Claim.Written) unconfirmedSend = send } },
            clear = { clearUnconfirmedSend(it) },
        )
        when (posted) {
            is Posted.Taken -> unconfirmedSend = posted.earlier
            Posted.Damaged -> unconfirmedSend = DamagedGuard
            else -> Unit
        }
        lastError = postedLine(posted)
        if (posted != Posted.Out) return@withContext false
        lastTxid = prepared.txid
        lastFeeSats = prepared.feeSats.toLong()
        lastAmountSats = prepared.amountSats.toLong()
        lastSentAtSeconds = nowSeconds()
        // Its own try: the payment is out, and reading it as failed invites a retry that pays twice.
        try {
            show(core.snapshot(wallet.descriptor, KeyNetwork.TEST))
        } catch (e: Exception) {
        }
        true
    }

    /** Posts the guarded payment's own bytes again, as [SingleSigWallet.repost] does: the same transaction, so it can never pay twice. Only [Repost.Out] drops the guard. */
    override suspend fun repost(): Repost = withContext(io) {
        val pending = unconfirmedSend as? UnconfirmedSend ?: return@withContext Repost.Failed
        val outcome = payments.repost(wallet.guardKey, pending) { hex -> postAgain(core.postHex, KeyNetwork.TEST, hex, pending.txid) } ?: Repost.Failed
        if (outcome == Repost.Out && !pending.holdsCancel) {
            payments.settle(wallet.guardKey, pending, others())
            if (unconfirmedSend == pending) unconfirmedSend = null
        }
        outcome
    }

    /**
     * The payment of [guard] is out, refused or listed, or the user checked the explorer and found none: drops that guard here and on
     * the phone, and only that one. False, with it kept, when the phone could not drop it.
     */
    override fun clearUnconfirmedSend(guard: Unsettled): Boolean {
        if (!payments.settle(wallet.guardKey, guard, others())) return false
        if (unconfirmedSend == guard) unconfirmedSend = null
        return true
    }
}
