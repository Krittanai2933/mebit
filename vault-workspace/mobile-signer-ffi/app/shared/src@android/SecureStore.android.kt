package com.mebit.app

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.AtomicFile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The one Keystore alias of the wallet's key pair. Nothing ever lists aliases. */
private const val ALIAS = "com.mebit.app.wallet"

/** The lowest API level that stores: the prompt needs `setAllowedAuthenticators` and the key `setUserAuthenticationParameters`. */
private const val MIN_API = Build.VERSION_CODES.R

private const val RSA_OAEP = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"

/** SHA-1 for MGF1, the only MGF1 digest the Keystore accepts below API 34. */
private val OAEP = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)

private const val AES_GCM = "AES/GCM/NoPadding"
/** The size of an RSA-2048 block, so of every wrapped key. */
private const val WRAPPED_SIZE = 256
private const val IV_SIZE = 12
private const val TAG_SIZE = 16

/** "MW" and version 1, so a future layout is told apart from damage. */
private val ENVELOPE_HEADER = byteArrayOf(0x4D, 0x57, 1)

/**
 * Taken around every Keystore and file call of the store, so a write's key check and file swap never interleave with another
 * call. Never held while the OS sheet is up, so a probe or a foreground flush never waits for the owner.
 */
private val storeLock = Any()

/**
 * Which copy each place's file holds, as this process counts it. Guarded by [storeLock]. Top level, never a field of [KeystoreShelf],
 * which has one instance per Context: a counter per instance would let a stale Replace from another instance through.
 */
private val copies: Map<Place, CopyCounter> = Place.all.associateWith { CopyCounter() }

/**
 * The data key every envelope on this phone carries, with its RSA-wrapped copy. One prompt unwraps one key (measured 3 Nov), so
 * one wrapped key in every envelope is what makes one prompt open every wallet. [wrapped] is the 256 bytes the layout always had.
 * Minted at a fresh phone's first write, from the public half, with no prompt. On a phone that already holds a wallet it is adopted
 * from the envelope the first prompt unwrapped, so that wallet's file is never rewritten to get a shared key and an older build can
 * still open it. Lives beside the words in memory, for the same lifetime.
 */
internal class PhoneKey(val dataKey: ByteArray, val wrapped: ByteArray)

/** Guarded by [storeLock]. Top level for the reason [copies] is: one per process across every Context. */
private var phoneKey: PhoneKey? = null

/** One background thread for key generation, which may take seconds in a TEE or StrongBox. */
private val keyMaker = Executors.newSingleThreadExecutor { Thread(it, "mebit-wallet-key").apply { isDaemon = true } }

/** The running or last key generation, and what it threw. Guarded by [storeLock]. */
private var keyJob: Future<Throwable?>? = null

/**
 * The wallets in this phone's Keystore, one place per wallet. `noBackupFilesDir/wallet` for place 1 and `wallet.n` for the rest
 * each hold one envelope ([sealEnvelope]) written through `AtomicFile`, so a reader sees the old copy or the new one, never half.
 * Their AES key is wrapped by an RSA-2048 pair under [ALIAS] that needs the owner (a strong biometric or the screen lock) for every use of its
 * private half, needs an unlocked phone, and is made in StrongBox when the phone has one. A write uses only the public half, so
 * it never prompts. A read shows one framework sheet with the private half as its `CryptoObject`. With `allowBackup=false` and
 * `noBackupFilesDir` the files stay out of backups, and the key never leaves the Keystore, so a copy on another phone opens nothing.
 *
 * The key is made off the main thread, when the probe finds no wallet on a phone with a screen lock, after an erase, or at a
 * write that finds none, never over a stored wallet's key. A write that arrives before it is ready answers TryLater, and
 * the session saves again at its next write point or when the app returns. Logs nothing.
 */
private class KeystoreShelf(private val context: Context) : SecureShelf {
    private val files: Map<Place, AtomicFile> = Place.all.associateWith { walletFile(it) }

    private val stores: Map<Place, SecureStore> = Place.all.associateWith { KeystorePlace(it) }

    override fun store(place: Place): SecureStore = stores.getValue(place)

