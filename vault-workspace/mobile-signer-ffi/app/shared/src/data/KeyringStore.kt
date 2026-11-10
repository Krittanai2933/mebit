package com.mebit.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mebit.ffi.signer.BleException
import com.mebit.ffi.signer.FoundWallet
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.PairingCodes
import com.mebit.ffi.signer.WalletKind
import com.mebit.ffi.signer.WalletLink
import com.mebit.ffi.signer.generateSoftwareMultisigKey
import com.mebit.ffi.signer.scanWallets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

enum class KeySourceLabel {
    ThisDevice,
    MebitOther,
    Jade,
    TrezorSafe7,
}

data class PendingKey(
    val label: String,
    val fingerprint: String,
    val xpub: String,
    val derivationPath: String,
    val source: KeySourceLabel,
    val isMock: Boolean = false,
)

data class SavedKey(
    val id: String,
    val label: String,
    val fingerprint: String,
    val xpub: String,
    val derivationPath: String,
    val source: KeySourceLabel,
    val verified: Boolean,
    val isMock: Boolean = false,
)

/** Why a scan could not list everything. The wallets found before it stay in [HardwareScan.Done.found]. */
sealed interface ScanProblem {
    data object BluetoothOff : ScanProblem
    data class PermissionDenied(val message: String) : ScanProblem
    data class Failed(val message: String) : ScanProblem
}

/** What the hardware screen lists: nothing yet, the wallets seen so far, or the end of a scan. */
sealed interface HardwareScan {
    data object Idle : HardwareScan
    data class Scanning(val found: List<FoundWallet>) : HardwareScan
    data class Done(val found: List<FoundWallet>, val problem: ScanProblem?) : HardwareScan
}

/** How [KeyringStore.enrollHardware] ended. [Failed] also covers a call ignored because a connect was already running. */
enum class EnrollResult { Enrolled, UnsupportedModel, Failed }

/**
 * Keyring for Movie Page 01–05 / T13–T15, kept across restarts in [book]. Only [confirmFingerprint] after a VerifyKey match may add a verified key.
 *
 * [scan] lists the wallets of one kind in range. Tests pass a fake, so they need no Bluetooth. [ownKeys] is each wallet's own key, in place order.
 */
