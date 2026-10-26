package com.mebit.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mebit.ffi.signer.BleException
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
)

data class SavedKey(
    val id: String,
    val label: String,
    val fingerprint: String,
    val xpub: String,
    val derivationPath: String,
    val source: KeySourceLabel,
    val verified: Boolean,
    val usedInVault: Boolean = false,
)

/**
 * In-memory keyring for Movie Page 01–05 / T13–T15.
 * Only [commitPending] after VerifyKey match may add a verified key.
 */
class KeyringStore {
    val keys = mutableStateListOf<SavedKey>()
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

    fun clearError() {
        lastError = null
        lastMismatch = false
    }

    fun startThisDevice(passphrase: String = "", network: KeyNetwork = KeyNetwork.TEST) {
        clearError()
        try {
            val key = generateSoftwareMultisigKey(passphrase, network, keys.size.toUInt())
            pending = PendingKey(
                label = "มือถือ · ${key.masterFingerprint.take(8)}",
                fingerprint = key.masterFingerprint,
                xpub = key.xpub,
                derivationPath = key.derivationPath,
                source = KeySourceLabel.ThisDevice,
            )
        } catch (e: Exception) {
            lastError = e.message
            pending = null
        }
    }

    /** Mock mebit-on-other-device until docs/04 #26 (real tpub so vault FFI works). */
    fun startMebitOtherMock(network: KeyNetwork = KeyNetwork.TEST) {
        clearError()
        try {
            val key = generateSoftwareMultisigKey("", network, (keys.size + 17).toUInt())
            pending = PendingKey(
                label = "mebit · เครื่องอื่น (mock)",
                fingerprint = key.masterFingerprint,
                xpub = key.xpub,
                derivationPath = key.derivationPath,
                source = KeySourceLabel.MebitOther,
            )
        } catch (e: Exception) {
            lastError = e.message
            pending = null
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
                label = when (source) {
                    KeySourceLabel.Jade -> "Jade · BLE"
                    KeySourceLabel.TrezorSafe7 -> "Trezor Safe 7 · BLE"
                    KeySourceLabel.ThisDevice, KeySourceLabel.MebitOther -> error("not hardware")
                },
                fingerprint = key.masterFingerprint,
                xpub = key.xpub,
                derivationPath = key.derivationPath,
                source = source,
            )
        } catch (e: Exception) {
            lastError = e.message
            pending = null
        }
    }

    /** Live BLE enroll. A wrong model sets [lastError] and leaves the unsupported-model screen as its own action. */
    suspend fun startHardwareLive(source: KeySourceLabel) {
        require(source == KeySourceLabel.Jade || source == KeySourceLabel.TrezorSafe7)
        if (hardwareBusy) return
        hardwareBusy = true
        clearError()
        pending = null
        val kind = when (source) {
            KeySourceLabel.Jade -> WalletKind.JADE
            KeySourceLabel.TrezorSafe7 -> WalletKind.TREZOR_SAFE7
            KeySourceLabel.ThisDevice, KeySourceLabel.MebitOther -> error("not hardware")
        }
        val codes = FieldPairingCodes(
            jade = source == KeySourceLabel.Jade,
            read = { pairingCode },
            consume = { pairingCode = "" },
        )
        try {
            val found = scanWallets(kind).firstOrNull()
                ?: throw BleException.NotFound("ไม่พบอุปกรณ์ในระยะ")
            if (found.kind != kind) {
                throw BleException.WrongDevice("อุปกรณ์นี้ไม่ใช่ ${hardwareName(source)}")
            }
            val link = WalletLink(found)
            try {
                link.connect(codes)
                link.unlock()
                val key = link.accountXpub(TESTNET_MULTISIG_ACCOUNT)
                pending = PendingKey(
                    label = "${hardwareName(source)} · ${found.name}",
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
        } catch (e: CancellationException) {
            pending = null
            throw e
        } catch (e: Exception) {
            lastError = e.message ?: "เชื่อมต่อไม่สำเร็จ"
            pending = null
        } finally {
            hardwareBusy = false
        }
    }

    /**
     * Confirm fingerprint. Mismatch clears pending and does not save.
     * @return true if saved
     */
    fun confirmFingerprint(observed: String): Boolean {
        clearError()
        val p = pending ?: run {
            lastError = "ไม่มีกุญแจรอยืนยัน"
            return false
        }
        if (!observed.equals(p.fingerprint, ignoreCase = true)) {
            lastMismatch = true
            lastError = "Fingerprint ไม่ตรง · ไม่บันทึกเข้าคลัง"
            pending = null
            return false
        }
        keys.add(
            SavedKey(
                id = "key_${keys.size + 1}_${p.fingerprint}",
                label = p.label,
                fingerprint = p.fingerprint,
                xpub = p.xpub,
                derivationPath = p.derivationPath,
                source = p.source,
                verified = true,
            ),
        )
        pending = null
        return true
    }

    fun importVerifiedFromOnboarding(session: OnboardingSession) {
        if (session.fingerprint.isBlank()) return
        if (keys.any { it.fingerprint.equals(session.fingerprint, ignoreCase = true) }) return
        keys.add(
            SavedKey(
                id = "onboard_${session.fingerprint}",
                label = "มือถือ · onboarding",
                fingerprint = session.fingerprint,
                xpub = session.xpub,
                derivationPath = session.derivationPath,
                source = KeySourceLabel.ThisDevice,
                verified = session.backupConfirmed,
            ),
        )
    }

    fun markUsedInVault(fingerprint: String) {
        val i = keys.indexOfFirst { it.fingerprint.equals(fingerprint, ignoreCase = true) }
        if (i >= 0) keys[i] = keys[i].copy(usedInVault = true)
    }

    fun tryDelete(id: String): Boolean {
        val key = keys.find { it.id == id } ?: return false
        if (key.usedInVault) {
            lastError = "ลบไม่ได้ · กุญแจยังถูกใช้ใน Vault"
            return false
        }
        keys.removeAll { it.id == id }
        return true
    }

    fun verifiedCount(): Int = keys.count { it.verified }
}

/** BIP-48 P2WSH account on testnet. Coin type 1, account 0, script type 2. */
internal const val TESTNET_MULTISIG_ACCOUNT = "m/48'/1'/0'/2'"

private fun hardwareName(source: KeySourceLabel): String = when (source) {
    KeySourceLabel.Jade -> "Jade"
    KeySourceLabel.TrezorSafe7 -> "Trezor Safe 7"
    KeySourceLabel.ThisDevice, KeySourceLabel.MebitOther -> error("not hardware")
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
