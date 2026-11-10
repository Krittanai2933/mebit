package com.mebit.app.navigation

import com.mebit.app.data.KeySourceLabel

/** Where a key is being added, which decides where a phone key's retry returns. */
enum class KeyFlow { Onboarding, Keyring }

/** The flow after [opened] is navigated to: Key Source starts onboarding, the keyring screens and the Wallet list end it, any other route keeps [current]. */
fun keyFlowAfter(opened: MovieRoute, current: KeyFlow): KeyFlow = when (opened) {
    MovieRoute.KeySource -> KeyFlow.Onboarding
    MovieRoute.AddKey, MovieRoute.Keyring, MovieRoute.VaultList -> KeyFlow.Keyring
    else -> current
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
