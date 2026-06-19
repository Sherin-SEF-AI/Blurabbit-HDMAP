package com.blurabbit.hdmap.di

import com.blurabbit.hdmap.export.GeoJsonExporter
import com.blurabbit.hdmap.export.Lanelet2Exporter
import com.blurabbit.hdmap.export.MapExporters
import com.blurabbit.hdmap.export.MbtilesExporter
import com.blurabbit.hdmap.export.OpenDriveExporter
import com.blurabbit.hdmap.hdmap.MappingPipeline
import com.blurabbit.hdmap.hdmap.RoadIntelligenceScorer
import com.blurabbit.hdmap.mapping.IntersectionDetector
import com.blurabbit.hdmap.mapping.MapFeatureExtractor
import com.blurabbit.hdmap.mapping.OdometryBackend
import com.blurabbit.hdmap.mapping.VisualInertialBackend
import com.blurabbit.hdmap.mapping.RoadEventDetector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the pure-Kotlin mapping / hdmap / export components. These modules deliberately have no
 * Hilt of their own, so the graph is assembled here. The [OdometryBackend] binding is the seam to
 * swap GNSS dead-reckoning for a visual-inertial backend later.
 */
@Module
@InstallIn(SingletonComponent::class)
object PipelineModule {

    // Visual-inertial: GNSS-inertial EKF + monocular VO heading fusion (degrades to EKF, then to
    // GNSS dead-reckoning, as inputs drop out). The native ORB-SLAM3/OpenVINS back-end attaches here.
    @Provides @Singleton
    fun provideOdometryBackend(): OdometryBackend = VisualInertialBackend()

    @Provides @Singleton
    fun provideIntersectionDetector(): IntersectionDetector = IntersectionDetector()

    @Provides @Singleton
    fun provideFeatureExtractor(intersections: IntersectionDetector): MapFeatureExtractor =
        MapFeatureExtractor(intersections)

    @Provides @Singleton
    fun provideScorer(): RoadIntelligenceScorer = RoadIntelligenceScorer()

    @Provides @Singleton
    fun provideRoadEventDetector(): RoadEventDetector = RoadEventDetector()

    @Provides @Singleton
    fun provideMappingPipeline(
        odometry: OdometryBackend,
        extractor: MapFeatureExtractor,
        roadEvents: RoadEventDetector,
        scorer: RoadIntelligenceScorer,
    ): MappingPipeline = MappingPipeline(odometry, extractor, roadEvents, scorer)

    @Provides @Singleton fun provideGeoJson(): GeoJsonExporter = GeoJsonExporter()
    @Provides @Singleton fun provideOpenDrive(): OpenDriveExporter = OpenDriveExporter()
    @Provides @Singleton fun provideLanelet2(): Lanelet2Exporter = Lanelet2Exporter()
    @Provides @Singleton fun provideMbtiles(): MbtilesExporter = MbtilesExporter()

    @Provides @Singleton
    fun provideMapExporters(
        geoJson: GeoJsonExporter,
        openDrive: OpenDriveExporter,
        lanelet2: Lanelet2Exporter,
        mbtiles: MbtilesExporter,
    ): MapExporters = MapExporters(geoJson, openDrive, lanelet2, mbtiles)
}
