package com.blurabbit.hdmap.backend

import com.blurabbit.hdmap.domain.geo.EnuPoint
import com.blurabbit.hdmap.domain.geo.Geo
import com.blurabbit.hdmap.domain.geo.GeoPoint
import com.blurabbit.hdmap.domain.geo.Polyline
import com.blurabbit.hdmap.domain.hdmap.Crosswalk
import com.blurabbit.hdmap.domain.hdmap.HdMap
import com.blurabbit.hdmap.domain.hdmap.Intersection
import com.blurabbit.hdmap.domain.hdmap.Lane
import com.blurabbit.hdmap.domain.hdmap.LaneCenterline
import com.blurabbit.hdmap.domain.hdmap.RoadCondition
import com.blurabbit.hdmap.domain.hdmap.RoadEdge
import com.blurabbit.hdmap.domain.hdmap.RoadSegment
import com.blurabbit.hdmap.domain.hdmap.SpeedBreaker
import com.blurabbit.hdmap.domain.hdmap.TrafficSign
import com.blurabbit.hdmap.domain.hdmap.TrafficSignal
import com.blurabbit.hdmap.domain.util.Ids
import com.blurabbit.hdmap.hdmap.RoadIntelligenceScorer
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Crowd-sourced map fusion. Merges many per-trip [HdMap]s observing the same roads into one
 * consensus map:
 *
 *  - **Association** — point features of the same type are bucketed into a fine spatial grid
 *    (~6 m cells), so independent observations of the same real-world object cluster together.
 *  - **Geometry refinement** — a cluster's location is the confidence-weighted centroid of its
 *    observations.
 *  - **Confidence boosting** — agreement across independent trips raises confidence via a noisy-OR
 *    (two 0.6 sightings → 0.84); the observation count and contributing trips are recorded.
 *
 * Linear features (segments/lanes/edges) are unioned for this prototype; polyline fusion is the
 * documented next step. Road-intelligence is recomputed on the consensus map.
 */
class FusionEngine(private val gridDeg: Double = 0.00006) { // ~6.6 m at the equator

    fun fuse(maps: List<HdMap>): HdMap {
        if (maps.isEmpty()) return HdMap(tripId = CONSENSUS, generatedAtMs = 0)
        val generatedAt = maps.maxOf { it.generatedAtMs }

        val base = HdMap(
            tripId = CONSENSUS,
            generatedAtMs = generatedAt,
            // Linear features: fused (matched polylines merged station-by-station).
            segments = fuseSegments(maps.flatMap { it.segments }),
            lanes = fuseLanes(maps.flatMap { it.lanes }),
            centerlines = fuseCenterlines(maps.flatMap { it.centerlines }),
            roadEdges = fuseRoadEdges(maps.flatMap { it.roadEdges }),
            // Point features: fused with confidence boosting.
            speedBreakers = fuseSpeedBreakers(maps.flatMap { it.speedBreakers }),
            signals = fuseSignals(maps.flatMap { it.signals }),
            signs = fuseSigns(maps.flatMap { it.signs }),
            conditions = fuseConditions(maps.flatMap { it.conditions }),
            intersections = fuseIntersections(maps.flatMap { it.intersections }),
            crosswalks = fuseCrosswalks(maps.flatMap { it.crosswalks }),
        )
        return base.copy(intelligence = RoadIntelligenceScorer().score(base))
    }

    // --- polyline (linear-feature) fusion ----------------------------------------------------

