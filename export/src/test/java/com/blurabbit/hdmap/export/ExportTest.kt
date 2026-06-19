package com.blurabbit.hdmap.export

import com.blurabbit.hdmap.domain.geo.GeoPoint
import com.blurabbit.hdmap.domain.hdmap.HdMap
import com.blurabbit.hdmap.domain.hdmap.Lane
import com.blurabbit.hdmap.domain.hdmap.RoadEdge
import com.blurabbit.hdmap.domain.hdmap.RoadIntelligence
import com.blurabbit.hdmap.domain.hdmap.RoadSegment
import com.blurabbit.hdmap.domain.hdmap.RoadSide
import com.blurabbit.hdmap.domain.hdmap.TrafficSign
import com.blurabbit.hdmap.domain.hdmap.SignClass
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import org.junit.Test

class ExportTest {

    private val line = listOf(GeoPoint(12.97, 77.59), GeoPoint(12.971, 77.591), GeoPoint(12.972, 77.592))

    private fun sampleMap(): HdMap = HdMap(
        tripId = "trip_test",
        generatedAtMs = 123L,
        segments = listOf(RoadSegment("seg1", line, 250.0, 2, 7.0, 60, confidence = 0.8, timestampNs = 1, sourceTrip = "trip_test")),
        lanes = listOf(Lane("lane1", "seg1", 0, line, 3.5, 0.8, 1, "trip_test")),
        roadEdges = listOf(
            RoadEdge("e1", line, RoadSide.LEFT, 0.7, 1, "trip_test"),
            RoadEdge("e2", line, RoadSide.RIGHT, 0.7, 1, "trip_test"),
        ),
        signs = listOf(TrafficSign("s1", GeoPoint(12.9705, 77.5905), SignClass.SPEED_LIMIT, "60", 0.9, 2, "trip_test")),
        intelligence = RoadIntelligence(90.0, 85.0, 30.0, 88.0, 0.0),
    )

    @Test
    fun geoJson_isValidFeatureCollection() {
        val text = GeoJsonExporter().export(sampleMap())
        val root = Json.parseToString(text)
        assertThat(root["type"].toString()).contains("FeatureCollection")
        val features = root["features"]!!.jsonArray
        // segment + lane + 2 edges + 1 sign = 5 features
        assertThat(features.size).isEqualTo(5)
    }

    @Test
    fun openDrive_isWellFormedXmlWithRoad() {
        val text = OpenDriveExporter().export(sampleMap())
        assertThat(text).startsWith("<?xml")
        assertThat(text).contains("<OpenDRIVE>")
        assertThat(text).contains("<road")
        assertThat(text).contains("<planView")
        assertThat(text).contains("</OpenDRIVE>")
    }

    @Test
    fun lanelet2_hasLaneletRelation() {
        val text = Lanelet2Exporter().export(sampleMap())
        assertThat(text).contains("<osm")
        assertThat(text).contains("""k="type" v="lanelet"""")
        assertThat(text).contains("role=\"left\"")
        assertThat(text).contains("role=\"right\"")
    }

    @Test
    fun mbtiles_isTileJson() {
        val text = MbtilesExporter().export(sampleMap())
        assertThat(text).contains("\"tilejson\"")
        assertThat(text).contains("vector_layers")
    }

    private fun Json.parseToString(text: String): JsonObject =
        parseToJsonElement(text) as JsonObject
}
