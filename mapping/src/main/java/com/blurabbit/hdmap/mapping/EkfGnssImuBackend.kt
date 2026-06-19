package com.blurabbit.hdmap.mapping

import com.blurabbit.hdmap.domain.geo.EnuPoint
import com.blurabbit.hdmap.domain.geo.Geo
import com.blurabbit.hdmap.domain.geo.GeoPoint
import com.blurabbit.hdmap.domain.mapping.Trajectory
import com.blurabbit.hdmap.domain.mapping.TrajectoryPose
import com.blurabbit.hdmap.domain.sensor.ImuStream
import com.blurabbit.hdmap.domain.sensor.OrientationSample
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import javax.inject.Inject

/**
 * Loosely-coupled GNSS-inertial odometry. A Kalman filter in the local ENU frame fuses
 * double-integrated inertial acceleration (predict, at IMU rate) with GNSS position + velocity
 * (update, at each fix), producing a drift-corrected, smoothed trajectory that is far steadier than
 * raw fixes between GNSS epochs.
 *
 * Implementation: two decoupled 2-state filters `[pos, vel]` — one for East, one for North (the
 * process and measurements are axis-independent here), so the math is exact 2×2 and dependency-free.
 * Body-frame linear acceleration is rotated into ENU using the device rotation quaternion
 * (`TYPE_ROTATION_VECTOR`), whose world frame is already East-North-Up on Android.
 *
 * Falls back to [GnssImuDeadReckoning] when inertial data is unavailable. The visual-inertial
 * upgrade (ORB-SLAM3 / OpenVINS via a native JNI bridge) implements this same [OdometryBackend] seam.
 */
class EkfGnssImuBackend @Inject constructor() : OdometryBackend {

    override val name: String = "gnss-inertial-ekf"

    override fun estimate(input: OdometryInput): Trajectory {
        val fixes = input.gnss
            .filter { it.lat != 0.0 || it.lon != 0.0 }
            .filter { it.horizontalAccM <= MAX_ACCURACY_M || it.horizontalAccM == 0.0 }
            .sortedBy { it.unifiedNs }
        val accel = input.imu.filter { it.stream == ImuStream.LINEAR_ACCEL }.sortedBy { it.unifiedNs }
        val orient = input.orientation.sortedBy { it.unifiedNs }

        // No inertial data → the GNSS-only baseline already does the right thing.
        if (fixes.size < 2 || accel.isEmpty() || orient.isEmpty()) {
            return GnssImuDeadReckoning().estimate(input)
        }

        val origin = GeoPoint(fixes.first().lat, fixes.first().lon, fixes.first().altM)
        val first = fixes.first()
        val firstEnu = Geo.toEnu(origin, GeoPoint(first.lat, first.lon, first.altM))
        val (v0e, v0n) = velocityEnu(first.speedMps, first.bearingDeg)
        val initPosVar = (first.horizontalAccM.takeIf { it > 0 } ?: 5.0).let { it * it }
        val kfE = AxisKf(firstEnu.east, v0e, initPosVar)
        val kfN = AxisKf(firstEnu.north, v0n, initPosVar)

        // Merge IMU (predict) and GNSS (update) events on one timeline.
        val poses = ArrayList<TrajectoryPose>(fixes.size)
        var ai = 0
        var lastNs = first.unifiedNs
        // Current world-frame acceleration held between IMU samples.
        var aE = 0.0; var aN = 0.0

        for (fix in fixes) {
            // Apply all IMU predicts up to this fix.
            while (ai < accel.size && accel[ai].unifiedNs <= fix.unifiedNs) {
                val s = accel[ai]
                val dt = (s.unifiedNs - lastNs).coerceAtLeast(0) / 1e9
                if (dt in 1e-4..1.0) { kfE.predict(dt, aE); kfN.predict(dt, aN) }
                val q = nearest(orient, s.unifiedNs)
                val (wx, wy, _) = rotateToWorld(q, s.x, s.y, s.z)
                aE = wx; aN = wy
                lastNs = s.unifiedNs
                ai++
            }
            // Predict to the fix time, then update with GNSS position + velocity.
            val dt = (fix.unifiedNs - lastNs).coerceAtLeast(0) / 1e9
            if (dt in 1e-4..2.0) { kfE.predict(dt, aE); kfN.predict(dt, aN) }
            lastNs = fix.unifiedNs

            val enu = Geo.toEnu(origin, GeoPoint(fix.lat, fix.lon, fix.altM))
            val posR = (fix.horizontalAccM.takeIf { it > 0 } ?: 8.0).let { it * it }
            kfE.updatePos(enu.east, posR); kfN.updatePos(enu.north, posR)
            if (fix.speedMps > 0.3) {
                val (ze, zn) = velocityEnu(fix.speedMps, fix.bearingDeg)
                val velR = (fix.speedAccMps.takeIf { it > 0 } ?: 1.0).let { it * it }
                kfE.updateVel(ze, velR); kfN.updateVel(zn, velR)
            }

            val geo = Geo.fromEnu(origin, EnuPoint(kfE.pos, kfN.pos))
            val speed = hypot(kfE.vel, kfN.vel)
            val heading = if (speed > MOVING_MPS) (atan2(kfE.vel, kfN.vel) * 180.0 / PI + 360.0) % 360.0
            else fix.bearingDeg
            poses += TrajectoryPose(fix.unifiedNs, geo, EnuPoint(kfE.pos, kfN.pos), heading, speed)
        }
        return Trajectory(input.tripId, origin, poses)
    }

