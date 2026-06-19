package com.blurabbit.hdmap.backend

import com.blurabbit.hdmap.domain.hdmap.HdMap
import com.blurabbit.hdmap.domain.hdmap.MapFeature
import com.blurabbit.hdmap.domain.hdmap.RoadConditionType
import com.blurabbit.hdmap.domain.hdmap.SignClass
import com.blurabbit.hdmap.export.ExportFormat
import com.blurabbit.hdmap.export.GeoJsonExporter
import com.blurabbit.hdmap.export.Lanelet2Exporter
import com.blurabbit.hdmap.export.MbtilesExporter
import com.blurabbit.hdmap.export.OpenDriveExporter
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress

/**
 * Blurabbit HD-map backend (prototype). A dependency-free JDK HTTP server that ingests per-trip HD
 * maps, fuses them into a crowd-sourced consensus map, and serves it for query / dataset-search /
 * export — reusing the very same domain schema and exporters the Android app produces.
 *
 * Endpoints:
 *   GET  /v1/health
 *   GET  /v1/stats
 *   POST /v1/trips/{id}          body = serialized HdMap JSON  → ingest + re-fuse
 *   GET  /v1/consensus.geojson   → fused map as GeoJSON
 *   GET  /v1/export?format=geojson|opendrive|lanelet2|mbtiles
 *   POST /v1/search              body = {"any":["speed_breaker","traffic_signal",...]}
 */
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private lateinit var store: FeatureStore
private val queue: FeatureQueue = InMemoryFeatureQueue()

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    store = buildStore()
    val server = HttpServer.create(InetSocketAddress(port), 0)
    server.createContext("/v1/") { ex -> route(ex) }
    server.executor = null
    server.start()
    println("Blurabbit HD-map backend [store=${store::class.simpleName}] listening on http://localhost:$port/v1/")
}

/** STORE=postgres + DB_URL/DB_USER/DB_PASSWORD selects the JDBC store; default is in-memory. */
private fun buildStore(): FeatureStore = when (System.getenv("STORE")?.lowercase()) {
    "postgres", "postgis", "jdbc" -> JdbcFeatureStore(
        jdbcUrl = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5432/blurabbit",
        user = System.getenv("DB_USER"),
        password = System.getenv("DB_PASSWORD"),
    )
    else -> InMemoryFeatureStore()
}

/** Drain the ingestion queue into the store (the fusion worker step). */
private fun drainQueue() {
    while (true) {
        val map = queue.poll() ?: break
        store.ingest(map)
    }
}

private fun route(ex: HttpExchange) {
    try {
        val path = ex.requestURI.path
        val method = ex.requestMethod
        when {
            path == "/v1/health" && method == "GET" -> respond(ex, 200, "text/plain", "ok")
            path == "/v1/stats" && method == "GET" -> stats(ex)
            path.startsWith("/v1/trips/") && method == "POST" -> ingest(ex, path.removePrefix("/v1/trips/"))
            path == "/v1/consensus.geojson" && method == "GET" ->
                respond(ex, 200, ExportFormat.GEOJSON.mimeType, GeoJsonExporter().export(store.consensus))
            path == "/v1/export" && method == "GET" -> export(ex)
            path == "/v1/search" && method == "POST" -> search(ex)
            else -> respond(ex, 404, "application/json", """{"error":"not found"}""")
        }
    } catch (t: Throwable) {
        respond(ex, 400, "application/json", """{"error":${quote(t.message ?: "bad request")}}""")
    }
}

private fun ingest(ex: HttpExchange, tripId: String) {
    val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
    val map = json.decodeFromString(HdMap.serializer(), body)
    queue.enqueue(map)   // ingestion → queue → fusion worker
    drainQueue()
    val c = store.consensus
    respond(ex, 202, "application/json",
        """{"ingested":${quote(tripId)},"trips":${store.tripCount()},""" +
            """"consensusFeatures":${c.featureCount},"speedBreakers":${c.speedBreakers.size},""" +
            """"signals":${c.signals.size},"signs":${c.signs.size}}""")
}

