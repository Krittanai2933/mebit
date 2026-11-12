package com.mebit.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mebit.app.data.SingleSigWallet.DamagedGuard
import com.mebit.app.data.SingleSigWallet.UnconfirmedSend
import com.mebit.app.data.SingleSigWallet.Unsettled
import com.mebit.app.sha256
import com.mebit.ffi.signer.FeeTiers
import com.mebit.ffi.signer.HotScan
import com.mebit.ffi.signer.HotSendResult
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.MobileSignerException
import com.mebit.ffi.signer.PreparedSend
import com.mebit.ffi.signer.VaultPsbt
import com.mebit.ffi.signer.VaultSpendable
import com.mebit.ffi.signer.hotBroadcast
import com.mebit.ffi.signer.hotFeeTiers
import com.mebit.ffi.signer.vaultFinalizePsbt
import com.mebit.ffi.signer.vaultPrepareSend
import com.mebit.ffi.signer.vaultSignPsbt
import com.mebit.ffi.signer.vaultSpendable
import com.mebit.ffi.signer.vaultTypicalVsize
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

/** Where one send from a several-key wallet stands. A new send is a new [VaultSend]. */
sealed interface VaultSendState {
    /** Penpot 5.1's form, with nothing prepared. */
    data object Form : VaultSendState

    /**
     * Penpot 5.2: [psbt] is the payment the user reviews and keys sign, and its [VaultPsbt.signedKeys] is the only record of who signed.
     * [stale] once the node refused it, because these bytes can never go out, so 5.1 prepares another.
     */
    data class Collecting(val psbt: VaultPsbt, val stale: Boolean = false) : VaultSendState

