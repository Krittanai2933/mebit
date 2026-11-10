package com.mebit.app.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mebit.app.Why
import com.mebit.app.data.Every
import com.mebit.app.data.StartNotice
import com.mebit.app.data.UnlockResult
import com.mebit.app.data.Wallets

/**
 * The cold-start gate as plain state with no UI. It holds where the app starts and the two ways off the unlock screen,
 * [unlock] and [forget]. Making one runs this process's cold start ([Wallets.start]), which may erase wallets a
 * deleted install left behind and writes the marker, so make one per process.
 */
class WalletGateState internal constructor(private val wallets: Wallets) {
    /** The probe [boot] starts from and the notice, read without a prompt. */
    private val started = wallets.start()

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

    /** Idempotent. One attempt runs at a time, and nothing happens unless Locked. No record leaves this function. */
    suspend fun unlock(reason: String) {
        if (busy || boot != Boot.Locked) return
        busy = true
        try {
            when (val every = wallets.unlockEvery(reason)) {
                is Every.Refused -> lastAttempt = every.result // Cancelled, Denied, LockedOut and Unavailable stay locked with nothing installed
                is Every.Broken -> boot = Boot.Broken(every.cause)
                is Every.Open -> boot = Boot.Open(startRoute(every.resume.session.backupConfirmed, every.resume.session.addressTypeConfirmed), every.resume.place)
            }
        } finally {
            busy = false
        }
    }

    /**
     * After the confirm dialog. While a copy still opens (Locked), the owner proves themselves first with the same OS
     * prompt as an unlock, so someone holding an unlocked phone cannot erase the only copy of an unbacked-up wallet. A copy
     * that can never open (Broken) is erased on the confirm alone, because nothing readable is left to protect. A refusal
     * erases nothing. From Open or Onboarding it does nothing, because Forget belongs to the unlock and recovery screens
     * and a wallet the user has opened or just made must never be erased without the owner check. An older guard a record
     * showed goes to [Wallets.forgetEvery], which moves it to the guard file first or erases nothing.
     */
    suspend fun forget(reason: String) {
        if (busy || (boot != Boot.Locked && boot !is Boot.Broken)) return
        busy = true
        try {
            if (boot == Boot.Locked) {
                when (val refused = wallets.proveOwner(reason)) {
                    null -> Unit
                    is Every.Broken -> {
                        boot = Boot.Broken(refused.cause) // it cannot open after all, so the recovery screen offers Forget on the confirm alone
                        return
                    }
                    is Every.Refused -> {
                        lastAttempt = refused.result // the owner check refused, so the copy stays
                        return
                    }
                    is Every.Open -> error("proveOwner never installs")
                }
            }
            if (wallets.forgetEvery()) boot = Boot.Onboarding else lastAttempt = UnlockResult.Unavailable(Why.Other)
        } finally {
            busy = false
        }
    }
}
