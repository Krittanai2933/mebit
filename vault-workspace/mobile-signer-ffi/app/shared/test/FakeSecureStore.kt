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
import kotlinx.coroutines.runBlocking

/**
 * An in-memory [SecureStore] for common tests. It keeps what was written and serves a fresh copy of it, and each answer
 * can be scripted instead: [read] and [write] are null to act like a store (serve the held bytes or NotFound, refuse a
 * Create over a held record), and [failWrite] makes that write, counting from 1, answer Unavailable(Other). It records
 * every write and keeps every array it served, so a test can check that the caller zeroed both. It prints nothing.
 */
internal class FakeSecureStore(
    var probe: StoreProbe = StoreProbe.Empty,
    var read: ReadResult? = null,
    var write: WriteResult? = null,
    var erase: EraseResult = EraseResult.Erased,
    var marker: Marker = Marker.Absent,
    private val failWrite: Int = 0,
    /** Which marker writes, counting from 1, do not take effect: nothing is recorded and the marker keeps its value. */
    private val failMarker: Set<Int> = emptySet(),
) : SecureStore {
    /** One write call: its mode, a copy of the bytes, and the array the caller handed over. */
    class Write(val mode: WriteMode, val bytes: ByteArray, val handed: ByteArray)

    private var held: ByteArray? = null
    val writes = mutableListOf<Write>()
    val served = mutableListOf<ByteArray>()
    val markerWrites = mutableListOf<Marker>()
    var erases = 0
        private set

    override fun probe(): StoreProbe = probe

    override suspend fun read(reason: String): ReadResult {
        val answer = read ?: held?.let { ReadResult.Bytes(it.copyOf()) } ?: ReadResult.NotFound
        if (answer is ReadResult.Bytes) served += answer.bytes
        return answer
    }

    override fun write(bytes: ByteArray, mode: WriteMode): WriteResult {
        writes += Write(mode, bytes.copyOf(), bytes)
        val answer = when {
            writes.size == failWrite -> WriteResult.Unavailable(Why.Other)
            else -> write ?: if (mode == WriteMode.Create && held != null) WriteResult.AlreadyExists else WriteResult.Written
        }
        if (answer == WriteResult.Written) {
            held = bytes.copyOf()
            probe = StoreProbe.Present
        }
        return answer
    }

    override fun erase(): EraseResult {
        erases++
        if (erase == EraseResult.Erased) {
            held = null
            probe = StoreProbe.Empty
        }
        return erase
    }

    override fun marker(): Marker = marker

    private var markerCalls = 0

    override fun writeMarker(marker: Marker): Boolean {
        markerCalls++
        if (markerCalls in failMarker) return false
        markerWrites += marker
        if (this.marker != Marker.Unsupported) this.marker = marker
        return true
    }
}

/** An 8-character check value for test records. Nothing derives a key in these tests, so any 8 ASCII characters fit. */
internal const val TEST_CHECK = "0a1b2c3d"

/** A record of the public test words. Arguments name only what a test changes. */
internal fun testRecord(
    words: List<String> = PUBLIC_WORDS_24,
    passphrase: String = "",
    network: KeyNetwork = KeyNetwork.TEST,
    fingerprint: String = TEST_CHECK,
    backupConfirmed: Boolean = false,
    imported: Boolean = false,
    appLockEnabled: Boolean = false,
    unconfirmedSend: SingleSigWallet.UnconfirmedSend? = null,
) = WalletRecord(words, passphrase, network, fingerprint, backupConfirmed, imported, appLockEnabled, unconfirmedSend)

/**
 * Bytes laid out by hand as the codec's KDoc documents version 1, so a test can store what the encoder never writes:
 * another version, network byte or flag bit, a bad word list, damaged UTF-8.
 */
internal fun v1Bytes(
    words: ByteArray = PUBLIC_WORDS_24.joinToString(" ").encodeToByteArray(),
    passphrase: ByteArray = ByteArray(0),
    version: Int = 1,
    network: Int = 0,
    flags: Int = 0,
    fingerprint: ByteArray = TEST_CHECK.encodeToByteArray(),
): ByteArray = byteArrayOf('M'.code.toByte(), 'B'.code.toByte(), version.toByte(), network.toByte(), flags.toByte()) +
    fingerprint + u16(words.size) + words + u16(passphrase.size) + passphrase

private fun u16(n: Int) = byteArrayOf((n shr 8).toByte(), n.toByte())

/** The section that follows the passphrase when flag 8 is set, laid out by hand as the codec's KDoc documents it. */
internal fun sendSection(txid: ByteArray, sinceSeconds: Long): ByteArray =
    u16(txid.size) + txid + ByteArray(8) { (sinceSeconds shr (56 - 8 * it)).toByte() }

/** A txid as Esplora writes it: 64 lowercase hex characters. */
internal const val TEST_TXID = "a14858161cc59cc1fdcafd3b8dfa3d8eb1731e69f28a304f806f98c1d2b9eb9f"

/** Every probe, each Unavailable reason included. */
internal val EVERY_PROBE: List<StoreProbe> =
    listOf(StoreProbe.Empty, StoreProbe.Present, StoreProbe.KeyGone) + Why.entries.map { StoreProbe.Unavailable(it) }

/** A probe's name for a failure message. Unavailable is a plain class, so its own toString would not say why. */
internal fun label(probe: StoreProbe): String = if (probe is StoreProbe.Unavailable) "Unavailable(${probe.why})" else probe.toString()

internal fun ByteArray.isZeroed() = all { it == 0.toByte() }

/** [SavedWallet.open] for a test that has no coroutine of its own. */
internal fun SavedWallet.openNow(): UnlockResult = runBlocking { open("test") }
