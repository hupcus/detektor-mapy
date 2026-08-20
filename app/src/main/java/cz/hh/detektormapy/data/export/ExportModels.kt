package cz.hh.detektormapy.data.export

import kotlinx.serialization.Serializable
import java.io.File

/** Layout of an export archive; also the contract [ProjectImporter] reads back. */
object ExportLayout {
    const val MANIFEST = "manifest.json"
    const val FINDS = "finds.geojson"
    const val PLACES = "places.geojson"
    const val AREAS = "areas.geojson"
    const val CALIBRATIONS = "calibrations.json"
    const val GCP = "gcp.json"
    const val TRACKS_DIR = "tracks"
    const val PHOTOS_DIR = "photos"

    /** Bumped whenever the archive layout changes in a way importers must notice. */
    const val FORMAT_VERSION = 1
}

/** Row counts of one archive, written to `manifest.json` and returned by both directions. */
@Serializable
data class ExportCounts(
    val finds: Int = 0,
    val photos: Int = 0,
    val places: Int = 0,
    val areas: Int = 0,
    val tracks: Int = 0,
    val trackPoints: Int = 0,
    val calibrations: Int = 0,
    val gcpSets: Int = 0,
    val gcpPoints: Int = 0,
)

/** `manifest.json`. */
@Serializable
data class ExportManifest(
    /** [cz.hh.detektormapy.data.DetektorDatabase.VERSION] the archive was written from. */
    val schemaVersion: Int,
    /** [ExportLayout.FORMAT_VERSION]. */
    val formatVersion: Int = ExportLayout.FORMAT_VERSION,
    val exportedAt: Long,
    val app: String = "DetektorMapy",
    val counts: ExportCounts = ExportCounts(),
    /** Photos whose source file was gone at export time; the rows are still exported. */
    val missingPhotoFiles: Int = 0,
)

/** A calibration row in `calibrations.json`. */
@Serializable
data class CalibrationDto(
    val id: Long,
    val externalId: String,
    val layerId: String,
    val label: String,
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
    val matrix: List<Double>,
    val createdAt: Long,
    val updatedAt: Long,
    val active: Boolean,
)

/** A GCP point pair in `gcp.json`. */
@Serializable
data class GcpPointDto(
    val id: Long,
    val srcX: Double,
    val srcY: Double,
    val dstX: Double,
    val dstY: Double,
    val label: String,
)

/** A GCP set in `gcp.json`. */
@Serializable
data class GcpSetDto(
    val id: Long,
    val externalId: String,
    val layerId: String,
    val name: String,
    val imagePath: String? = null,
    val createdAt: Long,
    val points: List<GcpPointDto> = emptyList(),
)

/** Outcome of [ProjectExporter.export]. */
data class ExportResult(
    val archive: File,
    val counts: ExportCounts,
    /** Photo rows whose file could not be read; the archive is still valid. */
    val missingPhotoFiles: Int,
)

/** Outcome of [ProjectImporter.import]. */
data class ImportResult(
    /** Rows actually inserted. */
    val imported: ExportCounts,
    /** Rows recognised as already present and therefore left alone. */
    val skipped: ExportCounts,
    /** Non-fatal problems: unreadable entries, malformed GPX, missing photo files. */
    val warnings: List<String> = emptyList(),
)
