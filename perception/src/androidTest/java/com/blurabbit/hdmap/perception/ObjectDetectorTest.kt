package com.blurabbit.hdmap.perception

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.blurabbit.hdmap.domain.perception.DetectionKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * On-device verification that the bundled SSD MobileNet COCO model actually loads and detects.
 * Runs against the canonical YOLO test images (dog/bicycle/truck, person/dog/horse) whose contents
 * are known COCO classes, proving the full preprocess → inference → decode path works on hardware.
 */
@RunWith(AndroidJUnit4::class)
class ObjectDetectorTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context

    private fun detector() = ObjectDetector(context, TfliteRuntime(context))

    private fun asset(name: String): Bitmap =
        testContext.assets.open(name).use { BitmapFactory.decodeStream(it)!! }

    @Test
    fun modelLoadsOnDevice() {
        assertThat(detector().isModelAvailable).isTrue()
    }

    @Test
    fun detectsKnownObjectsInDogImage() {
        val labels = detector().rawLabelsForTest(asset("dog.jpg")).map { it.first.lowercase() }
        // dog.jpg contains a dog, a bicycle, and a truck — SSD MobileNet finds these COCO classes.
        assertThat(labels).isNotEmpty()
        assertThat(labels.any { it in setOf("dog", "bicycle", "car", "truck") }).isTrue()
    }

    @Test
    fun detectsPersonInPersonImage() {
        val labels = detector().rawLabelsForTest(asset("person.jpg")).map { it.first.lowercase() }
        assertThat(labels).contains("person")
    }

    @Test
    fun signalAdapterMapsTrafficLightKindOnly() {
        // The adapter must only surface signal-kind detections (filtering is correct).
        val signal = SsdTrafficSignalDetector(detector())
        val frame = PerceptionFrame(0, asset("dog.jpg"), 768, 576)
        assertThat(signal.detect(frame).all { it.kind == DetectionKind.TRAFFIC_SIGNAL }).isTrue()
    }
}
