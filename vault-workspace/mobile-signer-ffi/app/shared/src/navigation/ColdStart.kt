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

/** Pure. The unbacked-up wallet lands where the backup nudge lives; the Back stack holds only this route. */
fun startRoute(backupConfirmed: Boolean): MovieRoute =
    if (backupConfirmed) MovieRoute.S1HomeSingleSig else MovieRoute.HomeFirstRun
