package com.blurabbit.hdmap.domain.model

/** Lifecycle of a collection trip, from live capture through HD-map generation. */
enum class TripStatus { RECORDING, COMPLETED, PROCESSING, MAPPED, FAILED }

/**
 * A single collection drive. Raw sensor logs live on disk (per-trip JSONL + sidecar video); this
 * record holds the indexed metadata and rollup statistics shown in the UI and used by processing.
 */
data class Trip(
    val id: String,
    val name: String,
    val status: TripStatus,
    val startWallMs: Long,
    val endWallMs: Long? = null,
    val startElapsedNs: Long = 0,
    val distanceMeters: Double = 0.0,
    val durationMs: Long = 0,
    val maxSpeedMps: Double = 0.0,
    val gpsSamples: Long = 0,
    val imuSamples: Long = 0,
    val frameCount: Long = 0,
    val featureCount: Int = 0,
    val logDir: String = "",
    val notes: String = "",
)
