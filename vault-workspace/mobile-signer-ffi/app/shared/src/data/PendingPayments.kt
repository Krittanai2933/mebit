package com.mebit.app.data

import com.mebit.app.PlainFile
import com.mebit.app.data.SingleSigWallet.DamagedGuard
import com.mebit.app.data.SingleSigWallet.UnconfirmedSend
import com.mebit.app.data.SingleSigWallet.Unsettled
import com.mebit.app.sha256
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** How a [PendingPayments.claim] ended. */
sealed interface Claim {
    /** The phone holds the new guard now, so the payment may be posted. */
    data object Written : Claim

    /** The wallet already has a guard on the phone, [earlier], which stays. That payment may be out, so this one must not go. */
    class Taken(val earlier: UnconfirmedSend) : Claim

    /** The file is on the phone but cannot be read or decoded ([DamagedGuard]), so any wallet may have a payment out and this one must not go. Nothing is written. */
    data object Damaged : Claim

    /** There is no file to keep the guard in, or the write failed, so the phone may not hold the guard and the payment must not go. */
    data object NotSaved : Claim
}

/** How a [PendingPayments.swap] ended. */
sealed interface Swap {
    /** The phone holds the replacement as the wallet's guard now, in place of the payment it replaces, so it may be posted. */
    data object Written : Swap

    /** The wallet's line no longer names the payment the replacement replaces, so nothing is written. [now] is what the file holds instead, which is another payment's guard, null when the guard is gone, or [DamagedGuard]. */
    class Changed(val now: Unsettled?) : Swap

    /** There is no file, the write failed, or the replacement is not the next line of that payment's chain, so it must not go. */
    data object NotSaved : Swap
}

/**
 * The pay-twice guards on this phone: for each wallet, by master fingerprint, the payment that may be out. They live in [file], a
 * plain file in app storage that no backup copies, not in the secure store, so a phone that cannot keep a secret still keeps its
 * guard. The file holds no key, address or amount in the clear, only txids, times, and the signed transaction of a guard written
 * since that kept it, which the chain makes public once a payment is out and which leaves with its line.
 * Every call holds one lock for the whole process, so two live instances of one wallet never both find no guard and both post.
 * With no [file] there is nowhere to keep a guard, so every claim is [Claim.NotSaved].
 *
 * The file holds one line per wallet, "fingerprint txid seconds" or "fingerprint txid seconds hex", and a newline, the txid empty or
 * 64 lowercase hex, and the hex a transaction whose txid is the line's ([txidOf]). A replacement's line ([swap]) adds the txids it
 * replaces, "fingerprint txid seconds hex replaced,replaced", each 64 lowercase hex, distinct and not the line's own, and "cancel"
 * after them once its chain holds a cancel. A file that cannot be read, or holds anything
 * else, is damaged. It may hide any wallet's guard, so it is [DamagedGuard] for every wallet, refuses every claim, and only the
 * user's clear deletes it. Only a wallet's own settle deletes its guard, never a forget of the wallet, so the same words imported
 * again still refuse until that payment settles.
 */
class PendingPayments internal constructor(private val file: PlainFile?) {
    /** The guard of the wallet [fingerprint]: null when it has none or there is no [file], and [DamagedGuard] while the file is damaged. */
    fun of(fingerprint: String): Unsettled? = synchronized(guardLock) {
        val f = file ?: return@synchronized null
        val guards = f.guards() ?: return@synchronized DamagedGuard
        guards[fingerprint]
    }

    /** Writes [send] as the guard of [fingerprint], unless that wallet already has one, or the file is damaged or cannot take it. */
    fun claim(fingerprint: String, send: UnconfirmedSend): Claim = synchronized(guardLock) {
        val f = file ?: return@synchronized Claim.NotSaved
        val guards = f.guards() ?: return@synchronized Claim.Damaged
        guards[fingerprint]?.let { return@synchronized Claim.Taken(it) }
        val written = FINGERPRINT.matches(fingerprint) && send.writable() && f.write(encode(guards + (fingerprint to send)))
        if (written) Claim.Written else Claim.NotSaved
    }

    /**
     * Writes [next] as the guard of [fingerprint] in place of [earlier], while the wallet's line still names [earlier]'s txid, and
     * otherwise writes nothing and says what the file holds. [next] must keep its signed transaction, name the whole chain it
     * replaces, [earlier]'s and [earlier] itself, and keep a cancel mark the chain holds, because any payment of the chain may be the
     * one that is out, and only a block settles a cancel.
     */
    fun swap(fingerprint: String, earlier: UnconfirmedSend, next: UnconfirmedSend): Swap = synchronized(guardLock) {
        val f = file ?: return@synchronized Swap.NotSaved
        val guards = f.guards() ?: return@synchronized Swap.Changed(DamagedGuard)
        val now = guards[fingerprint]
        if (now?.txid != earlier.txid) return@synchronized Swap.Changed(now)
        val follows = next.hex != null && next.replaces == earlier.replaces + earlier.txid && (next.holdsCancel || !earlier.holdsCancel)
        if (follows && next.writable() && f.write(encode(guards + (fingerprint to next)))) Swap.Written else Swap.NotSaved
    }

    /**
     * Runs [post] on the signed transaction the file keeps with [guard], while the file still holds that guard for [fingerprint] with
     * its transaction, and returns what [post] returned. Null, with nothing run, when the file holds no such line. The read and the
     * mark that this transaction is being posted share one hold of the lock, and no [settle] drops the guard until [post] returns,
     * so no clear can let another payment start while these bytes may still land.
     */
    fun <T> repost(fingerprint: String, guard: UnconfirmedSend, post: (hex: String) -> T): T? {
        val hex = synchronized(guardLock) {
            file?.guards()?.get(fingerprint)?.takeIf { it.txid == guard.txid }?.hex?.also { reposting += guard.txid }
        } ?: return null
        try {
            return post(hex)
        } finally {
            synchronized(guardLock) { reposting -= guard.txid }
        }
    }

