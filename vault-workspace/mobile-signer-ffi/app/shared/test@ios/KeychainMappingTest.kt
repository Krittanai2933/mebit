package com.mebit.app

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import platform.LocalAuthentication.LAErrorAppCancel
import platform.LocalAuthentication.LAErrorAuthenticationFailed
import platform.LocalAuthentication.LAErrorBiometryLockout
import platform.LocalAuthentication.LAErrorBiometryNotAvailable
import platform.LocalAuthentication.LAErrorBiometryNotEnrolled
import platform.LocalAuthentication.LAErrorInvalidContext
import platform.LocalAuthentication.LAErrorNotInteractive
import platform.LocalAuthentication.LAErrorPasscodeNotSet
import platform.LocalAuthentication.LAErrorSystemCancel
import platform.LocalAuthentication.LAErrorUserCancel
import platform.LocalAuthentication.LAErrorUserFallback
import platform.Security.errSecAuthFailed
import platform.Security.errSecDuplicateItem
import platform.Security.errSecInteractionNotAllowed
import platform.Security.errSecItemNotFound
import platform.Security.errSecMissingEntitlement
import platform.Security.errSecNotAvailable
import platform.Security.errSecParam
import platform.Security.errSecSuccess
import platform.Security.errSecUserCanceled

/**
 * The Keychain store's decisions, fed plain statuses, owner-check error codes and account names. The test executable cannot reach the Keychain
 * (every call answers errSecMissingEntitlement, measured), so the calls themselves are proven on a simulator instead.
 */
class KeychainMappingTest {
    @Test
    fun anyItemIsPresentWhateverThePasscodeCheckSays() {
        assertEquals("Present", name(probeOutcome(errSecSuccess, listOf("wallet.1"), passcodeSet = true)))
        assertEquals("Present", name(probeOutcome(errSecSuccess, listOf("wallet.7", "wallet.8"), passcodeSet = false)))
    }

    @Test
    fun noItemIsEmptyOnAPhoneWithAPasscode() {
        assertEquals("Empty", name(probeOutcome(errSecItemNotFound, emptyList(), passcodeSet = true)))
    }

    @Test
    fun noItemOnAPhoneWithoutAPasscodeCannotHoldAWallet() {
        assertEquals("Unavailable(NoScreenLock)", name(probeOutcome(errSecItemNotFound, emptyList(), passcodeSet = false)))
    }

    @Test
    fun aLockedOrUnavailableKeychainIsTryLater() {
        assertEquals("Unavailable(TryLater)", name(probeOutcome(errSecInteractionNotAllowed, emptyList(), passcodeSet = true)))
        assertEquals("Unavailable(TryLater)", name(probeOutcome(errSecNotAvailable, emptyList(), passcodeSet = true)))
        assertEquals("Unavailable(TryLater)", name(probeOutcome(errSecInteractionNotAllowed, emptyList(), passcodeSet = false)))
    }

    @Test
    fun anyOtherProbeStatusIsOther() {
        assertEquals("Unavailable(Other)", name(probeOutcome(errSecMissingEntitlement, emptyList(), passcodeSet = true)))
        assertEquals("Unavailable(Other)", name(probeOutcome(errSecParam, emptyList(), passcodeSet = true)))
    }

