package com.blurabbit.hdmap.domain.usecase

import com.blurabbit.hdmap.domain.model.Trip
import com.blurabbit.hdmap.domain.model.TripStatus
import com.blurabbit.hdmap.domain.repository.TripRepository
import javax.inject.Inject

/** Creates and persists a new trip in [TripStatus.RECORDING]. */
class CreateTripUseCase @Inject constructor(
    private val tripRepository: TripRepository,
) {
    suspend operator fun invoke(
        id: String,
        name: String,
        startWallMs: Long,
        startElapsedNs: Long,
        logDir: String,
    ): Trip {
        val trip = Trip(
            id = id,
            name = name,
            status = TripStatus.RECORDING,
            startWallMs = startWallMs,
            startElapsedNs = startElapsedNs,
            logDir = logDir,
        )
        tripRepository.upsert(trip)
        return trip
    }
}
