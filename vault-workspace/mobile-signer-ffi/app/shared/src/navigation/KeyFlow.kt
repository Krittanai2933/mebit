package com.mebit.app.navigation

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
