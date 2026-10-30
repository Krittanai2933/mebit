package com.mebit.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.SoftwareAccountKey
import com.mebit.ffi.signer.generateSoftwareMultisigKey
import com.mebit.ffi.signer.restoreSoftwareMultisigKey

/**
 * In-memory onboarding state for Movie Page 00.* screens.
 * Secrets stay here until a later secure-storage PR.
 */
class OnboardingSession {
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

    fun generateThisDevice(passphrase: String = "", network: KeyNetwork = KeyNetwork.TEST) {
        lastError = null
        try {
            applyKey(generateSoftwareMultisigKey(passphrase, network, 0u), passphrase)
        } catch (e: Exception) {
            lastError = e.message
        }
    }

    /** An imported key counts as backed up: the user typed the words from their own record. */
    fun restoreFromWords(words: List<String>, passphrase: String = "", network: KeyNetwork = KeyNetwork.TEST) {
        lastError = null
        try {
            applyKey(restoreSoftwareMultisigKey(words, passphrase, network, 0u), passphrase)
            backupConfirmed = true
            imported = true
        } catch (e: Exception) {
            lastError = e.message
        }
    }

    /** Forget the key, for an import the user rejected at the fingerprint check. */
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
    }

    fun markBackupConfirmed() {
        backupConfirmed = true
    }

    fun markAppLockEnabled() {
        appLockEnabled = true
    }

    private fun applyKey(key: SoftwareAccountKey, passphrase: String) {
        words = key.mnemonicWords
        fingerprint = key.masterFingerprint
        xpub = key.xpub
        derivationPath = key.derivationPath
        this.passphrase = passphrase
        backupConfirmed = false
        imported = false
    }
}
