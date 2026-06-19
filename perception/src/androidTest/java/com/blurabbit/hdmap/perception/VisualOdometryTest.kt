package com.blurabbit.hdmap.perception

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Random

/**
 * On-device verification of the monocular visual-odometry front-end: feed a textured frame and a
 * copy shifted horizontally (simulating a camera yaw), and confirm VO tracks the features and
 * reports a visual-yaw of the correct sign and roughly the expected magnitude.
 */
@RunWith(AndroidJUnit4::class)
class VisualOdometryTest {

    private val w = 320
    private val h = 240

    /** A high-texture image so corners are detectable and block-matching is unambiguous. */
    private fun texture(seed: Long): Bitmap {
        val rnd = Random(seed)
        val px = IntArray(w * h)
        for (i in px.indices) {
            val v = rnd.nextInt(256)
            px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Shift image content right by [shift] px (content moving right ⇒ camera yawed left). */
    private fun shiftedRight(src: Bitmap, shift: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) {
            val sx = x - shift
            out.setPixel(x, y, if (sx >= 0) src.getPixel(sx, y) else src.getPixel(0, y))
        }
        return out
    }

    @Test
    fun recoversVisualYawFromHorizontalShift() {
        val vo = VisualOdometry()
        val frame1 = texture(42)
        val frame2 = shiftedRight(frame1, 8)

        assertThat(vo.track(PerceptionFrame(0, frame1, w, h))).isNull() // first frame: no motion yet
        val est = vo.track(PerceptionFrame(200_000_000L, frame2, w, h))

        assertThat(est).isNotNull()
        est!!
        assertThat(est.tracked).isAtLeast(8)
        // Content shifted right → negative visual yaw; magnitude ≈ (8/320)*66° ≈ 1.65°.
        assertThat(est.dYawDeg).isLessThan(-0.5)
        assertThat(est.dYawDeg).isGreaterThan(-4.0)
    }

    @Test
    fun reportsNoMotionForIdenticalFrames() {
        val vo = VisualOdometry()
        val frame = texture(7)
        vo.track(PerceptionFrame(0, frame, w, h))
        val est = vo.track(PerceptionFrame(200_000_000L, texture(7), w, h))
        assertThat(est).isNotNull()
        assertThat(kotlin.math.abs(est!!.dYawDeg)).isLessThan(0.5) // near-zero yaw
    }
}
