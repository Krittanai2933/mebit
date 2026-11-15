package com.mebit.app

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

private const val JAN_15_2026_NOON_UTC = 1_768_478_400L
private const val JUL_15_2026_NOON_UTC = 1_784_116_800L
private const val JAN_1_1900_UTC = -2_208_988_800L
private const val JAN_1_2100_UTC = 4_102_444_800L

// Typed again on purpose, so that a typo in the shipped table fails the cross-check.
private val expectedMonths =
    listOf("ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.", "ก.ค.", "ส.ค.", "ก.ย.", "ต.ค.", "พ.ย.", "ธ.ค.")

class ThaiDateTimeAndroidTest {
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
    fun anUnknownZoneIsUtcNotTheDeviceZone() = withDeviceZone("Asia/Bangkok") {
        assertEquals(0, utcOffsetSeconds(JAN_15_2026_NOON_UTC, "Nowhere/Land"))
    }

    @Test
    fun nullIsTheDeviceZone() = withDeviceZone("America/New_York") {
        assertEquals(-18_000, utcOffsetSeconds(JAN_15_2026_NOON_UTC, null))
        assertEquals(-14_400, utcOffsetSeconds(JUL_15_2026_NOON_UTC, null))
    }

    @Test
    fun printsTheBoardSampleForABangkokId() {
        assertEquals("23 ต.ค. 2569 · 14:32 น.", formatThaiDateTime(1_792_740_720, "Asia/Bangkok"))
    }

    @Test
    fun printsTheShortBoardSampleForABangkokId() {
        assertEquals("18 ส.ค. 2569 · 14:02", formatThaiDateTimeShort(1_787_036_520, "Asia/Bangkok"))
        assertEquals("18 ส.ค. 2569 · 14:02 น.", formatThaiDateTime(1_787_036_520, "Asia/Bangkok"))
    }

    @Test
    fun theShortFormIsJavaTimesTextWithoutTheSuffixForRandomInstants() {
        val random = Random(2569)
        repeat(2_000) {
            val instant = random.nextLong(JAN_1_1900_UTC, JAN_1_2100_UTC)
            for (zone in listOf("Asia/Bangkok", "America/New_York")) {
                assertEquals(javaTimeText(instant, zone).removeSuffix(" น."), formatThaiDateTimeShort(instant, zone), "$instant in $zone")
            }
        }
    }

    @Test
    fun matchesJavaTimeForRandomInstants() {
        val random = Random(2569)
        repeat(2_000) {
            val instant = random.nextLong(JAN_1_1900_UTC, JAN_1_2100_UTC)
            for (zone in listOf("Asia/Bangkok", "America/New_York")) {
                assertEquals(javaTimeText(instant, zone), formatThaiDateTime(instant, zone), "$instant in $zone")
            }
        }
    }

    private fun javaTimeText(instant: Long, zone: String): String {
        val local = ZonedDateTime.ofInstant(Instant.ofEpochSecond(instant), ZoneId.of(zone))
        val hour = local.hour.toString().padStart(2, '0')
        val minute = local.minute.toString().padStart(2, '0')
        return "${local.dayOfMonth} ${expectedMonths[local.monthValue - 1]} ${local.year + 543} · $hour:$minute น."
    }

    private fun withDeviceZone(id: String, block: () -> Unit) {
        val before = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(id))
        try {
            block()
        } finally {
            TimeZone.setDefault(before)
        }
    }
}
