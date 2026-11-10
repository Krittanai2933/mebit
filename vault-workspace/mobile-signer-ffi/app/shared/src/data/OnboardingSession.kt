package com.mebit.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mebit.app.EraseResult
import com.mebit.app.Place
import com.mebit.app.StoreVersion
import com.mebit.app.Why
import com.mebit.app.WriteMode
import com.mebit.app.walletName
import com.mebit.ffi.signer.AddressType
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.SoftwareAccountKey
import com.mebit.ffi.signer.generateSoftwareMultisigKey
import com.mebit.ffi.signer.hotAddressTypeEnabled
import com.mebit.ffi.signer.hotForgetScanState
import com.mebit.ffi.signer.restoreSoftwareMultisigKey
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** Whether the wallet saved on this phone holds this session's key. Staged is a passphrase import waiting for its fingerprint check. */
enum class SaveState { NotSaved, Staged, Saved }

/**
 * Onboarding state for Movie Page 00.* screens, plus the hot wallet's payment of unknown outcome, which [payments] keeps in a
 * plain file beside the key, so a phone that cannot save the key keeps it too. Without a [wallet] the key lives in memory only.
 * With one, each committed change is saved through one write point, and only [forgetSavedWallet] erases the saved copy. The
 * guards outlive it. [newKey] and [restoreKey] make the key, and [forgetScans] drops the core's scan of every wallet, and tests
 * replace them because the host JVM has no native library. [pin] is the session Spending PIN, beside the words and never saved.
 * [place] is this wallet's place on the phone, read only for its name, because [wallet] is already bound to that place.
 * [onPhone] says whether another wallet on the phone holds a fingerprint. [startsLocked] is App Lock as the phone has it when this
 * wallet is added, which its first save carries and [clear] returns to. [others] gives the other wallets' fingerprints, for the clear
 * of a damaged guard file, null while a wallet this run could not read may be on the phone.
 */
