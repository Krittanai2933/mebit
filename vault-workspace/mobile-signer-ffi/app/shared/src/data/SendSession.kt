package com.mebit.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import com.mebit.ffi.signer.AddressType
import com.mebit.ffi.signer.FeeTiers
import com.mebit.ffi.signer.HotScan
import com.mebit.ffi.signer.HotSendResult
import com.mebit.ffi.signer.HotTx
import com.mebit.ffi.signer.HotTxDetail
import com.mebit.ffi.signer.HotWalletSnapshot
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.MobileSignerException
import com.mebit.ffi.signer.PreparedSend
import com.mebit.ffi.signer.hotBroadcast
import com.mebit.ffi.signer.hotFeeTiers
import com.mebit.ffi.signer.hotPrepareCancel
import com.mebit.ffi.signer.hotPrepareFeeBump
import com.mebit.ffi.signer.hotPrepareSendAll
import com.mebit.ffi.signer.hotPrepareSendAtRate
import com.mebit.ffi.signer.hotReceiveAddress
import com.mebit.ffi.signer.hotTxDetail
import com.mebit.ffi.signer.hotWalletHistory
import com.mebit.ffi.signer.hotWalletSnapshot
import com.mebit.app.feature.send.thousands
import kotlin.time.Clock

/**
 * Single-sig hot wallet surface for Movie Page S*.
 * Live path uses the session's address type + Esplora (hot_* UniFFI). Demo path keeps offline fixtures.
 *
 * [prepareAtRate], [prepareAll], [postHex], [snapshot], [fetchHistory], [receiveAddressOf], [fetchFeeTiers], [fetchDetail], [bumpAtRate] and [cancelAtRate] are the hot_* calls. Tests pass fakes, so they need no native library or network.
 * [nowSeconds] is the phone clock in Unix seconds, which [lastSentAtSeconds] reads when a payment goes out.
 */
