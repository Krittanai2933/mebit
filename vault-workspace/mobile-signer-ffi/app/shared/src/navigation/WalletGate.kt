package com.mebit.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.mebit.app.Place
import com.mebit.app.SystemBack
import com.mebit.app.data.NodeSettings
import com.mebit.app.data.UnitSettings
import com.mebit.app.data.PendingPayments
import com.mebit.app.data.SavedVaults
import com.mebit.app.data.Wallets
import com.mebit.app.feature.onboarding.NoScreenLockScreen
import com.mebit.app.feature.onboarding.UnlockScreen
import com.mebit.app.plainFile
import com.mebit.app.rememberBiometrics
import com.mebit.app.rememberLeaveApp
import com.mebit.app.rememberPairings
import com.mebit.app.rememberScreenLockSettingsOpener
import com.mebit.app.rememberScanStateDir
import com.mebit.app.rememberSecureShelf
import com.mebit.app.rememberVaultsStorage
import com.mebit.app.ui.components.LocalCovered
import com.mebit.app.ui.components.coversScreen
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.hotUseStateDir
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

/** What the gate does about the phone's own screen lock. */
enum class LockGate {
    /** No lock yet this run: only the block shows, and nothing opens a store. */
    Wait,

    /** The phone has its lock: the app runs. */
    Open,

    /** The lock this run had is gone: the block covers the app, whose wallets stay in memory until the lock is back. */
    Covered,
}

/** Pure. [present] is whether the phone has its own lock now, and [seenThisRun] whether it had one at any read since this process began. */
fun lockGate(present: Boolean, seenThisRun: Boolean): LockGate = when {
    present -> LockGate.Open
    seenThisRun -> LockGate.Covered
    else -> LockGate.Wait
}

/**
 * What App() shows: the no-screen-lock block, Welcome, the unlock or recovery screen, or the nav host at the route the saved wallet
 * opens at. A phone with no screen lock gets only the block, hardware wallets included, because every gate of the app is the phone's
 * own lock (open item 79). The lock is read again whenever the app comes back, as from Settings.
 */
@Composable
fun WalletGate() {
    val phone = rememberBiometrics()
    var present by remember(phone) { mutableStateOf(phone.available()) }
    var seen by remember(phone) { mutableStateOf(present) }
    fun read() {
        present = phone.available()
        seen = seen || present
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { read() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { read() }

    val lock = lockGate(present, seen)
    if (lock == LockGate.Wait) {
        NoScreenLockScreen(rememberScreenLockSettingsOpener())
    } else {
        // Open and Covered compose the app in the same place, so a lock that comes and goes keeps every wallet and screen.
        CompositionLocalProvider(LocalCovered provides (lock == LockGate.Covered)) { OpenGate(lock) }
        if (lock == LockGate.Covered) {
            // Composed after the app, so this SystemBack wins over its screens' and Back sends the app to the background.
            SystemBack(rememberLeaveApp())
            Box(modifier = Modifier.fillMaxSize().coversScreen()) { NoScreenLockScreen(rememberScreenLockSettingsOpener()) }
        }
    }
}

/** The waits before each flush of [OpenGate]: at once, then for a key that may take seconds to make in a TEE or StrongBox. */
private val SAVE_RETRY_WAITS = listOf(0, 1, 2, 4, 8, 16).map { it.seconds }

@Composable
private fun OpenGate(lock: LockGate) {
    // Before any screen asks the core about a wallet, so its first sync after a restart reads the scan file.
    val scanDir = rememberScanStateDir(KeyNetwork.TEST)
    remember(scanDir) { scanDir?.let(::hotUseStateDir) }
    // Also before any chain read: the node the user chose in Settings is in force from the first request.
    val nodeSettings = remember(scanDir) { NodeSettings(scanDir?.let { plainFile("$it/node-choice") }).also { it.restore() } }
    val unitSettings = remember(scanDir) { UnitSettings(scanDir?.let { plainFile("$it/btc-unit") }).also { it.restore() } }
    val storage = rememberVaultsStorage()
    val shelf = rememberSecureShelf()
    val pairings = rememberPairings()
    // The pay-twice guard files, one-key and several-key, and the last-used wallet sit beside the scan files. Built together, because
    // each asks the other: the book is saved for the wallets' keys, and a wallet's clear of a damaged guard file names the book's wallets.
    val (wallets, vaults) = remember(shelf, scanDir, storage, pairings) {
        lateinit var book: SavedVaults
        val wallets = Wallets(
            shelf,
            payments = PendingPayments(scanDir?.let { plainFile("$it/pending-payment") }),
            lastUsed = scanDir?.let { plainFile("$it/last-wallet") },
            forgetBook = {
                storage.delete()
                pairings.deleteAll()
            },
            vaultPayments = PendingPayments(scanDir?.let { plainFile("$it/pending-vault-payment") }),
            bookGuardKeys = { book.guardKeys() },
            bookHardware = { book.current.hardware },
        )
        book = SavedVaults(storage, owners = { wallets.vaultOwners() })
        wallets to book
    }
    val gate = remember(wallets) { WalletGateState(wallets) }
    // Welcome means this phone holds no wallet, so what the stores hold is nobody's now, as after a reinstall, which a Keychain item survives.
    remember(gate) {
        if (gate.boot == Boot.Onboarding) {
            storage.delete()
            pairings.deleteAll()
        }
    }

    // A failed save is tried again when the app comes back, for example once the user has set a passcode, and when the lock comes
    // back after Covered marked every saved wallet ([Wallets.lockRead]). Keyed on the lock this composition read, never on one read
    // in the ON_START callback, which may run before the gate's own read. While a save is still owed, as while a new Keystore key is
    // being made, it tries again a few times with growing waits. Not while Locked or Broken: a session with nothing in it has
    // nothing to flush there, and if the iOS store ever falls back to the Keychain's own sheet, a read holds the store's lock while
    // that sheet is up, so a flush would block the main thread until it closes.
    var returns by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { returns++ }
    LaunchedEffect(returns, lock, wallets.savesPending) {
        if (lock == LockGate.Covered) {
            wallets.lockRead(lockPresent = false)
            return@LaunchedEffect
        }
        for (wait in SAVE_RETRY_WAITS) {
            delay(wait)
            if (gate.boot !is Boot.Open && gate.boot != Boot.Onboarding) return@LaunchedEffect
            if (!wallets.lockRead(lockPresent = true)) return@LaunchedEffect
        }
    }

    when (val boot = gate.boot) {
        Boot.Onboarding -> MebitNavHost(wallets, Stop(MovieRoute.start, Place.FIRST), gate.notice, vaults, pairings, nodeSettings, unitSettings)
        is Boot.Open -> MebitNavHost(wallets, Stop(boot.route, boot.place), vaults = vaults, pairings = pairings, nodeSettings = nodeSettings, unitSettings = unitSettings)
        Boot.Locked, is Boot.Broken -> UnlockScreen(gate)
    }
}