class OnboardingSession(
    private val wallet: SavedWallet? = null,
    private val newKey: (String, KeyNetwork, UInt) -> SoftwareAccountKey = { p, n, a -> generateSoftwareMultisigKey(p, n, a) },
    private val restoreKey: (List<String>, String, KeyNetwork, UInt) -> SoftwareAccountKey = { w, p, n, a -> restoreSoftwareMultisigKey(w, p, n, a) },
    private val forgetScans: () -> Unit = { hotForgetScanState() },
    private val payments: PendingPayments = PendingPayments(null),
    val pin: SpendingPin = SpendingPin(),
    private val forgetVaults: () -> Unit = {},
    val place: Place = Place.FIRST,
    private val onPhone: (String) -> Boolean = { false },
    private val startsLocked: Boolean = false,
    private val others: () -> List<String>? = { emptyList() },
) {
    val walletName: String get() = place.walletName

    var words: List<String> by mutableStateOf(emptyList())
        private set
    var fingerprint: String by mutableStateOf("")
        private set
    var xpub: String by mutableStateOf("")
        private set
    var derivationPath: String by mutableStateOf("")
        private set

    /** The BIP-39 passphrase this key was derived with, empty for none. The hot wallet needs it too. */
    var passphrase: String by mutableStateOf("")
        private set
    var backupConfirmed: Boolean by mutableStateOf(false)
        private set

    /** The key came from typed words, so there was no backup step and its App Lock screen has no step count. */
    var imported: Boolean by mutableStateOf(false)
        private set
    var appLockEnabled: Boolean by mutableStateOf(startsLocked)
        private set

    /**
     * From a Create or an Import in this run, or a cold start of a wallet whose address type is not confirmed, until its setup ends on
     * the Wallet list. Only then may the new key take a passphrase, or the wallet change its address type, because a wallet the list has shown
     * may hold coins.
     */
    var inFirstSetup: Boolean by mutableStateOf(false)
        private set
    var lastError: String? by mutableStateOf(null)
        private set

    /** How the hot wallet's addresses look. Native SegWit, the type of every wallet saved before the choice existed. */
    var addressType: AddressType by mutableStateOf(AddressType.NATIVE_SEGWIT)
        private set

    /** The network the key was made for, saved with it. */
    var network: KeyNetwork by mutableStateOf(KeyNetwork.TEST)
        private set

    /**
     * False from a new key's first save until the user leaves the Address Type step forward ([confirmAddressType]). Saved with the
     * wallet, so a cold start returns to that screen with [addressType], the user's last tap or the preselected Native SegWit.
     */
    var addressTypeConfirmed: Boolean by mutableStateOf(true)
        private set

    /** What this session knows about the copy on the phone. One state, so Saved can never exist without the copy it refers to. */
    private sealed interface Held {
        /** No copy of this key is known to be on the phone. */
        data object Nothing : Held

        /** A passphrase import waiting for its fingerprint check. */
        data object Staged : Held

        /** The phone held this copy at our last read or write. */
        class Copy(val version: StoreVersion) : Held

        /** Another instance changed the phone since. This session never writes again in this run. */
        data object Stale : Held
    }

    private var held: Held by mutableStateOf(Held.Nothing)

    /** NotSaved is today's memory-only state. Screens read it to say "saved on this phone" only when it is true. */
    val saved: SaveState
        get() = when (held) {
            Held.Nothing, Held.Stale -> SaveState.NotSaved
            Held.Staged -> SaveState.Staged
            is Held.Copy -> SaveState.Saved
        }

    /** Why the wallet is not saved on this phone, for the notice. Null until a save fails, and again after one succeeds. */
    var notSavedBecause: Why? by mutableStateOf(null)
        private set

    /**
     * A Replace failed and no write has succeeded since. The wallet stays Saved, because the earlier copy is on the phone, so
     * [notSavedBecause] stays null and the notice reads this instead.
     */
    var lastSaveFailed: Boolean by mutableStateOf(false)
        private set

    /**
     * The payment of unknown outcome that the record on the phone still holds, because a build before the guard file saved it there
     * (flag 8). It stops this wallet's payments like a guard in the file. Every save drops that section, so the write point saves only
     * once the guard file holds the same payment, or the payment settled, and this stays until such a save succeeds. So a damaged file
     * or another payment's guard in the file never drops it, and a payment the user cleared never comes back from the record.
     */
    private var recordGuard: SingleSigWallet.UnconfirmedSend? = null

    /** A save that failed and has not been repeated yet. [flushPendingSave] and the next write point repeat it. */
    private var pendingSave = false

    /** Saved before it returns, so a crash can never lose a key whose words were shown. Refused while the session holds a key, unless it is staged. */
    fun generateThisDevice(passphrase: String = "", network: KeyNetwork = KeyNetwork.TEST) {
        lastError = null
        if (refuseToReplace()) return
        try {
            applyKey(newKey(passphrase, network, 0u), passphrase, network)
        } catch (e: Exception) {
            lastError = e.message
            return
        }
        inFirstSetup = true
        persist(fresh = true)
    }

    /**
     * Penpot 1.4.2: re-derives the key just created from the same words with [passphrase], during first setup only, and
     * replaces the saved copy through the single write point. An empty passphrase gives the original key back. The words
     * do not change, but the new key has not been checked, so Verify Words runs again, and its address type waits for the
     * user's confirm again. A failed Replace leaves the earlier copy on the phone, which [proveSavedCopy] catches at App Lock.
     */
    fun changeNewKeyPassphrase(passphrase: String) {
        lastError = null
        if (!inFirstSetup || imported || words.isEmpty() || held == Held.Staged || held == Held.Stale) return
        if (passphrase == this.passphrase) return
        val kept = held
        val type = addressType
        val key = try {
            restoreKey(words, passphrase, network, 0u)
        } catch (e: Exception) {
            lastError = e.message
            return
        }
        if (onPhone(key.masterFingerprint)) {
            lastError = ON_PHONE
            return
        }
        applyKey(key, passphrase, network)
        held = kept // the copy on the phone is still there, so this save must be a Replace of it, not a Create
        addressType = type // the same words of the same wallet, so the type the user chose stands
        persist()
    }

    /**
     * Penpot 1.5.1: a tap on an address type row, in first setup only, saved at once as the user's tentative pick, not confirmed, through
     * the single write point: a Replace of the copy on the phone, so a restart keeps it, or with the rest of the record when
     * [acceptStagedKey] writes a staged import. The same type again writes nothing, and so does a type the core does not take on
     * [network] ([hotAddressTypeEnabled], on mainnet only Native SegWit), so no path saves one. [SavedWallet] saves only TEST today, so
     * a MAIN session meets that rule in tests alone. A failed Replace leaves the earlier copy on the phone, which [proveSavedCopy]
     * catches at App Lock.
     */
    fun chooseAddressType(type: AddressType) {
        if (!inFirstSetup || words.isEmpty() || held == Held.Stale || type == addressType) return
        if (!hotAddressTypeEnabled(type, network)) return
        addressType = type
        addressTypeConfirmed = false
        persist()
    }

    /**
     * The user left the Address Type step forward, by its button or the fingerprint sheet after it, so [addressType] is final: saved
     * through the single write point, or with the rest of the record when [acceptStagedKey] writes a staged import next. A confirmed type
     * writes nothing. App Lock's save repairs a confirm whose write failed, and until then a cold start returns to the screen.
     */
    fun confirmAddressType() {
        if (!inFirstSetup || words.isEmpty() || held == Held.Stale || addressTypeConfirmed) return
        addressTypeConfirmed = true
        persist()
    }

    /** The Wallet list has shown the wallet, so its key and its address type are final. */
    fun endFirstSetup() {
        inFirstSetup = false
    }

    /**
     * An imported key counts as backed up: the user typed the words from their own record. [accepted] is false for a
     * passphrase import: the key is staged until the fingerprint matches ([acceptStagedKey]). Refused while the session holds a key, unless it is staged.
     */
    fun restoreFromWords(words: List<String>, passphrase: String = "", network: KeyNetwork = KeyNetwork.TEST, accepted: Boolean = passphrase.isEmpty()) {
        lastError = null
        if (refuseToReplace()) return
        val key = try {
            restoreKey(words, passphrase, network, 0u)
        } catch (e: Exception) {
            lastError = e.message
            return
        }
        // Before applyKey, so a refused duplicate leaves this session exactly as it was. A staged import is checked here too, with its passphrase's fingerprint.
        if (onPhone(key.masterFingerprint)) {
            lastError = ON_PHONE
            return
        }
        applyKey(key, passphrase, network)
        backupConfirmed = true
        imported = true
        inFirstSetup = true
        if (accepted) persist(fresh = true) else held = Held.Staged
    }

    /** The fingerprint sheet said "matches". Nothing was written before this. */
    fun acceptStagedKey() {
        if (held != Held.Staged) return
        held = Held.Nothing
        persist(fresh = true)
    }

    /**
     * The App Lock Enable step on a phone that saved the wallet. Reads the saved copy back (the one OS prompt the user
     * already expects) and compares it with this session's key, so a store that cannot open its own copy shows up now,
     * while the user can still write the words down, not at the next cold start. The words and the passphrase are
     * compared as well as the check value, because a copy whose passphrase was damaged after the write still carries
     * the right check value and would pass, then fail to open at the next cold start. The caller marks the lock on
     * when this returns Verified. A mismatch marks the wallet not saved and keeps the key in memory. A phone that could
     * not save reports why, and the screen keeps today's Biometrics scan.
     */
    internal suspend fun proveSavedCopy(reason: String): UnlockResult {
        val w = wallet ?: return UnlockResult.Unavailable(Why.NotWired)
        if (saved != SaveState.Saved) return UnlockResult.Unavailable(notSavedBecause ?: Why.Other)
        return when (val r = w.open(reason)) {
            // r.version is not adopted: it may name a newer copy another instance wrote, which this session's older flags must never overwrite.
            is UnlockResult.Opened -> if (holdsThisKey(r.record)) UnlockResult.Verified else notSaved(UnlockResult.Broken(Cause.Corrupt))
            is UnlockResult.Broken -> notSaved(r) // a copy that cannot open is not a saved wallet, whatever the reason
            else -> r // cancelled, denied, locked out or try later: nothing is learned, and the user can try again
        }
    }

    private fun holdsThisKey(record: WalletRecord) =
        record.fingerprint == fingerprint && record.network == network && record.words == words && record.passphrase == passphrase &&
            record.addressType == addressType

    /** The copy on the phone is not this wallet or cannot be opened, so this wallet is not saved after all. The key stays in memory. */
    private fun notSaved(result: UnlockResult): UnlockResult {
        held = Held.Nothing
        notSavedBecause = Why.Other
        return result
    }

    /** Forget the key, for an import the user rejected at the fingerprint check. Memory only: a saved wallet is erased by [forgetSavedWallet] and nothing else. */
    fun clear() {
        words = emptyList()
        fingerprint = ""
        xpub = ""
        derivationPath = ""
        passphrase = ""
        backupConfirmed = false
        imported = false
        appLockEnabled = startsLocked
        inFirstSetup = false
        lastError = null
        network = KeyNetwork.TEST
        addressType = AddressType.NATIVE_SEGWIT
        addressTypeConfirmed = true
        recordGuard = null
        held = Held.Nothing
        notSavedBecause = null
        lastSaveFailed = false
        pendingSave = false
        pin.clear()
    }

    fun markBackupConfirmed() {
        backupConfirmed = true
        persist()
    }

    fun markAppLockEnabled() {
        appLockEnabled = true
        persist()
    }

    /**
     * The wallet's payment of unknown outcome ([SingleSigWallet.unconfirmedSend]) as the phone keeps it, so a wallet built after a
     * restart, or another live instance of it, takes it at its next bind. Null when there is none. Reads the guard file.
     */
    fun unconfirmedSend(): SingleSigWallet.Unsettled? = recordGuard ?: payments.of(fingerprint)

    /**
     * The wallet is about to post [txid]. Writes its guard to the guard file, with the signed transaction [hex] when there is one,
     * which is what lets the wallet post. [Claim.Written] only when the file now holds it, and [Claim.Taken] when this wallet already
     * has a guard on the phone, which stays.
     */
    fun recordUnconfirmedSend(txid: String, sinceSeconds: Long, hex: String? = null): Claim =
        recordGuard?.let { Claim.Taken(it) } ?: payments.claim(fingerprint, SingleSigWallet.UnconfirmedSend(txid, sinceSeconds, hex))

    /**
     * The payment [guard] is out, refused, listed by the explorer, or cleared by the user, so its guard leaves the phone: every copy or
     * none, the record's first and then the file's, its signed bytes with it. False, with every copy kept, when the record could not be
     * saved without it, or while a [repostUnconfirmedSend] of it runs in this process. A damaged guard file stays damaged for [others].
     */
    fun clearUnconfirmedSend(guard: SingleSigWallet.Unsettled): Boolean {
        if (guard is SingleSigWallet.UnconfirmedSend && guard.txid == recordGuard?.txid && !persist(dropRecordGuard = true)) return false
        return payments.settle(fingerprint, guard, others())
    }

    /** The wallet is about to post [next], which replaces its guard [earlier]. Swaps this wallet's line in the guard file ([PendingPayments.swap]). */
    fun replaceUnconfirmedSend(earlier: SingleSigWallet.UnconfirmedSend, next: SingleSigWallet.UnconfirmedSend): Swap = payments.swap(fingerprint, earlier, next)

    /** [PendingPayments.repost] of this wallet's [guard]. [post] gets the signed bytes the guard file keeps with it, or nothing runs and this is null. */
    fun <T> repostUnconfirmedSend(guard: SingleSigWallet.UnconfirmedSend, post: (hex: String) -> T): T? = payments.repost(fingerprint, guard, post)

    /**
     * After the user confirmed. Erases the saved wallet and the core's scan files, then clears memory. A failed erase keeps them and
     * returns false. The pay-twice guards stay, a damaged guard file included: they hold no key and are keyed by fingerprint, so the
     * same words imported again take their guard back and refuse until it settles, and another wallet never meets them.
     * The record may hold the only copy of an older guard (flag 8). That is this session's own, or [oldGuard], which the gate read from
     * a record it did not install, with that record's fingerprint. The guard moves to the guard file first, and while it cannot,
     * nothing is erased and this returns false, as after a failed erase.
     */
    fun forgetSavedWallet(oldGuard: Pair<String, SingleSigWallet.UnconfirmedSend>? = null): Boolean {
        val (heldFor, guard) = oldGuard ?: (fingerprint to recordGuard)
        if (guard != null && !payments.moved(heldFor, guard)) return false
        return when (val r = wallet?.forget() ?: EraseResult.Erased) {
            EraseResult.Erased -> {
                forgetScans()
                forgetVaults()
                clear()
                true
            }
            is EraseResult.Failed -> {
                // The copy is still on the phone, but the erase advanced the store's counter, so the session's Replace must expect the new version or every later save is a Conflict.
                if (held is Held.Copy) held = Held.Copy(r.version)
                false
            }
        }
    }

    /**
     * Puts a released record into the session, all or nothing: the key must derive and its fingerprint must equal the
     * record's. Derives once. Catches Exception only: a missing native library is a packaging bug and must crash, not
     * offer to delete a good wallet. [oldGuard], the guard a record from a build before the guard file held, moves to that
     * file, and the save that follows writes the record without it, so it moves once ([recordGuard]).
     */
    internal fun install(record: WalletRecord, version: StoreVersion, oldGuard: SingleSigWallet.UnconfirmedSend? = null): Boolean {
        val key = try {
            restoreKey(record.words, record.passphrase, record.network, 0u)
        } catch (e: Exception) {
            return false
        }
        if (key.masterFingerprint != record.fingerprint) return false
        applyKey(key, record.passphrase, record.network)
        backupConfirmed = record.backupConfirmed // from the record, not from how the key was made, which forces these
        imported = record.imported
        appLockEnabled = record.appLockEnabled
        addressType = record.addressType
        addressTypeConfirmed = record.addressTypeConfirmed
        // A type not yet confirmed means the Wallet list never showed the wallet, so setup goes on where it stopped.
        inFirstSetup = !record.addressTypeConfirmed
        held = Held.Copy(version)
        recordGuard = oldGuard
        if (oldGuard != null) persist()
        return true
    }

    // The record is built inside the lock, so the call that writes last always writes the newest state.
    private val persistLock = SynchronizedObject()

    /**
     * The single write point. A hardware-only session has no words and writes nothing. [fresh]: this call saves a key the user
     * has not relied on yet. [dropRecordGuard]: the payment of [recordGuard] settled, so the save may drop it without moving it first.
     * True when the copy on the phone now holds this record.
     */
    private fun persist(fresh: Boolean = false, dropRecordGuard: Boolean = false): Boolean = synchronized(persistLock) {
        val w = wallet ?: return@synchronized false
        if (words.isEmpty()) return@synchronized false
        recordGuard?.let {
            if (!dropRecordGuard && !payments.moved(fingerprint, it)) {
                pendingSave = true // every write point and the flush try the move again, and no save drops the guard from the record before it
                return@synchronized false
            }
        }
        val mode = when (val h = held) {
            Held.Staged, Held.Stale -> return@synchronized false
            Held.Nothing -> WriteMode.Create
            is Held.Copy -> WriteMode.Replace(h.version)
        }
        when (val r = w.save(toRecord(), mode)) {
            is SaveResult.Written -> {
                held = Held.Copy(r.version)
                recordGuard = null // the record on the phone no longer holds it
                notSavedBecause = null
                lastSaveFailed = false
                pendingSave = false
                return@synchronized true
            }
            SaveResult.Conflict -> {
                // The phone holds the result of the user's latest act in another instance (a newer copy, or a Forget). A Create here
                // would bring a forgotten wallet back, so this session keeps its key in memory and writes nothing more this run.
                held = Held.Stale
                notSavedBecause = Why.Other
                pendingSave = false
            }
            SaveResult.AlreadyExists -> {
                pendingSave = false // another copy is there, so trying again would meet it again
                if (fresh) {
                    clear() // lost a race on a key nobody has seen yet: drop ours, never overwrite theirs
                    lastError = TAKEN
                } else {
                    held = Held.Nothing // never drop a key the user may have written down
                    notSavedBecause = Why.Other
                }
            }
            is SaveResult.NotSaved -> {
                // A failed Replace leaves the earlier copy on the phone, so the wallet stays Saved under the same version and only a
                // failed Create is not saved. Downgrading it would make every later write a Create, which meets that copy and is lost for good.
                if (mode == WriteMode.Create) {
                    held = Held.Nothing
                    notSavedBecause = r.why
                } else {
                    lastSaveFailed = true
                }
                // A store that can never keep it here has nothing to retry, and a foreground flush would only encode the record again.
                pendingSave = r.why != Why.NotWired && r.why != Why.OsTooOld
            }
        }
        false
    }

    /** Tries a save that failed again, for example when the app comes back to the foreground. Does nothing unless one is pending. */
    fun flushPendingSave() {
        if (pendingSave) persist()
    }

    /** Create and Import never drop a key the user may have written down. Only a Staged import, which nothing has shown as final yet, gives way. */
    private fun refuseToReplace(): Boolean {
        when {
            held is Held.Copy || held == Held.Stale -> lastError = TAKEN // a Stale session still holds a key the user may have written down
            words.isNotEmpty() && held != Held.Staged -> lastError = UNSAVED // a Create the phone refused: this process holds the only copy
            else -> return false
        }
        return true
    }

    private fun toRecord() = WalletRecord(words, passphrase, network, fingerprint, backupConfirmed, imported, appLockEnabled, addressType, addressTypeConfirmed)

    private fun applyKey(key: SoftwareAccountKey, passphrase: String, network: KeyNetwork) {
        words = key.mnemonicWords
        fingerprint = key.masterFingerprint
        xpub = key.xpub
        derivationPath = key.derivationPath
        this.passphrase = passphrase
        this.network = network
        backupConfirmed = false
        imported = false
        addressType = AddressType.NATIVE_SEGWIT
        addressTypeConfirmed = false
        held = Held.Nothing
    }

    private companion object {
        /** The refusal under Create or Import while a wallet is saved on this phone. */
        const val TAKEN = "เครื่องนี้มีกระเป๋าบันทึกไว้แล้ว · ถ้าจะใช้กระเป๋าอื่น ให้เริ่มใหม่จากหน้าปลดล็อก"

        /** The refusal while the session holds a key the phone did not save. Only a restart drops it, on purpose. */
        const val UNSAVED = "ยังมีกุญแจที่ยังไม่ได้บันทึกอยู่ในแอป สร้างหรือนำเข้าใหม่ทับไม่ได้ ปิดแอปแล้วเปิดใหม่ถ้าจะเริ่มใหม่"

        /** The refusal when another wallet on this phone already holds this fingerprint. */
        const val ON_PHONE = "กระเป๋านี้อยู่ในเครื่องนี้แล้ว"
    }
}
