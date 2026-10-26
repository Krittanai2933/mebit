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
    var backupConfirmed: Boolean by mutableStateOf(false)
        private set
    var appLockEnabled: Boolean by mutableStateOf(false)
        private set
    var lastError: String? by mutableStateOf(null)
        private set

    fun generateThisDevice(passphrase: String = "", network: KeyNetwork = KeyNetwork.TEST) {
        lastError = null
        try {
            applyKey(generateSoftwareMultisigKey(passphrase, network, 0u))
        } catch (e: Exception) {
            lastError = e.message
        }
    }

    fun restoreFromWords(words: List<String>, passphrase: String = "", network: KeyNetwork = KeyNetwork.TEST) {
        lastError = null
        try {
            applyKey(restoreSoftwareMultisigKey(words, passphrase, network, 0u))
        } catch (e: Exception) {
            lastError = e.message
        }
    }

    fun markBackupConfirmed() {
        backupConfirmed = true
    }

    fun markAppLockEnabled() {
        appLockEnabled = true
    }

    private fun applyKey(key: SoftwareAccountKey) {
        words = key.mnemonicWords
        fingerprint = key.masterFingerprint
        xpub = key.xpub
        derivationPath = key.derivationPath
        backupConfirmed = false
    }
}