class SingleSigWallet(
    private val prepareAtRate: (words: List<String>, passphrase: String, network: KeyNetwork, addressType: AddressType, destination: String, amountSats: ULong, feeRateSatVb: ULong) -> PreparedSend = ::hotPrepareSendAtRate,
    private val prepareAll: (words: List<String>, passphrase: String, network: KeyNetwork, addressType: AddressType, destination: String, feeRateSatVb: ULong) -> PreparedSend = ::hotPrepareSendAll,
    private val postHex: (network: KeyNetwork, hex: String, txid: String) -> HotSendResult = ::hotBroadcast,
    private val snapshot: (words: List<String>, passphrase: String, network: KeyNetwork, addressType: AddressType) -> HotWalletSnapshot = ::hotWalletSnapshot,
    private val fetchHistory: (words: List<String>, passphrase: String, network: KeyNetwork, addressType: AddressType) -> List<HotTx> = ::hotWalletHistory,
    private val nowSeconds: () -> Long = { Clock.System.now().epochSeconds },
    private val receiveAddressOf: (words: List<String>, passphrase: String, network: KeyNetwork, addressType: AddressType, index: UInt) -> String = ::hotReceiveAddress,
    private val fetchFeeTiers: (network: KeyNetwork) -> FeeTiers = ::hotFeeTiers,
    private val fetchDetail: (words: List<String>, passphrase: String, network: KeyNetwork, addressType: AddressType, txid: String) -> HotTxDetail? = ::hotTxDetail,
    private val bumpAtRate: (words: List<String>, passphrase: String, network: KeyNetwork, addressType: AddressType, earlierHex: String, feeRateSatVb: ULong) -> PreparedSend = ::hotPrepareFeeBump,
    private val cancelAtRate: (words: List<String>, passphrase: String, network: KeyNetwork, addressType: AddressType, earlierHex: String, feeRateSatVb: ULong) -> PreparedSend = ::hotPrepareCancel,
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

    /** A payment that may be out, which stops every new one: [UnconfirmedSend] when the phone's guard names it, [DamagedGuard] when the guard file is damaged. */
    sealed interface Unsettled

    /**
     * A signed payment that may be out, because its post began and has not been answered with a refusal or an explorer that lists it.
     * [txid] is the one hot_prepare_send_at_rate computed. [sinceSeconds] is the phone clock when the post began. [hex] is the signed
     * transaction the guard file keeps beside it, whose txid is [txid], so [repost] can post the same payment again. Null for a guard
     * written without it, by a build before it or by the move from an older record.
     * A replacement ([postReplacement]) spends every coin of the payment it replaces, so one chain of them can pay at most once.
     * [replaces] names the earlier payments of its chain, oldest first, because any of them may be the one that is out, and
     * [holdsCancel] is true once the chain holds a cancel. Both only beside [hex].
     */
    data class UnconfirmedSend(
        val txid: String,
        val sinceSeconds: Long,
        val hex: String? = null,
        val replaces: List<String> = emptyList(),
        val holdsCancel: Boolean = false,
    ) : Unsettled {
        /**
         * Whether [history] shows this chain settled. It must list the txid or one this replaced, and once the chain holds a cancel only
         * with a block behind it, because until a block holds one transaction of the chain the payment it cancels may still confirm.
         */
        fun settledBy(history: List<HotTx>): Boolean =
            history.any { (it.txid == txid || it.txid in replaces) && (!holdsCancel || it.confirmations > 0u) }
    }

    /** The guard file is on the phone but cannot be read or decoded, so a payment may be out whose txid and time are lost. No explorer can settle it, only the user. */
    data object DamagedGuard : Unsettled

    /** A payment the core signed that nothing has posted: what [prepare] holds until [post] sends it or [dropHeldSend] lets it go. [network] is the one it was signed for, so [post] cannot send it anywhere else. */
    data class HeldSend(val prepared: PreparedSend, val network: KeyNetwork)

    /** A [kind] replacement of the guard [replaces] that the core signed and nothing has posted. [prepareReplacement] holds it until [postReplacement] sends it or [dropHeldReplacement] lets it go. [network] is the one it was signed for, and [rateSatVb] the rate it was asked for. */
    data class HeldReplacement(val prepared: PreparedSend, val network: KeyNetwork, val replaces: UnconfirmedSend, val kind: Replacement, val rateSatVb: Long)

    /** The [WalletMode] the last [bindSession] left. [WalletMode.Demo] until the first bind. */
    var mode: WalletMode by mutableStateOf(WalletMode.Demo)
        private set

    /** How far the scan behind the last snapshot got. The figures are the wallet's only once it is [HotScan.COMPLETE], see [figuresKnown]. */
    var scan: HotScan by mutableStateOf(HotScan.COMPLETE)
        private set
    /** What the wallet holds, confirmed and mempool: Home's figure. */
    var balanceSats: Long by mutableStateOf(0L)
        private set

    /** What a payment could spend now, from the same coin lists the core spends: the Send screen's figure. At most [balanceSats]. */
    var spendableSats: Long by mutableStateOf(0L)
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

    /** The Rust text of what the last [bindSession] could not load, after "snapshot: " or "history: ". Only a debug build shows it, so a phone screenshot names the request that failed. Null after a bind that loaded both. */
    var lastFailure: String? by mutableStateOf(null)
        private set
    val live: Boolean get() = mode == WalletMode.Live

    /** False when the snapshot failed or its scan has not reached the end of the wallet. Screens then show a dash, not [balanceSats], and no payment can start. */
    val balanceKnown: Boolean get() = figuresKnown(mode, scan)

    /** True when [receiveAddress] belongs to the key, so money sent to it arrives. The demo's address is made up. */
    val addressIsReal: Boolean get() = mode != WalletMode.Demo && receiveAddress.isNotEmpty()

    /** Recent transactions of the wallet address in Esplora's order: mempool first, then confirmed newest first. First page only. Empty for the demo fixture and after a failed history call. */
    var history: List<HotTx> by mutableStateOf(emptyList())
        private set

    /**
     * The payment that may be out, or null. [post] sets it and saves it before the post, and while it is set [prepare] refuses, because the same payment could go out twice. It clears when the post returns or the node refuses, when a [repost] of its bytes or a fee bump ([postReplacement]) is out, when a [bindSession] loads a [history] that holds its txid or one it replaced, when the user clears it through [clearUnconfirmedSend] after checking the explorer, and in [useDemoFixture]. Never by the clock. A chain that holds a cancel clears only by the user or by a history that lists one of its txids with a block. Every clear names the guard it settles, so a replacement's guard that took that one's place meanwhile stays. [lastTxid] and [lastSentAtSeconds] stay for payments that went out.
     * The session keeps it in the guard file ([OnboardingSession.unconfirmedSend]), so a wallet built after a restart, or another live instance of the wallet, takes it at its next bind, and clearing it here clears the file too.
     * A damaged guard file is [DamagedGuard] here: no history can settle it, so only the user's clear does.
     */
    var unconfirmedSend: Unsettled? by mutableStateOf(null)
        private set

    /**
     * The payment [prepare] signed and holds for the Review screen, or null. It has no guard and nothing of it is out.
     * Invariant: the pay-twice guard is written only in [post], after the user confirmed, and [post] posts only once that write succeeded.
     * So no guard ever names a payment the user did not confirm, and no payment is ever posted without its guard on the phone first.
     */
    var heldSend: HeldSend? by mutableStateOf(null)
        private set

    /** Counts the drops of a held payment, so a [prepare] that returns after [dropHeldSend] ran holds nothing. */
    private var heldEpoch: Int by mutableStateOf(0)

    /** The replacement [prepareReplacement] signed and holds for the screen's card, or null. Nothing of it is written or out. */
    var heldReplacement: HeldReplacement? by mutableStateOf(null)
        private set

    /** Counts the drops of a held replacement, as [heldEpoch] does for a payment. */
    private var replacementEpoch: Int by mutableStateOf(0)

    fun bindSession(session: OnboardingSession, network: KeyNetwork = KeyNetwork.TEST) {
        lastError = null
        lastFailure = null
        if (session.words.size < 12) {
            useDemoFixture()
            return
        }
        // A wallet with no guard of its own takes the one the phone keeps for this key, which a run before a restart or another instance wrote.
        if (unconfirmedSend == null) unconfirmedSend = session.unconfirmedSend()
        try {
            val snap = snapshot(session.words, session.passphrase, network, session.addressType)
            show(snap) {
                mode = WalletMode.Live
                lastError = scanErrorThai(snap.scan)
            }
            // The history can speak only for the guard held as it begins to load. A replacement posted during the load came after it.
            val judged = unconfirmedSend as? UnconfirmedSend
            // A history failure is not the wallet's, so its own try keeps it from changing the mode or reaching lastError.
            val loaded = try {
                fetchHistory(session.words, session.passphrase, network, session.addressType)
            } catch (e: Exception) {
                lastFailure = "history: ${e.message ?: e}"
                null
            }
            history = loaded ?: emptyList()
            // Only a list that names the payment can settle it. The empty list of a failed call says nothing, and neither does the clock.
            if (loaded != null && judged?.settledBy(loaded) == true) clearUnconfirmedSend(session, judged)
        } catch (e: Exception) {
            // Mode first, so no frame shows the cleared balance as a known one.
            mode = WalletMode.Unavailable
            lastError = readErrorThai(e.message)
            lastFailure = "snapshot: ${e.message ?: e}"
            balanceSats = 0L
            spendableSats = 0L
            history = emptyList()
            // The address needs only the key, so the wallet keeps its own address, never the demo's, while the chain is out of reach.
            receiveAddress = try {
                receiveAddressOf(session.words, session.passphrase, network, session.addressType, 0u)
            } catch (_: Exception) {
                ""
            }
        }
    }

    /**
     * Takes [snap]'s figures and status, with [also], in one snapshot of the state, so no frame pairs a new partial sum with the
     * old status and shows it as the balance, or the old sum with a new complete status.
     */
    private fun show(snap: HotWalletSnapshot, also: () -> Unit = {}) = Snapshot.withMutableSnapshot {
        scan = snap.scan
        receiveAddress = snap.receiveAddress
        balanceSats = snap.balanceSats.toLong()
        spendableSats = snap.spendableSats.toLong()
        also()
    }

    /** The fee tiers for the Send screen. Blocks on the network for a few seconds at most, so call it off the main thread. The fixed tiers when the bridge fails, as when Rust cannot be reached. */
    fun feeTiers(network: KeyNetwork = KeyNetwork.TEST): LiveFeeTiers =
        try {
            LiveFeeTiers.of(fetchFeeTiers(network))
        } catch (e: Exception) {
            LiveFeeTiers.DEFAULT
        }

    /**
     * What the detail screen knows of [txid] beyond its row. Fewer than 12 words is the demo, as in [bindSession] and [prepare], and asks nothing.
     * Writes no wallet state, because the screen owns the answer, so a screen left mid-call leaves nothing behind. Blocks, so call it off the main thread.
     */
    fun detail(session: OnboardingSession, txid: String, network: KeyNetwork = KeyNetwork.TEST): TxDetailLoad.Done {
        if (session.words.size < 12) return TxDetailLoad.RowOnly
        return try {
            fetchDetail(session.words, session.passphrase, network, session.addressType, txid)?.let { TxDetailLoad.Loaded(it) } ?: TxDetailLoad.NotFound
        } catch (e: Exception) {
            TxDetailLoad.Failed(e.message ?: e.toString())
        }
    }

    /**
     * Signs [amountSats] to [destination] at [feeRateSatVb] and holds the result in [heldSend], with no guard written and nothing posted,
     * so the Review screen can read the real fee and ask before [post]. The fee the transaction pays is its size times the rate, so a
     * payment that needs many coins pays more than the estimate the screens showed, and [PreparedSend.feeSats] is what it pays.
     * With [sendAll] the core spends every coin and pays the destination the balance less the real fee, so [amountSats] is only the
     * estimate the screens showed and [PreparedSend.amountSats] is what leaves.
     * A payment already held is replaced, because nothing of it was posted or guarded. The demo mock pays at once and holds nothing.
     * False, with no error, when [dropHeldSend] ran while the core was signing: the user left, so the payment is let go, not held.
     */
    fun prepare(
        session: OnboardingSession,
        amountSats: Long,
        feeRateSatVb: Long,
        destination: String,
        network: KeyNetwork = KeyNetwork.TEST,
        sendAll: Boolean = false,
    ): Boolean {
        lastError = null
        heldSend = null
        val epoch = heldEpoch
        val feeSats = feeRateSatVb * TYPICAL_VSIZE
        if (unconfirmedSend != null) {
            lastError = EARLIER_UNSETTLED
            return false
        }
        // A real key never takes the mock below, which would show a made-up payment as sent, and never pays from a scan that has not found the whole wallet.
        if (session.words.size >= 12 && !(live && balanceKnown)) {
            lastError = "ยังดึงยอดคงเหลือไม่ได้ จึงยังส่งไม่ได้"
            return false
        }
        if (session.words.size < 12 || !live) {
            if (amountSats + feeSats > balanceSats) {
                lastError = "ยอดไม่พอ"
                return false
            }
            balanceSats -= amountSats + feeSats
            spendableSats -= amountSats + feeSats
            lastTxid = "mocktxid_" + balanceSats.toString(16) + "_" + amountSats.toString(16)
            lastFeeSats = feeSats
            lastAmountSats = amountSats
            lastSentAtSeconds = nowSeconds()
            return true
        }
        val prepared = try {
            if (sendAll) {
                prepareAll(session.words, session.passphrase, network, session.addressType, destination, feeRateSatVb.toULong())
            } else {
                prepareAtRate(session.words, session.passphrase, network, session.addressType, destination, amountSats.toULong(), feeRateSatVb.toULong())
            }
        } catch (e: Exception) {
            lastError = sendErrorThai(e.message)
            return false
        }
        if (epoch != heldEpoch) return false
        heldSend = HeldSend(prepared, network)
        return true
    }

    /** Lets the held payment go without posting it, a [prepare] still running included. Nothing of it was posted or guarded, so the draft stays as it is and the user can change the tier or the amount and [prepare] again. */
    fun dropHeldSend() {
        heldEpoch++
        heldSend = null
    }

    /**
     * Posts the payment [prepare] holds, once the user confirmed it. The guard goes to the phone first, then the post, so an app killed
     * during the post still refuses this payment after the restart. [lastFeeSats] and [lastAmountSats] hold what the transaction pays
     * once it is out. False with nothing held, and false with nothing posted while another payment's guard is set, which it keeps.
     */
    fun post(session: OnboardingSession): Boolean {
        lastError = null
        // Another payment may be out: this one must not go, and that payment's guard must not be overwritten.
        if (unconfirmedSend != null) {
            lastError = EARLIER_UNSETTLED
            return false
        }
        val (prepared, network) = heldSend ?: return false
        heldSend = null
        // The guard reaches the phone before the post, so an app killed during the post still refuses this payment after the restart.
        // It keeps the signed bytes only when they are this txid's transaction. Without them the payment still goes, with no repost.
        val pending = UnconfirmedSend(prepared.txid, nowSeconds(), prepared.hex.takeIf { txidOf(it) == prepared.txid })
        when (val claim = session.recordUnconfirmedSend(pending.txid, pending.sinceSeconds, pending.hex)) {
            Claim.Written -> unconfirmedSend = pending
            is Claim.Taken -> {
                // Another live instance of this wallet, or a run before a restart, guarded a payment that may be out.
                unconfirmedSend = claim.earlier
                lastError = EARLIER_UNSETTLED
                return false
            }
            Claim.Damaged -> {
                // The file can no longer say whose payment may be out, so it may be this wallet's.
                unconfirmedSend = DamagedGuard
                lastError = EARLIER_UNSETTLED
                return false
            }
            Claim.NotSaved -> {
                lastError = GUARD_NOT_SAVED
                return false
            }
        }
        try {
            postHex(network, prepared.hex, prepared.txid)
        } catch (e: MobileSignerException.BroadcastUnknown) {
            // The guard stays. Plain Thai, because Home prints lastError raw until its next bind clears it, and the Rust text is English.
            lastError = "ยังไม่ทราบว่าการส่งออกไปแล้วหรือยัง อย่าส่งซ้ำ"
            return false
        } catch (e: MobileSignerException.InvalidPsbtJson) {
            // The node refused the transaction, or hot_broadcast refused before it posted anything: nothing is out and the guard goes.
            clearUnconfirmedSend(session, pending)
            lastError = sendErrorThai(e.message)
            return false
        } catch (e: Exception) {
            // Any other failure could have come after the post, so the guard stays and the payment reads as unknown.
            lastError = "ยังไม่ทราบว่าการส่งออกไปแล้วหรือยัง อย่าส่งซ้ำ"
            return false
        }
        clearUnconfirmedSend(session, pending)
        lastTxid = prepared.txid
        lastFeeSats = prepared.feeSats.toLong()
        lastAmountSats = prepared.amountSats.toLong()
        lastSentAtSeconds = nowSeconds()
        // Its own try: the payment is out once the post returns, and reading it as failed invites a retry that pays twice.
        try {
            show(snapshot(session.words, session.passphrase, network, session.addressType))
        } catch (e: Exception) {
        }
        return true
    }

    /**
     * The payment of [guard] is out or refused, the explorer lists it, or the user checked and found no such transaction. Clears that
     * guard on the phone and here, so a new payment can start, and only that guard. One that took its place meanwhile, a fee bump's or
     * a cancel's, stays here and on the phone, because its payment may be out. False, with the guard kept here and on the phone, when
     * the phone could not drop every copy of it ([OnboardingSession.clearUnconfirmedSend]).
     */
    fun clearUnconfirmedSend(session: OnboardingSession, guard: Unsettled): Boolean {
        if (!session.clearUnconfirmedSend(guard)) return false
        if (unconfirmedSend == guard) unconfirmedSend = null
        return true
    }

    /**
     * Posts the guarded payment again, from the signed bytes the guard file keeps with it. The same bytes are the same transaction, so
     * this can never pay twice and needs no wait. The screen asks the owner first, as it does before signing. [Repost.Out] drops the
     * guard here while the wallet still holds it, and on the phone unless another instance's repost of it still runs. The balance waits
     * for Home's next bind. The screen's draft of the payment goes in the same step ([com.mebit.app.feature.send.RepostAttempt.start]).
     * Every other end keeps the guard, a refusal included, because after a first post a refusal can mean the payment confirmed or that
     * another transaction spent its coins. [Repost.Failed], with nothing posted, when the phone no longer keeps this guard with its bytes.
     * A chain that holds a cancel keeps its guard on [Repost.Out] too, because the payment it cancels may still confirm.
     * Blocks, so call it off the main thread.
     */
    fun repost(session: OnboardingSession, network: KeyNetwork = KeyNetwork.TEST): Repost {
        val pending = unconfirmedSend as? UnconfirmedSend ?: return Repost.Failed
        val outcome = session.repostUnconfirmedSend(pending) { hex -> posted(network, hex, pending.txid) } ?: Repost.Failed
        if (outcome == Repost.Out && !pending.holdsCancel) {
            // Out is known, so the wallet lets go even while another instance's repost of these bytes keeps the phone's copy. A claim still
            // meets that copy until its repost ends out or a bind lists the txid. A replacement's guard that took this one's place stays.
            session.clearUnconfirmedSend(pending)
            if (unconfirmedSend == pending) unconfirmedSend = null
        }
        return outcome
    }

    /** Posts [hex], whose txid is [txid], and says how it ended. */
    private fun posted(network: KeyNetwork, hex: String, txid: String): Repost = try {
        postHex(network, hex, txid)
        Repost.Out
    } catch (e: MobileSignerException.BroadcastUnknown) {
        Repost.Unknown
    } catch (e: MobileSignerException.InvalidPsbtJson) {
        // "esplora post:" is the node's own answer. Every other refusal came before anything was posted.
        if (e.message.orEmpty().removePrefix("invalid PSBT json: ").startsWith("esplora post:")) Repost.Refused else Repost.Failed
    } catch (e: Exception) {
        Repost.Failed
    }

    /**
     * Signs a [kind] replacement of the guarded payment, at [feeRateSatVb] or the lowest rate the network takes for it, and holds it in
     * [heldReplacement] with nothing written or posted, so the screen can show what it pays and ask before [postReplacement]. It needs
     * the guard's signed bytes and a real key whose wallet is live with its balance known, as [prepare] does. A chain that holds a cancel
     * takes no fee bump, because that would pay the destination the cancel took back. False when any is missing, with a Thai [lastError]
     * when the core refused, and with no error when [dropHeldReplacement] ran while the core was signing. Blocks, so call it off the
     * main thread.
     */
    fun prepareReplacement(session: OnboardingSession, kind: Replacement, feeRateSatVb: Long, network: KeyNetwork = KeyNetwork.TEST): Boolean {
        lastError = null
        heldReplacement = null
        val epoch = replacementEpoch
        val pending = unconfirmedSend as? UnconfirmedSend ?: return false
        val hex = pending.hex ?: return false
        if (kind == Replacement.FeeBump && pending.holdsCancel) return false
        if (session.words.size < 12 || !(live && balanceKnown)) {
            lastError = "ยังดึงยอดคงเหลือไม่ได้ จึงยังส่งไม่ได้"
            return false
        }
        val sign = when (kind) {
            Replacement.FeeBump -> bumpAtRate
            Replacement.Cancel -> cancelAtRate
        }
        val prepared = try {
            sign(session.words, session.passphrase, network, session.addressType, hex, feeRateSatVb.toULong())
        } catch (e: Exception) {
            lastError = sendErrorThai(e.message)
            return false
        }
        if (epoch != replacementEpoch) return false
        heldReplacement = HeldReplacement(prepared, network, pending, kind, feeRateSatVb)
        return true
    }

    /** Lets the held replacement go without posting it, a [prepareReplacement] still running included. Nothing of it was written or posted. */
    fun dropHeldReplacement() {
        replacementEpoch++
        heldReplacement = null
    }

    /**
     * Posts the replacement [prepareReplacement] holds, once the user confirmed it. Its guard goes to the phone first. The file swaps the
     * guard it replaces for one that names the whole chain and keeps a cancel mark ([PendingPayments.swap]), so whichever payment of the
     * chain is out, the guard stays until a history settles it. Then the post.
     * [Repost.Out] of a fee bump drops the guard and records the bump as the last payment, as [post] does. A cancel that is out keeps
     * the guard, because until a block holds one transaction of the chain the payment it cancels may confirm, and it records nothing,
     * because nothing was paid to anyone. [Repost.Unknown] and [Repost.Refused] keep the replacement's guard, because an earlier payment
     * may be out. [Repost.Failed] posts nothing. That covers no held replacement, one that no longer replaces this wallet's guard, a file
     * that names another payment now, none or a damaged one, which the wallet then takes, and a file that could not take the guard.
     * Blocks, so call it off the main thread.
     */
    fun postReplacement(session: OnboardingSession): Repost {
        val held = heldReplacement ?: return Repost.Failed
        heldReplacement = null
        val earlier = held.replaces
        if ((unconfirmedSend as? UnconfirmedSend)?.txid != earlier.txid) return Repost.Failed
        val prepared = held.prepared
        val next = UnconfirmedSend(prepared.txid, nowSeconds(), prepared.hex, earlier.replaces + earlier.txid, earlier.holdsCancel || held.kind == Replacement.Cancel)
        when (val swap = session.replaceUnconfirmedSend(earlier, next)) {
            Swap.Written -> unconfirmedSend = next
            is Swap.Changed -> {
                unconfirmedSend = swap.now
                return Repost.Failed
            }
            Swap.NotSaved -> return Repost.Failed
        }
        val outcome = session.repostUnconfirmedSend(next) { hex -> posted(held.network, hex, next.txid) } ?: Repost.Failed
        if (outcome == Repost.Out && !next.holdsCancel) {
            clearUnconfirmedSend(session, next)
            lastTxid = prepared.txid
            lastFeeSats = prepared.feeSats.toLong()
            lastAmountSats = prepared.amountSats.toLong()
            lastSentAtSeconds = nowSeconds()
        }
        return outcome
    }

    /** Whether the screen may offer the clear control now: by [offersUnconfirmedClear] for a payment the guard names, and at once for a damaged guard, which has no post time to wait on. False without a guard. */
    fun unconfirmedClearOffered(): Boolean = when (val pending = unconfirmedSend) {
        null -> false
        DamagedGuard -> true
        is UnconfirmedSend -> offersUnconfirmedClear(pending.sinceSeconds, nowSeconds())
    }

    /** Offline fixture for catalog / no-seed CI. */
    fun useDemoFixture() {
        mode = WalletMode.Demo
        scan = HotScan.COMPLETE
        balanceSats = 1_250_000L
        spendableSats = 1_250_000L
        receiveAddress = "tb1qmebitdemosinglesig0000000000000000recv"
        lastTxid = ""
        lastFeeSats = 0L
        lastAmountSats = 0L
        lastSentAtSeconds = 0L
        history = emptyList()
        unconfirmedSend = null
        heldEpoch++
        heldSend = null
        dropHeldReplacement()
    }
}

