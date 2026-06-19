package com.blurabbit.hdmap.capture

import androidx.lifecycle.LifecycleOwner
import com.blurabbit.hdmap.capture.log.SensorLogWriter
import com.blurabbit.hdmap.core.clock.ClockSynchronizer
import com.blurabbit.hdmap.core.clock.DriftMonitor
import com.blurabbit.hdmap.core.clock.MonotonicClock
import com.blurabbit.hdmap.domain.util.Ids
import com.blurabbit.hdmap.domain.geo.Geo
import com.blurabbit.hdmap.domain.geo.GeoPoint
import com.blurabbit.hdmap.domain.model.Trip
import com.blurabbit.hdmap.domain.model.TripStatus
import com.blurabbit.hdmap.domain.repository.TripRepository
import com.blurabbit.hdmap.sensors.GnssRecord
import com.blurabbit.hdmap.sensors.ImuRecord
import com.blurabbit.hdmap.sensors.OrientationRecord
import com.blurabbit.hdmap.sensors.SensorSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the recording pipeline. Collects every available [SensorSource] plus camera-frame metadata
 * onto the unified clock, streams them to per-trip JSONL logs, maintains live trip statistics, and
 * persists the trip record on start/finish. A simple phase state machine (IDLE → RECORDING ⇄ PAUSED
 * → STOPPING → IDLE) drives the foreground service and dashboard.
 */
