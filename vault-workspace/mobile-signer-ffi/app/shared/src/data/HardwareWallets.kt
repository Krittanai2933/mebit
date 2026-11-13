package com.mebit.app.data

import com.mebit.app.Pairings
import com.mebit.app.sha256
import com.mebit.ffi.signer.AddressType
import com.mebit.ffi.signer.BleException
import com.mebit.ffi.signer.FoundWallet
import com.mebit.ffi.signer.HotScan
import com.mebit.ffi.signer.HwAccountKey
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.WalletKind
import com.mebit.ffi.signer.WalletShape
import com.mebit.ffi.signer.scanWallets
import com.mebit.ffi.signer.walletDescribe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/** A phone wallet as the duplicate rule sees it: one key in one address type has one set of addresses. */
data class PhoneWallet(val name: String, val fingerprint: String, val addressType: AddressType)

/** How [HardwareWallets.add] ended. Only [Added] saved anything. */
sealed interface HardwareAdd {
    data class Added(val wallet: HardwareWallet) : HardwareAdd

    /** The same addresses are on this phone already, in the wallet [name]: shown with the duplicate line, `duplicateLine(name)`. */
    data class Duplicate(val name: String) : HardwareAdd

    /**
     * A place that did not open may hold a phone wallet of the same addresses, so the add is refused with a phone add's line,
     * `addRefusalLine(AddRefusal.Unopened)`.
     */
    data object Unopened : HardwareAdd

    /** The core refused the key the device gave, so no wallet this app reads would come of it. [message] is the core's text. */
    data class Unreadable(val message: String) : HardwareAdd

    /** The book cannot be judged or written now. */
    data object NotSaved : HardwareAdd
}

/** How [HardwareWallets.remove] ended. Only [Removed] changed anything. */
enum class HardwareRemoval {
    Removed,

    /** The owner check did not pass. */
    Refused,

    /** The book cannot be saved and judged now, or the write failed: the card stays, and the screen says so (REMOVE_FAILED_LINE). */
    NotSaved,
}

/**
 * The book's one-key Jade and Safe 7 wallets, one [DescribedWallet] for each, and the [link] every device step of theirs goes through.
 * [payments] is the phone's one guard file and [guardKeys] every guard key on the phone ([Wallets.guardKeys]).
 */
