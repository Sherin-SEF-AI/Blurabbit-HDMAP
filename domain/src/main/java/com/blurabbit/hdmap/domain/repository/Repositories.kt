package com.blurabbit.hdmap.domain.repository

import com.blurabbit.hdmap.domain.hdmap.HdMap
import com.blurabbit.hdmap.domain.model.Trip
import com.blurabbit.hdmap.domain.model.TripStatus
import kotlinx.coroutines.flow.Flow

/** Persistence boundary for trips. Implemented by the [:data] module (Room). */
interface TripRepository {
    fun observeTrips(): Flow<List<Trip>>
    suspend fun getTrip(id: String): Trip?
    suspend fun upsert(trip: Trip)
    suspend fun updateStatus(id: String, status: TripStatus)
    suspend fun delete(id: String)
}

/** Persistence boundary for generated HD maps (one per trip). */
interface MapRepository {
    suspend fun saveMap(map: HdMap)
    suspend fun getMap(tripId: String): HdMap?
    fun observeMap(tripId: String): Flow<HdMap?>
    suspend fun delete(tripId: String)
}
