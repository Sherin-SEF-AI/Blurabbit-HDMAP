# Export Formats

Implemented in [`:export`](../export/src/main/java/com/blurabbit/hdmap/export). All exporters take an
`HdMap` and return a text artifact; `MapExporters` is the façade the app calls.

| Format | Class | Extension | Status |
|---|---|---|---|
| GeoJSON | `GeoJsonExporter` | `.geojson` | ✅ full - RFC 7946 FeatureCollection, all feature types, provenance + attributes in `properties` |
| OpenDRIVE | `OpenDriveExporter` | `.xodr` | ✅ functional - ASAM OpenDRIVE 1.4; one `<road>` per segment, straight-line reference geometry, one driving lane per side; geoReference header |
| Lanelet2 | `Lanelet2Exporter` | `.osm` | ✅ functional - OSM XML; `lanelet` relations from left/right bound ways; regulatory POI nodes |
| Vector Tiles / MBTiles | `MbtilesExporter` | `.json` | 🟡 scaffold - emits a TileJSON 3.0 descriptor with bounds + layer set |

## GeoJSON (primary, validation target)

LineString for linear features, Point for point features, Polygon for crosswalks. Every Feature's
`properties` carries `featureType`, `id`, `confidence`, `timestampNs`, `sourceTrip` plus
type-specific keys, so the output is queryable in any GIS tool and round-trips into fusion.
Verified by `ExportTest.geoJson_isValidFeatureCollection`.

## OpenDRIVE

The GNSS centerline is projected to a local planar frame (origin in the `<header>` geoReference) and
emitted as a chain of `<geometry><line/></geometry>` records with cumulative `s`. Suitable for import
into CARLA / esmini. Future refinements: spiral/arc reference lines and lane-level successor/
predecessor connectivity from the intersection graph.

## Lanelet2

Left/right road edges become linestring `way`s; each lane is a `lanelet` relation referencing them.
Signals, signs, speed breakers and stop lines are written as tagged nodes (regulatory elements).
Consumable by Autoware / the lanelet2 library.

## Vector Tiles / MBTiles (next step)

The full path: slice consensus features into a z/x/y pyramid → encode each tile as **MVT/PBF** →
pack tiles into an **MBTiles** SQLite container. The current scaffold produces the TileJSON
descriptor and the GeoJSON exporter already yields the source geometry the tiler consumes. Server-
side this is done by the Tile Generator (see `03-backend-and-cloud.md`); on-device it can run via a
JTS-based slicer.
