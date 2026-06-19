package com.blurabbit.hdmap.backend

import com.blurabbit.hdmap.domain.hdmap.HdMap
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.DriverManager

/**
 * Persistent feature store over JDBC. Uses portable SQL (DELETE+INSERT upsert, plain numeric bbox
 * columns + btree index) so the exact same code runs on **embedded H2** in tests and **PostgreSQL**
 * in production. The production schema upgrades the bbox to a PostGIS `geometry(4326)` column with a
 * GIST index — see `backend/sql/schema-postgis.sql`; the Kotlin query path is unchanged.
 *
 * Trips are stored as their serialized [HdMap] JSON; the consensus map is recomputed by
 * [FusionEngine] on each ingest and cached in memory (the consensus is also persisted for restart).
 */
class JdbcFeatureStore(
    jdbcUrl: String,
    user: String? = null,
    password: String? = null,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : FeatureStore {

    private val conn: Connection =
        if (user != null) DriverManager.getConnection(jdbcUrl, user, password)
        else DriverManager.getConnection(jdbcUrl)
    private val fusion = FusionEngine()

    @Volatile
    override var consensus: HdMap = HdMap(tripId = "consensus", generatedAtMs = 0)
        private set

    init {
        conn.createStatement().use { st ->
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS trips (
                    trip_id VARCHAR(128) PRIMARY KEY,
                    generated_at BIGINT NOT NULL,
                    min_lat DOUBLE PRECISION, min_lon DOUBLE PRECISION,
                    max_lat DOUBLE PRECISION, max_lon DOUBLE PRECISION,
                    json VARCHAR NOT NULL
                )
                """.trimIndent(),
            )
            st.execute("CREATE INDEX IF NOT EXISTS trips_bbox_idx ON trips (min_lat, min_lon, max_lat, max_lon)")
        }
        refuse()
    }

    @Synchronized
    override fun ingest(map: HdMap) {
        val bbox = Bbox.of(map)
        conn.autoCommit = false
        try {
            conn.prepareStatement("DELETE FROM trips WHERE trip_id = ?").use {
                it.setString(1, map.tripId); it.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO trips (trip_id, generated_at, min_lat, min_lon, max_lat, max_lon, json) VALUES (?,?,?,?,?,?,?)",
            ).use { ps ->
                ps.setString(1, map.tripId)
                ps.setLong(2, map.generatedAtMs)
                if (bbox != null) {
                    ps.setDouble(3, bbox.minLat); ps.setDouble(4, bbox.minLon)
                    ps.setDouble(5, bbox.maxLat); ps.setDouble(6, bbox.maxLon)
                } else {
                    for (i in 3..6) ps.setNull(i, java.sql.Types.DOUBLE)
                }
                ps.setString(7, json.encodeToString(HdMap.serializer(), map))
                ps.executeUpdate()
            }
            conn.commit()
        } catch (t: Throwable) {
            conn.rollback(); throw t
        } finally {
            conn.autoCommit = true
        }
        refuse()
    }

    @Synchronized
    override fun tripCount(): Int =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM trips").use { rs -> rs.next(); rs.getInt(1) }
        }

    @Synchronized
    override fun tripIds(): List<String> =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT trip_id FROM trips ORDER BY trip_id").use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }

    @Synchronized
    override fun queryByBbox(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): List<HdMap> =
        conn.prepareStatement(
            "SELECT json FROM trips WHERE min_lat <= ? AND max_lat >= ? AND min_lon <= ? AND max_lon >= ?",
        ).use { ps ->
            ps.setDouble(1, maxLat); ps.setDouble(2, minLat); ps.setDouble(3, maxLon); ps.setDouble(4, minLon)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(decode(rs.getString(1))) }.filterNotNull()
            }
        }

    private fun loadAll(): List<HdMap> =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT json FROM trips").use { rs ->
                buildList { while (rs.next()) add(decode(rs.getString(1))) }.filterNotNull()
            }
        }

    private fun refuse() {
        consensus = fusion.fuse(loadAll())
    }

    private fun decode(s: String): HdMap? =
        runCatching { json.decodeFromString(HdMap.serializer(), s) }.getOrNull()
}
