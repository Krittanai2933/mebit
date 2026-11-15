package com.mebit.app

import java.util.TimeZone

actual fun utcOffsetSeconds(epochSeconds: Long, timeZoneId: String?): Int {
    // getTimeZone answers GMT for an id it does not know.
    val zone = if (timeZoneId == null) TimeZone.getDefault() else TimeZone.getTimeZone(timeZoneId)
    return zone.getOffset(epochSeconds * 1000) / 1000
}
