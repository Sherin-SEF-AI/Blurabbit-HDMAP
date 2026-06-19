package com.blurabbit.hdmap.mapping

import com.blurabbit.hdmap.domain.mapping.VoEstimate
import com.blurabbit.hdmap.domain.sensor.GnssSample
import com.blurabbit.hdmap.domain.sensor.ImuSample
import com.blurabbit.hdmap.domain.sensor.ImuStream
import com.blurabbit.hdmap.domain.sensor.OrientationSample
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MappingTest {

    /** A synthetic straight eastward drive: ~10 fixes spaced ~10 m apart. */
    private fun straightDrive(): List<GnssSample> {
        val out = ArrayList<GnssSample>()
        var lon = 77.5946
        for (i in 0 until 10) {
            out += GnssSample(
                unifiedNs = i * 1_000_000_000L,
                lat = 12.9716,
                lon = lon,
                altM = 900.0,
                speedMps = 10.0,
                bearingDeg = 90.0,
                horizontalAccM = 4.0,
            )
            lon += 0.0001 // ~10.8 m east at this latitude
        }
        return out
    }

    @Test
    fun deadReckoning_producesTrajectoryFromGnss() {
        val traj = GnssImuDeadReckoning().estimate(OdometryInput("trip_x", straightDrive()))
        assertThat(traj.poses).isNotEmpty()
        assertThat(traj.poses.first().geo.lon).isWithin(1e-9).of(77.5946)
    }

    @Test
    fun extractor_buildsRoadSegmentAndEdges() {
        val traj = GnssImuDeadReckoning().estimate(OdometryInput("trip_x", straightDrive()))
        val map = MapFeatureExtractor(IntersectionDetector()).extract(traj, emptyList(), generatedAtMs = 0)
        assertThat(map.segments).hasSize(1)
        assertThat(map.segments.first().lengthM).isGreaterThan(50.0)
        // One left + one right road edge synthesized from the centerline.
        assertThat(map.roadEdges).hasSize(2)
        assertThat(map.lanes).hasSize(1)
    }

    @Test
    fun extractor_collapsesDetectionRunIntoOneFeature() {
        val traj = GnssImuDeadReckoning().estimate(OdometryInput("trip_x", straightDrive()))
        // One traffic light seen across 12 consecutive frames (0.2 s apart) → must be ONE signal,
        // not 12. A 13th sighting 6 s later is a different signal → 2 total.
        val dets = ArrayList<com.blurabbit.hdmap.domain.perception.Detection>()
        for (i in 0 until 12) dets += com.blurabbit.hdmap.domain.perception.Detection(
            com.blurabbit.hdmap.domain.perception.DetectionKind.TRAFFIC_SIGNAL, "traffic light", 0.55,
            frameUnifiedNs = i * 200_000_000L,
        )
        dets += com.blurabbit.hdmap.domain.perception.Detection(
            com.blurabbit.hdmap.domain.perception.DetectionKind.TRAFFIC_SIGNAL, "traffic light", 0.6,
            frameUnifiedNs = 8_000_000_000L,
        )
        val map = MapFeatureExtractor(IntersectionDetector()).extract(traj, dets, generatedAtMs = 0)
        assertThat(map.signals).hasSize(2)
    }

    @Test
    fun roadEventDetector_flagsVerticalJoltAsEvent() {
        val traj = GnssImuDeadReckoning().estimate(OdometryInput("trip_x", straightDrive()))
        // Calm vertical accel with one strong bump at ~4.5s (vehicle moving at 10 m/s).
        val imu = ArrayList<ImuSample>()
        val gravity = (0..900).map { i ->
            ImuSample(i * 10_000_000L, ImuStream.GRAVITY, 0.0, 0.0, 9.81)
        }
        val linear = (0..900).map { i ->
            val ns = i * 10_000_000L
            val z = if (i in 448..452) 5.0 else 0.1 // sharp bump on the vertical (z) axis
            ImuSample(ns, ImuStream.LINEAR_ACCEL, 0.0, 0.0, z)
        }
        imu += gravity; imu += linear

        val events = RoadEventDetector().detect(traj, imu)
        val total = events.speedBreakers.size + events.conditions.count {
            it.type.name == "POTHOLE"
        }
        assertThat(total).isAtLeast(1)
    }

    @Test
    fun ekf_smoothsNoisyGnssTowardTheTrueLine() {
        // True path: straight east. GNSS fixes jittered north by ±a few metres; IMU reports ~zero
        // linear accel with a level (identity) orientation. The EKF should pull the path back toward
        // the straight line, i.e. lower north-coordinate variance than the raw fixes.
        val jitter = doubleArrayOf(0.0, 3e-5, -3e-5, 2e-5, -2e-5, 3e-5, -3e-5, 1e-5, -1e-5, 0.0)
        val gnss = (0 until 10).map { i ->
            GnssSample(
                unifiedNs = i * 1_000_000_000L,
                lat = 12.9716 + jitter[i],
                lon = 77.5946 + i * 0.0001,
                altM = 900.0,
                speedMps = 10.0,
                bearingDeg = 90.0,
                horizontalAccM = 5.0,
            )
        }
        val imu = (0..1000).map { i ->
            ImuSample(i * 10_000_000L, ImuStream.LINEAR_ACCEL, 0.0, 0.0, 0.0)
        }
        val orient = (0..100).map { i ->
            OrientationSample(i * 100_000_000L, w = 1.0, x = 0.0, y = 0.0, z = 0.0)
        }

        val input = OdometryInput("trip_ekf", gnss, imu, orient)
        val ekf = EkfGnssImuBackend().estimate(input)
        assertThat(ekf.poses).hasSize(10)

        fun northVar(traj: com.blurabbit.hdmap.domain.mapping.Trajectory) =
            traj.poses.map { it.enu.north }.let { ns -> val m = ns.average(); ns.sumOf { (it - m) * (it - m) } / ns.size }

        val raw = GnssImuDeadReckoning().estimate(OdometryInput("trip_raw", gnss))
        assertThat(northVar(ekf)).isLessThan(northVar(raw))
    }

    @Test
    fun ekf_fallsBackToDeadReckoningWithoutImu() {
        val traj = EkfGnssImuBackend().estimate(OdometryInput("trip_x", straightDrive()))
        assertThat(traj.poses).isNotEmpty()
    }

    @Test
    fun visualInertial_usesVisualYawAtLowSpeed() {
        // Crawling forward (0.5 m/s, GNSS bearing unreliable) while the camera yaws — VO supplies
        // the heading the GNSS can't, so the fused heading should track the integrated visual yaw.
        val gnss = (0 until 6).map { i ->
            GnssSample(i * 1_000_000_000L, 12.9700 + i * 0.000002, 77.5900, 900.0,
                speedMps = 0.5, bearingDeg = 0.0, horizontalAccM = 5.0)
        }
        val vo = (0 until 6).map { i ->
            VoEstimate(i * 1_000_000_000L + 500_000_000L, dtMs = 200, dYawDeg = 3.0,
                forwardScore = 0.1, tracked = 40, confidence = 0.8)
        }
        val traj = VisualInertialBackend().estimate(OdometryInput("t", gnss, vo = vo))
        assertThat(traj.poses).hasSize(6)
        assertThat(traj.poses.last().headingDeg).isGreaterThan(5.0) // visual yaw accumulated

        // Without VO the heading stays ~0.
        val noVo = VisualInertialBackend().estimate(OdometryInput("t", gnss))
        assertThat(noVo.poses.last().headingDeg).isLessThan(5.0)
    }
}
