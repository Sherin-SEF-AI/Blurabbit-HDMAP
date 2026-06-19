package com.blurabbit.hdmap.perception

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared on-device inference harness. Loads a `.tflite` model from `assets/models/` and builds an
 * [Interpreter] with the best available accelerator (GPU → NNAPI → CPU). Real detectors
 * (YOLOv11 / RT-DETR / SegFormer / YOLOP / LaneATT) construct their interpreter through here; the
 * stub detectors never call it, so the app runs with zero models bundled.
 */
@Singleton
class TfliteRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** True if a model asset exists, so a caller can decide whether to use a real or stub detector. */
    fun isModelPresent(assetPath: String): Boolean =
        runCatching { context.assets.open(assetPath).close(); true }.getOrDefault(false)

    /**
     * Build an [Interpreter] for [assetPath], or null if the asset is missing or fails to load.
     * Accelerator selection falls back gracefully so it works on any device.
     */
    fun loadInterpreter(assetPath: String, preferGpu: Boolean = true): Interpreter? {
        if (!isModelPresent(assetPath)) return null
        val model = runCatching { mapModel(assetPath) }.getOrNull() ?: return null
        val options = Interpreter.Options()
        var configured = false
        if (preferGpu && runCatching { CompatibilityList().isDelegateSupportedOnThisDevice }.getOrDefault(false)) {
            runCatching { options.addDelegate(GpuDelegate()); configured = true }
        }
        if (!configured) {
            runCatching { options.addDelegate(NnApiDelegate()); configured = true }
        }
        if (!configured) {
            options.setNumThreads(Runtime.getRuntime().availableProcessors().coerceAtMost(4))
        }
        return runCatching { Interpreter(model, options) }.getOrNull()
    }

    /**
     * Build a CPU-only [Interpreter] (multi-threaded). Use for models with ops that delegates don't
     * support — e.g. SSD's `TFLite_Detection_PostProcess` — where GPU/NNAPI would fail or fall back.
     */
    fun loadCpuInterpreter(assetPath: String, numThreads: Int = 4): Interpreter? {
        if (!isModelPresent(assetPath)) return null
        val model = runCatching { mapModel(assetPath) }.getOrNull() ?: return null
        val options = Interpreter.Options()
            .setNumThreads(numThreads.coerceIn(1, Runtime.getRuntime().availableProcessors()))
        return runCatching { Interpreter(model, options) }.getOrNull()
    }

    private fun mapModel(assetPath: String): MappedByteBuffer {
        val fd = context.assets.openFd(assetPath)
        fd.createInputStream().channel.use { channel ->
            return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }
}
