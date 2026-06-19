package com.blurabbit.hdmap.domain.mapping

import com.blurabbit.hdmap.domain.geo.EnuPoint
import com.blurabbit.hdmap.domain.geo.GeoPoint

/** A single estimated vehicle pose along the trip trajectory. */
data class TrajectoryPose(
    val unifiedNs: Long,
    val geo: GeoPoint,
    val enu: EnuPoint,
    val headingDeg: Double,
    val speedMps: Double,
)

/**
 * The estimated vehicle path for a trip — the backbone the map-feature extractor hangs geometry
 * off of. Produced by the [:mapping] module from GNSS + IMU (and, later, visual odometry).
 */
data class Trajectory(
    val tripId: String,
    val origin: GeoPoint,
    val poses: List<TrajectoryPose>,
) {
    val isEmpty: Boolean get() = poses.isEmpty()
}
