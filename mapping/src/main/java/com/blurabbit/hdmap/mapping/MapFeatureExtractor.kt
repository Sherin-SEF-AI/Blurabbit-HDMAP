package com.blurabbit.hdmap.mapping

import com.blurabbit.hdmap.domain.geo.EnuPoint
import com.blurabbit.hdmap.domain.geo.Geo
import com.blurabbit.hdmap.domain.geo.GeoPoint
import com.blurabbit.hdmap.domain.hdmap.BoundaryColor
import com.blurabbit.hdmap.domain.hdmap.BoundaryType
import com.blurabbit.hdmap.domain.hdmap.Crosswalk
import com.blurabbit.hdmap.domain.hdmap.HdMap
import com.blurabbit.hdmap.domain.hdmap.Lane
import com.blurabbit.hdmap.domain.hdmap.LaneBoundary
import com.blurabbit.hdmap.domain.hdmap.LaneCenterline
import com.blurabbit.hdmap.domain.hdmap.RoadCondition
import com.blurabbit.hdmap.domain.hdmap.RoadConditionType
import com.blurabbit.hdmap.domain.hdmap.RoadEdge
import com.blurabbit.hdmap.domain.hdmap.RoadSegment
import com.blurabbit.hdmap.domain.hdmap.RoadSide
import com.blurabbit.hdmap.domain.hdmap.SignClass
import com.blurabbit.hdmap.domain.hdmap.SignalType
import com.blurabbit.hdmap.domain.hdmap.SpeedBreaker
import com.blurabbit.hdmap.domain.hdmap.StopLine
import com.blurabbit.hdmap.domain.hdmap.TrafficSign
import com.blurabbit.hdmap.domain.hdmap.TrafficSignal
import com.blurabbit.hdmap.domain.mapping.Trajectory
import com.blurabbit.hdmap.domain.perception.Detection
import com.blurabbit.hdmap.domain.perception.DetectionKind
import com.blurabbit.hdmap.domain.util.Ids
import javax.inject.Inject

/**
 * Turns an estimated [Trajectory] plus per-frame perception [Detection]s into HD-map features.
 *
 * From the trajectory alone it derives the road centerline (simplified), a baseline lane and its
 * centerline, and left/right road edges offset by the estimated road width — a usable GNSS-only
 * map. Perception detections are lifted to world space by matching each detection's frame timestamp
 * to the nearest trajectory pose and emitted as the corresponding point/line feature. The result
 * carries no [com.blurabbit.hdmap.domain.hdmap.RoadIntelligence]; the `:hdmap` scorer adds that.
 */
