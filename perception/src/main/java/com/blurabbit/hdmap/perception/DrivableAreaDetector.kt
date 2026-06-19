package com.blurabbit.hdmap.perception

import android.graphics.Bitmap
import com.blurabbit.hdmap.domain.perception.Detection
import com.blurabbit.hdmap.domain.perception.DetectionKind
import com.blurabbit.hdmap.domain.perception.NormBox
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.tan
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real lane/road detector backed by the bundled TwinLiteNet drivable-area model
 * (`assets/models/drivable_twinlite.tflite`). It segments the drivable road surface, finds the
 * left/right corridor extents at a near look-ahead row, and estimates the **road width in metres**
 * via a flat-ground inverse-perspective mapping (IPM) using assumed phone-camera geometry.
 *
 * It emits a single [DetectionKind.ROAD_EDGE] detection per frame carrying the width estimate,
 * drivable coverage, and ego-lane offset as attributes; the mapping pipeline aggregates the width
 * (median across the trip) to set the road segment's true width instead of a fixed default.
 *
 * Input/output shapes are auto-detected (NHWC vs NCHW), input normalized to [0,1]; output is the
 * 2-class (background, drivable) per-pixel logits decoded by argmax.
 */
@Singleton
class DrivableAreaDetector @Inject constructor(
    private val runtime: TfliteRuntime,
) : LaneDetector {

    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var loaded = false
    private var inH = 0
    private var inW = 0
    private var outH = 0
    private var outW = 0
    private var nhwc = true
    private lateinit var inputBuffer: ByteBuffer

    override fun detect(frame: PerceptionFrame): List<Detection> {
        val bitmap = frame.bitmap ?: return emptyList()
        ensureLoaded()
        val itp = interpreter ?: return emptyList()
        return runCatching { infer(itp, bitmap, frame.unifiedNs) }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val itp = runtime.loadCpuInterpreter(MODEL) ?: return
        val inShape = itp.getInputTensor(0).shape()   // [1,H,W,3]
        inH = inShape[1]; inW = inShape[2]
        val outShape = itp.getOutputTensor(0).shape() // [1,H,W,2] or [1,2,H,W]
        nhwc = outShape[3] == 2 || outShape[3] < outShape[1]
        outH = if (nhwc) outShape[1] else outShape[2]
        outW = if (nhwc) outShape[2] else outShape[3]
        inputBuffer = ByteBuffer.allocateDirect(inH * inW * 3 * 4).apply { order(ByteOrder.nativeOrder()) }
        interpreter = itp
    }

    @Synchronized
    private fun infer(itp: Interpreter, bitmap: Bitmap, unifiedNs: Long): List<Detection> {
        val resized = Bitmap.createScaledBitmap(bitmap, inW, inH, true)
        val px = IntArray(inW * inH)
        resized.getPixels(px, 0, inW, 0, 0, inW, inH)
        if (resized != bitmap) resized.recycle()
        inputBuffer.rewind()
        for (p in px) {
            inputBuffer.putFloat(((p shr 16) and 0xFF) / 255f) // R
            inputBuffer.putFloat(((p shr 8) and 0xFF) / 255f)  // G
            inputBuffer.putFloat((p and 0xFF) / 255f)           // B
        }
        inputBuffer.rewind()

        val output = Array(1) { Array(if (nhwc) outH else 2) { Array(if (nhwc) outW else outH) { FloatArray(if (nhwc) 2 else outW) } } }
        itp.run(inputBuffer, output)

        // Per-row left/right drivable extents (normalized x) + coverage.
        var drivableCount = 0
        val leftX = FloatArray(SAMPLE_ROWS) { Float.NaN }
        val rightX = FloatArray(SAMPLE_ROWS) { Float.NaN }
        for (r in 0 until SAMPLE_ROWS) {
            val yn = ROW_START + r * (ROW_END - ROW_START) / (SAMPLE_ROWS - 1)
            val y = (yn * (outH - 1)).toInt().coerceIn(0, outH - 1)
            var minX = -1; var maxX = -1
            for (x in 0 until outW) {
                val bg = at(output, 0, y, x)
                val dr = at(output, 1, y, x)
                if (dr > bg) {
                    if (minX < 0) minX = x
                    maxX = x
                    drivableCount++
                }
            }
            if (minX >= 0) {
                leftX[r] = minX.toFloat() / (outW - 1)
                rightX[r] = maxX.toFloat() / (outW - 1)
            }
        }
        val coverage = drivableCount.toFloat() / (SAMPLE_ROWS * outW)
        if (coverage < MIN_COVERAGE || coverage > MAX_COVERAGE) return emptyList()

        // Use the nearest look-ahead row that has a corridor for the metric width estimate.
        val rowIdx = (0 until SAMPLE_ROWS).lastOrNull { !leftX[it].isNaN() } ?: return emptyList()
        val yn = ROW_END - (SAMPLE_ROWS - 1 - rowIdx) * (ROW_END - ROW_START) / (SAMPLE_ROWS - 1)
        val widthM = ipmWidthMeters(leftX[rowIdx], rightX[rowIdx], yn)
        if (widthM < MIN_WIDTH_M || widthM > MAX_WIDTH_M) return emptyList()

        val center = (leftX[rowIdx] + rightX[rowIdx]) / 2f
        val half = ((rightX[rowIdx] - leftX[rowIdx]) / 2f).coerceAtLeast(0.05f)
        val offset = (0.5f - center) / half // +ve = vehicle is right of corridor centre

        return listOf(
            Detection(
                kind = DetectionKind.ROAD_EDGE,
                label = "drivable_corridor",
                confidence = coverage.coerceIn(0.3f, 0.95f).toDouble(),
                frameUnifiedNs = unifiedNs,
                box = NormBox(left = leftX[rowIdx], top = yn, right = rightX[rowIdx], bottom = yn),
                attributes = mapOf(
                    "widthM" to String.format("%.2f", widthM),
                    "coverage" to String.format("%.3f", coverage),
                    "offset" to String.format("%.3f", offset),
                    "model" to "twinlite_drivable",
                ),
            ),
        )
    }

    private fun at(output: Array<Array<Array<FloatArray>>>, c: Int, y: Int, x: Int): Float =
        if (nhwc) output[0][y][x][c] else output[0][c][y][x]

    /**
     * Flat-ground IPM: project the corridor's normalized left/right at image row [vNorm] to metres,
     * assuming a level phone camera at [CAMERA_HEIGHT_M] with the given field of view. Approximate
     * (uncalibrated), but a robust single scalar per frame once medianed across the trip.
     */
    private fun ipmWidthMeters(leftNorm: Float, rightNorm: Float, vNorm: Float): Double {
        val theta = (vNorm - 0.5f) * VFOV_RAD            // ray depression below the horizon
        if (theta <= 0.02f) return Double.MAX_VALUE       // above/near horizon → unreliable
        val d = CAMERA_HEIGHT_M / tan(theta.toDouble())   // forward ground distance
        val xl = d * tan(((leftNorm - 0.5f) * HFOV_RAD).toDouble())
        val xr = d * tan(((rightNorm - 0.5f) * HFOV_RAD).toDouble())
        return xr - xl
    }

    private companion object {
        const val MODEL = "models/drivable_twinlite.tflite"
        const val SAMPLE_ROWS = 16
        const val ROW_START = 0.55f
        const val ROW_END = 0.92f
        const val MIN_COVERAGE = 0.03f
        const val MAX_COVERAGE = 0.97f
        const val MIN_WIDTH_M = 2.0
        const val MAX_WIDTH_M = 25.0
        const val CAMERA_HEIGHT_M = 1.3
        val VFOV_RAD = Math.toRadians(55.0).toFloat()
        val HFOV_RAD = Math.toRadians(68.0).toFloat()
    }
}