/** What replaces a payment of unknown outcome. Each spends every coin of the payment it replaces, so at most one of them can confirm. */
enum class Replacement {
    /** The same destination and amount at a higher fee. */
    FeeBump,

    /** Every coin back to this wallet, at a higher fee. Only a block settles it, because until then the payment it cancels may confirm. */
    Cancel,
}

/** How a [SingleSigWallet.repost] or a [SingleSigWallet.postReplacement] ended. Only [Out] drops the guard, and not one whose chain holds a cancel. */
enum class Repost {
    /** The node took the bytes, or answered that it already holds or has confirmed them, so the payment is out. */
    Out,

    /** The answer was lost again and the explorer does not list the txid. */
    Unknown,

    /** The node refused the bytes. The payment may have confirmed already, or another transaction may have spent its coins. */
    Refused,

    /** Anything else, nothing posted included. */
    Failed,
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
    // The floor depends on the output's script, so the line takes it from the Rust text and keeps no copy of the floors.
    val floor = Regex("""network's (\d+) sat dust floor""").find(m)?.groupValues?.get(1)
    return when {
        m.startsWith("amount ") && floor != null -> "เครือข่ายไม่รับยอดส่งที่ต่ำกว่า $floor sat สำหรับที่อยู่ปลายทางนี้"
        m.startsWith("insufficient funds") -> "ยอดที่ยืนยันแล้วไม่พอ รอให้เงินที่เพิ่งได้รับยืนยันก่อน"
        // hot_prepare_send_at_rate, hot_prepare_send_all and, after "replace: ", hot_prepare_fee_bump: the coins need more than the network's 100,000 vB in one transaction.
        m.removePrefix("replace: ").startsWith("too many coins") -> "ต้องรวมเหรียญเกินขนาดที่เครือข่ายรับในธุรกรรมเดียว (100,000 vB) ส่งจำนวนที่น้อยลง"
        // hot_prepare_send_all: the balance less the real fee would be below the destination's floor.
        m.startsWith("the whole balance ") && floor != null -> "ยอดทั้งหมดหลังหักค่าธรรมเนียมต่ำกว่า $floor sat ซึ่งเครือข่ายไม่รับสำหรับที่อยู่ปลายทางนี้"
        m.startsWith("fee ") && " is below " in m -> "ค่าธรรมเนียมต่ำเกินไปสำหรับธุรกรรมนี้ เลือกระดับที่สูงขึ้น"
        // The rate asked, or the fee on the signed bytes, above the 10,000 sat/vB the node takes.
        m.startsWith("fee ") && " is above " in m -> "เครือข่ายไม่รับค่าธรรมเนียมเกิน 10,000 sat/vB เลือกระดับที่ต่ำลง"
        // hot_prepare_fee_bump and hot_prepare_cancel. A spent coin most likely means the earlier payment is out.
        m.startsWith("replace: coins spent:") -> "เหรียญของรายการเดิมถูกใช้ไปแล้ว รายการเดิมอาจออกไปแล้ว ตรวจประวัติในหน้าแรกก่อน"
        m.startsWith("replace: not this wallet's payment:") -> "รายการเดิมไม่ใช่รายการที่กระเป๋านี้แทนที่ได้"
        m.startsWith("replace: rate ") || m.startsWith("replace: fee ") -> "รายการใหม่ต้องใช้ค่าธรรมเนียมเกิน 10,000 sat/vB ซึ่งเครือข่ายไม่รับ"
        m.startsWith("replace: what is left") && floor != null -> "ยอดที่เหลือหลังหักค่าธรรมเนียมใหม่ต่ำกว่า $floor sat ซึ่งเครือข่ายไม่รับ"
        m.startsWith("esplora scan:") -> ABNORMAL_HISTORY
        m.startsWith("esplora utxo:") || m.startsWith("esplora balance:") -> "ข้อมูลจาก explorer ไม่ตรงกัน ลองใหม่ภายหลัง"
        m == "amount plus fee does not fit u64" -> "จำนวนผิดปกติ"
        m.startsWith("broadcast:") || m.startsWith("esplora post:") -> "ส่งไม่สำเร็จ"
        // A host refused a read or a probe with HTTP 429, its request limit, or every host is still under one (chain.rs limited_text).
        m.startsWith("esplora get") && "429" in m -> "explorer จำกัดจำนวนการเรียกชั่วคราว รอสักครู่แล้วลองใหม่"
        m.startsWith("esplora get") -> "ติดต่อ explorer ไม่ได้ ลองใหม่ภายหลัง"
        else -> SEND_FALLBACK
    }
}

