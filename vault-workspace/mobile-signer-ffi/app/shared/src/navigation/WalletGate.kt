package com.mebit.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.mebit.app.Place
import com.mebit.app.data.PendingPayments
import com.mebit.app.data.SavedVaults
import com.mebit.app.data.Wallets
import com.mebit.app.feature.onboarding.UnlockScreen
import com.mebit.app.plainFile
import com.mebit.app.rememberScanStateDir
import com.mebit.app.rememberSecureShelf
import com.mebit.app.rememberVaultsStorage
import com.mebit.ffi.signer.hotUseStateDir

/**
 * What App() shows: Welcome, the unlock or recovery screen, or the nav host at the route the saved wallet opens at.
 * The nav host does not exist until the wallets are open or there are none, so nothing that reads a session runs
 * before the owner check.
 */
@Composable
fun WalletGate() {
    // Before any screen asks the core about a wallet, so its first sync after a restart reads the scan file.
    val scanDir = rememberScanStateDir()
    remember(scanDir) { scanDir?.let(::hotUseStateDir) }
    val storage = rememberVaultsStorage()
    val shelf = rememberSecureShelf()
    // The pay-twice guard file and the last-used wallet sit beside the scan files.
    val wallets = remember(shelf, scanDir, storage) {
        Wallets(
            shelf,
            payments = PendingPayments(scanDir?.let { plainFile("$it/pending-payment") }),
            lastUsed = scanDir?.let { plainFile("$it/last-wallet") },
            forgetBook = { storage?.delete() },
        )
    }
    val vaults = remember(wallets, storage) { SavedVaults(storage, owners = { wallets.vaultOwners() }) }
    val gate = remember(wallets) { WalletGateState(wallets) }
    // Welcome means this phone holds no wallet, so what the store holds is nobody's now, as after a reinstall, which a Keychain item survives.
    remember(gate) { if (gate.boot == Boot.Onboarding) storage?.delete() }

    // A failed save is tried again when the app comes back, for example once the user has set a passcode. Not while Locked or
    // Broken: a session with nothing in it has nothing to flush there, and if the iOS store ever falls back to the Keychain's
    // own sheet, a read holds the store's lock while that sheet is up, so a flush would block the main thread until it closes.
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (gate.boot is Boot.Open || gate.boot == Boot.Onboarding) wallets.flushPendingSaves()
    }

    when (val boot = gate.boot) {
        Boot.Onboarding -> MebitNavHost(wallets, Stop(MovieRoute.start, Place.FIRST), gate.notice, vaults)
        is Boot.Open -> MebitNavHost(wallets, Stop(boot.route, boot.place), vaults = vaults)
        Boot.Locked, is Boot.Broken -> UnlockScreen(gate)
    }
}