    /**
     * One prompt per group of envelopes that share a wrapped key, which on a healthy phone is one group. The first group's refusal
     * refuses the whole read. A later group's refusal covers that group's places only: it can exist only after a downgrade to an
     * older build or a damaged wrapped key, so its second sheet is rare and loses nothing.
     */
    override suspend fun readEvery(reason: String): EveryRead {
        val start = withContext(Dispatchers.IO) { synchronized(storeLock) { startEveryRead() } }
        if (start !is EveryStart.Ask) return (start as EveryStart.Done).result
        val out = start.already.toMutableMap()
        try {
            val (parsed, damaged) = start.envelopes.partition { wrappedKeyOf(it.bytes) != null }
            damaged.forEach { out[it.place] = ReadResult.Unreadable } // a file that does not parse never costs a sheet
            var passedOnce = false
            for (group in parsed.groupBy { wrappedKeyOf(it.bytes)!!.toList() }.values) {
                val cipher = withContext(Dispatchers.IO) {
                    try {
                        unwrapCipher(start.privateKey)
                    } catch (e: Exception) {
                        group.forEach { out[it.place] = androidInitOutcome(e) }
                        null
                    }
                } ?: continue
                val error = askOwner(context, reason, BiometricPrompt.CryptoObject(cipher))
                if (error != null) {
                    if (!passedOnce) return EveryRead.Refused(androidPromptOutcome(error))
                    group.forEach { out[it.place] = androidPromptOutcome(error) }
                    continue
                }
                passedOnce = true
                withContext(Dispatchers.IO) {
                    synchronized(storeLock) {
                        val (results, unwrapped) = openWithOneUnwrap(group.map { it.bytes to it.version }, cipher::doFinal, ::keystoreCode)
                        group.zip(results) { e, r -> out[e.place] = r }
                        if (phoneKey == null && unwrapped != null) {
                            phoneKey = PhoneKey(unwrapped, wrappedKeyOf(group.first().bytes)!!)
                        } else {
                            unwrapped?.fill(0)
                        }
                    }
                }
            }
            return EveryRead.Found(out.filterValues { it != ReadResult.NotFound })
        } finally {
            start.envelopes.forEach { it.bytes.fill(0) }
        }
    }

    /** Every place's file, then the key pair, then a fresh key for the next wallet, as a Forget has always done. A crash midway reads as KeyGone and recovery offers Forget again. */
    override fun eraseEvery(): Boolean = synchronized(storeLock) {
        copies.values.forEach { it.advance() }
        try {
            keyStore().deleteEntry(ALIAS)
        } catch (e: Exception) {
            // A key left without its files reads as Empty and is reused by the next write, so the files decide.
        }
        files.values.forEach { it.delete() }
        forgetPhoneKey()
        val nothingLeft = Place.all.none(::walletFileExists)
        if (nothingLeft && deviceSecure()) startKey()
        nothingLeft
    }

    private fun walletFile(place: Place) = AtomicFile(File(context.noBackupFilesDir, walletFileName(place)))

    /** Whether [place]'s file is there, counting the backup `AtomicFile` restores after a write that died midway. */
    private fun walletFileExists(place: Place): Boolean = files.getValue(place).baseFile.let { it.exists() || File(it.path + ".bak").exists() }

    private fun deviceSecure(): Boolean = context.getSystemService(KeyguardManager::class.java).isDeviceSecure

    /** Every held place's envelope and the key, or why the read stops before the sheet. */
    private fun startEveryRead(): EveryStart {
        val held = Place.all.filter(::walletFileExists)
        val fileExists = held.isNotEmpty()
        androidReadRefusal(Build.VERSION.SDK_INT, fileExists, if (fileExists) aliasPresent() else false)?.let { refusal ->
            return EveryStart.Done(
                when (refusal) {
                    ReadResult.NotFound -> EveryRead.Found(emptyMap())
                    ReadResult.KeyGone -> EveryRead.Found(held.associateWith { ReadResult.KeyGone })
                    else -> EveryRead.Refused(refusal)
                },
            )
        }
        val privateKey = try {
            keyStore().getKey(ALIAS, null) as? PrivateKey ?: return EveryStart.Done(EveryRead.Found(held.associateWith { ReadResult.KeyGone }))
        } catch (e: Exception) {
            return EveryStart.Done(EveryRead.Found(held.associateWith { androidInitOutcome(e) }))
        }
        val already = mutableMapOf<Place, ReadResult>()
        val envelopes = held.mapNotNull { place ->
            try {
                Envelope(place, files.getValue(place).readFully(), copies.getValue(place).current) // taken with the envelope, so a write during the sheet makes both stale together
            } catch (e: IOException) {
                already[place] = ReadResult.Unavailable(Why.TryLater)
                null
            }
        }
        return EveryStart.Ask(envelopes, privateKey, already)
    }

