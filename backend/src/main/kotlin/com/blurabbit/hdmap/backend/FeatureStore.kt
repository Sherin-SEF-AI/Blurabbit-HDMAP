package com.blurabbit.hdmap.backend

import com.blurabbit.hdmap.domain.geo.GeoPoint
import com.blurabbit.hdmap.domain.hdmap.HdMap
import java.util.concurrent.ConcurrentHashMap

/** Geographic bounding box of a trip's features (null when the trip has no geometry). */
data class Bbox(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double) {
    fun intersects(o: Bbox): Boolean =
        minLat <= o.maxLat && maxLat >= o.minLat && minLon <= o.maxLon && maxLon >= o.minLon

    companion object {
        fun of(map: HdMap): Bbox? {
            val pts = ArrayList<GeoPoint>()
            map.segments.forEach { pts += it.geometry }
            map.lanes.forEach { pts += it.centerline }
            map.roadEdges.forEach { pts += it.geometry }
            map.signals.forEach { pts += it.location }
            map.signs.forEach { pts += it.location }
            map.speedBreakers.forEach { pts += it.location }
            map.intersections.forEach { pts += it.location }
            if (pts.isEmpty()) return null
            return Bbox(pts.minOf { it.lat }, pts.minOf { it.lon }, pts.maxOf { it.lat }, pts.maxOf { it.lon })
        }
    }
}

/**
 * Authoritative feature store: holds each ingested trip's [HdMap] and the fused consensus map,
 * re-running fusion when trips change. Two implementations: [InMemoryFeatureStore] (default) and
 * [JdbcFeatureStore] (PostgreSQL/PostGIS in production; H2 in tests).
 */
interface FeatureStore {
    val consensus: HdMap
    fun ingest(map: HdMap)
    fun tripCount(): Int
    fun tripIds(): List<String>
    /** Trips whose feature bbox intersects the query box — the dataset/region read path. */
    fun queryByBbox(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): List<HdMap>
}

class InMemoryFeatureStore : FeatureStore {
    private val trips = ConcurrentHashMap<String, HdMap>()
    private val fusion = FusionEngine()

    @Volatile
    override var consensus: HdMap = HdMap(tripId = "consensus", generatedAtMs = 0)
        private set

    @Synchronized
    override fun ingest(map: HdMap) {
        trips[map.tripId] = map
        consensus = fusion.fuse(trips.values.toList())
    }

    override fun tripCount(): Int = trips.size
    override fun tripIds(): List<String> = trips.keys.sorted()

    override fun queryByBbox(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): List<HdMap> {
        val q = Bbox(minLat, minLon, maxLat, maxLon)
        return trips.values.filter { Bbox.of(it)?.intersects(q) == true }
    }
}
