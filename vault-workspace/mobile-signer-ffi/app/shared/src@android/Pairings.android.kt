package com.mebit.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.mebit.ffi.signer.KeyNetwork
import java.io.File
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Testnet's Keystore alias of the pairing credentials' key, which stays as it was. Nothing ever lists aliases. */
private const val PAIRING_ALIAS = "mebit_pairing_v1"

/** Where each network keeps its credentials, under `noBackupFilesDir`, and under which alias. Mainnet's folder is a sibling of testnet's, never inside it, because a delete-all lists the folder. */
internal fun pairingPath(network: KeyNetwork): String = network.folder("pairing")

internal fun pairingAlias(network: KeyNetwork): String = network.alias(PAIRING_ALIAS)

private const val PAIRING_AES_GCM = "AES/GCM/NoPadding"
private const val PAIRING_IV_SIZE = 12
private const val PAIRING_TAG_BITS = 128

/** One lock for every [KeystorePairings], because they all share the one folder and key. */
private val pairingsLock = Any()

/**
 * One file per credential in [dir], named [pairingName]: the IV, then the AES-256-GCM ciphertext and tag. The key needs an unlocked phone
 * and no owner check, because the Safe 7 itself asks to allow each connect. The folder is in no backup.
 */
private class KeystorePairings(private val dir: File, private val alias: String) : Pairings {
    override fun read(fingerprint: String): ByteArray? = synchronized(pairingsLock) {
        val file = fileOf(fingerprint) ?: return null
        if (!exists(file)) return null
        try {
            val sealed = file.readFully()
            val key = keyStore().getKey(alias, null) as? SecretKey ?: return null
            val aes = Cipher.getInstance(PAIRING_AES_GCM).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(PAIRING_TAG_BITS, sealed, 0, PAIRING_IV_SIZE)) }
            aes.doFinal(sealed, PAIRING_IV_SIZE, sealed.size - PAIRING_IV_SIZE)
        } catch (e: Exception) {
            null
        }
    }

    override fun write(fingerprint: String, credential: ByteArray): Boolean = synchronized(pairingsLock) {
        val file = fileOf(fingerprint) ?: return false
        val sealed = try {
            val aes = Cipher.getInstance(PAIRING_AES_GCM).apply { init(Cipher.ENCRYPT_MODE, keyStore().getKey(alias, null) as? SecretKey ?: newPairingKey(alias)) }
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
            keyStore().deleteEntry(alias)
        } catch (e: Exception) {
            // A key left without a file is harmless, because the next write reuses it, so the files decide.
        }
        dir.listFiles().isNullOrEmpty()
    }

    private fun fileOf(fingerprint: String): AtomicFile? = pairingName(fingerprint)?.let { AtomicFile(File(dir, it)) }

    private fun exists(file: AtomicFile): Boolean = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()
}

@Composable
internal actual fun rememberPairings(network: KeyNetwork): Pairings {
    val context = LocalContext.current
    return remember(context, network) { KeystorePairings(File(context.noBackupFilesDir, pairingPath(network)), pairingAlias(network)) }
}

private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

private fun newPairingKey(alias: String): SecretKey {
    val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setKeySize(256)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setUnlockedDeviceRequired(true)
        .build()
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
}
