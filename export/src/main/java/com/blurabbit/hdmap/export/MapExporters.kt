package com.blurabbit.hdmap.export

import com.blurabbit.hdmap.domain.hdmap.HdMap
import javax.inject.Inject

/**
 * Single entry point the app calls to export an [HdMap] in any [ExportFormat]. Holds every
 * registered [HdMapExporter] so the UI can offer the full set without knowing the concrete classes.
 */
class MapExporters @Inject constructor(
    geoJson: GeoJsonExporter,
    openDrive: OpenDriveExporter,
    lanelet2: Lanelet2Exporter,
    mbtiles: MbtilesExporter,
) {
    private val byFormat: Map<ExportFormat, HdMapExporter> =
        listOf(geoJson, openDrive, lanelet2, mbtiles).associateBy { it.format }

    val formats: List<ExportFormat> = ExportFormat.entries

    fun export(map: HdMap, format: ExportFormat): String =
        byFormat.getValue(format).export(map)

    fun fileName(tripId: String, format: ExportFormat): String =
        "blurabbit_${tripId}.${format.extension}"
}
