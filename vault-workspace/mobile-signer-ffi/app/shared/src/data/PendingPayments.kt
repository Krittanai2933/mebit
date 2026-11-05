package com.mebit.app.data

import com.mebit.app.PlainFile
import com.mebit.app.data.SingleSigWallet.DamagedGuard
import com.mebit.app.data.SingleSigWallet.UnconfirmedSend
import com.mebit.app.data.SingleSigWallet.Unsettled
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

/**
 * The pay-twice guards on this phone: for each wallet, by master fingerprint, the payment that may be out. They live in [file], a
 * plain file in app storage that no backup copies, not in the secure store, so a phone that cannot keep a secret still keeps its
 * guard. The file holds no key, address or amount, only txids, which the chain makes public once a payment is out, and times.
 * Every call holds one lock for the whole process, so two live instances of one wallet never both find no guard and both post.
 * With no [file] there is nowhere to keep a guard, so every claim is [Claim.NotSaved].
 *
 * Layout: one line per wallet, "fingerprint txid seconds" and a newline, the txid empty or 64 lowercase hex. A file that cannot be
 * read, or holds anything else, is damaged: it may hide any wallet's guard, so it is [DamagedGuard] for every wallet, refuses every
 * claim, and only the user's clear deletes it. Only a wallet's own settle deletes its guard, never a forget of the wallet, so the
 * same words imported again still refuse until that payment settles.
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
        val written = FINGERPRINT.matches(fingerprint) && TXID.matches(send.txid) && f.write(encode(guards + (fingerprint to send)))
        if (written) Claim.Written else Claim.NotSaved
    }

    /**
     * Drops [guard] while the file still holds it: a payment's line while it still names that txid, so a late settle never drops a
     * newer payment's guard, and a damaged file while it is still damaged, so a late clear never drops a file that reads again.
     */
    fun settle(fingerprint: String, guard: Unsettled): Unit = synchronized(guardLock) {
        val f = file ?: return@synchronized
        val guards = f.guards()
        when (guard) {
            DamagedGuard -> if (guards == null) f.delete()
            is UnconfirmedSend -> {
                if (guards == null || guards[fingerprint]?.txid != guard.txid) return@synchronized
                val rest = guards - fingerprint
                if (rest.isEmpty()) f.delete() else f.write(encode(rest))
            }
        }
    }
}

/** One lock for every [PendingPayments] in the process, because they all share the one file. */
private val guardLock = SynchronizedObject()

private val FINGERPRINT = Regex("[0-9a-f]{8}")
private val TXID = Regex("(?:[0-9a-f]{64})?")

/** The guards the file holds, or null when it is damaged: there but unreadable, or holding text [encode] never writes. */
private fun PlainFile.guards(): Map<String, UnconfirmedSend>? = read()?.let(::decode)

private fun encode(guards: Map<String, UnconfirmedSend>): String =
    guards.entries.joinToString("") { (fingerprint, send) -> "$fingerprint ${send.txid} ${send.sinceSeconds}\n" }

/** The guards [text] holds, or null when it is anything [encode] never writes. */
private fun decode(text: String): Map<String, UnconfirmedSend>? {
    if (text.isNotEmpty() && !text.endsWith('\n')) return null
    val guards = LinkedHashMap<String, UnconfirmedSend>()
    for (line in text.split('\n').dropLast(1)) {
        val fields = line.split(' ')
        val since = fields.getOrNull(2)?.toLongOrNull()
        if (fields.size != 3 || !FINGERPRINT.matches(fields[0]) || !TXID.matches(fields[1]) || since == null || fields[0] in guards) return null
        guards[fields[0]] = UnconfirmedSend(fields[1], since)
    }
    return guards
}
