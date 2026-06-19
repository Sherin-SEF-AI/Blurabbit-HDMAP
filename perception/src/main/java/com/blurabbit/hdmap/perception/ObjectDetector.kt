package com.blurabbit.hdmap.perception

import android.content.Context
import android.graphics.Bitmap
import com.blurabbit.hdmap.domain.perception.Detection
import com.blurabbit.hdmap.domain.perception.DetectionKind
import com.blurabbit.hdmap.domain.perception.NormBox
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real on-device object detector backed by the bundled SSD MobileNet v1 COCO model
 * (`assets/models/detect.tflite`). It detects traffic lights and stop signs and maps them to the
 * HD-map perception kinds; the mapping pipeline then lifts them to world space via the trajectory.
 *
 * The model is a quantized SSD: uint8 300×300 input, four float outputs (boxes, classes, scores,
 * count) that are already non-max-suppressed, so no manual NMS is needed. It loads CPU-only because
 * the detection post-process op is not delegate-friendly. A one-frame cache (keyed by the frame's
 * unified timestamp) means the sign and signal adapters share a single inference per frame.
 */
@Singleton
class ObjectDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runtime: TfliteRuntime,
) {
    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var loaded = false
    private var labels: List<String> = emptyList()

    private val inputBuffer = ByteBuffer.allocateDirect(INPUT * INPUT * 3).apply { order(ByteOrder.nativeOrder()) }
    private val pixels = IntArray(INPUT * INPUT)

    private val cacheLock = Any()
    private var cacheNs: Long = Long.MIN_VALUE
    private var cacheResult: List<Detection> = emptyList()

    val isModelAvailable: Boolean get() { ensureLoaded(); return interpreter != null }

    /** All supported detections for a frame (traffic lights + stop signs). Cached per frame. */
    fun detect(frame: PerceptionFrame): List<Detection> {
        val bitmap = frame.bitmap ?: return emptyList()
        ensureLoaded()
        val itp = interpreter ?: return emptyList()
        synchronized(cacheLock) {
            if (frame.unifiedNs == cacheNs) return cacheResult
        }
        val result = runCatching {
            inferAll(itp, bitmap).mapNotNull { raw ->
                val kind = kindFor(raw.label) ?: return@mapNotNull null
                Detection(
                    kind = kind,
                    label = raw.label,
                    confidence = raw.score.toDouble(),
                    frameUnifiedNs = frame.unifiedNs,
                    box = raw.box,
                    attributes = mapOf("model" to "ssd_mobilenet_v1_coco"),
                )
            }
        }.getOrDefault(emptyList())
        synchronized(cacheLock) {
            cacheNs = frame.unifiedNs
            cacheResult = result
        }
        return result
    }

    /** Test hook: every COCO label (not just sign/signal) the model finds above threshold. */
    fun rawLabelsForTest(bitmap: Bitmap): List<Pair<String, Float>> {
        ensureLoaded()
        val itp = interpreter ?: return emptyList()
        return inferAll(itp, bitmap).map { it.label to it.score }
    }

    private data class RawDet(val label: String, val score: Float, val box: NormBox)

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        interpreter = runtime.loadCpuInterpreter(MODEL)
        labels = runCatching {
            context.assets.open(LABELS).bufferedReader().readLines().map { it.trim() }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun inferAll(itp: Interpreter, bitmap: Bitmap): List<RawDet> {
        val resized = if (bitmap.width == INPUT && bitmap.height == INPUT) bitmap
        else Bitmap.createScaledBitmap(bitmap, INPUT, INPUT, true)
        resized.getPixels(pixels, 0, INPUT, 0, 0, INPUT, INPUT)
        inputBuffer.rewind()
        for (p in pixels) {
            inputBuffer.put(((p shr 16) and 0xFF).toByte()) // R
            inputBuffer.put(((p shr 8) and 0xFF).toByte())  // G
            inputBuffer.put((p and 0xFF).toByte())           // B
        }
        if (resized != bitmap) resized.recycle()

        val boxes = Array(1) { Array(MAX_DET) { FloatArray(4) } }
        val classes = Array(1) { FloatArray(MAX_DET) }
        val scores = Array(1) { FloatArray(MAX_DET) }
        val count = FloatArray(1)
        val outputs = mapOf(0 to boxes, 1 to classes, 2 to scores, 3 to count)
        itp.runForMultipleInputsOutputs(arrayOf<Any>(inputBuffer), outputs)

        val n = count[0].toInt().coerceIn(0, MAX_DET)
        val out = ArrayList<RawDet>(n)
        for (i in 0 until n) {
            val score = scores[0][i]
            if (score < SCORE_THRESHOLD) continue
            // This SSD model emits 0-based class indices = (COCO id − 1); the bundled labelmap has a
            // leading "???" slot, so the name lives at index+1 (verified: person→0, traffic light→9).
            val label = labels.getOrNull(classes[0][i].toInt() + 1) ?: continue
            val b = boxes[0][i] // [ymin, xmin, ymax, xmax] normalized
            out += RawDet(label, score, NormBox(left = b[1], top = b[0], right = b[3], bottom = b[2]))
        }
        return out
    }

    private fun kindFor(label: String): DetectionKind? = when {
        label.equals("traffic light", true) -> DetectionKind.TRAFFIC_SIGNAL
        label.equals("stop sign", true) -> DetectionKind.TRAFFIC_SIGN
        else -> null
    }

    private companion object {
        const val MODEL = "models/detect.tflite"
        const val LABELS = "models/coco_labels.txt"
        const val INPUT = 300
        const val MAX_DET = 10
        const val SCORE_THRESHOLD = 0.5f
    }
}

/** Adapter exposing the shared [ObjectDetector]'s traffic-signal detections. */
@Singleton
class SsdTrafficSignalDetector @Inject constructor(
    private val detector: ObjectDetector,
) : TrafficSignalDetector {
    override fun detect(frame: PerceptionFrame) =
        detector.detect(frame).filter { it.kind == com.blurabbit.hdmap.domain.perception.DetectionKind.TRAFFIC_SIGNAL }
}

/** Adapter exposing the shared [ObjectDetector]'s traffic-sign (stop sign) detections. */
@Singleton
class SsdTrafficSignDetector @Inject constructor(
    private val detector: ObjectDetector,
) : TrafficSignDetector {
    override fun detect(frame: PerceptionFrame) =
        detector.detect(frame).filter { it.kind == com.blurabbit.hdmap.domain.perception.DetectionKind.TRAFFIC_SIGN }
}