class HardwareWallets internal constructor(
    private val book: SavedVaults,
    private val pairings: Pairings? = null,
    private val payments: PendingPayments = PendingPayments(null),
    private val guardKeys: () -> List<String>? = { emptyList() },
    openLink: (FoundWallet) -> DeviceLink = ::bleLink,
    private val describe: (String, KeyNetwork) -> WalletShape = { d, n -> walletDescribe(d, n) },
    private val core: DescribedCore = DescribedCore(),
    private val nowSeconds: () -> Long = { Clock.System.now().epochSeconds },
    /** Lists the devices of one kind in range, as KeyringStore's scan does. Tests pass a fake, so they need no Bluetooth. */
    private val scan: suspend (WalletKind) -> List<FoundWallet> = { scanWallets(it) },
) {
    /** Reads a device's key for a new wallet ([oneKeyPath]) and reaches the device of a saved one. */
    internal val link = HardwareLink(pairings, openLink)

    /** By descriptor, so a wallet saved again under a removed one's id never takes that one's state. */
    private val chains = mutableMapOf<String, DescribedWallet>()

    /** The Send form of each wallet, by descriptor as [chains], so a form is never another wallet's. */
    private val drafts = mutableMapOf<String, SendDraft>()

    /** In the order they were made. */
    val all: List<HardwareWallet> get() = book.current.hardware

    /** The saved wallet [id], or null for none or for one removed since. */
    fun find(id: String?): HardwareWallet? = all.find { it.id == id }

    /** The chain side of [wallet], one per wallet while this object lives, so Home, Send and the lists share its state. */
    fun chain(wallet: HardwareWallet): DescribedWallet = chains.getOrPut(wallet.descriptor) {
        DescribedWallet(wallet, payments, link, others = { guardKeys()?.minus(wallet.guardKey) }, core, nowSeconds)
    }

    /** The Send form of [wallet], priced at its address type's typical size. */
    fun sendDraft(wallet: HardwareWallet): SendDraft = drafts.getOrPut(wallet.descriptor) { SendDraft(wallet.addressType) }

    /**
     * The first device of [wallet]'s kind in range, for its payment to be signed on. Throws the scan's BleException, NotFound when none is
     * seen. [DescribedWallet.sign] then refuses a device that holds another key before anything shows on it.
     */
    suspend fun nearest(wallet: HardwareWallet): FoundWallet = scan(wallet.kind).firstOrNull() ?: throw BleException.NotFound("no ${wallet.kind} in range")

    /**
     * For a list on screen, as [Wallets.refreshBalances] does for the phone wallets: one wallet at a time, skipping one refreshed in the
     * last minute, and stopping at a scan that came back Continuing, because a host that limits one wallet would limit the next.
     */
    suspend fun refreshBalances() {
        for (wallet in all) {
            val chain = chain(wallet)
            if (chain.boundWithin(BALANCE_FRESH_SECONDS)) continue
            chain.refresh()
            if (chain.scan == HotScan.CONTINUING) return
        }
    }

    /**
     * Saves the wallet of [key], which a [kind] device read at [type]'s [oneKeyPath], once wallet_describe reads its descriptor back,
     * named and numbered by the book. A [HardwareAdd.Duplicate] when a saved hardware wallet has the same descriptor, or a wallet in
     * [phones] the same key and address type, because either has the same addresses. [HardwareAdd.Unopened] while [phones] is null
     * ([Wallets.phoneWallets]), as a phone add is refused then.
     */
    suspend fun add(kind: WalletKind, type: AddressType, key: HwAccountKey, phones: List<PhoneWallet>?): HardwareAdd = withContext(Dispatchers.Default) {
        if (phones == null) return@withContext HardwareAdd.Unopened
        // An account xpub does not say its path, so the core reads a key from another path too, and the device could never sign for it.
        if (key.derivationPath != oneKeyPath(type)) return@withContext HardwareAdd.Unreadable("the key was read at ${key.derivationPath}, not ${oneKeyPath(type)}")
        val unnamed = HardwareWallet("", "", kind, type, key.masterFingerprint.lowercase(), key.xpub, address = "")
        val shape = try {
            describe(unnamed.descriptor, KeyNetwork.TEST)
        } catch (e: Exception) {
            return@withContext HardwareAdd.Unreadable(e.message ?: e.toString())
        }
        val wallet = unnamed.copy(address = shape.firstAddress)
        phones.firstOrNull { it.fingerprint.equals(wallet.fingerprint, ignoreCase = true) && it.addressType == type }?.let { return@withContext HardwareAdd.Duplicate(it.name) }
        var outcome: HardwareAdd = HardwareAdd.NotSaved
        // Decided inside the edit, on the newest book, so two adds never both take one number or both pass the duplicate check.
        val saved = book.editSaved { b ->
            b.hardware.firstOrNull { it.descriptor == wallet.descriptor }?.let {
                outcome = HardwareAdd.Duplicate(it.name)
                return@editSaved b
            }
            val number = nextNumber(b.lastHardwareNumber, b.hardware.map { it.id }, "hw_")
            val made = wallet.copy(id = "hw_$number", name = hardwareWalletName(kind, b.hardware))
            outcome = HardwareAdd.Added(made)
            b.copy(hardware = b.hardware + made, lastHardwareNumber = number)
        }
        if (saved) outcome else HardwareAdd.NotSaved
    }

    /**
     * Removes the hardware wallet [id] once [ownerCheck] passes, the check a phone wallet's removal asks for after its confirm sheet:
     * saved for good ([SavedVaults.editSaved]) or refused with the wallet kept. Then the Safe 7 pairing of its key goes, unless a saved
     * key or another hardware wallet has that fingerprint. Nothing is asked while the book cannot be saved and judged, when the menu
     * item is blocked ([com.mebit.app.feature.send.hardwareDeleteItem]). The coins stay on the network, and the device brings the
     * wallet back. Removing an id the book no longer holds changes nothing and is [HardwareRemoval.Removed].
     */
    suspend fun remove(id: String, ownerCheck: suspend () -> Boolean): HardwareRemoval {
        val gone = all.find { it.id == id } ?: return HardwareRemoval.Removed
        if (book.saving != BookSaving.Ready) return HardwareRemoval.NotSaved
        if (!ownerCheck()) return HardwareRemoval.Refused
        if (!book.editSaved { b -> b.copy(hardware = b.hardware.filterNot { it.id == id }) }) return HardwareRemoval.NotSaved
        chains.remove(gone.descriptor)
        book.forgetUnusedPairing(pairings, gone.fingerprint)
        return HardwareRemoval.Removed
    }

    private companion object {
        const val BALANCE_FRESH_SECONDS = 60L
    }
}

