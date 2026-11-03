package com.mebit.app

import com.mebit.app.data.ANY_VERSION
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.fail
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
 * The Keychain store's decisions, fed plain statuses, owner-check error codes, account names, slots and creation dates. The test
 * executable cannot reach the Keychain (every call answers errSecMissingEntitlement, measured), so the calls themselves are proven
 * on a simulator instead.
 */
class KeychainMappingTest {
    @Test
    fun anyListedItemIsPresentWhateverTheStatusThePasscodeOrTheLock() {
        listOf(errSecSuccess, errSecItemNotFound, errSecInteractionNotAllowed, errSecNotAvailable, errSecParam).forEach { status ->
            everyPhone { passcodeSet, unlocked ->
                assertEquals("Present", name(probeOutcome(status, listOf("wallet.a"), passcodeSet, unlocked)), "status $status passcode $passcodeSet unlocked $unlocked")
            }
        }
        assertEquals("Present", name(probeOutcome(errSecSuccess, listOf("wallet.a", "wallet.b"), passcodeSet = true, protectedDataAvailable = true)))
    }

    @Test
    fun nothingFoundIsEmptyOnAPhoneWithAPasscode() {
        listOf(errSecItemNotFound, errSecSuccess).forEach { status ->
            listOf(true, false).forEach { unlocked ->
                assertEquals("Empty", name(probeOutcome(status, emptyList(), passcodeSet = true, protectedDataAvailable = unlocked)), "status $status unlocked $unlocked")
            }
        }
    }

    @Test
    fun nothingFoundOnAPhoneWithoutAPasscodeCannotHoldAWallet() {
        listOf(errSecItemNotFound, errSecSuccess).forEach { status ->
            listOf(true, false).forEach { unlocked ->
                assertEquals(
                    "Unavailable(NoScreenLock)",
                    name(probeOutcome(status, emptyList(), passcodeSet = false, protectedDataAvailable = unlocked)),
                    "status $status unlocked $unlocked",
                )
            }
        }
    }

    @Test
    fun aRefusedListOnAnUnlockedPhoneIsAWalletThatNeedsItsOwner() {
        // What an iPhone answered on 2 Nov 2026 for the silent list while its wallet was there and the phone was unlocked.
        listOf(true, false).forEach { passcodeSet ->
            assertEquals("Present", name(probeOutcome(errSecInteractionNotAllowed, emptyList(), passcodeSet, protectedDataAvailable = true)), "passcode $passcodeSet")
        }
    }

    @Test
    fun aRefusedListOnALockedPhoneIsTryLater() {
        listOf(true, false).forEach { passcodeSet ->
            assertEquals(
                "Unavailable(TryLater)",
                name(probeOutcome(errSecInteractionNotAllowed, emptyList(), passcodeSet, protectedDataAvailable = false)),
                "passcode $passcodeSet",
            )
        }
    }

    @Test
    fun anUnavailableKeychainIsTryLater() {
        everyPhone { passcodeSet, unlocked ->
            assertEquals("Unavailable(TryLater)", name(probeOutcome(errSecNotAvailable, emptyList(), passcodeSet, unlocked)), "passcode $passcodeSet unlocked $unlocked")
        }
    }

    @Test
    fun anyOtherProbeStatusIsOther() {
        listOf(errSecMissingEntitlement, errSecParam, errSecAuthFailed).forEach { status ->
            everyPhone { passcodeSet, unlocked ->
                assertEquals("Unavailable(Other)", name(probeOutcome(status, emptyList(), passcodeSet, unlocked)), "status $status passcode $passcodeSet unlocked $unlocked")
            }
        }
    }

    @Test
    fun aSuccessfulReadHandsOverTheBytesAndTheCopyTheyCameFrom() {
        val result = assertIs<ReadResult.Bytes>(readOutcome(errSecSuccess, byteArrayOf(1, 2, 3), LATER_VERSION))
        assertContentEquals(byteArrayOf(1, 2, 3), result.bytes)
        assertEquals(LATER_VERSION, result.version, "the copy the bytes came from")
    }

    @Test
    fun aReadThatFindsNothingIsNotFound() {
        assertEquals("NotFound", name(readOutcome(errSecItemNotFound, null, ANY_VERSION)))
        assertEquals("NotFound", name(readOutcome(errSecSuccess, null, ANY_VERSION)))
    }

    @Test
    fun aCancelledSheetIsCancelled() {
        assertEquals("Cancelled", name(readOutcome(errSecUserCanceled, null, ANY_VERSION)))
    }

    @Test
    fun aFailedOwnerCheckIsDenied() {
        assertEquals("Denied", name(readOutcome(errSecAuthFailed, null, ANY_VERSION)))
    }

    @Test
    fun aReadWhileLockedIsTryLater() {
        assertEquals("Unavailable(TryLater)", name(readOutcome(errSecInteractionNotAllowed, null, ANY_VERSION)))
    }

