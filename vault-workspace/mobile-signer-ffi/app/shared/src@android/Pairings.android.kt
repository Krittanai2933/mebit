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

/** The one Keystore alias of the pairing credentials' key. Nothing ever lists aliases. */
private const val PAIRING_ALIAS = "mebit_pairing_v1"

private const val PAIRING_AES_GCM = "AES/GCM/NoPadding"
private const val PAIRING_IV_SIZE = 12
private const val PAIRING_TAG_BITS = 128

/** One lock for every [KeystorePairings], because they all share the one folder and key. */
private val pairingsLock = Any()

/**
 * One file per credential in [dir], named [pairingName]: the IV, then the AES-256-GCM ciphertext and tag. The key needs an unlocked phone
 * and no owner check, because the Safe 7 itself asks to allow each connect. The folder is in no backup.
 */
private class KeystorePairings(private val dir: File) : Pairings {
    override fun read(fingerprint: String): ByteArray? = synchronized(pairingsLock) {
        val file = fileOf(fingerprint) ?: return null
        if (!exists(file)) return null
        try {
            val sealed = file.readFully()
            val key = keyStore().getKey(PAIRING_ALIAS, null) as? SecretKey ?: return null
            val aes = Cipher.getInstance(PAIRING_AES_GCM).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(PAIRING_TAG_BITS, sealed, 0, PAIRING_IV_SIZE)) }
            aes.doFinal(sealed, PAIRING_IV_SIZE, sealed.size - PAIRING_IV_SIZE)
        } catch (e: Exception) {
            null
        }
    }

    override fun write(fingerprint: String, credential: ByteArray): Boolean = synchronized(pairingsLock) {
        val file = fileOf(fingerprint) ?: return false
        val sealed = try {
            val aes = Cipher.getInstance(PAIRING_AES_GCM).apply { init(Cipher.ENCRYPT_MODE, keyStore().getKey(PAIRING_ALIAS, null) as? SecretKey ?: newPairingKey()) }
            check(aes.iv.size == PAIRING_IV_SIZE) { "unexpected GCM IV size" }
            aes.iv + aes.doFinal(credential)
        } catch (e: Exception) {
            return false
        }
        dir.mkdirs()
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
        // finishWrite logs a failed sync, close or rename instead of throwing, so only a read-back proves the credential is there.
        val back = read(fingerprint) ?: return false
        try {
            back.contentEquals(credential)
        } finally {
            back.fill(0)
        }
    }

    override fun delete(fingerprint: String): Boolean = synchronized(pairingsLock) {
        val file = fileOf(fingerprint) ?: return false
        file.delete()
        !exists(file)
    }

    override fun deleteAll(): Boolean = synchronized(pairingsLock) {
        dir.listFiles()?.forEach { it.delete() }
        try {
            keyStore().deleteEntry(PAIRING_ALIAS)
        } catch (e: Exception) {
            // A key left without a file is harmless, because the next write reuses it, so the files decide.
        }
        dir.listFiles().isNullOrEmpty()
    }

    private fun fileOf(fingerprint: String): AtomicFile? = pairingName(fingerprint)?.let { AtomicFile(File(dir, it)) }

    private fun exists(file: AtomicFile): Boolean = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()
}

/** Null below API 30, where the wallet store and the book keep nothing either. */
@Composable
internal actual fun rememberPairings(): Pairings? {
    val context = LocalContext.current
    return remember(context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) null else KeystorePairings(File(context.noBackupFilesDir, "pairing"))
    }
}

private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

private fun newPairingKey(): SecretKey {
    val spec = KeyGenParameterSpec.Builder(PAIRING_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setKeySize(256)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setUnlockedDeviceRequired(true)
        .build()
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
}
