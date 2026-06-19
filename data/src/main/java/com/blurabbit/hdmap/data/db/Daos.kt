package com.blurabbit.hdmap.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Query("SELECT * FROM trips ORDER BY startWallMs DESC")
    fun observeAll(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun getById(id: String): TripEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(trip: TripEntity)

    @Query("UPDATE trips SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface MapDao {
    @Query("SELECT * FROM hd_maps WHERE tripId = :tripId")
    suspend fun getByTrip(tripId: String): HdMapEntity?

    @Query("SELECT * FROM hd_maps WHERE tripId = :tripId")
    fun observeByTrip(tripId: String): Flow<HdMapEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(map: HdMapEntity)

    @Query("DELETE FROM hd_maps WHERE tripId = :tripId")
    suspend fun delete(tripId: String)
}
