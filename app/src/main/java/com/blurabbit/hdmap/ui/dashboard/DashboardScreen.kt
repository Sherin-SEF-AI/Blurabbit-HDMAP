package com.blurabbit.hdmap.ui.dashboard

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blurabbit.hdmap.capture.RecordingPhase
import com.blurabbit.hdmap.capture.RecordingState

@Composable
fun DashboardScreen(viewModel: DashboardViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) viewModel.start("")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Blurabbit HD Map Collector", style = MaterialTheme.typography.titleLarge)
        Text(
            "Mount the phone facing the road and start a capture. Camera, GNSS and IMU are recorded " +
                "on one synchronized clock and turned into a lane-level HD map.",
            style = MaterialTheme.typography.bodyMedium,
        )

        StatusCard(state)
        MetricsCard(state)

        if (state.phase == RecordingPhase.IDLE) {
            Button(
                onClick = {
                    permissions.launch(
                        arrayOf(
                            Manifest.permission.CAMERA,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start capture") }
        } else {
            OutlinedButton(onClick = { viewModel.stop() }, modifier = Modifier.fillMaxWidth()) {
                Text("Stop capture")
            }
        }

        if (state.sensorHealth.isNotEmpty()) SensorHealthCard(state)
        if (state.warnings.isNotEmpty()) WarningsCard(state)
    }
}

@Composable
private fun StatusCard(state: RecordingState) {
    val label = when (state.phase) {
        RecordingPhase.RECORDING -> "● RECORDING"
        RecordingPhase.PAUSED -> "❚❚ PAUSED"
        RecordingPhase.STOPPING -> "Finalizing…"
        RecordingPhase.IDLE -> "Idle"
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text("Duration: %.0fs".format(state.durationMs / 1000.0))
            Text("Speed: %.1f m/s   (max %.1f)".format(state.currentSpeedMps, state.maxSpeedMps))
            Text("GPS satellites: ${state.satellitesUsed}/${state.satellitesTotal}")
        }
    }
}

@Composable
private fun MetricsCard(state: RecordingState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Distance"); Text("%.2f km".format(state.distanceMeters / 1000.0))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("GNSS samples"); Text("${state.gpsSamples}")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("IMU samples"); Text("${state.imuSamples}")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Camera frames"); Text("${state.frameCount}")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Detections"); Text("${state.detections}")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Dropped writes"); Text("${state.droppedWrites}")
            }
        }
    }
}

@Composable
private fun SensorHealthCard(state: RecordingState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Sensor health", style = MaterialTheme.typography.titleSmall)
            state.sensorHealth.forEach { h ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(h.sourceId)
                    Text("%.0f / %.0f Hz %s".format(h.actualHz, h.expectedHz, if (h.healthy) "✓" else "⚠"))
                }
            }
        }
    }
}

@Composable
private fun WarningsCard(state: RecordingState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Warnings", style = MaterialTheme.typography.titleSmall)
            state.warnings.takeLast(5).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
