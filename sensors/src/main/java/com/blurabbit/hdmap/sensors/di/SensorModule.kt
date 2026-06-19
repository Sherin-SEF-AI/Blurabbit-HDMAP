package com.blurabbit.hdmap.sensors.di

import com.blurabbit.hdmap.sensors.SensorSource
import com.blurabbit.hdmap.sensors.impl.GnssSensorSource
import com.blurabbit.hdmap.sensors.impl.ImuSensorSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/**
 * Collects every [SensorSource] into a `Set<SensorSource>` the recorder consumes. Adding a new
 * sensor (RTK GNSS, OBD-II, CAN bus, LiDAR) is one `@Binds @IntoSet` line here.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SensorModule {

    @Binds @IntoSet @Singleton
    abstract fun bindGnss(impl: GnssSensorSource): SensorSource

    @Binds @IntoSet @Singleton
    abstract fun bindImu(impl: ImuSensorSource): SensorSource
}
