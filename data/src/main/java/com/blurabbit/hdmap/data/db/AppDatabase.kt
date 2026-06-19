package com.blurabbit.hdmap.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [TripEntity::class, HdMapEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun mapDao(): MapDao

    companion object {
        const val NAME = "blurabbit_hdmap.db"
    }
}
