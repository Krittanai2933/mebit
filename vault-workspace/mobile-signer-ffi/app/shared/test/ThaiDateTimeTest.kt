package com.mebit.app

import kotlin.test.Test
import kotlin.test.assertEquals

private const val BANGKOK = 25_200

class ThaiDateTimeTest {
    @Test
    fun printsTheBoardSample() {
        assertEquals("23 ต.ค. 2569 · 14:32 น.", thaiDateTime(1_792_740_720, BANGKOK))
    }

    @Test
    fun theShortFormIsTheBoardSampleOfTheHomeRowsWithoutTheSuffix() {
        assertEquals("18 ส.ค. 2569 · 14:02", thaiDateTime(1_787_036_520, BANGKOK, withSuffix = false))
    }

    @Test
    fun theShortFormStillRollsOverAtMidnight() {
        assertEquals("31 ธ.ค. 2569 · 23:59", thaiDateTime(1_798_736_340, BANGKOK, withSuffix = false))
        assertEquals("1 ม.ค. 2570 · 00:00", thaiDateTime(1_798_736_400, BANGKOK, withSuffix = false))
    }

    @Test
    fun theLongFormKeepsItsSuffix() {
        assertEquals("18 ส.ค. 2569 · 14:02 น.", thaiDateTime(1_787_036_520, BANGKOK))
        assertEquals("1 ม.ค. 2570 · 00:00 น.", thaiDateTime(1_798_736_400, BANGKOK, withSuffix = true))
    }

    @Test
    fun midnightOfNewYearStartsTheNextBuddhistYear() {
        assertEquals("31 ธ.ค. 2569 · 23:59 น.", thaiDateTime(1_798_736_340, BANGKOK))
        assertEquals("1 ม.ค. 2570 · 00:00 น.", thaiDateTime(1_798_736_400, BANGKOK))
    }

    @Test
    fun twentyNinthOfFebruaryExistsInALeapYear() {
        assertEquals("29 ก.พ. 2571 · 09:05 น.", thaiDateTime(1_835_402_700, BANGKOK))
        assertEquals("1 มี.ค. 2571 · 00:00 น.", thaiDateTime(1_835_481_600, 0))
    }

    @Test
    fun aCenturyYearIsLeapOnlyWhenDivisibleByFourHundred() {
        assertEquals("29 ก.พ. 2543 · 12:00 น.", thaiDateTime(951_825_600, 0))
        assertEquals("1 มี.ค. 2543 · 00:00 น.", thaiDateTime(951_868_800, 0))
        assertEquals("28 ก.พ. 2443 · 23:59 น.", thaiDateTime(-2_203_891_260, 0))
        assertEquals("1 มี.ค. 2443 · 00:00 น.", thaiDateTime(-2_203_891_200, 0))
        assertEquals("28 ก.พ. 2643 · 23:59 น.", thaiDateTime(4_107_542_340, 0))
        assertEquals("1 มี.ค. 2643 · 00:00 น.", thaiDateTime(4_107_542_400, 0))
    }

    @Test
    fun theUnixEpochIsSevenInTheMorningInBangkok() {
        assertEquals("1 ม.ค. 2513 · 07:00 น.", thaiDateTime(0, BANGKOK))
    }

    @Test
    fun aNegativeEpochCountsBackFromTheUnixEpoch() {
        assertEquals("1 ม.ค. 2512 · 00:00 น.", thaiDateTime(-31_536_000, 0))
        assertEquals("1 ม.ค. 2512 · 14:32 น.", thaiDateTime(-31_483_680, 0))
        assertEquals("31 ธ.ค. 2512 · 23:59 น.", thaiDateTime(-1, 0))
    }

    @Test
    fun anOffsetThatCrossesMidnightChangesTheDay() {
        assertEquals("1 ม.ค. 2570 · 00:00 น.", thaiDateTime(1_798_736_400, BANGKOK))
        assertEquals("31 ธ.ค. 2569 · 17:00 น.", thaiDateTime(1_798_736_400, 0))
        assertEquals("31 ธ.ค. 2569 · 19:00 น.", thaiDateTime(1_798_761_600, -18_000))
    }

    @Test
    fun everyMonthHasItsThaiAbbreviation() {
        val fifthOfEachMonth = listOf(
            1_767_603_900L to "5 ม.ค. 2569 · 09:05 น.",
            1_770_282_300L to "5 ก.พ. 2569 · 09:05 น.",
            1_772_701_500L to "5 มี.ค. 2569 · 09:05 น.",
            1_775_379_900L to "5 เม.ย. 2569 · 09:05 น.",
            1_777_971_900L to "5 พ.ค. 2569 · 09:05 น.",
            1_780_650_300L to "5 มิ.ย. 2569 · 09:05 น.",
            1_783_242_300L to "5 ก.ค. 2569 · 09:05 น.",
            1_785_920_700L to "5 ส.ค. 2569 · 09:05 น.",
            1_788_599_100L to "5 ก.ย. 2569 · 09:05 น.",
            1_791_191_100L to "5 ต.ค. 2569 · 09:05 น.",
            1_793_869_500L to "5 พ.ย. 2569 · 09:05 น.",
            1_796_461_500L to "5 ธ.ค. 2569 · 09:05 น.",
        )
        fifthOfEachMonth.forEach { (epoch, text) -> assertEquals(text, thaiDateTime(epoch, 0)) }
    }
}
