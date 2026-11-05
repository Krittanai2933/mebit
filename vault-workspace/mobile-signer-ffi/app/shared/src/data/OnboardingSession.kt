package com.mebit.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mebit.app.EraseResult
import com.mebit.app.StoreVersion
import com.mebit.app.Why
import com.mebit.app.WriteMode
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.SoftwareAccountKey
import com.mebit.ffi.signer.generateSoftwareMultisigKey
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
 */
class OnboardingSession(
    private val wallet: SavedWallet? = null,
    private val newKey: (String, KeyNetwork, UInt) -> SoftwareAccountKey = { p, n, a -> generateSoftwareMultisigKey(p, n, a) },
    private val restoreKey: (List<String>, String, KeyNetwork, UInt) -> SoftwareAccountKey = { w, p, n, a -> restoreSoftwareMultisigKey(w, p, n, a) },
    private val forgetScans: () -> Unit = { hotForgetScanState() },
    private val payments: PendingPayments = PendingPayments(null),
    val pin: SpendingPin = SpendingPin(),
) {
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
    var appLockEnabled: Boolean by mutableStateOf(false)
        private set
    var lastError: String? by mutableStateOf(null)
        private set

    /** The network the key was made for, saved with it. */
    var network: KeyNetwork by mutableStateOf(KeyNetwork.TEST)
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
        persist(fresh = true)
    }

    /**
     * An imported key counts as backed up: the user typed the words from their own record. [accepted] is false for a
     * passphrase import: the key is staged until the fingerprint matches ([acceptStagedKey]). Refused while the session holds a key, unless it is staged.
     */
    fun restoreFromWords(words: List<String>, passphrase: String = "", network: KeyNetwork = KeyNetwork.TEST, accepted: Boolean = passphrase.isEmpty()) {
        lastError = null
        if (refuseToReplace()) return
        try {
            applyKey(restoreKey(words, passphrase, network, 0u), passphrase, network)
            backupConfirmed = true
            imported = true
        } catch (e: Exception) {
            lastError = e.message
            return
        }
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
        record.fingerprint == fingerprint && record.network == network && record.words == words && record.passphrase == passphrase

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
        appLockEnabled = false
        lastError = null
        network = KeyNetwork.TEST
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
     * saved without it, or while a [repostUnconfirmedSend] of it runs in this process.
     */
    fun clearUnconfirmedSend(guard: SingleSigWallet.Unsettled): Boolean {
        if (guard is SingleSigWallet.UnconfirmedSend && guard.txid == recordGuard?.txid && !persist(dropRecordGuard = true)) return false
        return payments.settle(fingerprint, guard)
    }

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
        if (guard != null && !movedToFile(heldFor, guard)) return false
        return when (val r = wallet?.forget() ?: EraseResult.Erased) {
            EraseResult.Erased -> {
                forgetScans()
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
            if (!dropRecordGuard && !movedToFile(fingerprint, it)) {
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

    /**
     * Whether the guard file holds [guard] for the wallet [heldFor] now, written by this call or by an earlier move that the record's
     * save did not follow. A damaged file, one that cannot take it, or another payment of that wallet in the file all leave it in the
     * record alone.
     */
    private fun movedToFile(heldFor: String, guard: SingleSigWallet.UnconfirmedSend): Boolean = when (val claim = payments.claim(heldFor, guard)) {
        Claim.Written -> true
        is Claim.Taken -> claim.earlier.txid == guard.txid
        Claim.Damaged, Claim.NotSaved -> false
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

    private fun toRecord() = WalletRecord(words, passphrase, network, fingerprint, backupConfirmed, imported, appLockEnabled)

    private fun applyKey(key: SoftwareAccountKey, passphrase: String, network: KeyNetwork) {
        words = key.mnemonicWords
        fingerprint = key.masterFingerprint
        xpub = key.xpub
        derivationPath = key.derivationPath
        this.passphrase = passphrase
        this.network = network
        backupConfirmed = false
        imported = false
        held = Held.Nothing
    }

    private companion object {
        /** The refusal under Create or Import while a wallet is saved on this phone. */
        const val TAKEN = "เครื่องนี้มีกระเป๋าบันทึกไว้แล้ว · ถ้าจะใช้กระเป๋าอื่น ให้เริ่มใหม่จากหน้าปลดล็อก"

        /** The refusal while the session holds a key the phone did not save. Only a restart drops it, on purpose. */
        const val UNSAVED = "ยังมีกุญแจที่ยังไม่ได้บันทึกอยู่ในแอป สร้างหรือนำเข้าใหม่ทับไม่ได้ ปิดแอปแล้วเปิดใหม่ถ้าจะเริ่มใหม่"
    }
}
