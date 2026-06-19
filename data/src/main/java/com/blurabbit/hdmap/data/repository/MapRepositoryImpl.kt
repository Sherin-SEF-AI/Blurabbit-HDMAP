package com.blurabbit.hdmap.data.repository

import com.blurabbit.hdmap.data.db.HdMapEntity
import com.blurabbit.hdmap.data.db.MapDao
import com.blurabbit.hdmap.domain.hdmap.HdMap
import com.blurabbit.hdmap.domain.repository.MapRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MapRepositoryImpl @Inject constructor(
    private val dao: MapDao,
    private val json: Json,
) : MapRepository {

    override suspend fun saveMap(map: HdMap) {
        dao.upsert(
            HdMapEntity(
                tripId = map.tripId,
                generatedAtMs = map.generatedAtMs,
                featureCount = map.featureCount,
                json = json.encodeToString(HdMap.serializer(), map),
            ),
        )
    }

    override suspend fun getMap(tripId: String): HdMap? =
        dao.getByTrip(tripId)?.let { decode(it) }

    override fun observeMap(tripId: String): Flow<HdMap?> =
        dao.observeByTrip(tripId).map { entity -> entity?.let { decode(it) } }

    override suspend fun delete(tripId: String) = dao.delete(tripId)

    private fun decode(entity: HdMapEntity): HdMap? =
        runCatching { json.decodeFromString(HdMap.serializer(), entity.json) }.getOrNull()
}