private const val SEND_FALLBACK = "ส่งไม่สำเร็จ ลองใหม่ภายหลัง"

/** The line for a host that lied about the wallet's history, on a payment it refused and on Home for a snapshot whose scan is [HotScan.UNVERIFIED]. */
private const val ABNORMAL_HISTORY = "ประวัติของกระเป๋านี้ผิดปกติ ตรวจกับ explorer ก่อนส่ง"

/**
 * Whether screens may show and spend the wallet's figures. Never a live wallet's until its scan reached the end of the wallet,
 * so a sum of part of the wallet never reads as its balance and makes the user think money is missing.
 */
fun figuresKnown(mode: SingleSigWallet.WalletMode, scan: HotScan): Boolean = when (mode) {
    SingleSigWallet.WalletMode.Demo -> true
    SingleSigWallet.WalletMode.Unavailable -> false
    SingleSigWallet.WalletMode.Live -> scan == HotScan.COMPLETE
}

/** Home's red line for a snapshot whose scan a host lied in, or null. */
fun scanErrorThai(scan: HotScan): String? = ABNORMAL_HISTORY.takeIf { scan == HotScan.UNVERIFIED }

/** [SingleSigWallet.prepare] and [SingleSigWallet.post] while an earlier payment may be out. */
private const val EARLIER_UNSETTLED = "การส่งครั้งก่อนยังยืนยันไม่ได้ จึงยังส่งใหม่ไม่ได้"

