package com.mebit.app.data

import com.mebit.app.EraseResult
import com.mebit.app.Marker
import com.mebit.app.ReadResult
import com.mebit.app.SecureStore
import com.mebit.app.StoreProbe
import com.mebit.app.StoreVersion
import com.mebit.app.Why
import com.mebit.app.WriteMode
import com.mebit.app.WriteResult
import com.mebit.ffi.signer.AddressType
import com.mebit.ffi.signer.KeyNetwork

/** Why a saved wallet can never open. Only forgetting it helps. */
enum class Cause { Missing, KeyGone, Corrupt, NewerVersion, UnsupportedNetwork }

/** A one-time sentence for Welcome after the start erased or lost a wallet. */
enum class StartNotice { OrphanWiped, WalletVanished }

/** What the cold start of this process found: the probe to decide the boot with, and a notice to show once. */
class WalletStart(val probe: StoreProbe, val notice: StartNotice?)

/** What the start does about the marker and the probe, as [reinstallPolicy] decides. */
sealed interface ReinstallAction {
    data object None : ReinstallAction

    /** First launch of this install, nothing on the phone. */
    data object MarkEmpty : ReinstallAction

    /** A wallet is there but the marker says empty: self-heal, never a wipe. */
    data object MarkKept : ReinstallAction

    /** First launch of a fresh install, and a wallet from a deleted install is there. */
    data object WipeOrphan : ReinstallAction

    /** The marker says kept and nothing is there: the OS removed it, or a backup came to another phone. */
    data object Vanished : ReinstallAction
}

/**
 * Pure, and the only place that decides a wipe: the platform reports a marker and a probe, never a rule.
 * Never wipes on doubt: an Unavailable probe is None and writes no marker, so the next launch decides.
 */
fun reinstallPolicy(marker: Marker, probe: StoreProbe): ReinstallAction = when (marker) {
    Marker.Unsupported, Marker.Unreadable -> ReinstallAction.None // no marker to trust, or a marker that could not be read: never wipe on doubt
    Marker.Absent -> when (probe) {
        StoreProbe.Empty -> ReinstallAction.MarkEmpty
        StoreProbe.Present, StoreProbe.KeyGone -> ReinstallAction.WipeOrphan
        is StoreProbe.Unavailable -> ReinstallAction.None
    }
    Marker.Empty -> if (probe == StoreProbe.Present) ReinstallAction.MarkKept else ReinstallAction.None
    // A wallet was kept and the phone now holds nothing, or has no passcode and so cannot hold one: either way it is gone, and nothing is wiped.
    Marker.Kept -> if (probe == StoreProbe.Empty || (probe is StoreProbe.Unavailable && probe.why == Why.NoScreenLock)) ReinstallAction.Vanished else ReinstallAction.None
}

/** How an attempt to open the saved wallet ended. */
sealed interface UnlockResult {
    /** [oldGuard] is the pay-twice guard a build before the guard file saved in the record (flag 8), which [OnboardingSession.install] moves to that file. */
    class Opened(val record: WalletRecord, val version: StoreVersion, val oldGuard: SingleSigWallet.UnconfirmedSend? = null) : UnlockResult

    /** The Enable step's read-back: the saved copy opened and its fingerprint matches this session. Carries no data. */
    data object Verified : UnlockResult

    data object Cancelled : UnlockResult

    data object Denied : UnlockResult

    data object LockedOut : UnlockResult

    /** Can never open. Only forgetting it helps. */
    class Broken(val cause: Cause) : UnlockResult

    class Unavailable(val why: Why) : UnlockResult
}

/** How a save ended. */
sealed interface SaveResult {
    class Written(val version: StoreVersion) : SaveResult

    data object AlreadyExists : SaveResult

    /** A Replace met another copy than the one the caller last read or wrote. Nothing written, no marker touched. */
    data object Conflict : SaveResult

    class NotSaved(val why: Why) : SaveResult
}