    @Test
    fun anyOtherReadStatusIsOther() {
        listOf(errSecMissingEntitlement, errSecNotAvailable, errSecParam, errSecDuplicateItem).forEach { status ->
            assertEquals("Unavailable(Other)", name(readOutcome(status, null, ANY_VERSION)), "status $status")
        }
    }

    @Test
    fun aSlotWithBytesIsTheWalletWhateverTheOtherSlotAnswers() {
        val copy = ReadResult.Bytes(byteArrayOf(1, 2, 3), ANY_VERSION)
        listOf(ReadResult.NotFound, ReadResult.Cancelled, ReadResult.Denied, ReadResult.Unavailable(Why.TryLater)).forEach { other ->
            assertSame(copy, slotsOutcome(mapOf(Slot.A to copy, Slot.B to other), ::noDateRead), "the copy in A, B answers ${name(other)}")
            assertSame(copy, slotsOutcome(mapOf(Slot.A to other, Slot.B to copy), ::noDateRead), "the copy in B, A answers ${name(other)}")
        }
    }

    @Test
    fun twoCopiesGoToTheNewestAndOnlyTheyReadTheDates() {
        val inA = ReadResult.Bytes(byteArrayOf(1), ANY_VERSION)
        val inB = ReadResult.Bytes(byteArrayOf(2), LATER_VERSION)
        Slot.entries.forEach { newest ->
            var datesRead = 0
            val result = slotsOutcome(mapOf(Slot.A to inA, Slot.B to inB)) {
                datesRead++
                newest
            }
            assertSame(if (newest == Slot.A) inA else inB, result, "newest $newest")
            assertEquals(if (newest == Slot.A) ANY_VERSION else LATER_VERSION, assertIs<ReadResult.Bytes>(result).version, "the version of $newest")
            assertEquals(1, datesRead, "newest $newest")
        }
    }

    @Test
    fun noCopyInEitherSlotIsNotFound() {
        assertEquals("NotFound", name(slotsOutcome(mapOf(Slot.A to ReadResult.NotFound, Slot.B to ReadResult.NotFound), ::noDateRead)))
    }

    @Test
    fun aRefusalIsNeverTakenForAMissingWallet() {
        assertEquals("Cancelled", name(slotsOutcome(mapOf(Slot.A to ReadResult.NotFound, Slot.B to ReadResult.Cancelled), ::noDateRead)))
        assertEquals("Denied", name(slotsOutcome(mapOf(Slot.A to ReadResult.Denied, Slot.B to ReadResult.NotFound), ::noDateRead)))
        assertEquals("Unavailable(Other)", name(slotsOutcome(mapOf(Slot.A to ReadResult.NotFound, Slot.B to ReadResult.Unavailable(Why.Other)), ::noDateRead)))
        // Two refusals: the first slot's answer.
        assertEquals(
            "Unavailable(TryLater)",
            name(slotsOutcome(mapOf(Slot.A to ReadResult.Unavailable(Why.TryLater), Slot.B to ReadResult.Cancelled), ::noDateRead)),
        )
    }

    @Test
    fun theNewestCopyIsTheOneAddedLast() {
        assertEquals(Slot.B, pickNewest(mapOf(Slot.A to 100.0, Slot.B to 100.5)))
        assertEquals(Slot.A, pickNewest(mapOf(Slot.A to 813_000_000.25, Slot.B to 812_999_999.0)))
    }

    @Test
    fun aSingleCopyIsTheNewest() {
        assertEquals(Slot.B, pickNewest(mapOf(Slot.B to 5.0)))
        assertEquals(Slot.A, pickNewest(mapOf(Slot.A to null)))
    }

    @Test
    fun aDateThatCouldNotBeReadLosesToAnyThatCould() {
        assertEquals(Slot.B, pickNewest(mapOf(Slot.A to null, Slot.B to 1.0)))
        // A date before 2001, the reference date, is still a date.
        assertEquals(Slot.A, pickNewest(mapOf(Slot.A to -60.0, Slot.B to null)))
    }