/** [SingleSigWallet.post] when the guard file could not take the payment's guard ([Claim.NotSaved]), so nothing was posted. Review shows it too. */
internal const val GUARD_NOT_SAVED = "บันทึกสถานะการส่งไม่ได้ จึงยังไม่ส่ง ลองใหม่ภายหลัง"

/** [sendErrorThai] for a failed read of the wallet: its generic fallback would say a send failed when only a fetch did. */
fun readErrorThai(message: String?): String =
    sendErrorThai(message).takeUnless { it == SEND_FALLBACK } ?: "ดึงข้อมูลกระเป๋าไม่สำเร็จ ลองใหม่ภายหลัง"

/** A typical 1-input, 2-output P2WPKH payment. The screens show rate times this as the estimate; the core charges rate times the real size. */
const val TYPICAL_VSIZE = 141L

/**
 * An absolute surprise worth one tap: a fee this many sats over the estimate is questioned however large the payment, about 30 baht
 * at today's price. Below it, a fee over twice the estimate is questioned only when it also eats a tenth of the amount.
 */
const val FEE_SURPRISE_SATS = 1_000L

/**
 * Whether the Review screen asks before posting a payment whose real fee is [feeSats] when it showed [shownFeeSats] (the rate times
 * [TYPICAL_VSIZE]) and [amountSats] is what the destination gets. The fee must be more than twice what was shown, and either more
 * than a tenth of the amount or at least [FEE_SURPRISE_SATS] over the estimate. Twice alone would stop for an ordinary four-coin
 * payment (345 sat on 10,000 sat at 1 sat/vB, 204 sat over), which is not worth a tap. A tenth alone would ask about every small
 * payment, whose fee is a large share by nature even when it is exactly what was shown, and would let a fee hundreds of times the
 * estimate through on a large payment, which the absolute threshold catches.
 */
