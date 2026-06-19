package com.blurabbit.hdmap

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point. `@HiltAndroidApp` bootstraps the DI graph; implementing
 * [Configuration.Provider] hands WorkManager the Hilt worker factory so upload workers can be
 * constructed with injected dependencies.
 */
@HiltAndroidApp
class HdMapApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var autoProcessor: AutoProcessor

    override fun onCreate() {
        super.onCreate()
        // Generate the HD map + GeoJSON automatically as soon as a trip finishes recording.
        autoProcessor.start()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
