package com.mebit.app

import android.hardware.biometrics.BiometricPrompt
import android.security.keystore.KeyPermanentlyInvalidatedException
import com.mebit.app.data.ANY_VERSION
import com.mebit.ffi.signer.KeyNetwork
import java.security.InvalidAlgorithmParameterException
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.KeyPairGenerator
import java.nio.ByteBuffer
import java.io.FileNotFoundException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Keystore and the OS prompt themselves are proven on an emulator (`.audit/d1-secure-storage/android/spike-2026-11-03.md`), because the host JVM has neither.
 */
class KeystoreMappingTest {
    @Test
    fun aWalletFileWithItsKeyIsPresentWithOrWithoutAScreenLock() {
        listOf(true, false).forEach { secure ->
            assertEquals("Present", name(androidProbeOutcome(fileExists = true, aliasPresent = true, privateHalfFailure = null, deviceSecure = secure)), "secure $secure")
        }
    }

    @Test
    fun aWalletFileWithoutItsKeyIsKeyGoneEvenWithNoScreenLock() {
        // Measured: removing the PIN deletes the alias and keeps the file. That phone must reach the recovery screen, not Welcome.
        listOf(true, false).forEach { secure ->
            assertEquals("KeyGone", name(androidProbeOutcome(fileExists = true, aliasPresent = false, privateHalfFailure = null, deviceSecure = secure)), "secure $secure")
        }
    }

    @Test
    fun aKeystoreThatThrowsBesideAWalletFileIsTryLater() {
        listOf(true, false).forEach { secure ->
            assertEquals("Unavailable(TryLater)", name(androidProbeOutcome(fileExists = true, aliasPresent = null, privateHalfFailure = null, deviceSecure = secure)), "secure $secure")
        }
    }

    @Test
    fun noWalletFileIsEmptyWithAScreenLockAndNoScreenLockWithout() {
        listOf(true, false, null).forEach { alias ->
            assertEquals("Empty", name(androidProbeOutcome(fileExists = false, aliasPresent = alias, privateHalfFailure = null, deviceSecure = true)), "alias $alias")
            assertEquals("Unavailable(NoScreenLock)", name(androidProbeOutcome(fileExists = false, aliasPresent = alias, privateHalfFailure = null, deviceSecure = false)), "alias $alias")
        }
    }

    // Measured on the API 30 emulator: removing the PIN keeps the alias and its blobs, and getKey then throws API 30's invalidation shape.
    // The phone must reach the recovery screen at a cold start, and a return must save every wallet in memory again.
    @Test
    fun aWalletFileBesideAnInvalidatedKeyIsKeyGoneAndAKeyThatFailsOtherwiseIsPresent() {
        listOf(true, false).forEach { secure ->
            assertEquals("KeyGone", name(androidProbeOutcome(fileExists = true, aliasPresent = true, privateHalfFailure = api30Invalidated(), deviceSecure = secure)), "secure $secure")
            assertEquals("KeyGone", name(androidProbeOutcome(fileExists = true, aliasPresent = true, privateHalfFailure = KeyPermanentlyInvalidatedException(), deviceSecure = secure)), "secure $secure")
            assertEquals("Present", name(androidProbeOutcome(fileExists = true, aliasPresent = true, privateHalfFailure = api30Locked(), deviceSecure = secure)), "a locked phone, secure $secure")
        }
    }

