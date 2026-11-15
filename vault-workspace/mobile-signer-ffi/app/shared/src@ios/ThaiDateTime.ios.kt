package com.mebit.app

import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.Foundation.timeZoneWithName

actual fun utcOffsetSeconds(epochSeconds: Long, timeZoneId: String?): Int {
    val zone = if (timeZoneId == null) NSTimeZone.localTimeZone else NSTimeZone.timeZoneWithName(timeZoneId)
    // timeZoneWithName answers null for an id it does not know, which means UTC.
    return zone?.secondsFromGMTForDate(NSDate.dateWithTimeIntervalSince1970(epochSeconds.toDouble()))?.toInt() ?: 0
}
