package com.blurabbit.hdmap.hdmap

import com.blurabbit.hdmap.domain.hdmap.HdMap
import com.blurabbit.hdmap.domain.hdmap.RoadConditionType
import com.blurabbit.hdmap.domain.hdmap.RoadIntelligence
import javax.inject.Inject
import kotlin.math.max

/**
 * Computes the five per-trip road-intelligence scores (0..100) from an [HdMap].
 *
 * The formulas are transparent and density-based so they remain meaningful from a single drive:
 *  - **health/quality** fall as surface defects (potholes, cracks, rough surface) accumulate per km;
 *  - **safety** additionally penalizes sharp/uncontrolled intersections and waterlogging;
 *  - **construction** rises with work-zone / construction density;
 *  - **traffic density** is a proxy from controlled-feature density (signals + signs + breakers),
 *    pending real flow data from cross-trip fusion.
 */
class RoadIntelligenceScorer @Inject constructor() {

    fun score(map: HdMap): RoadIntelligence {
        val km = max(0.05, map.segments.sumOf { it.lengthM } / 1000.0)

        val defects = map.conditions.count {
            it.type == RoadConditionType.POTHOLE || it.type == RoadConditionType.CRACK ||
                it.type == RoadConditionType.ROUGH_SURFACE
        }
        val construction = map.conditions.count {
            it.type == RoadConditionType.WORK_ZONE || it.type == RoadConditionType.CONSTRUCTION
        }
        val waterlogging = map.conditions.count { it.type == RoadConditionType.WATERLOGGING }

        val geometryConfidence = map.segments.map { it.confidence }.ifEmpty { listOf(0.5) }.average()

        val health = clamp(100.0 - (defects / km) * 18.0 - (waterlogging / km) * 10.0)
        val quality = clamp(geometryConfidence * 100.0 - (defects / km) * 8.0)
        val construction100 = clamp((construction / km) * 40.0, lo = 0.0, hi = 100.0)
        val safety = clamp(
            100.0 - (defects / km) * 10.0 - (waterlogging / km) * 15.0 -
                (map.intersections.size / km) * 6.0 - construction100 * 0.2,
        )
        val controlled = map.signals.size + map.signs.size + map.speedBreakers.size
        val trafficDensity = clamp((controlled / km) * 25.0, lo = 0.0, hi = 100.0)

        return RoadIntelligence(
            healthScore = health,
            qualityScore = quality,
            trafficDensityScore = trafficDensity,
            safetyScore = safety,
            constructionScore = construction100,
        )
    }

    private fun clamp(v: Double, lo: Double = 0.0, hi: Double = 100.0): Double = v.coerceIn(lo, hi)
}