    @Test
    fun onlyAnInvalidationIsAnInvalidatedKey() {
        val invalidated = listOf(
            "KeyPermanentlyInvalidatedException" to KeyPermanentlyInvalidatedException(),
            "an invalidation as a cause" to InvalidKeyException("init", KeyPermanentlyInvalidatedException()),
            "API 30's getKey" to api30Invalidated(),
            "API 30's getKey as a cause" to IllegalStateException(api30Invalidated()),
        )
        val other = listOf(
            "API 30's getKey on a locked phone or any other Keystore error" to api30Locked(),
            "the invalidation message with a cause" to UnrecoverableKeyException(API30_MESSAGE).apply { initCause(KeyStoreException("4")) },
            "the message in other case" to UnrecoverableKeyException(API30_MESSAGE.lowercase()),
            "the message with a full stop" to UnrecoverableKeyException("$API30_MESSAGE."),
            "an UnrecoverableKeyException with no message" to UnrecoverableKeyException(),
            "the message on another type" to InvalidKeyException(API30_MESSAGE),
            "a locked phone at init (InvalidKeyException <- KeyStoreException)" to InvalidKeyException(KeyStoreException("2")),
            "ProviderException" to ProviderException(),
            "IllegalStateException" to IllegalStateException(),
            "NullPointerException" to NullPointerException(),
        )
        invalidated.forEach { (case, t) -> assertTrue(androidKeyInvalidated(t), case) }
        other.forEach { (case, t) -> assertFalse(androidKeyInvalidated(t), case) }
        // The read and the write decide by the same rule.
        invalidated.forEach { (case, t) ->
            assertEquals("KeyGone", name(androidInitOutcome(t)), case)
            assertTrue(androidKeyMustBeRemade(certificatePresent = true, privateHalfFailure = t), case)
        }
        other.forEach { (case, t) ->
            assertEquals("Unavailable(TryLater)", name(androidInitOutcome(t)), case)
            assertFalse(androidKeyMustBeRemade(certificatePresent = true, privateHalfFailure = t), case)
        }
    }