    private fun fuseSegments(all: List<RoadSegment>): List<RoadSegment> {
        if (all.isEmpty()) return emptyList()
        val origin = all.first().geometry.first()
        return clusterLines(all, { it.geometry }, { it.confidence }, { "" }, origin).map { g ->
            val geom = mergeGeom(g.map { it.geometry to it.confidence }, origin)
            RoadSegment(
                id = Ids.feature("cons_seg"),
                geometry = geom,
                lengthM = Geo.lengthMeters(geom),
                laneCount = g.groupingBy { it.laneCount }.eachCount().maxByOrNull { it.value }!!.key,
                widthM = g.map { it.widthM }.average(),
                speedLimitKph = g.mapNotNull { it.speedLimitKph }.ifEmpty { null }?.let { it.groupingBy { v -> v }.eachCount().maxByOrNull { e -> e.value }!!.key },
                confidence = noisyOr(g.map { it.confidence }),
                timestampNs = g.minOf { it.timestampNs },
                sourceTrip = trips(g.map { it.sourceTrip }),
            )
        }
    }

    private fun fuseLanes(all: List<Lane>): List<Lane> {
        if (all.isEmpty()) return emptyList()
        val origin = all.first().centerline.first()
        return clusterLines(all, { it.centerline }, { it.confidence }, { it.index.toString() }, origin).map { g ->
            Lane(
                id = Ids.feature("cons_lane"),
                segmentId = g.first().segmentId,
                index = g.first().index,
                centerline = mergeGeom(g.map { it.centerline to it.confidence }, origin),
                widthM = g.map { it.widthM }.average(),
                confidence = noisyOr(g.map { it.confidence }),
                timestampNs = g.minOf { it.timestampNs },
                sourceTrip = trips(g.map { it.sourceTrip }),
            )
        }
    }

    private fun fuseCenterlines(all: List<LaneCenterline>): List<LaneCenterline> {
        if (all.isEmpty()) return emptyList()
        val origin = all.first().geometry.first()
        return clusterLines(all, { it.geometry }, { it.confidence }, { "" }, origin).map { g ->
            LaneCenterline(
                id = Ids.feature("cons_cl"),
                laneId = g.first().laneId,
                geometry = mergeGeom(g.map { it.geometry to it.confidence }, origin),
                confidence = noisyOr(g.map { it.confidence }),
                timestampNs = g.minOf { it.timestampNs },
                sourceTrip = trips(g.map { it.sourceTrip }),
            )
        }
    }

    private fun fuseRoadEdges(all: List<RoadEdge>): List<RoadEdge> {
        if (all.isEmpty()) return emptyList()
        val origin = all.first().geometry.first()
        return clusterLines(all, { it.geometry }, { it.confidence }, { it.side.name }, origin).map { g ->
            RoadEdge(
                id = Ids.feature("cons_edge"),
                geometry = mergeGeom(g.map { it.geometry to it.confidence }, origin),
                side = g.first().side,
                confidence = noisyOr(g.map { it.confidence }),
                timestampNs = g.minOf { it.timestampNs },
                sourceTrip = trips(g.map { it.sourceTrip }),
            )
        }
    }

    // --- per-type fusion ---------------------------------------------------------------------

    private fun fuseSpeedBreakers(all: List<SpeedBreaker>): List<SpeedBreaker> =
        cluster(all, { it.location }, { "" }).map { g ->
            SpeedBreaker(
                id = Ids.feature("cons_sb"),
                location = weightedCentroid(g.map { it.location to it.confidence }),
                heightEstimateCm = g.mapNotNull { it.heightEstimateCm }.ifEmpty { null }?.average(),
                confidence = noisyOr(g.map { it.confidence }),
                timestampNs = g.minOf { it.timestampNs },
                sourceTrip = trips(g.map { it.sourceTrip }),
            )
        }

    private fun fuseSignals(all: List<TrafficSignal>): List<TrafficSignal> =
        cluster(all, { it.location }, { it.signalType.name }).map { g ->
            TrafficSignal(
                id = Ids.feature("cons_sig"),
                location = weightedCentroid(g.map { it.location to it.confidence }),
                polePosition = null,
                signalType = g.groupingBy { it.signalType }.eachCount().maxByOrNull { it.value }!!.key,
                confidence = noisyOr(g.map { it.confidence }),
                timestampNs = g.minOf { it.timestampNs },
                sourceTrip = trips(g.map { it.sourceTrip }),
            )
        }

