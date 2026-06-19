package com.blurabbit.hdmap.core.clock

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ClockSynchronizerTest {

    private class FakeTime(var nanos: Long = 0) : TimeProvider {
        override fun elapsedRealtimeNanos(): Long = nanos
        override fun currentTimeMillis(): Long = nanos / 1_000_000
    }

    @Test
    fun identitySource_convertsWithZeroOffset() {
        val time = FakeTime(1_000)
        val sync = ClockSynchronizer(MonotonicClock(time))
        sync.registerIdentitySource("gnss")
        assertThat(sync.convert("gnss", 500)).isEqualTo(500)
    }

    @Test
    fun observeAndConvert_estimatesOffsetTowardMinimum() {
        val time = FakeTime()
        val sync = ClockSynchronizer(MonotonicClock(time))
        // Source clock runs 1_000_000 ns behind the unified clock; delivery adds jitter.
        time.nanos = 5_000_000
        val unified = sync.observeAndConvert("imu", sourceTsNs = 4_000_000)
        // First observation adopts the observed offset exactly.
        assertThat(unified).isEqualTo(5_000_000)
        // Feed more samples; converted timestamps stay close to the unified clock.
        for (i in 1..50) {
            time.nanos = 5_000_000 + i * 1_000_000L
            val src = time.nanos - 1_000_000L
            val conv = sync.observeAndConvert("imu", src)
            assertThat(conv).isAtLeast(src)
        }
    }
}
