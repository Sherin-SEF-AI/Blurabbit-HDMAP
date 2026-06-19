-- Blurabbit HD-map backend — PRODUCTION schema (PostgreSQL + PostGIS).
-- The JdbcFeatureStore runs on the portable `trips` table below (also works on H2). In production,
-- enable PostGIS and add the spatial column + GIST index for fast region queries, and materialize
-- the fused consensus into `features` for the tile generator and dataset search.

CREATE EXTENSION IF NOT EXISTS postgis;

-- Raw per-trip maps (matches JdbcFeatureStore; bbox columns + optional PostGIS geometry).
CREATE TABLE IF NOT EXISTS trips (
    trip_id      VARCHAR(128) PRIMARY KEY,
    generated_at BIGINT NOT NULL,
    min_lat      DOUBLE PRECISION,
    min_lon      DOUBLE PRECISION,
    max_lat      DOUBLE PRECISION,
    max_lon      DOUBLE PRECISION,
    bbox         geometry(Polygon, 4326),   -- production: GIST-indexed bbox
    json         TEXT NOT NULL,
    uploaded_at  TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS trips_bbox_gix  ON trips USING GIST (bbox);
CREATE INDEX IF NOT EXISTS trips_bbox_btree ON trips (min_lat, min_lon, max_lat, max_lon);

-- Materialized consensus features (output of the fusion workers) — powers tiles + dataset search.
CREATE TABLE IF NOT EXISTS features (
    id           UUID PRIMARY KEY,
    feature_type TEXT NOT NULL,                  -- road_segment, lane, traffic_signal, speed_breaker, ...
    geom         geometry(Geometry, 4326) NOT NULL,
    attributes   JSONB NOT NULL,
    confidence   DOUBLE PRECISION NOT NULL,
    observations INTEGER NOT NULL DEFAULT 1,
    source_trips TEXT[] NOT NULL,
    first_seen   TIMESTAMPTZ,
    last_seen    TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS features_geom_gix ON features USING GIST (geom);
CREATE INDEX IF NOT EXISTS features_type_idx ON features (feature_type);
CREATE INDEX IF NOT EXISTS features_attr_gin ON features USING GIN (attributes);

-- Per-segment road-intelligence scores.
CREATE TABLE IF NOT EXISTS road_intelligence (
    segment_id      UUID PRIMARY KEY REFERENCES features(id) ON DELETE CASCADE,
    health          REAL, quality REAL, traffic_density REAL, safety REAL, construction REAL,
    updated_at      TIMESTAMPTZ DEFAULT now()
);
