package com.mebit.app.data

import com.mebit.app.EraseResult
import com.mebit.app.Marker
import com.mebit.app.ReadResult
import com.mebit.app.SecureStore
import com.mebit.app.StoreProbe
import com.mebit.app.Why
import com.mebit.app.WriteMode
import com.mebit.app.WriteResult
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
    Marker.Unsupported -> ReinstallAction.None
    Marker.Absent -> when (probe) {
        StoreProbe.Empty -> ReinstallAction.MarkEmpty
        StoreProbe.Present, StoreProbe.KeyGone -> ReinstallAction.WipeOrphan
        is StoreProbe.Unavailable -> ReinstallAction.None
    }
    Marker.Empty -> if (probe == StoreProbe.Present) ReinstallAction.MarkKept else ReinstallAction.None
    Marker.Kept -> if (probe == StoreProbe.Empty) ReinstallAction.Vanished else ReinstallAction.None
}

/** How an attempt to open the saved wallet ended. */
sealed interface UnlockResult {
    class Opened(val record: WalletRecord) : UnlockResult

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
    data object Written : SaveResult

    data object AlreadyExists : SaveResult

    class NotSaved(val why: Why) : SaveResult
}

/** The wallet saved on this phone. The only code that touches [SecureStore], so the rules below live in one place. */
class SavedWallet internal constructor(private val store: SecureStore) {
    /** Never prompts. */
    fun probe(): StoreProbe = store.probe()

    /**
     * The cold start of this process. Reads the marker and the probe, runs the pure [reinstallPolicy], and does what it says
     * (erase an orphan, write the marker). Never wipes on doubt. Returns the probe to decide the boot with and a one-time notice.
     */
    fun start(): WalletStart {
        val marker = store.marker()
        var probe = store.probe()
        var notice: StartNotice? = null
        when (reinstallPolicy(marker, probe)) {
            ReinstallAction.None -> Unit
            ReinstallAction.MarkEmpty -> store.writeMarker(Marker.Empty)
            ReinstallAction.MarkKept -> store.writeMarker(Marker.Kept)
            ReinstallAction.WipeOrphan -> if (store.erase() is EraseResult.Erased) {
                store.writeMarker(Marker.Empty)
                probe = store.probe()
                notice = StartNotice.OrphanWiped
            } else {
                // The wipe failed, so the wallet stays and counts as the user's. Another try at the next launch could erase it after they began to rely on it.
                store.writeMarker(Marker.Kept)
            }
            ReinstallAction.Vanished -> {
                store.writeMarker(Marker.Empty)
                notice = StartNotice.WalletVanished
            }
        }
        return WalletStart(probe, notice)
    }

    /** May show the OS owner check. The record exists in memory only inside the returned Opened, and the bytes read are zeroed. */
    suspend fun open(reason: String): UnlockResult = when (val r = store.read(reason)) {
        is ReadResult.Bytes -> try {
            when (val d = RecordCodec.decode(r.bytes)) {
                is Decoded.Ok -> if (d.record.network in NETWORKS) UnlockResult.Opened(d.record) else UnlockResult.Broken(Cause.UnsupportedNetwork)
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
                WriteResult.Written -> SaveResult.Written.also { store.writeMarker(Marker.Kept) }
                WriteResult.AlreadyExists -> SaveResult.AlreadyExists
                is WriteResult.Unavailable -> SaveResult.NotSaved(w.why)
            }
        } finally {
            bytes.fill(0)
        }
    }

    /** After the user confirmed. The only call in the app that deletes the wallet. */
    fun forget(): Boolean = (store.erase() is EraseResult.Erased).also { if (it) store.writeMarker(Marker.Empty) }

    private companion object {
        val NETWORKS = setOf(KeyNetwork.TEST)
    }
}

/**
 * Layout, big endian: magic "MB" (2), version (1), network (1: 0 test, 1 main), flags (1: 1 backup, 2 imported, 4 lock),
 * fingerprint (8 ASCII), words as ONE UTF-8 string joined by single spaces (u16 length + bytes), passphrase (u16 length + bytes, byte exact).
 * encode throws, naming no word, on a record it could not read back exactly: a fingerprint that is not 8 ASCII characters,
 * an empty word or one holding a space, text UTF-8 cannot carry (a lone surrogate), a field over 65,535 bytes.
 * decode never throws: bad magic, short or trailing bytes, word count outside BIP39_SIZES, an empty word, a fingerprint byte
 * outside ASCII, bad UTF-8 => Corrupt; a version above VERSION, or a network byte or flag bit v1 never writes => Newer.
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
                out[4] = ((if (r.backupConfirmed) BACKUP else 0) or (if (r.imported) IMPORTED else 0) or (if (r.appLockEnabled) LOCK else 0)).toByte()
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
        if ((flags and (BACKUP or IMPORTED or LOCK).inv()) != 0) return Decoded.Newer
        if ((5 until HEADER).any { b[it] < 0 }) return Decoded.Corrupt
        val wordsEnd = b.fieldEnd(HEADER) ?: return Decoded.Corrupt
        val passphraseEnd = b.fieldEnd(wordsEnd) ?: return Decoded.Corrupt
        if (passphraseEnd != b.size) return Decoded.Corrupt
        val words = b.utf8OrNull(HEADER + 2, wordsEnd)?.split(' ') ?: return Decoded.Corrupt
        val passphrase = b.utf8OrNull(wordsEnd + 2, passphraseEnd) ?: return Decoded.Corrupt
        if (words.size !in WalletRecord.BIP39_SIZES || words.any { it.isEmpty() }) return Decoded.Corrupt
        val fingerprint = b.decodeToString(5, HEADER)
        return Decoded.Ok(
            WalletRecord(words, passphrase, network, fingerprint, (flags and BACKUP) != 0, (flags and IMPORTED) != 0, (flags and LOCK) != 0),
        )
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
    class Ok(val record: WalletRecord) : Decoded

    data object Corrupt : Decoded

    data object Newer : Decoded
}
