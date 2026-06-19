package com.blurabbit.hdmap.data.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.blurabbit.hdmap.data.db.AppDatabase
import com.blurabbit.hdmap.data.db.MapDao
import com.blurabbit.hdmap.data.db.TripDao
import com.blurabbit.hdmap.data.repository.MapRepositoryImpl
import com.blurabbit.hdmap.data.repository.TripRepositoryImpl
import com.blurabbit.hdmap.domain.repository.MapRepository
import com.blurabbit.hdmap.domain.repository.TripRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideTripDao(db: AppDatabase): TripDao = db.tripDao()
    @Provides fun provideMapDao(db: AppDatabase): MapDao = db.mapDao()

    @Provides
    @Singleton
    fun provideTripRepository(dao: TripDao): TripRepository = TripRepositoryImpl(dao)

    @Provides
    @Singleton
    fun provideMapRepository(dao: MapDao, json: Json): MapRepository = MapRepositoryImpl(dao, json)
}
