package com.blurabbit.hdmap

import com.blurabbit.hdmap.domain.model.TripStatus
import com.blurabbit.hdmap.domain.repository.MapRepository
import com.blurabbit.hdmap.domain.repository.TripRepository
import com.blurabbit.hdmap.export.ExportFormat
import com.blurabbit.hdmap.usecase.ExportTripUseCase
import com.blurabbit.hdmap.usecase.ProcessTripUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watches for completed trips and automatically generates their HD map + GeoJSON export, so a drive
 * becomes a finished map with no manual step. Idempotent: a trip is processed once (it then leaves
 * the COMPLETED state), guarded by an in-flight set against duplicate flow emissions.
 */
@Singleton
class AutoProcessor @Inject constructor(
    private val tripRepository: TripRepository,
    private val mapRepository: MapRepository,
    private val processTrip: ProcessTripUseCase,
    private val exportTrip: ExportTripUseCase,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inFlight = Collections.synchronizedSet(HashSet<String>())

    fun start() {
        scope.launch {
            tripRepository.observeTrips().collectLatest { trips ->
                trips.filter { it.status == TripStatus.COMPLETED }.forEach { trip ->
                    if (!inFlight.add(trip.id)) return@forEach
                    scope.launch {
                        try {
                            if (mapRepository.getMap(trip.id) == null) {
                                processTrip(trip.id).onSuccess {
                                    exportTrip(trip.id, ExportFormat.GEOJSON)
                                }
                            }
                        } finally {
                            inFlight.remove(trip.id)
                        }
                    }
                }
            }
        }
    }
}