    private fun fuseSigns(all: List<TrafficSign>): List<TrafficSign> =
        cluster(all, { it.location }, { it.signClass.name }).map { g ->
            TrafficSign(
                id = Ids.feature("cons_sign"),
                location = weightedCentroid(g.map { it.location to it.confidence }),
                signClass = g.first().signClass,
                value = g.mapNotNull { it.value }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
                confidence = noisyOr(g.map { it.confidence }),
                timestampNs = g.minOf { it.timestampNs },
                sourceTrip = trips(g.map { it.sourceTrip }),
            )
        }

    private fun fuseConditions(all: List<RoadCondition>): List<RoadCondition> =
        cluster(all, { it.geometry.first() }, { it.type.name }).map { g ->
            val loc = weightedCentroid(g.map { it.geometry.first() to it.confidence })
            RoadCondition(
                id = Ids.feature("cons_cond"),
                geometry = listOf(loc),
                type = g.first().type,
                severity = g.map { it.severity }.average(),
                confidence = noisyOr(g.map { it.confidence }),
                timestampNs = g.minOf { it.timestampNs },
                sourceTrip = trips(g.map { it.sourceTrip }),
            )
        }

    private fun fuseIntersections(all: List<Intersection>): List<Intersection> =
        cluster(all, { it.location }, { "" }).map { g ->
            Intersection(
                id = Ids.feature("cons_isxn"),
                location = weightedCentroid(g.map { it.location to it.confidence }),
                type = g.groupingBy { it.type }.eachCount().maxByOrNull { it.value }!!.key,
                armCount = g.maxOf { it.armCount },
                confidence = noisyOr(g.map { it.confidence }),
                timestampNs = g.minOf { it.timestampNs },
                sourceTrip = trips(g.map { it.sourceTrip }),
            )
        }

    private fun fuseCrosswalks(all: List<Crosswalk>): List<Crosswalk> =
        cluster(all, { it.polygon.first() }, { "" }).map { g ->
            Crosswalk(
                id = Ids.feature("cons_xwalk"),
                polygon = g.first().polygon,
                confidence = noisyOr(g.map { it.confidence }),
                timestampNs = g.minOf { it.timestampNs },
                sourceTrip = trips(g.map { it.sourceTrip }),
            )
        }

    // --- helpers -----------------------------------------------------------------------------

    private fun <T> cluster(items: List<T>, loc: (T) -> GeoPoint, discriminator: (T) -> String): List<List<T>> {
        val buckets = LinkedHashMap<String, MutableList<T>>()
        for (item in items) {
            val p = loc(item)
            val key = "${discriminator(item)}@${floor(p.lat / gridDeg).toLong()}:${floor(p.lon / gridDeg).toLong()}"
            buckets.getOrPut(key) { mutableListOf() }.add(item)
        }
        return buckets.values.toList()
    }

    /**
     * Greedy polyline clustering: highest-confidence lines seed a "spine"; a line joins a cluster
     * (same [sideKey]) when its resampled mean-nearest distance to the spine is within the gate.
     */
    private fun <T> clusterLines(
        items: List<T>,
        geom: (T) -> Polyline,
        conf: (T) -> Double,
        sideKey: (T) -> String,
        origin: GeoPoint,
    ): List<List<T>> {
        data class Cluster(val side: String, val spine: List<EnuPoint>, val members: MutableList<T>)
        val clusters = ArrayList<Cluster>()
        for (item in items.sortedByDescending { conf(it) }) {
            val enu = resampleEnu(geom(item), origin)
            if (enu.size < 2) continue
            val side = sideKey(item)
            val match = clusters.firstOrNull { it.side == side && meanNearestDist(enu, it.spine) < LINE_GATE_M }
            if (match != null) match.members.add(item) else clusters.add(Cluster(side, enu, mutableListOf(item)))
        }
        return clusters.map { it.members }
    }

