package com.blurabbit.hdmap.sensors.impl

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import com.blurabbit.hdmap.core.clock.ClockSynchronizer
import com.blurabbit.hdmap.domain.sensor.GnssSample
import com.blurabbit.hdmap.sensors.GnssRecord
import com.blurabbit.hdmap.sensors.RateTracker
import com.blurabbit.hdmap.sensors.SensorHealthSnapshot
import com.blurabbit.hdmap.sensors.SensorRecord
import com.blurabbit.hdmap.sensors.SensorSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject

/**
 * GNSS source. Emits [GnssSample]s carrying lat/lon/alt/speed/bearing/accuracies plus satellite
 * counts (constellation breakdown incl. NavIC = CONSTELLATION_IRNSS observed via the status
 * callback). Uses `Location.getElapsedRealtimeNanos()`, already on the unified timebase, so the
 * source is registered as an identity source (zero offset).
 */
class GnssSensorSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sync: ClockSynchronizer,
) : SensorSource {

    override val id: String = "gnss"

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val fixTracker = RateTracker(expectedHz = 1.0)
    private var thread: HandlerThread? = null
    private var statusCallback: GnssStatus.Callback? = null
    private var androidListener: android.location.LocationListener? = null
    @Volatile private var lastSatTotal = 0
    @Volatile private var lastSatUsed = 0

    override fun isAvailable(): Boolean =
        locationManager.allProviders.contains(LocationManager.GPS_PROVIDER)

    @SuppressLint("MissingPermission") // caller guarantees ACCESS_FINE_LOCATION before start()
    override fun start(scope: CoroutineScope): Flow<SensorRecord> = callbackFlow {
        sync.registerIdentitySource(id)
        val producer = this
        val ht = HandlerThread("gnss").apply { start() }
        thread = ht
        val handler = Handler(ht.looper)

        val listener = android.location.LocationListener { loc -> emitFix(producer, loc) }
        androidListener = listener
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, ht.looper,
            )
        } catch (_: SecurityException) { /* permission revoked mid-run */ }

        val cb = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) = updateSatellites(status)
        }
        statusCallback = cb
        try {
            locationManager.registerGnssStatusCallback(cb, handler)
        } catch (_: SecurityException) {}

        awaitClose { stopInternal() }
    }

    private fun emitFix(producer: ProducerScope<SensorRecord>, loc: Location) {
        // elapsedRealtimeNanos() is already on the unified clock.
        val unified = loc.elapsedRealtimeNanos
        fixTracker.onSample(unified)
        val sample = GnssSample(
            unifiedNs = unified,
            lat = loc.latitude,
            lon = loc.longitude,
            altM = if (loc.hasAltitude()) loc.altitude else 0.0,
            speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0,
            bearingDeg = if (loc.hasBearing()) loc.bearing.toDouble() else 0.0,
            horizontalAccM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 0.0,
            verticalAccM = if (loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters.toDouble() else 0.0,
            speedAccMps = if (loc.hasSpeedAccuracy()) loc.speedAccuracyMetersPerSecond.toDouble() else 0.0,
            bearingAccDeg = if (loc.hasBearingAccuracy()) loc.bearingAccuracyDegrees.toDouble() else 0.0,
            satellitesUsed = lastSatUsed,
            satellitesTotal = lastSatTotal,
        )
        producer.trySend(GnssRecord(sample))
    }

    private fun updateSatellites(status: GnssStatus) {
        var used = 0
        for (i in 0 until status.satelliteCount) {
            if (status.usedInFix(i)) used++
        }
        lastSatTotal = status.satelliteCount
        lastSatUsed = used
    }

    override fun stop() = stopInternal()

    @SuppressLint("MissingPermission")
    private fun stopInternal() {
        androidListener?.let { runCatching { locationManager.removeUpdates(it) } }
        statusCallback?.let { runCatching { locationManager.unregisterGnssStatusCallback(it) } }
        androidListener = null
        statusCallback = null
        thread?.quitSafely(); thread = null
    }

    /** Latest satellite counts (total to used) for the dashboard GPS-quality widget. */
    fun satelliteCounts(): Pair<Int, Int> = lastSatTotal to lastSatUsed

    override fun health(): SensorHealthSnapshot = fixTracker.snapshot(id, driftMs = 0.0)
}