    @Test
    fun aReadStartsOnlyWithTheFileAndItsKey() {
        assertEquals("NotFound", name(androidReadRefusal(fileExists = false, aliasPresent = true)))
        assertEquals("NotFound", name(androidReadRefusal(fileExists = false, aliasPresent = false)))
        assertEquals("KeyGone", name(androidReadRefusal(fileExists = true, aliasPresent = false)))
        assertEquals("Unavailable(TryLater)", name(androidReadRefusal(fileExists = true, aliasPresent = null)))
        assertNull(androidReadRefusal(fileExists = true, aliasPresent = true))
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
            assertEquals("AlreadyExists", name(androidWriteRefusal(fileExists = true, deviceSecure = secure, mode = WriteMode.Create)), "secure $secure")
        }
    }

    @Test
    fun aWriteWithoutAScreenLockIsNoScreenLock() {
        assertEquals("Unavailable(NoScreenLock)", name(androidWriteRefusal(fileExists = false, deviceSecure = false, mode = WriteMode.Create)))
        listOf(true, false).forEach { fileExists ->
            assertEquals("Unavailable(NoScreenLock)", name(androidWriteRefusal(fileExists, deviceSecure = false, mode = WriteMode.Replace(ANY_VERSION))), "file $fileExists")
        }
    }

    @Test
    fun aWriteGoesOnWithAScreenLockUnlessACreateMeetsAFile() {
        assertNull(androidWriteRefusal(fileExists = false, deviceSecure = true, mode = WriteMode.Create))
        assertNull(androidWriteRefusal(fileExists = false, deviceSecure = true, mode = WriteMode.Replace(ANY_VERSION)))
        assertNull(androidWriteRefusal(fileExists = true, deviceSecure = true, mode = WriteMode.Replace(ANY_VERSION)))
    }

    // A pair the read calls KeyGone must not take another envelope: sealing to its public half writes a copy nothing opens.
    @Test
    fun aWriteMakesANewKeyOnlyWhenThePairIsGoneOrInvalidated() {
        assertTrue(androidKeyMustBeRemade(certificatePresent = false, privateHalfFailure = null), "no certificate")
        assertTrue(androidKeyMustBeRemade(certificatePresent = true, privateHalfFailure = KeyPermanentlyInvalidatedException()), "an invalidated key")
        assertFalse(androidKeyMustBeRemade(certificatePresent = true, privateHalfFailure = null), "a working key")
        // Measured while the device was locked: InvalidKeyException <- KeyStoreException(code=2). The write needs only the public half.
        listOf(InvalidKeyException(), ProviderException(), IllegalStateException(), NullPointerException()).forEach { t ->
            assertFalse(androidKeyMustBeRemade(certificatePresent = true, privateHalfFailure = t), t::class.simpleName)
        }
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
        assertEquals("Erased", name(androidEraseOutcome(fileLeft = false, ANY_VERSION)))
        assertEquals("Failed(Other)", name(androidEraseOutcome(fileLeft = true, ANY_VERSION)))
    }

    @Test
    fun anEnvelopeOpensToItsPayloadAndCarriesTheCopyItWasReadFrom() {
        listOf(ByteArray(173) { it.toByte() }, byteArrayOf(7), ByteArray(4096) { (it * 31).toByte() }).forEach { payload ->
            val opened = assertIs<ReadResult.Bytes>(openEnvelope(sealEnvelope(payload, phoneKey), unwrapWith(pair), noCode, LATER_VERSION))
            assertContentEquals(payload, opened.bytes, "payload of ${payload.size} bytes")
            assertEquals(LATER_VERSION, opened.version, "the version of a payload of ${payload.size} bytes")
        }
    }

    @Test
    fun twoSealsOfOnePayloadDiffer() {
        val payload = ByteArray(173) { it.toByte() }
        assertFalse(sealEnvelope(payload, phoneKey).contentEquals(sealEnvelope(payload, phoneKey)), "a fresh IV each time, under the one phone key")
    }

    @Test
    fun everyMintIsAFreshDataKeyWrappedForThePublicHalf() {
        val keys = List(2) { mintPhoneKey(pair.public) }
        assertEquals(32, keys[0].dataKey.size, "an AES-256 key")
        assertFalse(keys[0].dataKey.contentEquals(keys[1].dataKey), "two mints share a data key")
        assertContentEquals(keys[0].dataKey, unwrapWith(pair)(keys[0].wrapped), "the wrapped copy opens to the data key")
    }

    @Test
    fun envelopesOfOnePhoneKeyOpenWithOneUnwrap() {
        val payloads = List(3) { i -> ByteArray(173 + i) { it.toByte() } }
        val envelopes = payloads.map { sealEnvelope(it, phoneKey) }
        assertEquals(1, envelopes.map { wrappedKeyOf(it)!!.toList() }.toSet().size, "one wrapped key in every envelope")
        var unwraps = 0
        val (results, key) = openWithOneUnwrap(envelopes.map { it to LATER_VERSION }, { w -> unwraps++; unwrapWith(pair)(w) }, noCode)
        assertEquals(1, unwraps, "one use of the Keystore key, so one prompt")
        results.zip(payloads) { result, payload -> assertContentEquals(payload, assertIs<ReadResult.Bytes>(result).bytes, "payload of ${payload.size} bytes") }
        assertTrue(results.all { (it as ReadResult.Bytes).version == LATER_VERSION }, "each result carries its envelope's copy")
        assertContentEquals(phoneKey.dataKey, key, "the data key the caller keeps")
    }

    @Test
    fun aDamagedEnvelopeOfAGroupDoesNotBlockTheOthers() {
        val good = List(2) { sealEnvelope(ByteArray(173) { 7 }, phoneKey) }
        val damaged = sealEnvelope(ByteArray(173) { 7 }, phoneKey).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        var unwraps = 0
        val (results, key) = openWithOneUnwrap(listOf(good[0] to ANY_VERSION, damaged to ANY_VERSION, good[1] to ANY_VERSION), { w -> unwraps++; unwrapWith(pair)(w) }, noCode)
        assertEquals(1, unwraps, "one use of the Keystore key")
        assertIs<ReadResult.Bytes>(results[0], "the first envelope")
        assertEquals("Unreadable", name(results[1]))
        assertIs<ReadResult.Bytes>(results[2], "the envelope after the damaged one")
        assertContentEquals(phoneKey.dataKey, key)
    }

    @Test
    fun aFailedUnwrapIsTriedPerEnvelopeAndLeavesNoKey() {
        val envelopes = List(2) { sealEnvelope(ByteArray(173) { 7 }, phoneKey) }
        val needsAuth: (ByteArray) -> ByteArray = { throw IllegalBlockSizeException() }
        val (results, key) = openWithOneUnwrap(envelopes.map { it to ANY_VERSION }, needsAuth) { 2 }
        assertTrue(results.all { name(it) == "Unavailable(TryLater)" }, "the owner must be asked again, so nothing may read as damage")
        assertNull(key, "no key to adopt")
    }

    @Test
    fun anExistingEnvelopesOwnKeyIsAdoptedSoItIsNotRewritten() {
        // What an existing phone's first unlock does: the first envelope's key becomes the phone key, so its file needs no new write.
        val existing = sealEnvelope(ByteArray(173) { 3 }, mintPhoneKey(pair.public))
        val (results, key) = openWithOneUnwrap(listOf(existing to ANY_VERSION), unwrapWith(pair), noCode)
        assertIs<ReadResult.Bytes>(results.single())
        val adopted = PhoneKey(key!!, wrappedKeyOf(existing)!!)
        val added = sealEnvelope(ByteArray(50) { 9 }, adopted)
        assertContentEquals(wrappedKeyOf(existing), wrappedKeyOf(added), "a second wallet's envelope carries the first one's wrapped key")
        var unwraps = 0
        val (both, _) = openWithOneUnwrap(listOf(existing to ANY_VERSION, added to ANY_VERSION), { w -> unwraps++; unwrapWith(pair)(w) }, noCode)
        assertEquals(1, unwraps, "both envelopes open with one prompt")
        assertTrue(both.all { it is ReadResult.Bytes }, "the existing envelope and the added one")
    }

    @Test
    fun theWrappedKeyOfAnEnvelopeIsReadByItsLayoutOnly() {
        val sealed = sealEnvelope(ByteArray(173) { 7 }, phoneKey)
        assertContentEquals(phoneKey.wrapped, wrappedKeyOf(sealed), "the 256 bytes after the header and the length")
        assertNull(wrappedKeyOf(ByteArray(0)))
        assertNull(wrappedKeyOf(sealed.copyOf(sealed.size - 200)), "an envelope too short for its layout")
        assertNull(wrappedKeyOf(sealed.copyOf().also { it[0] = 0 }), "another header")
        assertNull(wrappedKeyOf(sealed.copyOf().also { it[6] = 1 }), "another wrapped length")
    }

    @Test
    fun eachPlaceHasItsOwnFileAndPlaceOneKeepsTodays() {
        assertEquals("wallet", walletFileName(Place.FIRST), "the file the first wallet is already in")
        assertEquals("wallet.2", walletFileName(Place.parse(2)!!))
        assertEquals("wallet.10", walletFileName(Place.parse(10)!!))
        assertEquals(Place.COUNT, Place.all.map(::walletFileName).toSet().size, "two places share a file")
    }

    @Test
    fun testnetStorageIsWhereItAlwaysWas() {
        assertEquals((1..10).map { if (it == 1) "wallet" else "wallet.$it" }, Place.all.map { walletPath(KeyNetwork.TEST, it) })
        assertEquals("com.mebit.app.wallet", walletAlias(KeyNetwork.TEST))
        assertEquals("vaults/book", vaultsPath(KeyNetwork.TEST))
        assertEquals("mebit_vaults_v1", vaultsAlias(KeyNetwork.TEST))
        assertEquals("pairing", pairingPath(KeyNetwork.TEST))
        assertEquals("mebit_pairing_v1", pairingAlias(KeyNetwork.TEST))
    }

    @Test
    fun mainnetStorageSitsUnderMainAndUnderAliasesOfItsOwn() {
        val testPaths = Place.all.map { walletPath(KeyNetwork.TEST, it) } + vaultsPath(KeyNetwork.TEST)
        val mainPaths = Place.all.map { walletPath(KeyNetwork.MAIN, it) } + vaultsPath(KeyNetwork.MAIN)
        assertEquals(Place.COUNT + 1, mainPaths.toSet().size)
        mainPaths.forEach { assertTrue(it.startsWith("main/"), it) }
        assertTrue(mainPaths.none { it in testPaths }, "a mainnet path equals a testnet one")
        assertEquals("main/wallet", walletPath(KeyNetwork.MAIN, Place.FIRST))
        assertEquals("main/vaults/book", vaultsPath(KeyNetwork.MAIN))
        assertNotEquals(walletAlias(KeyNetwork.TEST), walletAlias(KeyNetwork.MAIN))
        assertNotEquals(vaultsAlias(KeyNetwork.TEST), vaultsAlias(KeyNetwork.MAIN))
        assertEquals("com.mebit.app.wallet.main", walletAlias(KeyNetwork.MAIN))
        assertEquals("mebit_vaults_v1.main", vaultsAlias(KeyNetwork.MAIN))
        assertEquals("main/pairing", pairingPath(KeyNetwork.MAIN))
        assertNotEquals(pairingAlias(KeyNetwork.TEST), pairingAlias(KeyNetwork.MAIN))
        assertEquals("mebit_pairing_v1.main", pairingAlias(KeyNetwork.MAIN))
        assertTrue(!pairingPath(KeyNetwork.MAIN).startsWith(pairingPath(KeyNetwork.TEST) + "/"), "mainnet pairings under testnet's folder, which an older Forget empties")
    }

    @Test
    fun everyFlippedBitIsUnreadable() {
        val sealed = sealEnvelope(ByteArray(173) { it.toByte() }, phoneKey)
        // Every byte of the header, the length and the IV, and a byte every 7 through the wrapped key and the ciphertext and tag.
        val positions = (0 until 3 + 4 + 256 + 12 + 8).toSet() + (0 until sealed.size step 7) + (sealed.size - 1)
        positions.forEach { at ->
            val damaged = sealed.copyOf().also { it[at] = (it[at].toInt() xor 0x01).toByte() }
            assertEquals("Unreadable", name(openEnvelope(damaged, unwrapWith(pair), noCode, ANY_VERSION)), "bit flipped at $at of ${sealed.size}")
        }
    }

    @Test
    fun everyShortOrLongEnvelopeIsUnreadable() {
        val sealed = sealEnvelope(ByteArray(173) { it.toByte() }, phoneKey)
        (0 until sealed.size).forEach { size ->
            assertEquals("Unreadable", name(openEnvelope(sealed.copyOf(size), unwrapWith(pair), noCode, ANY_VERSION)), "cut to $size of ${sealed.size}")
        }
        assertEquals("Unreadable", name(openEnvelope(sealed + 0, unwrapWith(pair), noCode, ANY_VERSION)), "one byte added")
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
        assertEquals("Unreadable", name(openEnvelope(later, unwrapWith(pair), noCode, ANY_VERSION)))
    }

    @Test
    fun anEnvelopeForAnotherKeyIsUnreadable() {
        val sealed = sealEnvelope(ByteArray(173) { it.toByte() }, phoneKey)
        assertEquals("Unreadable", name(openEnvelope(sealed, unwrapWith(otherPair), noCode, ANY_VERSION)))
    }

    @Test
    fun anUnwrapThatNeedsTheOwnerAgainIsTryLaterNotUnreadable() {
        val sealed = sealEnvelope(ByteArray(173) { it.toByte() }, phoneKey)
        val needsAuth: (ByteArray) -> ByteArray = { throw IllegalBlockSizeException() }
        assertEquals("Unavailable(TryLater)", name(openEnvelope(sealed, needsAuth, { 2 }, ANY_VERSION)))
        assertEquals("Unreadable", name(openEnvelope(sealed, needsAuth, { 10 }, ANY_VERSION)))
    }

    private companion object {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val otherPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val phoneKey = mintPhoneKey(pair.public)
        val noCode: (Throwable) -> Int? = { null }

        fun unwrapWith(keys: java.security.KeyPair): (ByteArray) -> ByteArray = { wrapped -> unwrapCipher(keys.private).doFinal(wrapped) }
    }

    @Test
    fun aWriteCountsOnlyWhenTheFileHoldsTheNewBytes() {
        val sealed = byteArrayOf(1, 2, 3)
        assertTrue(androidWriteLanded(sealed) { byteArrayOf(1, 2, 3) }, "the same bytes read into a new array")
        assertFalse(androidWriteLanded(sealed) { byteArrayOf(9, 9) }, "the old copy, after a rename that failed")
        assertFalse(androidWriteLanded(sealed) { throw FileNotFoundException("wallet.2") }, "no file, after a first write whose rename failed")
    }
}

private const val API30_MESSAGE = "User changed or deleted their auth credentials"

/** What API 30's `AndroidKeyStoreSpi.engineGetKey` throws for an invalidated key: the invalidation's message and no cause. */
private fun api30Invalidated() = UnrecoverableKeyException(API30_MESSAGE)

/** What API 30's getKey throws for any other Keystore error, as on a locked phone: its own message and the Keystore's error as the cause. */
private fun api30Locked() = UnrecoverableKeyException("Failed to obtain information about key").apply { initCause(KeyStoreException("LOCKED")) }

/** A version after one advance, distinct from [ANY_VERSION]. */
private val LATER_VERSION = CopyCounter().advance()

/** A result as one comparable word, so a failure prints what came back. */
private fun name(result: Any?): String = when (result) {
    is StoreProbe.Unavailable -> "Unavailable(${result.why})"
    is ReadResult.Unavailable -> "Unavailable(${result.why})"
    is WriteResult.Unavailable -> "Unavailable(${result.why})"
    is EraseResult.Failed -> "Failed(${result.why})"
    null -> "null"
    else -> result.toString()
}
