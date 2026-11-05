package com.mebit.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.mebit.app.data.OnboardingSession
import com.mebit.app.data.PendingPayments
import com.mebit.app.data.SavedWallet
import com.mebit.app.data.rememberSavedWallet
import com.mebit.app.feature.onboarding.UnlockScreen
import com.mebit.app.plainFile
import com.mebit.app.rememberScanStateDir
import com.mebit.ffi.signer.hotUseStateDir

/**
 * What App() shows: Welcome, the unlock or recovery screen, or the nav host at the route the saved wallet opens at.
 * The nav host does not exist until the wallet is open or there is none, so nothing that reads the session runs
 * before the owner check.
 */
@Composable
fun WalletGate(wallet: SavedWallet = rememberSavedWallet()) {
    // Before any screen asks the core about the wallet, so its first sync after a restart reads the scan file.
    val scanDir = rememberScanStateDir()
    remember(scanDir) { scanDir?.let(::hotUseStateDir) }
    // One wallet under both, because the gate's forget erases through the session. The pay-twice guard file sits beside the scan files.
    val session = remember(wallet, scanDir) { OnboardingSession(wallet, payments = PendingPayments(scanDir?.let { plainFile("$it/pending-payment") })) }
    val gate = remember(wallet) { WalletGateState(wallet, session) }

    // A failed save is tried again when the app comes back, for example once the user has set a passcode. Not while Locked or
    // Broken: a session with nothing in it has nothing to flush there, and if the iOS store ever falls back to the Keychain's
    // own sheet, a read holds the store's lock while that sheet is up, so a flush would block the main thread until it closes.
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (gate.boot is Boot.Open || gate.boot == Boot.Onboarding) session.flushPendingSave()
    }

    when (val boot = gate.boot) {
        Boot.Onboarding -> MebitNavHost(session, MovieRoute.start, gate.notice)
        is Boot.Open -> MebitNavHost(session, boot.route)
        Boot.Locked, is Boot.Broken -> UnlockScreen(gate)
    }
}
