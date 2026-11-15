package com.mebit.app

import com.mebit.app.data.ANY_VERSION
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
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
    fun eachPlaceHasItsOwnAccountsAndPlaceOneKeepsTodays() {
        assertEquals("wallet.a", Place.FIRST.account(Slot.A), "the account the real wallet is already under")
        assertEquals("wallet.b", Place.FIRST.account(Slot.B))
        assertEquals("wallet.2.a", Place.parse(2)!!.account(Slot.A))
        assertEquals("wallet.2.b", Place.parse(2)!!.account(Slot.B))
        assertEquals("wallet.10.b", Place.parse(10)!!.account(Slot.B))
        val every = Place.all.flatMap { p -> Slot.entries.map { p.account(it) } }
        assertEquals(every.size, every.toSet().size, "two places share an account")
    }

    @Test
    fun aCreateOnAnEmptyPlaceLeavesOneCopyInSlotA() {
        val place = FakeSlots()
        assertEquals(errSecSuccess, place.create())
        assertEquals(setOf(Slot.A), place.held, "the copy")
        assertEquals(listOf(Slot.B), place.deleted, "only the second add is undone")
    }

    @Test
    fun aCreateNeverLandsBesideACopyInEitherSlot() {
        // Measured on the iPhone, 10 Nov: only slot B holds a copy, so the second add answers duplicate and the first is undone.
        val onlyInB = FakeSlots(Slot.B)
        assertEquals(errSecDuplicateItem, onlyInB.create())
        assertEquals(setOf(Slot.B), onlyInB.held, "the copy in B must survive and A must be empty")
        assertEquals(listOf(Slot.A), onlyInB.deleted, "only the add this Create made")

        val inA = FakeSlots(Slot.A)
        assertEquals(errSecDuplicateItem, inA.create())
        assertEquals(setOf(Slot.A), inA.held, "the copy in A is never deleted")
        assertTrue(inA.deleted.isEmpty(), "a duplicate on the first add must touch nothing")

        assertEquals("AlreadyExists", name(writeRefusal(onlyInB.create(), WriteMode.Create, passcodeSet = true)))
    }

    @Test
    fun aCreateWhoseAddFailsUndoesWhatItAddedAndSaysWhy() {
        val firstFails = FakeSlots().apply { fail[Slot.A] = errSecInteractionNotAllowed }
        assertEquals(errSecInteractionNotAllowed, firstFails.create())
        assertTrue(firstFails.held.isEmpty() && firstFails.deleted.isEmpty(), "a failed first add must change nothing")

        val secondFails = FakeSlots().apply { fail[Slot.B] = errSecNotAvailable }
        assertEquals(errSecNotAvailable, secondFails.create())
        assertTrue(secondFails.held.isEmpty(), "the first add must be undone, leaving the place empty")
        assertEquals("Unavailable(Other)", name(writeRefusal(errSecNotAvailable, WriteMode.Create, passcodeSet = true)))
    }

    @Test
    fun aReplaceToAnEmptyPlaceGoesToSlotA() {
        assertEquals(Slot.A, slotToAdd(emptySet()))
    }

    @Test
    fun aReplaceGoesToTheFirstSlotWhoseAddWasNotADuplicate() {
        assertEquals(Slot.B, slotToAdd(setOf(Slot.A)))
        assertEquals(Slot.A, slotToAdd(setOf(Slot.B)))
    }

    @Test
    fun aReplaceWithBothSlotsTakenAddsNothing() {
        assertNull(slotToAdd(setOf(Slot.A, Slot.B)))
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
        assertEquals("Erased", name(eraseOutcome(errSecSuccess, ANY_VERSION)))
        assertEquals("Erased", name(eraseOutcome(errSecItemNotFound, ANY_VERSION)))
    }

    @Test
    fun anyOtherEraseStatusFails() {
        assertEquals("Failed(Other)", name(eraseOutcome(errSecInteractionNotAllowed, ANY_VERSION)))
        assertEquals("Failed(Other)", name(eraseOutcome(errSecMissingEntitlement, ANY_VERSION)))
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

/** One place's two slots for [createInBothSlots]: an add answers duplicate when the slot is held. [fail] scripts an add's status instead. */
private class FakeSlots(vararg start: Slot) {
    val held = start.toMutableSet()
    val deleted = mutableListOf<Slot>()
    val fail = mutableMapOf<Slot, Int>()

    fun create(): Int = createInBothSlots(::add, ::delete)

    private fun add(slot: Slot): Int = fail[slot] ?: if (held.add(slot)) errSecSuccess else errSecDuplicateItem

    private fun delete(slot: Slot) {
        deleted += slot
        held -= slot
    }
}

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
