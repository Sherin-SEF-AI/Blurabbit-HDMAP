package com.blurabbit.hdmap.upload.di

import com.blurabbit.hdmap.upload.UploadScheduler
import com.blurabbit.hdmap.upload.WorkManagerUploadScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// UploadConfig + TripMapJsonProvider are supplied by the app (it owns the backend endpoint and
// storage). This module only binds the WorkManager-backed scheduler.
@Module
@InstallIn(SingletonComponent::class)
abstract class UploadModule {
    @Binds @Singleton
    abstract fun bindScheduler(impl: WorkManagerUploadScheduler): UploadScheduler
}
