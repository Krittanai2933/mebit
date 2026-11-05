package com.mebit.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mebit.app.BiometricResult
import com.mebit.app.Biometrics
import com.mebit.app.SleepCountingTime
import com.mebit.app.sha256
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred

/** Digits in a Spending PIN. */
const val PIN_LENGTH = 6

/**
 * The session Spending PIN of open item 79, option D, which guards spending on a phone with no owner check of its own. It lives
 * beside the words in [OnboardingSession] and goes with them, as a salted hash in memory and a count of the wrong PINs since the
 * last right one, timed on the monotonic [clock]. Nothing of it is ever written, and no number of wrong PINs wipes anything.
 */
class SpendingPin(internal val clock: TimeSource = SleepCountingTime) {
    private class Salted(val salt: ByteArray, val hash: ByteArray)

    private var stored: Salted? by mutableStateOf(null)
    private var misses = 0
    private var lastMiss: TimeMark? = null

    val isSet: Boolean get() = stored != null

    /** Makes [pin] this session's PIN. False, and nothing changes, for anything but six digits or for a common PIN. */
    fun set(pin: String): Boolean {
        if (!SIX_DIGITS.matches(pin) || isCommonPin(pin)) return false
        // A salt needs no secrecy. It only keeps one PIN from hashing alike in two sessions.
        val salt = Random.nextBytes(16)
        stored = Salted(salt, sha256(salt + pin.encodeToByteArray()))
        return true
    }

    /** True for the session's PIN outside a wait. A wrong PIN counts toward [pinWait], and a try inside a wait counts nothing. */
    fun check(pin: String): Boolean {
        val held = stored ?: return false
        if (waitLeft() > Duration.ZERO) return false
        if (sha256(held.salt + pin.encodeToByteArray()).contentEquals(held.hash)) {
            misses = 0
            lastMiss = null
            return true
        }
        misses++
        lastMiss = clock.markNow()
        return false
    }

    /** What is left of the wait the last wrong PIN started, or zero. */
    fun waitLeft(): Duration = lastMiss?.let { (pinWait(misses) - it.elapsedNow()).coerceAtLeast(Duration.ZERO) } ?: Duration.ZERO

    /** Drops the PIN with the words it guarded. */
    fun clear() {
        stored = null
        misses = 0
        lastMiss = null
    }

    private companion object {
        val SIX_DIGITS = Regex("[0-9]{$PIN_LENGTH}")
    }
}

/** Phoenix's waits after wrong PINs in a row. None for the first two, then 10 s, 30 s, 1, 2, 5 and 10 minutes, then 30 minutes each. */
fun pinWait(misses: Int): Duration = when (misses) {
    0, 1, 2 -> Duration.ZERO
    3 -> 10.seconds
    4 -> 30.seconds
    5 -> 1.minutes
    6 -> 2.minutes
    7 -> 5.minutes
    8 -> 10.minutes
    else -> 30.minutes
}

/**
 * A PIN anyone would try first: a run up or down (123456, 654321), a pair three times (000000, 121212), three digits twice
 * (123123), or three doubled digits in a run (112233). Refused when one is set, because a list of them falls in a day of tries.
 */
fun isCommonPin(pin: String): Boolean {
    fun stepsByOne(digits: String) = digits.zipWithNext { a, b -> b - a }.toSet().let { it == setOf(1) || it == setOf(-1) }
    val pairs = pin.chunked(2)
    return stepsByOne(pin) || pin == pin.take(2).repeat(3) || pin == pin.take(3).repeat(2) ||
        (pairs.all { it.length == 2 && it[0] == it[1] } && stepsByOne(pairs.joinToString("") { it.take(1) }))
}

/** The steps of one PIN entry. A gate checks the session's PIN. A first PIN, or a change once the current one matched, is typed twice. */
enum class PinStep { Check, New, Repeat }

/** Why the last six digits did not go through. */
enum class PinProblem { Wrong, Common, Mismatch }

/** What the user types into one PIN sheet or step, as the pad sends it. */
class PinEntry(private val pin: SpendingPin, private val change: Boolean = false) {
    var step: PinStep by mutableStateOf(if (pin.isSet) PinStep.Check else PinStep.New)
        private set
    var problem: PinProblem? by mutableStateOf(null)
        private set
    private var digits by mutableStateOf("")
    private var first = ""