class KeyringStore(
    private val scan: suspend (WalletKind) -> List<FoundWallet> = { scanWallets(it) },
    private val book: SavedVaults = SavedVaults.memoryOnly(),
    private val ownKeys: () -> List<SavedKey> = { emptyList() },
) {
    /** Every wallet's own key first, then every saved key, a key already saved showing once. Read-only, because [SavedVaults.edit] is the only way to change what is saved. */
    val keys: List<SavedKey>
        get() {
            val saved = book.current.keys
            return ownKeys().filterNot { o -> saved.any { it.fingerprint.equals(o.fingerprint, ignoreCase = true) } } + saved
        }

    var pending: PendingKey? by mutableStateOf(null)
        private set
    var lastError: String? by mutableStateOf(null)
        private set
    var lastMismatch: Boolean by mutableStateOf(false)
        private set

    /** Six digits a Trezor Safe 7 shows while pairing. A Jade never reads this. */
    var pairingCode: String by mutableStateOf("")

    var hardwareBusy: Boolean by mutableStateOf(false)
        private set

    var hardwareScan: HardwareScan by mutableStateOf(HardwareScan.Idle)
        private set

    fun clearError() {
        lastError = null
        lastMismatch = false
    }

    /** Real tpub for a CI run that has no pasted fixture. */
    fun startMebitOtherMock(network: KeyNetwork = KeyNetwork.TEST) {
        clearError()
        try {
            val key = generateSoftwareMultisigKey("", network, (keys.size + 17).toUInt())
            pending = PendingKey(
                label = "mebit · เครื่องอื่น · จำลอง",
                fingerprint = key.masterFingerprint,
                xpub = key.xpub,
                derivationPath = key.derivationPath,
                source = KeySourceLabel.MebitOther,
                isMock = true,
            )
        } catch (e: Exception) {
            lastError = e.message
            pending = null
        }
    }

    /**
     * Paste or share for docs/04 #26. No QR.
     * A bad paste clears [pending] and sets [lastError].
     */
    fun startMebitOtherFromPaste(text: String): Boolean {
        clearError()
        pending = null
        return try {
            pending = parseExternalKeyPaste(text)
            true
        } catch (e: IllegalArgumentException) {
            lastError = e.message
            false
        }
    }

    /** Demo HW enrollment without live BLE — real account key, HW label. */
    fun startHardwareMock(source: KeySourceLabel, network: KeyNetwork = KeyNetwork.TEST) {
        clearError()
        require(source == KeySourceLabel.Jade || source == KeySourceLabel.TrezorSafe7)
        try {
            val account = (keys.size + if (source == KeySourceLabel.Jade) 31 else 47).toUInt()
            val key = generateSoftwareMultisigKey("", network, account)
            pending = PendingKey(
                label = defaultKeyLabel(source, isMock = true),
                fingerprint = key.masterFingerprint,
                xpub = key.xpub,
                derivationPath = key.derivationPath,
                source = source,
                isMock = true,
            )
        } catch (e: Exception) {
            lastError = e.message
            pending = null
        }
    }

    /**
     * Scans for a Jade, then a Safe 7 (about 4 s each) and publishes the rows after each kind. A kind that is
     * not in range adds no rows and is no problem. Ignored while a scan or a connect is running. A cancelled
     * scan keeps the rows it had.
     */
    suspend fun scanHardware() {
        if (hardwareScan is HardwareScan.Scanning || hardwareBusy) return
        val found = mutableListOf<FoundWallet>()
        var problem: ScanProblem? = null
        hardwareScan = HardwareScan.Scanning(emptyList())
        try {
            for (kind in listOf(WalletKind.JADE, WalletKind.TREZOR_SAFE7)) {
                try {
                    found += scan(kind)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: BleException.NotFound) {
                    // On iOS this is also what Bluetooth off looks like, so it cannot be told apart from an empty room.
                } catch (e: Exception) {
                    problem = problem ?: e.toScanProblem()
                }
                hardwareScan = HardwareScan.Scanning(found.toList())
            }
        } finally {
            hardwareScan = HardwareScan.Done(found.toList(), problem)
        }
    }

    /**
     * Live BLE enroll of a wallet [scanHardware] found: connect, unlock, read the multisig account key and
     * stage it in [pending]. Nothing is saved until [confirmFingerprint]. A model mebit does not support is
     * [EnrollResult.UnsupportedModel] and sets no error. Any other failure sets [lastError].
     */
    suspend fun enrollHardware(found: FoundWallet): EnrollResult {
        if (hardwareBusy) return EnrollResult.Failed
        hardwareBusy = true
        clearError()
        pending = null
        // A code typed for an earlier connect must not answer this one.
        pairingCode = ""
        val source = hardwareSource(found.kind)
        val codes = FieldPairingCodes(
            jade = source == KeySourceLabel.Jade,
            read = { pairingCode },
            consume = { pairingCode = "" },
        )
        try {
            val link = WalletLink(found)
            try {
                link.connect(codes)
                link.unlock()
                val key = link.accountXpub(TESTNET_MULTISIG_ACCOUNT)
                pending = PendingKey(
                    label = defaultKeyLabel(source, isMock = false),
                    fingerprint = key.masterFingerprint,
                    xpub = key.xpub,
                    derivationPath = key.derivationPath,
                    source = source,
                )
            } finally {
                withContext(NonCancellable) {
                    runCatching { link.disconnect() }
                }
                link.close()
            }
            return EnrollResult.Enrolled
        } catch (e: CancellationException) {
            pending = null
            throw e
        } catch (e: BleException.WrongDevice) {
            pending = null
            return EnrollResult.UnsupportedModel
        } catch (e: Exception) {
            lastError = e.message ?: "เชื่อมต่อไม่สำเร็จ"
            pending = null
            return EnrollResult.Failed
        } finally {
            hardwareBusy = false
        }
    }

    /**
     * Confirm fingerprint. Mismatch clears pending and does not save.
     * @return true if saved
     */
    fun confirmFingerprint(observed: String, label: String? = null): Boolean {
        clearError()
        val p = pending ?: run {
            lastError = "ไม่มีกุญแจรอยืนยัน"
            return false
        }
        if (!observed.filterNot(Char::isWhitespace).equals(p.fingerprint, ignoreCase = true)) {
            lastMismatch = true
            lastError = "Fingerprint ไม่ตรง · ไม่บันทึกเข้าคลัง"
            pending = null
            return false
        }
        val name = label?.trim()?.takeIf { it.isNotEmpty() } ?: p.label
        val savedLabel = if (p.isMock && !name.endsWith(" · จำลอง")) "$name · จำลอง" else name
        book.edit { b ->
            // The first number no saved key uses, because a size-based id repeats after a delete and Choose Keys counts picks by id.
            val id = generateSequence(b.keys.size + 1) { it + 1 }.map { "key_${it}_${p.fingerprint}" }.first { candidate -> b.keys.none { it.id == candidate } }
            b.copy(
                keys = b.keys + SavedKey(
                    id = id,
                    label = savedLabel,
                    fingerprint = p.fingerprint,
                    xpub = p.xpub,
                    derivationPath = p.derivationPath,
                    source = p.source,
                    verified = true,
                    isMock = p.isMock,
                ),
            )
        }
        pending = null
        return true
    }

    fun discardPending() {
        pending = null
        clearError()
    }

    fun tryDelete(id: String): Boolean {
        val key = keys.find { it.id == id } ?: return false
        if (inVault(key)) {
            lastError = "ลบไม่ได้ · กุญแจยังถูกใช้ใน Vault"
            return false
        }
        book.edit { b -> b.copy(keys = b.keys.filterNot { it.id == id }) }
        return true
    }

    fun verifiedCount(): Int = keys.count { it.verified }

    /** A wallet's own key comes from its session and has no saved row to remove. */
    fun canDelete(key: SavedKey): Boolean = ownKeys().none { it.id == key.id }

    private fun inVault(key: SavedKey) = book.current.usesKey(key.fingerprint)
}