    /** What a write answers while the key is missing, starting its generation when none is running. Holds [storeLock]. */
    private fun keyNotReady(): WriteResult {
        val job = keyJob
        if (job != null && !job.isDone) return WriteResult.Unavailable(Why.TryLater)
        keyJob = null
        job?.get()?.let { return androidGenerationOutcome(it) } // the next write starts again
        startKey()
        return WriteResult.Unavailable(Why.TryLater)
    }

    /** Starts making the key in the background unless a generation is running. Holds [storeLock]. */
    private fun startKey() {
        if (Build.VERSION.SDK_INT < MIN_API || keyJob?.isDone == false) return
        keyJob = keyMaker.submit(Callable { generateKeyIfMissing() })
    }

    /** Holds [storeLock]. */
    private fun forgetPhoneKey() {
        phoneKey?.dataKey?.fill(0)
        phoneKey = null
    }

    private inner class KeystorePlace(private val place: Place) : SecureStore {
        private val file = files.getValue(place)
        private val copy = copies.getValue(place)

        override fun probe(): StoreProbe = synchronized(storeLock) {
            val fileExists = Place.all.any(::walletFileExists)
            val probe = androidProbeOutcome(Build.VERSION.SDK_INT, fileExists, if (fileExists) aliasPresent() else false, deviceSecure())
            if (probe == StoreProbe.Empty) startKey()
            probe
        }

        override suspend fun read(reason: String): ReadResult {
            val start = withContext(Dispatchers.IO) { synchronized(storeLock) { startRead() } }
            if (start !is ReadStart.Ask) return (start as ReadStart.Done).result
            val error = askOwner(context, reason, BiometricPrompt.CryptoObject(start.cipher))
            if (error != null) return androidPromptOutcome(error).also { start.envelope.fill(0) }
            return withContext(Dispatchers.IO) {
                synchronized(storeLock) { openEnvelope(start.envelope, start.cipher::doFinal, ::keystoreCode, start.version) }.also { start.envelope.fill(0) }
            }
        }

        override fun write(bytes: ByteArray, mode: WriteMode): WriteResult = synchronized(storeLock) {
            copy.refuse(mode)?.let { return it } // a stale Replace touches neither the key nor the file
            androidWriteRefusal(Build.VERSION.SDK_INT, walletFileExists(place), deviceSecure(), mode)?.let { return it }
            val certificate = try {
                keyStore().getCertificate(ALIAS)
            } catch (e: Exception) {
                return WriteResult.Unavailable(Why.TryLater)
            }
            if (certificate == null) {
                // The pair is gone, as after a screen lock was removed, so the phone key's wrapped copy can never be opened again. A new key must not reuse it.
                forgetPhoneKey()
                return keyNotReady()
            }
            val envelope = try {
                // A software copy of the public half, so the wrap runs outside the Keystore and never needs the owner.
                val key = phoneKey ?: mintPhoneKey(KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(certificate.publicKey.encoded))).also { phoneKey = it }
                sealEnvelope(bytes, key)
            } catch (e: Exception) {
                return WriteResult.Unavailable(Why.Other)
            }
            val out = try {
                file.startWrite()
            } catch (e: IOException) {
                return WriteResult.Unavailable(Why.Other)
            }
            try {
                out.write(envelope)
                file.finishWrite(out)
                WriteResult.Written(copy.advance())
            } catch (e: IOException) {
                file.failWrite(out) // the old file stays, so the copy does not advance
                WriteResult.Unavailable(Why.Other)
            }
        }

        /** Deletes this place's file only. The key pair and [phoneKey] stay, because every other place still needs them. */
        override fun erase(): EraseResult = synchronized(storeLock) {
            val version = copy.advance()
            file.delete()
            androidEraseOutcome(walletFileExists(place), version)
        }

