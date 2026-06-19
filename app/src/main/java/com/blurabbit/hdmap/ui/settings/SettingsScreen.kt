package com.blurabbit.hdmap.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        InfoCard(
            "Capture",
            "Camera 1080p · GNSS (GPS/GLONASS/Galileo/BeiDou/NavIC) · IMU ≥100 Hz. " +
                "All streams share one SystemClock.elapsedRealtimeNanos timebase.",
        )
        InfoCard(
            "Perception",
            "Lane / sign / signal / road-feature detectors are pluggable. Drop a .tflite model into " +
                "assets/models and swap the binding in PerceptionModule to enable on-device inference.",
        )
        InfoCard(
            "Export",
            "GeoJSON, OpenDRIVE (.xodr), Lanelet2 (.osm) and a vector-tile TileJSON descriptor are " +
                "generated from the trip's HD map.",
        )
        InfoCard("Backend", "Upload endpoint is not configured in this build (offline-first).")
        InfoCard("Version", "Blurabbit HD Map Collector 1.0.0")
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
