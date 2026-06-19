package com.blurabbit.hdmap.domain.sensor

import kotlinx.serialization.Serializable

/**
 * Serializable sensor samples persisted to per-trip JSONL logs during recording and replayed by
 * the mapping pipeline. All timestamps are unified nanoseconds (the [elapsedRealtimeNanos] base).
 */

@Serializable
data class GnssSample(
    val unifiedNs: Long,
    val lat: Double,
    val lon: Double,
    val altM: Double,
    val speedMps: Double,
    val bearingDeg: Double,
    val horizontalAccM: Double,
    val verticalAccM: Double = 0.0,
    val speedAccMps: Double = 0.0,
    val bearingAccDeg: Double = 0.0,
    val satellitesUsed: Int = 0,
    val satellitesTotal: Int = 0,
)

/** Which inertial stream an [ImuSample] came from. */
enum class ImuStream { ACCEL, GYRO, MAG, GRAVITY, LINEAR_ACCEL }

@Serializable
data class ImuSample(
    val unifiedNs: Long,
    val stream: ImuStream,
    val x: Double,
    val y: Double,
    val z: Double,
    val accuracy: Int = 0,
)

@Serializable
data class OrientationSample(
    val unifiedNs: Long,
    val w: Double,
    val x: Double,
    val y: Double,
    val z: Double,
    val headingAccuracyRad: Double = 0.0,
)

/** Per-frame camera metadata (the frame pixels live in the sidecar video/JPEG, not here). */
@Serializable
data class CameraFrameMeta(
    val frameId: Long,
    val unifiedNs: Long,
    val exposureNs: Long = 0,
    val iso: Int = 0,
    val focalLengthMm: Float = 0f,
    val width: Int = 0,
    val height: Int = 0,
)
