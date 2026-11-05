package com.mebit.app

import android.os.SystemClock
import kotlin.time.TimeSource

internal actual val SleepCountingTime: TimeSource = NanosTimeSource(SystemClock::elapsedRealtimeNanos)
