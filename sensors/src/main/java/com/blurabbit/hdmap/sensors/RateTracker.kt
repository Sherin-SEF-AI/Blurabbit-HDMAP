package com.blurabbit.hdmap.sensors

import java.util.concurrent.atomic.AtomicLong

/**
 * Cheap, lock-free running rate estimator used by sources to populate [SensorHealthSnapshot].
 * Computes an exponential moving average of sample intervals so the reported Hz reacts to
 * stalls (frame drops / GPS loss) without per-sample allocation.
 */
class RateTracker(private val expectedHz: Double) {
    private val count = AtomicLong(0)
    private val dropped = AtomicLong(0)
    @Volatile private var lastTsNs = 0L
    @Volatile private var emaIntervalNs = if (expectedHz > 0) (1e9 / expectedHz) else 0.0

    fun onSample(unifiedTsNs: Long) {
        count.incrementAndGet()
        val last = lastTsNs
        if (last != 0L && unifiedTsNs > last) {
            val interval = (unifiedTsNs - last).toDouble()
            emaIntervalNs = emaIntervalNs * 0.9 + interval * 0.1
        }
        lastTsNs = unifiedTsNs
    }

    fun onDropped(n: Long = 1) = dropped.addAndGet(n)

    fun actualHz(): Double = if (emaIntervalNs > 0) 1e9 / emaIntervalNs else 0.0
    fun total(): Long = count.get()
    fun droppedCount(): Long = dropped.get()

    fun snapshot(sourceId: String, driftMs: Double): SensorHealthSnapshot {
        val actual = actualHz()
        // Healthy if within 30% of the expected rate (or rate-agnostic sources with expected<=0).
        val healthy = expectedHz <= 0.0 || actual >= expectedHz * 0.7
        return SensorHealthSnapshot(sourceId, expectedHz, actual, dropped.get(), driftMs, healthy)
    }
}
