package com.blurabbit.hdmap.perception.di

import com.blurabbit.hdmap.perception.DrivableAreaDetector
import com.blurabbit.hdmap.perception.EmptyRoadFeatureDetector
import com.blurabbit.hdmap.perception.LaneDetector
import com.blurabbit.hdmap.perception.RoadFeatureDetector
import com.blurabbit.hdmap.perception.SsdTrafficSignDetector
import com.blurabbit.hdmap.perception.SsdTrafficSignalDetector
import com.blurabbit.hdmap.perception.TrafficSignDetector
import com.blurabbit.hdmap.perception.TrafficSignalDetector
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires the perception detectors.
 *
 * Traffic **signs** (stop signs) and **signals** (traffic lights) use the real bundled SSD
 * MobileNet COCO model; **lane/road geometry** uses the real TwinLiteNet drivable-area model via
 * [DrivableAreaDetector] (estimates road width via IPM). Only the generic road-feature detector
 * remains a stub — road-surface events (speed breakers / potholes / rough) come from the IMU
 * road-event detector instead, so every layer is populated by a real signal.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PerceptionModule {

    @Binds @Singleton
    abstract fun bindSignalDetector(impl: SsdTrafficSignalDetector): TrafficSignalDetector

    @Binds @Singleton
    abstract fun bindSignDetector(impl: SsdTrafficSignDetector): TrafficSignDetector

    @Binds @Singleton
    abstract fun bindLaneDetector(impl: DrivableAreaDetector): LaneDetector

    companion object {
        @Provides @Singleton fun provideRoadFeatureDetector(): RoadFeatureDetector = EmptyRoadFeatureDetector()
    }
}
