# Backend & Cloud Architecture

The Android app is offline-first; the backend exists to **aggregate trips across users** and fuse
them into a consensus HD map. This document is the design for that backend (not yet implemented in
code - the app's `:upload` module is the client seam).

## Components

```
 Phones ──HTTPS──► API Gateway ──► Ingestion Service ──► Object Storage (S3/GCS)
                        │                  │                   (raw JSONL, exports, video)
                        │                  ▼
                        │           Message Queue (Kafka/PubSub)
                        │                  │
                        │                  ▼
                        │           Map Fusion Workers ──► Feature Store (PostGIS)
                        │                  │                       │
                        │                  ▼                       ▼
                        │           Vector DB (tile/feature   Tile Generator ──► MBTiles / XYZ
                        │            embeddings, search)            │
                        ▼                                           ▼
                 Dataset Search API ◄───────────────────── CDN (vector tiles, exports)
```

| Component | Technology | Role |
|---|---|---|
| API Gateway | Envoy / Cloud API Gateway | auth (Bearer + node id), rate limiting, routing |
| Ingestion Service | Kotlin/Go service | validates upload manifest, writes raw artifacts to object storage, emits a "trip ingested" event |
| Object Storage | S3 / GCS | raw per-trip JSONL, sidecar media, generated exports |
| Message Queue | Kafka / Pub/Sub | decouples ingestion from fusion; enables replay |
| Map Fusion Engine | stateless workers | merge overlapping observations into consensus features (see below) |
| Feature Store | PostGIS | authoritative consensus HD-map features, spatial-indexed |
| Vector Database | pgvector / Milvus | similarity search over feature/scene embeddings for dataset search |
| Tile Generator | tippecanoe-style pipeline | slices PostGIS features into MVT vector tiles / MBTiles |
| CDN | CloudFront / Cloud CDN | serves tiles and exports |
| Dataset Search API | service over PostGIS + Vector DB | "roads with potholes / signals / missing markings" |

## Map Fusion Engine

Input: many per-trip `HdMap`s observing the same road. Output: one consensus map.

1. **Spatial bucketing** - index incoming features by geohash / S2 cell.
2. **Association** - match features of the same type within a distance + heading gate
   (e.g. centerlines within 1.5 m, signs within 5 m).
3. **Geometry refinement** - weighted average / spline fit of associated polylines, weighted by each
   observation's `confidence` and GNSS accuracy.
4. **Confidence boosting** - consensus confidence rises with the number of independent `sourceTrip`s
   that agree (Bayesian update); disagreeing observations lower it.
5. **Dedup & retire** - collapse duplicates; retire features unseen across N recent trips
   (handles repainted lanes, removed signs).
6. **Publish** - write consensus features to the Feature Store; trigger tile regeneration for
   affected cells.

The on-device `sourceTrip` + `timestampNs` + `confidence` fields are exactly what this engine needs.

## Cloud infrastructure

- **Compute**: Kubernetes (GKE/EKS); fusion workers as a horizontally-scaled, queue-driven
  Deployment with KEDA autoscaling on queue depth.
- **Storage tiers**: hot (PostGIS, recent tiles), warm (object storage exports), cold (archived raw
  logs in Glacier-class storage).
- **IaC**: Terraform; per-region deployment for data residency (e.g. India/NavIC data kept in-region).
- **Observability**: OpenTelemetry traces from gateway → ingestion → fusion; Prometheus/Grafana.
