package com.mebit.app.navigation

import com.mebit.app.data.KeySourceLabel
import com.mebit.app.data.OnboardingSession

/** Where a key is being added, which decides the step after Confirm Key. */
enum class KeyFlow { Onboarding, Keyring }

/** The flow after [opened] is navigated to: Key Source starts onboarding, the keyring screens and Home end it, any other route keeps [current]. */
fun keyFlowAfter(opened: MovieRoute, current: KeyFlow): KeyFlow = when (opened) {
    MovieRoute.KeySource -> KeyFlow.Onboarding
    MovieRoute.AddKey, MovieRoute.Keyring, MovieRoute.KeyringFirstVault, MovieRoute.HomeFirstRun -> KeyFlow.Keyring
    else -> current
}

/** The route after a matched fingerprint: the hardware backup step during onboarding, the keyring otherwise. */
fun routeAfterVerify(flow: KeyFlow): MovieRoute = when (flow) {
    KeyFlow.Onboarding -> MovieRoute.HwBackupConfirm
    KeyFlow.Keyring -> MovieRoute.Keyring
}

/** After a backup: first setup goes on to the address type, a wallet opened later returns to its Home. */
internal fun routeAfterBackup(inFirstSetup: Boolean): MovieRoute = if (inFirstSetup) MovieRoute.AddressType else MovieRoute.HomeFirstRun

/**
 * Where an added wallet ends. An add that began on the Keyring returns there, where the new key shows. Any other add goes to the wallet's own Home,
 * the first-run Home while it is not backed up. [stack] is the Back stack's routes, with Add Key on it.
 */
internal fun routeAfterAdd(stack: List<MovieRoute>, backedUp: Boolean): MovieRoute {
    val addKey = stack.indexOfLast { it == MovieRoute.AddKey }
    return when {
        addKey > 0 && stack[addKey - 1] == MovieRoute.Keyring -> MovieRoute.Keyring
        backedUp -> MovieRoute.S1HomeSingleSig
        else -> MovieRoute.HomeFirstRun
    }
}

/** The end of an add, whatever its route: the wallet leaves first setup, because its key shows on Home or the Keyring from here on and a vault may use it. */
internal fun endAdd(stack: List<MovieRoute>, session: OnboardingSession): MovieRoute {
    session.endFirstSetup()
    return routeAfterAdd(stack, session.backupConfirmed)
}

fun routeBeforeVerify(source: KeySourceLabel, flow: KeyFlow): MovieRoute = when (source) {
    KeySourceLabel.Jade, KeySourceLabel.TrezorSafe7 -> MovieRoute.HardwareWallet
    KeySourceLabel.MebitOther -> MovieRoute.PairMebitOther
    KeySourceLabel.ThisDevice -> if (flow == KeyFlow.Onboarding) MovieRoute.KeySource else MovieRoute.AddKey
}

internal fun keyRetryBackStack(stack: List<MovieRoute>, sourceRoute: MovieRoute): List<MovieRoute> {
    val existing = stack.indexOfLast { it == sourceRoute }
    return if (existing >= 0) stack.take(existing + 1)
    else stack.dropLastWhile { it == MovieRoute.VerifyKey || it == MovieRoute.T14VerifyMismatch } + sourceRoute
}
