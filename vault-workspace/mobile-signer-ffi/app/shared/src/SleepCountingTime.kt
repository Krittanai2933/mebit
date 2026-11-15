package com.mebit.app

import kotlin.time.AbstractLongTimeSource
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/**
 * Time that keeps counting while the phone is in deep sleep, which the 60-second lock and the waits after wrong PINs run on.
 * [TimeSource.Monotonic] reads System.nanoTime on Android, which stops in deep sleep, so a minute asleep would not count.
 */
internal expect val SleepCountingTime: TimeSource

/** A [TimeSource] over [nanos], a reading in nanoseconds from any fixed point. */
internal class NanosTimeSource(private val nanos: () -> Long) : AbstractLongTimeSource(DurationUnit.NANOSECONDS) {
    override fun read(): Long = nanos()
}