    /** A 2-state `[position, velocity]` Kalman filter for one axis, with acceleration as control. */
    private class AxisKf(var pos: Double, var vel: Double, posVar: Double) {
        // covariance P = [[p00,p01],[p10,p11]]
        private var p00 = posVar; private var p01 = 0.0; private var p10 = 0.0; private var p11 = 4.0

        fun predict(dt: Double, accel: Double) {
            pos += vel * dt + 0.5 * accel * dt * dt
            vel += accel * dt
            // P = F P Fᵀ + Q,  F = [[1,dt],[0,1]]
            val n00 = p00 + dt * (p10 + p01) + dt * dt * p11
            val n01 = p01 + dt * p11
            val n10 = p10 + dt * p11
            val n11 = p11
            val sa = ACCEL_NOISE * ACCEL_NOISE
            val dt2 = dt * dt
            p00 = n00 + sa * dt2 * dt2 / 4.0
            p01 = n01 + sa * dt2 * dt / 2.0
            p10 = n10 + sa * dt2 * dt / 2.0
            p11 = n11 + sa * dt2
        }

        fun updatePos(z: Double, r: Double) {
            // H = [1,0]; innovation in position
            val s = p00 + r
            val k0 = p00 / s; val k1 = p10 / s
            val y = z - pos
            pos += k0 * y; vel += k1 * y
            val a00 = (1 - k0) * p00; val a01 = (1 - k0) * p01
            val a10 = p10 - k1 * p00; val a11 = p11 - k1 * p01
            p00 = a00; p01 = a01; p10 = a10; p11 = a11
        }

        fun updateVel(z: Double, r: Double) {
            // H = [0,1]; innovation in velocity
            val s = p11 + r
            val k0 = p01 / s; val k1 = p11 / s
            val y = z - vel
            pos += k0 * y; vel += k1 * y
            val a00 = p00 - k0 * p10; val a01 = p01 - k0 * p11
            val a10 = (1 - k1) * p10; val a11 = (1 - k1) * p11
            p00 = a00; p01 = a01; p10 = a10; p11 = a11
        }
    }

    private fun velocityEnu(speed: Double, bearingDeg: Double): Pair<Double, Double> {
        val b = bearingDeg * PI / 180.0
        return speed * sin(b) to speed * cos(b) // East, North
    }

    /** Rotate a body-frame vector into the ENU world frame via the rotation quaternion. */
    private fun rotateToWorld(q: OrientationSample, x: Double, y: Double, z: Double): Triple<Double, Double, Double> {
        val tx = 2.0 * (q.y * z - q.z * y)
        val ty = 2.0 * (q.z * x - q.x * z)
        val tz = 2.0 * (q.x * y - q.y * x)
        return Triple(
            x + q.w * tx + (q.y * tz - q.z * ty),
            y + q.w * ty + (q.z * tx - q.x * tz),
            z + q.w * tz + (q.x * ty - q.y * tx),
        )
    }

    private fun nearest(orient: List<OrientationSample>, ns: Long): OrientationSample =
        orient.minByOrNull { kotlin.math.abs(it.unifiedNs - ns) }!!

    private companion object {
        const val MAX_ACCURACY_M = 30.0
        const val MOVING_MPS = 0.8
        const val ACCEL_NOISE = 2.0 // process noise std (m/s²)
    }
}
