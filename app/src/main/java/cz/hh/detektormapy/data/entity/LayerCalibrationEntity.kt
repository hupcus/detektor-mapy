package cz.hh.detektormapy.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A per-region georeference correction for one overlay layer (PLAN.md section 6, mode A).
 *
 * The six affine coefficients are stored as six real columns rather than a JSON blob so they stay
 * queryable and cheap to migrate. The bounding box is likewise four real columns, which lets the
 * "nearest calibration wins" rule -- smallest bbox that still contains the point -- run entirely
 * in SQL. The transform itself operates in Web Mercator metres, see
 * [cz.hh.detektormapy.calibration.Affine2D].
 */
@Entity(
    tableName = "layer_calibrations",
    indices = [
        Index(value = ["layerId"], name = "index_layer_calibrations_layerId"),
        Index(value = ["layerId", "active"], name = "index_layer_calibrations_layerId_active"),
        Index(value = ["createdAt"], name = "index_layer_calibrations_createdAt"),
    ],
)
data class LayerCalibrationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    /** Matches [cz.hh.detektormapy.map.LayerDef.id]. */
    val layerId: String,
    /** User-visible name, e.g. "Lipno - sever". */
    val label: String = "",
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
    val m0: Double = 1.0,
    val m1: Double = 0.0,
    val m2: Double = 0.0,
    val m3: Double = 0.0,
    val m4: Double = 1.0,
    val m5: Double = 0.0,
    val createdAt: Long,
    val updatedAt: Long,
    val active: Boolean = true,
) {
    /** Bounding box area in square degrees; only used to rank overlapping calibrations. */
    val areaDeg: Double get() = (east - west) * (north - south)

    /** Stable identity used by export / import to deduplicate across devices and re-imports. */
    val externalId: String get() = ExternalIds.calibration(createdAt, layerId)

    /** Anchor for the mapper extensions in `cz.hh.detektormapy.data.mapper`. */
    companion object
}
