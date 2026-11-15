package com.mebit.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mebit.ffi.signer.AddressType
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.WalletShape
import com.mebit.ffi.signer.computeVaultAddressMultisig
import com.mebit.ffi.signer.walletDescribe

/** Penpot 3.1's two cards. */
enum class Signing { SingleSig, Multisig }

/**
 * Penpot 3.1's preset chips, the only thresholds the app builds. "กำหนดเอง" is drawn disabled and is no value here, so M above N,
 * M below 1 and an N the core refuses cannot be chosen.
 */
enum class Preset(val m: Int, val n: Int, val sub: String) {
    TwoOfThree(2, 3, "ใช้บ่อยที่สุด"),
    ThreeOfFive(3, 5, "กระจายมากขึ้น"),
    ;

    /** "2-of-3": the chip's title and the start of Review's Policy row. */
    val label: String get() = "$m-of-$n"
}

/**
 * A plan that is ready to create: exactly the preset's n confirmed keys, in keyring order, so the same keys always give the same file.
 * Only [of] makes one, so a plan that exists has passed that check.
 */
class ReadyPlan private constructor(val preset: Preset, val keys: List<SavedKey>) {
    /** Built once, so Review's descriptor box and the BSMS file carry the same line. */
    val descriptor: String = walletDescriptor(preset.m, keys)

    fun bsms(address: String): String = walletBsms(descriptor, keys, address)

    /** The saved wallet with this plan's M, N and keys. wsh(sortedmulti) sorts the keys, so the tap order and the keyring order never matter. */
    fun duplicateOf(wallets: List<CreatedVault>): CreatedVault? {
        val mine = keys.map { it.fingerprint.lowercase() }.sorted()
        return wallets.firstOrNull { it.m == preset.m && it.n == preset.n && it.keyFingerprints.map { f -> f.lowercase() }.sorted() == mine }
    }

    companion object {
        /** Null unless [keys] are exactly n confirmed keys of n phrases, because whoever holds a phrase signs for every key it gives. */
        fun of(preset: Preset, keys: List<SavedKey>): ReadyPlan? {
            val phrases = keys.map { it.fingerprint.lowercase() }.toSet()
            return if (keys.size == preset.n && keys.all { it.verified } && phrases.size == keys.size) ReadyPlan(preset, keys) else null
        }
    }
}

/** A confirmed key that a pick rules out: [blocked] comes from the phrase of [picked], since both have one master fingerprint. */
data class SamePhrase(val picked: SavedKey, val blocked: SavedKey) {
    /** The line under [blocked]'s greyed row on Choose Keys. */
    val line: String get() = "${picked.label} กับ ${blocked.label} ใช้ชุดคำเดียวกัน เลือกได้ดอกเดียว"
}

/**
 * The create-wallet wizard's choices, Penpot 3.1 to 3.4. NavGraph holds one exactly while Policy is on the Back stack
 * ([com.mebit.app.navigation.inCreateFlow]), so nothing resets it and nothing can forget a field.
 *
 * Invariants: [picks] never holds more than [preset].n ids, and only [livePicks] says which of them still count. [create] saves at most one vault per draft.
 * [preset] and [picks] are kept while [signing] is single-sig, so a switch back loses nothing.
 */
