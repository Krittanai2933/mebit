package com.mebit.app

import kotlin.test.Test
import kotlin.test.assertEquals
import platform.Foundation.NSTimeZone
import platform.Foundation.localTimeZone

private const val JAN_15_2026_NOON_UTC = 1_768_478_400L
private const val JUL_15_2026_NOON_UTC = 1_784_116_800L

class ThaiDateTimeIosTest {
    @Test
    fun bangkokIsSevenHoursAheadAllYear() {
        assertEquals(25_200, utcOffsetSeconds(JAN_15_2026_NOON_UTC, "Asia/Bangkok"))
        assertEquals(25_200, utcOffsetSeconds(JUL_15_2026_NOON_UTC, "Asia/Bangkok"))
    }

    @Test
    fun newYorkMovesAnHourForSummerTime() {
        assertEquals(-18_000, utcOffsetSeconds(JAN_15_2026_NOON_UTC, "America/New_York"))
        assertEquals(-14_400, utcOffsetSeconds(JUL_15_2026_NOON_UTC, "America/New_York"))
    }

    @Test
    fun anUnknownZoneIsUtc() {
        assertEquals(0, utcOffsetSeconds(JAN_15_2026_NOON_UTC, "Nowhere/Land"))
    }

    @Test
    fun nullIsTheDeviceZone() {
        val device = NSTimeZone.localTimeZone.name
        assertEquals(utcOffsetSeconds(JUL_15_2026_NOON_UTC, device), utcOffsetSeconds(JUL_15_2026_NOON_UTC, null))
    }

    @Test
    fun printsTheBoardSampleForABangkokId() {
        assertEquals("23 ต.ค. 2569 · 14:32 น.", formatThaiDateTime(1_792_740_720, "Asia/Bangkok"))
    }
}
