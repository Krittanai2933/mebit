package com.mebit.app

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The one Keystore alias of the saved vaults' key. Nothing ever lists aliases. */
private const val ALIAS = "mebit_vaults_v1"

private const val AES_GCM = "AES/GCM/NoPadding"
private const val IV_SIZE = 12
private const val TAG_BITS = 128

/** One lock for every [KeystoreVaults], because they all share the one file and key. */
private val vaultsFileLock = Any()

/** The file holds the IV, then the AES-256-GCM ciphertext and tag. Its key needs an unlocked phone but no owner check, because the wallet's key prompts at every use and this read must not. */
private class KeystoreVaults(path: File) : VaultsStorage {
    private val file = AtomicFile(path)

    override fun read(): String? = synchronized(vaultsFileLock) {
        if (!fileExists()) return ""
        try {
            val sealed = file.readFully()
            val key = keyStore().getKey(ALIAS, null) as? SecretKey ?: return null
            val aes = Cipher.getInstance(AES_GCM).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, IV_SIZE)) }
            aes.doFinal(sealed, IV_SIZE, sealed.size - IV_SIZE).decodeToString()
        } catch (e: Exception) {
            null
        }
    }

    override fun write(text: String): Boolean = synchronized(vaultsFileLock) {
        val sealed = try {
            val aes = Cipher.getInstance(AES_GCM).apply { init(Cipher.ENCRYPT_MODE, keyStore().getKey(ALIAS, null) as? SecretKey ?: newKey()) }
            check(aes.iv.size == IV_SIZE) { "unexpected GCM IV size" }
            aes.iv + aes.doFinal(text.encodeToByteArray())
        } catch (e: Exception) {
            return false
        }
        file.baseFile.parentFile?.mkdirs()
        val out = try {
            file.startWrite()
        } catch (e: IOException) {
            return false
        }
        try {
            out.write(sealed)
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            return false
        }
        // finishWrite logs a failed sync, close or rename instead of throwing, so only a read-back proves the text is there.
        read() == text
    }

    override fun delete(): Boolean = synchronized(vaultsFileLock) {
        file.delete()
        try {
            keyStore().deleteEntry(ALIAS)
        } catch (e: Exception) {
            // A key left without its file is harmless, because the next write reuses it, so the file decides.
        }
        !fileExists()
    }

    private fun fileExists(): Boolean = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()
}

/** Null below API 30, where the wallet store keeps nothing either. */
@Composable
internal actual fun rememberVaultsStorage(): VaultsStorage? {
    val context = LocalContext.current
    return remember(context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) null else KeystoreVaults(File(context.noBackupFilesDir, "vaults/book"))
    }
}

private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

private fun newKey(): SecretKey {
    val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setKeySize(256)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setUnlockedDeviceRequired(true)
        .build()
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
}
