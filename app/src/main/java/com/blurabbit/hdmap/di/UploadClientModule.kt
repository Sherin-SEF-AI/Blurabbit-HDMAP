package com.blurabbit.hdmap.di

import com.blurabbit.hdmap.upload.RepoTripMapJsonProvider
import com.blurabbit.hdmap.upload.TripMapJsonProvider
import com.blurabbit.hdmap.upload.UploadConfig
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Backend upload configuration. [UploadConfig.endpoint] points at the HD-map backend; with
 * `adb reverse tcp:8089 tcp:8089` the phone reaches a backend running on the dev machine. Set to
 * null for fully offline builds.
 */
@Module
@InstallIn(SingletonComponent::class)
object UploadConfigModule {
    @Provides @Singleton
    fun provideUploadConfig(): UploadConfig = UploadConfig(endpoint = "http://localhost:8089")
}

@Module
@InstallIn(SingletonComponent::class)
abstract class UploadBindingsModule {
    @Binds @Singleton
    abstract fun bindMapJsonProvider(impl: RepoTripMapJsonProvider): TripMapJsonProvider
}
