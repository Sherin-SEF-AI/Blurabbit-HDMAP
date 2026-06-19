package com.blurabbit.hdmap.perception

import android.graphics.Bitmap
import com.blurabbit.hdmap.domain.perception.Detection

/**
 * A single camera frame handed to the perception models. The pixels ([bitmap]) are optional so the
 * pipeline can run metadata-only when no model is loaded; [unifiedNs] keys the detection back to
 * the synchronized trajectory for world-space lifting.
 */
data class PerceptionFrame(
    val unifiedNs: Long,
    val bitmap: Bitmap?,
    val width: Int,
    val height: Int,
)

/**
 * Pluggable detector contracts. Each maps to a family of HD-map features. Swapping the stub for a
 * real model (YOLOv11 / RT-DETR / SegFormer / YOLOP / LaneATT) is purely a matter of providing a
 * different implementation backed by [TfliteRuntime] — no pipeline changes.
 */
interface LaneDetector {
    /** Lane markings, centerlines, and road edges for a frame. */
    fun detect(frame: PerceptionFrame): List<Detection>
}

interface TrafficSignDetector {
    fun detect(frame: PerceptionFrame): List<Detection>
}

interface TrafficSignalDetector {
    fun detect(frame: PerceptionFrame): List<Detection>
}

interface RoadFeatureDetector {
    /** Speed breakers, potholes, cracks, waterlogging, work zones. */
    fun detect(frame: PerceptionFrame): List<Detection>
}

/**
 * Day-one fallbacks. The full capture → trajectory → mapping → export pipeline runs with these in
 * place; there are simply no vision detections until model weights are dropped into assets/models/.
 */
class EmptyLaneDetector : LaneDetector {
    override fun detect(frame: PerceptionFrame): List<Detection> = emptyList()
}

class EmptyTrafficSignDetector : TrafficSignDetector {
    override fun detect(frame: PerceptionFrame): List<Detection> = emptyList()
}

class EmptyTrafficSignalDetector : TrafficSignalDetector {
    override fun detect(frame: PerceptionFrame): List<Detection> = emptyList()
}

class EmptyRoadFeatureDetector : RoadFeatureDetector {
    override fun detect(frame: PerceptionFrame): List<Detection> = emptyList()
}
