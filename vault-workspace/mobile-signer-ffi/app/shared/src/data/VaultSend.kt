package com.mebit.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mebit.app.data.SingleSigWallet.DamagedGuard
import com.mebit.app.data.SingleSigWallet.UnconfirmedSend
import com.mebit.app.data.SingleSigWallet.Unsettled
import com.mebit.app.sha256
import com.mebit.ffi.signer.HotScan
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.MobileSignerException
import com.mebit.ffi.signer.SpendReview
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

/**
 * One several-key payment as Rust's latest review of its PSBT reads it, with [destination] as the user typed it on 5.1. Made only by
 * [VaultSend], from a [SpendReview] of its wallet. It pays exactly one output that is not the wallet's change, to the typed destination
 * or its lowercase, so 5.2, 5.8 and 5.9 never show an address the PSBT does not pay. wallet_draft pays only the typed destination and
 * every later answer keeps its unsigned txid, so this throws only for an answer that broke those rules, and the call that got it keeps nothing.
 */
class VaultPayment internal constructor(internal val review: SpendReview, val destination: String) {
    private val paid = review.payments.single()

    init {
        require(paid.address == destination || paid.address == destination.lowercase()) { "the payment is not to the destination typed" }
    }

    val amountSats: ULong get() = paid.sats
    val changeSats: ULong get() = review.changeSats
    val feeSats: ULong get() = review.feeSats

    /** The unsigned txid, which is the sent transaction's id for the wallet's P2WSH inputs: 5.9's slip and the guard line name it. */
    val txid: String get() = review.unsignedTxid

    /** The master fingerprints Rust found with a valid signature on every input, uppercase, as the book names keys. */
    val signed: Set<String> = review.signed_.map { it.uppercase() }.toSet()
}

/** Where one send from a several-key wallet stands. A new send is a new [VaultSend]. */
sealed interface VaultSendState {
    /** Penpot 5.1's form, with nothing prepared. */
    data object Form : VaultSendState

    /**
     * Penpot 5.2: [payment] is what the user reviews and keys sign, and its [VaultPayment.signed] is the only record of who signed.
     * [stale] once the node refused it, because these bytes can never go out, so 5.1 prepares another.
     */
    data class Collecting(val payment: VaultPayment, val stale: Boolean = false) : VaultSendState

    /** The node took [payment]'s transaction, which the review still draws while it leaves, and the phone learned so at [atSeconds], 5.9's date. */
    data class Sent(val payment: VaultPayment, val atSeconds: Long) : VaultSendState
}

/** What a [VaultSend] call is doing. One runs at a time, and the screens hold every button while one runs. */
sealed interface VaultBusy {
    data object Preparing : VaultBusy

    data class Signing(val keyIndex: Int) : VaultBusy

    data object Posting : VaultBusy
}

/**
 * One send from [vault], fixed for its life, so no screen can change the wallet under a prepared payment or count another wallet's key.
 * [draft] is 5.1's form, priced at the wallet's own size. [prepare] makes the unsigned payment, each [sign] adds one key's signature through
 * Rust, and [broadcast] finishes it and posts it behind the wallet's pay-twice guard in [guards]. Who signed is what Rust read from the PSBT
 * it returned, never a list kept here. Every Rust call goes through [core] with the wallet's BSMS line 2, so tests need no native library,
 * and runs under one lock, so two signs started together each build on the PSBT the other returned. [sign] runs Rust on [worker] after
 * the owner check.
 */
