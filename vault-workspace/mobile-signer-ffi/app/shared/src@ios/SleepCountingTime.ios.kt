package com.mebit.app

import kotlin.time.TimeSource
import platform.posix.CLOCK_MONOTONIC_RAW
import platform.posix.clock_gettime_nsec_np

// CLOCK_MONOTONIC_RAW counts sleep. CLOCK_UPTIME_RAW, the clock of mach_absolute_time, stops in it.
internal actual val SleepCountingTime: TimeSource = NanosTimeSource { clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW.toUInt()).toLong() }
