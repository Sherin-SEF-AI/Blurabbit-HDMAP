package com.blurabbit.hdmap.mapping

import com.blurabbit.hdmap.domain.mapping.Trajectory
import javax.inject.Inject

/**
 * Visual-inertial odometry: the GNSS-inertial EKF ([EkfGnssImuBackend]) for position/velocity, with
 * the monocular **visual-odometry front-end** ([com.blurabbit.hdmap.perception.VisualOdometry])
 * supplying heading where GNSS can't — at low speed and through slow turns, where GNSS bearing is
 * unreliable. Between high-speed (GNSS-trusted) poses, the per-frame visual yaw increments are
 * integrated to carry heading, then re-anchored when the vehicle is moving fast enough for GNSS
 * bearing to be trustworthy.
 *
 * This is the front-end + loose fusion of visual SLAM. The back-end (loop closure + global bundle
 * adjustment, e.g. ORB-SLAM3 / OpenVINS) plugs into this same [OdometryBackend] seam via a native
 * JNI bridge for sub-decimetre, globally-consistent maps.
 */
class VisualInertialBackend @Inject constructor() : OdometryBackend {

    override val name: String = "visual-inertial-vo"

    private val ekf = EkfGnssImuBackend()

    override fun estimate(input: OdometryInput): Trajectory {
        val base = ekf.estimate(input)
        if (input.vo.isEmpty() || base.poses.size < 2) return base

        val vo = input.vo.filter { it.confidence >= MIN_VO_CONF }.sortedBy { it.unifiedNs }
        if (vo.isEmpty()) return base

        var anchorHeading = base.poses.first().headingDeg
        var anchorNs = base.poses.first().unifiedNs
        val out = ArrayList(base.poses)
        for (i in out.indices) {
            val p = out[i]
            if (p.speedMps > GNSS_TRUST_MPS) {
                anchorHeading = p.headingDeg
                anchorNs = p.unifiedNs
            } else {
                // Carry heading visually from the last GNSS-trusted anchor.
                var yaw = 0.0
                for (e in vo) if (e.unifiedNs in (anchorNs + 1)..p.unifiedNs) yaw += e.dYawDeg
                out[i] = p.copy(headingDeg = (anchorHeading + yaw + 360.0) % 360.0)
            }
        }
        return base.copy(poses = out)
    }

    private companion object {
        const val GNSS_TRUST_MPS = 2.5
        const val MIN_VO_CONF = 0.2
    }
}