class MapFeatureExtractor @Inject constructor(
    private val intersectionDetector: IntersectionDetector,
) {
    fun extract(
        trajectory: Trajectory,
        detections: List<Detection>,
        generatedAtMs: Long,
    ): HdMap {
        val tripId = trajectory.tripId
        if (trajectory.poses.size < 2) return HdMap(tripId = tripId, generatedAtMs = generatedAtMs)

        val poses = trajectory.poses
        val enu = poses.map { it.enu }
        val keep = PolylineOps.simplifyIndices(enu, EPSILON_M)
        val centerlineGeo = keep.map { poses[it].geo }
        val centerlineEnu = keep.map { enu[it] }
        val firstNs = poses.first().unifiedNs
        val lengthM = Geo.lengthMeters(centerlineGeo)
        val coverageConfidence = (0.5 + (poses.size.coerceAtMost(600) / 1200.0)).coerceIn(0.5, 0.9)

        // Vision-estimated road width from the drivable-area detector (median across the trip),
        // falling back to a default when no usable corridor was seen.
        val visionWidths = detections
            .filter { it.kind == DetectionKind.ROAD_EDGE }
            .mapNotNull { it.attributes["widthM"]?.toDoubleOrNull() }
            .filter { it in 2.0..25.0 }
            .sorted()
        val roadWidth = if (visionWidths.isNotEmpty()) visionWidths[visionWidths.size / 2] else ROAD_WIDTH_M

        val origin = trajectory.origin
        val halfWidth = roadWidth / 2.0
        val leftEdgeGeo = PolylineOps.offset(centerlineEnu, halfWidth).map { Geo.fromEnu(origin, it) }
        val rightEdgeGeo = PolylineOps.offset(centerlineEnu, -halfWidth).map { Geo.fromEnu(origin, it) }

        val segmentId = Ids.feature("seg")
        val laneId = Ids.feature("lane")

        val segment = RoadSegment(
            id = segmentId,
            geometry = centerlineGeo,
            lengthM = lengthM,
            laneCount = DEFAULT_LANES,
            widthM = roadWidth,
            confidence = coverageConfidence,
            timestampNs = firstNs,
            sourceTrip = tripId,
        )
        val lane = Lane(
            id = laneId,
            segmentId = segmentId,
            index = 0,
            centerline = centerlineGeo,
            widthM = LANE_WIDTH_M,
            confidence = coverageConfidence,
            timestampNs = firstNs,
            sourceTrip = tripId,
        )
        val centerline = LaneCenterline(
            id = Ids.feature("cl"),
            laneId = laneId,
            geometry = centerlineGeo,
            confidence = coverageConfidence,
            timestampNs = firstNs,
            sourceTrip = tripId,
        )
        val roadEdges = listOf(
            RoadEdge(Ids.feature("edge"), leftEdgeGeo, RoadSide.LEFT, coverageConfidence * 0.8, firstNs, tripId),
            RoadEdge(Ids.feature("edge"), rightEdgeGeo, RoadSide.RIGHT, coverageConfidence * 0.8, firstNs, tripId),
        )

        // Perception-derived features. A real object is visible across many frames, so each
        // contiguous run of same-kind detections (small time gaps) collapses to ONE feature placed
        // at closest approach (the run's last sighting), with the run's peak confidence.
        val signs = ArrayList<TrafficSign>()
        val signals = ArrayList<TrafficSignal>()
        val speedBreakers = ArrayList<SpeedBreaker>()
        val conditions = ArrayList<RoadCondition>()
        val crosswalks = ArrayList<Crosswalk>()
        val stopLines = ArrayList<StopLine>()
        val tsList = poses.map { it.unifiedNs to it.geo }
        for (run in temporalRuns(detections)) {
            val d = run.last() // closest approach to the object
            val at = nearestGeo(tsList, d.frameUnifiedNs) ?: continue
            val conf = run.maxOf { it.confidence }
            val ns = d.frameUnifiedNs
            when (d.kind) {
                DetectionKind.TRAFFIC_SIGN -> signs += TrafficSign(
                    Ids.feature("sign"), at, signClassOf(d.label), d.attributes["value"], conf, ns, tripId,
                )
                DetectionKind.TRAFFIC_SIGNAL -> signals += TrafficSignal(
                    Ids.feature("sig"), at, null, SignalType.VEHICLE, conf, ns, tripId,
                )
                DetectionKind.SPEED_BREAKER -> speedBreakers += SpeedBreaker(Ids.feature("sb"), at, null, conf, ns, tripId)
                DetectionKind.POTHOLE -> conditions += condition(at, RoadConditionType.POTHOLE, conf, ns, tripId)
                DetectionKind.CRACK -> conditions += condition(at, RoadConditionType.CRACK, conf, ns, tripId)
                DetectionKind.WATERLOGGING -> conditions += condition(at, RoadConditionType.WATERLOGGING, conf, ns, tripId)
                DetectionKind.WORK_ZONE -> conditions += condition(at, RoadConditionType.WORK_ZONE, conf, ns, tripId)
                DetectionKind.CROSSWALK -> crosswalks += Crosswalk(Ids.feature("xwalk"), listOf(at), conf, ns, tripId)
                DetectionKind.STOP_LINE -> stopLines += StopLine(Ids.feature("stop"), listOf(at), conf, ns, tripId)
                DetectionKind.LANE_MARKING, DetectionKind.ROAD_EDGE, DetectionKind.INTERSECTION -> Unit
            }
        }

        return HdMap(
            tripId = tripId,
            generatedAtMs = generatedAtMs,
            segments = listOf(segment),
            lanes = listOf(lane),
            centerlines = listOf(centerline),
            roadEdges = roadEdges,
            intersections = intersectionDetector.detect(trajectory, tripId),
            signs = signs,
            signals = signals,
            conditions = conditions,
            speedBreakers = speedBreakers,
            crosswalks = crosswalks,
            stopLines = stopLines,
        )
    }

    private fun condition(at: GeoPoint, type: RoadConditionType, conf: Double, ns: Long, tripId: String) =
        RoadCondition(Ids.feature("cond"), listOf(at), type, severity = conf, confidence = conf, timestampNs = ns, sourceTrip = tripId)

    /**
     * Group detections into temporal runs per (kind, label): a run is a contiguous burst of
     * sightings of the same object, broken when the time gap exceeds [RUN_GAP_NS].
     */
    private fun temporalRuns(dets: List<Detection>): List<List<Detection>> {
        if (dets.isEmpty()) return emptyList()
        val runs = ArrayList<List<Detection>>()
        for ((_, list) in dets.groupBy { it.kind to it.label }) {
            val sorted = list.sortedBy { it.frameUnifiedNs }
            var cur = ArrayList<Detection>()
            var lastNs = 0L
            for (d in sorted) {
                if (cur.isNotEmpty() && d.frameUnifiedNs - lastNs > RUN_GAP_NS) { runs.add(cur); cur = ArrayList() }
                cur.add(d); lastNs = d.frameUnifiedNs
            }
            if (cur.isNotEmpty()) runs.add(cur)
        }
        return runs
    }

    private fun signClassOf(label: String): SignClass = when {
        label.contains("speed", true) || label.contains("limit", true) -> SignClass.SPEED_LIMIT
        label.contains("stop", true) -> SignClass.STOP
        label.contains("warn", true) -> SignClass.WARNING
        label.contains("direction", true) -> SignClass.DIRECTION
        else -> SignClass.OTHER
    }

    private fun nearestGeo(samples: List<Pair<Long, GeoPoint>>, ns: Long): GeoPoint? =
        samples.minByOrNull { kotlin.math.abs(it.first - ns) }?.second

    @Suppress("unused")
    private fun centroid(points: List<EnuPoint>): EnuPoint =
        EnuPoint(points.map { it.east }.average(), points.map { it.north }.average())

    private companion object {
        const val EPSILON_M = 1.0
        const val ROAD_WIDTH_M = 7.0
        const val LANE_WIDTH_M = 3.5
        const val DEFAULT_LANES = 2
        const val RUN_GAP_NS = 3_000_000_000L // >3 s gap ⇒ a different object
    }
}
