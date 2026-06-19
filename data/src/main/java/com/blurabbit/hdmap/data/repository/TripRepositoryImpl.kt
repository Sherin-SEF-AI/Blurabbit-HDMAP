package com.blurabbit.hdmap.data.repository

import com.blurabbit.hdmap.data.db.TripDao
import com.blurabbit.hdmap.data.db.TripEntity
import com.blurabbit.hdmap.domain.model.Trip
import com.blurabbit.hdmap.domain.model.TripStatus
import com.blurabbit.hdmap.domain.repository.TripRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TripRepositoryImpl @Inject constructor(
    private val dao: TripDao,
) : TripRepository {

    override fun observeTrips(): Flow<List<Trip>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getTrip(id: String): Trip? = dao.getById(id)?.toDomain()

    override suspend fun upsert(trip: Trip) = dao.upsert(trip.toEntity())

    override suspend fun updateStatus(id: String, status: TripStatus) =
        dao.updateStatus(id, status.name)

    override suspend fun delete(id: String) = dao.delete(id)
}

private fun TripEntity.toDomain() = Trip(
    id = id,
    name = name,
    status = runCatching { TripStatus.valueOf(status) }.getOrDefault(TripStatus.FAILED),
    startWallMs = startWallMs,
    endWallMs = endWallMs,
    startElapsedNs = startElapsedNs,
    distanceMeters = distanceMeters,
    durationMs = durationMs,
    maxSpeedMps = maxSpeedMps,
    gpsSamples = gpsSamples,
    imuSamples = imuSamples,
    frameCount = frameCount,
    featureCount = featureCount,
    logDir = logDir,
    notes = notes,
)

private fun Trip.toEntity() = TripEntity(
    id = id,
    name = name,
    status = status.name,
    startWallMs = startWallMs,
    endWallMs = endWallMs,
    startElapsedNs = startElapsedNs,
    distanceMeters = distanceMeters,
    durationMs = durationMs,
    maxSpeedMps = maxSpeedMps,
    gpsSamples = gpsSamples,
    imuSamples = imuSamples,
    frameCount = frameCount,
    featureCount = featureCount,
    logDir = logDir,
    notes = notes,
)