/**
 * "กระเป๋า Jade" or "กระเป๋า Trezor" for a kind's first wallet, then "กระเป๋า Jade 2" and on: one past the highest number a [saved]
 * name of that kind carries, the plain name counting as 1, so a removal never makes a name repeat a saved one.
 */
internal fun hardwareWalletName(kind: WalletKind, saved: List<HardwareWallet>): String {
    val base = "กระเป๋า " + when (kind) {
        WalletKind.JADE -> "Jade"
        WalletKind.TREZOR_SAFE7 -> "Trezor"
    }
    val highest = saved.mapNotNull { if (it.name == base) 1 else it.name.removePrefix("$base ").takeIf { n -> n != it.name }?.toIntOrNull() }.maxOrNull()
    return if (highest == null) base else "$base ${highest + 1}"
}

/**
 * A one-key wallet whose key lives on a Jade or a Safe 7: testnet account 0 of [addressType], watch-only on the phone, its payments
 * signed on the device. The descriptor is derived from the key and never stored, so it can never disagree with the type or the key.
 */
data class HardwareWallet(
    /** "hw_<n>", n one past the highest number this book ever gave ([nextNumber]), the rule vault ids follow. */
    val id: String,
    /** "กระเป๋า Jade", "กระเป๋า Jade 2", "กระเป๋า Trezor" ([hardwareWalletName]). */
    val name: String,
    /** The kind of device the app scans for to sign. */
    val kind: WalletKind,
    val addressType: AddressType,
    /** The key's master fingerprint, 8 lowercase hex. */
    val fingerprint: String,
    /** The account key at [path], a tpub. */
    val xpub: String,
    /** Receive address 0, a check value: the book refuses a stored copy whose descriptor no longer derives it. */
    val address: String,
    /** The send in progress, a base64 PSBT, "" for none. The several-key phase fills it; a one-key send is held in memory only. */
    val pending: String = "",
) {
    /** The path the device derives [xpub] at. */
    val path: String get() = oneKeyPath(addressType)

    /** What every wallet_* call takes. */
    val descriptor: String get() = oneKeyDescriptor(addressType, fingerprint, xpub)

    /**
     * This wallet's key in the pay-twice guard file: the first 8 hex of the descriptor's SHA-256, the shape a phone wallet's
     * fingerprint has there, so two hardware wallets of one key in two address types never share a guard.
     */
    val guardKey: String
        get() = sha256(descriptor.encodeToByteArray()).take(4).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

/**
 * Pure. The name of [wallet]'s key as the Keyring names it ("Jade ของฉัน", or the name Confirm Key gave it), from the confirmed key in
 * [keys] of its device's kind with its fingerprint, else the name a key of that device starts with.
 */
internal fun hardwareKeyName(wallet: HardwareWallet, keys: List<SavedKey>): String {
    val source = hardwareSource(wallet.kind)
    return keys.firstOrNull { it.verified && it.source == source && it.fingerprint.equals(wallet.fingerprint, ignoreCase = true) }?.label
        ?: defaultKeyLabel(source, isMock = false)
}

/** BIP-44, 49, 84 or 86 on testnet, account 0: the path the phone wallet of [type] derives from, as hot_account_path prints it. */
internal fun oneKeyPath(type: AddressType): String = "m/" + when (type) {
    AddressType.LEGACY -> 44
    AddressType.NESTED_SEGWIT -> 49
    AddressType.NATIVE_SEGWIT -> 84
    AddressType.TAPROOT -> 86
} + "'/1'/0'"

/** Pure. `pkh(...)`, `sh(wpkh(...))`, `wpkh(...)` or `tr(...)` over `[fingerprint/path]xpub`, the shapes wallet_describe reads. */
internal fun oneKeyDescriptor(type: AddressType, fingerprint: String, xpub: String): String {
    val key = "[$fingerprint/${oneKeyPath(type).removePrefix("m/")}]$xpub"
    return when (type) {
        AddressType.LEGACY -> "pkh($key)"
        AddressType.NESTED_SEGWIT -> "sh(wpkh($key))"
        AddressType.NATIVE_SEGWIT -> "wpkh($key)"
        AddressType.TAPROOT -> "tr($key)"
    }
}