    @Test
    fun aSuccessfulReadHandsOverTheBytes() {
        val result = readOutcome(errSecSuccess, byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), assertIs<ReadResult.Bytes>(result).bytes)
    }

    @Test
    fun aReadThatFindsNothingIsNotFound() {
        assertEquals("NotFound", name(readOutcome(errSecItemNotFound, null)))
        assertEquals("NotFound", name(readOutcome(errSecSuccess, null)))
    }

    @Test
    fun aCancelledSheetIsCancelled() {
        assertEquals("Cancelled", name(readOutcome(errSecUserCanceled, null)))
    }

    @Test
    fun aFailedOwnerCheckIsDenied() {
        assertEquals("Denied", name(readOutcome(errSecAuthFailed, null)))
    }

    @Test
    fun aReadWhileLockedIsTryLater() {
        assertEquals("Unavailable(TryLater)", name(readOutcome(errSecInteractionNotAllowed, null)))
    }

    @Test
    fun anyOtherReadStatusIsOther() {
        listOf(errSecMissingEntitlement, errSecNotAvailable, errSecParam, errSecDuplicateItem).forEach { status ->
            assertEquals("Unavailable(Other)", name(readOutcome(status, null)), "status $status")
        }
    }

    @Test
    fun aPassedOwnerCheckGoesOnToTheKeychainRead() {
        assertNull(evaluateOutcome(null))
    }

    @Test
    fun anOwnerCheckCancelledByTheUserTheSystemOrTheAppIsCancelled() {
        listOf(LAErrorUserCancel, LAErrorSystemCancel, LAErrorAppCancel).forEach { code ->
            assertEquals("Cancelled", name(evaluateOutcome(code)), "code $code")
        }
    }

    @Test
    fun anOwnerCheckThatRejectsTheFaceOrCodeIsDenied() {
        assertEquals("Denied", name(evaluateOutcome(LAErrorAuthenticationFailed)))
    }

    @Test
    fun aFaceIdLockoutIsLockedOut() {
        assertEquals("LockedOut", name(evaluateOutcome(LAErrorBiometryLockout)))
    }

    @Test
    fun anOwnerCheckOnAPhoneWithoutAPasscodeIsNoScreenLock() {
        assertEquals("Unavailable(NoScreenLock)", name(evaluateOutcome(LAErrorPasscodeNotSet)))
    }

    @Test
    fun anyOtherOwnerCheckErrorIsOtherAndNeverGoesOnToTheRead() {
        listOf(LAErrorUserFallback, LAErrorBiometryNotAvailable, LAErrorBiometryNotEnrolled, LAErrorInvalidContext, LAErrorNotInteractive, 0L).forEach { code ->
            assertEquals("Unavailable(Other)", name(evaluateOutcome(code)), "code $code")
        }
    }

    @Test
    fun anAddThatSucceedsIsWritten() {
        WriteMode.entries.forEach { mode -> assertEquals("Written", name(writeOutcome(errSecSuccess, mode, passcodeSet = true)), "$mode") }
    }

    @Test
    fun aDuplicateOnCreateIsAlreadyExists() {
        assertEquals("AlreadyExists", name(writeOutcome(errSecDuplicateItem, WriteMode.Create, passcodeSet = true)))
    }

    @Test
    fun aDuplicateOnReplaceIsAFailureNotAnExistingWallet() {
        assertEquals("Unavailable(Other)", name(writeOutcome(errSecDuplicateItem, WriteMode.Replace, passcodeSet = true)))
    }

    @Test
    fun aFailedWriteOnAPhoneWithoutAPasscodeIsNoScreenLock() {
        listOf(errSecParam, errSecNotAvailable, errSecInteractionNotAllowed).forEach { status ->
            WriteMode.entries.forEach { mode ->
                assertEquals("Unavailable(NoScreenLock)", name(writeOutcome(status, mode, passcodeSet = false)), "status $status $mode")
            }
        }
    }

    @Test
    fun anyOtherWriteFailureIsOther() {
        listOf(errSecInteractionNotAllowed, errSecNotAvailable, errSecMissingEntitlement).forEach { status ->
            WriteMode.entries.forEach { mode ->
                assertEquals("Unavailable(Other)", name(writeOutcome(status, mode, passcodeSet = true)), "status $status $mode")
            }
        }
    }

    @Test
    fun eraseIsDoneWhenItDeletedOrFoundNothing() {
        assertEquals("Erased", name(eraseOutcome(errSecSuccess)))
        assertEquals("Erased", name(eraseOutcome(errSecItemNotFound)))
    }

    @Test
    fun anyOtherEraseStatusFails() {
        assertEquals("Failed(Other)", name(eraseOutcome(errSecInteractionNotAllowed)))
        assertEquals("Failed(Other)", name(eraseOutcome(errSecMissingEntitlement)))
    }

    @Test
    fun theNewestGenerationIsTheHighestNumber() {
        assertEquals(3, newestGeneration(listOf("wallet.2", "wallet.3", "wallet.1")))
        assertEquals(12, newestGeneration(listOf("wallet.9", "wallet.12")))
    }

    @Test
    fun onlyCanonicalWalletAccountsAreGenerations() {
        assertNull(newestGeneration(emptyList()))
        val notGenerations = listOf("", "other", "wallet.", "wallet.x", "wallet.0", "wallet.-1", "wallet.01", "wallet.+2", "Wallet.3", "wallet.3 ", "wallet.99999999999")
        assertNull(newestGeneration(notGenerations))
        assertEquals(2, newestGeneration(notGenerations + "wallet.2"))
    }

    @Test
    fun aCreateOnAServiceWithNoWalletAddsGenerationOne() {
        assertEquals(1, generationToAdd(emptyList(), WriteMode.Create))
        assertEquals(1, generationToAdd(listOf("other"), WriteMode.Create))
    }

    @Test
    fun aCreateNeverAddsBesideAWallet() {
        assertNull(generationToAdd(listOf("wallet.1"), WriteMode.Create))
        assertNull(generationToAdd(listOf("wallet.4", "wallet.5"), WriteMode.Create))
        assertNull(generationToAdd(listOf("wallet.x"), WriteMode.Create))
    }

    @Test
    fun aReplaceAddsTheGenerationAfterTheNewest() {
        assertEquals(2, generationToAdd(listOf("wallet.1"), WriteMode.Replace))
        assertEquals(6, generationToAdd(listOf("wallet.5", "wallet.4"), WriteMode.Replace))
        assertEquals(1, generationToAdd(emptyList(), WriteMode.Replace))
    }

    @Test
    fun aWriteThenDeletesEveryOlderGenerationAndNothingElse() {
        assertEquals(listOf("wallet.1"), accountsToDelete(listOf("wallet.1"), added = 2))
        // Two older generations: what a crash between an earlier add and its deletes leaves behind.
        assertEquals(listOf("wallet.4", "wallet.5"), accountsToDelete(listOf("wallet.4", "wallet.5", "other", "wallet.x"), added = 6))
        assertEquals(emptyList(), accountsToDelete(emptyList(), added = 1))
    }

    @Test
    fun theGenerationJustAddedIsNeverDeleted() {
        assertEquals(listOf("wallet.1", "wallet.2"), accountsToDelete(listOf("wallet.1", "wallet.2", "wallet.3"), added = 3))
    }

    @Test
    fun theMarkerFileSaysEmptyOrKept() {
        assertEquals(Marker.Empty, markerOf("empty"))
        assertEquals(Marker.Kept, markerOf("kept"))
    }

    @Test
    fun anyOtherMarkerContentIsUnreadableNeverAbsent() {
        listOf(null, "", "Kept", "kept\n", " kept", "absent", "unsupported", "unreadable", "emptykept").forEach { content ->
            assertEquals(Marker.Unreadable, markerOf(content), "content [$content]")
        }
    }

    @Test
    fun onlyEmptyAndKeptCanBeWritten() {
        assertEquals("empty", markerContent(Marker.Empty))
        assertEquals("kept", markerContent(Marker.Kept))
        listOf(Marker.Absent, Marker.Unsupported, Marker.Unreadable).forEach { assertNull(markerContent(it), "$it") }
    }
}

/** A result's name for an assertion. The plain result classes would otherwise print an object id instead of the reason. */
private fun name(result: Any?): String = when (result) {
    is StoreProbe.Unavailable -> "Unavailable(${result.why})"
    is ReadResult.Unavailable -> "Unavailable(${result.why})"
    is WriteResult.Unavailable -> "Unavailable(${result.why})"
    is EraseResult.Failed -> "Failed(${result.why})"
    is ReadResult.Bytes -> "Bytes"
    else -> result.toString()
}
