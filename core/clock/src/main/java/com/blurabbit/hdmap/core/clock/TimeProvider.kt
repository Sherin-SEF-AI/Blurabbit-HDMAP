package com.blurabbit.hdmap.core.clock

import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Indirection over the system clocks so clock logic is unit-testable without Android.
 *
 * [elapsedRealtimeNanos] is the ONLY timebase used for cross-sensor synchronization.
 * [currentTimeMillis] (wall clock) is exposed solely for human-facing display / metadata
 * and must never be used to order or align sensor streams.
 */
interface TimeProvider {
    /** Nanoseconds since boot, including deep sleep. The unified synchronization timebase. */
    fun elapsedRealtimeNanos(): Long

    /** Wall-clock milliseconds since the Unix epoch. Display / metadata only. */
    fun currentTimeMillis(): Long
}

@Singleton
class SystemTimeProvider @Inject constructor() : TimeProvider {
    override fun elapsedRealtimeNanos(): Long = SystemClock.elapsedRealtimeNanos()
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}
