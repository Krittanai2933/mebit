package com.mebit.app.data

import com.mebit.app.BiometricResult
import com.mebit.app.Biometrics
import com.mebit.app.Why
import com.mebit.app.WriteResult
import com.mebit.ffi.signer.SoftwareAccountKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * The gates of the session Spending PIN (open item 79, option D), driven as the nav host drives them: [AppLockState.confirm] before
 * signing, the 60-second lock, the words once onboarding is over, and a PIN change. [Phone] is the phone's own owner check, whose
 * [Phone.lock] says whether the phone has a passcode or a secure screen lock right now.
 */
class OwnerCheckTest {
    private class Phone(var lock: Boolean, private val answer: BiometricResult = BiometricResult.Success) : Biometrics {
        var asked = 0
        override fun available() = lock
        override suspend fun authenticate(reason: String, allowPasscode: Boolean): BiometricResult {
            asked++
            return answer
        }
    }

    private val clock = TestTimeSource()
    private val appLock = AppLockState(clock)

    @Test
    fun aPhoneWithItsOwnLockSignsAsBeforeAndNeverSeesThePin() = runBlocking {
        val phone = Phone(lock = true)
        val owner = OwnerCheck(phone, sessionWithKey(SpendingPin(clock)))
        assertTrue(appLock.confirm(owner, enabled = false, reason = "sign"), "App Lock off signs with no check, as before")
        assertEquals(0, phone.asked)
        assertTrue(appLock.confirm(owner, enabled = true, reason = "sign"))
        assertEquals(1, phone.asked, "App Lock on asks the phone's own check")
        assertFalse(appLock.confirm(OwnerCheck(Phone(lock = true, BiometricResult.Cancelled), sessionWithKey(SpendingPin(clock))), enabled = true, reason = "sign"))
        assertNull(owner.asking, "no PIN sheet on a phone with a lock")
        assertFalse(owner.usesPin)
        assertFalse(owner.needsPin, "onboarding goes to App Lock, not to the PIN")
    }

    @Test
    fun aPhoneWithNoOwnerCheckSignsOnlyPastThePin() = runBlocking {
        val phone = Phone(lock = false)
        val session = sessionWithKey(SpendingPin(clock))
        assertTrue(session.pin.set("270419"))
        val owner = OwnerCheck(phone, session)
        val signed = gate(owner) { appLock.confirm(owner, enabled = false, reason = "ยืนยันการเซ็นส่งเงิน") }
        val sheet = assertNotNull(owner.asking, "the PIN sheet before signing, with App Lock off")
        assertEquals("ยืนยันการเซ็นส่งเงิน", sheet.title)
        assertEquals(PinStep.Check, sheet.entry.step)
        assertTrue(sheet.cancellable)
        sheet.enter("583920")
        assertFalse(signed.isCompleted, "a wrong PIN signs nothing and keeps the sheet")
        assertEquals(PinProblem.Wrong, sheet.entry.problem)
        sheet.enter("270419")
        assertTrue(signed.answer())
        assertNull(owner.asking, "the sheet closes once the PIN matched")
        assertEquals(0, phone.asked, "the phone has no check to ask")
    }

    @Test
    fun aCancelledPinSheetSignsNothing() = runBlocking {
        val session = sessionWithKey(SpendingPin(clock))
        assertTrue(session.pin.set("270419"))
        val owner = OwnerCheck(Phone(lock = false), session)
        val signed = gate(owner) { appLock.confirm(owner, enabled = true, reason = "sign") }
        assertNotNull(owner.asking).cancel()
        assertFalse(signed.answer())
        assertNull(owner.asking)
    }

    @Test
    fun aSavedWalletWhosePhoneLockWasRemovedIsAskedThePin() = runBlocking {
        val phone = Phone(lock = true)
        val session = sessionWithKey(SpendingPin(clock), SavedWallet(FakeSecureStore()))
        assertEquals(SaveState.Saved, session.saved, "saved while the phone had its lock")
        val owner = OwnerCheck(phone, session)
        assertFalse(owner.usesPin)
        phone.lock = false
        assertTrue(owner.usesPin, "decided from the phone now, not from the saved state")
        assertTrue(owner.needsPin)
        val signed = gate(owner) { appLock.confirm(owner, enabled = false, reason = "sign") }
        val sheet = assertNotNull(owner.asking, "a saved wallet with App Lock off must not sign with no check once the lock is gone")
        assertEquals(PinStep.New, sheet.entry.step, "no PIN yet, so one is set before the first signing")
        sheet.enter("270419")
        assertEquals(PinStep.Repeat, sheet.entry.step)
        sheet.enter("270419")
        assertTrue(signed.answer())
        assertTrue(session.pin.check("270419"))
        assertEquals(0, phone.asked)
    }