    @Test
    fun aTieGoesToSlotA() {
        assertEquals(Slot.A, pickNewest(mapOf(Slot.A to 42.0, Slot.B to 42.0)))
        assertEquals(Slot.A, pickNewest(mapOf(Slot.A to null, Slot.B to null)))
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
    fun theWalletIsKeptInTheAccountsWalletAAndWalletB() {
        // The stored accounts: renaming one would hide every wallet already saved under it.
        assertEquals(listOf("wallet.a", "wallet.b"), Slot.entries.map { it.account })
    }

    @Test
    fun aCreateGoesOnToItsAddOnlyWhenTheStoreIsEmpty() {
        assertNull(createRefusal(StoreProbe.Empty))
        assertNull(createRefusal(probeOutcome(errSecItemNotFound, emptyList(), passcodeSet = true, protectedDataAvailable = true)))
    }

    @Test
    fun aCreateNeverAddsBesideAWalletItSeesOrOnlySenses() {
        assertEquals("AlreadyExists", name(createRefusal(probeOutcome(errSecSuccess, listOf("wallet.b"), passcodeSet = true, protectedDataAvailable = true))))
        // The iPhone's answer while a wallet is there and the phone is unlocked: the list is refused and names no item.
        assertEquals("AlreadyExists", name(createRefusal(probeOutcome(errSecInteractionNotAllowed, emptyList(), passcodeSet = true, protectedDataAvailable = true))))
        assertEquals("AlreadyExists", name(createRefusal(StoreProbe.KeyGone)))
    }

    @Test
    fun aCreateOnALockedPhoneIsTryLater() {
        assertEquals(
            "Unavailable(TryLater)",
            name(createRefusal(probeOutcome(errSecInteractionNotAllowed, emptyList(), passcodeSet = true, protectedDataAvailable = false))),
        )
    }

    @Test
    fun aCreateThatCannotCheckTheStoreSaysWhy() {
        Why.entries.forEach { why -> assertEquals("Unavailable($why)", name(createRefusal(StoreProbe.Unavailable(why))), "$why") }
    }

    @Test
    fun aWriteToAnEmptyStoreGoesToSlotA() {
        MODES.forEach { mode -> assertEquals(Slot.A, slotToAdd(mode, emptySet()), "$mode") }
    }

    @Test
    fun aReplaceGoesToTheFirstSlotWhoseAddWasNotADuplicate() {
        assertEquals(Slot.B, slotToAdd(WriteMode.Replace(ANY_VERSION),setOf(Slot.A)))
        assertEquals(Slot.A, slotToAdd(WriteMode.Replace(ANY_VERSION),setOf(Slot.B)))
    }

    @Test
    fun aReplaceWithBothSlotsTakenAddsNothing() {
        assertNull(slotToAdd(WriteMode.Replace(ANY_VERSION),setOf(Slot.A, Slot.B)))
    }

    @Test
    fun aCreateNeverAddsBesideAWallet() {
        listOf(setOf(Slot.A), setOf(Slot.B), setOf(Slot.A, Slot.B)).forEach { taken -> assertNull(slotToAdd(WriteMode.Create, taken), "taken $taken") }
    }

    @Test
    fun aWriteDeletesTheOtherSlotNeverTheOneItAdded() {
        assertEquals(Slot.B, Slot.A.other)
        assertEquals(Slot.A, Slot.B.other)
    }

    @Test
    fun anAddThatSucceedsIsNotRefusedUnderEitherKind() {
        MODES.forEach { mode -> assertNull(writeRefusal(errSecSuccess, mode, passcodeSet = true), "$mode") }
    }

    @Test
    fun aDuplicateOnCreateIsAlreadyExists() {
        assertEquals("AlreadyExists", name(writeRefusal(errSecDuplicateItem, WriteMode.Create, passcodeSet = true)))
    }

    @Test
    fun aDuplicateOnReplaceIsAFailureNotAnExistingWallet() {
        // A Replace reaches this only when both slots answered duplicate.
        assertEquals("Unavailable(Other)", name(writeRefusal(errSecDuplicateItem, WriteMode.Replace(ANY_VERSION), passcodeSet = true)))
    }

    @Test
    fun aFailedWriteOnAPhoneWithoutAPasscodeIsNoScreenLock() {
        listOf(errSecParam, errSecNotAvailable, errSecInteractionNotAllowed).forEach { status ->
            MODES.forEach { mode ->
                assertEquals("Unavailable(NoScreenLock)", name(writeRefusal(status, mode, passcodeSet = false)), "status $status $mode")
            }
        }
    }

    @Test
    fun anyOtherWriteFailureIsOther() {
        listOf(errSecInteractionNotAllowed, errSecNotAvailable, errSecMissingEntitlement).forEach { status ->
            MODES.forEach { mode ->
                assertEquals("Unavailable(Other)", name(writeRefusal(status, mode, passcodeSet = true)), "status $status $mode")
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

/** Both write kinds. A Replace names a version, so the sealed WriteMode has no entries list. */
private val MODES = listOf(WriteMode.Create, WriteMode.Replace(ANY_VERSION))

/** A version after one advance, distinct from [ANY_VERSION]. */
private val LATER_VERSION = CopyCounter().advance()

/** Runs [check] for every phone: with and without a passcode, unlocked and locked. */
private fun everyPhone(check: (passcodeSet: Boolean, unlocked: Boolean) -> Unit) =
    listOf(true, false).forEach { passcodeSet -> listOf(true, false).forEach { unlocked -> check(passcodeSet, unlocked) } }

/** The creation dates are read only when both slots hold a copy, so any other read that asks for them fails. */
private fun noDateRead(): Slot = fail("the dates were read although at most one slot holds a copy")

/** A result's name for an assertion. The plain result classes would otherwise print an object id instead of the reason. */
private fun name(result: Any?): String = when (result) {
    is StoreProbe.Unavailable -> "Unavailable(${result.why})"
    is ReadResult.Unavailable -> "Unavailable(${result.why})"
    is WriteResult.Unavailable -> "Unavailable(${result.why})"
    is EraseResult.Failed -> "Failed(${result.why})"
    is ReadResult.Bytes -> "Bytes"
    else -> result.toString()
}
