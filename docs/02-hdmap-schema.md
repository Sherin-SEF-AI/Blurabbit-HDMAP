# HD-Map Schema

The canonical model lives in code at
[`domain/.../hdmap/HdMapModel.kt`](../domain/src/main/java/com/blurabbit/hdmap/domain/hdmap/HdMapModel.kt)
and is the single source of truth for storage, export, and fusion.

## Mandatory provenance (every feature)

Each feature implements `MapFeature` and carries the four spec-required fields:

| Field | Type | Meaning |
|---|---|---|
| `id` | String | globally unique (`<prefix>_<uuid>`) |
| `confidence` | Double (0..1) | observation confidence |
| `timestampNs` | Long | unified-clock ns when observed |
| `sourceTrip` | String | originating trip id (provenance for fusion) |

## Entities

| Entity | Geometry | Key attributes |
|---|---|---|
| `RoadSegment` | Polyline | lengthM, laneCount, widthM, speedLimitKph, direction |
| `Lane` | Polyline (centerline) | segmentId, index, widthM |
| `LaneCenterline` | Polyline | laneId |
| `LaneBoundary` | Polyline | type (SOLID/DASHED/DOUBLE/BOTTS_DOTS/CURB), color, side |
| `RoadEdge` | Polyline | side (LEFT/RIGHT) |
| `Intersection` | Point | type (T/CROSS/ROUNDABOUT/MERGE/SPLIT), armCount |
| `TrafficSignal` | Point | polePosition, signalType |
| `TrafficSign` | Point | signClass (SPEED_LIMIT/STOP/WARNING/DIRECTION/REGULATORY), value |
| `RoadCondition` | Polyline | type (POTHOLE/CRACK/WATERLOGGING/WORK_ZONE/CONSTRUCTION/ROUGH_SURFACE), severity |
| `SpeedBreaker` | Point | heightEstimateCm |
| `Crosswalk` | Polygon | - |
| `StopLine` | Polyline | - |

`HdMap` aggregates all of the above for one trip plus a `RoadIntelligence` bundle
(health / quality / trafficDensity / safety / construction, each 0..100).

## Geometry frame

- Storage & export: **WGS84** lat/lon/alt (`GeoPoint`).
- Internal mapping math: **local ENU** tangent plane anchored at the trip's first GNSS fix
  (`Geo.toEnu`/`fromEnu`), which keeps curvature, lane offsetting, and simplification numerically
  stable, then projects back to WGS84.

## Versioning

The model is `@Serializable` (kotlinx.serialization). Schema evolution uses additive optional
fields; the stored JSON in Room is decoded with `ignoreUnknownKeys = true` so older rows keep
loading. A `schemaVersion` field is added at the first breaking change.