    @Test
    fun aPhoneThatLostItsLockWithAppLockOnSetsAPinInsteadOfFailingForever() = runBlocking {
        val phone = Phone(lock = true)
        val session = sessionWithKey(SpendingPin(clock))
        val owner = OwnerCheck(phone, session)
        phone.lock = false
        val signed = gate(owner) { appLock.confirm(owner, enabled = true, reason = "sign") }
        val sheet = assertNotNull(owner.asking)
        assertEquals(PinStep.New, sheet.entry.step)
        sheet.enter("123456")
        assertEquals(PinProblem.Common, sheet.entry.problem, "a common PIN is refused")
        assertEquals(PinStep.New, sheet.entry.step)
        sheet.enter("270419")
        sheet.enter("270418")
        assertEquals(PinProblem.Mismatch, sheet.entry.problem, "two different entries set nothing")
        assertEquals(PinStep.New, sheet.entry.step)
        assertFalse(session.pin.isSet)
        sheet.enter("270419")
        sheet.enter("270419")
        assertTrue(signed.answer())
        assertEquals(0, phone.asked)
    }

    @Test
    fun aPhoneThatCouldNotSaveItsKeySignsPastItsPinNowThatItKeepsItsGuard() = runBlocking {
        // Before N2 the screen-lock notice kept these phones from Review. Now the PIN is their owner check, so they sign past it.
        for (why in listOf(Why.NoScreenLock, Why.OsTooOld)) {
            val guards = FakePlainFile()
            val session = sessionWithKey(SpendingPin(clock), SavedWallet(FakeSecureStore(write = WriteResult.Unavailable(why))), guards)
            assertEquals(SaveState.NotSaved, session.saved, "$why")
            assertTrue(session.pin.set("270419"))
            val owner = OwnerCheck(Phone(lock = false), session)
            val signed = gate(owner) { appLock.confirm(owner, enabled = false, reason = "sign") }
            assertNotNull(owner.asking, "$why").enter("270419")
            assertTrue(signed.answer(), "$why")
            assertEquals(Claim.Written, session.recordUnconfirmedSend(TEST_TXID, 1), "the guard this phone keeps, $why")
        }
    }

    @Test
    fun onceSetThePinStaysTheCheckWhenTheUserAddsALock() = runBlocking {
        val phone = Phone(lock = false)
        val session = sessionWithKey(SpendingPin(clock))
        assertTrue(session.pin.set("270419"))
        val owner = OwnerCheck(phone, session)
        phone.lock = true
        val signed = gate(owner) { appLock.confirm(owner, enabled = false, reason = "sign") }
        assertNotNull(owner.asking).enter("270419")
        assertTrue(signed.answer())
        assertEquals(0, phone.asked)
    }

    @Test
    fun theSixtySecondLockCoversAPhoneThePinGuards() = runBlocking {
        val session = sessionWithKey(SpendingPin(clock))
        val owner = OwnerCheck(Phone(lock = false), session)
        assertFalse(owner.locksWhenAway, "no PIN yet, as during onboarding, so there is nothing to ask for")
        assertTrue(session.pin.set("270419"))
        assertTrue(owner.locksWhenAway, "App Lock off, yet the PIN locks after a minute away")
        appLock.onBackground()
        clock += 61.seconds
        appLock.onForeground(owner.locksWhenAway)
        assertTrue(appLock.locked)
        val opened = gate(owner) { owner.unlock("ปลดล็อก mebit") }
        val sheet = assertNotNull(owner.asking)
        assertFalse(sheet.cancellable, "the lock has no way out but the PIN")
        sheet.enter("583920")
        assertFalse(opened.isCompleted, "a wrong PIN keeps the lock")
        sheet.enter("270419")
        assertTrue(opened.answer())

        assertFalse(OwnerCheck(Phone(lock = true), sessionWithKey(SpendingPin(clock))).locksWhenAway, "a phone with a lock and App Lock off stays open, as before")
    }

