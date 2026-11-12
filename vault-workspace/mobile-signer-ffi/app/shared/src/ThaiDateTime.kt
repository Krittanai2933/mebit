package com.mebit.app

private val thaiMonths =
    listOf("ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.", "ก.ค.", "ส.ค.", "ก.ย.", "ต.ค.", "พ.ย.", "ธ.ค.")

/**
 * The offset from UTC in seconds that [timeZoneId] has at [epochSeconds], daylight saving included.
 * A null [timeZoneId] means the device's current zone.
 * An id the platform does not know gives 0, which is UTC, on both platforms.
 */
expect fun utcOffsetSeconds(epochSeconds: Long, timeZoneId: String?): Int

/**
 * [epochSeconds] as a Thai local date and time, such as `23 ต.ค. 2569 · 14:32 น.`.
 * The day has no padding, the year is the Buddhist year and the time is 24-hour.
 * The text is the same on both platforms because no platform locale or calendar takes part.
 * [timeZoneId] is an IANA id such as `Asia/Bangkok`. Null means the device's current zone and an unknown id means UTC.
 */
fun formatThaiDateTime(epochSeconds: Long, timeZoneId: String? = null): String =
    thaiDateTime(epochSeconds, utcOffsetSeconds(epochSeconds, timeZoneId))

/**
 * [formatThaiDateTime] without the closing ` น.`, such as `23 ต.ค. 2569 · 14:32`, for the rows where the board leaves it out.
 * The day, the year, the time and [timeZoneId] work as they do there.
 */
fun formatThaiDateTimeShort(epochSeconds: Long, timeZoneId: String? = null): String =
    thaiDateTime(epochSeconds, utcOffsetSeconds(epochSeconds, timeZoneId), withSuffix = false)

/** [epochSeconds] as the local 24-hour time alone, such as `14:32`, the time [formatThaiDateTime] gives. [timeZoneId] works as it does there. */
fun formatThaiTime(epochSeconds: Long, timeZoneId: String? = null): String = thaiTime(epochSeconds, utcOffsetSeconds(epochSeconds, timeZoneId))

// Own date maths (Howard Hinnant's civil_from_days) so that no platform locale or calendar decides the Buddhist year.
internal fun thaiDateTime(epochSeconds: Long, utcOffsetSeconds: Int, withSuffix: Boolean = true): String {
    val local = epochSeconds + utcOffsetSeconds
    val daysSinceYear0March1 = local.floorDiv(86_400) + 719_468
    val era = daysSinceYear0March1.floorDiv(146_097)
    val dayOfEra = (daysSinceYear0March1 - era * 146_097).toInt()
    val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val monthFromMarch = (5 * dayOfYear + 2) / 153
    val day = dayOfYear - (153 * monthFromMarch + 2) / 5 + 1
    val month = if (monthFromMarch < 10) monthFromMarch + 3 else monthFromMarch - 9
    val year = era * 400 + yearOfEra + (if (month <= 2) 1 else 0)
    return "$day ${thaiMonths[month - 1]} ${year + 543} · ${thaiTime(epochSeconds, utcOffsetSeconds)}" + if (withSuffix) " น." else ""
}

/** The 24-hour local time alone, such as `14:32`. */
internal fun thaiTime(epochSeconds: Long, utcOffsetSeconds: Int): String {
    val secondOfDay = (epochSeconds + utcOffsetSeconds).mod(86_400)
    return (secondOfDay / 3_600).toString().padStart(2, '0') + ":" + (secondOfDay % 3_600 / 60).toString().padStart(2, '0')
}
