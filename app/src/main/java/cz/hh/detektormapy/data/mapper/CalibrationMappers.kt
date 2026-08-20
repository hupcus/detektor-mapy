package cz.hh.detektormapy.data.mapper

import cz.hh.detektormapy.calibration.Affine2D
import cz.hh.detektormapy.data.entity.LayerCalibrationEntity
import cz.hh.detektormapy.util.BBox

/** The six affine coefficients of a calibration row, as a transform in Web Mercator metres. */
fun LayerCalibrationEntity.toAffine(): Affine2D = Affine2D(a = m0, b = m1, tx = m2, c = m3, d = m4, ty = m5)

/** The WGS84 box a calibration is valid for. */
fun LayerCalibrationEntity.toBBox(): BBox = BBox(west = west, south = south, east = east, north = north)

/**
 * Builds a calibration row out of the domain types the calibration screen works with.
 *
 * The transform is flattened into six real columns rather than a JSON blob so the
 * "smallest containing bbox wins" query can run in SQL and so a future coefficient change is an
 * ordinary column migration.
 */
fun layerCalibrationOf(
    layerId: String,
    label: String,
    bbox: BBox,
    transform: Affine2D,
    createdAt: Long,
    updatedAt: Long = createdAt,
    id: Long = 0L,
    active: Boolean = true,
): LayerCalibrationEntity {
    val m = transform.toFloatArray()
    return LayerCalibrationEntity(
        id = id,
        layerId = layerId,
        label = label,
        west = bbox.west,
        south = bbox.south,
        east = bbox.east,
        north = bbox.north,
        m0 = m[0],
        m1 = m[1],
        m2 = m[2],
        m3 = m[3],
        m4 = m[4],
        m5 = m[5],
        createdAt = createdAt,
        updatedAt = updatedAt,
        active = active,
    )
}

/** Alias of [layerCalibrationOf] reading as a factory on the entity itself. */
fun LayerCalibrationEntity.Companion.from(
    layerId: String,
    label: String,
    bbox: BBox,
    transform: Affine2D,
    createdAt: Long,
    updatedAt: Long = createdAt,
    id: Long = 0L,
    active: Boolean = true,
): LayerCalibrationEntity = layerCalibrationOf(
    layerId = layerId,
    label = label,
    bbox = bbox,
    transform = transform,
    createdAt = createdAt,
    updatedAt = updatedAt,
    id = id,
    active = active,
)

/** Returns a copy of the row carrying a new [transform], stamped with [updatedAt]. */
fun LayerCalibrationEntity.withTransform(transform: Affine2D, updatedAt: Long): LayerCalibrationEntity {
    val m = transform.toFloatArray()
    return copy(m0 = m[0], m1 = m[1], m2 = m[2], m3 = m[3], m4 = m[4], m5 = m[5], updatedAt = updatedAt)
}

/** Returns a copy of the row carrying a new [bbox], stamped with [updatedAt]. */
fun LayerCalibrationEntity.withBBox(bbox: BBox, updatedAt: Long): LayerCalibrationEntity = copy(
    west = bbox.west,
    south = bbox.south,
    east = bbox.east,
    north = bbox.north,
    updatedAt = updatedAt,
)
