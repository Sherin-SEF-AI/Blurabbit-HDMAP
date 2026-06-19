package com.blurabbit.hdmap.mapping

import com.blurabbit.hdmap.domain.geo.EnuPoint
import kotlin.math.hypot
import kotlin.math.sqrt

/** Geometry helpers operating in the metric ENU frame used by the mapping pipeline. */
internal object PolylineOps {

    /**
     * Ramer–Douglas–Peucker simplification. Returns the indices of [points] to keep so callers can
     * carry the matching geo points / timestamps. [epsilonM] is the max perpendicular error.
     */
    fun simplifyIndices(points: List<EnuPoint>, epsilonM: Double): List<Int> {
        if (points.size <= 2) return points.indices.toList()
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        rdp(points, 0, points.lastIndex, epsilonM, keep)
        return points.indices.filter { keep[it] }
    }

    private fun rdp(p: List<EnuPoint>, start: Int, end: Int, eps: Double, keep: BooleanArray) {
        if (end <= start + 1) return
        var maxD = 0.0
        var idx = -1
        for (i in start + 1 until end) {
            val d = perpDistance(p[i], p[start], p[end])
            if (d > maxD) { maxD = d; idx = i }
        }
        if (maxD > eps && idx != -1) {
            keep[idx] = true
            rdp(p, start, idx, eps, keep)
            rdp(p, idx, end, eps, keep)
        }
    }

    private fun perpDistance(pt: EnuPoint, a: EnuPoint, b: EnuPoint): Double {
        val dx = b.east - a.east
        val dy = b.north - a.north
        val len = hypot(dx, dy)
        if (len < 1e-9) return hypot(pt.east - a.east, pt.north - a.north)
        val t = ((pt.east - a.east) * dx + (pt.north - a.north) * dy) / (len * len)
        val projE = a.east + t * dx
        val projN = a.north + t * dy
        return hypot(pt.east - projE, pt.north - projN)
    }

    /**
     * Offset a polyline laterally by [offsetM] metres. Positive offsets go to the left of travel
     * direction, negative to the right. Used to synthesize lane boundaries / road edges from the
     * GNSS centerline before perception refines them.
     */
    fun offset(points: List<EnuPoint>, offsetM: Double): List<EnuPoint> {
        if (points.size < 2) return points
        val out = ArrayList<EnuPoint>(points.size)
        for (i in points.indices) {
            val a = points[if (i == 0) 0 else i - 1]
            val b = points[if (i == points.lastIndex) points.lastIndex else i + 1]
            var dx = b.east - a.east
            var dy = b.north - a.north
            val len = sqrt(dx * dx + dy * dy)
            if (len < 1e-9) { out += points[i]; continue }
            dx /= len; dy /= len
            // Left normal of travel direction (dx,dy) is (-dy, dx).
            val nx = -dy; val ny = dx
            val p = points[i]
            out += EnuPoint(p.east + nx * offsetM, p.north + ny * offsetM, p.up)
        }
        return out
    }
}
