package com.blurabbit.hdmap.export

import com.blurabbit.hdmap.domain.geo.GeoPoint
import com.blurabbit.hdmap.domain.geo.Polyline
import com.blurabbit.hdmap.domain.hdmap.HdMap
import com.blurabbit.hdmap.domain.hdmap.MapFeature
import javax.inject.Inject

/**
 * Exports an [HdMap] as a GeoJSON `FeatureCollection` (RFC 7946). Every HD-map feature becomes a
 * GeoJSON Feature: linear features → LineString, point features → Point, crosswalks → Polygon. The
 * mandatory provenance fields (id/confidence/timestamp/sourceTrip) plus a `featureType` tag and
 * type-specific attributes are written into `properties`, so the output is queryable in any GIS
 * tool and round-trips into the fusion engine. Coordinates are `[lon, lat]` per the spec.
 */
class GeoJsonExporter @Inject constructor() : HdMapExporter {

    override val format: ExportFormat = ExportFormat.GEOJSON

    override fun export(map: HdMap): String {
        val features = ArrayList<String>()
        map.segments.forEach { f ->
            features += lineFeature("road_segment", f.geometry, f, mapOf(
                "lengthM" to f.lengthM, "laneCount" to f.laneCount, "widthM" to f.widthM,
                "speedLimitKph" to f.speedLimitKph, "direction" to f.direction.name))
        }
        map.lanes.forEach { f ->
            features += lineFeature("lane", f.centerline, f, mapOf(
                "segmentId" to f.segmentId, "index" to f.index, "widthM" to f.widthM))
        }
        map.centerlines.forEach { f ->
            features += lineFeature("lane_centerline", f.geometry, f, mapOf("laneId" to f.laneId))
        }
        map.boundaries.forEach { f ->
            features += lineFeature("lane_boundary", f.geometry, f, mapOf(
                "boundaryType" to f.type.name, "color" to f.color.name, "side" to f.side.name))
        }
        map.roadEdges.forEach { f ->
            features += lineFeature("road_edge", f.geometry, f, mapOf("side" to f.side.name))
        }
        map.stopLines.forEach { f -> features += lineFeature("stop_line", f.geometry, f, emptyMap()) }
        map.conditions.forEach { f ->
            features += lineFeature("road_condition", f.geometry, f, mapOf(
                "conditionType" to f.type.name, "severity" to f.severity))
        }
        map.crosswalks.forEach { f -> features += polygonFeature("crosswalk", f.polygon, f, emptyMap()) }
        map.intersections.forEach { f ->
            features += pointFeature("intersection", f.location, f, mapOf(
                "intersectionType" to f.type.name, "armCount" to f.armCount))
        }
        map.signals.forEach { f ->
            features += pointFeature("traffic_signal", f.location, f, mapOf("signalType" to f.signalType.name))
        }
        map.signs.forEach { f ->
            features += pointFeature("traffic_sign", f.location, f, mapOf(
                "signClass" to f.signClass.name, "value" to f.value))
        }
        map.speedBreakers.forEach { f ->
            features += pointFeature("speed_breaker", f.location, f, mapOf("heightCm" to f.heightEstimateCm))
        }

        val intel = map.intelligence?.let {
            ""","roadIntelligence":{"health":${it.healthScore},"quality":${it.qualityScore},""" +
                """"trafficDensity":${it.trafficDensityScore},"safety":${it.safetyScore},""" +
                """"construction":${it.constructionScore}}"""
        } ?: ""

        return """{"type":"FeatureCollection","name":"blurabbit_hdmap_${esc(map.tripId)}",""" +
            """"generatedAtMs":${map.generatedAtMs}$intel,"features":[${features.joinToString(",")}]}"""
    }

    private fun lineFeature(type: String, line: Polyline, f: MapFeature, extra: Map<String, Any?>): String {
        val coords = line.joinToString(",", "[", "]") { coord(it) }
        return feature("""{"type":"LineString","coordinates":$coords}""", type, f, extra)
    }

    private fun polygonFeature(type: String, ring: Polyline, f: MapFeature, extra: Map<String, Any?>): String {
        val closed = if (ring.isNotEmpty()) ring + ring.first() else ring
        val coords = closed.joinToString(",", "[[", "]]") { coord(it) }
        return feature("""{"type":"Polygon","coordinates":$coords}""", type, f, extra)
    }

    private fun pointFeature(type: String, p: GeoPoint, f: MapFeature, extra: Map<String, Any?>): String =
        feature("""{"type":"Point","coordinates":${coord(p)}}""", type, f, extra)

    private fun feature(geometry: String, type: String, f: MapFeature, extra: Map<String, Any?>): String {
        val props = StringBuilder()
        props.append(""""featureType":"${esc(type)}",""")
        props.append(""""id":"${esc(f.id)}",""")
        props.append(""""confidence":${f.confidence},""")
        props.append(""""timestampNs":${f.timestampNs},""")
        props.append(""""sourceTrip":"${esc(f.sourceTrip)}"""")
        for ((k, v) in extra) {
            if (v == null) continue
            props.append(",").append('"').append(esc(k)).append("\":").append(jsonValue(v))
        }
        return """{"type":"Feature","geometry":$geometry,"properties":{$props}}"""
    }

    private fun coord(p: GeoPoint): String = "[${p.lon},${p.lat}]"

    private fun jsonValue(v: Any?): String = when (v) {
        null -> "null"
        is Number, is Boolean -> v.toString()
        else -> "\"${esc(v.toString())}\""
    }

    private fun esc(s: String): String = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }
}