    /** The node took [psbt]'s transaction, which 5.2 still draws while it leaves. */
    data class Sent(val psbt: VaultPsbt) : VaultSendState
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
 * it returned, never a list kept here. Every Rust call is a lambda, so tests need no native library, and runs under one lock, so two signs
 * started together each build on the PSBT the other returned. [sign] runs Rust on [worker] after the owner check.
 */
class VaultSend(
    val vault: CreatedVault,
    private val guards: PendingPayments,
    private val nowSeconds: () -> Long = { Clock.System.now().epochSeconds },
    private val worker: CoroutineContext = Dispatchers.Default,
    typicalVsize: (xpubs: List<String>, m: UByte) -> ULong = ::vaultTypicalVsize,
    private val spendableOf: (xpubs: List<String>, m: UByte) -> VaultSpendable = ::vaultSpendable,
    private val fetchFeeTiers: (network: KeyNetwork) -> FeeTiers = ::hotFeeTiers,
    private val prepareSend: (xpubs: List<String>, m: UByte, destination: String, amountSats: ULong?, feeRateSatVb: ULong) -> VaultPsbt = ::vaultPrepareSend,
    private val signPsbt: (xpubs: List<String>, m: UByte, held: VaultPsbt, keyIndex: UInt, words: List<String>, passphrase: String) -> VaultPsbt = ::vaultSignPsbt,
    private val finalizePsbt: (xpubs: List<String>, m: UByte, held: VaultPsbt) -> PreparedSend = ::vaultFinalizePsbt,
    private val postHex: (network: KeyNetwork, hex: String, txid: String) -> HotSendResult = ::hotBroadcast,
) {
    private val xpubs = xpubsIn(vault.bsms)
    private val m = vault.m.toUByte()
    private val guardKey = vaultGuardKey(vault)
    private val lock = SynchronizedObject()

    /** 5.1's form, blank at the normal tier and priced at the wallet's own size, 201 vB for a 2-of-3. Throws for a wallet the core cannot read. */
    val draft: SendDraft = SendDraft(typicalVsize(xpubs, m).toLong())

    var state: VaultSendState by mutableStateOf(VaultSendState.Form)
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

    /** The payment 5.2 shows: the one held for signatures, or the one that went out. */
    val payment: VaultPsbt?
        get() = when (val now = state) {
            is VaultSendState.Collecting -> now.psbt
            is VaultSendState.Sent -> now.psbt
            VaultSendState.Form -> null
        }

    /** The uppercase fingerprints of the wallet's keys that Rust found signed on every input of [payment], each once. */
    val signedFingerprints: Set<String>
        get() = payment?.signedKeys.orEmpty()
            .mapNotNull { vault.keyFingerprints.getOrNull(it.toInt())?.uppercase() }
            .toSet()

    /** 5.2's "ต้องเซ็นอีก k ดอก". */
    val signaturesNeeded: Int get() = (vault.m - signedFingerprints.size).coerceAtLeast(0)

    /** The organize-vault-send property: a payment the node may still take, signed by at least m distinct fingerprints of this wallet. */
    fun readyToBroadcast(): Boolean = (state as? VaultSendState.Collecting)?.stale == false && signedFingerprints.size >= vault.m

    /** 5.1's read on open: the guard file, what the wallet can spend, and the fee tiers, the board's fixed ones when the fetch fails. Blocks. */
    fun load(): LiveFeeTiers {
        guard = guards.of(guardKey)
        spendableSats = try {
            spendableOf(xpubs, m).takeIf { it.scan == HotScan.COMPLETE }?.spendableSats?.toLong()
        } catch (e: Exception) {
            null
        }
        return try {
            LiveFeeTiers.of(fetchFeeTiers(KeyNetwork.TEST))
        } catch (e: Exception) {
            LiveFeeTiers.DEFAULT
        }
    }

    /**
     * 5.1's ตรวจสอบ: the unsigned payment of [draft]'s request, held for 5.2 with no signature. A payment held before goes with its
     * signatures, which signed other bytes. Refused while the guard file holds a payment of this wallet. False with [lastError]. Blocks.
     */
    fun prepare(): Boolean = synchronized(lock) {
        lastError = null
        guard = guards.of(guardKey)
        if (guard != null) return@synchronized fail(EARLIER_UNSETTLED)
        val request = draft.request
        busy = VaultBusy.Preparing
        try {
            val amount = request.amountSats.toULong().takeUnless { request.sendAll }
            state = VaultSendState.Collecting(prepareSend(xpubs, m, request.destination, amount, request.rateSatVb.toULong()))
            true
        } catch (e: Exception) {
            fail(sendErrorThai(e.message))
        } finally {
            busy = null
        }
    }

    /**
     * 5.2's เซ็น on a row this phone signs. Asks the owner first ([confirm], once per signature), then Rust on [worker]. Keeps Rust's
     * answer only when it is the same payment and names [signer]'s key among the signers, so a key counts only once Rust returned a PSBT
     * that carries its valid signature. False, with Rust never asked, when the owner check fails.
     */
    suspend fun sign(signer: KeySigner.Phone, confirm: suspend () -> Boolean): Boolean {
        if ((state as? VaultSendState.Collecting)?.stale != false) return false
        if (!confirm()) return fail(OWNER_NOT_CONFIRMED)
        return withContext(worker) {
            synchronized(lock) {
                val held = (state as? VaultSendState.Collecting)?.takeIf { !it.stale }?.psbt ?: return@synchronized false
                lastError = null
                busy = VaultBusy.Signing(signer.index)
                try {
                    val next = signPsbt(xpubs, m, held, signer.index.toUInt(), signer.words, signer.passphrase)
                    if (next.txid != held.txid || signer.index.toUInt() !in next.signedKeys) {
                        fail(NOT_SIGNED)
                    } else {
                        state = VaultSendState.Collecting(next)
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
     * 5.2's ส่งธุรกรรม, only while [readyToBroadcast]. Rust finishes the transaction, and its guard reaches the phone before the post, as a
     * one-key [SingleSigWallet.post] does. A guard that already names this txid means these same bytes may be out, so they go again, which can
     * never pay twice, and any other guard refuses. Out: the guard goes and [state] is [VaultSendState.Sent]. An unknown outcome keeps the
     * guard. The node's refusal of a first post drops the guard and makes the payment stale. Blocks.
     */
    fun broadcast(): Boolean = synchronized(lock) {
        lastError = null
        if (state is VaultSendState.Sent) return@synchronized fail(ALREADY_SENT)
        val held = (state as? VaultSendState.Collecting)?.psbt
        if (held == null || !readyToBroadcast()) return@synchronized fail(NOT_ENOUGH_SIGNATURES)
        busy = VaultBusy.Posting
        try {
            val ready = finalizePsbt(xpubs, m, held)
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

    private fun firstPost(held: VaultPsbt, pending: UnconfirmedSend, hex: String): Boolean = try {
        postHex(KeyNetwork.TEST, hex, pending.txid)
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

    private fun repostSame(held: VaultPsbt, earlier: UnconfirmedSend): Boolean =
        when (guards.repost(guardKey, earlier) { hex -> posted(hex, earlier.txid) } ?: Repost.Failed) {
            Repost.Out -> out(held, earlier)
            Repost.Unknown -> unknown(earlier)
            Repost.Refused, Repost.Failed -> {
                guard = earlier
                fail(REPOST_NOT_TAKEN)
            }
        }

    private fun out(held: VaultPsbt, sent: UnconfirmedSend): Boolean {
        guards.settle(guardKey, sent)
        guard = null
        state = VaultSendState.Sent(held)
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
        postHex(KeyNetwork.TEST, hex, txid)
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
        const val NOT_SIGNED = "เซ็นไม่สำเร็จ ลองใหม่อีกครั้ง"
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
