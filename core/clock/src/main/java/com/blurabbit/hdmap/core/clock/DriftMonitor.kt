package com.blurabbit.hdmap.core.clock

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** A timestamp anomaly surfaced to the data-quality pipeline. */
data class TimestampWarning(
    val sourceId: String,
    val kind: Kind,
    val detail: String,
    val unifiedTsNs: Long,
) {
    enum class Kind { DRIFT, NON_MONOTONIC, FUTURE_TIMESTAMP }
}

/**
 * Validates the timestamps flowing through the recorder:
 *  - **monotonicity** per source (out-of-order samples are flagged),
 *  - **future** timestamps (ahead of the unified clock beyond tolerance),
 *  - **drift** of a source's clock offset over time.
 *
 * Emits [TimestampWarning]s; the recorder forwards them to sensor health without dropping data.
 */
@Singleton
class DriftMonitor @Inject constructor(
    private val clock: MonotonicClock,
) {
    private val lastTsNs = ConcurrentHashMap<String, Long>()
    private val baselineOffsetNs = ConcurrentHashMap<String, Long>()

    private val _warnings = MutableSharedFlow<TimestampWarning>(extraBufferCapacity = 64)
    val warnings: SharedFlow<TimestampWarning> = _warnings.asSharedFlow()

    /**
     * @param unifiedTsNs the already-converted unified timestamp of the sample
     * @param currentOffsetNs the source's current offset estimate (from [ClockSynchronizer])
     * @return true if the sample looks valid; false if it should be treated as suspect
     */
    fun validate(sourceId: String, unifiedTsNs: Long, currentOffsetNs: Long?): Boolean {
        var ok = true
        val now = clock.nowNanos()

        if (unifiedTsNs > now + FUTURE_TOLERANCE_NS) {
            emit(sourceId, TimestampWarning.Kind.FUTURE_TIMESTAMP,
                "ts is ${(unifiedTsNs - now)}ns ahead of clock", unifiedTsNs)
            ok = false
        }

        val prev = lastTsNs.put(sourceId, unifiedTsNs)
        if (prev != null && unifiedTsNs < prev) {
            emit(sourceId, TimestampWarning.Kind.NON_MONOTONIC,
                "ts went backwards by ${prev - unifiedTsNs}ns", unifiedTsNs)
            ok = false
        }

        if (currentOffsetNs != null) {
            val baseline = baselineOffsetNs.putIfAbsent(sourceId, currentOffsetNs) ?: currentOffsetNs
            val drift = kotlin.math.abs(currentOffsetNs - baseline)
            if (drift > DRIFT_THRESHOLD_NS) {
                emit(sourceId, TimestampWarning.Kind.DRIFT,
                    "offset drifted ${drift / 1_000_000}ms from baseline", unifiedTsNs)
                baselineOffsetNs[sourceId] = currentOffsetNs
            }
        }
        return ok
    }

    fun reset() {
        lastTsNs.clear()
        baselineOffsetNs.clear()
    }

    private fun emit(sourceId: String, kind: TimestampWarning.Kind, detail: String, ts: Long) {
        _warnings.tryEmit(TimestampWarning(sourceId, kind, detail, ts))
    }

    private companion object {
        const val FUTURE_TOLERANCE_NS = 5_000_000L      // 5 ms
        const val DRIFT_THRESHOLD_NS = 20_000_000L      // 20 ms
    }
}