    /** How many digits of this step are typed, for the dots. The digits stay here. */
    val typed: Int get() = digits.length

    /** Whether the pad takes digits now. Not while a wrong PIN's wait runs, which only the check step waits out. */
    fun open(): Boolean = step != PinStep.Check || pin.waitLeft() == Duration.ZERO

    /** One digit from the pad. True once the entry is done, when the PIN matched or a new one is set. */
    fun type(digit: Char): Boolean {
        if (!open()) return false
        digits += digit
        if (digits.length < PIN_LENGTH) return false
        val entered = digits
        digits = ""
        when (step) {
            PinStep.Check -> when {
                !pin.check(entered) -> problem = PinProblem.Wrong
                !change -> return true
                else -> next(PinStep.New, problem = null)
            }
            PinStep.New -> if (isCommonPin(entered)) {
                problem = PinProblem.Common
            } else {
                first = entered
                next(PinStep.Repeat, problem = null)
            }
            PinStep.Repeat -> if (entered == first && pin.set(entered)) return true else next(PinStep.New, PinProblem.Mismatch)
        }
        return false
    }

    fun erase() {
        digits = digits.dropLast(1)
    }

    private fun next(step: PinStep, problem: PinProblem?) {
        this.step = step
        this.problem = problem
        if (step == PinStep.New) first = ""
    }
}

/** One open PIN sheet, with the [title] of its check step, what is typed, and whether it may be closed. The gate that opened it waits. */
class PinAsk internal constructor(val title: String, val entry: PinEntry, val cancellable: Boolean) {
    internal val answer = CompletableDeferred<Boolean>()

    /** A digit from the pad. A PIN that matched, or a new one set, passes the gate. */
    fun type(digit: Char) {
        if (entry.type(digit)) answer.complete(true)
    }

    /** Closes the sheet with nothing passed. */
    fun cancel() {
        answer.complete(false)
    }
}

/**
 * The owner check of the gates: signing ([AppLockState.confirm]), the 60-second lock, the words once onboarding is over, and a PIN
 * change. It is the phone's own while the phone has one, and the Spending PIN sheet ([asking]) where it has none, decided live at
 * each gate. Once a PIN is set it stays the check for the session, so a lock added later never drops the PIN the user chose.
 */
class OwnerCheck(private val phone: Biometrics, private val session: OnboardingSession) : Biometrics {
    /** The PIN sheet on screen, or null. The nav host draws it, and the gate that opened it waits for its answer. */
    var asking: PinAsk? by mutableStateOf(null)
        private set

    /** Whether the Spending PIN guards this session, because one is set or the phone has no owner check now. */
    val usesPin: Boolean get() = session.pin.isSet || !phone.available()

    /** No owner check and no PIN yet. Onboarding sets one before Home, and a gate sets one before it lets anything through. */
    val needsPin: Boolean get() = usesPin && !session.pin.isSet

    /** Whether the 60-second lock applies, once the user turned App Lock on or a PIN is set. Before the PIN, as during onboarding, there is nothing to ask for. */
    val locksWhenAway: Boolean get() = session.appLockEnabled || session.pin.isSet

    override fun available(): Boolean = !usesPin

    override suspend fun authenticate(reason: String, allowPasscode: Boolean): BiometricResult = when {
        !usesPin -> phone.authenticate(reason, allowPasscode)
        ask(reason, change = false, cancellable = true) -> BiometricResult.Success
        else -> BiometricResult.Cancelled
    }

    /** Before the 24 words once onboarding is over. A phone with its own owner check shows them as before. */
    suspend fun guardWords(reason: String): Boolean = !usesPin || ask(reason, change = false, cancellable = true)

    /** A new PIN, typed twice, once the current one matched. */
    suspend fun changePin(reason: String): Boolean = ask(reason, change = true, cancellable = true)

    /** The 60-second lock on a phone the PIN guards. The sheet has no way out but the PIN. */
    suspend fun unlock(reason: String): Boolean = ask(reason, change = false, cancellable = false)

    private suspend fun ask(reason: String, change: Boolean, cancellable: Boolean): Boolean {
        // A newer gate takes the sheet, and the one it replaces passes nothing.
        asking?.cancel()
        val ask = PinAsk(reason, PinEntry(session.pin, change), cancellable)
        asking = ask
        try {
            return ask.answer.await()
        } finally {
            if (asking === ask) asking = null
        }
    }
}
