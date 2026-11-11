package com.mebit.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import com.mebit.app.EveryRead
import com.mebit.app.Marker
import com.mebit.app.Place
import com.mebit.app.PlainFile
import com.mebit.app.SecureShelf
import com.mebit.app.walletName
import com.mebit.ffi.signer.AddressType
import com.mebit.ffi.signer.HotScan
import com.mebit.ffi.signer.HotTx
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.SoftwareAccountKey
import com.mebit.ffi.signer.VaultSnapshot
import com.mebit.ffi.signer.generateSoftwareMultisigKey
import com.mebit.ffi.signer.hotForgetScanState
import com.mebit.ffi.signer.restoreSoftwareMultisigKey
import com.mebit.ffi.signer.vaultHistory
import com.mebit.ffi.signer.vaultSnapshot
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/** One wallet in memory. Nothing here is shared with another wallet, so one wallet's pay-twice guard, held payment or draft cannot reach another. */
class OpenWallet internal constructor(val place: Place, val session: OnboardingSession, val chain: SingleSigWallet) {
    private var draft: SendDraft? = null
    private var draftType: AddressType? = null

    /** The draft for the wallet's current address type. Made on first use, because a draft asks the core for a size. A new type means a new draft, which loses nothing: the type changes only before any payment. */
    val sendDraft: SendDraft
        get() {
            val type = session.addressType
            draft?.takeIf { draftType == type }?.let { return it }
            return SendDraft(type).also {
                draft = it
                draftType = type
            }
        }
}

/** Why a wallet cannot be added now. */
enum class AddRefusal {
    /** Every place is taken. */
    Full,

    /** A wallet here is only in memory, because this phone has no passcode or a save failed. A second one would make it worse. */
    NotSavedHere,

    /** A listed wallet has not finished setup. So at most one can be unfinished, and a cold start knows which to resume. */
    SetupUnfinished,

    /** A place did not open this run but may at the next launch, and its words are unknown, so they could be added a second time. */
    Unopened,
}

/** How a [Wallets.remove] ended. Only [Removed] erased anything. */
sealed interface WalletRemoval {
    data object Removed : WalletRemoval

    /** A vault uses this key, or may, because the book could not be judged. */
    data object InVault : WalletRemoval

    /** The owner check did not pass. */
    class Refused(val result: UnlockResult) : WalletRemoval

    /**
     * The wallet's words are only in memory, or no other saved wallet past setup could take over the book, or the book could not be handed
     * over, or the erase failed, or an older guard could not move. Nothing left memory.
     */
    data object NotErased : WalletRemoval
}

/** What an unlock did. */
internal sealed interface Every {
    /** The owner check refused, or nothing could be read this moment. Nothing is installed. */
    class Refused(val result: UnlockResult) : Every

    /** At least one wallet is in memory. [resume] is the one to open. */
    class Open(val resume: OpenWallet) : Every

    /** No wallet opened and none ever will. Only Forget helps. */
    class Broken(val cause: Cause) : Every
}

/**
 * Every wallet this phone holds, in memory. The store is the truth about what exists. This list is derived from it at the unlock and
 * kept in step by [beginAdd] and [remove], its only writers. Sessions write their own records and never see this list, except through
 * the read-only `onPhone` and `others` checks, so two wallets never coordinate a write.
 *
 * Holds at least one entry at all times: an empty wallet at place 1 until the unlock or the first create, because the nav host needs a session for Welcome.
 * At most one entry has no key, because [beginAdd] reuses it.
 */
