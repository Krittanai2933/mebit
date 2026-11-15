package com.mebit.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mebit.app.BiometricResult
import com.mebit.app.Biometrics
import com.mebit.app.SleepCountingTime
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * When the app asks for the owner again: after it has been in the background for more than a minute, and before it signs a send.
 * Only matters once the user turned the lock on, or where the check is not the phone's own but the Spending PIN ([OwnerCheck]).
 * In memory only: a cold start begins at the wallet gate, which asks for the owner itself when a wallet is saved, and this lock
 * starts unlocked with the nav host that owns it.
 */
class AppLockState(internal val clock: TimeSource = SleepCountingTime) {
    var locked: Boolean by mutableStateOf(false)
        private set

    private var leftAt: TimeMark? = null

    fun onBackground() {
        leftAt = clock.markNow()
    }

    /** [enabled] is whether this session locks after a minute away ([OwnerCheck.locksWhenAway]). */
    fun onForeground(enabled: Boolean) {
        val left = leftAt
        leftAt = null
        if (enabled && left != null && left.elapsedNow() > GRACE) locked = true
    }

    fun unlock() {
        locked = false
    }

    /**
     * Asks for the owner before spending when the user turned the lock on, and always where [biometrics] says the phone's own check
     * does not guard the session, which makes it the Spending PIN. Fails closed, so anything but a match refuses.
     */
    suspend fun confirm(biometrics: Biometrics, enabled: Boolean, reason: String): Boolean =
        (!enabled && biometrics.available()) || biometrics.authenticate(reason, allowPasscode = true) == BiometricResult.Success

    private companion object {
        val GRACE = 60.seconds
    }
}
