package com.mebit.app.data

import com.mebit.app.Marker
import com.mebit.app.StoreProbe
import com.mebit.app.Why
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ReinstallPolicyTest {
    /** The design's table. Every pair not listed here must be None. */
    private val table: Map<Pair<Marker, StoreProbe>, ReinstallAction> = mapOf(
        (Marker.Absent to StoreProbe.Empty) to ReinstallAction.MarkEmpty,
        (Marker.Absent to StoreProbe.Present) to ReinstallAction.WipeOrphan,
        (Marker.Absent to StoreProbe.KeyGone) to ReinstallAction.WipeOrphan,
        (Marker.Empty to StoreProbe.Present) to ReinstallAction.MarkKept,
        (Marker.Kept to StoreProbe.Empty) to ReinstallAction.Vanished,
    )

    /** Unavailable is a plain class, so it cannot be a key of the table: the one Unavailable cell is written here. */
    private fun expected(marker: Marker, probe: StoreProbe): ReinstallAction =
        if (marker == Marker.Kept && probe is StoreProbe.Unavailable && probe.why == Why.NoScreenLock) {
            ReinstallAction.Vanished // the item is gone and the phone has no passcode to hold another
        } else {
            table[marker to probe] ?: ReinstallAction.None
        }

    @Test
    fun everyMarkerAndProbePairMatchesTheTable() {
        for (marker in Marker.entries) {
            for (probe in EVERY_PROBE) {
                assertEquals(expected(marker, probe), reinstallPolicy(marker, probe), "$marker, ${label(probe)}")
            }
        }
    }

    @Test
    fun neverWipesOnDoubt() {
        for (marker in Marker.entries) {
            for (why in Why.entries) {
                val goneForSure = marker == Marker.Kept && why == Why.NoScreenLock
                assertEquals(if (goneForSure) ReinstallAction.Vanished else ReinstallAction.None, reinstallPolicy(marker, StoreProbe.Unavailable(why)), "$marker, $why")
            }
        }
    }

    @Test
    fun wipesOnlyWhenTheMarkerIsAbsent() {
        for (marker in Marker.entries - Marker.Absent) {
            for (probe in EVERY_PROBE) {
                assertNotEquals<ReinstallAction>(ReinstallAction.WipeOrphan, reinstallPolicy(marker, probe), "$marker, ${label(probe)}")
            }
        }
    }
}
