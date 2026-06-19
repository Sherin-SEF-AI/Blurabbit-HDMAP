package com.blurabbit.hdmap.capture.log

import com.blurabbit.hdmap.domain.mapping.VoEstimate
import com.blurabbit.hdmap.domain.perception.Detection
import com.blurabbit.hdmap.domain.sensor.CameraFrameMeta
import com.blurabbit.hdmap.domain.sensor.GnssSample
import com.blurabbit.hdmap.domain.sensor.ImuSample
import com.blurabbit.hdmap.domain.sensor.OrientationSample
import com.blurabbit.hdmap.sensors.GnssRecord
import com.blurabbit.hdmap.sensors.ImuRecord
import com.blurabbit.hdmap.sensors.OrientationRecord
import com.blurabbit.hdmap.sensors.SensorRecord
import kotlinx.serialization.json.Json
import java.io.BufferedWriter
import java.io.File

/** Canonical per-trip log filenames. */
object LogFiles {
    const val GNSS = "gnss.jsonl"
    const val IMU = "imu.jsonl"
    const val ORIENTATION = "orientation.jsonl"
    const val CAMERA = "camera.jsonl"
    const val DETECTIONS = "detections.jsonl"
    const val VO = "vo.jsonl"
}

/**
 * Append-only JSONL writer for one trip. Each sensor stream gets its own newline-delimited file so
 * the mapping pipeline can replay any stream independently. Writes are synchronized because sensor
 * sources may deliver on different threads.
 */
class SensorLogWriter(private val dir: File, private val json: Json = DEFAULT_JSON) {

    private val gnss: BufferedWriter by lazy { open(LogFiles.GNSS) }
    private val imu: BufferedWriter by lazy { open(LogFiles.IMU) }
    private val orientation: BufferedWriter by lazy { open(LogFiles.ORIENTATION) }
    private val camera: BufferedWriter by lazy { open(LogFiles.CAMERA) }
    private val detections: BufferedWriter by lazy { open(LogFiles.DETECTIONS) }
    private val vo: BufferedWriter by lazy { open(LogFiles.VO) }
    private val lock = Any()

    init {
        dir.mkdirs()
    }

    fun write(record: SensorRecord) = synchronized(lock) {
        when (record) {
            is GnssRecord -> gnss.appendLine(json.encodeToString(GnssSample.serializer(), record.sample))
            is ImuRecord -> imu.appendLine(json.encodeToString(ImuSample.serializer(), record.sample))
            is OrientationRecord ->
                orientation.appendLine(json.encodeToString(OrientationSample.serializer(), record.sample))
        }
    }

    fun writeFrame(meta: CameraFrameMeta) = synchronized(lock) {
        camera.appendLine(json.encodeToString(CameraFrameMeta.serializer(), meta))
    }

    fun writeDetections(list: List<Detection>) = synchronized(lock) {
        list.forEach { detections.appendLine(json.encodeToString(Detection.serializer(), it)) }
    }

    fun writeVo(estimate: VoEstimate) = synchronized(lock) {
        vo.appendLine(json.encodeToString(VoEstimate.serializer(), estimate))
    }

    fun flush() = synchronized(lock) {
        runCatching { gnss.flush(); imu.flush(); orientation.flush(); camera.flush(); detections.flush(); vo.flush() }
    }

    fun close() = synchronized(lock) {
        runCatching { gnss.close(); imu.close(); orientation.close(); camera.close(); detections.close(); vo.close() }
    }

    private fun open(name: String): BufferedWriter = File(dir, name).bufferedWriter()

    companion object {
        val DEFAULT_JSON = Json { encodeDefaults = true }
    }
}

/** Replays a trip's JSONL logs back into typed samples for the mapping pipeline. */
class SensorLogReader(private val dir: File, private val json: Json = SensorLogWriter.DEFAULT_JSON) {

    fun readGnss(): List<GnssSample> = readLines(LogFiles.GNSS) { json.decodeFromString(GnssSample.serializer(), it) }
    fun readImu(): List<ImuSample> = readLines(LogFiles.IMU) { json.decodeFromString(ImuSample.serializer(), it) }
    fun readOrientation(): List<OrientationSample> =
        readLines(LogFiles.ORIENTATION) { json.decodeFromString(OrientationSample.serializer(), it) }
    fun readFrames(): List<CameraFrameMeta> =
        readLines(LogFiles.CAMERA) { json.decodeFromString(CameraFrameMeta.serializer(), it) }
    fun readDetections(): List<Detection> =
        readLines(LogFiles.DETECTIONS) { json.decodeFromString(Detection.serializer(), it) }
    fun readVo(): List<VoEstimate> =
        readLines(LogFiles.VO) { json.decodeFromString(VoEstimate.serializer(), it) }

    private fun <T> readLines(name: String, parse: (String) -> T): List<T> {
        val f = File(dir, name)
        if (!f.exists()) return emptyList()
        return f.useLines { lines ->
            lines.filter { it.isNotBlank() }
                .mapNotNull { line -> runCatching { parse(line) }.getOrNull() }
                .toList()
        }
    }
}
