package com.blurabbit.hdmap.mapping

import com.blurabbit.hdmap.domain.geo.Geo
import com.blurabbit.hdmap.domain.geo.GeoPoint

/**
 * Greedy spatial clustering for point features within a single trip — the safety net that collapses
 * residual duplicate observations of the same real-world object (e.g. an IMU speed-breaker that
 * fired on consecutive bumps, or two detection runs of one signal) into one feature.
 */
object PointDedup {

    /** Cluster items sharing a [key] whose [loc] is within [gateM] metres of a cluster seed. */
    fun <T> clusters(items: List<T>, loc: (T) -> GeoPoint, key: (T) -> String, gateM: Double): List<List<T>> {
        data class C(val k: String, val seed: GeoPoint, val members: MutableList<T>)
        val cs = ArrayList<C>()
        for (item in items) {
            val p = loc(item); val k = key(item)
            val match = cs.firstOrNull { it.k == k && Geo.haversineMeters(it.seed, p) <= gateM }
            if (match != null) match.members.add(item) else cs.add(C(k, p, mutableListOf(item)))
        }
        return cs.map { it.members }
    }

    fun centroid(points: List<GeoPoint>): GeoPoint =
        GeoPoint(points.map { it.lat }.average(), points.map { it.lon }.average(), points.map { it.altM }.average())

    /** Independent agreeing observations push confidence toward certainty. */
    fun noisyOr(confidences: List<Double>): Double {
        var p = 1.0
        for (c in confidences) p *= (1.0 - c.coerceIn(0.0, 0.99))
        return (1.0 - p).coerceIn(0.0, 0.99)
    }
}
