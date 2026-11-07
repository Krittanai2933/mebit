package com.mebit.app.navigation

import com.mebit.app.StoreProbe
import com.mebit.app.Why
import com.mebit.app.data.Cause

/** Where the app starts, decided before the first frame and before the nav host exists. */
sealed interface Boot {
    /** Welcome: nothing saved, or this phone cannot hold a wallet. */
    data object Onboarding : Boot

    /** A wallet is saved: the owner check comes first. */
    data object Locked : Boot

    /** Saved but can never open: recovery only. */
    class Broken(val cause: Cause) : Boot

    /** Session installed: show the nav host here. */
    class Open(val route: MovieRoute) : Boot
}

/** Pure. Never offers Welcome on a guess: an unknown error may be hiding a saved wallet, so it stays Locked. */
fun coldStart(probe: StoreProbe): Boot = when (probe) {
    StoreProbe.Empty -> Boot.Onboarding
    StoreProbe.Present -> Boot.Locked
    StoreProbe.KeyGone -> Boot.Broken(Cause.KeyGone)
    is StoreProbe.Unavailable -> when (probe.why) {
        Why.NoScreenLock, Why.OsTooOld, Why.NotWired -> Boot.Onboarding // this phone cannot hold a wallet, so none can be hiding
        Why.TryLater, Why.Other -> Boot.Locked
    }
}

/**
 * Pure. A wallet whose address type the user has not confirmed opens at Address Type, before App Lock, Home or any address. Otherwise
 * the unbacked-up wallet lands where the backup nudge lives. The Back stack holds only this route.
 */
fun startRoute(backupConfirmed: Boolean, addressTypeConfirmed: Boolean): MovieRoute = when {
    !addressTypeConfirmed -> MovieRoute.AddressType
    backupConfirmed -> MovieRoute.S1HomeSingleSig
    else -> MovieRoute.HomeFirstRun
}
