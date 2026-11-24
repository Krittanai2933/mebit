package com.mebit.app.ui.components

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class BottomBarInsetTest {
    // The tabs end 60 dp below the bar's top edge. What is left under them must hold the board's 11 dp and the whole system strip.
    @Test
    fun theTabsClearEverySystemStrip() {
        listOf(
            0.dp, // no strip: an old Android phone with hardware keys
            24.dp, // Android gesture handle
            34.dp, // iPhone home indicator
            48.dp, // Android three-button bar
        ).forEach { strip ->
            assertEquals(11.dp + strip, barHeightOver(strip) - 60.dp, "strip $strip")
        }
    }
}
