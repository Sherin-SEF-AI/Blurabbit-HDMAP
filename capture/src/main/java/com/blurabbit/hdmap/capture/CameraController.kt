package com.blurabbit.hdmap.capture

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Size
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import android.graphics.Bitmap
import androidx.lifecycle.LifecycleOwner
import com.blurabbit.hdmap.core.clock.ClockSynchronizer
import com.blurabbit.hdmap.domain.mapping.VoEstimate
import com.blurabbit.hdmap.domain.perception.Detection
import com.blurabbit.hdmap.domain.sensor.CameraFrameMeta
import com.blurabbit.hdmap.perception.PerceptionFrame
import com.blurabbit.hdmap.perception.PerceptionPipeline
import com.blurabbit.hdmap.perception.VisualOdometry
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives the front (rear-facing, i.e. road-facing) camera via CameraX at 1080p. Each analyzed
 * frame yields a [CameraFrameMeta] with a unified-clock timestamp (camera epoch is normalized by
 * [ClockSynchronizer]) plus exposure/ISO read from the Camera2 capture result. The pixels are left
 * for the perception pipeline / video sidecar; only metadata is logged here.
 */
@Singleton
class CameraController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sync: ClockSynchronizer,
    private val perception: PerceptionPipeline,
    private val visualOdometry: VisualOdometry,
) {
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private val frameId = AtomicLong(0)

    @Volatile private var lastExposureNs: Long = 0
    @Volatile private var lastIso: Int = 0

    /**
     * @param onFrame per-frame metadata (every analyzed frame)
     * @param onDetections perception results for the strided frames the model actually runs on
     */
    @OptIn(ExperimentalCamera2Interop::class)
    fun start(
        owner: LifecycleOwner,
        onFrame: (CameraFrameMeta) -> Unit,
        onDetections: (List<Detection>) -> Unit = {},
        onVo: (VoEstimate) -> Unit = {},
    ) {
        visualOdometry.reset()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val cameraProvider = runCatching { future.get() }.getOrNull() ?: return@addListener
            provider = cameraProvider

            val builder = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1920, 1080),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            ),
                        )
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

            Camera2Interop.Extender(builder).setSessionCaptureCallback(captureCallback)
            val analysis = builder.build()
            analysis.setAnalyzer(analysisExecutor) { image ->
                val id = frameId.getAndIncrement()
                val unified = sync.observeAndConvert("camera", image.imageInfo.timestamp)
                onFrame(
                    CameraFrameMeta(
                        frameId = id,
                        unifiedNs = unified,
                        exposureNs = lastExposureNs,
                        iso = lastIso,
                        width = image.width,
                        height = image.height,
                    ),
                )
                // Run perception on a strided subset to bound CPU/GPU cost. With the stub detectors
                // this is a no-op; a real .tflite model produces detections lifted to the map later.
                if (id % INFERENCE_STRIDE == 0L) {
                    runCatching {
                        val bitmap = downscale(image.toBitmap())
                        val frame = PerceptionFrame(unified, bitmap, bitmap.width, bitmap.height)
                        val results = perception.run(frame)
                        if (results.isNotEmpty()) onDetections(results)
                        visualOdometry.track(frame)?.let(onVo) // monocular VO front-end
                        bitmap.recycle()
                    }
                }
                image.close()
            }

            runCatching {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        runCatching { provider?.unbindAll() }
        provider = null
        frameId.set(0)
    }

    private fun downscale(src: Bitmap): Bitmap {
        val maxDim = maxOf(src.width, src.height)
        if (maxDim <= INFERENCE_MAX_DIM) return src
        val scale = INFERENCE_MAX_DIM.toFloat() / maxDim
        val scaled = Bitmap.createScaledBitmap(
            src, (src.width * scale).toInt(), (src.height * scale).toInt(), true,
        )
        if (scaled != src) src.recycle()
        return scaled
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastExposureNs = it }
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastIso = it }
        }
    }

    private companion object {
        const val INFERENCE_STRIDE = 6L     // run perception on every 6th frame (~5 Hz at 30 fps)
        const val INFERENCE_MAX_DIM = 640   // downscale before inference
    }
}
