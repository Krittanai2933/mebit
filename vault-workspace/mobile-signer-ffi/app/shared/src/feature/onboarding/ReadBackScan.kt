package com.mebit.app.feature.onboarding

import com.mebit.app.BiometricResult
import com.mebit.app.data.UnlockResult

/**
 * Pure. After a scan on the Enable step that came back Unavailable, whether onboarding goes on without a lock now (true) or
 * shows its line and waits for the link (false). The link goes on, as on a phone that cannot scan. So does a read-back that
 * just gave the wallet's copy up ([copyGivenUp]: the session held a copy before it and holds none after). The phone's lock is
 * fine then, the copy is what failed, and a second scan would turn on a lock the next launch cannot keep. The Wallet list's
 * notice says the key is not saved. A read-back that answered try later keeps the copy, so the step stays and the user taps again.
 */
internal fun goesOnWithoutLock(enable: Boolean, allowPasscode: Boolean, copyGivenUp: Boolean): Boolean =
    enable && (allowPasscode || copyGivenUp)

/** Pure. Whether the App Lock screen lets the user on after a scan: a match, or an Unavailable that [goesOnWithoutLock] lets through. */
internal fun scanOpens(result: BiometricResult, enable: Boolean, allowPasscode: Boolean, copyGivenUp: Boolean): Boolean =
    when (result) {
        BiometricResult.Success -> true
        is BiometricResult.Unavailable -> goesOnWithoutLock(enable, allowPasscode, copyGivenUp)
        BiometricResult.Failed, BiometricResult.LockedOut, BiometricResult.Cancelled -> false
    }

/**
 * Pure. The App Lock Enable read-back's answer ([com.mebit.app.data.OnboardingSession.proveSavedCopy]) as the scan result the
 * screen already handles. Only a Verified copy turns the lock on. A copy that can never open is Unavailable, so the screen
 * offers to go on without a lock, as on a phone that cannot scan.
 */
internal fun readBackScan(result: UnlockResult): BiometricResult = when (result) {
    UnlockResult.Verified -> BiometricResult.Success
    UnlockResult.Cancelled -> BiometricResult.Cancelled
    UnlockResult.Denied -> BiometricResult.Failed
    UnlockResult.LockedOut -> BiometricResult.LockedOut
    is UnlockResult.Unavailable -> BiometricResult.Unavailable(result.why.name)
    is UnlockResult.Broken -> BiometricResult.Unavailable(result.cause.name)
    // The read-back answers Verified, never Opened, and a copy nobody compared proves nothing.
    is UnlockResult.Opened -> BiometricResult.Unavailable("Opened")
}