class VaultSend internal constructor(
    val vault: CreatedVault,
    private val guards: PendingPayments,
    private val nowSeconds: () -> Long = { Clock.System.now().epochSeconds },
    private val worker: CoroutineContext = Dispatchers.Default,
    private val core: DescribedCore = DescribedCore(),
) {
    private val descriptor = descriptorIn(vault.bsms)

    /** The book's key fingerprints, uppercase, in BSMS order: what a row is, and the only fingerprints that count. */
    private val fingerprints = vault.keyFingerprints.map { it.uppercase() }
    private val guardKey = vaultGuardKey(vault)
    private val lock = SynchronizedObject()

    /**
     * 5.1's form, blank at the normal tier and priced at the wallet's own size, 201 vB for a 2-of-3. Throws for a wallet the core cannot
     * read, and for one it reads as another than the book's: another m, other fingerprints or another order of them, or another receive
     * address 0. So the guard, made from that address, the rows, made from those fingerprints, and every Rust call name one wallet.
     */
    val draft: SendDraft = core.describe(descriptor, KeyNetwork.TEST).let { shape ->
        require(shape.needed.toInt() == vault.m && shape.keys.map { it.fingerprint.uppercase() } == fingerprints && shape.firstAddress == vault.address) {
            "the core reads another wallet than the book's"
        }
        SendDraft(shape.typicalVsize.toLong())
    }

    var state: VaultSendState by mutableStateOf(VaultSendState.Form)
        private set

    /** When this phone signed the payment held, in epoch seconds by uppercase fingerprint: the time on 5.2's rows, which counts no signer. */
    var signedAt: Map<String, Long> by mutableStateOf(emptyMap())
        private set

    var busy: VaultBusy? by mutableStateOf(null)
        private set

    /** Thai: why the last call did not go through, or null. */
    var lastError: String? by mutableStateOf(null)
        private set

    /** This wallet's payment that may be out, as the guard file last said. While it is set no new payment starts and 5.1 says why. */
    var guard: Unsettled? by mutableStateOf(null)
        private set

    /** What a payment could spend, null until [load] read a scan that reached the end of both chains. 5.1's ใช้ได้ and ส่งทั้งหมด. */
    var spendableSats: Long? by mutableStateOf(null)
        private set

    /** The payment 5.2 and the review show: the one held for signatures, or the one that went out. */
    val payment: VaultPayment?
        get() = when (val now = state) {
            is VaultSendState.Collecting -> now.payment
            is VaultSendState.Sent -> now.payment
            VaultSendState.Form -> null
        }

    /** The uppercase fingerprints of the wallet's keys that Rust found signed on every input of [payment], each once. Any other counts nothing. */
    val signedFingerprints: Set<String>
        get() = payment?.signed.orEmpty() intersect fingerprints.toSet()

    /** 5.2's "ต้องเซ็นอีก k ดอก". */
    val signaturesNeeded: Int get() = (vault.m - signedFingerprints.size).coerceAtLeast(0)

    /** The organize-vault-send property: a payment the node may still take, signed by at least m distinct fingerprints of this wallet. */
    fun readyToBroadcast(): Boolean = (state as? VaultSendState.Collecting)?.stale == false && signedFingerprints.size >= vault.m

    /** Whether key [index]'s signature would still count toward m: fewer than m distinct fingerprints signed, none of them that key's. */
    fun needsKey(index: Int): Boolean {
        val fingerprint = vault.keyFingerprints.getOrNull(index)?.uppercase() ?: return false
        return signedFingerprints.size < vault.m && fingerprint !in signedFingerprints
    }

    /** 5.1's read on open: the guard file, what the wallet can spend, and the fee tiers, the board's fixed ones when the fetch fails. Blocks. */
    fun load(): LiveFeeTiers {
        guard = guards.of(guardKey)
        spendableSats = try {
            core.snapshot(descriptor, KeyNetwork.TEST).takeIf { it.scan == HotScan.COMPLETE }?.spendableSats?.toLong()
        } catch (e: Exception) {
            null
        }
        return try {
            LiveFeeTiers.of(core.feeTiers(KeyNetwork.TEST))
        } catch (e: Exception) {
            LiveFeeTiers.DEFAULT
        }
    }

    /**
     * 5.1's ตรวจสอบ: the unsigned payment of [draft]'s request, held for 5.2 with no signature. A payment held before goes with its
     * signatures, which signed other bytes. Refused while the guard file holds a payment of this wallet. False with [lastError], and
     * whatever was held stays held. Blocks.
     */
    fun prepare(): Boolean = synchronized(lock) {
        lastError = null
        guard = guards.of(guardKey)
        if (guard != null) return@synchronized fail(EARLIER_UNSETTLED)
        val request = draft.request
        busy = VaultBusy.Preparing
        try {
            val amount = request.amountSats.toULong().takeUnless { request.sendAll }
            val review = core.draft(descriptor, KeyNetwork.TEST, request.destination, amount, request.rateSatVb.toULong())
            state = VaultSendState.Collecting(VaultPayment(review, request.destination))
            signedAt = emptyMap()
            true
        } catch (e: Exception) {
            fail(sendErrorThai(e.message))
        } finally {
            busy = null
        }
    }

    /**
     * 5.2's เซ็น on a row this phone signs. Asks the owner first ([confirm], once per signature), then Rust on [worker]. Keeps Rust's
     * answer only when it is the same payment and names the fingerprint of [signer]'s row among the signers, so a key counts only once
     * Rust returned a PSBT that carries its valid signature. Rust signs with whichever key of the wallet the words make, so words of
     * another row's key keep nothing. False, with Rust never asked, when the owner check fails. False, with neither asked, once the
     * payment no longer [needsKey] [signer]'s key, checked again under the lock because another sign can end during the owner check.
     */
    suspend fun sign(signer: KeySigner.Phone, confirm: suspend () -> Boolean): Boolean {
        if (heldFor(signer) == null) return false
        if (!confirm()) return fail(OWNER_NOT_CONFIRMED)
        return withContext(worker) {
            synchronized(lock) {
                val held = heldFor(signer) ?: return@synchronized false
                lastError = null
                busy = VaultBusy.Signing(signer.index)
                try {
                    val key = fingerprints[signer.index]
                    val next = VaultPayment(core.signWithWords(descriptor, KeyNetwork.TEST, held.review.psbt, held.txid, signer.words, signer.passphrase), held.destination)
                    if (next.txid != held.txid || key !in next.signed) {
                        fail(NOT_SIGNED)
                    } else {
                        state = VaultSendState.Collecting(next)
                        signedAt = signedAt + (key to nowSeconds())
                        true
                    }
                } catch (e: Exception) {
                    fail(sendErrorThai(e.message))
                } finally {
                    busy = null
                }
            }
        }
    }

    /**
     * The review's ส่งธุรกรรม, only while [readyToBroadcast]. Rust finishes the transaction, and its guard reaches the phone before the post,
     * as a one-key [SingleSigWallet.post] does. A finished transaction of another txid than the one 5.2 showed is neither guarded nor posted.
     * A guard that already names this txid means these same bytes may be out, so they go again, which can never pay twice, and any other
     * guard refuses. Out: the guard goes and [state] is [VaultSendState.Sent]. An unknown outcome keeps the guard. The node's refusal of a
     * first post drops the guard and makes the payment stale. Blocks.
     */
    fun broadcast(): Boolean = synchronized(lock) {
        lastError = null
        if (state is VaultSendState.Sent) return@synchronized fail(ALREADY_SENT)
        val held = (state as? VaultSendState.Collecting)?.payment
        if (held == null || !readyToBroadcast()) return@synchronized fail(NOT_ENOUGH_SIGNATURES)
        busy = VaultBusy.Posting
        try {
            val ready = core.finish(descriptor, KeyNetwork.TEST, held.review.psbt, held.txid)
            if (ready.txid != held.txid) return@synchronized fail(PAYMENT_CHANGED)
            val pending = UnconfirmedSend(ready.txid, nowSeconds(), ready.hex.takeIf { txidOf(it) == ready.txid })
            when (val claim = guards.claim(guardKey, pending)) {
                Claim.Written -> firstPost(held, pending, ready.hex)
                is Claim.Taken -> if (claim.earlier.txid == ready.txid) repostSame(held, claim.earlier) else refused(claim.earlier)
                Claim.Damaged -> refused(DamagedGuard)
                Claim.NotSaved -> fail(GUARD_NOT_SAVED)
            }
        } catch (e: Exception) {
            fail(sendErrorThai(e.message))
        } finally {
            busy = null
        }
    }

    /**
     * 5.1's way out of a payment of unknown outcome: posts the guard's own signed bytes again ([PendingPayments.repost]), which can never pay
     * twice. Out drops the guard. Every other end keeps it, a refusal included, because the payment may have confirmed. The screen asks the
     * owner first. Blocks.
     */
    fun repostGuarded(): Repost = synchronized(lock) {
        val earlier = guard as? UnconfirmedSend ?: return@synchronized Repost.Failed
        busy = VaultBusy.Posting
        try {
            val outcome = guards.repost(guardKey, earlier) { hex -> posted(hex, earlier.txid) } ?: Repost.Failed
            if (outcome == Repost.Out && guards.settle(guardKey, earlier)) guard = null
            outcome
        } finally {
            busy = null
        }
    }

    /** Whether 5.1 offers the clear: at once for a damaged guard file, else once the guard is old enough by [offersUnconfirmedClear]. */
    fun clearOffered(): Boolean = when (val pending = guard) {
        null -> false
        DamagedGuard -> true
        is UnconfirmedSend -> offersUnconfirmedClear(pending.sinceSeconds, nowSeconds())
    }

    /**
     * The user checked the explorer and found no such transaction: drops the guard here and in the file, so a payment can start. A damaged
     * file gives way to a damaged line for each of [others], the guard keys of the phone's other several-key wallets, since any of them may
     * have a payment out. False, with the guard kept, when the phone could not drop it.
     */
    fun clearGuard(others: List<String>): Boolean = synchronized(lock) {
        val pending = guard ?: return@synchronized true
        if (!guards.settle(guardKey, pending, others)) return@synchronized false
        guard = null
        true
    }

    /** The payment [signer] may sign now: held, one the node may still take, and still needing [signer]'s key. */
    private fun heldFor(signer: KeySigner.Phone): VaultPayment? =
        (state as? VaultSendState.Collecting)?.takeIf { !it.stale && needsKey(signer.index) }?.payment

    private fun firstPost(held: VaultPayment, pending: UnconfirmedSend, hex: String): Boolean = try {
        core.postHex(KeyNetwork.TEST, hex, pending.txid)
        out(held, pending)
    } catch (e: MobileSignerException.BroadcastUnknown) {
        unknown(pending)
    } catch (e: MobileSignerException.InvalidPsbtJson) {
        // Nothing is out: the node refused these bytes, or the core refused before it posted anything.
        guards.settle(guardKey, pending)
        if (refusedByNode(e)) state = VaultSendState.Collecting(held, stale = true)
        fail(sendErrorThai(e.message))
    } catch (e: Exception) {
        unknown(pending)
    }

    private fun repostSame(held: VaultPayment, earlier: UnconfirmedSend): Boolean =
        when (guards.repost(guardKey, earlier) { hex -> posted(hex, earlier.txid) } ?: Repost.Failed) {
            Repost.Out -> out(held, earlier)
            Repost.Unknown -> unknown(earlier)
            Repost.Refused, Repost.Failed -> {
                guard = earlier
                fail(REPOST_NOT_TAKEN)
            }
        }

    private fun out(held: VaultPayment, sent: UnconfirmedSend): Boolean {
        guards.settle(guardKey, sent)
        guard = null
        state = VaultSendState.Sent(held, nowSeconds())
        return true
    }

    private fun unknown(pending: UnconfirmedSend): Boolean {
        guard = pending
        return fail(SEND_UNKNOWN)
    }

    private fun refused(earlier: Unsettled): Boolean {
        guard = earlier
        return fail(EARLIER_UNSETTLED)
    }

    /** Posts [hex], whose txid is [txid], and says how it ended, as [SingleSigWallet.repost] reads the core's answers. */
    private fun posted(hex: String, txid: String): Repost = try {
        core.postHex(KeyNetwork.TEST, hex, txid)
        Repost.Out
    } catch (e: MobileSignerException.BroadcastUnknown) {
        Repost.Unknown
    } catch (e: MobileSignerException.InvalidPsbtJson) {
        if (refusedByNode(e)) Repost.Refused else Repost.Failed
    } catch (e: Exception) {
        Repost.Failed
    }

    private fun refusedByNode(e: MobileSignerException.InvalidPsbtJson): Boolean =
        e.message.orEmpty().removePrefix("invalid PSBT json: ").startsWith("esplora post:")

    private fun fail(line: String): Boolean {
        lastError = line
        return false
    }

    private companion object {
        const val OWNER_NOT_CONFIRMED = "ยืนยันตัวตนไม่สำเร็จ ลองอีกครั้ง · ยังไม่มีอะไรถูกเซ็น"
        const val NOT_ENOUGH_SIGNATURES = "ลายเซ็นยังไม่ครบ"
        const val ALREADY_SENT = "ส่งธุรกรรมนี้ไปแล้ว"
        const val SEND_UNKNOWN = "ยังไม่ทราบว่าการส่งออกไปแล้วหรือยัง อย่าส่งซ้ำ"
        const val REPOST_NOT_TAKEN = "เครือข่ายไม่รับรายการเดิม อาจยืนยันไปแล้ว ตรวจรายการของ Wallet หรือใน explorer"
    }
}

