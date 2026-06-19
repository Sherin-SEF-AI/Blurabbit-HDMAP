package com.blurabbit.hdmap.capture

import com.blurabbit.hdmap.sensors.SensorHealthSnapshot

enum class RecordingPhase { IDLE, RECORDING, PAUSED, STOPPING }

/** Live snapshot of the recorder, observed by the dashboard at ~2 Hz. */
data class RecordingState(
    val phase: RecordingPhase = RecordingPhase.IDLE,
    val tripId: String? = null,
    val tripName: String = "",
    val startElapsedNs: Long = 0,
    val durationMs: Long = 0,
    val currentSpeedMps: Double = 0.0,
    val maxSpeedMps: Double = 0.0,
    val distanceMeters: Double = 0.0,
    val satellitesUsed: Int = 0,
    val satellitesTotal: Int = 0,
    val gpsSamples: Long = 0,
    val imuSamples: Long = 0,
    val frameCount: Long = 0,
    val detections: Long = 0,
    val droppedWrites: Long = 0,
    val warnings: List<String> = emptyList(),
    val sensorHealth: List<SensorHealthSnapshot> = emptyList(),
) {
    val isActive: Boolean get() = phase == RecordingPhase.RECORDING || phase == RecordingPhase.PAUSED
}
