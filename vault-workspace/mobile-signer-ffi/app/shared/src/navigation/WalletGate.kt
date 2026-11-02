package com.mebit.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.mebit.app.data.OnboardingSession
import com.mebit.app.data.SavedWallet
import com.mebit.app.data.rememberSavedWallet
import com.mebit.app.feature.onboarding.UnlockScreen

/**
 * What App() shows: Welcome, the unlock or recovery screen, or the nav host at the route the saved wallet opens at.
 * The nav host does not exist until the wallet is open or there is none, so nothing that reads the session runs
 * before the owner check.
 */
@Composable
fun WalletGate(wallet: SavedWallet = rememberSavedWallet()) {
    // One wallet under both, because the gate's forget erases through the session.
    val session = remember(wallet) { OnboardingSession(wallet) }
    val gate = remember(wallet) { WalletGateState(wallet, session) }

    // A failed save is tried again when the app comes back. Only once open: the iOS store holds one lock for a whole
    // read, so a flush while an unlock's sheet is up would block the main thread until the sheet closes.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { if (gate.boot is Boot.Open) session.flushPendingSave() }

    when (val boot = gate.boot) {
        Boot.Onboarding -> MebitNavHost(session, MovieRoute.start, gate.notice)
        is Boot.Open -> MebitNavHost(session, boot.route)
        Boot.Locked, is Boot.Broken -> UnlockScreen(gate)
    }
}
