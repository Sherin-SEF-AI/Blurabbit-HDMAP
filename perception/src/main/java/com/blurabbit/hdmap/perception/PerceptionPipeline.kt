package com.blurabbit.hdmap.perception

import com.blurabbit.hdmap.domain.perception.Detection
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fans a frame out to every detector and concatenates the results. This is the single seam the
 * mapping pipeline calls; which concrete detectors are wired in is decided by Hilt
 * ([com.blurabbit.hdmap.perception.di.PerceptionModule]).
 */
@Singleton
class PerceptionPipeline @Inject constructor(
    private val lane: LaneDetector,
    private val sign: TrafficSignDetector,
    private val signal: TrafficSignalDetector,
    private val roadFeature: RoadFeatureDetector,
) {
    fun run(frame: PerceptionFrame): List<Detection> = buildList {
        addAll(lane.detect(frame))
        addAll(sign.detect(frame))
        addAll(signal.detect(frame))
        addAll(roadFeature.detect(frame))
    }
}