    // N1c hardening. A gate that started while the 60-second lock asked took the lock's sheet, so the unlock answered false, nothing
    // asked again and the cover stayed with no sheet. A locked app signs nothing and shows no words, so that gate passes nothing.
    @Test
    fun aGateThatStartsWhileTheLockAsksPassesNothingAndLeavesTheLockItsSheet() = runBlocking {
        val session = sessionWithKey(SpendingPin(clock))
        assertTrue(session.pin.set("270419"))
        val owner = OwnerCheck(Phone(lock = false), session)
        val opened = gate(owner) { owner.unlock("ปลดล็อก mebit") }
        val lock = assertNotNull(owner.asking)
        val words = gate(owner) { owner.guardWords("ดู 24 คำสำรอง") }
        assertSame(lock, owner.asking, "the lock's sheet after another gate started")
        assertFalse(opened.isCompleted, "the lock still asks")
        assertFalse(words.answer(), "the words behind the lock")
        val signed = gate(owner) { appLock.confirm(owner, enabled = false, reason = "ยืนยันการเซ็นส่งเงิน") }
        assertFalse(signed.answer(), "a payment behind the lock")
        assertSame(lock, owner.asking)
        lock.enter("270419")
        assertTrue(opened.answer(), "the PIN still unlocks")
        assertNull(owner.asking)
        // A sheet that may be cancelled still gives way to a newer gate.
        val first = gate(owner) { owner.guardWords("ดู 24 คำสำรอง") }
        val second = gate(owner) { appLock.confirm(owner, enabled = false, reason = "ยืนยันการเซ็นส่งเงิน") }
        assertFalse(first.answer(), "the sheet a newer gate replaced")
        assertNotNull(owner.asking).enter("270419")
        assertTrue(second.answer())
    }

    @Test
    fun aLockRemovedUnderAppLockMakesTheUnlockSetAPinInsteadOfStickingForever() = runBlocking {
        val phone = Phone(lock = true)
        val session = sessionWithKey(SpendingPin(clock), SavedWallet(FakeSecureStore()))
        session.markAppLockEnabled()
        val owner = OwnerCheck(phone, session)
        assertTrue(owner.locksWhenAway)
        phone.lock = false
        assertTrue(owner.usesPin, "the overlay unlocks through the PIN sheet, not the phone's check it lost")
        val opened = gate(owner) { owner.unlock("ปลดล็อก mebit") }
        val sheet = assertNotNull(owner.asking)
        assertEquals(PinStep.New, sheet.entry.step)
        sheet.enter("270419")
        sheet.enter("270419")
        assertTrue(opened.answer())
        assertEquals(0, phone.asked)
    }

    @Test
    fun theWordsAskThePinOnlyWherePinGuards() = runBlocking {
        val session = sessionWithKey(SpendingPin(clock))
        assertTrue(session.pin.set("270419"))
        val owner = OwnerCheck(Phone(lock = false), session)
        val shown = gate(owner) { owner.guardWords("ดู 24 คำสำรอง") }
        val sheet = assertNotNull(owner.asking, "the PIN before the words")
        assertEquals(PinStep.Check, sheet.entry.step)
        sheet.enter("583920")
        assertFalse(shown.isCompleted)
        sheet.enter("270419")
        assertTrue(shown.answer())

        val cancelled = gate(owner) { owner.guardWords("ดู 24 คำสำรอง") }
        assertNotNull(owner.asking).cancel()
        assertFalse(cancelled.answer(), "a cancelled sheet shows no words")

        val withLock = OwnerCheck(Phone(lock = true), sessionWithKey(SpendingPin(clock)))
        assertTrue(withLock.guardWords("ดู 24 คำสำรอง"), "a phone with a lock shows the words as before")
        assertNull(withLock.asking)
    }

    @Test
    fun aPinChangeAsksTheCurrentPinFirst() = runBlocking {
        val session = sessionWithKey(SpendingPin(clock))
        assertTrue(session.pin.set("270419"))
        val owner = OwnerCheck(Phone(lock = false), session)
        val changed = gate(owner) { owner.changePin("เปลี่ยน PIN") }
        val sheet = assertNotNull(owner.asking)
        assertEquals(PinStep.Check, sheet.entry.step, "the current PIN first")
        sheet.enter("583920")
        assertEquals(PinStep.Check, sheet.entry.step, "a wrong current PIN goes no further")
        assertEquals(PinProblem.Wrong, sheet.entry.problem)
        sheet.enter("270419")
        assertEquals(PinStep.New, sheet.entry.step)
        sheet.enter("583920")
        assertEquals(PinStep.Repeat, sheet.entry.step)
        sheet.enter("583920")
        assertTrue(changed.answer())
        assertTrue(session.pin.check("583920"), "the new PIN")
        assertFalse(session.pin.check("270419"), "the old PIN no longer passes")
    }

