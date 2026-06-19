package com.blurabbit.hdmap.domain.geo

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** A WGS84 geographic coordinate (degrees + metres). */
@Serializable
data class GeoPoint(
    val lat: Double,
    val lon: Double,
    val altM: Double = 0.0,
)

/** A point in a local East-North-Up tangent plane (metres) relative to some origin. */
data class EnuPoint(
    val east: Double,
    val north: Double,
    val up: Double = 0.0,
)

/** An ordered polyline of geographic points — the geometry type for all linear HD-map features. */
typealias Polyline = List<GeoPoint>

/**
 * Geodesy helpers used by the mapping pipeline and exporters.
 *
 * The local-mapping pipeline works in a metric East-North-Up (ENU) frame anchored at the trip's
 * first fix, then projects features back to WGS84 for storage/export. This keeps geometry math
 * (curvature, lane width, polyline simplification) simple and numerically stable over a trip,
 * while final outputs remain standard lat/lon.
 */
object Geo {
    private const val WGS84_A = 6_378_137.0          // semi-major axis (m)
    private const val WGS84_F = 1.0 / 298.257223563  // flattening
    private const val WGS84_E2 = WGS84_F * (2 - WGS84_F)
    private const val DEG2RAD = PI / 180.0
    private const val RAD2DEG = 180.0 / PI

    /** Great-circle distance in metres (haversine). */
    fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
        val dLat = (b.lat - a.lat) * DEG2RAD
        val dLon = (b.lon - a.lon) * DEG2RAD
        val lat1 = a.lat * DEG2RAD
        val lat2 = b.lat * DEG2RAD
        val h = sin(dLat / 2).let { it * it } +
            cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
        return 2 * WGS84_A * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Initial bearing (degrees, 0..360) from [a] to [b]. */
    fun bearingDeg(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = a.lat * DEG2RAD
        val lat2 = b.lat * DEG2RAD
        val dLon = (b.lon - a.lon) * DEG2RAD
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (atan2(y, x) * RAD2DEG + 360.0) % 360.0
    }

    /** Total length in metres of a polyline. */
    fun lengthMeters(line: Polyline): Double {
        var sum = 0.0
        for (i in 1 until line.size) sum += haversineMeters(line[i - 1], line[i])
        return sum
    }

    /** Project a geographic point to the local ENU plane anchored at [origin]. */
    fun toEnu(origin: GeoPoint, p: GeoPoint): EnuPoint {
        val (ox, oy, oz) = toEcef(origin)
        val (px, py, pz) = toEcef(p)
        val dx = px - ox
        val dy = py - oy
        val dz = pz - oz
        val lat = origin.lat * DEG2RAD
        val lon = origin.lon * DEG2RAD
        val sinLat = sin(lat); val cosLat = cos(lat)
        val sinLon = sin(lon); val cosLon = cos(lon)
        val east = -sinLon * dx + cosLon * dy
        val north = -sinLat * cosLon * dx - sinLat * sinLon * dy + cosLat * dz
        val up = cosLat * cosLon * dx + cosLat * sinLon * dy + sinLat * dz
        return EnuPoint(east, north, up)
    }

    /** Inverse of [toEnu]: ENU offset back to a geographic point. */
    fun fromEnu(origin: GeoPoint, e: EnuPoint): GeoPoint {
        val lat = origin.lat * DEG2RAD
        val lon = origin.lon * DEG2RAD
        val sinLat = sin(lat); val cosLat = cos(lat)
        val sinLon = sin(lon); val cosLon = cos(lon)
        val dx = -sinLon * e.east - sinLat * cosLon * e.north + cosLat * cosLon * e.up
        val dy = cosLon * e.east - sinLat * sinLon * e.north + cosLat * sinLon * e.up
        val dz = cosLat * e.north + sinLat * e.up
        val (ox, oy, oz) = toEcef(origin)
        return fromEcef(ox + dx, oy + dy, oz + dz)
    }

    fun enuDistance(a: EnuPoint, b: EnuPoint): Double = hypot(a.east - b.east, a.north - b.north)

    private fun toEcef(p: GeoPoint): Triple<Double, Double, Double> {
        val lat = p.lat * DEG2RAD
        val lon = p.lon * DEG2RAD
        val sinLat = sin(lat)
        val n = WGS84_A / sqrt(1 - WGS84_E2 * sinLat * sinLat)
        val x = (n + p.altM) * cos(lat) * cos(lon)
        val y = (n + p.altM) * cos(lat) * sin(lon)
        val z = (n * (1 - WGS84_E2) + p.altM) * sinLat
        return Triple(x, y, z)
    }

    private fun fromEcef(x: Double, y: Double, z: Double): GeoPoint {
        val lon = atan2(y, x)
        val p = hypot(x, y)
        var lat = atan2(z, p * (1 - WGS84_E2))
        var alt = 0.0
        repeat(5) {
            val sinLat = sin(lat)
            val n = WGS84_A / sqrt(1 - WGS84_E2 * sinLat * sinLat)
            alt = p / cos(lat) - n
            lat = atan2(z, p * (1 - WGS84_E2 * n / (n + alt)))
        }
        return GeoPoint(lat * RAD2DEG, lon * RAD2DEG, alt)
    }
}
