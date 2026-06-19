package com.blurabbit.hdmap.usecase

import com.blurabbit.hdmap.capture.TripStorage
import com.blurabbit.hdmap.core.common.dispatchers.AppDispatchers
import com.blurabbit.hdmap.domain.repository.MapRepository
import com.blurabbit.hdmap.export.ExportFormat
import com.blurabbit.hdmap.export.MapExporters
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** Serializes a trip's stored HD map to [format] and writes it under the trip's exports directory. */
class ExportTripUseCase @Inject constructor(
    private val mapRepository: MapRepository,
    private val exporters: MapExporters,
    private val storage: TripStorage,
    private val dispatchers: AppDispatchers,
) {
    suspend operator fun invoke(tripId: String, format: ExportFormat): File? =
        withContext(dispatchers.io) {
            val map = mapRepository.getMap(tripId) ?: return@withContext null
            val content = exporters.export(map, format)
            val file = File(storage.exportsDir(tripId), exporters.fileName(tripId, format))
            file.writeText(content)
            file
        }
}
