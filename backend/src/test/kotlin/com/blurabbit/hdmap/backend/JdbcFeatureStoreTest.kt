package com.blurabbit.hdmap.backend

import com.blurabbit.hdmap.domain.geo.GeoPoint
import com.blurabbit.hdmap.domain.hdmap.HdMap
import com.blurabbit.hdmap.domain.hdmap.SpeedBreaker
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exercises the real JDBC persistence path against an embedded H2 database (PostgreSQL-compatible
 * mode), so the same SQL that runs on production PostgreSQL is verified here. Each test uses a
 * uniquely-named in-memory DB kept alive via DB_CLOSE_DELAY=-1.
 */
class JdbcFeatureStoreTest {

    private fun newStore(): JdbcFeatureStore {
        val name = "blurabbit_test_${dbSeq.incrementAndGet()}"
        return JdbcFeatureStore("jdbc:h2:mem:$name;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
    }

    private companion object { val dbSeq = AtomicInteger(0) }

    private fun trip(id: String, lat: Double, lon: Double, conf: Double) = HdMap(
        tripId = id,
        generatedAtMs = 1,
        speedBreakers = listOf(SpeedBreaker("sb_$id", GeoPoint(lat, lon), null, conf, 1, id)),
    )

    @Test
    fun ingestsPersistsAndFusesAcrossConnections() {
        val store = newStore()
        store.ingest(trip("tripA", 12.9700000, 77.5900000, 0.6))
        store.ingest(trip("tripB", 12.9700150, 77.5900150, 0.6)) // ~2 m away → fuses

        assertThat(store.tripCount()).isEqualTo(2)
        assertThat(store.tripIds()).containsExactly("tripA", "tripB")
        // Fusion ran over the persisted rows: one consensus breaker, confidence boosted.
        assertThat(store.consensus.speedBreakers).hasSize(1)
        assertThat(store.consensus.speedBreakers.first().confidence).isWithin(0.001).of(0.84)
    }

    @Test
    fun upsertReplacesSameTrip() {
        val store = newStore()
        store.ingest(trip("tripA", 12.97, 77.59, 0.6))
        store.ingest(trip("tripA", 12.97, 77.59, 0.9)) // same id → replace, not duplicate
        assertThat(store.tripCount()).isEqualTo(1)
    }

    @Test
    fun queryByBboxFiltersByRegion() {
        val store = newStore()
        store.ingest(trip("near", 12.9700, 77.5900, 0.7))
        store.ingest(trip("far", 40.0000, -74.0000, 0.7)) // New York — outside the query box
        val hits = store.queryByBbox(12.96, 77.58, 12.98, 77.60)
        assertThat(hits.map { it.tripId }).containsExactly("near")
    }
}