class CreateWalletDraft(
    private val book: SavedVaults = SavedVaults.memoryOnly(),
    // Injected so logic tests need no core.
    private val derive: (List<String>, UByte) -> String = { xpubs, m -> computeVaultAddressMultisig(xpubs, m) },
    private val describe: (String, KeyNetwork) -> WalletShape = { d, n -> walletDescribe(d, n) },
) {
    /** Penpot 3.1 opens with multisig selected. */
    var signing: Signing by mutableStateOf(Signing.Multisig)

    var preset: Preset by mutableStateOf(Preset.TwoOfThree)
        private set

    /** Penpot 3.2.1 to 3.2.3's one pick for a one-key wallet: null for a new key on this phone, else the id of a key in [oneKeyChoices]. */
    var deviceKey: String? by mutableStateOf(null)

    /** Penpot 3.3.2's pick, the address type of a one-key hardware wallet, Native SegWit until one is tapped. */
    var addressType: AddressType by mutableStateOf(AddressType.NATIVE_SEGWIT)

    /** The key [deviceKey] picks, while [keys] still offer it ([oneKeyChoices]). Null for this phone, or for a key gone since its tap. */
    fun device(keys: List<SavedKey>): SavedKey? = deviceKey?.let { id -> oneKeyChoices(keys).find { it.id == id } }

    private val picked = mutableStateListOf<String>()

    /** Picked key ids in tap order, a copy. Read in composition, so a toggle redraws the rows and the counter. */
    val picks: List<String> get() = picked.toList()

    /** The vault [create] saved, so a second call writes nothing. */
    private var made: CreatedVault? = null

    fun choose(next: Preset) {
        preset = next
        while (picked.size > next.n) picked.removeAt(picked.lastIndex)
    }

    /** The picks that are a confirmed key in [keys] now, in tap order. A key hidden or removed since its tick is not one. */
    fun livePicks(keys: List<SavedKey>): List<String> = picked.filter { id -> keys.any { it.verified && it.id == id } }

    /** Ticks or unticks [keyId], after dropping the picks that are no longer a confirmed key in [keys]. A tick past n, or of a key a pick [blocked], is ignored. */
    fun toggle(keyId: String, keys: List<SavedKey>) {
        picked.retainAll(livePicks(keys))
        if (keyId in picked) picked.remove(keyId) else if (picked.size < preset.n && keyId !in blocked(keys)) picked.add(keyId)
    }

    /** The confirmed keys in [keys] that a live pick rules out, by key id, each with the pick of its phrase: Choose Keys greys them out with [SamePhrase.line]. */
    fun blocked(keys: List<SavedKey>): Map<String, SamePhrase> {
        val live = livePicks(keys)
        val picks = keys.filter { it.verified && it.id in live }.distinctBy { it.id }
        return keys.filter { it.verified && it.id !in live }.mapNotNull { key ->
            picks.firstOrNull { it.fingerprint.equals(key.fingerprint, ignoreCase = true) }?.let { key.id to SamePhrase(it, key) }
        }.toMap()
    }

    /** The plan the live picks make against [keys], or null while they are short. A saved book may repeat an id, so ids are counted once. */
    fun ready(keys: List<SavedKey>): ReadyPlan? {
        val live = livePicks(keys)
        return ReadyPlan.of(preset, keys.filter { it.verified && it.id in live }.distinctBy { it.id })
    }

    /**
     * Saves this draft's wallet once and returns it. A second call, such as a double tap on "สร้าง Wallet", returns the same vault and
     * writes nothing. Null, with nothing saved, when the core refuses the keys (a duplicate key, for one), or wallet_describe refuses the
     * descriptor, so no wallet the app could not read back is ever made. Named "Wallet <n>" with id "vault_<n>", n one past the highest
     * number this book ever gave ([nextNumber]), so a removed wallet's id and name never come back, and a saved "Vault 1" is followed by "Wallet 2".
     */
    fun create(ready: ReadyPlan): CreatedVault? {
        made?.let { return it }
        val address = try {
            describe(ready.descriptor, KeyNetwork.TEST)
            derive(ready.keys.map { it.xpub }, ready.preset.m.toUByte())
        } catch (e: Exception) {
            return null
        }
        // Built here, not inside the edit, because an edit can run again later over a newer book.
        val vault = CreatedVault(
            id = "",
            name = "",
            m = ready.preset.m,
            n = ready.preset.n,
            policy = CustodyPolicy.PersonalCustody,
            address = address,
            bsms = ready.bsms(address),
            keyFingerprints = ready.keys.map { it.fingerprint },
            hasMockKey = ready.keys.any { it.isMock },
            keyIds = ready.keys.map { it.id },
        )
        book.edit { b ->
            val number = nextNumber(b.lastVaultNumber, b.vaults.map { it.id }, "vault_")
            b.copy(vaults = b.vaults + vault.copy(id = "vault_$number", name = "Wallet $number"), lastVaultNumber = number)
        }
        return book.current.vaults.last().also { made = it }
    }
}

/**
 * Pure. The keys Choose Key offers after this phone for a one-key wallet: each confirmed Jade or Safe 7 key, in keyring order, because
 * the wallet's key is read from that device. A demo key stands for no device, so it is not offered.
 */
internal fun oneKeyChoices(keys: List<SavedKey>): List<SavedKey> =
    keys.filter { it.verified && !it.isMock && (it.source == KeySourceLabel.Jade || it.source == KeySourceLabel.TrezorSafe7) }.distinctBy { it.id }

/** Pure. `wsh(sortedmulti(m,[fp/path]xpub,...))`, a bare xpub for a key whose fingerprint or path is not valid. */
internal fun walletDescriptor(m: Int, keys: List<SavedKey>): String = "wsh(sortedmulti($m,${keys.joinToString(",") { descriptorKey(it) }}))"

/**
 * Pure. The BSMS 1.0 text: the header, [descriptor] on line 2 (the line [SavedVaults] reads at every start), one "fingerprint path"
 * line per key, the path restriction line, and the two "#" lines.
 */
internal fun walletBsms(descriptor: String, keys: List<SavedKey>, address: String): String {
    val pathLines = keys.joinToString("\n") { "${it.fingerprint} ${it.derivationPath}" }
    return listOf(
        "BSMS 1.0",
        descriptor,
        pathLines,
        "/0/*,/1/*",
        "# mebit personal_custody · receive[0]=$address",
        "# export=file · not QR",
    ).joinToString("\n")
}

/** Key origin when the fingerprint and path are known. Otherwise the bare xpub. */
private fun descriptorKey(key: SavedKey): String {
    val fingerprint = key.fingerprint.trim().lowercase()
    val path = key.derivationPath.trim().removePrefix("m/")
    val fingerprintOk = Regex("^[0-9a-f]{8}$").matches(fingerprint)
    val pathOk = Regex("^\\d+'?(/\\d+'?)+$").matches(path)
    return if (fingerprintOk && pathOk) "[$fingerprint/$path]${key.xpub}" else key.xpub
}
