package com.mebit.app.data

import com.mebit.app.data.SingleSigWallet.DamagedGuard
import com.mebit.app.data.SingleSigWallet.UnconfirmedSend
import com.mebit.app.data.SingleSigWallet.Unsettled
import com.mebit.ffi.signer.HotSendResult
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.MobileSignerException

/** How one [postWithGuard] ended. The wallet sets its own state from it, and [postedLine] is what its screens say. */
sealed interface Posted {
    /** The node took it, and its guard was dropped. */
    data object Out : Posted

    /** The answer was lost, or something failed once the post began, so the payment may be out and its guard stays. */
    data object Unknown : Posted

    /** The node refused it, or the core refused before posting anything, so nothing is out and its guard was dropped. [message] is the core's. */
    data class Refused(val message: String?) : Posted

    /** The wallet already has a guard on the phone, [earlier], which stays, because that payment may be out. Nothing was posted. */
    data class Taken(val earlier: UnconfirmedSend) : Posted

    /** The guard file is damaged, so this wallet may have a payment out. Nothing was posted. */
    data object Damaged : Posted

    /** The guard could not be written, so the phone may not hold it. Nothing was posted. */
    data object NotSaved : Posted
}

/**
 * The pay-twice protocol of every wallet that broadcasts: [claim] writes the guard [send] on the phone first, so an app killed during the
 * post still refuses the payment after a restart, then [hex] is posted, then [clear] drops the guard once the payment is out or was
 * refused. An unknown outcome keeps it.
 */
internal fun postWithGuard(
    send: UnconfirmedSend,
    hex: String,
    network: KeyNetwork,
    postHex: (KeyNetwork, String, String) -> HotSendResult,
    claim: (UnconfirmedSend) -> Claim,
    clear: (UnconfirmedSend) -> Unit,
): Posted {
    when (val claimed = claim(send)) {
        Claim.Written -> Unit
        is Claim.Taken -> return Posted.Taken(claimed.earlier)
        Claim.Damaged -> return Posted.Damaged
        Claim.NotSaved -> return Posted.NotSaved
    }
    try {
        postHex(network, hex, send.txid)
    } catch (e: MobileSignerException.BroadcastUnknown) {
        return Posted.Unknown
    } catch (e: MobileSignerException.InvalidPsbtJson) {
        // The node refused the transaction, or hot_broadcast refused before it posted anything: nothing is out and the guard goes.
        clear(send)
        return Posted.Refused(e.message)
    } catch (e: Exception) {
        // Any other failure could have come after the post, so the guard stays and the payment reads as unknown.
        return Posted.Unknown
    }
    clear(send)
    return Posted.Out
}

/** The line a screen shows for [posted], null once it is out. */
internal fun postedLine(posted: Posted): String? = when (posted) {
    Posted.Out -> null
    Posted.Unknown -> SEND_UNKNOWN
    is Posted.Refused -> sendErrorThai(posted.message)
    is Posted.Taken, Posted.Damaged -> EARLIER_UNSETTLED
    Posted.NotSaved -> GUARD_NOT_SAVED
}

/** Posts [hex], whose txid is [txid], again, the bytes a guard keeps, and says how it ended. */
internal fun postAgain(postHex: (KeyNetwork, String, String) -> HotSendResult, network: KeyNetwork, hex: String, txid: String): Repost = try {
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
 * A wallet's payment that may be out, and the two ways out of a guard that nothing settles, which the screens offer every wallet that
 * broadcasts ([com.mebit.app.feature.send.UnconfirmedClearControl]).
 */
interface GuardExits {
    val unconfirmedSend: Unsettled?

    /** Whether the clear may be offered now: once the payment is old enough by the phone clock ([clearOffered]), at once for a damaged guard. */
    fun unconfirmedClearOffered(): Boolean

    /** Posts the guard's own signed bytes again, the same transaction, so it can never pay twice. Only [Repost.Out] drops the guard. */
    suspend fun repost(): Repost

    /** Drops [guard] here and on the phone once the user checked it is not out. False, with it kept, when the phone could not drop it. */
    fun clearUnconfirmedSend(guard: Unsettled): Boolean
}

/** Whether a screen may offer the control that clears [guard] when the clock reads [nowSeconds]: by [offersUnconfirmedClear] for a payment, at once for a damaged guard. */
internal fun clearOffered(guard: Unsettled?, nowSeconds: Long): Boolean = when (guard) {
    null -> false
    DamagedGuard -> true
    is UnconfirmedSend -> offersUnconfirmedClear(guard.sinceSeconds, nowSeconds)
}