        override fun marker(): Marker = Marker.Unsupported

        /** Android keeps no marker: an uninstall removes the file and the key together, so no orphan outlives it. */
        override fun writeMarker(marker: Marker): Boolean = true

        /** The file, the key and an initialised cipher, or why a read stops before the sheet. */
        private fun startRead(): ReadStart {
            val fileExists = walletFileExists(place)
            androidReadRefusal(Build.VERSION.SDK_INT, fileExists, if (fileExists) aliasPresent() else false)?.let { return ReadStart.Done(it) }
            val envelope = try {
                file.readFully()
            } catch (e: IOException) {
                return ReadStart.Done(ReadResult.Unavailable(Why.TryLater))
            }
            return try {
                val key = keyStore().getKey(ALIAS, null) as? PrivateKey ?: return ReadStart.Done(ReadResult.KeyGone)
                ReadStart.Ask(envelope, unwrapCipher(key), copy.current) // taken with the envelope, so a write during the sheet makes both stale together
            } catch (e: Exception) {
                envelope.fill(0)
                ReadStart.Done(androidInitOutcome(e))
            }
        }
    }
}

/** [place]'s file under `noBackupFilesDir`. Place 1 keeps today's file, so the wallet already on a phone is read from where it is, and each later place has its own. */
internal fun walletFileName(place: Place): String = if (place == Place.FIRST) "wallet" else "wallet.${place.number}"

/** Where a read stands before the sheet. */
private sealed interface ReadStart {
    class Done(val result: ReadResult) : ReadStart

    class Ask(val envelope: ByteArray, val cipher: Cipher, val version: StoreVersion) : ReadStart
}

/** One place's bytes as read before the sheet, and the copy they are. */
private class Envelope(val place: Place, val bytes: ByteArray, val version: StoreVersion)

/** Where a read of every place stands before the sheet. */
private sealed interface EveryStart {
    class Done(val result: EveryRead) : EveryStart

    /** [already] holds the places that answered before any sheet. */
    class Ask(val envelopes: List<Envelope>, val privateKey: PrivateKey, val already: Map<Place, ReadResult>) : EveryStart
}

/** The Keystore shelf over this Context, which the read's sheet needs. Every instance shares [storeLock] and the files. */
@Composable
internal actual fun rememberSecureShelf(): SecureShelf {
    val context = LocalContext.current
    return remember(context) { KeystoreShelf(context) }
}

private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

/** Whether the alias exists, or null when the Keystore could not answer. */
private fun aliasPresent(): Boolean? = try {
    keyStore().containsAlias(ALIAS)
} catch (e: Exception) {
    null
}

/** Makes the pair unless it exists, StrongBox first. Runs on [keyMaker] without [storeLock]. Returns what it threw. */
private fun generateKeyIfMissing(): Throwable? = try {
    if (aliasPresent() != true) {
        try {
            generateKey(strongBox = true)
        } catch (e: StrongBoxUnavailableException) {
            generateKey(strongBox = false)
        }
    }
    null
} catch (e: Exception) {
    e
}

private fun generateKey(strongBox: Boolean) {
    val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setKeySize(WRAPPED_SIZE * 8)
        .setDigests(KeyProperties.DIGEST_SHA256)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
        .setUserAuthenticationRequired(true)
        .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
        .setInvalidatedByBiometricEnrollment(false)
        .setUnlockedDeviceRequired(true)
        .setIsStrongBoxBacked(strongBox)
        .build()
    KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
}

/** The numeric code of an `android.security.KeyStoreException` in [t]'s causes, public from API 33, else null. */
private fun keystoreCode(t: Throwable): Int? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    return generateSequence(t) { it.cause }.filterIsInstance<android.security.KeyStoreException>().firstOrNull()?.numericErrorCode
}

/**
 * What the probe makes of the facts, in this order: the API level, the wallet file, its key, the screen lock. A file without
 * its key is KeyGone even with no screen lock, because removing the lock deletes the key and keeps the file. [aliasPresent]
 * is null when the Keystore threw, and is not read without a file.
 */