fun feeNeedsConfirmation(feeSats: Long, shownFeeSats: Long, amountSats: Long): Boolean =
    feeSats > 2 * shownFeeSats && (feeSats * 10 > amountSats || feeSats - shownFeeSats >= FEE_SURPRISE_SATS)

/** Review asks above this rate. The core refuses only above the network's 10,000 sat/vB. */
const val HIGH_FEE_RATE = 2_000L

/**
 * One reason the Review screen asks before posting the payment the core signed. Declared in title order, the most money at stake
 * first, because the first reason that holds titles the card. Draft copy awaiting design review (open items 77, 85 and 86).
 */
enum class FeeReason(val title: String) {
    /** The tier's rate is above [HIGH_FEE_RATE]. The network takes up to 10,000 sat/vB. */
    HighRate("อัตราค่าธรรมเนียมสูงกว่า 2,000 sat/vB"),

    /** A fixed amount whose fee is more than the amount. The fee is paid on top, so the destination still gets all of it. */
    AboveAmount("ค่าธรรมเนียมสูงกว่าจำนวนที่ส่ง"),

    /** A fixed amount whose real fee is far above the estimate Review showed ([feeNeedsConfirmation]). */
    AboveEstimate("ค่าธรรมเนียมจริงสูงกว่าที่แสดง"),

