package com.blurabbit.hdmap.hdmap

import com.blurabbit.hdmap.domain.hdmap.HdMap
import com.blurabbit.hdmap.domain.perception.Detection
import com.blurabbit.hdmap.mapping.MapFeatureExtractor
import com.blurabbit.hdmap.mapping.OdometryBackend
import com.blurabbit.hdmap.mapping.OdometryInput
import com.blurabbit.hdmap.mapping.PointDedup
import com.blurabbit.hdmap.mapping.RoadEventDetector
import javax.inject.Inject

/**
 * End-to-end local mapping for one trip:
 *
 *   sensors (replayed) → [OdometryBackend] → Trajectory ─┬─► [MapFeatureExtractor] ─► geometry + camera detections
 *                                                        └─► [RoadEventDetector]   ─► IMU speed breakers / potholes / rough
 *                              → merge → [dedup] → [RoadIntelligenceScorer] → scored HdMap
 *
 * A within-trip [dedup] pass collapses duplicate observations of the same object (a signal seen over
 * many frames, an IMU breaker firing repeatedly) into one feature, so the single-trip map is clean
 * before storage/export/fusion. The app's process-trip use case calls [build].
 */
class MappingPipeline @Inject constructor(
    private val odometry: OdometryBackend,
    private val extractor: MapFeatureExtractor,
    private val roadEvents: RoadEventDetector,
    private val scorer: RoadIntelligenceScorer,
) {
    fun build(input: OdometryInput, detections: List<Detection>, generatedAtMs: Long): HdMap {
        val trajectory = odometry.estimate(input)
        val base = extractor.extract(trajectory, detections, generatedAtMs)
        val events = roadEvents.detect(trajectory, input.imu)
        val merged = dedup(
            base.copy(
                speedBreakers = base.speedBreakers + events.speedBreakers,
                conditions = base.conditions + events.conditions,
            ),
        )
        return merged.copy(intelligence = scorer.score(merged))
    }

    /** Spatial dedup of point features within the trip (per type, distinct gates). */
    private fun dedup(map: HdMap): HdMap = map.copy(
        signals = PointDedup.clusters(map.signals, { it.location }, { it.signalType.name }, SIGNAL_GATE_M).map { g ->
            g.maxByOrNull { it.confidence }!!.copy(
                location = PointDedup.centroid(g.map { it.location }),
                confidence = PointDedup.noisyOr(g.map { it.confidence }),
            )
        },
        signs = PointDedup.clusters(map.signs, { it.location }, { it.signClass.name }, SIGN_GATE_M).map { g ->
            g.maxByOrNull { it.confidence }!!.copy(
                location = PointDedup.centroid(g.map { it.location }),
                confidence = PointDedup.noisyOr(g.map { it.confidence }),
            )
        },
        speedBreakers = PointDedup.clusters(map.speedBreakers, { it.location }, { "" }, BREAKER_GATE_M).map { g ->
            g.maxByOrNull { it.confidence }!!.copy(
                location = PointDedup.centroid(g.map { it.location }),
                confidence = PointDedup.noisyOr(g.map { it.confidence }),
            )
        },
        conditions = PointDedup.clusters(map.conditions, { it.geometry.first() }, { it.type.name }, CONDITION_GATE_M).map { g ->
            g.maxByOrNull { it.confidence }!!.copy(
                confidence = PointDedup.noisyOr(g.map { it.confidence }),
            )
        },
    )

    private companion object {
        const val SIGNAL_GATE_M = 30.0
        const val SIGN_GATE_M = 20.0
        const val BREAKER_GATE_M = 10.0
        const val CONDITION_GATE_M = 15.0
    }
}