/** One place's saved wallet, bound to its store at construction so its session cannot reach another wallet's bytes. The only code that touches [SecureStore], so the rules below live in one place. */
class SavedWallet internal constructor(private val store: SecureStore) {
    /** May show the OS owner check. The record exists in memory only inside the returned Opened, and the bytes read are zeroed. */
    suspend fun open(reason: String): UnlockResult = decoded(store.read(reason)).also {
        // A wallet its owner has opened is theirs whatever the marker said, so a later launch must never take it for an orphan. Best effort.
        if (it is UnlockResult.Opened) store.writeMarker(Marker.Kept)
    }

    /** What one read's answer means. [open] without its marker write, so a read of every place writes the marker once. Zeroes the bytes. */
    internal fun decoded(r: ReadResult): UnlockResult = when (r) {
        is ReadResult.Bytes -> try {
            when (val d = RecordCodec.decode(r.bytes)) {
                is Decoded.Ok -> if (d.record.network in NETWORKS) UnlockResult.Opened(d.record, r.version, d.oldGuard) else UnlockResult.Broken(Cause.UnsupportedNetwork)
                Decoded.Newer -> UnlockResult.Broken(Cause.NewerVersion) // never overwritten: a newer app wrote it
                Decoded.Corrupt -> UnlockResult.Broken(Cause.Corrupt)
            }
        } finally {
            r.bytes.fill(0)
        }
        ReadResult.NotFound -> UnlockResult.Broken(Cause.Missing)
        ReadResult.KeyGone -> UnlockResult.Broken(Cause.KeyGone)
        ReadResult.Unreadable -> UnlockResult.Broken(Cause.Corrupt)
        ReadResult.Cancelled -> UnlockResult.Cancelled
        ReadResult.Denied -> UnlockResult.Denied
        ReadResult.LockedOut -> UnlockResult.LockedOut
        is ReadResult.Unavailable -> UnlockResult.Unavailable(r.why)
    }

