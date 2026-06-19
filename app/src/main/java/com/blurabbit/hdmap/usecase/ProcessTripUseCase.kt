package com.blurabbit.hdmap.usecase

import com.blurabbit.hdmap.capture.log.SensorLogReader
import com.blurabbit.hdmap.core.common.dispatchers.AppDispatchers
import com.blurabbit.hdmap.domain.hdmap.HdMap
import com.blurabbit.hdmap.domain.model.TripStatus
import com.blurabbit.hdmap.domain.repository.MapRepository
import com.blurabbit.hdmap.domain.repository.TripRepository
import com.blurabbit.hdmap.hdmap.MappingPipeline
import com.blurabbit.hdmap.mapping.OdometryInput
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Generates the HD map for a recorded trip: replays the per-trip sensor logs, runs the local
 * mapping pipeline (trajectory → features → road-intelligence scores), persists the result, and
 * flips the trip to [TripStatus.MAPPED]. Perception detections are empty until a model is bundled,
 * so this currently produces the GNSS-derived geometry plus scores.
 */
class ProcessTripUseCase @Inject constructor(
    private val tripRepository: TripRepository,
    private val mapRepository: MapRepository,
    private val pipeline: MappingPipeline,
    private val dispatchers: AppDispatchers,
) {
    suspend operator fun invoke(tripId: String): Result<HdMap> = withContext(dispatchers.default) {
        val trip = tripRepository.getTrip(tripId)
            ?: return@withContext Result.failure(IllegalStateException("Trip not found: $tripId"))
        runCatching {
            tripRepository.updateStatus(tripId, TripStatus.PROCESSING)
            val reader = SensorLogReader(File(trip.logDir))
            val input = OdometryInput(
                tripId = tripId,
                gnss = reader.readGnss(),
                imu = reader.readImu(),
                orientation = reader.readOrientation(),
                vo = reader.readVo(),
            )
            val map = pipeline.build(input, detections = reader.readDetections(), generatedAtMs = System.currentTimeMillis())
            mapRepository.saveMap(map)
            tripRepository.upsert(trip.copy(status = TripStatus.MAPPED, featureCount = map.featureCount))
            map
        }.onFailure { tripRepository.updateStatus(tripId, TripStatus.FAILED) }
    }
}