    /** Send all whose real fee is far above the estimate, or whose amount is [FEE_SURPRISE_SATS] or more under what Review showed. */
    SendAllBelowShown("ค่าธรรมเนียมจริงสูงกว่าที่แสดง"),

    /** Change the network refuses as an output (below its dust floor), or too little to pay for its own, went into the fee ([PreparedSend.dustChangeSats]). */
    ChangeToFee("เงินทอนรวมเข้าค่าธรรมเนียม"),
}

/**
 * The one card Review shows for [reasons], never empty: titled by the first, with one line per reason and one closing line, so any
 * combination reads true. [rateSatVb] is the rate asked, [dustChangeSats] the change that went into the fee, and [sendAll] names the
 * amount as what leaves for real. The rows and tags of the card are the same for every reason.
 */
data class FeeQuestion(val reasons: List<FeeReason>, val rateSatVb: Long, val dustChangeSats: Long, val sendAll: Boolean) {
    val title: String get() = reasons.first().title
    val message: String get() = (reasons.map { line(it) } + "ตรวจค่าธรรมเนียมก่อนส่ง").joinToString(" ")
    val amountLabel: String get() = if (sendAll) "ส่งจริง" else "ส่ง"

    private fun line(reason: FeeReason): String = when (reason) {
        FeeReason.HighRate -> "อัตรา ${thousands(rateSatVb)} sat/vB สูงกว่าปกติมาก ค่าธรรมเนียมจึงสูง"
        FeeReason.AboveAmount -> "ผู้รับได้ครบตามจำนวน แต่ค่าธรรมเนียมเครือข่ายมากกว่าเงินที่ส่ง"
        FeeReason.AboveEstimate -> "กระเป๋านี้ต้องรวมหลายเหรียญ ธุรกรรมจึงใหญ่กว่าปกติ"
        FeeReason.SendAllBelowShown -> "กระเป๋านี้ต้องรวมหลายเหรียญ ธุรกรรมจึงใหญ่กว่าปกติ จำนวนที่ส่งลดลงจากที่แสดงเพราะหักค่าธรรมเนียมจริงแล้ว"
        FeeReason.ChangeToFee -> "เศษ ${thousands(dustChangeSats)} sat ทอนกลับเข้ากระเป๋าไม่ได้ จึงรวมเข้าในค่าธรรมเนียม"
    }
}

/**
 * Why the Review screen asks before posting [held], the payment the core signed for [draft], or null when it posts at once. A fixed
 * amount asks when [feeNeedsConfirmation] holds against the estimate Review showed and when the fee is more than the amount. Send all
 * asks on the first, and when what leaves is [FEE_SURPRISE_SATS] or more under the amount it showed, because the amount absorbs the fee
 * and the spendable balance can have moved since the snapshot. Send all never asks about the fee against the amount, which the core never refused.
 * Change that went into the fee asks with its own reason, and the estimate rule judges the fee without it, so it never reads as several coins.
 */
fun feeQuestion(held: PreparedSend, draft: SendDraft): FeeQuestion? {
    val feeSats = held.feeSats.toLong()
    val amountSats = held.amountSats.toLong()
    val dustSats = held.dustChangeSats.toLong()
    val aboveEstimate = feeNeedsConfirmation(feeSats - dustSats, draft.feeSats, amountSats)
    val reasons = listOfNotNull(
        FeeReason.HighRate.takeIf { draft.rateSatVb > HIGH_FEE_RATE },
        FeeReason.AboveAmount.takeIf { !draft.isSendAll && feeSats > amountSats },
        FeeReason.AboveEstimate.takeIf { !draft.isSendAll && aboveEstimate },
        FeeReason.SendAllBelowShown.takeIf { draft.isSendAll && (aboveEstimate || draft.amountSats - amountSats >= FEE_SURPRISE_SATS) },
        FeeReason.ChangeToFee.takeIf { dustSats > 0 },
    )
    return if (reasons.isEmpty()) null else FeeQuestion(reasons, draft.rateSatVb, dustSats, draft.isSendAll)
}