    @Test
    fun aCancelledChangeKeepsTheOldPin() = runBlocking {
        val session = sessionWithKey(SpendingPin(clock))
        assertTrue(session.pin.set("270419"))
        val owner = OwnerCheck(Phone(lock = false), session)
        val changed = gate(owner) { owner.changePin("เปลี่ยน PIN") }
        val sheet = assertNotNull(owner.asking)
        sheet.enter("270419")
        sheet.enter("583920")
        sheet.cancel()
        assertFalse(changed.answer())
        assertTrue(session.pin.check("270419"))
    }

    @Test
    fun aWrongPinWaitOutlivesTheSheetAndComesBackWithTheNextOne() = runBlocking {
        val session = sessionWithKey(SpendingPin(clock))
        assertTrue(session.pin.set("270419"))
        val owner = OwnerCheck(Phone(lock = false), session)
        val first = gate(owner) { appLock.confirm(owner, enabled = false, reason = "sign") }
        val sheet = assertNotNull(owner.asking)
        repeat(3) { sheet.enter("583920") }
        assertFalse(sheet.entry.open(), "the third wrong PIN closes the pad for 10 s")
        sheet.cancel()
        assertFalse(first.answer(), "the user leaves Review")

        clock += 4.seconds
        val again = gate(owner) { appLock.confirm(owner, enabled = false, reason = "sign") }
        val next = assertNotNull(owner.asking, "the user comes back and signs again")
        assertFalse(next.entry.open(), "the wait goes on in the new sheet")
        assertEquals(6.seconds, session.pin.waitLeft())
        next.enter("270419")
        assertFalse(again.isCompleted, "the right PIN typed inside the wait passes nothing")
        clock += 6.seconds
        assertTrue(next.entry.open())
        next.enter("270419")
        assertTrue(again.answer())
    }

    @Test
    fun nothingOfThePinReachesTheStoreOrAFile() = runBlocking {
        val store = FakeSecureStore()
        val guards = FakePlainFile()
        val session = sessionWithKey(SpendingPin(clock), SavedWallet(store), guards)
        val writes = store.writes.size
        val owner = OwnerCheck(Phone(lock = false), session)

        val set = gate(owner) { appLock.confirm(owner, enabled = false, reason = "sign") }
        assertNotNull(owner.asking).run {
            enter("270419")
            enter("270419")
        }
        assertTrue(set.answer())
        val signed = gate(owner) { appLock.confirm(owner, enabled = false, reason = "sign") }
        assertNotNull(owner.asking).run {
            enter("583920")
            enter("270419")
        }
        assertTrue(signed.answer())
        val changed = gate(owner) { owner.changePin("เปลี่ยน PIN") }
        assertNotNull(owner.asking).run {
            enter("270419")
            enter("583920")
            enter("583920")
        }
        assertTrue(changed.answer())

        assertEquals(writes, store.writes.size, "the secure store")
        assertEquals(0, guards.writes, "the guard file")
        assertNull(guards.text)
    }

    /** Starts [gate] as a screen does, then lets it run until it waits on the PIN sheet or ends. */
    private suspend fun <T> CoroutineScope.gate(owner: OwnerCheck, gate: suspend () -> T): Deferred<T> {
        val running = async { gate() }
        yield()
        return running
    }

    private fun PinAsk.enter(digits: String) = digits.forEach(::type)

    /** The gate's answer. A gate that never answers fails the test instead of hanging it. */
    private suspend fun <T> Deferred<T>.answer(): T = withTimeout(2_000) { await() }
}

/** A session holding the public test words in memory, made as Create makes them, with [pin] beside them. */
internal fun sessionWithKey(pin: SpendingPin, wallet: SavedWallet? = null, guards: FakePlainFile? = null): OnboardingSession =
    OnboardingSession(
        wallet,
        newKey = { _, _, _ -> SoftwareAccountKey(PUBLIC_WORDS_24, TEST_CHECK, "tpub-test", "m/48'/1'/0'/2'") },
        payments = PendingPayments(guards),
        pin = pin,
    ).apply { generateThisDevice() }
