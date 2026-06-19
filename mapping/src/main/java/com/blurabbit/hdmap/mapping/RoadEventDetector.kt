package com.blurabbit.hdmap.mapping

import com.blurabbit.hdmap.domain.geo.GeoPoint
import com.blurabbit.hdmap.domain.hdmap.RoadCondition
import com.blurabbit.hdmap.domain.hdmap.RoadConditionType
import com.blurabbit.hdmap.domain.hdmap.SpeedBreaker
import com.blurabbit.hdmap.domain.mapping.Trajectory
import com.blurabbit.hdmap.domain.sensor.ImuSample
import com.blurabbit.hdmap.domain.sensor.ImuStream
import com.blurabbit.hdmap.domain.util.Ids
import kotlin.math.abs
import kotlin.math.sqrt
import javax.inject.Inject

/** Speed breakers, potholes, and rough-surface stretches detected from inertial data. */
data class RoadEvents(
    val speedBreakers: List<SpeedBreaker>,
    val conditions: List<RoadCondition>,
)

/**
 * Detects road-surface events from the IMU — a *real* perception capability that needs no ML model.
 *
 * It isolates the **vertical** acceleration by projecting linear acceleration (gravity already
 * removed) onto the gravity direction sampled from the GRAVITY stream, so braking/cornering don't
 * masquerade as bumps. Sharp vertical jolts above a threshold become point events (speed breaker vs
 * pothole, disambiguated by vehicle speed), and sustained high-variance stretches become rough-
 * surface conditions. Every event is georeferenced to the nearest synchronized trajectory pose.
 */
class RoadEventDetector @Inject constructor() {

    fun detect(trajectory: Trajectory, imu: List<ImuSample>): RoadEvents {
        if (trajectory.poses.size < 2 || imu.isEmpty()) return RoadEvents(emptyList(), emptyList())

        val gravity = imu.filter { it.stream == ImuStream.GRAVITY }.sortedBy { it.unifiedNs }
        val linear = imu.filter { it.stream == ImuStream.LINEAR_ACCEL }.sortedBy { it.unifiedNs }
        val source = linear.ifEmpty { imu.filter { it.stream == ImuStream.ACCEL }.sortedBy { it.unifiedNs } }
        if (source.isEmpty()) return RoadEvents(emptyList(), emptyList())
        val usingRawAccel = linear.isEmpty()

        // Vertical-acceleration series aligned to the trajectory (only while the vehicle is moving).
        data class V(val ns: Long, val vert: Double, val speed: Double, val geo: GeoPoint)
        val series = ArrayList<V>(source.size)
        var gIdx = 0
        for (s in source) {
            val pose = nearestPose(trajectory, s.unifiedNs)
            if (pose.speedMps < MOVING_MPS) continue
            val vert = if (usingRawAccel) {
                abs(sqrt(s.x * s.x + s.y * s.y + s.z * s.z) - GRAVITY_MAG)
            } else {
                // project linear accel onto the unit gravity vector nearest in time
                while (gIdx < gravity.size - 1 && gravity[gIdx + 1].unifiedNs <= s.unifiedNs) gIdx++
                val g = gravity.getOrNull(gIdx)
                if (g != null) {
                    val gm = sqrt(g.x * g.x + g.y * g.y + g.z * g.z).coerceAtLeast(1e-3)
                    abs((s.x * g.x + s.y * g.y + s.z * g.z) / gm)
                } else {
                    abs(s.z)
                }
            }
            series += V(s.unifiedNs, vert, pose.speedMps, pose.geo)
        }
        if (series.size < 3) return RoadEvents(emptyList(), emptyList())

        // Peak picking with a refractory window.
        val speedBreakers = ArrayList<SpeedBreaker>()
        val potholes = ArrayList<RoadCondition>()
        var lastEventNs: Long? = null
        for (i in 1 until series.size - 1) {
            val v = series[i]
            val isPeak = v.vert >= series[i - 1].vert && v.vert > series[i + 1].vert
            if (!isPeak || v.vert < PEAK_THRESHOLD) continue
            val prevEvent = lastEventNs
            if (prevEvent != null && v.ns - prevEvent < REFRACTORY_NS) continue
            lastEventNs = v.ns
            val confidence = ((v.vert - PEAK_THRESHOLD) / PEAK_THRESHOLD).coerceIn(0.3, 0.95)
            if (v.speed < SPEED_BREAKER_MAX_MPS && v.vert < POTHOLE_THRESHOLD) {
                speedBreakers += SpeedBreaker(
                    id = Ids.feature("sb"),
                    location = v.geo,
                    heightEstimateCm = estimateHeightCm(v.vert),
                    confidence = confidence,
                    timestampNs = v.ns,
                    sourceTrip = trajectory.tripId,
                )
            } else {
                potholes += RoadCondition(
                    id = Ids.feature("cond"),
                    geometry = listOf(v.geo),
                    type = RoadConditionType.POTHOLE,
                    severity = (v.vert / POTHOLE_THRESHOLD).coerceIn(0.3, 1.0),
                    confidence = confidence,
                    timestampNs = v.ns,
                    sourceTrip = trajectory.tripId,
                )
            }
        }

        // Rough-surface stretches: sliding-window RMS over the vertical series.
        val rough = roughStretches(series.map { it.ns to it.vert }, series.map { it.geo }, trajectory.tripId)

        return RoadEvents(speedBreakers, potholes + rough)
    }

    private fun roughStretches(
        series: List<Pair<Long, Double>>,
        geos: List<GeoPoint>,
        tripId: String,
    ): List<RoadCondition> {
        if (series.size < ROUGH_WINDOW) return emptyList()
        val out = ArrayList<RoadCondition>()
        var i = 0
        while (i + ROUGH_WINDOW <= series.size) {
            val window = series.subList(i, i + ROUGH_WINDOW)
            val rms = sqrt(window.sumOf { it.second * it.second } / window.size)
            if (rms > ROUGH_RMS_THRESHOLD) {
                out += RoadCondition(
                    id = Ids.feature("cond"),
                    geometry = listOf(geos[i], geos[i + ROUGH_WINDOW - 1]),
                    type = RoadConditionType.ROUGH_SURFACE,
                    severity = (rms / ROUGH_RMS_THRESHOLD - 1.0).coerceIn(0.2, 1.0),
                    confidence = 0.5,
                    timestampNs = series[i].first,
                    sourceTrip = tripId,
                )
                i += ROUGH_WINDOW // non-overlapping reports
            } else {
                i += ROUGH_WINDOW / 2
            }
        }
        return out
    }

    private fun nearestPose(trajectory: Trajectory, ns: Long) =
        trajectory.poses.minByOrNull { abs(it.unifiedNs - ns) }!!

    /** Very rough height proxy from jolt magnitude — for display/ranking only, not survey-grade. */
    private fun estimateHeightCm(vert: Double): Double = (vert * 1.5).coerceIn(2.0, 20.0)

    private companion object {
        const val MOVING_MPS = 1.5
        const val GRAVITY_MAG = 9.81
        const val PEAK_THRESHOLD = 2.5            // m/s² vertical jolt to register an event
        const val POTHOLE_THRESHOLD = 6.0         // sharper than this (at speed) → pothole
        const val SPEED_BREAKER_MAX_MPS = 9.0     // breakers are taken slowly
        const val REFRACTORY_NS = 1_200_000_000L  // 1.2 s between point events
        const val ROUGH_WINDOW = 40
        const val ROUGH_RMS_THRESHOLD = 1.6       // m/s² sustained → rough surface
    }
}
