package com.blurabbit.hdmap.sensors

import com.blurabbit.hdmap.domain.sensor.GnssSample
import com.blurabbit.hdmap.domain.sensor.ImuSample
import com.blurabbit.hdmap.domain.sensor.OrientationSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/** One timestamped sample ready for the recorder. Typed (no protobuf) so it serializes to JSONL. */
sealed interface SensorRecord {
    val unifiedTsNs: Long
}

data class GnssRecord(val sample: GnssSample) : SensorRecord {
    override val unifiedTsNs: Long get() = sample.unifiedNs
}

data class ImuRecord(val sample: ImuSample) : SensorRecord {
    override val unifiedTsNs: Long get() = sample.unifiedNs
}

data class OrientationRecord(val sample: OrientationSample) : SensorRecord {
    override val unifiedTsNs: Long get() = sample.unifiedNs
}

/** Rolling data-quality snapshot for a source, surfaced to the dashboard. */
data class SensorHealthSnapshot(
    val sourceId: String,
    val expectedHz: Double,
    val actualHz: Double,
    val droppedSamples: Long,
    val driftMs: Double,
    val healthy: Boolean,
)

/**
 * The single seam every data producer implements. New hardware (RTK GNSS, OBD-II, CAN, LiDAR,
 * USB camera) is added by writing a [SensorSource] and binding it `@IntoSet` — the recorder and
 * health pipeline pick it up with no other changes.
 */
interface SensorSource {
    /** Stable identifier, e.g. "imu", "gnss". */
    val id: String

    /** Whether the underlying hardware exists on this device. */
    fun isAvailable(): Boolean = true

    /** Begin producing records on [scope]; the flow completes when [stop] is called. */
    fun start(scope: CoroutineScope): Flow<SensorRecord>

    fun stop()

    fun health(): SensorHealthSnapshot
}
