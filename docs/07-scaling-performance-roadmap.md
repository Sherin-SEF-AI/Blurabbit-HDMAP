# Scaling, Performance & Roadmap

## Performance (on-device)

| Concern | Approach (implemented / planned) |
|---|---|
| Sensor throughput (100-200 Hz IMU) | dedicated `HandlerThread`, hardware FIFO batching (`maxReportLatency` 20 ms), lock-free `RateTracker` |
| Camera hot path | CameraX `STRATEGY_KEEP_ONLY_LATEST`; metadata-only logging; perception runs on a separate executor with frame striding |
| Write amplification | append-only JSONL per stream (no per-sample DB rows); Room only for trip + map metadata |
| Clock cost | single `elapsedRealtimeNanos` read per sample; offset estimate is O(1) EMA |
| Battery/thermal | foreground service with declared types; planned adaptive frame rate + thermal throttling hooks |
| Memory | streaming writers, bounded buffers; trajectory simplified (Douglas-Peucker) before feature build |
| Inference | LiteRT GPU→NNAPI→CPU fallback (`TfliteRuntime`); quantized models; configurable inference stride |

## Scaling (backend)

- **Stateless, queue-driven fusion workers** autoscaled on queue depth (KEDA) - throughput scales
  linearly with trips/sec.
- **Spatial sharding** by S2/geohash cell so fusion and tiling parallelize across regions with no
  cross-shard coordination.
- **Read path** fully CDN-cached vector tiles; PostGIS read replicas for feature/search queries.
- **Storage tiering** hot (PostGIS) / warm (exports in object storage) / cold (archived raw logs).
- **Idempotent ingestion** keyed by `tripId` so retries never double-count an observation.

## Implementation roadmap

**Phase 0 - Collector (this build, done)**
Modular Android app; synchronized camera+GNSS+IMU capture; JSONL logging; GNSS-baseline mapping;
HD-map model; GeoJSON/OpenDRIVE/Lanelet2 export; road-intelligence scoring; pluggable perception +
upload seams. Builds to a debug APK; unit-tested geometry/mapping/export/clock.

**Phase 1 - Real perception**
Bundle a lane-segmentation model (YOLOP/LaneATT) + a sign/signal detector (YOLOv11/RT-DETR) via
`TfliteRuntime`; lift detections to world space; populate sign/signal/condition layers.

**Phase 2 - Visual-inertial odometry**
Add an `OdometryBackend` bridging ORB-SLAM3 / OpenVINS / VINS-Fusion (native) for sub-metre,
lane-accurate trajectories; tighten lane geometry.

**Phase 3 - Backend & fusion**
Ingestion service, object storage, Kafka, PostGIS feature store, fusion workers, vector tiles/CDN.

**Phase 4 - Crowd-sourced consensus + dataset search**
Multi-trip fusion producing consensus maps with confidence boosting; dataset search API; on-demand
regional OpenDRIVE/Lanelet2 export for AV simulation (CARLA/LGSVL) and ADAS.

**Phase 5 - Hardware expansion**
RTK GNSS (ZED-F9P), external/USB & multi-camera rigs, LiDAR, CAN/OBD-II, fleet management - each a
new `SensorSource`, no pipeline changes.
