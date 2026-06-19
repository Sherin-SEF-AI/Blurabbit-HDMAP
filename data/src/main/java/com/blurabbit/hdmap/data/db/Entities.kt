package com.blurabbit.hdmap.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey val id: String,
    val name: String,
    val status: String,
    val startWallMs: Long,
    val endWallMs: Long?,
    val startElapsedNs: Long,
    val distanceMeters: Double,
    val durationMs: Long,
    val maxSpeedMps: Double,
    val gpsSamples: Long,
    val imuSamples: Long,
    val frameCount: Long,
    val featureCount: Int,
    val logDir: String,
    val notes: String,
)

/** The generated HD map for a trip, stored as a serialized [com.blurabbit.hdmap.domain.hdmap.HdMap]. */
@Entity(tableName = "hd_maps")
data class HdMapEntity(
    @PrimaryKey val tripId: String,
    val generatedAtMs: Long,
    val featureCount: Int,
    val json: String,
)
