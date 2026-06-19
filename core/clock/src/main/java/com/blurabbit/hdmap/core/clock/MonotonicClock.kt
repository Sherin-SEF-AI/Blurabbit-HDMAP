package com.blurabbit.hdmap.core.clock

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single, app-wide source of synchronized time. Every sensor sample's unified timestamp
 * ultimately derives from [nowNanos]. Backed by `SystemClock.elapsedRealtimeNanos()`.
 */
@Singleton
class MonotonicClock @Inject constructor(
    private val timeProvider: TimeProvider,
) {
    /** boot epoch in ns: epoch_now − elapsed_now. Captured per trip so unified ns → real wall time. */
    @Volatile private var bootEpochNanos: Long = Long.MIN_VALUE

    /** Current unified time in nanoseconds (elapsed realtime since boot). */
    fun nowNanos(): Long = timeProvider.elapsedRealtimeNanos()

    /**
     * Snapshot the offset between the wall clock and the unified (elapsed-realtime) clock. Call once
     * at the start of a recording so [toEpochNanos] maps the monotonic timebase onto real epoch time.
     */
    fun captureEpochAnchor() {
        bootEpochNanos = timeProvider.currentTimeMillis() * 1_000_000L - timeProvider.elapsedRealtimeNanos()
    }

    /** Convert a unified (elapsed-realtime) nanosecond timestamp to epoch nanoseconds. */
    fun toEpochNanos(unifiedElapsedNanos: Long): Long {
        if (bootEpochNanos == Long.MIN_VALUE) captureEpochAnchor()
        return bootEpochNanos + unifiedElapsedNanos
    }

    /** Wall-clock epoch milliseconds for "now" — human-readable trip metadata only. */
    fun wallEpochMillisNow(): Long = timeProvider.currentTimeMillis()
}
