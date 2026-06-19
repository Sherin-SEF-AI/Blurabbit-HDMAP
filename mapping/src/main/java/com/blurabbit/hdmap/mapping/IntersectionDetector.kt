package com.blurabbit.hdmap.mapping

import com.blurabbit.hdmap.domain.hdmap.Intersection
import com.blurabbit.hdmap.domain.hdmap.IntersectionType
import com.blurabbit.hdmap.domain.mapping.Trajectory
import com.blurabbit.hdmap.domain.util.Ids
import kotlin.math.abs
import javax.inject.Inject

/**
 * Heuristic intersection detector: flags places where the vehicle turns sharply at low speed,
 * which on a single drive is the strongest available cue for a junction. Cross-trip fusion later
 * upgrades these to typed intersections (T / cross / roundabout) from connectivity.
 */
class IntersectionDetector @Inject constructor() {

    fun detect(trajectory: Trajectory, tripId: String): List<Intersection> {
        val poses = trajectory.poses
        if (poses.size < 3) return emptyList()
        val result = ArrayList<Intersection>()
        var lastMarkedNs = Long.MIN_VALUE
        for (i in 1 until poses.lastIndex) {
            val turn = abs(headingDelta(poses[i - 1].headingDeg, poses[i + 1].headingDeg))
            val slow = poses[i].speedMps < SLOW_MPS
            val spaced = poses[i].unifiedNs - lastMarkedNs > MIN_SPACING_NS
            if (turn > TURN_DEG && slow && spaced) {
                result += Intersection(
                    id = Ids.feature("isxn"),
                    location = poses[i].geo,
                    type = IntersectionType.UNKNOWN,
                    armCount = 0,
                    confidence = 0.35,
                    timestampNs = poses[i].unifiedNs,
                    sourceTrip = tripId,
                )
                lastMarkedNs = poses[i].unifiedNs
            }
        }
        return result
    }

    /** Signed smallest-angle difference between two headings, in degrees (−180..180). */
    private fun headingDelta(a: Double, b: Double): Double {
        var d = (b - a) % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }

    private companion object {
        const val TURN_DEG = 60.0
        const val SLOW_MPS = 6.0
        const val MIN_SPACING_NS = 5_000_000_000L // 5 s
    }
}
