package com.blurabbit.hdmap.export

import com.blurabbit.hdmap.domain.geo.GeoPoint
import com.blurabbit.hdmap.domain.hdmap.HdMap
import com.blurabbit.hdmap.domain.hdmap.RoadSegment
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import javax.inject.Inject

/**
 * Exports an [HdMap] to ASAM OpenDRIVE (`.xodr`), the road-network format consumed by AV
 * simulators (CARLA, esmini). Each [RoadSegment] becomes a `<road>` whose reference line is the
 * GNSS centerline projected to a local planar frame and emitted as a chain of straight
 * `<geometry><line/></geometry>` records; a single driving lane is written per side with the
 * estimated width. The projection origin is recorded in `<header>` (geoReference) for georeferencing.
 *
 * This is a faithful-but-minimal OpenDRIVE 1.4 writer — straight-segment reference lines and one
 * lane section per road. Spirals/arcs and lane-level connectivity are future refinements.
 */
class OpenDriveExporter @Inject constructor() : HdMapExporter {

    override val format: ExportFormat = ExportFormat.OPENDRIVE

    override fun export(map: HdMap): String {
        val origin = map.segments.firstOrNull()?.geometry?.firstOrNull() ?: GeoPoint(0.0, 0.0)
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("<OpenDRIVE>\n")
        sb.append("""  <header revMajor="1" revMinor="4" name="blurabbit_hdmap_${map.tripId}" version="1.0" """)
        sb.append("""date="${map.generatedAtMs}" north="0.0" south="0.0" east="0.0" west="0.0">""").append('\n')
        sb.append("""    <geoReference><![CDATA[+proj=tmerc +lat_0=${origin.lat} +lon_0=${origin.lon} +k=1 +x_0=0 +y_0=0 +ellps=WGS84]]></geoReference>""").append('\n')
        sb.append("  </header>\n")

        var roadId = 1
        for (segment in map.segments) {
            appendRoad(sb, roadId++, segment, origin)
        }
        sb.append("</OpenDRIVE>\n")
        return sb.toString()
    }

    private fun appendRoad(sb: StringBuilder, id: Int, segment: RoadSegment, origin: GeoPoint) {
        val pts = segment.geometry.map { project(origin, it) }
        if (pts.size < 2) return
        val geometries = ArrayList<String>()
        var s = 0.0
        for (i in 0 until pts.size - 1) {
            val (x0, y0) = pts[i]
            val (x1, y1) = pts[i + 1]
            val dx = x1 - x0; val dy = y1 - y0
            val len = hypot(dx, dy)
            if (len < 1e-6) continue
            val hdg = atan2(dy, dx)
            geometries += """      <geometry s="$s" x="$x0" y="$y0" hdg="$hdg" length="$len"><line/></geometry>"""
            s += len
        }
        val laneWidth = segment.widthM / 2.0

        sb.append("""  <road name="seg_${segment.id}" length="$s" id="$id" junction="-1">""").append('\n')
        sb.append("    <planView\n")
        geometries.forEach { sb.append(it).append('\n') }
        sb.append("    </planView>\n")
        sb.append("    <lanes>\n")
        sb.append("""      <laneSection s="0.0">""").append('\n')
        sb.append("""        <left><lane id="1" type="driving" level="false">""").append('\n')
        sb.append("""          <width sOffset="0.0" a="$laneWidth" b="0.0" c="0.0" d="0.0"/>""").append('\n')
        sb.append("        </lane></left>\n")
        sb.append("""        <center><lane id="0" type="none" level="false"/></center>""").append('\n')
        sb.append("""        <right><lane id="-1" type="driving" level="false">""").append('\n')
        sb.append("""          <width sOffset="0.0" a="$laneWidth" b="0.0" c="0.0" d="0.0"/>""").append('\n')
        sb.append("        </lane></right>\n")
        sb.append("      </laneSection>\n")
        sb.append("    </lanes>\n")
        sb.append("  </road>\n")
    }

    /** Equirectangular projection to a local tangent plane (metres) anchored at [origin]. */
    private fun project(origin: GeoPoint, p: GeoPoint): Pair<Double, Double> {
        val x = Math.toRadians(p.lon - origin.lon) * cos(Math.toRadians(origin.lat)) * EARTH_R
        val y = Math.toRadians(p.lat - origin.lat) * EARTH_R
        return x to y
    }

    private companion object {
        const val EARTH_R = 6_378_137.0
    }
}
