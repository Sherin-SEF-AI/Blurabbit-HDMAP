package com.blurabbit.hdmap.export

import com.blurabbit.hdmap.domain.hdmap.HdMap

/** Supported HD-map export targets. */
enum class ExportFormat(val displayName: String, val extension: String, val mimeType: String) {
    GEOJSON("GeoJSON", "geojson", "application/geo+json"),
    OPENDRIVE("OpenDRIVE", "xodr", "application/xml"),
    LANELET2("Lanelet2 (OSM)", "osm", "application/xml"),
    MBTILES("Vector Tiles (TileJSON)", "json", "application/json"),
}

/** Serializes an [HdMap] to a single export artifact (text payload). */
interface HdMapExporter {
    val format: ExportFormat
    fun export(map: HdMap): String
}