class Wallets internal constructor(
    private val shelf: SecureShelf,
    private val payments: PendingPayments = PendingPayments(null),
    private val lastUsed: PlainFile? = null,
    private val forgetBook: () -> Unit = {},
    private val forgetScans: () -> Unit = { hotForgetScanState() },
    private val newKey: (String, KeyNetwork, UInt) -> SoftwareAccountKey = { p, n, a -> generateSoftwareMultisigKey(p, n, a) },
    private val restoreKey: (List<String>, String, KeyNetwork, UInt) -> SoftwareAccountKey = { w, p, n, a -> restoreSoftwareMultisigKey(w, p, n, a) },
    private val newChain: () -> SingleSigWallet = { SingleSigWallet() },
    private val vaultSnapshotOf: (xpubs: List<String>, m: UByte) -> VaultSnapshot = { x, m -> vaultSnapshot(x, m) },
    private val vaultHistoryOf: (xpubs: List<String>, m: UByte) -> List<HotTx> = { x, m -> vaultHistory(x, m) },
    private val nowSeconds: () -> Long = { Clock.System.now().epochSeconds },
    /** The several-key wallets' pay-twice guards, keyed by [vaultGuardKey], in a file apart from [payments], whose format a vault line would break. */
    internal val vaultPayments: PendingPayments = PendingPayments(null),
) {
    private val saved = mutableMapOf<Place, SavedWallet>()

    private fun savedAt(place: Place) = saved.getOrPut(place) { SavedWallet(shelf.store(place)) }

    private fun empty(place: Place, locked: Boolean) = OpenWallet(
        place,
        OnboardingSession(
            savedAt(place), newKey, restoreKey,
            forgetScans = {}, // one wallet leaving keeps the others' scans, and forgetEvery clears them all
            payments = payments,
            forgetVaults = {}, // the book is the phone's, and only forgetEvery erases it
            place = place,
            onPhone = { fingerprint -> all.any { it.place != place && it.session.fingerprint.equals(fingerprint, ignoreCase = true) } },
            startsLocked = locked,
            others = { if (unreadMayOpen) null else listed.filter { it.place != place }.map { it.session.fingerprint } },
        ),
        newChain(),
    )

    var all: List<OpenWallet> by mutableStateOf(listOf(empty(Place.FIRST, locked = false)))
        private set

    /** Places that hold something this run could not open. Occupied, so never reused and never written. */
    var unopened: Map<Place, UnlockResult> by mutableStateOf(emptyMap())
        private set

    /** A place that did not open may open at the next launch, so a wallet whose fingerprint this run cannot know may be on the phone. A Broken place never opens in this build. */
    private val unreadMayOpen: Boolean
        get() = unopened.values.any { it !is UnlockResult.Broken }

    /** The older guards (flag 8) of records read during an owner check or a failed install, which a Forget moves to the guard file first. */
    private var oldGuards = emptyList<Pair<String, SingleSigWallet.UnconfirmedSend>>()

    /** The wallets a list may show: a real key that is not a staged import. The one rule behind 3.5, 4.3.0, 4.4.0 and the keyring. */
    val listed: List<OpenWallet>
        get() = all.filter { it.session.words.size >= 12 && it.session.saved != SaveState.Staged }

    /** The wallet at [place]. Every Stop names a place in this list, because only [remove] drops one and it rewrites the Back stack in the same snapshot. */
    fun at(place: Place): OpenWallet = all.first { it.place == place }

    /**
     * The cold start of this process. Reads the marker and the probe, runs the pure [reinstallPolicy], and does what it says
     * (erase an orphan, write the marker). Never wipes on doubt. Returns the probe to decide the boot with and a one-time notice.
     */
    fun start(): WalletStart {
        val store = shelf.store(Place.FIRST)
        val marker = store.marker()
        var probe = store.probe()
        var notice: StartNotice? = null
        when (reinstallPolicy(marker, probe)) {
            ReinstallAction.None -> Unit
            ReinstallAction.MarkEmpty -> store.writeMarker(Marker.Empty)
            ReinstallAction.MarkKept -> store.writeMarker(Marker.Kept)
            ReinstallAction.WipeOrphan -> if (shelf.eraseEvery()) {
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

    /** One owner check, every record read, every record installed. */
    internal suspend fun unlockEvery(reason: String): Every {
        val found = when (val read = shelf.readEvery(reason)) {
            is EveryRead.Refused -> return Every.Refused(savedAt(Place.FIRST).decoded(read.result))
            is EveryRead.Found -> decodedEvery(read)
        }
        // A wallet its owner has opened is theirs whatever the marker said. One write, not one per wallet.
        if (found.values.any { it is UnlockResult.Opened }) shelf.store(Place.FIRST).writeMarker(Marker.Kept)
        return withContext(Dispatchers.Default) { installEvery(found) } // derives once per wallet
    }

    private fun decodedEvery(read: EveryRead.Found) = read.places.mapValues { (place, r) -> savedAt(place).decoded(r) }

    /** Builds the new list and swaps it in one write, so an unlock that opens nothing leaves everything as it was. */
    private fun installEvery(found: Map<Place, UnlockResult>): Every {
        val installed = mutableListOf<OpenWallet>()
        val left = LinkedHashMap<Place, UnlockResult>()
        val guards = mutableListOf<Pair<String, SingleSigWallet.UnconfirmedSend>>()
        for ((place, r) in found) {
            if (r !is UnlockResult.Opened) {
                left[place] = r
                continue
            }
            val w = all.firstOrNull { it.place == place } ?: empty(place, locked = false) // the empty first wallet is reused, so a reference taken before the unlock stays good
            if (w.session.install(r.record, r.version, r.oldGuard)) {
                installed += w
            } else {
                left[place] = UnlockResult.Broken(Cause.Corrupt)
                r.oldGuard?.let { guards += r.record.fingerprint to it }
            }
        }
        oldGuards = guards
        val sorted = installed.sortedBy { it.place.number }
        val outcome = afterEveryRead(sorted, left)
        if (outcome is Every.Open) {
            Snapshot.withMutableSnapshot {
                all = sorted
                unopened = left
            }
        }
        return outcome
    }

    /** Pure. An unfinished wallet opens at Address Type before anything else, then the last used, then the lowest place. */
    internal fun resumeAt(installed: List<OpenWallet>, last: Place?): OpenWallet =
        installed.firstOrNull { !it.session.addressTypeConfirmed } ?: installed.firstOrNull { it.place == last } ?: installed.first()

    private fun lastUsedPlace(): Place? = lastUsed?.read()?.trim()?.toIntOrNull()?.let(Place::parse)

    /** A place that could not be read this moment never turns into a recovery screen, so anything but Broken stays Locked. Nothing found at all is the one Missing case. */
    internal fun afterEveryRead(installed: List<OpenWallet>, left: Map<Place, UnlockResult>): Every = when {
        installed.isNotEmpty() -> Every.Open(resumeAt(installed, lastUsedPlace()))
        left.isEmpty() -> Every.Broken(Cause.Missing)
        left.values.any { it !is UnlockResult.Broken } -> Every.Refused(left.values.first { it !is UnlockResult.Broken })
        else -> Every.Broken((left.values.first() as UnlockResult.Broken).cause)
    }

    /** The Locked Forget's owner check. Reads every record and keeps only their older guards. Null when the owner is proven, else why not, and never [Every.Open]. */
    internal suspend fun proveOwner(reason: String): Every? = when (val read = shelf.readEvery(reason)) {
        is EveryRead.Refused -> Every.Refused(savedAt(Place.FIRST).decoded(read.result))
        is EveryRead.Found -> {
            val found = decodedEvery(read)
            oldGuards = found.values.filterIsInstance<UnlockResult.Opened>().mapNotNull { o -> o.oldGuard?.let { o.record.fingerprint to it } }
            when {
                found.values.any { it is UnlockResult.Opened } -> null
                found.isEmpty() -> Every.Broken(Cause.Missing)
                found.values.all { it is UnlockResult.Broken } -> Every.Broken((found.values.first() as UnlockResult.Broken).cause)
                else -> Every.Refused(found.values.first { it !is UnlockResult.Broken })
            }
        }
    }

    /**
     * Forget on the unlock screen: every wallet, the book and the scans. The older guards move first or nothing is erased.
     * The guards themselves stay, so the same words imported again still refuse until that payment settles.
     */
    fun forgetEvery(): Boolean {
        if (oldGuards.any { (fingerprint, guard) -> !payments.moved(fingerprint, guard) }) return false
        if (!shelf.eraseEvery()) return false
        shelf.store(Place.FIRST).writeMarker(Marker.Empty)
        forgetScans()
        forgetBook()
        lastUsed?.delete()
        all.forEach { it.session.clear() }
        // Outside the snapshot below, so a vault read that lands on another thread meanwhile cannot make its apply fail.
        synchronized(vaultWriteLock) {
            vaultGeneration++
            vaultReads.clear()
        }
        Snapshot.withMutableSnapshot {
            all = listOf(empty(Place.FIRST, locked = false))
            unopened = emptyMap()
        }
        oldGuards = emptyList()
        return true
    }

    /** Null when a wallet can be added. */
    fun addRefusal(): AddRefusal? = when {
        listed.any { it.session.saved != SaveState.Saved } -> AddRefusal.NotSavedHere
        listed.any { !it.session.addressTypeConfirmed } -> AddRefusal.SetupUnfinished
        unreadMayOpen -> AddRefusal.Unopened
        all.none(::isEmptyEntry) && firstFreePlace() == null -> AddRefusal.Full
        else -> null
    }

    private fun isEmptyEntry(w: OpenWallet) = w.session.words.isEmpty() || w.session.saved == SaveState.Staged

    /** The lowest place no wallet holds and no unopened record holds. A wallet's number is its place, so a removed number comes back. */
    private fun firstFreePlace(): Place? = Place.all.firstOrNull { p -> all.none { it.place == p } && p !in unopened }

    /**
     * The wallet a Create or an Import fills, or null with [addRefusal] saying why. Never writes: the new wallet writes itself through
     * its own session at the moment the key exists. Reuses an empty entry, or one holding a staged import, so an abandoned add leaves
     * nothing to clean up.
     */
    fun beginAdd(): OpenWallet? {
        if (addRefusal() != null) return null
        all.firstOrNull(::isEmptyEntry)?.let { return it }
        val place = firstFreePlace() ?: return null
        val w = empty(place, locked = all.any { it.session.appLockEnabled })
        all = (all + w).sortedBy { it.place.number }
        return w
    }

    /**
     * From 4.1.1. Only a saved wallet whose own copy opens under the owner check, the same OS check as a Forget, with its fingerprint, because
     * any other holds its words only in memory. Refused while the key is in a vault or the book cannot be judged. The book is handed over
     * before the erase, to a saved wallet past setup, whose key can no longer change, so a crash at any point leaves it owned by a wallet that exists.
     * [onGone] rewrites the Back stack in the snapshot that drops the wallet, so no frame can show a Stop whose wallet is gone.
     */
    suspend fun remove(w: OpenWallet, book: SavedVaults, reason: String, onGone: (next: OpenWallet) -> Unit): WalletRemoval {
        if (w.session.saved != SaveState.Saved) return WalletRemoval.NotErased
        val fingerprint = w.session.fingerprint
        withContext(Dispatchers.Default) { book.sync() }
        if (book.usesKey(fingerprint) != false) return WalletRemoval.InVault
        val next = listed.firstOrNull { it.place != w.place && it.session.saved == SaveState.Saved && !it.session.inFirstSetup && it.session.addressTypeConfirmed }
            ?: return WalletRemoval.NotErased
        val oldGuard = when (val r = savedAt(w.place).open(reason)) {
            is UnlockResult.Opened -> {
                if (r.record.fingerprint != fingerprint) return WalletRemoval.NotErased // this place no longer holds the wallet the user named
                r.oldGuard?.let { r.record.fingerprint to it }
            }
            is UnlockResult.Broken -> return WalletRemoval.NotErased // the copy can never open, so the words live only in memory
            else -> return WalletRemoval.Refused(r)
        }
        if (!book.handOver(fingerprint, next.session.vaultsOwner() ?: return WalletRemoval.NotErased)) return WalletRemoval.NotErased
        if (!w.session.forgetSavedWallet(oldGuard)) return WalletRemoval.NotErased
        Snapshot.withMutableSnapshot {
            all = all - w
            onGone(next)
        }
        return WalletRemoval.Removed
    }

    /** The book's owners. Null while no wallet is saved, which is the no-owner path. */
    fun vaultOwners(): VaultOwners? {
        val owners = all.mapNotNull { it.session.vaultsOwner() }.takeIf { it.isNotEmpty() } ?: return null
        val unknown = unopened.isNotEmpty() || all.any { it.session.words.isNotEmpty() && it.session.saved != SaveState.Saved }
        return VaultOwners(owners, everyPlaceOpened = !unknown, earlier = all.flatMap { it.session.earlierVaultsOwners() })
    }

    /** Every wallet's own key, in place order. */
    fun ownKeys(): List<SavedKey> = listed.mapNotNull { it.session.ownKey() }

    /** Called when the app returns. Each session repeats its own failed save. */
    fun flushPendingSaves() = all.forEach { it.session.flushPendingSave() }

    /**
     * One vault's last read this run: the core's balance answer, or null for a read that failed, when it ended, and the transactions asked
     * with that balance. [history] is null when none were asked (a list read, or a balance that did not reach the end of both chains), a
     * failure when they were asked and did not come, else the list. Only [readVault] writes one, so it is null beside any balance that is not
     * COMPLETE.
     */
    private class VaultRead(val snapshot: VaultSnapshot?, val atSeconds: Long, val history: Result<List<HotTx>>? = null) {
        /**
         * Whether this read, while fresh, spares a pass another read. Any read spares a list. A detail is spared by one that asked the history,
         * failed or not, so a failure costs a minute as a failed balance does, and by one whose balance did not complete, which no history can follow.
         */
        fun answers(withHistory: Boolean): Boolean = !withHistory || history != null || snapshot?.scan != HotScan.COMPLETE
    }

    /**
     * Each vault's last read, keyed by its receive address, which only its keys and m make, so a book edit that renumbers ids never moves
     * a figure to another vault. In memory only, because plain files hold no amount. State, so a card redraws when its read lands.
     */
    private val vaultReads = mutableStateMapOf<String, VaultRead>()

    /** One vault read at a time across every pass, with the freshness check under the same lock, so two screens never read one vault twice. */
    private val vaultLock = SynchronizedObject()

    /** Counts erases, so a read that ends after [forgetEvery] writes nothing back. */
    private var vaultGeneration by mutableStateOf(0)

    /** Makes a read's generation check and write one step against an erase's bump and clear. Never held across a read, so an erase never waits on the network. */
    private val vaultWriteLock = SynchronizedObject()

    /**
     * [vault]'s balance in sats once a read reached the end of both its chains. Null before its first read this run, after a failed read, and
     * while its scan stopped short or a host lied. Null is a dash on every screen and makes any total a dash, so part of a vault never reads as
     * all of it. The one rule for a vault's figure, as [figuresKnown] is for a one-key wallet's. A vault has no demo, because stand-in keys still
     * make a real address.
     */
    fun vaultSats(vault: CreatedVault): Long? =
        vaultReads[vault.address]?.snapshot?.takeIf { it.scan == HotScan.COMPLETE }?.balanceSats?.toLong()

    /**
     * [vault]'s transactions from the read behind [vaultSats], as the core lists them: the mempool first, then newest first. Null until a detail
     * read asked them with the balance on screen and they came, so always null while [vaultSats] is, and a list read that replaces the balance
     * drops them, so a balance and the rows beside it always come from one read.
     */
    fun vaultTxs(vault: CreatedVault): List<HotTx>? = vaultReads[vault.address]?.history?.getOrNull()

    /**
     * For a several-key wallet's detail (Penpot 3.7): its balance and, once that read reached the end of both chains, its transactions, by
     * [readVault]'s rules. Never part of [refreshBalances], so a list on screen asks no page of any vault's history.
     */
    suspend fun readVaultDetail(vault: CreatedVault) {
        withContext(Dispatchers.Default) { readVault(vault, withHistory = true) }
    }

    /**
     * For a list on screen. One wallet at a time, never in parallel, the one-key wallets first, then [vaults] in book order. Skips a wallet
     * read in the last minute, a failed read included, so N wallets cost N routine syncs at most per visit. A public host that limits one
     * wallet would limit the next, so a scan that came back Continuing ends the pass, whichever kind of wallet it was, and Home's own retry
     * or the next visit goes on later. The wallet at [first] goes first, so the one in use is bound even when another scan stops the pass.
     * [vaults] is required, so no screen that shows a vault can forget to read it.
     */
    suspend fun refreshBalances(vaults: List<CreatedVault>, first: Place? = null) {
        for (w in listed.sortedBy { it.place != first }) {
            if (!w.session.addressTypeConfirmed || w.chain.boundWithin(BALANCE_FRESH_SECONDS)) continue // an unfinished wallet has no final address type to scan
            withContext(Dispatchers.Default) { w.chain.bindSession(w.session) }
            if (w.chain.scan == HotScan.CONTINUING) return
        }
        for (v in vaults) {
            if (withContext(Dispatchers.Default) { readVault(v) } == HotScan.CONTINUING) return
        }
    }

    /**
     * Reads [vault] unless a read of it ended in the last minute that [VaultRead.answers] this pass. The balance first, then, for a detail
     * ([withHistory]) whose balance reached the end of both chains, the transactions, inside the core's ten-second share window so they add
     * only their pages and the tip. Both land in one write after the erase check, so a balance and its rows come from one read and an erase
     * leaves neither. Blocks on the network. How far its scan got, or null when nothing was asked or the read failed.
     */
    private fun readVault(vault: CreatedVault, withHistory: Boolean = false): HotScan? = synchronized(vaultLock) {
        val last = vaultReads[vault.address]
        if (last != null && nowSeconds() - last.atSeconds in 0..BALANCE_FRESH_SECONDS && last.answers(withHistory)) return@synchronized null
        val generation = vaultGeneration
        val keys = xpubsIn(vault.bsms)
        val m = vault.m.toUByte()
        // A mainnet vault, a malformed BSMS and a lost host all leave the figure unknown, never guessed.
        val snapshot = try {
            vaultSnapshotOf(keys, m)
        } catch (_: Exception) {
            null
        }
        val history = if (withHistory && snapshot?.scan == HotScan.COMPLETE) {
            try {
                Result.success(vaultHistoryOf(keys, m))
            } catch (e: Exception) {
                Result.failure(e)
            }
        } else {
            null
        }
        val endedAt = nowSeconds()
        synchronized(vaultWriteLock) { if (generation == vaultGeneration) vaultReads[vault.address] = VaultRead(snapshot, endedAt, history) }
        // Only a history that lists the payment settles its guard, as a one-key bind settles its own. The empty list of a failed read says nothing.
        history?.getOrNull()?.let { listed ->
            val key = vaultGuardKey(vault)
            (vaultPayments.of(key) as? SingleSigWallet.UnconfirmedSend)?.takeIf { it.settledBy(listed) }?.let { vaultPayments.settle(key, it) }
        }
        snapshot?.scan
    }

    /**
     * Drops [vault]'s read once a payment from it went out, so the detail it lands on reads it again, and bumps the generation an erase bumps,
     * so a read that began before the payment writes nothing back. The core marked every scan stale at the post, so that read is a routine sync.
     */
    fun forgetVaultRead(vault: CreatedVault) {
        synchronized(vaultWriteLock) {
            vaultGeneration++
            vaultReads.remove(vault.address)
        }
    }

    private var lastNoted: Place? = null

    /** Remembers the wallet in use for the next cold start, as a plain number, so the rule that plain files hold no key, address or amount stands. Writes only on a change. */
    fun noteUsed(place: Place) {
        if (place != lastNoted) {
            lastNoted = place
            lastUsed?.write(place.number.toString())
        }
    }

    private companion object {
        const val BALANCE_FRESH_SECONDS = 60L
    }
}

/** The one line 3.5 shows for places that did not open, or null. */
internal fun unopenedNotice(unopened: Map<Place, UnlockResult>): String? =
    if (unopened.isEmpty()) null else "เปิด " + unopened.keys.joinToString(", ") { it.walletName } + " ไม่ได้ในตอนนี้ กระเป๋าอื่นใช้ได้ตามปกติ"

internal fun addRefusalLine(r: AddRefusal): String = when (r) {
    AddRefusal.Full -> "เครื่องนี้เก็บกระเป๋าได้สูงสุด ${Place.COUNT} ใบ"
    AddRefusal.NotSavedHere -> "เครื่องนี้ยังบันทึกกระเป๋าไม่ได้ ตั้งรหัสล็อกหน้าจอก่อน จึงจะเพิ่มกระเป๋าได้"
    AddRefusal.SetupUnfinished -> "ตั้งค่ากระเป๋าที่ยังไม่เสร็จก่อน จึงจะเพิ่มกระเป๋าใหม่ได้"
    AddRefusal.Unopened -> "เปิดกระเป๋าบางใบไม่ได้ในตอนนี้ ปิดแล้วเปิดแอปใหม่ก่อนเพิ่มกระเป๋า"
}
