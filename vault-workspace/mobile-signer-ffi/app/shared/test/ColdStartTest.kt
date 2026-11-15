package com.mebit.app.navigation

import com.mebit.app.StoreProbe
import com.mebit.app.Why
import com.mebit.app.data.Cause
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ColdStartTest {
    @Test
    fun emptyStartsOnboarding() = assertEquals(Boot.Onboarding, coldStart(StoreProbe.Empty))

    @Test
    fun presentStartsLocked() = assertEquals(Boot.Locked, coldStart(StoreProbe.Present))

    @Test
    fun keyGoneStartsBroken() = assertEquals(Cause.KeyGone, assertIs<Boot.Broken>(coldStart(StoreProbe.KeyGone)).cause)

    @Test
    fun noScreenLockOsTooOldAndNotWiredStartOnboarding() {
        for (why in listOf(Why.NoScreenLock, Why.OsTooOld, Why.NotWired)) {
            assertEquals(Boot.Onboarding, coldStart(StoreProbe.Unavailable(why)), "$why")
        }
    }

    @Test
    fun unknownErrorAndTryLaterStayLocked() {
        for (why in listOf(Why.TryLater, Why.Other)) {
            assertEquals(Boot.Locked, coldStart(StoreProbe.Unavailable(why)), "$why")
        }
    }

    @Test
    fun aWalletPastAddressTypeOpensTheWalletList() {
        assertEquals(MovieRoute.VaultList, startRoute(addressTypeConfirmed = true))
    }

    // A restart while the user was choosing never finalizes the type: the wallet opens on Address Type.
    @Test
    fun startRouteSendsAWalletWithAnUnconfirmedAddressTypeBackToAddressType() {
        assertEquals(MovieRoute.AddressType, startRoute(addressTypeConfirmed = false))
    }
}