private fun stats(ex: HttpExchange) {
    val c = store.consensus
    val intel = c.intelligence
    val tripIdsJson = store.tripIds().joinToString(",", "[", "]") { quote(it) }
    respond(ex, 200, "application/json",
        """{"trips":${store.tripCount()},"tripIds":$tripIdsJson,""" +
            """"consensus":{"features":${c.featureCount},"segments":${c.segments.size},""" +
            """"speedBreakers":${c.speedBreakers.size},"signals":${c.signals.size},"signs":${c.signs.size},""" +
            """"conditions":${c.conditions.size},"intersections":${c.intersections.size}},""" +
            """"roadIntelligence":${if (intel == null) "null" else
                """{"health":${intel.healthScore},"quality":${intel.qualityScore},""" +
                    """"safety":${intel.safetyScore},"construction":${intel.constructionScore}}"""}}""")
}

private fun export(ex: HttpExchange) {
    val fmt = query(ex, "format") ?: "geojson"
    val exporter = when (fmt.lowercase()) {
        "opendrive", "xodr" -> OpenDriveExporter()
        "lanelet2", "osm" -> Lanelet2Exporter()
        "mbtiles", "tilejson" -> MbtilesExporter()
        else -> GeoJsonExporter()
    }
    respond(ex, 200, exporter.format.mimeType, exporter.export(store.consensus))
}

@Serializable private data class SearchRequest(val any: List<String> = emptyList())
@Serializable private data class SearchHit(val type: String, val lat: Double, val lon: Double, val confidence: Double, val sourceTrips: String)
@Serializable private data class SearchResponse(val count: Int, val hits: List<SearchHit>)

private fun search(ex: HttpExchange) {
    val req = json.decodeFromString(SearchRequest.serializer(), ex.requestBody.readBytes().toString(Charsets.UTF_8))
    val want = req.any.map { it.lowercase() }.toSet()
    val c = store.consensus
    val hits = ArrayList<SearchHit>()

    fun add(type: String, f: MapFeature, lat: Double, lon: Double) =
        hits.add(SearchHit(type, lat, lon, f.confidence, f.sourceTrip))

    if ("speed_breaker" in want) c.speedBreakers.forEach { add("speed_breaker", it, it.location.lat, it.location.lon) }
    if ("traffic_signal" in want) c.signals.forEach { add("traffic_signal", it, it.location.lat, it.location.lon) }
    if ("traffic_sign" in want || "stop_sign" in want)
        c.signs.filter { "traffic_sign" in want || it.signClass == SignClass.STOP }
            .forEach { add("traffic_sign", it, it.location.lat, it.location.lon) }
    if ("pothole" in want) c.conditions.filter { it.type == RoadConditionType.POTHOLE }
        .forEach { add("pothole", it, it.geometry.first().lat, it.geometry.first().lon) }
    if ("construction" in want || "work_zone" in want)
        c.conditions.filter { it.type == RoadConditionType.WORK_ZONE || it.type == RoadConditionType.CONSTRUCTION }
            .forEach { add("construction", it, it.geometry.first().lat, it.geometry.first().lon) }
    if ("rough_surface" in want) c.conditions.filter { it.type == RoadConditionType.ROUGH_SURFACE }
        .forEach { add("rough_surface", it, it.geometry.first().lat, it.geometry.first().lon) }
    if ("intersection" in want) c.intersections.forEach { add("intersection", it, it.location.lat, it.location.lon) }
    if ("missing_lane_markings" in want)
        c.segments.filter { it.confidence < 0.6 }.forEach { add("missing_lane_markings", it, it.geometry.first().lat, it.geometry.first().lon) }

    respond(ex, 200, "application/json", json.encodeToString(SearchResponse.serializer(), SearchResponse(hits.size, hits)))
}

// --- http helpers ---------------------------------------------------------------------------

private fun query(ex: HttpExchange, key: String): String? =
    ex.requestURI.query?.split("&")?.map { it.split("=", limit = 2) }
        ?.firstOrNull { it[0] == key }?.getOrNull(1)

private fun respond(ex: HttpExchange, code: Int, contentType: String, body: String) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    ex.responseHeaders.add("Content-Type", contentType)
    ex.sendResponseHeaders(code, bytes.size.toLong())
    ex.responseBody.use { it.write(bytes) }
}

private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
