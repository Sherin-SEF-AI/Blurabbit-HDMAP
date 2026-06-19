package com.blurabbit.hdmap.perception

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.blurabbit.hdmap.domain.perception.DetectionKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification of the TwinLiteNet drivable-area detector: it must load and run, and on a
 * real road scene produce a drivable corridor with a plausible IPM road-width estimate.
 */
@RunWith(AndroidJUnit4::class)
class DrivableAreaDetectorTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context

    private fun asset(name: String): Bitmap =
        testContext.assets.open(name).use { BitmapFactory.decodeStream(it)!! }

    private fun detector() = DrivableAreaDetector(TfliteRuntime(context))

    @Test
    fun runsWithoutCrashingOnArbitraryImage() {
        // A non-road image should simply yield no corridor (or a low-coverage one) — never crash.
        val result = detector().detect(PerceptionFrame(0, asset("dog.jpg"), 768, 576))
        assertThat(result).isNotNull()
    }

    @Test
    fun detectsDrivableCorridorWithPlausibleWidthOnRoad() {
        val result = detector().detect(PerceptionFrame(1_000, asset("road.jpg"), 960, 540))
        assertThat(result).isNotEmpty()
        val d = result.first()
        assertThat(d.kind).isEqualTo(DetectionKind.ROAD_EDGE)
        val widthM = d.attributes["widthM"]?.toDouble() ?: 0.0
        assertThat(widthM).isGreaterThan(2.0)
        assertThat(widthM).isLessThan(25.0)
    }
}
