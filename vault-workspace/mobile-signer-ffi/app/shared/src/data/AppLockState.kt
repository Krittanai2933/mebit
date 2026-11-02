package com.mebit.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mebit.app.BiometricResult
import com.mebit.app.Biometrics
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * When the app asks for Face ID again: after it has been in the background for more than a
 * minute, and before it signs a send. Only matters once the user turned the lock on. In memory
 * only: a cold start begins at the wallet gate, which asks for the owner itself when a wallet is
 * saved, and this lock starts unlocked with the nav host that owns it.
 */
class AppLockState(private val clock: TimeSource = TimeSource.Monotonic) {
    var locked: Boolean by mutableStateOf(false)
        private set

    private var leftAt: TimeMark? = null

    fun onBackground() {
        leftAt = clock.markNow()
    }

    /** [enabled] is whether the user turned the lock on. */
    fun onForeground(enabled: Boolean) {
        val left = leftAt
        leftAt = null
        if (enabled && left != null && left.elapsedNow() > GRACE) locked = true
    }

    fun unlock() {
        locked = false
    }

    /** Face ID before spending, when the lock is on. Fails closed: anything but a match refuses. */
    suspend fun confirm(biometrics: Biometrics, enabled: Boolean, reason: String): Boolean =
        !enabled || biometrics.authenticate(reason, allowPasscode = true) == BiometricResult.Success

    private companion object {
        val GRACE = 60.seconds
    }
}
