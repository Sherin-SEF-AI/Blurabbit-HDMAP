package com.blurabbit.hdmap.mapping

import com.blurabbit.hdmap.domain.mapping.VoEstimate
import com.blurabbit.hdmap.domain.sensor.GnssSample
import com.blurabbit.hdmap.domain.sensor.ImuSample
import com.blurabbit.hdmap.domain.sensor.OrientationSample

/** Replayed sensor streams for one trip, fed to an [OdometryBackend]. */
data class OdometryInput(
    val tripId: String,
    val gnss: List<GnssSample>,
    val imu: List<ImuSample> = emptyList(),
    val orientation: List<OrientationSample> = emptyList(),
    val vo: List<VoEstimate> = emptyList(),
)

/**
 * Pluggable trajectory backend. The shipped baseline is GNSS-primary dead reckoning with IMU
 * heading assist ([GnssImuDeadReckoning]). Visual / visual-inertial backends (ORB-SLAM3,
 * OpenVINS, VINS-Fusion) implement the same seam and are swapped in via the future native bridge —
 * no change to the feature extractor or storage.
 */
interface OdometryBackend {
    val name: String
    fun estimate(input: OdometryInput): com.blurabbit.hdmap.domain.mapping.Trajectory
}