/** BIP-48 P2WSH account on testnet. Coin type 1, account 0, script type 2. */
internal const val TESTNET_MULTISIG_ACCOUNT = "m/48'/1'/0'/2'"

internal fun hardwareName(source: KeySourceLabel): String = when (source) {
    KeySourceLabel.Jade -> "Jade"
    KeySourceLabel.TrezorSafe7 -> "Trezor Safe 7"
    KeySourceLabel.ThisDevice, KeySourceLabel.MebitOther -> error("not hardware")
}

/** Penpot 2.2's card title for a source, which 2.1 and 3.2 repeat as the start of a key's line. */
internal fun keySourceName(source: KeySourceLabel): String = when (source) {
    KeySourceLabel.ThisDevice -> "mebit บนเครื่องนี้"
    KeySourceLabel.MebitOther -> "mebit บนเครื่องอื่น"
    KeySourceLabel.Jade, KeySourceLabel.TrezorSafe7 -> hardwareName(source)
}

/** "Jade · 7f2c a91b": the source, then the fingerprint in groups of four. The sub line on Penpot 3.2 and 2.1. */
internal fun keyLine(key: SavedKey): String = "${keySourceName(key.source)} · ${fingerprintGroups(key.fingerprint)}"

internal fun hardwareMonogram(source: KeySourceLabel): String = when (source) {
    KeySourceLabel.Jade -> "JD"
    KeySourceLabel.TrezorSafe7 -> "TZ"
    KeySourceLabel.ThisDevice, KeySourceLabel.MebitOther -> error("not hardware")
}

/** "7F2CA91B" becomes "7f2c a91b": lowercase, in groups of four. */
internal fun fingerprintGroups(fingerprint: String): String = fingerprint.lowercase().chunked(4).joinToString(" ")

internal fun hardwareSource(kind: WalletKind): KeySourceLabel = when (kind) {
    WalletKind.JADE -> KeySourceLabel.Jade
    WalletKind.TREZOR_SAFE7 -> KeySourceLabel.TrezorSafe7
}

/** The name a hardware key starts with on Confirm Key: "Jade ของฉัน" for a real device, "Jade · จำลอง" for a demo one. */
internal fun defaultKeyLabel(source: KeySourceLabel, isMock: Boolean): String =
    hardwareName(source) + if (isMock) " · จำลอง" else " ของฉัน"

private fun Exception.toScanProblem(): ScanProblem = when (this) {
    is BleException.BluetoothOff -> ScanProblem.BluetoothOff
    is BleException.PermissionDenied -> ScanProblem.PermissionDenied(message.orEmpty())
    else -> ScanProblem.Failed(message ?: "ค้นหาไม่สำเร็จ")
}

/**
 * Jade returns null. A Safe 7 shows its digits during connect, so this waits until [read]
 * is six digits, then [consume] clears them so the next ask does not resend the same code.
 */
internal class FieldPairingCodes(
    private val jade: Boolean,
    private val read: () -> String,
    private val consume: () -> Unit,
) : PairingCodes {
    override suspend fun pairingCode(): String? {
        if (jade) return null
        while (true) {
            val code = read().trim()
            if (code.length == 6 && code.all(Char::isDigit)) {
                consume()
                return code
            }
            delay(200)
        }
    }
}
