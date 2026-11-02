package com.mebit.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mebit.app.Why
import com.mebit.app.WriteMode
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.SoftwareAccountKey
import com.mebit.ffi.signer.generateSoftwareMultisigKey
import com.mebit.ffi.signer.restoreSoftwareMultisigKey

/** Whether the wallet saved on this phone holds this session's key. Staged is a passphrase import waiting for its fingerprint check. */
enum class SaveState { NotSaved, Staged, Saved }

/**
 * Onboarding state for Movie Page 00.* screens. Without a [wallet] it lives in memory only. With one, each committed
 * change is saved through one write point, and only [forgetSavedWallet] erases the saved copy. [newKey] and [restoreKey]
 * make the key, and tests replace them because the host JVM has no native library.
 */
class OnboardingSession(
    private val wallet: SavedWallet? = null,
    private val newKey: (String, KeyNetwork, UInt) -> SoftwareAccountKey = { p, n, a -> generateSoftwareMultisigKey(p, n, a) },
    private val restoreKey: (List<String>, String, KeyNetwork, UInt) -> SoftwareAccountKey = { w, p, n, a -> restoreSoftwareMultisigKey(w, p, n, a) },
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

    /** NotSaved is today's memory-only state. Screens read it to say "saved on this phone" only when it is true. */
    var saved: SaveState by mutableStateOf(SaveState.NotSaved)
        private set

    /** Why the wallet is not saved on this phone, for the notice. Null until a save fails, and again after one succeeds. */
    var notSavedBecause: Why? by mutableStateOf(null)
        private set

    /** A save that failed and has not been repeated yet. [flushPendingSave] and the next write point repeat it. */
    private var pendingSave = false

    /** Saved before it returns, so a crash can never lose a key whose words were shown. Refused while a wallet is saved. */
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
     * passphrase import: the key is staged until the fingerprint matches ([acceptStagedKey]). Refused while a wallet is saved.
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
        if (accepted) persist(fresh = true) else saved = SaveState.Staged
    }

    /** The fingerprint sheet said "matches". Nothing was written before this. */
    fun acceptStagedKey() {
        if (saved != SaveState.Staged) return
        saved = SaveState.NotSaved
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
            is UnlockResult.Opened -> if (holdsThisKey(r.record)) UnlockResult.Verified else notSaved(UnlockResult.Broken(Cause.Corrupt))
            is UnlockResult.Broken -> notSaved(r) // a copy that cannot open is not a saved wallet, whatever the reason
            else -> r // cancelled, denied, locked out or try later: nothing is learned, and the user can try again
        }
    }

    private fun holdsThisKey(record: WalletRecord) =
        record.fingerprint == fingerprint && record.network == network && record.words == words && record.passphrase == passphrase

    /** The copy on the phone is not this wallet or cannot be opened, so this wallet is not saved after all. The key stays in memory. */
    private fun notSaved(result: UnlockResult): UnlockResult {
        saved = SaveState.NotSaved
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
        saved = SaveState.NotSaved
        notSavedBecause = null
        pendingSave = false
    }

    fun markBackupConfirmed() {
        backupConfirmed = true
        persist()
    }

    fun markAppLockEnabled() {
        appLockEnabled = true
        persist()
    }

    /** After the user confirmed. Erases the saved wallet, then clears memory. A failed erase keeps both and returns false. */
    fun forgetSavedWallet(): Boolean {
        val ok = wallet?.forget() ?: true
        if (ok) clear()
        return ok
    }

    /**
     * Puts a released record into the session, all or nothing: the key must derive and its fingerprint must equal the
     * record's. Derives once. Catches Exception only: a missing native library is a packaging bug and must crash, not
     * offer to delete a good wallet.
     */
    internal fun install(record: WalletRecord): Boolean {
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
        saved = SaveState.Saved
        return true
    }

    /** The single write point. A hardware-only session has no words and writes nothing. [fresh]: this call saves a key the user has not relied on yet. */
    private fun persist(fresh: Boolean = false) {
        val w = wallet ?: return
        if (words.isEmpty() || saved == SaveState.Staged) return
        val mode = if (saved == SaveState.Saved) WriteMode.Replace else WriteMode.Create
        when (val r = w.save(toRecord(), mode)) {
            SaveResult.Written -> {
                saved = SaveState.Saved
                notSavedBecause = null
                pendingSave = false
            }
            SaveResult.AlreadyExists -> {
                pendingSave = false // another copy is there, so trying again would meet it again
                if (fresh) {
                    clear() // lost a race on a key nobody has seen yet: drop ours, never overwrite theirs
                    lastError = TAKEN
                } else {
                    saved = SaveState.NotSaved // never drop a key the user may have written down
                    notSavedBecause = Why.Other
                }
            }
            is SaveResult.NotSaved -> {
                // A failed Replace leaves the earlier copy on the phone, so the wallet stays Saved and only a failed Create is not saved.
                // Downgrading it would make every later write a Create, which meets that copy and is lost for good.
                if (mode == WriteMode.Create) {
                    saved = SaveState.NotSaved
                    notSavedBecause = r.why
                }
                pendingSave = true
            }
        }
    }

    /** Tries a save that failed again, for example when the app comes back to the foreground. Does nothing unless one is pending. */
    fun flushPendingSave() {
        if (pendingSave) persist()
    }

    private fun refuseToReplace(): Boolean {
        if (saved != SaveState.Saved) return false
        lastError = TAKEN // the debug catalog can still reach Create and Import, and this stops them
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
        saved = SaveState.NotSaved
    }

    private companion object {
        /** The refusal under Create or Import while a wallet is saved on this phone. */
        const val TAKEN = "เครื่องนี้มีกระเป๋าบันทึกไว้แล้ว · ถ้าจะใช้กระเป๋าอื่น ให้เริ่มใหม่จากหน้าปลดล็อก"
    }
}