internal fun androidProbeOutcome(api: Int, fileExists: Boolean, aliasPresent: Boolean?, deviceSecure: Boolean): StoreProbe = when {
    api < MIN_API -> StoreProbe.Unavailable(Why.OsTooOld)
    fileExists -> when (aliasPresent) {
        true -> StoreProbe.Present
        false -> StoreProbe.KeyGone
        null -> StoreProbe.Unavailable(Why.TryLater)
    }
    !deviceSecure -> StoreProbe.Unavailable(Why.NoScreenLock)
    else -> StoreProbe.Empty
}

/** Why a read stops before the sheet, or null when the file and its key are there. */
internal fun androidReadRefusal(api: Int, fileExists: Boolean, aliasPresent: Boolean?): ReadResult? = when {
    api < MIN_API -> ReadResult.Unavailable(Why.OsTooOld)
    !fileExists -> ReadResult.NotFound
    aliasPresent == null -> ReadResult.Unavailable(Why.TryLater)
    !aliasPresent -> ReadResult.KeyGone
    else -> null
}

/** A cipher that will not take the private half. Only an invalidated key is gone; a locked phone and the rest try again. */
internal fun androidInitOutcome(t: Throwable): ReadResult =
    if (t is KeyPermanentlyInvalidatedException) ReadResult.KeyGone else ReadResult.Unavailable(Why.TryLater)

/**
 * The sheet's error code. Cancel, a system cancel and five wrong PINs all arrive as a cancel (measured: 10), so the unlock
 * screen must never ask again by itself. A wrong attempt is retried inside the sheet, so nothing is Denied.
 */
internal fun androidPromptOutcome(errorCode: Int): ReadResult = when (errorCode) {
    BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED, BiometricPrompt.BIOMETRIC_ERROR_CANCELED, NEGATIVE_BUTTON -> ReadResult.Cancelled
    BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT, BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT -> ReadResult.LockedOut
    BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS, BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL -> ReadResult.Unavailable(Why.NoScreenLock)
    else -> ReadResult.Unavailable(Why.TryLater)
}

/** The framework's hidden code for the negative button, which the AndroidX prompt names ERROR_NEGATIVE_BUTTON. */
private const val NEGATIVE_BUTTON = 13

/**
 * A failed unwrap or decrypt after the owner passed. Damage (a bad tag or padding, or Keystore code 10 for a key that is not
 * this one) is Unreadable. Anything else, and code 10 where API 30 to 32 cannot read it, tries again rather than offering to
 * delete a copy that may be good.
 */
internal fun androidUnwrapOutcome(t: Throwable, keystoreCode: Int?): ReadResult =
    if (t is BadPaddingException || keystoreCode == 10) ReadResult.Unreadable else ReadResult.Unavailable(Why.TryLater)

/** Why a write stops before the key is touched, or null to go on. A Create meets any wallet file, readable or not. */
internal fun androidWriteRefusal(api: Int, fileExists: Boolean, deviceSecure: Boolean, mode: WriteMode): WriteResult? = when {
    api < MIN_API -> WriteResult.Unavailable(Why.OsTooOld)
    mode == WriteMode.Create && fileExists -> WriteResult.AlreadyExists
    !deviceSecure -> WriteResult.Unavailable(Why.NoScreenLock)
    else -> null
}

/** A key generation that threw. Measured: with no screen lock it is InvalidAlgorithmParameterException <- IllegalStateException. */
internal fun androidGenerationOutcome(t: Throwable): WriteResult =
    if (generateSequence(t) { it.cause }.any { it is IllegalStateException }) WriteResult.Unavailable(Why.NoScreenLock) else WriteResult.Unavailable(Why.Other)

/** An erase is done when no wallet file is left. A key left behind is harmless. */
internal fun androidEraseOutcome(fileLeft: Boolean, version: StoreVersion): EraseResult = if (fileLeft) EraseResult.Failed(Why.Other, version) else EraseResult.Erased

/** A cipher that unwraps an envelope's AES key with [privateKey]. */
internal fun unwrapCipher(privateKey: PrivateKey): Cipher = Cipher.getInstance(RSA_OAEP).apply { init(Cipher.DECRYPT_MODE, privateKey, OAEP) }

