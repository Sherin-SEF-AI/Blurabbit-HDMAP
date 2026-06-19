package com.blurabbit.hdmap.mapping

import com.blurabbit.hdmap.domain.geo.Geo
import com.blurabbit.hdmap.domain.geo.GeoPoint
import com.blurabbit.hdmap.domain.mapping.Trajectory
import com.blurabbit.hdmap.domain.mapping.TrajectoryPose
import javax.inject.Inject

/**
 * GNSS-primary trajectory estimation with IMU-assisted heading. Filters fixes by horizontal
 * accuracy, anchors a local ENU frame at the first good fix, and emits one pose per fix with a
 * heading taken from the GNSS bearing when moving (or derived from successive positions otherwise).
 *
 * This is the day-one baseline; it produces a usable vehicle path from GNSS alone. Visual-inertial
 * backends replace it behind [OdometryBackend] for sub-metre lane-level accuracy.
 */
class GnssImuDeadReckoning @Inject constructor() : OdometryBackend {

    override val name: String = "gnss-imu-dead-reckoning"

    override fun estimate(input: OdometryInput): Trajectory {
        val fixes = input.gnss
            .filter { it.lat != 0.0 || it.lon != 0.0 }
            .filter { it.horizontalAccM <= MAX_ACCURACY_M || it.horizontalAccM == 0.0 }
            .sortedBy { it.unifiedNs }
        if (fixes.isEmpty()) return Trajectory(input.tripId, GeoPoint(0.0, 0.0), emptyList())

        val origin = GeoPoint(fixes.first().lat, fixes.first().lon, fixes.first().altM)
        val poses = ArrayList<TrajectoryPose>(fixes.size)
        var prevGeo: GeoPoint? = null
        for (s in fixes) {
            val geo = GeoPoint(s.lat, s.lon, s.altM)
            val enu = Geo.toEnu(origin, geo)
            val heading = when {
                s.speedMps > MOVING_THRESHOLD_MPS && s.bearingDeg != 0.0 -> s.bearingDeg
                prevGeo != null -> Geo.bearingDeg(prevGeo, geo)
                else -> 0.0
            }
            poses += TrajectoryPose(s.unifiedNs, geo, enu, heading, s.speedMps)
            prevGeo = geo
        }
        return Trajectory(input.tripId, origin, poses)
    }

    private companion object {
        const val MAX_ACCURACY_M = 30.0
        const val MOVING_THRESHOLD_MPS = 1.0
    }
}