/** What the card of a signed fee bump or cancel says about its fee beyond the fee row, or null: a rate above [HIGH_FEE_RATE] and the change it put into the fee. */
fun replacementQuestion(held: SingleSigWallet.HeldReplacement): FeeQuestion? {
    val dustSats = held.prepared.dustChangeSats.toLong()
    // shortcut: the line follows the rate asked (the fast tier), so a rate that BIP-125 rule 6 raised past 2,000 shows no line. Upgrade if PreparedSend ever carries the rate paid.
    val reasons = listOfNotNull(FeeReason.HighRate.takeIf { held.rateSatVb > HIGH_FEE_RATE }, FeeReason.ChangeToFee.takeIf { dustSats > 0 })
    return if (reasons.isEmpty()) null else FeeQuestion(reasons, held.rateSatVb, dustSats, sendAll = false)
}

/**
 * The line the card of a signed fee bump shows under its amount when the new fee came out of that amount ([PreparedSend.amountCutSats]),
 * or null. Draft copy awaiting design review (open item 86).
 */
fun amountCutLine(held: SingleSigWallet.HeldReplacement): String? =
    held.prepared.amountCutSats.toLong().takeIf { it > 0 }?.let { "ผู้รับจะได้น้อยกว่ารายการเดิม ${thousands(it)} sat เพราะค่าธรรมเนียมใหม่หักจากจำนวนที่ส่ง" }

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

        /** The Rust answer as the screens use it. Rust already caps a rate at 9,900, 1% under the network's 10,000, and never answers 0. */
        fun of(tiers: FeeTiers) = LiveFeeTiers(tiers.slowSatVb.toLong(), tiers.normalSatVb.toLong(), tiers.fastSatVb.toLong(), tiers.live)
    }
}

/** One fee tile: the tier's board copy, its rate and the estimate in sats. */
data class FeeTile(val tier: FeeTier, val satPerVb: Long, val feeSats: Long)

/** What the detail screen knows beyond its row. [SingleSigWallet.detail] answers only [Done], and [Loading] is the screen's own. */
sealed interface TxDetailLoad {
    data object Loading : TxDetailLoad

    sealed interface Done : TxDetailLoad

    data class Loaded(val detail: HotTxDetail) : Done

    /** The explorer does not know the txid now, because it was replaced, dropped, or not yet seen by this host. */
    data object NotFound : Done

    /** [rustText] is the core's message. The screen words it with [readErrorThai], and a debug build shows it raw. */
    data class Failed(val rustText: String) : Done

    /** No key, so the demo asks nothing and the row is all there is. */
    data object RowOnly : Done
}

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
        sendAllMarked = false
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

/**
 * One send from one vault. [resetSignatures] binds the vault being spent, and [markSigned] keeps a fingerprint only when it is a
 * key of that vault, once, so [signedFingerprints] is the set of distinct vault signers and [readyToBroadcast] compares its size
 * with the vault's own `m`. Nothing a screen passes in can lower the threshold or count a key from outside the vault.
 */
class MultisigSendDraft {
    var amountSats: Long by mutableStateOf(100_000L)
    var destination: String by mutableStateOf("tb1qvaultdestinationdemo000000000000000")
    var vault: CreatedVault? by mutableStateOf(null)
        private set
    private val signed = mutableStateListOf<String>()
    /** Uppercase master fingerprints of the vault keys that have signed this send, each once. */
    val signedFingerprints: List<String> get() = signed
    var psbtBase64: String by mutableStateOf("")
        private set
    var lastError: String? by mutableStateOf(null)
        private set
    var broadcastTxid: String by mutableStateOf("")
        private set

    fun resetSignatures(vault: CreatedVault) {
        this.vault = vault
        signed.clear()
        broadcastTxid = ""
        lastError = null
        // Placeholder PSBT body until vault PSBT builder lands; HW sign still goes through WalletLink.
        psbtBase64 = "cHNidP8BAF4CAAAAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/////wAAAAA="
    }

    /** Records that a vault key signed. False, with [lastError], when no send is started or [fingerprint] is not a key of its vault. */
    fun markSigned(fingerprint: String): Boolean {
        val fp = fingerprint.uppercase()
        val keys = vault?.keyFingerprints.orEmpty()
        if (keys.none { it.equals(fp, ignoreCase = true) }) {
            lastError = "กุญแจนี้ไม่ใช่กุญแจของ Vault นี้"
            return false
        }
        if (fp !in signed) signed.add(fp)
        return true
    }

    fun setSignedPsbt(base64: String) {
        psbtBase64 = base64
    }

    fun signaturesNeeded(): Int = ((vault?.m ?: 0) - signed.size).coerceAtLeast(0)

    fun readyToBroadcast(): Boolean = vault?.let { signed.size >= it.m } ?: false

    /** Destination + amount gate before HW sign (vault-core real-PSBT policy is Term-2). */
    fun policyAllowsSign(): Boolean {
        if (destination.isBlank() || amountSats <= 0L) {
            lastError = "ปลายทางหรือจำนวนไม่ถูกต้อง"
            return false
        }
        return true
    }

    /** Stand-in for the network broadcast: it only mints a txid. A send that already has one is not broadcast again. */
    fun broadcastMock(): Boolean {
        if (!readyToBroadcast()) {
            lastError = "ลายเซ็นยังไม่ครบ"
            return false
        }
        if (broadcastTxid.isNotBlank()) {
            lastError = "ส่งธุรกรรมนี้ไปแล้ว"
            return false
        }
        broadcastTxid = "mockvaulttx_${signed.size}_${amountSats.toString(16)}"
        return true
    }
}
