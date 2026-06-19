package com.blurabbit.hdmap.ui.tripdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blurabbit.hdmap.domain.hdmap.HdMap
import com.blurabbit.hdmap.domain.model.Trip

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TripDetailScreen(
    onOpenMap: () -> Unit = {},
    viewModel: TripDetailViewModel = hiltViewModel(),
) {
    val trip by viewModel.trip.collectAsStateWithLifecycle()
    val map by viewModel.map.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        trip?.let { TripSummary(it) }

        message?.let {
            Card(Modifier.fillMaxWidth()) { Text(it, Modifier.padding(12.dp)) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { viewModel.generate() }, enabled = !busy) {
                if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp))
                Text("Generate HD map")
            }
            OutlinedButton(onClick = { viewModel.upload() }) { Text("Upload") }
        }

        map?.let { MapSummary(it) }

        if (map != null) {
            Button(onClick = onOpenMap, modifier = Modifier.fillMaxWidth()) { Text("View map") }
            Text("Export", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                viewModel.exportFormats.forEach { fmt ->
                    OutlinedButton(onClick = { viewModel.export(fmt) }) { Text(fmt.displayName) }
                }
            }
        }
    }
}

@Composable
private fun TripSummary(trip: Trip) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(trip.name, style = MaterialTheme.typography.titleLarge)
            Text("Status: ${trip.status}")
            Text("Distance: %.2f km".format(trip.distanceMeters / 1000.0))
            Text("Duration: %.0f s".format(trip.durationMs / 1000.0))
            Text("GNSS: ${trip.gpsSamples} · IMU: ${trip.imuSamples} · Frames: ${trip.frameCount}")
        }
    }
}

@Composable
private fun MapSummary(map: HdMap) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("HD map", style = MaterialTheme.typography.titleMedium)
            Text("Features: ${map.featureCount}")
            Text("Segments ${map.segments.size} · Lanes ${map.lanes.size} · Edges ${map.roadEdges.size}")
            Text("Intersections ${map.intersections.size} · Signs ${map.signs.size} · Signals ${map.signals.size}")
            map.intelligence?.let { ri ->
                Text("Road intelligence", style = MaterialTheme.typography.titleSmall)
                Text("Health %.0f · Quality %.0f · Safety %.0f".format(ri.healthScore, ri.qualityScore, ri.safetyScore))
                Text("Traffic %.0f · Construction %.0f".format(ri.trafficDensityScore, ri.constructionScore))
            }
        }
    }
}
