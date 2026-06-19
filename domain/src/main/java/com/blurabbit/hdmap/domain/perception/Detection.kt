package com.blurabbit.hdmap.domain.perception

import kotlinx.serialization.Serializable

/** A normalized (0..1) bounding box in image space. */
@Serializable
data class NormBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val cx: Float get() = (left + right) / 2f
    val cy: Float get() = (top + bottom) / 2f
}

/** Category of a perception detection — maps onto the HD-map feature it contributes to. */
enum class DetectionKind {
    LANE_MARKING,
    ROAD_EDGE,
    TRAFFIC_SIGN,
    TRAFFIC_SIGNAL,
    SPEED_BREAKER,
    POTHOLE,
    CRACK,
    CROSSWALK,
    STOP_LINE,
    WATERLOGGING,
    WORK_ZONE,
    INTERSECTION,
}

/**
 * One observation emitted by an on-device perception model for a single camera frame. The mapping
 * pipeline lifts these image-space detections to world geometry using the synchronized trajectory.
 */
@Serializable
data class Detection(
    val kind: DetectionKind,
    val label: String,
    val confidence: Double,
    val frameUnifiedNs: Long,
    val box: NormBox? = null,
    val attributes: Map<String, String> = emptyMap(),
)
