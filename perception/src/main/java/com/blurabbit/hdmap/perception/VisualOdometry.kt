package com.blurabbit.hdmap.perception

import android.graphics.Bitmap
import com.blurabbit.hdmap.domain.mapping.VoEstimate
import kotlin.math.abs
import kotlin.math.hypot
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monocular visual-odometry front-end — the tracking/egomotion stage of a visual-SLAM pipeline,
 * implemented in pure Kotlin so it runs on the device with no native build.
 *
 * Per frame it: converts to a small grayscale image, picks one strong corner per grid cell
 * (max gradient), tracks the previous frame's corners by block matching (min SAD over a search
 * window), then summarizes the flow field into ego-motion:
 *  - **visual yaw** from the median horizontal flow of far-field (upper-image) features, and
 *  - **forward motion** from the focus-of-expansion divergence (features stream outward when moving).
 *
 * The result is a per-frame [VoEstimate] the [com.blurabbit.hdmap.mapping] backend fuses with
 * GNSS+IMU to correct heading where GNSS can't (slow turns, stops). The visual-SLAM *back-end*
 * (loop closure + global bundle adjustment, e.g. ORB-SLAM3) attaches to the same trajectory seam
 * via a native bridge.
 */
@Singleton
class VisualOdometry @Inject constructor() {

    private var prevGray: IntArray? = null
    private var prevFeatures: List<IntArray> = emptyList() // each = [x, y]
    private var prevNs: Long = 0L
    private var w = 0
    private var h = 0

    fun reset() {
        prevGray = null; prevFeatures = emptyList(); prevNs = 0L
    }

    fun track(frame: PerceptionFrame): VoEstimate? {
        val bmp = frame.bitmap ?: return null
        val gray = toGray(bmp) // sets w,h
        val prev = prevGray
        val prevF = prevFeatures
        val prevTs = prevNs

        val features = detectFeatures(gray)
        val result: VoEstimate? = if (prev == null || prevF.isEmpty()) {
            null
        } else {
            summarize(prev, gray, prevF, frame.unifiedNs - prevTs, frame.unifiedNs)
        }

        prevGray = gray
        prevFeatures = features
        prevNs = frame.unifiedNs
        return result
    }

    private fun summarize(prev: IntArray, cur: IntArray, prevF: List<IntArray>, dtNs: Long, ns: Long): VoEstimate? {
        val cx = w / 2.0; val cy = h / 2.0
        val dxs = ArrayList<Double>(prevF.size)
        var radialSum = 0.0
        var matched = 0
        for (f in prevF) {
            val m = matchBlock(prev, cur, f[0], f[1]) ?: continue
            val dx = m[0].toDouble(); val dy = m[1].toDouble()
            // ignore near-zero / wild matches
            if (abs(dx) > SEARCH.toDouble() || abs(dy) > SEARCH.toDouble()) continue
            matched++
            if (f[1] < h * 0.55) dxs.add(dx) // far field for yaw
            val rx = f[0] - cx; val ry = f[1] - cy
            val r = hypot(rx, ry).coerceAtLeast(1.0)
            radialSum += (rx * dx + ry * dy) / r
        }
        if (matched < MIN_TRACKED) return null
        val dxMed = if (dxs.isNotEmpty()) median(dxs) else 0.0
        val dYawDeg = -(dxMed / w) * HFOV_DEG
        val forwardScore = radialSum / matched
        val confidence = (matched.toDouble() / prevF.size).coerceIn(0.0, 1.0)
        return VoEstimate(
            unifiedNs = ns,
            dtMs = (dtNs / 1_000_000L),
            dYawDeg = dYawDeg,
            forwardScore = forwardScore,
            tracked = matched,
            confidence = confidence,
        )
    }

    /** Best integer (dx,dy) shift for the patch at (fx,fy), or null if no confident match. */
    private fun matchBlock(prev: IntArray, cur: IntArray, fx: Int, fy: Int): IntArray? {
        if (fx < PATCH + SEARCH || fx >= w - PATCH - SEARCH || fy < PATCH + SEARCH || fy >= h - PATCH - SEARCH) return null
        var bestSad = Int.MAX_VALUE
        var bestDx = 0; var bestDy = 0
        for (dy in -SEARCH..SEARCH) {
            for (dx in -SEARCH..SEARCH) {
                var sad = 0
                for (py in -PATCH..PATCH) {
                    val rowP = (fy + py) * w + fx
                    val rowC = (fy + py + dy) * w + fx + dx
                    for (px in -PATCH..PATCH) {
                        sad += abs(prev[rowP + px] - cur[rowC + px])
                    }
                }
                if (sad < bestSad) { bestSad = sad; bestDx = dx; bestDy = dy }
            }
        }
        // Reject low-texture / ambiguous matches.
        return if (bestSad < SAD_REJECT) intArrayOf(bestDx, bestDy) else null
    }

    /** One strongest corner (max gradient) per grid cell. */
    private fun detectFeatures(gray: IntArray): List<IntArray> {
        val out = ArrayList<IntArray>()
        val cw = w / GRID; val ch = h / GRID
        if (cw <= 2 * (PATCH + SEARCH) / GRID + 2) return out
        for (gy in 0 until GRID) {
            for (gx in 0 until GRID) {
                var bestG = GRAD_MIN; var bx = -1; var by = -1
                val x0 = (gx * cw).coerceAtLeast(PATCH + SEARCH + 1)
                val y0 = (gy * ch).coerceAtLeast(PATCH + SEARCH + 1)
                val x1 = ((gx + 1) * cw).coerceAtMost(w - PATCH - SEARCH - 1)
                val y1 = ((gy + 1) * ch).coerceAtMost(h - PATCH - SEARCH - 1)
                var y = y0
                while (y < y1) {
                    var x = x0
                    while (x < x1) {
                        val g = abs(gray[y * w + x + 1] - gray[y * w + x - 1]) +
                            abs(gray[(y + 1) * w + x] - gray[(y - 1) * w + x])
                        if (g > bestG) { bestG = g; bx = x; by = y }
                        x += 2
                    }
                    y += 2
                }
                if (bx >= 0) out.add(intArrayOf(bx, by))
            }
        }
        return out
    }

    private fun toGray(bmp: Bitmap): IntArray {
        val scaled = if (bmp.width <= VO_WIDTH) bmp
        else Bitmap.createScaledBitmap(bmp, VO_WIDTH, VO_WIDTH * bmp.height / bmp.width, true)
        w = scaled.width; h = scaled.height
        val px = IntArray(w * h)
        scaled.getPixels(px, 0, w, 0, 0, w, h)
        if (scaled != bmp) scaled.recycle()
        val gray = IntArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            gray[i] = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
        }
        return gray
    }

    private fun median(v: List<Double>): Double {
        val s = v.sorted(); val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    private companion object {
        const val VO_WIDTH = 320
        const val GRID = 10          // 10×10 feature grid
        const val PATCH = 3          // patch half-size for SAD
        const val SEARCH = 12        // search half-window (px)
        const val GRAD_MIN = 40      // min gradient to be a corner
        const val SAD_REJECT = 4000  // reject ambiguous matches above this SAD
        const val MIN_TRACKED = 8
        const val HFOV_DEG = 66.0    // assumed horizontal field of view
    }
}
