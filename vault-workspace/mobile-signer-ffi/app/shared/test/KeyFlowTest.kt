package com.mebit.app.navigation

import com.mebit.app.data.KeySourceLabel
import kotlin.test.Test
import kotlin.test.assertEquals

class KeyFlowTest {
    private val keyringScreens = listOf(
        MovieRoute.AddKey,
        MovieRoute.Keyring,
        MovieRoute.VaultList,
    )

    @Test
    fun keySourceStartsOnboardingFromEitherFlow() {
        KeyFlow.entries.forEach { flow ->
            assertEquals(KeyFlow.Onboarding, keyFlowAfter(MovieRoute.KeySource, flow), flow.name)
        }
    }

    @Test
    fun theKeyringScreensAndTheWalletListEndOnboardingEvenFromIt() {
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
    fun reconnectReturnsToTheSameSourceInBothFlows() {
        KeyFlow.entries.forEach { flow ->
            listOf(KeySourceLabel.Jade, KeySourceLabel.TrezorSafe7).forEach { source ->
                val route = routeBeforeVerify(source, flow)
                assertEquals(MovieRoute.HardwareWallet, route)
                assertEquals(flow, keyFlowAfter(route, flow))
            }
            assertEquals(MovieRoute.PairMebitOther, routeBeforeVerify(KeySourceLabel.MebitOther, flow))
        }
        assertEquals(MovieRoute.KeySource, routeBeforeVerify(KeySourceLabel.ThisDevice, KeyFlow.Onboarding))
        assertEquals(MovieRoute.AddKey, routeBeforeVerify(KeySourceLabel.ThisDevice, KeyFlow.Keyring))
    }

    @Test
    fun hardwareRetryPopsVerificationAndBackReturnsToItsOwningEntry() {
        listOf(MovieRoute.KeySource, MovieRoute.AddKey).forEach { entry ->
            val original = listOf(entry, MovieRoute.HardwareWallet)
            listOf(
                original + MovieRoute.VerifyKey,
                original + listOf(MovieRoute.VerifyKey, MovieRoute.T14VerifyMismatch),
            ).forEach { stack ->
                val retried = keyRetryBackStack(stack, MovieRoute.HardwareWallet)
                assertEquals(original, retried)
                assertEquals(entry, retried.dropLast(1).last())
            }
        }
    }

    @Test
    fun catalogRetryDropsStaleVerificationBeforeAddingItsSource() {
        assertEquals(
            listOf(MovieRoute.Welcome, MovieRoute.HardwareWallet),
            keyRetryBackStack(listOf(MovieRoute.Welcome, MovieRoute.VerifyKey, MovieRoute.T14VerifyMismatch), MovieRoute.HardwareWallet),
        )
        assertEquals(
            listOf(MovieRoute.AddKey, MovieRoute.PairMebitOther),
            keyRetryBackStack(listOf(MovieRoute.AddKey, MovieRoute.VerifyKey), MovieRoute.PairMebitOther),
        )
    }
}