    /**
     * Drops [guard] while the file still holds it: a payment's line while it still names that txid, so a late settle never drops a
     * newer payment's guard, and a damaged file while it is still damaged, so a late clear never drops a file that reads again.
     * False, dropping nothing, while a [repost] of that payment runs in this process. False too when the write or the delete that
     * drops it failed, so the guard may still be on the phone and the screen says the clear could not finish.
     */
    fun settle(fingerprint: String, guard: Unsettled): Boolean = synchronized(guardLock) {
        val f = file ?: return@synchronized true
        val guards = f.guards()
        when (guard) {
            DamagedGuard -> guards != null || f.delete()
            is UnconfirmedSend -> {
                if (guard.txid in reposting) return@synchronized false
                if (guards == null || guards[fingerprint]?.txid != guard.txid) return@synchronized true
                val rest = guards - fingerprint
                if (rest.isEmpty()) f.delete() else f.write(encode(rest))
            }
        }
    }
}

/** One lock for every [PendingPayments] in the process, because they all share the one file. */
private val guardLock = SynchronizedObject()

/** The txids whose [PendingPayments.repost] runs now, once per run. Read and written only under [guardLock]. */
private val reposting = mutableListOf<String>()

private val FINGERPRINT = Regex("[0-9a-f]{8}")
private val TXID = Regex("(?:[0-9a-f]{64})?")
private val REPLACED = Regex("[0-9a-f]{64}")
private const val CANCEL_MARK = "cancel"

/** The guards the file holds, or null when it is damaged: there but unreadable, or holding text [encode] never writes. */
private fun PlainFile.guards(): Map<String, UnconfirmedSend>? = read()?.let(::decode)

/**
 * Whether [encode] writes this guard so that [decode] reads it back the same. That needs the txid's shape, bytes that are its own
 * transaction, and the payments it replaces and the cancel mark only beside those bytes, each replaced txid once and none the guard's own.
 */
private fun UnconfirmedSend.writable(): Boolean =
    TXID.matches(txid) && (hex == null || txidOf(hex) == txid) &&
        (replaces.isEmpty() || hex != null && replaces.all(REPLACED::matches) && replaces.toSet().size == replaces.size && txid !in replaces) &&
        (!holdsCancel || replaces.isNotEmpty())

private fun encode(guards: Map<String, UnconfirmedSend>): String = guards.entries.joinToString("") { (fingerprint, send) ->
    val replaced = send.replaces.joinToString(",").ifEmpty { null }
    listOfNotNull(fingerprint, send.txid, "${send.sinceSeconds}", send.hex, replaced, CANCEL_MARK.takeIf { send.holdsCancel }).joinToString(" ") + "\n"
}

/** The guards [text] holds, or null when it is anything [encode] never writes. */
private fun decode(text: String): Map<String, UnconfirmedSend>? {
    if (text.isNotEmpty() && !text.endsWith('\n')) return null
    val guards = LinkedHashMap<String, UnconfirmedSend>()
    for (line in text.split('\n').dropLast(1)) {
        val fields = line.split(' ')
        val since = fields.getOrNull(2)?.toLongOrNull()
        if (fields.size !in 3..6 || !FINGERPRINT.matches(fields[0]) || since == null || fields[0] in guards) return null
        if (fields.size == 6 && fields[5] != CANCEL_MARK) return null
        val send = UnconfirmedSend(fields[1], since, fields.getOrNull(3), fields.getOrNull(4)?.split(',').orEmpty(), holdsCancel = fields.size == 6)
        // Bytes that are not the line's own transaction could be another payment, so the file is damaged rather than trusted.
        if (!send.writable()) return null
        guards[fields[0]] = send
    }
    return guards
}

/**
 * The txid of the transaction [hex] encodes, which is the double SHA-256 of it without the segwit marker, flag and witnesses, in
 * the reversed byte order explorers print. Null when [hex] is not lowercase hex of exactly one transaction.
 */
internal fun txidOf(hex: String): String? {
    if (hex.isEmpty() || hex.length % 2 != 0 || hex.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
    val tx = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    var at = 4
    fun byte(): Int = tx[at++].toInt() and 0xff
    // A count or a length, which can never be more than the bytes left, so a huge one ends the parse instead of the loop.
    fun count(): Int {
        val first = byte()
        val width = when (first) {
            0xfd -> 2
            0xfe -> 4
            0xff -> 8
            else -> return first
        }
        val value = (0 until width).fold(0L) { n, i -> n or (byte().toLong() shl (8 * i)) }
        require(value in 0..(tx.size - at).toLong())
        return value.toInt()
    }
    fun skip(n: Int) {
        require(n in 0..(tx.size - at))
        at += n
    }
    return try {
        val segwit = tx[4] == 0.toByte() && tx[5] == 1.toByte()
        if (segwit) at = 6
        val body = at
        val inputs = count()
        repeat(inputs) {
            skip(36)
            skip(count())
            skip(4)
        }
        repeat(count()) {
            skip(8)
            skip(count())
        }
        val bodyEnd = at
        if (segwit) repeat(inputs) { repeat(count()) { skip(count()) } }
        skip(4)
        if (at != tx.size) return null
        val stripped = tx.copyOfRange(0, 4) + tx.copyOfRange(body, bodyEnd) + tx.copyOfRange(at - 4, at)
        sha256(sha256(stripped)).reversedArray().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    } catch (e: Exception) {
        null
    }
}
