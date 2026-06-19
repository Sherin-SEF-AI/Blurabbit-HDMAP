package com.blurabbit.hdmap.domain.geo

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

class GeoTest {

    @Test
    fun haversine_knownDistance_isAccurate() {
        // ~1 deg of latitude is ~111 km.
        val a = GeoPoint(12.0, 77.0)
        val b = GeoPoint(13.0, 77.0)
        val d = Geo.haversineMeters(a, b)
        assertThat(d).isWithin(2_000.0).of(111_195.0)
    }

    @Test
    fun enu_roundTrip_recoversOriginalPoint() {
        val origin = GeoPoint(12.9716, 77.5946, 920.0)
        val p = GeoPoint(12.9750, 77.5980, 925.0)
        val enu = Geo.toEnu(origin, p)
        val back = Geo.fromEnu(origin, enu)
        assertThat(abs(back.lat - p.lat)).isLessThan(1e-6)
        assertThat(abs(back.lon - p.lon)).isLessThan(1e-6)
        assertThat(abs(back.altM - p.altM)).isLessThan(0.5)
    }

    @Test
    fun enu_origin_isZero() {
        val origin = GeoPoint(12.9716, 77.5946)
        val enu = Geo.toEnu(origin, origin)
        assertThat(abs(enu.east)).isLessThan(1e-6)
        assertThat(abs(enu.north)).isLessThan(1e-6)
    }

    @Test
    fun bearing_dueNorth_isZero() {
        val a = GeoPoint(12.0, 77.0)
        val b = GeoPoint(12.5, 77.0)
        assertThat(Geo.bearingDeg(a, b)).isWithin(0.5).of(0.0)
    }
}
