package com.blurabbit.hdmap.domain.hdmap

import com.blurabbit.hdmap.domain.geo.GeoPoint
import com.blurabbit.hdmap.domain.geo.Polyline
import kotlinx.serialization.Serializable

/**
 * The HD-map data model. Every feature carries the four mandatory provenance fields required by
 * the spec — unique [id], [confidence] (0..1), [timestampNs] (unified ns when observed), and
 * [sourceTrip] — so that the crowd-sourced fusion engine can merge observations across trips.
 */
interface MapFeature {
    val id: String
    val confidence: Double
    val timestampNs: Long
    val sourceTrip: String
}

enum class BoundaryType { SOLID, DASHED, DOUBLE_SOLID, BOTTS_DOTS, CURB, UNKNOWN }
enum class BoundaryColor { WHITE, YELLOW, BLUE, UNKNOWN }
enum class IntersectionType { T_JUNCTION, CROSS, ROUNDABOUT, MERGE, SPLIT, UNKNOWN }
enum class SignalType { VEHICLE, PEDESTRIAN, BICYCLE, UNKNOWN }
enum class SignClass { SPEED_LIMIT, STOP, WARNING, DIRECTION, REGULATORY, OTHER }
enum class RoadConditionType { POTHOLE, CRACK, WATERLOGGING, WORK_ZONE, CONSTRUCTION, ROUGH_SURFACE }
enum class RoadSide { LEFT, RIGHT, UNKNOWN }
enum class DrivingDirection { FORWARD, BACKWARD, BIDIRECTIONAL }

@Serializable
data class RoadSegment(
    override val id: String,
    val geometry: Polyline,
    val lengthM: Double,
    val laneCount: Int,
    val widthM: Double,
    val speedLimitKph: Int? = null,
    val direction: DrivingDirection = DrivingDirection.FORWARD,
    override val confidence: Double,
    override val timestampNs: Long,
    override val sourceTrip: String,
) : MapFeature

@Serializable
data class Lane(
    override val id: String,
    val segmentId: String,
    val index: Int,
    val centerline: Polyline,
    val widthM: Double,
    override val confidence: Double,
    override val timestampNs: Long,
    override val sourceTrip: String,
) : MapFeature

@Serializable
data class LaneBoundary(
    override val id: String,
    val segmentId: String,
    val geometry: Polyline,
    val type: BoundaryType,
    val color: BoundaryColor,
    val side: RoadSide,
    override val confidence: Double,
    override val timestampNs: Long,
    override val sourceTrip: String,
) : MapFeature

@Serializable
data class LaneCenterline(
    override val id: String,
    val laneId: String,
    val geometry: Polyline,
    override val confidence: Double,
    override val timestampNs: Long,
    override val sourceTrip: String,
) : MapFeature

@Serializable
data class RoadEdge(
    override val id: String,
    val geometry: Polyline,
    val side: RoadSide,
    override val confidence: Double,
    override val timestampNs: Long,
    override val sourceTrip: String,
) : MapFeature

@Serializable
data class Intersection(
    override val id: String,
    val location: GeoPoint,
    val type: IntersectionType,
    val armCount: Int,
    override val confidence: Double,
    override val timestampNs: Long,
    override val sourceTrip: String,
) : MapFeature

@Serializable
data class TrafficSignal(
    override val id: String,
    val location: GeoPoint,
    val polePosition: GeoPoint? = null,
    val signalType: SignalType,
    override val confidence: Double,
    override val timestampNs: Long,
    override val sourceTrip: String,
) : MapFeature

@Serializable
data class TrafficSign(
    override val id: String,
    val location: GeoPoint,
    val signClass: SignClass,
    val value: String? = null,
    override val confidence: Double,
    override val timestampNs: Long,
    override val sourceTrip: String,
) : MapFeature

@Serializable
data class RoadCondition(
    override val id: String,
    val geometry: Polyline,
    val type: RoadConditionType,
    val severity: Double,
    override val confidence: Double,
    override val timestampNs: Long,
    override val sourceTrip: String,
) : MapFeature

@Serializable
data class SpeedBreaker(
    override val id: String,
    val location: GeoPoint,
    val heightEstimateCm: Double? = null,
    override val confidence: Double,
    override val timestampNs: Long,
    override val sourceTrip: String,
) : MapFeature

@Serializable
data class Crosswalk(
    override val id: String,
    val polygon: Polyline,
    override val confidence: Double,
    override val timestampNs: Long,
    override val sourceTrip: String,
) : MapFeature

@Serializable
data class StopLine(
    override val id: String,
    val geometry: Polyline,
    override val confidence: Double,
    override val timestampNs: Long,
    override val sourceTrip: String,
) : MapFeature

/** Per-segment road-intelligence scores (0..100), one bundle per [HdMap]. */
@Serializable
data class RoadIntelligence(
    val healthScore: Double,
    val qualityScore: Double,
    val trafficDensityScore: Double,
    val safetyScore: Double,
    val constructionScore: Double,
)

/** The complete HD map produced for a single trip — the unit of storage, export, and fusion. */
@Serializable
data class HdMap(
    val tripId: String,
    val generatedAtMs: Long,
    val segments: List<RoadSegment> = emptyList(),
    val lanes: List<Lane> = emptyList(),
    val boundaries: List<LaneBoundary> = emptyList(),
    val centerlines: List<LaneCenterline> = emptyList(),
    val roadEdges: List<RoadEdge> = emptyList(),
    val intersections: List<Intersection> = emptyList(),
    val signals: List<TrafficSignal> = emptyList(),
    val signs: List<TrafficSign> = emptyList(),
    val conditions: List<RoadCondition> = emptyList(),
    val speedBreakers: List<SpeedBreaker> = emptyList(),
    val crosswalks: List<Crosswalk> = emptyList(),
    val stopLines: List<StopLine> = emptyList(),
    val intelligence: RoadIntelligence? = null,
) {
    val featureCount: Int
        get() = segments.size + lanes.size + boundaries.size + centerlines.size + roadEdges.size +
            intersections.size + signals.size + signs.size + conditions.size + speedBreakers.size +
            crosswalks.size + stopLines.size
}
