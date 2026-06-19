package com.blurabbit.hdmap.export

import com.blurabbit.hdmap.domain.geo.GeoPoint
import com.blurabbit.hdmap.domain.hdmap.HdMap
import javax.inject.Inject

/**
 * SCAFFOLD. Emits a TileJSON 3.0 descriptor for the HD map's vector-tile layer set, with bounds
 * computed from the feature geometry. The full pipeline — slicing features into a z/x/y pyramid,
 * encoding each tile as Mapbox Vector Tile (MVT/PBF), and packing the tiles into an MBTiles SQLite
 * container — is documented in /docs/export-formats.md and is the next implementation step. The
 * GeoJSON exporter already produces the source data this tiler would consume.
 */
class MbtilesExporter @Inject constructor() : HdMapExporter {

    override val format: ExportFormat = ExportFormat.MBTILES

    override fun export(map: HdMap): String {
        val bounds = boundsOf(map)
        val center = "${(bounds[0] + bounds[2]) / 2},${(bounds[1] + bounds[3]) / 2},14"
        return """{"tilejson":"3.0.0","name":"blurabbit_hdmap_${map.tripId}",""" +
            """"description":"HD map vector tile set (scaffold descriptor)",""" +
            """"version":"1.0.0","scheme":"xyz","minzoom":12,"maxzoom":18,""" +
            """"bounds":[${bounds.joinToString(",")}],"center":[$center],""" +
            """"vector_layers":[""" +
            """{"id":"lanes","description":"lane centerlines + boundaries"},""" +
            """{"id":"road_edges","description":"road edges"},""" +
            """{"id":"signs_signals","description":"traffic signs and signals"},""" +
            """{"id":"road_conditions","description":"potholes, speed breakers, work zones"}],""" +
            """"tiles":["mbtiles://blurabbit_hdmap_${map.tripId}/{z}/{x}/{y}.pbf"],""" +
            """"featureCount":${map.featureCount}}"""
    }

    private fun boundsOf(map: HdMap): DoubleArray {
        val pts = ArrayList<GeoPoint>()
        map.segments.forEach { pts += it.geometry }
        map.roadEdges.forEach { pts += it.geometry }
        map.signals.forEach { pts += it.location }
        map.signs.forEach { pts += it.location }
        if (pts.isEmpty()) return doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        val lons = pts.map { it.lon }; val lats = pts.map { it.lat }
        return doubleArrayOf(lons.min(), lats.min(), lons.max(), lats.max())
    }
}
