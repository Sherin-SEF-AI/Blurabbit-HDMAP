package com.blurabbit.hdmap.ui.trips

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blurabbit.hdmap.domain.model.Trip

@Composable
fun TripsScreen(
    onOpenTrip: (String) -> Unit,
    viewModel: TripsViewModel = hiltViewModel(),
) {
    val trips by viewModel.trips.collectAsStateWithLifecycle()

    if (trips.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("No trips yet", style = MaterialTheme.typography.titleMedium)
            Text("Start a capture from the Capture tab to record your first drive.")
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(trips, key = { it.id }) { trip -> TripRow(trip, onOpenTrip) }
    }
}

@Composable
private fun TripRow(trip: Trip, onOpen: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpen(trip.id) },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(trip.name, style = MaterialTheme.typography.titleMedium)
            Text("Status: ${trip.status}")
            Text("%.2f km · %.0fs · %d features".format(
                trip.distanceMeters / 1000.0, trip.durationMs / 1000.0, trip.featureCount,
            ))
        }
    }
}
