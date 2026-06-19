# Sample data

A real 3.9 km, 14 minute drive through Bangalore, captured and processed entirely on an Android phone with no internet.

| File | What it is |
| --- | --- |
| `bangalore-drive.geojson` | The deduplicated HD map (355 features): road segment, lane centerline, road edges, 31 traffic signals, 2 stop signs, speed breakers, and road conditions. Open it in QGIS, kepler.gl, geojson.io, or GitHub's map view. |
| `bangalore-drive.hdmap.json` | The same map in the native Blurabbit HD map model. |
| `detections-sample.jsonl` | The first 50 raw on device camera detections (SSD MobileNet COCO), one JSON object per line, with normalized bounding boxes and confidence. |
| `visual-odometry-sample.jsonl` | The first 50 raw frame to frame visual odometry estimates, with visual yaw, forward score, and tracked feature count. |

Every feature carries `confidence`, `timestampNs`, and `sourceTrip`, which is what lets the backend fuse many drives into a consensus map.
