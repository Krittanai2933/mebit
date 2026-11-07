package com.mebit.app.data

import com.mebit.app.CopyCounter
import com.mebit.app.EraseResult
import com.mebit.app.Marker
import com.mebit.app.PlainFile
import com.mebit.app.ReadResult
import com.mebit.app.SecureStore
import com.mebit.app.StoreProbe
import com.mebit.app.StoreVersion
import com.mebit.app.Why
import com.mebit.app.WriteMode
import com.mebit.app.WriteResult
import com.mebit.ffi.signer.AddressType
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
    private val copies = CopyCounter()
    val writes = mutableListOf<Write>()
    val served = mutableListOf<ByteArray>()
    val markerWrites = mutableListOf<Marker>()
    var erases = 0
        private set

    override fun probe(): StoreProbe = probe

    override suspend fun read(reason: String): ReadResult {
        val answer = read ?: held?.let { ReadResult.Bytes(it.copyOf(), copies.current) } ?: ReadResult.NotFound
        if (answer is ReadResult.Bytes) served += answer.bytes
        return answer
    }

    override fun write(bytes: ByteArray, mode: WriteMode): WriteResult {
        writes += Write(mode, bytes.copyOf(), bytes)
        copies.refuse(mode)?.let { return it }
        val answer = when {
            writes.size == failWrite -> WriteResult.Unavailable(Why.Other)
            else -> write ?: if (mode == WriteMode.Create && held != null) WriteResult.AlreadyExists else WriteResult.Written(copies.current)
        }
        if (answer !is WriteResult.Written) return answer
        held = bytes.copyOf()
        probe = StoreProbe.Present
        return WriteResult.Written(copies.advance())
    }

    override fun erase(): EraseResult {
        erases++
        val version = copies.advance()
        val scripted = erase
        if (scripted is EraseResult.Failed) return EraseResult.Failed(scripted.why, version)
        held = null
        probe = StoreProbe.Empty
        return EraseResult.Erased
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

/** A copy version for a test that scripts a read or installs a record by hand, where no store counted one. A fresh store's own. */
internal val ANY_VERSION: StoreVersion = CopyCounter().current

/** Scripts a [FakeSecureStore] erase that fails with [why]. The fake stamps the version it counted on the answer it returns. */
internal fun failedErase(why: Why) = EraseResult.Failed(why, ANY_VERSION)

/** Each write's kind, for a test that checks which writes a session made. */
internal fun FakeSecureStore.modes(): List<String> = writes.map { if (it.mode is WriteMode.Replace) "Replace" else "Create" }

/** A record of the public test words. Arguments name only what a test changes. */
internal fun testRecord(
    words: List<String> = PUBLIC_WORDS_24,
    passphrase: String = "",
    network: KeyNetwork = KeyNetwork.TEST,
    fingerprint: String = TEST_CHECK,
    backupConfirmed: Boolean = false,
    imported: Boolean = false,
    appLockEnabled: Boolean = false,
    addressType: AddressType = AddressType.NATIVE_SEGWIT,
    addressTypeConfirmed: Boolean = true,
) = WalletRecord(words, passphrase, network, fingerprint, backupConfirmed, imported, appLockEnabled, addressType, addressTypeConfirmed)

/**
 * An in-memory [PlainFile] for common tests. [text] is what the file holds, null for no file. [failWrite] makes each write fail
 * and [failDelete] each delete, leaving the file as it was, and [unreadable] each read, as a full disk or a damaged file would,
 * until a delete removes the file. [onRead] runs after a read took the text and before it returns it, so a test can hold a reader
 * between what it read and what it writes.
 */
internal class FakePlainFile : PlainFile {
    var text: String? = null
    var failWrite = false
    var failDelete = false
    var unreadable = false
    var onRead: () -> Unit = {}
    var writes = 0
        private set

    override fun read(): String? {
        val read = if (unreadable) null else text.orEmpty()
        onRead()
        return read
    }

    override fun write(text: String): Boolean {
        writes++
        if (failWrite) return false
        this.text = text
        return true
    }

    override fun delete(): Boolean {
        if (failDelete) return false
        text = null
        unreadable = false
        return true
    }
}

/** The guard this file holds for the wallet [fingerprint], read the way a new session reads it. */
internal fun FakePlainFile.guardOf(fingerprint: String = TEST_CHECK): SingleSigWallet.Unsettled? = PendingPayments(this).of(fingerprint)

/** The bytes of a record that a build before the guard file saved with [pending] as its guard (flag 8). */
internal fun oldRecordBytes(pending: SingleSigWallet.UnconfirmedSend, flags: Int = 0): ByteArray =
    v1Bytes(flags = flags or 8) + sendSection(pending.txid.encodeToByteArray(), pending.sinceSeconds)

/** A phone whose store holds [bytes] as the saved wallet, written the way an older build wrote it. */
internal fun storeHolding(bytes: ByteArray) = FakeSecureStore().also { it.write(bytes, WriteMode.Create) }

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

/**
 * A real signed testnet4 payment shaped as the hot wallet signs one (version 2, one P2WPKH input with a replaceable sequence, two
 * P2WPKH outputs) and its txid, from `rust/fixtures/proof/testnet4_tx_hex_real.txt`, whose txid the core's proof tests fold into
 * the header of block 156,696. A Python run of the txid rule gave the same id.
 */
internal const val SIGNED_HEX =
    "020000000001014e1476a453b5bf2d197fe1c55d7d77c578b4eea36cdf114063c882814ec3b2280100000000fdffffff02899f050000000000160014f922f0c9" +
        "f366db682ab541cdcd4de10b289b14bbe8030000000000001600145d8e5de55dc968ddfc31227f5014930f0c7d9a5c0247304402206aa15f0211d3dbb787" +
        "74d01775dfeec05d435f2b5d762ed255eb13d810b4a3040220489fc1588fd23ae90f67fe28e380e3182f638529dc4f500e89bf835a2e6c41c00121021fdf63" +
        "95c09dd67cc9394ca0e19c631d01ad6eb01c5d232ca6a51477f0ff0fb506640200"
internal const val SIGNED_TXID = "c5ed075ef44b807f51123cf0c6e53c75385a17a510f995b560bf73b1102d5474"

/**
 * Another real transaction, from `rust/fixtures/proof/mainnet_tx_hex_real.txt`, a taproot spend whose witness holds three items, one
 * of them a long script, with its txid as the same proof tests check it. Its bytes are never [SIGNED_TXID]'s.
 */
internal const val OTHER_SIGNED_HEX =
    "020000000001011178de7c75a21c6018da2b2a86f922d9857bc5de4f3f56ac9d3477fa54f6310e0a00000000fdffffff012202000000000000160014734b932f" +
        "31231f1ef91ccfcabd4cc973c7485fc103406911949a601c7d37bdb334646d62e7dde342b3d429cbd4e66530b6262146efbd03a8dae2822fbe50106cd119" +
        "78a4fba92e8c06cc813c2b9012645ee935c611d98820117f692257b2331233b5705ce9c682be8719ff1b2b64cbca290bd6faeb54423eac06e1e2d18589017" +
        "50063036f7264010118746578742f706c61696e3b636861727365743d7574662d38003a7b2270223a226272632d3230222c226f70223a226d696e74222c22" +
        "7469636b223a2273617473222c22616d74223a22313030303030303030227d6821c0117f692257b2331233b5705ce9c682be8719ff1b2b64cbca290bd6faeb" +
        "54423e00000000"
internal const val OTHER_SIGNED_TXID = "f13c5f8fbe7f01daf475ce39fdfb6bea766971a2a5cb3398f7da0e3fe7374877"

/** Every probe, each Unavailable reason included. */
internal val EVERY_PROBE: List<StoreProbe> =
    listOf(StoreProbe.Empty, StoreProbe.Present, StoreProbe.KeyGone) + Why.entries.map { StoreProbe.Unavailable(it) }

/** A probe's name for a failure message. Unavailable is a plain class, so its own toString would not say why. */
internal fun label(probe: StoreProbe): String = if (probe is StoreProbe.Unavailable) "Unavailable(${probe.why})" else probe.toString()

internal fun ByteArray.isZeroed() = all { it == 0.toByte() }

/** [SavedWallet.open] for a test that has no coroutine of its own. */
internal fun SavedWallet.openNow(): UnlockResult = runBlocking { open("test") }