/** Who can sign vault key [index] on this phone. Made only by [keySigners], so a [VaultSend.sign] always names a key the rule allowed. */
sealed interface KeySigner {
    val index: Int

    /** A wallet whose words are in memory and whose key is vault key [index]: its [words] and [passphrase] sign. */
    class Phone internal constructor(override val index: Int, val words: List<String>, val passphrase: String) : KeySigner

    /** Nothing on this phone can sign key [index] now. [reason] is the row's short line, draft copy no board draws. */
    class Elsewhere internal constructor(override val index: Int, val reason: String) : KeySigner
}

/**
 * One rule for every row of 5.2, so only a key whose words are in memory signs: vault key i is [KeySigner.Phone] when a wallet of [listed] ([Wallets.listed]: past setup,
 * words in memory) has fingerprint == keyFingerprints[i], ignoring case, and xpub == xpubsIn(bsms)[i], the pair [vaultKeyRows] names a key by.
 * Otherwise [KeySigner.Elsewhere] with why, from the [keyring] entry that names the key: a stand-in, a hardware key, another phone's, or none.
 */
internal fun keySigners(vault: CreatedVault, listed: List<OnboardingSession>, keyring: List<SavedKey>): List<KeySigner> {
    val xpubs = xpubsIn(vault.bsms)
    return vault.keyFingerprints.mapIndexed { i, fingerprint ->
        val xpub = xpubs.getOrNull(i)
        val phone = listed.firstOrNull { xpub != null && it.xpub == xpub && it.fingerprint.equals(fingerprint, ignoreCase = true) }
        if (phone != null) return@mapIndexed KeySigner.Phone(i, phone.words, phone.passphrase)
        val key = keyring.find { it.xpub == xpub && it.fingerprint.equals(fingerprint, ignoreCase = true) }
        KeySigner.Elsewhere(
            i,
            when {
                key?.isMock == true -> STAND_IN_KEY
                key?.source == KeySourceLabel.Jade || key?.source == KeySourceLabel.TrezorSafe7 -> SIGN_ON_HARDWARE_LATER
                key?.source == KeySourceLabel.MebitOther -> KEY_ON_ANOTHER_PHONE
                else -> KEY_NOT_HERE
            },
        )
    }
}

internal const val SIGN_ON_HARDWARE_LATER = "ยังเซ็นด้วยอุปกรณ์ฮาร์ดแวร์ในแอปไม่ได้"
internal const val KEY_ON_ANOTHER_PHONE = "กุญแจนี้อยู่บนมือถืออีกเครื่อง"
internal const val STAND_IN_KEY = "กุญแจจำลอง เซ็นไม่ได้"
internal const val KEY_NOT_HERE = "ไม่พบกุญแจนี้ในเครื่อง"

/**
 * The key of [vault]'s line in the vault guard file: 8 lowercase hex of SHA-256 over a tag and its receive address, the shape the guard file's
 * key takes, so the file holds no address. Only its keys and m make the address, so a book edit that renumbers ids never moves a guard. Two
 * wallets that met on these 32 bits would share one guard, which only refuses a payment.
 */
internal fun vaultGuardKey(vault: CreatedVault): String =
    sha256("mebit/vault-guard/v1 ${vault.address}".encodeToByteArray()).take(4).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
