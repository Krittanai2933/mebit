package com.mebit.app.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mebit.app.Why
import com.mebit.app.data.Cause
import com.mebit.app.data.OnboardingSession
import com.mebit.app.data.SavedWallet
import com.mebit.app.data.SingleSigWallet
import com.mebit.app.data.StartNotice
import com.mebit.app.data.UnlockResult

/**
 * The cold-start gate as plain state with no UI. It holds where the app starts and the two ways off the unlock screen,
 * [unlock] and [forget]. Making one runs this process's cold start ([SavedWallet.start]), which may erase a wallet a
 * deleted install left behind and writes the marker, so make one per process.
 */
class WalletGateState internal constructor(
    private val wallet: SavedWallet,
    private val session: OnboardingSession,
) {
    /** The probe [boot] starts from and the notice, read without a prompt. */
    private val started = wallet.start()

    /** Where the app is. Decided before the first frame, because the start never prompts. */
    var boot: Boot by mutableStateOf(coldStart(started.probe))
        private set

    /** The last attempt that kept the app locked, for the screen to explain. Null before the first. */
    var lastAttempt: UnlockResult? by mutableStateOf(null)
        private set

    /** A one-time sentence for Welcome after the start erased or lost a wallet. */
    val notice: StartNotice? = started.notice

    /** An unlock or a forget is running. They share it, so a second tap, or a Forget while the OS sheet is up, does nothing. */
    private var busy = false

    /**
     * The older guard (flag 8) of a record that opened but did not install, with that record's fingerprint, which the recovery
     * screen's Forget moves to the guard file before it erases. Nothing else of the record is kept.
     */
    private var notInstalled: Pair<String, SingleSigWallet.UnconfirmedSend>? = null

    /** Idempotent. One attempt runs at a time, and nothing happens unless Locked. The record never leaves this function. */
    suspend fun unlock(reason: String) {
        if (busy || boot != Boot.Locked) return
        busy = true
        try {
            when (val r = wallet.open(reason)) {
                is UnlockResult.Opened -> if (session.install(r.record, r.version, r.oldGuard)) {
                    boot = Boot.Open(startRoute(r.record.backupConfirmed, r.record.addressTypeConfirmed))
                } else {
                    notInstalled = r.oldGuard?.let { r.record.fingerprint to it }
                    boot = Boot.Broken(Cause.Corrupt)
                }
                is UnlockResult.Broken -> boot = Boot.Broken(r.cause)
                else -> lastAttempt = r // Cancelled, Denied, LockedOut and Unavailable stay locked with nothing installed
            }
        } finally {
            busy = false
        }
    }

    /**
     * After the confirm dialog. While the copy still opens (Locked), the owner proves themselves first with the same OS
     * prompt as an unlock, so someone holding an unlocked phone cannot erase the only copy of an unbacked-up wallet. A copy
     * that can never open (Broken) is erased on the confirm alone, because nothing readable is left to protect. A refusal
     * erases nothing. From Open or Onboarding it does nothing, because Forget belongs to the unlock and recovery screens
     * and a wallet the user has opened or just made must never be erased without the owner check. An older guard the record
     * showed goes to [OnboardingSession.forgetSavedWallet], which moves it to the guard file first or erases nothing.
     */
    suspend fun forget(reason: String) {
        if (busy || (boot != Boot.Locked && boot !is Boot.Broken)) return
        busy = true
        try {
            // A Broken record that could not be read shows no guard, so it is erased as before. Only one that opened has one here.
            var oldGuard = notInstalled
            if (boot == Boot.Locked) {
                when (val r = wallet.open(reason)) {
                    // The owner is proven. The record's older guard is all that is kept of it, so the erase never takes its only copy.
                    is UnlockResult.Opened -> oldGuard = r.oldGuard?.let { r.record.fingerprint to it }
                    is UnlockResult.Broken -> {
                        boot = Boot.Broken(r.cause) // it cannot open after all, so the recovery screen offers Forget on the confirm alone
                        return
                    }
                    else -> {
                        lastAttempt = r // the owner check refused, so the copy stays
                        return
                    }
                }
            }
            if (session.forgetSavedWallet(oldGuard)) boot = Boot.Onboarding else lastAttempt = UnlockResult.Unavailable(Why.Other)
        } finally {
            busy = false
        }
    }
}
