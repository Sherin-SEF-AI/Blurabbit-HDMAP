package com.blurabbit.hdmap.backend

import com.blurabbit.hdmap.domain.geo.GeoPoint
import com.blurabbit.hdmap.domain.hdmap.HdMap
import com.blurabbit.hdmap.domain.hdmap.RoadSegment
import com.blurabbit.hdmap.domain.hdmap.SpeedBreaker
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FusionEngineTest {

    private fun tripWithBreaker(tripId: String, lat: Double, lon: Double, conf: Double) = HdMap(
        tripId = tripId,
        generatedAtMs = 1,
        speedBreakers = listOf(
            SpeedBreaker("sb_$tripId", GeoPoint(lat, lon), null, conf, 1, tripId),
        ),
    )

    @Test
    fun mergesNearbyObservationsAndBoostsConfidence() {
        val a = tripWithBreaker("tripA", 12.9700000, 77.5900000, 0.6)
        val b = tripWithBreaker("tripB", 12.9700150, 77.5900150, 0.6) // ~2 m away

        val consensus = FusionEngine().fuse(listOf(a, b))

        // Two observations of the same breaker collapse to one consensus feature...
        assertThat(consensus.speedBreakers).hasSize(1)
        val merged = consensus.speedBreakers.first()
        // ...with confidence boosted by agreement (noisy-OR of 0.6,0.6 = 0.84)...
        assertThat(merged.confidence).isWithin(0.001).of(0.84)
        // ...and provenance from both trips.
        assertThat(merged.sourceTrip).contains("tripA")
        assertThat(merged.sourceTrip).contains("tripB")
    }

    @Test
    fun keepsDistinctObservationsSeparate() {
        val a = tripWithBreaker("tripA", 12.9700, 77.5900, 0.6)
        val b = tripWithBreaker("tripB", 12.9800, 77.6000, 0.6) // ~1.4 km away
        val consensus = FusionEngine().fuse(listOf(a, b))
        assertThat(consensus.speedBreakers).hasSize(2)
    }

    private fun tripWithSegment(tripId: String, latShift: Double, conf: Double): HdMap {
        // A ~110 m east-running segment, optionally shifted north by latShift degrees.
        val geom = (0..10).map { GeoPoint(12.9700 + latShift, 77.5900 + it * 0.0001) }
        return HdMap(
            tripId = tripId,
            generatedAtMs = 1,
            segments = listOf(RoadSegment("seg_$tripId", geom, 110.0, 2, 7.0, null,
                confidence = conf, timestampNs = 1, sourceTrip = tripId)),
        )
    }

    @Test
    fun fusesOverlappingSegmentsIntoOne() {
        val a = tripWithSegment("tripA", 0.0, 0.6)
        val b = tripWithSegment("tripB", 0.00002, 0.6) // ~2 m north — same road
        val consensus = FusionEngine().fuse(listOf(a, b))

        assertThat(consensus.segments).hasSize(1)
        val merged = consensus.segments.first()
        assertThat(merged.confidence).isWithin(0.001).of(0.84) // noisy-OR boost
        assertThat(merged.sourceTrip).contains("tripA")
        assertThat(merged.sourceTrip).contains("tripB")
        assertThat(merged.geometry.size).isAtLeast(2)
    }

    @Test
    fun keepsDistinctSegmentsSeparate() {
        val a = tripWithSegment("tripA", 0.0, 0.6)
        val b = tripWithSegment("tripB", 0.001, 0.6) // ~110 m north — a different road
        val consensus = FusionEngine().fuse(listOf(a, b))
        assertThat(consensus.segments).hasSize(2)
    }
}
