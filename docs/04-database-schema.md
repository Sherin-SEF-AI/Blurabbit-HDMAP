# Database Schema

## On-device (Room / SQLite, WAL)

Implemented in [`data/.../db`](../data/src/main/java/com/blurabbit/hdmap/data/db).

### `trips`
| Column | Type | Notes |
|---|---|---|
| id | TEXT PK | `trip_<uuid>` |
| name | TEXT | |
| status | TEXT | RECORDING / COMPLETED / PROCESSING / MAPPED / FAILED |
| startWallMs, endWallMs | INTEGER | wall clock (display only) |
| startElapsedNs | INTEGER | unified clock anchor |
| distanceMeters, durationMs, maxSpeedMps | REAL/INT | rollup stats |
| gpsSamples, imuSamples, frameCount | INTEGER | counters |
| featureCount | INTEGER | features in generated map |
| logDir | TEXT | path to per-trip JSONL logs |
| notes | TEXT | |

### `hd_maps`
| Column | Type | Notes |
|---|---|---|
| tripId | TEXT PK | FK → trips.id |
| generatedAtMs | INTEGER | |
| featureCount | INTEGER | |
| json | TEXT | serialized `HdMap` (kotlinx.serialization) |

Raw high-rate samples are **not** in SQLite - they stream to append-only JSONL
(`gnss.jsonl`, `imu.jsonl`, `orientation.jsonl`, `camera.jsonl`) under `trips/<id>/`, which is far
cheaper than per-sample rows at 100-200 Hz and is replayed by the mapping pipeline.

## Server-side (PostGIS) - design

```sql
CREATE TABLE trips (
  id            UUID PRIMARY KEY,
  node_id       UUID NOT NULL,
  uploaded_at   TIMESTAMPTZ NOT NULL,
  distance_m    DOUBLE PRECISION,
  bbox          GEOMETRY(Polygon, 4326)
);

-- One row per consensus feature; geometry is generic so all feature types share the table.
CREATE TABLE features (
  id            UUID PRIMARY KEY,
  feature_type  TEXT NOT NULL,            -- road_segment, lane, traffic_sign, ...
  geom          GEOMETRY(Geometry, 4326) NOT NULL,
  attributes    JSONB NOT NULL,           -- type-specific fields
  confidence    DOUBLE PRECISION NOT NULL,
  observations  INTEGER NOT NULL DEFAULT 1,
  source_trips  UUID[] NOT NULL,
  first_seen    TIMESTAMPTZ,
  last_seen     TIMESTAMPTZ
);
CREATE INDEX features_geom_gix ON features USING GIST (geom);
CREATE INDEX features_type_idx ON features (feature_type);
CREATE INDEX features_attr_gin ON features USING GIN (attributes);

-- Per-segment road intelligence scores.
CREATE TABLE road_intelligence (
  segment_id    UUID PRIMARY KEY REFERENCES features(id),
  health        REAL, quality REAL, traffic_density REAL, safety REAL, construction REAL,
  updated_at    TIMESTAMPTZ
);
```

GIST on `geom` powers spatial queries; GIN on `attributes` powers attribute filters
("speed_limit = 60", "condition_type = pothole") used by Dataset Search.
