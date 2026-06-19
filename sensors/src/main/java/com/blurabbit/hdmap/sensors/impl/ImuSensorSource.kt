package com.blurabbit.hdmap.sensors.impl

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.blurabbit.hdmap.core.clock.ClockSynchronizer
import com.blurabbit.hdmap.domain.sensor.ImuSample
import com.blurabbit.hdmap.domain.sensor.ImuStream
import com.blurabbit.hdmap.domain.sensor.OrientationSample
import com.blurabbit.hdmap.sensors.ImuRecord
import com.blurabbit.hdmap.sensors.OrientationRecord
import com.blurabbit.hdmap.sensors.RateTracker
import com.blurabbit.hdmap.sensors.SensorHealthSnapshot
import com.blurabbit.hdmap.sensors.SensorRecord
import com.blurabbit.hdmap.sensors.SensorSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.sqrt
import javax.inject.Inject

/**
 * Inertial sources: accelerometer, gyroscope, magnetometer, rotation vector, gravity, and linear
 * acceleration. Runs on a dedicated [HandlerThread] and requests the fastest sampling (~2.5 ms →
 * ≥100 Hz, well above the 100 Hz spec minimum) with a 20 ms hardware batch window so the sensor
 * FIFO wakes the CPU less often.
 *
 * Every `SensorEvent.timestamp` is mapped onto the unified clock via [ClockSynchronizer] because
 * its native epoch is device-dependent.
 */
class ImuSensorSource @Inject constructor(
    @ApplicationContext context: Context,
    private val sync: ClockSynchronizer,
) : SensorSource {

    override val id: String = "imu"

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private data class Stream(
        val type: Int,
        val sourceId: String,
        val expectedHz: Double,
        val imuStream: ImuStream? = null,
        val quaternion: Boolean = false,
    )

    private val streams = listOf(
        Stream(Sensor.TYPE_ACCELEROMETER, "imu.accel", 200.0, ImuStream.ACCEL),
        Stream(Sensor.TYPE_GYROSCOPE, "imu.gyro", 200.0, ImuStream.GYRO),
        Stream(Sensor.TYPE_MAGNETIC_FIELD, "imu.mag", 50.0, ImuStream.MAG),
        Stream(Sensor.TYPE_ROTATION_VECTOR, "imu.rotation", 100.0, quaternion = true),
        Stream(Sensor.TYPE_GRAVITY, "imu.gravity", 100.0, ImuStream.GRAVITY),
        Stream(Sensor.TYPE_LINEAR_ACCELERATION, "imu.linear", 100.0, ImuStream.LINEAR_ACCEL),
    )

    private val trackers = streams.associate { it.sourceId to RateTracker(it.expectedHz) }
    private val streamByType = streams.associateBy { it.type }

    private var handlerThread: HandlerThread? = null
    private var listener: SensorEventListener? = null

    override fun start(scope: CoroutineScope): Flow<SensorRecord> = callbackFlow {
        val thread = HandlerThread("imu-sensors").apply { start() }
        handlerThread = thread
        val handler = Handler(thread.looper)

        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val stream = streamByType[event.sensor.type] ?: return
                val unified = sync.observeAndConvert(stream.sourceId, event.timestamp)
                trackers[stream.sourceId]?.onSample(unified)
                val record: SensorRecord = if (stream.quaternion) {
                    val v = event.values
                    val w = if (v.size >= 4) v[3] else computeW(v[0], v[1], v[2])
                    OrientationRecord(
                        OrientationSample(
                            unifiedNs = unified,
                            w = w.toDouble(),
                            x = v[0].toDouble(),
                            y = v[1].toDouble(),
                            z = v[2].toDouble(),
                            headingAccuracyRad = if (v.size >= 5) v[4].toDouble() else 0.0,
                        ),
                    )
                } else {
                    ImuRecord(
                        ImuSample(
                            unifiedNs = unified,
                            stream = stream.imuStream!!,
                            x = event.values[0].toDouble(),
                            y = event.values.getOrElse(1) { 0f }.toDouble(),
                            z = event.values.getOrElse(2) { 0f }.toDouble(),
                            accuracy = event.accuracy,
                        ),
                    )
                }
                if (!trySend(record).isSuccess) trackers[stream.sourceId]?.onDropped()
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }
        listener = l

        streams.forEach { s ->
            val sensor = sensorManager.getDefaultSensor(s.type) ?: return@forEach
            sensorManager.registerListener(l, sensor, 2_500, 20_000, handler)
        }

        awaitClose { stopInternal() }
    }

    override fun stop() = stopInternal()

    private fun stopInternal() {
        listener?.let { sensorManager.unregisterListener(it) }
        listener = null
        handlerThread?.quitSafely()
        handlerThread = null
    }

    override fun health(): SensorHealthSnapshot {
        val driftMs = (sync.currentOffsetNs("imu.accel") ?: 0L) / 1_000_000.0
        return trackers.values
            .map { it.snapshot(id, driftMs) }
            .minByOrNull { it.actualHz / (it.expectedHz.takeIf { hz -> hz > 0 } ?: 1.0) }
            ?: SensorHealthSnapshot(id, 0.0, 0.0, 0, driftMs, true)
    }

    /** Reconstruct the quaternion scalar component when the rotation-vector omits it. */
    private fun computeW(x: Float, y: Float, z: Float): Float {
        val t = 1f - (x * x + y * y + z * z)
        return if (t > 0f) sqrt(t) else 0f
    }
}