    /** Merge matched polylines: walk the highest-confidence spine, averaging each member's nearest
     * station weighted by confidence, then project back to WGS84. */
    private fun mergeGeom(lines: List<Pair<Polyline, Double>>, origin: GeoPoint): Polyline {
        if (lines.size == 1) return lines.first().first
        val spine = lines.maxByOrNull { it.second }!!.first
        val spineEnu = resampleEnu(spine, origin)
        val members = lines.map { resampleEnu(it.first, origin) to it.second }
        return spineEnu.map { s ->
            var we = 0.0; var wn = 0.0; var wsum = 0.0
            for ((m, w) in members) {
                val nearest = m.minByOrNull { hypot(it.east - s.east, it.north - s.north) } ?: continue
                if (hypot(nearest.east - s.east, nearest.north - s.north) <= LINE_GATE_M) {
                    we += nearest.east * w; wn += nearest.north * w; wsum += w
                }
            }
            if (wsum > 0) Geo.fromEnu(origin, EnuPoint(we / wsum, wn / wsum)) else Geo.fromEnu(origin, s)
        }
    }

    /** Resample a polyline to ~[RESAMPLE_M]-spaced stations in the local ENU frame. */
    private fun resampleEnu(geom: Polyline, origin: GeoPoint): List<EnuPoint> {
        val pts = geom.map { Geo.toEnu(origin, it) }
        if (pts.size < 2) return pts
        val cum = DoubleArray(pts.size)
        for (i in 1 until pts.size) cum[i] = cum[i - 1] + hypot(pts[i].east - pts[i - 1].east, pts[i].north - pts[i - 1].north)
        val total = cum.last()
        if (total < 1e-6) return listOf(pts.first(), pts.last())
        val n = maxOf(2, (total / RESAMPLE_M).toInt() + 1)
        val out = ArrayList<EnuPoint>(n)
        var seg = 1
        for (k in 0 until n) {
            val target = total * k / (n - 1)
            while (seg < pts.size - 1 && cum[seg] < target) seg++
            val a = pts[seg - 1]; val b = pts[seg]
            val segLen = (cum[seg] - cum[seg - 1]).coerceAtLeast(1e-9)
            val f = ((target - cum[seg - 1]) / segLen).coerceIn(0.0, 1.0)
            out.add(EnuPoint(a.east + (b.east - a.east) * f, a.north + (b.north - a.north) * f))
        }
        return out
    }

    private fun meanNearestDist(a: List<EnuPoint>, b: List<EnuPoint>): Double =
        a.map { p -> b.minOf { hypot(p.east - it.east, p.north - it.north) } }.average()

    private fun weightedCentroid(points: List<Pair<GeoPoint, Double>>): GeoPoint {
        val wsum = points.sumOf { it.second }.takeIf { it > 1e-9 } ?: points.size.toDouble()
        val lat = points.sumOf { it.first.lat * it.second } / wsum
        val lon = points.sumOf { it.first.lon * it.second } / wsum
        val alt = points.sumOf { it.first.altM * it.second } / wsum
        return GeoPoint(lat, lon, alt)
    }

    /** Noisy-OR confidence: independent agreeing observations push consensus toward certainty. */
    private fun noisyOr(confidences: List<Double>): Double {
        var p = 1.0
        for (c in confidences) p *= (1.0 - c.coerceIn(0.0, 0.99))
        return (1.0 - p).coerceIn(0.0, 0.99)
    }

    private fun trips(sources: List<String>): String =
        sources.flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")

    private companion object {
        const val CONSENSUS = "consensus"
        const val RESAMPLE_M = 5.0   // polyline station spacing
        const val LINE_GATE_M = 4.0  // max lateral distance for two lines to be the same road
    }
}
