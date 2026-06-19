# API Specification

REST/JSON over HTTPS. Auth: `Authorization: Bearer <token>` + `X-Blurabbit-Node: <nodeId>`.
The app's `:upload` module is the client; `HdMapUploader`/`UploadConfig` hold the endpoint.

## Ingestion

### `POST /v1/trips`
Create a trip and obtain upload URLs.
```jsonc
// request
{ "tripId": "trip_...", "nodeId": "...", "startedAt": 0, "distanceM": 1234.5,
  "bbox": [minLon,minLat,maxLon,maxLat], "artifacts": ["gnss.jsonl","imu.jsonl","map.geojson"] }
// response
{ "tripId": "trip_...", "uploadUrls": { "gnss.jsonl": "https://...signed", "map.geojson": "https://..." } }
```

### `PUT <signed-url>`
Direct upload of each artifact to object storage (multipart for large files).

### `POST /v1/trips/{tripId}/complete`
Signals all artifacts uploaded → enqueues fusion. `202 Accepted`.

## Map read

### `GET /v1/tiles/{z}/{x}/{y}.pbf`
Mapbox Vector Tile of consensus features (CDN-cached).

### `GET /v1/features?bbox=...&type=traffic_sign`
GeoJSON FeatureCollection of consensus features in a bbox, optionally filtered by type/attributes.

### `GET /v1/export?bbox=...&format=opendrive|lanelet2|geojson`
On-demand export of a region in the requested HD format.

## Dataset Search

### `POST /v1/search`
```jsonc
// request - find roads matching feature/attribute predicates
{ "bbox": [..], "any": ["speed_breaker","pothole"],
  "filters": { "missing_lane_markings": true, "construction": true },
  "minConfidence": 0.5 }
// response
{ "count": 42, "segments": [ { "id":"...", "geom":{...}, "matched":["pothole"], "intelligence":{...} } ] }
```

Backed by PostGIS spatial + JSONB attribute indexes and the vector DB for scene-similarity queries.

## Errors

Standard HTTP codes; body `{ "error": { "code": "...", "message": "..." } }`. Retryable conditions
(5xx, 429) drive the WorkManager exponential backoff already wired in `WorkManagerUploadScheduler`.
