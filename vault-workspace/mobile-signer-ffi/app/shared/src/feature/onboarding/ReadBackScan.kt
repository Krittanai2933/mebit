package com.mebit.app.feature.onboarding

import com.mebit.app.BiometricResult
import com.mebit.app.data.UnlockResult

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
