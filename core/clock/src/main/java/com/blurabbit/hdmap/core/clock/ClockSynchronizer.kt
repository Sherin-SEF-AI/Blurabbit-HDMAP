package com.blurabbit.hdmap.core.clock

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps each sensor's native timestamp epoch onto the unified [MonotonicClock] timebase.
 *
 * Why this is necessary:
 *  - `SensorEvent.timestamp` is nanoseconds but its epoch is device-dependent (often, but not
 *    guaranteed to be, elapsedRealtime). We measure the offset at delivery time.
 *  - `Location.getElapsedRealtimeNanos()` is already on the unified clock → offset ≈ 0.
 *  - Camera frame timestamps depend on `SENSOR_INFO_TIMESTAMP_SOURCE`.
 *
 * For each source we estimate `offset = unifiedNow - sourceTs` observed at delivery. Because
 * delivery latency only ever makes the observed offset *larger*, the true offset is best
 * approximated by the running **minimum** observed offset, lightly smoothed to absorb jitter.
 */
@Singleton
class ClockSynchronizer @Inject constructor(
    private val clock: MonotonicClock,
) {
    private data class Estimate(
        @Volatile var offsetNs: Long,
        @Volatile var minOffsetNs: Long,
        @Volatile var samples: Long,
    )

    private val estimates = ConcurrentHashMap<String, Estimate>()

    /**
     * Record an observation for [sourceId]: a sample carrying [sourceTsNs] was just delivered.
     * Returns the unified timestamp for this very sample.
     */
    fun observeAndConvert(sourceId: String, sourceTsNs: Long): Long {
        val now = clock.nowNanos()
        val observed = now - sourceTsNs
        val est = estimates.compute(sourceId) { _, prev ->
            if (prev == null) {
                Estimate(offsetNs = observed, minOffsetNs = observed, samples = 1)
            } else {
                val newMin = minOf(prev.minOffsetNs, observed)
                // EMA toward the minimum offset (the latency-free estimate).
                val smoothed = (prev.offsetNs * (EMA_DEN - 1) + newMin) / EMA_DEN
                prev.offsetNs = smoothed
                prev.minOffsetNs = newMin
                prev.samples += 1
                prev
            }
        }!!
        return sourceTsNs + est.offsetNs
    }

    /** Convert a source timestamp using the current offset estimate without updating it. */
    fun convert(sourceId: String, sourceTsNs: Long): Long {
        val est = estimates[sourceId] ?: return sourceTsNs
        return sourceTsNs + est.offsetNs
    }

    /** Declare that a source is already on the unified clock (e.g. GNSS elapsedRealtimeNanos). */
    fun registerIdentitySource(sourceId: String) {
        estimates[sourceId] = Estimate(offsetNs = 0, minOffsetNs = 0, samples = 0)
    }

    fun currentOffsetNs(sourceId: String): Long? = estimates[sourceId]?.offsetNs

    fun reset() = estimates.clear()

    private companion object {
        const val EMA_DEN = 32L
    }
}
