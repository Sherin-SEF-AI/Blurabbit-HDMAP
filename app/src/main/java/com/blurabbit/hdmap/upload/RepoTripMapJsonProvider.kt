package com.blurabbit.hdmap.upload

import com.blurabbit.hdmap.domain.hdmap.HdMap
import com.blurabbit.hdmap.domain.repository.MapRepository
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Serializes a trip's stored HD map for upload, reusing the same schema the backend ingests. */
@Singleton
class RepoTripMapJsonProvider @Inject constructor(
    private val mapRepository: MapRepository,
    private val json: Json,
) : TripMapJsonProvider {
    override suspend fun mapJson(tripId: String): String? =
        mapRepository.getMap(tripId)?.let { json.encodeToString(HdMap.serializer(), it) }
}
