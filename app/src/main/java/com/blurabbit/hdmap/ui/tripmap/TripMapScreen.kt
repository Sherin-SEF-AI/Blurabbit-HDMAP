package com.blurabbit.hdmap.ui.tripmap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blurabbit.hdmap.domain.geo.GeoPoint
import com.blurabbit.hdmap.domain.geo.Polyline
import com.blurabbit.hdmap.domain.hdmap.HdMap
import kotlin.math.cos

/**
 * Renders a trip's HD map on a self-contained Compose Canvas (no map SDK / tiles): road/lane/edge
 * polylines + colored feature markers, with pinch-zoom + pan. Reuses the equirectangular
 * projector-with-pan/zoom pattern from the sibling CORRYDYX corridor map.
 */
@Composable
fun TripMapScreen(viewModel: TripMapViewModel = hiltViewModel()) {
    val map by viewModel.map.collectAsStateWithLifecycle()
    val m = map
    if (m == null || allPoints(m).isEmpty()) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("No map geometry yet", style = MaterialTheme.typography.titleMedium)
            Text("Generate the HD map first, or record an outdoor drive so GNSS produces road geometry.")
        }
        return
    }

    var scale by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val pts = remember(m) { allPoints(m) }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF0E0F11))
                .pointerInput(Unit) {
                    detectTransformGestures { _, panChange, zoomChange, _ ->
                        scale = (scale * zoomChange).coerceIn(0.3f, 30f)
                        pan += panChange
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val proj = projector(pts, size.width, size.height, scale, pan)
                fun line(geom: Polyline, color: Color, width: Float) {
                    if (geom.size < 2) return
                    val path = Path()
                    geom.forEachIndexed { i, p ->
                        val o = proj(p.lat, p.lon)
                        if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                    }
                    drawPath(path, color, style = Stroke(width = width))
                }
                fun dot(p: GeoPoint, color: Color, r: Float) {
                    val o = proj(p.lat, p.lon); drawCircle(color, r, o)
                }

                m.roadEdges.forEach { line(it.geometry, Color(0xFF5A5A5A), 3f) }
                m.segments.forEach { line(it.geometry, Color.White, 5f) }
                m.lanes.forEach { line(it.centerline, Color(0xFF40C4FF), 2f) }
                m.centerlines.forEach { line(it.geometry, Color(0xFF18FFFF), 2f) }
                m.conditions.forEach { line(it.geometry, Color(0xFFFF5252), 4f) }
                m.intersections.forEach { dot(it.location, Color(0xFFE040FB), 9f) }
                m.signals.forEach { dot(it.location, Color(0xFF69F0AE), 8f) }
                m.signs.forEach { dot(it.location, Color(0xFFFFD740), 8f) }
                m.speedBreakers.forEach { dot(it.location, Color(0xFFFF6E40), 8f) }
            }
        }
        Legend(m)
    }
}

@Composable
private fun Legend(m: HdMap) {
    Column(Modifier.fillMaxWidth().wrapContentHeight().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("HD map · ${m.featureCount} features", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Swatch(Color.White, "road"); Swatch(Color(0xFF40C4FF), "lane"); Swatch(Color(0xFF5A5A5A), "edge")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Swatch(Color(0xFF69F0AE), "signal"); Swatch(Color(0xFFFFD740), "sign")
            Swatch(Color(0xFFFF6E40), "breaker"); Swatch(Color(0xFFE040FB), "x-section")
        }
    }
}

@Composable
private fun Swatch(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(12.dp).background(color))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

private fun allPoints(m: HdMap): List<GeoPoint> = buildList {
    m.segments.forEach { addAll(it.geometry) }
    m.lanes.forEach { addAll(it.centerline) }
    m.centerlines.forEach { addAll(it.geometry) }
    m.roadEdges.forEach { addAll(it.geometry) }
    m.conditions.forEach { addAll(it.geometry) }
    m.signals.forEach { add(it.location) }
    m.signs.forEach { add(it.location) }
    m.speedBreakers.forEach { add(it.location) }
    m.intersections.forEach { add(it.location) }
}

/** Equirectangular projection around the point-set mean, with fit-to-view + zoom + pan. */
private fun projector(pts: List<GeoPoint>, w: Float, h: Float, scale: Float, pan: Offset): (Double, Double) -> Offset {
    if (pts.isEmpty()) return { _, _ -> Offset(w / 2, h / 2) }
    val meanLat = pts.map { it.lat }.average()
    val meanLon = pts.map { it.lon }.average()
    val mPerLat = 111_320.0
    val mPerLon = 111_320.0 * cos(Math.toRadians(meanLat))
    val xs = pts.map { (it.lon - meanLon) * mPerLon }
    val ys = pts.map { -(it.lat - meanLat) * mPerLat }
    val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
    val spanX = (maxX - minX).coerceAtLeast(20.0)
    val spanY = (maxY - minY).coerceAtLeast(20.0)
    val fit = (minOf(w / spanX, h / spanY) * 0.85).toFloat()
    return { lat, lon ->
        val mx = (lon - meanLon) * mPerLon
        val my = -(lat - meanLat) * mPerLat
        val px = w / 2 + ((mx - (minX + maxX) / 2) * fit * scale).toFloat() + pan.x
        val py = h / 2 + ((my - (minY + maxY) / 2) * fit * scale).toFloat() + pan.y
        Offset(px, py)
    }
}
