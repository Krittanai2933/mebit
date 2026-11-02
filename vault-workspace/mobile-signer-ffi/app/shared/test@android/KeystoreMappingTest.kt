package com.mebit.app

import android.hardware.biometrics.BiometricPrompt
import android.security.keystore.KeyPermanentlyInvalidatedException
import java.security.InvalidAlgorithmParameterException
import java.security.InvalidKeyException
import java.security.KeyPairGenerator
import java.nio.ByteBuffer
import java.security.ProviderException
import java.security.spec.MGF1ParameterSpec
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The Keystore store's decisions, fed plain facts (API level, file and alias present, screen lock, exceptions, prompt error
 * codes), and its envelope, sealed and opened with a software RSA pair. The Keystore and the OS prompt themselves are proven
 * on an emulator (`.audit/d1-secure-storage/android/spike-2026-11-03.md`), because the host JVM has neither.
 */
class KeystoreMappingTest {
    @Test
    fun belowApi30NothingIsStoredWhateverThePhoneHolds() {
        for (api in 24..29) {
            everyFact { fileExists, alias, secure ->
                assertEquals("Unavailable(OsTooOld)", name(androidProbeOutcome(api, fileExists, alias, secure)), "probe api $api")
                assertEquals("Unavailable(OsTooOld)", name(androidReadRefusal(api, fileExists, alias)), "read api $api")
                WriteMode.entries.forEach { mode ->
                    assertEquals("Unavailable(OsTooOld)", name(androidWriteRefusal(api, fileExists, secure, mode)), "write api $api $mode")
                }
            }
        }
    }

    @Test
    fun aWalletFileWithItsKeyIsPresentWithOrWithoutAScreenLock() {
        listOf(true, false).forEach { secure ->
            assertEquals("Present", name(androidProbeOutcome(30, fileExists = true, aliasPresent = true, deviceSecure = secure)), "secure $secure")
        }
    }

    @Test
    fun aWalletFileWithoutItsKeyIsKeyGoneEvenWithNoScreenLock() {
        // Measured: removing the PIN deletes the alias and keeps the file. That phone must reach the recovery screen, not Welcome.
        listOf(true, false).forEach { secure ->
            assertEquals("KeyGone", name(androidProbeOutcome(36, fileExists = true, aliasPresent = false, deviceSecure = secure)), "secure $secure")
        }
    }

    @Test
    fun aKeystoreThatThrowsBesideAWalletFileIsTryLater() {
        listOf(true, false).forEach { secure ->
            assertEquals("Unavailable(TryLater)", name(androidProbeOutcome(36, fileExists = true, aliasPresent = null, deviceSecure = secure)), "secure $secure")
        }
    }

    @Test
    fun noWalletFileIsEmptyWithAScreenLockAndNoScreenLockWithout() {
        listOf(true, false, null).forEach { alias ->
            assertEquals("Empty", name(androidProbeOutcome(30, fileExists = false, aliasPresent = alias, deviceSecure = true)), "alias $alias")
            assertEquals("Unavailable(NoScreenLock)", name(androidProbeOutcome(30, fileExists = false, aliasPresent = alias, deviceSecure = false)), "alias $alias")
        }
    }

    @Test
    fun aReadStartsOnlyWithTheFileAndItsKey() {
        assertEquals("NotFound", name(androidReadRefusal(30, fileExists = false, aliasPresent = true)))
        assertEquals("NotFound", name(androidReadRefusal(30, fileExists = false, aliasPresent = false)))
        assertEquals("KeyGone", name(androidReadRefusal(30, fileExists = true, aliasPresent = false)))
        assertEquals("Unavailable(TryLater)", name(androidReadRefusal(30, fileExists = true, aliasPresent = null)))
        assertNull(androidReadRefusal(30, fileExists = true, aliasPresent = true))
    }

    @Test
    fun aPermanentlyInvalidatedKeyIsKeyGoneAndAnyOtherInitFailureIsTryLater() {
        assertEquals("KeyGone", name(androidInitOutcome(KeyPermanentlyInvalidatedException())))
        // Measured while the device was locked: InvalidKeyException <- KeyStoreException(code=2).
        listOf(InvalidKeyException(), ProviderException(), IllegalStateException(), NullPointerException()).forEach { t ->
            assertEquals("Unavailable(TryLater)", name(androidInitOutcome(t)), t::class.simpleName)
        }
    }

    @Test
    fun aCancelledPromptIsCancelledAndFiveWrongPinsArriveTheSameWay() {
        listOf(
            BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED,
            BiometricPrompt.BIOMETRIC_ERROR_CANCELED,
            13, // the negative button, a code the framework keeps hidden
        ).forEach { code -> assertEquals("Cancelled", name(androidPromptOutcome(code)), "code $code") }
        assertEquals(10, BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED, "the code the emulator sent for Cancel and for five wrong PINs")
    }