@Singleton
class TripRecorder @Inject constructor(
    private val sources: Set<@JvmSuppressWildcards SensorSource>,
    private val tripRepository: TripRepository,
    private val storage: TripStorage,
    private val clock: MonotonicClock,
    private val sync: ClockSynchronizer,
    private val driftMonitor: DriftMonitor,
    private val camera: CameraController,
) {
    private val _state = MutableStateFlow(RecordingState())
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private var scope: CoroutineScope? = null
    private var writer: SensorLogWriter? = null

    private val gpsSamples = AtomicLong(0)
    private val imuSamples = AtomicLong(0)
    private val frameCount = AtomicLong(0)
    private val detectionCount = AtomicLong(0)
    private val droppedWrites = AtomicLong(0)

    private val distanceLock = Any()
    @Volatile private var distanceMeters = 0.0
    @Volatile private var maxSpeedMps = 0.0
    @Volatile private var currentSpeedMps = 0.0
    @Volatile private var lastGnss: GeoPoint? = null
    @Volatile private var tripId: String? = null
    @Volatile private var tripName: String = ""
    @Volatile private var startElapsedNs: Long = 0

    fun start(owner: LifecycleOwner, name: String) {
        if (_state.value.phase != RecordingPhase.IDLE) return
        resetCounters()
        clock.captureEpochAnchor()
        sync.reset()
        driftMonitor.reset()

        val id = Ids.trip()
        tripId = id
        tripName = name.ifBlank { "Trip ${id.takeLast(6)}" }
        startElapsedNs = clock.nowNanos()
        val dir = storage.tripDir(id)
        writer = SensorLogWriter(dir)

        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        s.launch {
            tripRepository.upsert(
                Trip(
                    id = id,
                    name = tripName,
                    status = TripStatus.RECORDING,
                    startWallMs = clock.wallEpochMillisNow(),
                    startElapsedNs = startElapsedNs,
                    logDir = dir.absolutePath,
                ),
            )
        }

        sources.filter { it.isAvailable() }.forEach { source ->
            source.start(s)
                .onEach { record ->
                    val ok = writer?.let { runCatching { it.write(record) }.isSuccess } ?: false
                    if (!ok) droppedWrites.incrementAndGet()
                    onRecord(record)
                }
                .launchIn(s)
        }

        camera.start(
            owner,
            onFrame = { meta -> writer?.writeFrame(meta); frameCount.incrementAndGet() },
            onDetections = { dets -> writer?.writeDetections(dets); detectionCount.addAndGet(dets.size.toLong()) },
            onVo = { vo -> writer?.writeVo(vo) },
        )

        s.launch { stateTicker() }
        _state.value = RecordingState(
            phase = RecordingPhase.RECORDING,
            tripId = id,
            tripName = tripName,
            startElapsedNs = startElapsedNs,
        )
    }

    fun pause() {
        if (_state.value.phase != RecordingPhase.RECORDING) return
        sources.forEach { it.stop() }
        camera.stop()
        writer?.flush()
        _state.value = _state.value.copy(phase = RecordingPhase.PAUSED)
    }

    fun resume(owner: LifecycleOwner) {
        if (_state.value.phase != RecordingPhase.PAUSED) return
        val s = scope ?: return
        sources.filter { it.isAvailable() }.forEach { source ->
            source.start(s)
                .onEach { record ->
                    writer?.let { runCatching { it.write(record) } }
                    onRecord(record)
                }
                .launchIn(s)
        }
        camera.start(
            owner,
            onFrame = { meta -> writer?.writeFrame(meta); frameCount.incrementAndGet() },
            onDetections = { dets -> writer?.writeDetections(dets); detectionCount.addAndGet(dets.size.toLong()) },
            onVo = { vo -> writer?.writeVo(vo) },
        )
        _state.value = _state.value.copy(phase = RecordingPhase.RECORDING)
    }

    suspend fun stop() {
        val id = tripId ?: return
        _state.value = _state.value.copy(phase = RecordingPhase.STOPPING)
        sources.forEach { it.stop() }
        camera.stop()
        runCatching { scope?.cancel() }
        writer?.flush(); writer?.close(); writer = null

        val durationMs = (clock.nowNanos() - startElapsedNs) / 1_000_000L
        val existing = tripRepository.getTrip(id)
        tripRepository.upsert(
            (existing ?: Trip(id = id, name = tripName, status = TripStatus.COMPLETED, startWallMs = 0))
                .copy(
                    status = TripStatus.COMPLETED,
                    endWallMs = clock.wallEpochMillisNow(),
                    durationMs = durationMs,
                    distanceMeters = distanceMeters,
                    maxSpeedMps = maxSpeedMps,
                    gpsSamples = gpsSamples.get(),
                    imuSamples = imuSamples.get(),
                    frameCount = frameCount.get(),
                ),
        )
        scope = null
        tripId = null
        _state.value = RecordingState()
    }

    private fun onRecord(record: com.blurabbit.hdmap.sensors.SensorRecord) {
        when (record) {
            is GnssRecord -> {
                gpsSamples.incrementAndGet()
                val s = record.sample
                currentSpeedMps = s.speedMps
                if (s.speedMps > maxSpeedMps) maxSpeedMps = s.speedMps
                val point = GeoPoint(s.lat, s.lon, s.altM)
                synchronized(distanceLock) {
                    lastGnss?.let { distanceMeters += Geo.haversineMeters(it, point) }
                    lastGnss = point
                }
            }
            is ImuRecord -> imuSamples.incrementAndGet()
            is OrientationRecord -> imuSamples.incrementAndGet()
        }
    }

    private suspend fun stateTicker() {
        while (scope?.isActive == true) {
            val now = clock.nowNanos()
            val gnss = sources.filterIsInstance<com.blurabbit.hdmap.sensors.impl.GnssSensorSource>().firstOrNull()
            val sats = gnss?.satelliteCounts() ?: (0 to 0)
            _state.value = _state.value.copy(
                durationMs = (now - startElapsedNs) / 1_000_000L,
                currentSpeedMps = currentSpeedMps,
                maxSpeedMps = maxSpeedMps,
                distanceMeters = distanceMeters,
                satellitesTotal = sats.first,
                satellitesUsed = sats.second,
                gpsSamples = gpsSamples.get(),
                imuSamples = imuSamples.get(),
                frameCount = frameCount.get(),
                detections = detectionCount.get(),
                droppedWrites = droppedWrites.get(),
                sensorHealth = sources.map { it.health() },
            )
            delay(500)
        }
    }

    private fun resetCounters() {
        gpsSamples.set(0); imuSamples.set(0); frameCount.set(0); detectionCount.set(0); droppedWrites.set(0)
        distanceMeters = 0.0; maxSpeedMps = 0.0; currentSpeedMps = 0.0; lastGnss = null
    }
}
