package com.mebit.app.data

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mebit.app.VaultsStorage
import com.mebit.ffi.signer.computeVaultAddressMultisig
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** Everything the phone remembers besides the wallet record, one value so one write can never store half of it. Holds no recovery words and no private key. */
data class VaultBook(val keys: List<SavedKey> = emptyList(), val vaults: List<CreatedVault> = emptyList())

/** Why the saved book is not simply in use. Each value is a notice on two screens and nothing else, so it can never stop the app reaching the wallet. */
enum class VaultsHealth {
    Ok,

    /** A write failed. This run is ahead of the stored copy, and the next sync tries again. */
    SaveFailed,

    /** The stored copy was read and cannot be used. It is left alone, and this run stays in memory. */
    Damaged,

    /** A newer app wrote the stored copy. Never opened and never overwritten, like a Newer record. */
    Newer,
}

/** The keys and vaults saved on this phone. Takes a [VaultsStorage] and not a SecureStore, so it has no way to reach the wallet's record, and saves only while [owner] names a wallet. */
class SavedVaults internal constructor(
    private val storage: VaultsStorage?,
    private val owner: () -> String?,
    private val deriveAddress: (List<String>, UByte) -> String = { x, m -> computeVaultAddressMultisig(x, m) },
) {
    /** The one copy of the state. Screens read it through [KeyringStore.keys] and [VaultDraft.created]. */
    var current: VaultBook by mutableStateOf(VaultBook())
        private set

    var health: VaultsHealth by mutableStateOf(VaultsHealth.Ok)
        private set

    /** The owner [current] was read for, null until it has been read. */
    private var opened: String? = null

    /** [current] is ahead of the stored copy. */
    private var owed = false

    /** The edits made while the stored book could not be read, so they can be run again over it once it can. */
    private var unopened = emptyList<(VaultBook) -> VaultBook>()

    /** Reads the stored book once per owner this run sees and writes what is owed. Never prompts, never throws, and safe to run any number of times. */
    fun sync(): Unit = synchronized(vaultsLock) {
        val me = me()
        if (me == null) {
            if (opened != null) {
                current = VaultBook()
                health = VaultsHealth.Ok
                opened = null
                owed = false
                unopened = emptyList()
            }
            return@synchronized
        }
        if (opened != me && !open(me)) return@synchronized
        if (owed) save(me)
    }

    /** The only way to change what is saved. [change] runs on the newest state, the result shows at once and is saved before this returns. */
    fun edit(change: (VaultBook) -> VaultBook): Unit = synchronized(vaultsLock) {
        val me = me()
        val savable = me?.takeIf { opened == it || open(it) }
        current = change(current)
        owed = true
        if (savable != null) {
            save(savable)
        } else {
            unopened = unopened + change
            if (me != null) health = VaultsHealth.SaveFailed
        }
    }

    private fun me(): String? = if (storage == null) null else owner()

    /** True when the stored copy was read and judged. False when it could not be read this moment, which concludes nothing. */
    private fun open(me: String): Boolean {
        val text = storage?.read() ?: return false
        when (val decoded = if (text.isEmpty()) null else VaultBookCodec.decode(text)) {
            null -> keepMemory()
            VaultBookCodec.Decoded.Damaged -> refuse(VaultsHealth.Damaged)
            VaultBookCodec.Decoded.Newer -> refuse(VaultsHealth.Newer)
            is VaultBookCodec.Decoded.Ok -> when {
                decoded.owner != me -> {
                    storage?.delete() // left by a wallet this phone no longer holds
                    keepMemory()
                }
                !addressesMatch(decoded.book) -> refuse(VaultsHealth.Damaged)
                else -> {
                    current = unopened.fold(decoded.book) { book, change -> change(book) }
                    owed = unopened.isNotEmpty()
                    health = VaultsHealth.Ok
                }
            }
        }
        opened = me
        unopened = emptyList()
        return true
    }

    private fun keepMemory() {
        owed = current != VaultBook()
        health = VaultsHealth.Ok
    }

    private fun refuse(why: VaultsHealth) {
        owed = true
        health = why
    }

    private fun save(me: String) {
        if (health == VaultsHealth.Damaged || health == VaultsHealth.Newer) return
        val text = VaultBookCodec.encodeChecked(me, current)
        if (text != null && storage?.write(text) == true) {
            owed = false
            health = VaultsHealth.Ok
        } else {
            health = VaultsHealth.SaveFailed
        }
    }

    // A saved address is a check value and not a cache, so a stored book can never put a wrong address in a Receive QR.
    private fun addressesMatch(book: VaultBook) = book.vaults.all { v ->
        try {
            deriveAddress(xpubsIn(v.bsms), v.m.toUByte()) == v.address
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        /** Nothing is ever saved. For a bare [com.mebit.app.navigation.MebitNavHost] and for tests that do not care. */
        fun memoryOnly(): SavedVaults = SavedVaults(null, owner = { null })
    }
}

/** One lock for every [SavedVaults] in the process, because they all share the one stored copy. */
private val vaultsLock = SynchronizedObject()

/** The wallet's own key as the keyring shows it, null for a session with no key. Never saved, so [SavedKey.verified] is always the session's backupConfirmed now. */
internal fun OnboardingSession.ownKey(): SavedKey? = fingerprint.takeIf { it.isNotBlank() }?.let { fp ->
    SavedKey(
        id = "onboard_$fp",
        label = "มือถือ · onboarding",
        fingerprint = fp,
        xpub = xpub,
        derivationPath = derivationPath,
        source = KeySourceLabel.ThisDevice,
        verified = backupConfirmed,
    )
}

/** The fingerprint of the wallet this session holds on the phone, or null while there is none. This is the one rule for whether the book may be saved. */
internal fun OnboardingSession.vaultsOwner(): String? = fingerprint.takeIf { saved == SaveState.Saved && it.isNotBlank() }

/** The extended keys in the descriptor line of [bsms], in order. Empty when the text is not the shape VaultDraft writes, which then fails the address check. */
private fun xpubsIn(bsms: String): List<String> =
    bsms.lines().getOrNull(1)?.substringAfter("sortedmulti(")?.substringBeforeLast("))")?.split(",")?.drop(1)?.map { it.substringAfterLast(']') }.orEmpty()

/** "MV1", then every value as "<length in UTF-16 units> <text>\n", so labels, Thai and BSMS newlines need no escaping. `verified` is not written, because a key is here only after Verify Key. */
private object VaultBookCodec {
    private const val MAX_BYTES = 1_048_576
    private val SOURCE_CODE = mapOf(KeySourceLabel.ThisDevice to 0, KeySourceLabel.MebitOther to 1, KeySourceLabel.Jade to 2, KeySourceLabel.TrezorSafe7 to 3)
    private val POLICY_CODE = mapOf(CustodyPolicy.PersonalCustody to 0)

    sealed interface Decoded {
        class Ok(val owner: String, val book: VaultBook) : Decoded

        data object Damaged : Decoded

        data object Newer : Decoded
    }

    /** The text for ([owner], [book]), or null when [decode] would not read exactly these values back. Null is never written. */
    fun encodeChecked(owner: String, book: VaultBook): String? {
        val text = StringBuilder("MV1\n")
        text.field(owner)
        text.field(book.keys.size)
        for (key in book.keys) {
            text.field(key.id)
            text.field(key.label)
            text.field(key.fingerprint)
            text.field(key.xpub)
            text.field(key.derivationPath)
            text.field(SOURCE_CODE[key.source] ?: return null)
            text.field(if (key.isMock) 1 else 0)
        }
        text.field(book.vaults.size)
        for (vault in book.vaults) {
            text.field(vault.id)
            text.field(vault.name)
            text.field(vault.m)
            text.field(vault.n)
            text.field(POLICY_CODE[vault.policy] ?: return null)
            text.field(vault.address)
            text.field(vault.bsms)
            text.field(if (vault.hasMockKey) 1 else 0)
            text.field(vault.keyFingerprints.size)
            vault.keyFingerprints.forEach { text.field(it) }
        }
        val encoded = text.toString()
        try {
            encoded.encodeToByteArray(throwOnInvalidSequence = true) // a lone surrogate in a label would not read back
        } catch (e: CharacterCodingException) {
            return null
        }
        val back = decode(encoded) as? Decoded.Ok ?: return null
        return encoded.takeIf { back.owner == owner && back.book == book }
    }

    /** Never throws. A version above 1 or a code v1 never writes is Newer, because the rest is not then judged by v1 rules. Anything else encode never writes is Damaged. */
    fun decode(text: String): Decoded {
        if (text.encodeToByteArray().size > MAX_BYTES || !text.startsWith("MV")) return Decoded.Damaged
        val headerEnd = text.indexOf('\n')
        if (headerEnd < 0) return Decoded.Damaged
        val version = text.substring(2, headerEnd)
        if (version.isEmpty() || version.startsWith("0") || version.any { it !in '0'..'9' }) return Decoded.Damaged
        if (version != "1") return Decoded.Newer
        val fields = Fields(text, headerEnd + 1)
        val owner = fields.text() ?: return Decoded.Damaged
        val keys = buildList {
            repeat(fields.number() ?: return Decoded.Damaged) {
                val id = fields.text() ?: return Decoded.Damaged
                val label = fields.text() ?: return Decoded.Damaged
                val fingerprint = fields.text() ?: return Decoded.Damaged
                val xpub = fields.text() ?: return Decoded.Damaged
                val derivationPath = fields.text() ?: return Decoded.Damaged
                val code = fields.number() ?: return Decoded.Damaged
                val source = SOURCE_CODE.entries.firstOrNull { it.value == code }?.key ?: return Decoded.Newer
                val isMock = fields.flag() ?: return Decoded.Damaged
                add(SavedKey(id, label, fingerprint, xpub, derivationPath, source, verified = true, isMock = isMock))
            }
        }
        val vaults = buildList {
            repeat(fields.number() ?: return Decoded.Damaged) {
                val id = fields.text() ?: return Decoded.Damaged
                val name = fields.text() ?: return Decoded.Damaged
                val m = fields.number() ?: return Decoded.Damaged
                val n = fields.number() ?: return Decoded.Damaged
                val code = fields.number() ?: return Decoded.Damaged
                val policy = POLICY_CODE.entries.firstOrNull { it.value == code }?.key ?: return Decoded.Newer
                val address = fields.text() ?: return Decoded.Damaged
                val bsms = fields.text() ?: return Decoded.Damaged
                val hasMockKey = fields.flag() ?: return Decoded.Damaged
                val count = fields.number() ?: return Decoded.Damaged
                val fingerprints = buildList { repeat(count) { add(fields.text() ?: return Decoded.Damaged) } }
                if (m !in 1..n || count != n) return Decoded.Damaged
                add(CreatedVault(id, name, m, n, policy, address, bsms, fingerprints, hasMockKey))
            }
        }
        return if (fields.atEnd()) Decoded.Ok(owner, VaultBook(keys, vaults)) else Decoded.Damaged
    }

    private fun StringBuilder.field(value: Any) {
        val text = value.toString()
        append(text.length).append(' ').append(text).append('\n')
    }

    /** [digits] as a number when it is written the one way encode writes it. */
    private fun canonical(digits: String): Int? = digits.toIntOrNull()?.takeIf { it >= 0 && it.toString() == digits }

    private class Fields(private val text: String, private var at: Int) {
        fun atEnd(): Boolean = at == text.length

        /** The next field, or null when the text stops short or is not what encode writes. */
        fun text(): String? {
            val space = text.indexOf(' ', at)
            if (space < 0) return null
            val length = canonical(text.substring(at, space)) ?: return null
            val start = space + 1
            if (length > text.length - start - 1 || text[start + length] != '\n') return null
            at = start + length + 1
            return text.substring(start, start + length)
        }

        fun number(): Int? = text()?.let { canonical(it) }

        fun flag(): Boolean? = when (text()) {
            "0" -> false
            "1" -> true
            else -> null
        }
    }
}

/** Pure. The line the keyring and vault list show. No Penpot board has it. */
internal fun vaultsNoticeText(health: VaultsHealth): String? = when (health) {
    VaultsHealth.Ok -> null
    VaultsHealth.SaveFailed -> "บันทึก Vault และกุญแจล่าสุดไม่สำเร็จ จะลองใหม่เมื่อเปิดแอปอีกครั้ง"
    VaultsHealth.Damaged -> "อ่าน Vault และกุญแจที่บันทึกไว้ไม่ได้ กระเป๋าและ 24 คำของคุณยังใช้ได้ตามปกติ"
    VaultsHealth.Newer -> "Vault และกุญแจถูกบันทึกโดยแอปเวอร์ชันใหม่กว่า อัปเดตแอปเพื่อดูรายการ"
}

/** One line, or nothing while the book is Ok. */
@Composable
internal fun VaultsNotice(vaults: SavedVaults) {
    vaultsNoticeText(vaults.health)?.let {
        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("vaults_notice"))
        Spacer(modifier = Modifier.height(12.dp))
    }
}
