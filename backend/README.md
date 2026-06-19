# Blurabbit HD-Map Backend (prototype)

A dependency-free JVM service (JDK built-in HTTP server) that ingests per-trip HD maps from the
Android app, **fuses them into a crowd-sourced consensus map**, and serves it for query, dataset
search, and export. It reuses the exact same `:domain` schema and `:export` serializers the app
produces - there is one HD-map model across device and cloud.

## Run

```bash
export JAVA_HOME=/path/to/jdk17
./gradlew :backend:installDist
PORT=8089 ./backend/build/install/backend/bin/backend
# → Blurabbit HD-map backend listening on http://localhost:8089/v1/
```

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/v1/health` | liveness |
| GET | `/v1/stats` | trip count + consensus feature counts + road intelligence |
| POST | `/v1/trips/{id}` | ingest a serialized `HdMap` JSON → re-fuse |
| GET | `/v1/consensus.geojson` | fused consensus map as GeoJSON |
| GET | `/v1/export?format=geojson\|opendrive\|lanelet2\|mbtiles` | export consensus map |
| POST | `/v1/search` | dataset search, body `{"any":["speed_breaker","traffic_signal","pothole","stop_sign","construction","intersection","missing_lane_markings"]}` |

## Fusion engine

`FusionEngine` merges point features (speed breakers, signals, signs, conditions, intersections,
crosswalks) observed by multiple trips:
- **association** - fine spatial grid (~6 m cells) per feature type,
- **geometry** - confidence-weighted centroid,
- **confidence** - noisy-OR boosting (two independent 0.6 sightings → 0.84), with observation
  provenance (`sourceTrip` = comma-joined trip ids).

Linear features (segments/lanes/edges) are unioned for now; polyline fusion is the next step.
Verified by `FusionEngineTest` and a live device→backend upload (`adb reverse tcp:8089 tcp:8089`,
app `UploadConfig.endpoint = http://localhost:8089`).

## Production path

This in-memory prototype maps onto the cloud design in [`../docs/03-backend-and-cloud.md`](../docs/03-backend-and-cloud.md):
API gateway → ingestion → object storage → queue → fusion workers → PostGIS feature store →
tile generator / dataset search → CDN.
