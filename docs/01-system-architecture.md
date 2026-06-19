# Blurabbit HD Map Collector - System Architecture

## 1. Purpose

Blurabbit turns any Android 12+ smartphone mounted in a vehicle into an HD-mapping probe. It
captures **camera + GNSS + IMU** on one synchronized clock, runs an on-device **perception +
local-mapping** pipeline, and produces **lane-level HD-map features** that are exported to industry
formats and (optionally) uploaded for **crowd-sourced fusion** into a consensus HD map.

This is a *map-generation* platform, not a navigation app.

## 2. Layered, modular, event-driven architecture

The Android app is split into Gradle modules along clean-architecture boundaries. The arrows show
the allowed compile dependencies (everything points toward `:domain`, which is pure Kotlin).

```
                         ┌────────────┐
                         │    :app    │  Compose UI · Hilt graph · use cases
                         └─────┬──────┘
       ┌─────────────┬────────┼─────────┬───────────┬──────────┐
       ▼             ▼        ▼         ▼           ▼          ▼
   :capture      :sensors  :perception :hdmap    :export    :upload
   (CameraX,     (GNSS,    (TFLite      (scoring  (GeoJSON/  (WorkMgr
    service,      IMU       detectors,   + pipe)   OpenDRIVE/ queue)
    recorder)     sources)  stubbed)        │      Lanelet2)    │
       │             │          │           ▼         │         │
       │             │          │        :mapping     │         │
       │             │          │     (odometry,      │         │
       │             │          │      features)      │         │
       └─────────────┴──────────┴──────────┴──────────┴─────────┘
                         ▼              ▼
                    :core:clock     :core:common
                         └──────┬───────┘
                                ▼
                            :domain   (models, geometry, repository interfaces)
```

| Module | Type | Responsibility |
|---|---|---|
| `:domain` | pure JVM | HD-map data model, geometry (WGS84/ENU), sensor samples, repository interfaces, use-case contracts |
| `:core:common` | android-lib | dispatchers, `Outcome`, DI for cross-cutting concerns |
| `:core:clock` | android-lib | `MonotonicClock`, `ClockSynchronizer`, `DriftMonitor` - the synchronization engine |
| `:data` | android-lib | Room database, DAOs, repository implementations |
| `:sensors` | android-lib | `SensorSource` plugin set (GNSS, IMU) |
| `:capture` | android-lib | CameraX controller, `TripRecorder` state machine, foreground service, JSONL logging |
| `:perception` | android-lib | pluggable lane/sign/signal/road-feature detectors + LiteRT harness (stubbed) |
| `:mapping` | pure JVM | odometry backends, trajectory estimation, map-feature extraction, intersection detection |
| `:hdmap` | pure JVM | road-intelligence scoring + the mapping pipeline orchestration |
| `:export` | pure JVM | GeoJSON / OpenDRIVE / Lanelet2 / vector-tile serializers |
| `:upload` | android-lib | WorkManager upload queue + ingestion client |
| `:app` | android-app | Compose UI (MVVM), Hilt wiring, `ProcessTripUseCase` / `ExportTripUseCase` |

## 3. Runtime data flow

```
            ┌──────── Foreground Service (LifecycleService) ────────┐
 Camera ───►│ CameraController ─┐                                   │
 GNSS  ───►│ GnssSensorSource ─┼─► TripRecorder ─► SensorLogWriter ├─► trips/<id>/*.jsonl
 IMU   ───►│ ImuSensorSource ──┘     │  (unified clock)             │   + Room TripEntity
            └────────────────────────┼──────────────────────────────┘
                                     ▼  (on stop)
              ProcessTripUseCase ─► SensorLogReader ─► MappingPipeline
                                                          │
              OdometryBackend ─► Trajectory ─► MapFeatureExtractor ─► HdMap
                                                          │
              PerceptionPipeline (detections) ───────────┘
                                                          ▼
                              RoadIntelligenceScorer ─► scored HdMap
                                                          ▼
                              MapRepository (Room, JSON) ─► ExportTripUseCase ─► GeoJSON/.xodr/.osm
                                                                                      │
                                                                          UploadScheduler (WorkManager)
```

Events (sensor records, recording-state changes, timestamp warnings) flow as Kotlin `Flow`/
`StateFlow`, decoupling producers (sensors, recorder) from consumers (UI, logger, health).

## 4. Synchronization engine (the core invariant)

Every sample is stamped on **one** timebase: `SystemClock.elapsedRealtimeNanos()`.

- GNSS fixes are already on this clock (`Location.getElapsedRealtimeNanos`) → identity source.
- `SensorEvent.timestamp` and camera frame timestamps have device-dependent epochs; `ClockSynchronizer`
  estimates each source's offset as the running **minimum** observed delivery offset, EMA-smoothed.
- `DriftMonitor` validates monotonicity, future timestamps, and offset drift, surfacing warnings
  without dropping data.

This guarantees cross-sensor alignment, which is what makes lane-level fusion possible.

## 5. Extensibility seams

- **New sensor** (RTK GNSS, OBD-II, CAN, LiDAR): implement `SensorSource`, bind `@IntoSet`. Nothing
  else changes.
- **New perception model**: implement a detector against `TfliteRuntime`, swap the `@Provides` in
  `PerceptionModule`. Models: YOLOv11, RT-DETR, SegFormer, YOLOP, LaneATT.
- **New odometry** (ORB-SLAM3 / OpenVINS / VINS-Fusion): implement `OdometryBackend`, rebind in
  `PipelineModule`.
- **New export format**: implement `HdMapExporter`, add to `MapExporters`.

See the other docs in this folder for the HD-map schema, backend, database, API, export formats,
scaling, performance, and roadmap.
