package com.mebit.app.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

class KeyFlowTest {
    private val keyringScreens = listOf(
        MovieRoute.AddKey,
        MovieRoute.Keyring,
        MovieRoute.KeyringFirstVault,
        MovieRoute.HomeFirstRun,
    )

    @Test
    fun keySourceStartsOnboardingFromEitherFlow() {
        KeyFlow.entries.forEach { flow ->
            assertEquals(KeyFlow.Onboarding, keyFlowAfter(MovieRoute.KeySource, flow), flow.name)
        }
    }

    @Test
    fun theKeyringScreensAndHomeEndOnboardingEvenFromIt() {
        keyringScreens.forEach { route ->
            KeyFlow.entries.forEach { flow ->
                assertEquals(KeyFlow.Keyring, keyFlowAfter(route, flow), "${route.name} from ${flow.name}")
            }
        }
    }

    @Test
    fun anyOtherRouteKeepsTheCurrentFlow() {
        MovieRoute.entries.filter { it != MovieRoute.KeySource && it !in keyringScreens }.forEach { route ->
            KeyFlow.entries.forEach { flow ->
                assertEquals(flow, keyFlowAfter(route, flow), "${route.name} from ${flow.name}")
            }
        }
    }

    @Test
    fun aMatchedFingerprintGoesToTheBackupStepOnlyInOnboarding() {
        assertEquals(MovieRoute.HwBackupConfirm, routeAfterVerify(KeyFlow.Onboarding))
        assertEquals(MovieRoute.Keyring, routeAfterVerify(KeyFlow.Keyring))
    }
}
