package com.blurabbit.hdmap.export

import com.blurabbit.hdmap.domain.geo.GeoPoint
import com.blurabbit.hdmap.domain.geo.Polyline
import com.blurabbit.hdmap.domain.hdmap.HdMap
import com.blurabbit.hdmap.domain.hdmap.RoadSide
import javax.inject.Inject

/**
 * Exports an [HdMap] to the Lanelet2 OSM-XML format used by Autoware and lanelet2. Each lane is
 * written as a `lanelet` relation referencing a left and right linestring `way` (taken from the
 * road edges, or synthesized from the lane centerline when edges are absent). Point features
 * (signals, signs, speed breakers) are emitted as tagged regulatory nodes. Coordinates are WGS84
 * lat/lon as Lanelet2 expects.
 */
class Lanelet2Exporter @Inject constructor() : HdMapExporter {

    override val format: ExportFormat = ExportFormat.LANELET2

    private var nextId = -1
    private val sb = StringBuilder()

    override fun export(map: HdMap): String {
        nextId = -1
        sb.setLength(0)
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<osm version="0.6" generator="blurabbit-hdmap">""").append('\n')

        val left = map.roadEdges.filter { it.side == RoadSide.LEFT }
        val right = map.roadEdges.filter { it.side == RoadSide.RIGHT }
        val pairs = minOf(left.size, right.size)
        for (i in 0 until pairs) {
            val leftWay = wayFromLine(left[i].geometry, "line_thin", "solid")
            val rightWay = wayFromLine(right[i].geometry, "line_thin", "solid")
            relation(
                listOf("way" to ("left" to leftWay), "way" to ("right" to rightWay)),
                tags = listOf("type" to "lanelet", "subtype" to "road", "location" to "urban"),
            )
        }
        // Fall back to lane centerlines as virtual lanelets when no edges exist.
        if (pairs == 0) {
            map.lanes.forEach { lane ->
                val way = wayFromLine(lane.centerline, "virtual", "")
                relation(
                    listOf("way" to ("centerline" to way)),
                    tags = listOf("type" to "lanelet", "subtype" to "road"),
                )
            }
        }

        map.signals.forEach { poiNode(it.location, listOf("type" to "traffic_light")) }
        map.signs.forEach { poiNode(it.location, listOf("type" to "traffic_sign", "subtype" to it.signClass.name.lowercase())) }
        map.speedBreakers.forEach { poiNode(it.location, listOf("type" to "speed_bump")) }
        map.stopLines.forEach { it.geometry.firstOrNull()?.let { p -> poiNode(p, listOf("type" to "stop_line")) } }

        sb.append("</osm>\n")
        return sb.toString()
    }

    private fun node(p: GeoPoint): Int {
        val id = nextId--
        sb.append("""  <node id="$id" lat="${p.lat}" lon="${p.lon}" version="1">""").append('\n')
        sb.append("""    <tag k="ele" v="${p.altM}"/>""").append('\n')
        sb.append("  </node>\n")
        return id
    }

    private fun poiNode(p: GeoPoint, tags: List<Pair<String, String>>): Int {
        val id = nextId--
        sb.append("""  <node id="$id" lat="${p.lat}" lon="${p.lon}" version="1">""").append('\n')
        tags.forEach { (k, v) -> sb.append("""    <tag k="${esc(k)}" v="${esc(v)}"/>""").append('\n') }
        sb.append("  </node>\n")
        return id
    }

    private fun wayFromLine(line: Polyline, type: String, subtype: String): Int {
        val nodeIds = line.map { node(it) }
        val id = nextId--
        sb.append("""  <way id="$id" version="1">""").append('\n')
        nodeIds.forEach { sb.append("""    <nd ref="$it"/>""").append('\n') }
        sb.append("""    <tag k="type" v="${esc(type)}"/>""").append('\n')
        if (subtype.isNotEmpty()) sb.append("""    <tag k="subtype" v="${esc(subtype)}"/>""").append('\n')
        sb.append("  </way>\n")
        return id
    }

    private fun relation(members: List<Pair<String, Pair<String, Int>>>, tags: List<Pair<String, String>>) {
        val id = nextId--
        sb.append("""  <relation id="$id" version="1">""").append('\n')
        members.forEach { (memberType, roleRef) ->
            val (role, ref) = roleRef
            sb.append("""    <member type="$memberType" ref="$ref" role="$role"/>""").append('\n')
        }
        tags.forEach { (k, v) -> sb.append("""    <tag k="${esc(k)}" v="${esc(v)}"/>""").append('\n') }
        sb.append("  </relation>\n")
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
}