/** A fresh data key, wrapped for [publicKey]. Never prompts. */
internal fun mintPhoneKey(publicKey: PublicKey): PhoneKey {
    val dataKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
    try {
        return PhoneKey(dataKey, Cipher.getInstance(RSA_OAEP).apply { init(Cipher.ENCRYPT_MODE, publicKey, OAEP) }.doFinal(dataKey))
    } catch (e: Exception) {
        dataKey.fill(0)
        throw e
    }
}

/**
 * [payload] under [key]'s AES-256-GCM data key with a fresh IV, the key RSA-wrapped as [PhoneKey.wrapped]:
 * `[header 3][wrapped length u32][wrapped key][IV 12][ciphertext and tag]`. The header is authenticated as GCM's extra data.
 */
internal fun sealEnvelope(payload: ByteArray, key: PhoneKey): ByteArray {
    val aes = Cipher.getInstance(AES_GCM).apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.dataKey, "AES")) }
    val iv = aes.iv
    check(iv.size == IV_SIZE) { "unexpected GCM IV size" }
    val header = ENVELOPE_HEADER + ByteBuffer.allocate(4).putInt(key.wrapped.size).array()
    aes.updateAAD(header)
    return header + key.wrapped + iv + aes.doFinal(payload)
}

/** The wrapped key [envelope] carries, or null when its layout does not parse. Envelopes with the same bytes here open with one unwrap. */
internal fun wrappedKeyOf(envelope: ByteArray): ByteArray? {
    val headerSize = ENVELOPE_HEADER.size + 4
    if (envelope.size < headerSize + WRAPPED_SIZE + IV_SIZE + TAG_SIZE || !envelope.copyOf(ENVELOPE_HEADER.size).contentEquals(ENVELOPE_HEADER)) return null
    if (ByteBuffer.wrap(envelope, ENVELOPE_HEADER.size, 4).int != WRAPPED_SIZE) return null
    return envelope.copyOfRange(headerSize, headerSize + WRAPPED_SIZE)
}

/**
 * The payload of [envelope], unwrapping its key with [unwrap] (the authorised Keystore cipher). A layout that does not parse,
 * a key that does not unwrap and a tag that fails are Unreadable. [keystoreCode] reads the Keystore's code from a failure.
 * [version] is the copy the envelope was read from, and rides on the Bytes.
 */
internal fun openEnvelope(envelope: ByteArray, unwrap: (ByteArray) -> ByteArray, keystoreCode: (Throwable) -> Int?, version: StoreVersion): ReadResult {
    val wrapped = wrappedKeyOf(envelope) ?: return ReadResult.Unreadable
    val headerSize = ENVELOPE_HEADER.size + 4
    val ivAt = headerSize + wrapped.size
    var dataKey: ByteArray? = null
    return try {
        dataKey = unwrap(wrapped)
        val aes = Cipher.getInstance(AES_GCM).apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(dataKey, "AES"), GCMParameterSpec(TAG_SIZE * 8, envelope, ivAt, IV_SIZE))
            updateAAD(envelope, 0, headerSize)
        }
        ReadResult.Bytes(aes.doFinal(envelope, ivAt + IV_SIZE, envelope.size - ivAt - IV_SIZE), version)
    } catch (e: Exception) {
        androidUnwrapOutcome(e, keystoreCode(e))
    } finally {
        dataKey?.fill(0)
    }
}

/**
 * Every envelope of one group of equal wrapped keys, each with the copy it was read from, opened with one use of [unwrap]: the first
 * envelope that unwraps runs it and the rest reuse that key, because a Keystore use costs an OS prompt. Returns each result in
 * order and the data key as a copy the caller owns, or null when nothing unwrapped.
 */
internal fun openWithOneUnwrap(
    envelopes: List<Pair<ByteArray, StoreVersion>>,
    unwrap: (ByteArray) -> ByteArray,
    keystoreCode: (Throwable) -> Int?,
): Pair<List<ReadResult>, ByteArray?> {
    var key: ByteArray? = null
    val results = envelopes.map { (envelope, version) ->
        openEnvelope(envelope, { wrapped -> key?.copyOf() ?: unwrap(wrapped).also { key = it.copyOf() } }, keystoreCode, version)
    }
    return results to key
}
