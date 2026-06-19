package com.blurabbit.hdmap.domain.mapping

import kotlinx.serialization.Serializable

/**
 * One frame-to-frame egomotion estimate from the monocular visual-odometry front-end.
 *
 * [dYawDeg] is the camera heading change since the previous tracked frame (from far-field feature
 * flow); [forwardScore] is the focus-of-expansion divergence (>0 ⇒ moving forward, magnitude grows
 * with speed/closeness); [tracked] is the number of features matched; [confidence] in 0..1.
 */
@Serializable
data class VoEstimate(
    val unifiedNs: Long,
    val dtMs: Long,
    val dYawDeg: Double,
    val forwardScore: Double,
    val tracked: Int,
    val confidence: Double,
)