    /**
     * Never prompts. [WriteMode.Create] never overwrites, which is how a second create or import is refused at the storage layer.
     * A record the codec cannot write back exactly is NotSaved(Other). The encoded bytes are zeroed once the store has them.
     */
    fun save(record: WalletRecord, mode: WriteMode): SaveResult {
        if (record.network !in NETWORKS) return SaveResult.NotSaved(Why.Other) // testnet only until the security review
        val bytes = try {
            RecordCodec.encode(record)
        } catch (e: Exception) {
            return SaveResult.NotSaved(Why.Other)
        }
        try {
            return when (val w = store.write(bytes, mode)) {
                is WriteResult.Written -> when {
                    store.writeMarker(Marker.Kept) -> SaveResult.Written(w.version)
                    mode is WriteMode.Replace -> SaveResult.Written(w.version) // the wallet was already there, under a marker that was not Absent
                    else -> {
                        // A new wallet without its marker would be taken for an orphan and erased at the next launch, so it is not kept.
                        store.erase()
                        SaveResult.NotSaved(Why.Other)
                    }
                }
                WriteResult.AlreadyExists -> SaveResult.AlreadyExists
                WriteResult.Conflict -> SaveResult.Conflict
                is WriteResult.Unavailable -> SaveResult.NotSaved(w.why)
            }
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * After the user confirmed. Erases this place. The marker goes to Empty unless the phone positively still holds another wallet:
     * a wrong Empty heals through [reinstallPolicy]'s MarkKept at the next start, while a missing Empty would read as Vanished.
     */
    fun forget(): EraseResult = store.erase().also {
        if (it is EraseResult.Erased) {
            val left = store.probe()
            if (left != StoreProbe.Present && left != StoreProbe.KeyGone) store.writeMarker(Marker.Empty)
        }
    }

    private companion object {
        val NETWORKS = setOf(KeyNetwork.TEST)
    }
}

/**
 * Layout, big endian: magic "MB" (2), version (1), network (1: 0 test, 1 main), flags (1: 1 backup, 2 imported, 4 lock,
 * 8 unconfirmed send, bits 16 and 32 the address type's code: 0 Native SegWit, 1 Legacy, 2 Nested SegWit, 3 Taproot, and 64 an
 * address type the user has not confirmed yet),
 * fingerprint (8 ASCII), words as ONE UTF-8 string joined by single spaces (u16 length + bytes),
 * passphrase (u16 length + bytes, byte exact), then only with flag 8 the payment of unknown outcome: its txid (u16 length +
 * ASCII, empty or 64 lowercase hex) and when its broadcast ended (8, Unix seconds, never negative). Only builds before the
 * guard file wrote flag 8. decode still reads it, so [OnboardingSession.install] can move that guard to [PendingPayments],
 * and encode never writes it. Native SegWit sets neither type bit, so every record of that type has the bytes an older
 * build reads too, and an older build takes a record of another type for a newer app's and never opens or overwrites it.
 * A confirmed type sets no bit 64, so its record keeps the bytes it had, and every build before bit 64 takes a record with it
 * for a newer app's in the same way.
 * encode throws, naming no word, on a record it could not read back exactly: a fingerprint that is not 8 ASCII characters,
 * an empty word or one holding a space, text UTF-8 cannot carry (a lone surrogate), a field over 65,535 bytes.
 * decode never throws: bad magic, short or trailing bytes, word count outside BIP39_SIZES, an empty word, a fingerprint byte
 * outside ASCII, bad UTF-8, a txid or time no build wrote => Corrupt; a version above VERSION, or a network byte or
 * flag bit v1 never writes => Newer.
 */
private object RecordCodec {
    const val VERSION = 1
    private val MAGIC = "MB".encodeToByteArray()
    private const val FINGERPRINT = 8
    private const val HEADER = 5 + FINGERPRINT // magic, version, network, flags, fingerprint
    private const val MAX_FIELD = 0xFFFF
    private val NETWORK_BYTE = mapOf(KeyNetwork.TEST to 0.toByte(), KeyNetwork.MAIN to 1.toByte()) // not the ordinal: MAIN is declared first
    private const val BACKUP = 1
    private const val IMPORTED = 2
    private const val LOCK = 4
    private const val UNCONFIRMED = 8
    private const val TYPE_SHIFT = 4
    private const val TYPE_BITS = 3 shl TYPE_SHIFT
    private const val TYPE_PENDING = 64

    /** Not the ordinal, as with [NETWORK_BYTE]: Native SegWit is 0 so that a default record keeps the bytes it had. */
    private val TYPE_CODE = mapOf(
        AddressType.NATIVE_SEGWIT to 0,
        AddressType.LEGACY to 1,
        AddressType.NESTED_SEGWIT to 2,
        AddressType.TAPROOT to 3,
    )
    private val TXID = Regex("(?:[0-9a-f]{64})?")

    fun encode(r: WalletRecord): ByteArray {
        require(r.fingerprint.length == FINGERPRINT && r.fingerprint.all { it.code < 0x80 }) { "fingerprint shape" }
        require(r.words.none { it.isEmpty() || ' ' in it }) { "word shape" } // either would split into other words on decode
        return utf8(r.words.joinToString(" ")) { words ->
            utf8(r.passphrase) { passphrase ->
                require(words.size <= MAX_FIELD && passphrase.size <= MAX_FIELD) { "field length" }
                val out = ByteArray(HEADER + 2 + words.size + 2 + passphrase.size)
                MAGIC.copyInto(out)
                out[2] = VERSION.toByte()
                out[3] = NETWORK_BYTE.getValue(r.network)
                out[4] = (
                    (if (r.backupConfirmed) BACKUP else 0) or (if (r.imported) IMPORTED else 0) or (if (r.appLockEnabled) LOCK else 0) or
                        (TYPE_CODE.getValue(r.addressType) shl TYPE_SHIFT) or (if (r.addressTypeConfirmed) 0 else TYPE_PENDING)
                    ).toByte()
                r.fingerprint.forEachIndexed { i, c -> out[5 + i] = c.code.toByte() }
                out.putField(out.putField(HEADER, words), passphrase)
                out
            }
        }
    }

    fun decode(b: ByteArray): Decoded {
        if (b.size < 3 || b[0] != MAGIC[0] || b[1] != MAGIC[1]) return Decoded.Corrupt
        val version = b[2].toInt() and 0xFF
        if (version > VERSION) return Decoded.Newer
        if (version < VERSION || b.size < HEADER) return Decoded.Corrupt
        // A value v1 never writes came from a newer app, so the rest is not judged by v1 rules.
        val network = NETWORK_BYTE.entries.firstOrNull { it.value == b[3] }?.key ?: return Decoded.Newer
        val flags = b[4].toInt() and 0xFF
        if ((flags and (BACKUP or IMPORTED or LOCK or UNCONFIRMED or TYPE_BITS or TYPE_PENDING).inv()) != 0) return Decoded.Newer
        val addressType = TYPE_CODE.entries.first { it.value == (flags and TYPE_BITS) shr TYPE_SHIFT }.key
        if ((5 until HEADER).any { b[it] < 0 }) return Decoded.Corrupt
        val wordsEnd = b.fieldEnd(HEADER) ?: return Decoded.Corrupt
        val passphraseEnd = b.fieldEnd(wordsEnd) ?: return Decoded.Corrupt
        val hasSend = (flags and UNCONFIRMED) != 0
        val txidEnd = if (hasSend) (b.fieldEnd(passphraseEnd) ?: return Decoded.Corrupt) else passphraseEnd
        if ((if (hasSend) txidEnd + 8 else passphraseEnd) != b.size) return Decoded.Corrupt
        val words = b.utf8OrNull(HEADER + 2, wordsEnd)?.split(' ') ?: return Decoded.Corrupt
        val passphrase = b.utf8OrNull(wordsEnd + 2, passphraseEnd) ?: return Decoded.Corrupt
        if (words.size !in WalletRecord.BIP39_SIZES || words.any { it.isEmpty() }) return Decoded.Corrupt
        val send = if (hasSend) (b.unconfirmedSendOrNull(passphraseEnd + 2, txidEnd) ?: return Decoded.Corrupt) else null
        val fingerprint = b.decodeToString(5, HEADER)
        return Decoded.Ok(
            WalletRecord(
                words, passphrase, network, fingerprint, (flags and BACKUP) != 0, (flags and IMPORTED) != 0, (flags and LOCK) != 0, addressType,
                addressTypeConfirmed = (flags and TYPE_PENDING) == 0,
            ),
            send,
        )
    }

    /** The txid in [from] until [txidEnd] and the 8 bytes of seconds after it, or null when either is something encode never writes. */
    private fun ByteArray.unconfirmedSendOrNull(from: Int, txidEnd: Int): SingleSigWallet.UnconfirmedSend? {
        val txid = decodeToString(from, txidEnd)
        val since = (txidEnd until txidEnd + 8).fold(0L) { acc, i -> (acc shl 8) or (this[i].toLong() and 0xFF) }
        return if (TXID.matches(txid) && since >= 0) SingleSigWallet.UnconfirmedSend(txid, since) else null
    }

    /** Runs [block] on the UTF-8 bytes of [text] and zeroes them afterwards, whatever happens. */
    private inline fun <T> utf8(text: String, block: (ByteArray) -> T): T {
        val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
        try {
            return block(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    /** The text in [from] until [to], or null when it is not valid UTF-8. */
    private fun ByteArray.utf8OrNull(from: Int, to: Int): String? = try {
        decodeToString(from, to, throwOnInvalidSequence = true)
    } catch (e: CharacterCodingException) {
        null
    }

    /** Writes [field] after its u16 length at [at] and returns where the next field starts. */
    private fun ByteArray.putField(at: Int, field: ByteArray): Int {
        this[at] = (field.size shr 8).toByte()
        this[at + 1] = field.size.toByte()
        field.copyInto(this, at + 2)
        return at + 2 + field.size
    }

    /** Where the u16-prefixed field at [at] ends, or null when the bytes stop short of it. */
    private fun ByteArray.fieldEnd(at: Int): Int? {
        if (size - at < 2) return null
        val end = at + 2 + (((this[at].toInt() and 0xFF) shl 8) or (this[at + 1].toInt() and 0xFF))
        return if (end <= size) end else null
    }
}

/** What decode made of the bytes. */
private sealed interface Decoded {
    /** [oldGuard] is the payment of unknown outcome a record with flag 8 holds, or null. */
    class Ok(val record: WalletRecord, val oldGuard: SingleSigWallet.UnconfirmedSend?) : Decoded

    data object Corrupt : Decoded

    data object Newer : Decoded
}