    @Test
    fun aLockoutIsLockedOut() {
        listOf(BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT, BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT).forEach { code ->
            assertEquals("LockedOut", name(androidPromptOutcome(code)), "code $code")
        }
    }

    @Test
    fun noEnrolledCheckAndNoCredentialAreNoScreenLock() {
        listOf(BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS, BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL).forEach { code ->
            assertEquals("Unavailable(NoScreenLock)", name(androidPromptOutcome(code)), "code $code")
        }
    }

    @Test
    fun anyOtherPromptErrorIsTryLaterAndNoneIsDenied() {
        listOf(
            BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BiometricPrompt.BIOMETRIC_ERROR_UNABLE_TO_PROCESS,
            BiometricPrompt.BIOMETRIC_ERROR_TIMEOUT,
            BiometricPrompt.BIOMETRIC_ERROR_NO_SPACE,
            BiometricPrompt.BIOMETRIC_ERROR_VENDOR,
            BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT,
            BiometricPrompt.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,
            0,
            999,
        ).forEach { code -> assertEquals("Unavailable(TryLater)", name(androidPromptOutcome(code)), "code $code") }
        (0..20).forEach { code -> assertFalse(androidPromptOutcome(code) == ReadResult.Denied, "code $code") }
    }

    @Test
    fun anUnwrapThatTheKeystoreCallsInvalidOrADamagedCiphertextIsUnreadable() {
        // Measured: an envelope wrapped for another key gave IllegalBlockSizeException <- KeyStoreException(code=10).
        assertEquals("Unreadable", name(androidUnwrapOutcome(IllegalBlockSizeException(), keystoreCode = 10)))
        assertEquals("Unreadable", name(androidUnwrapOutcome(AEADBadTagException(), keystoreCode = null)))
        assertEquals("Unreadable", name(androidUnwrapOutcome(BadPaddingException(), keystoreCode = null)))
    }

    @Test
    fun anyOtherUnwrapFailureIsTryLater() {
        // Measured: a second use of the key after one prompt gave IllegalBlockSizeException <- KeyStoreException(code=2).
        assertEquals("Unavailable(TryLater)", name(androidUnwrapOutcome(IllegalBlockSizeException(), keystoreCode = 2)))
        // API 30 to 32 cannot read the code, so a wrong key there tries again rather than offering to delete.
        assertEquals("Unavailable(TryLater)", name(androidUnwrapOutcome(IllegalBlockSizeException(), keystoreCode = null)))
        assertEquals("Unavailable(TryLater)", name(androidUnwrapOutcome(ProviderException(BadPaddingException()), keystoreCode = null)))
    }

    @Test
    fun aCreateMeetsAnyWalletFileAsAlreadyThere() {
        listOf(true, false).forEach { secure ->
            assertEquals("AlreadyExists", name(androidWriteRefusal(30, fileExists = true, deviceSecure = secure, mode = WriteMode.Create)), "secure $secure")
        }
    }

    @Test
    fun aWriteWithoutAScreenLockIsNoScreenLock() {
        assertEquals("Unavailable(NoScreenLock)", name(androidWriteRefusal(30, fileExists = false, deviceSecure = false, mode = WriteMode.Create)))
        listOf(true, false).forEach { fileExists ->
            assertEquals("Unavailable(NoScreenLock)", name(androidWriteRefusal(30, fileExists, deviceSecure = false, mode = WriteMode.Replace)), "file $fileExists")
        }
    }

    @Test
    fun aWriteGoesOnWithAScreenLockUnlessACreateMeetsAFile() {
        assertNull(androidWriteRefusal(30, fileExists = false, deviceSecure = true, mode = WriteMode.Create))
        assertNull(androidWriteRefusal(30, fileExists = false, deviceSecure = true, mode = WriteMode.Replace))
        assertNull(androidWriteRefusal(36, fileExists = true, deviceSecure = true, mode = WriteMode.Replace))
    }

    @Test
    fun aKeyRefusedForAMissingScreenLockIsNoScreenLockAndAnyOtherFailureIsOther() {
        // Measured: generation with no screen lock threw InvalidAlgorithmParameterException <- IllegalStateException.
        assertEquals("Unavailable(NoScreenLock)", name(androidGenerationOutcome(InvalidAlgorithmParameterException(IllegalStateException()))))
        listOf(InvalidAlgorithmParameterException(), ProviderException(), RuntimeException()).forEach { t ->
            assertEquals("Unavailable(Other)", name(androidGenerationOutcome(t)), t::class.simpleName)
        }
    }

    @Test
    fun anEraseIsDoneOnlyWhenNoWalletFileIsLeft() {
        assertEquals("Erased", name(androidEraseOutcome(fileLeft = false)))
        assertEquals("Failed(Other)", name(androidEraseOutcome(fileLeft = true)))
    }

