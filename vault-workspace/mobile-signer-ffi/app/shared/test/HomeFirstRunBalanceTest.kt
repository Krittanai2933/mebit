package com.mebit.app.feature.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals

class HomeFirstRunBalanceTest {
    // Commit f6734a2 moved every other money line to formatBtcCompact, and this hero kept cutting 379,087 sats to 0.0037.
    @Test
    fun theFirstRunHeroShowsEverySatThatMattersAndADashUntilTheChainAnswers() {
        assertEquals("0.00379087", heroBalanceText(379_087, live = true))
        assertEquals("0.00019436", heroBalanceText(19_436, live = true))
        assertEquals("1.00009999", heroBalanceText(100_009_999, live = true), "a whole part never hides the sats")
        assertEquals("0.0125", heroBalanceText(1_250_000, live = true), "a round amount keeps four places")
        assertEquals("0.0000", heroBalanceText(0, live = true))
        assertEquals("—", heroBalanceText(379_087, live = false))
    }
}