    @Test
    fun anEnvelopeOpensToItsPayload() {
        listOf(ByteArray(173) { it.toByte() }, byteArrayOf(7), ByteArray(4096) { (it * 31).toByte() }).forEach { payload ->
            val opened = openEnvelope(sealEnvelope(payload, pair.public), unwrapWith(pair), noCode)
            assertContentEquals(payload, assertIs<ReadResult.Bytes>(opened).bytes, "payload of ${payload.size} bytes")
        }
    }

    @Test
    fun twoSealsOfOnePayloadDiffer() {
        val payload = ByteArray(173) { it.toByte() }
        assertFalse(sealEnvelope(payload, pair.public).contentEquals(sealEnvelope(payload, pair.public)), "a fresh data key and IV each time")
    }

    @Test
    fun everySealUsesAFreshDataKey() {
        val payload = ByteArray(173) { it.toByte() }
        // The wrapped key sits after the 3-byte header and the 4-byte length.
        val keys = List(2) { unwrapWith(pair)(sealEnvelope(payload, pair.public).copyOfRange(7, 7 + 256)) }
        assertEquals(32, keys[0].size, "an AES-256 key")
        assertFalse(keys[0].contentEquals(keys[1]), "two seals share a data key")
    }

    @Test
    fun everyFlippedBitIsUnreadable() {
        val sealed = sealEnvelope(ByteArray(173) { it.toByte() }, pair.public)
        // Every byte of the header, the length and the IV, and a byte every 7 through the wrapped key and the ciphertext and tag.
        val positions = (0 until 3 + 4 + 256 + 12 + 8).toSet() + (0 until sealed.size step 7) + (sealed.size - 1)
        positions.forEach { at ->
            val damaged = sealed.copyOf().also { it[at] = (it[at].toInt() xor 0x01).toByte() }
            assertEquals("Unreadable", name(openEnvelope(damaged, unwrapWith(pair), noCode)), "bit flipped at $at of ${sealed.size}")
        }
    }

    @Test
    fun everyShortOrLongEnvelopeIsUnreadable() {
        val sealed = sealEnvelope(ByteArray(173) { it.toByte() }, pair.public)
        (0 until sealed.size).forEach { size ->
            assertEquals("Unreadable", name(openEnvelope(sealed.copyOf(size), unwrapWith(pair), noCode)), "cut to $size of ${sealed.size}")
        }
        assertEquals("Unreadable", name(openEnvelope(sealed + 0, unwrapWith(pair), noCode)), "one byte added")
    }

    @Test
    fun anEnvelopeOfAnotherVersionIsNeverOpenedAsThisOne() {
        // Sealed exactly as version 1 is, but under the header of version 2, so its tag is valid for what it says.
        val header = byteArrayOf(0x4D, 0x57, 2) + ByteBuffer.allocate(4).putInt(256).array()
        val dataKey = ByteArray(32) { 1 }
        val wrapped = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
            .apply { init(Cipher.ENCRYPT_MODE, pair.public, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)) }
            .doFinal(dataKey)
        val aes = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(dataKey, "AES")) }
        aes.updateAAD(header)
        val later = header + wrapped + aes.iv + aes.doFinal(ByteArray(173))
        assertEquals("Unreadable", name(openEnvelope(later, unwrapWith(pair), noCode)))
    }

    @Test
    fun anEnvelopeForAnotherKeyIsUnreadable() {
        val sealed = sealEnvelope(ByteArray(173) { it.toByte() }, pair.public)
        assertEquals("Unreadable", name(openEnvelope(sealed, unwrapWith(otherPair), noCode)))
    }

    @Test
    fun anUnwrapThatNeedsTheOwnerAgainIsTryLaterNotUnreadable() {
        val sealed = sealEnvelope(ByteArray(173) { it.toByte() }, pair.public)
        val needsAuth: (ByteArray) -> ByteArray = { throw IllegalBlockSizeException() }
        assertEquals("Unavailable(TryLater)", name(openEnvelope(sealed, needsAuth) { 2 }))
        assertEquals("Unreadable", name(openEnvelope(sealed, needsAuth) { 10 }))
    }

    private companion object {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val otherPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val noCode: (Throwable) -> Int? = { null }

        fun unwrapWith(keys: java.security.KeyPair): (ByteArray) -> ByteArray = { wrapped -> unwrapCipher(keys.private).doFinal(wrapped) }
    }
}

private fun everyFact(check: (fileExists: Boolean, aliasPresent: Boolean?, deviceSecure: Boolean) -> Unit) =
    listOf(true, false).forEach { file -> listOf(true, false, null).forEach { alias -> listOf(true, false).forEach { secure -> check(file, alias, secure) } } }

/** A result as one comparable word, so a failure prints what came back. */
private fun name(result: Any?): String = when (result) {
    is StoreProbe.Unavailable -> "Unavailable(${result.why})"
    is ReadResult.Unavailable -> "Unavailable(${result.why})"
    is WriteResult.Unavailable -> "Unavailable(${result.why})"
    is EraseResult.Failed -> "Failed(${result.why})"
    null -> "null"
    else -> result.toString()
}
