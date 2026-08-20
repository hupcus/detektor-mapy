package cz.hh.detektormapy.data.repository

import cz.hh.detektormapy.calibration.Affine2D
import cz.hh.detektormapy.data.dao.LayerCalibrationDao
import cz.hh.detektormapy.data.entity.LayerCalibrationEntity
import cz.hh.detektormapy.data.mapper.layerCalibrationOf
import cz.hh.detektormapy.data.mapper.toAffine
import cz.hh.detektormapy.data.mapper.withBBox
import cz.hh.detektormapy.data.mapper.withTransform
import cz.hh.detektormapy.util.BBox
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Per-region georeference corrections of overlay layers (PLAN.md section 6, mode A). */
class CalibrationRepository(private val dao: LayerCalibrationDao) {

    fun observeAll(): Flow<List<LayerCalibrationEntity>> = dao.observeAll()

    fun observeForLayer(layerId: String): Flow<List<LayerCalibrationEntity>> = dao.observeForLayer(layerId)

    fun observeIntersecting(layerId: String, bbox: BBox): Flow<List<LayerCalibrationEntity>> =
        dao.observeIntersecting(layerId, bbox.west, bbox.south, bbox.east, bbox.north)

    /**
     * "Nearest calibration wins": the active calibration of [layerId] with the smallest bounding
     * box that still contains the point, or null when the point sits outside every box.
     */
    fun bestCalibrationFor(layerId: String, lat: Double, lon: Double): Flow<LayerCalibrationEntity?> =
        dao.observeBestFor(layerId, lat, lon)

    /** The same rule, already reduced to the transform the tile server needs. */
    fun bestTransformFor(layerId: String, lat: Double, lon: Double): Flow<Affine2D> =
        bestCalibrationFor(layerId, lat, lon).map { it?.toAffine() ?: Affine2D.IDENTITY }

    suspend fun getBestCalibrationFor(layerId: String, lat: Double, lon: Double): LayerCalibrationEntity? =
        dao.getBestFor(layerId, lat, lon)

    suspend fun get(id: Long): LayerCalibrationEntity? = dao.getById(id)

    suspend fun getAll(): List<LayerCalibrationEntity> = dao.getAll()

    suspend fun getForLayer(layerId: String): List<LayerCalibrationEntity> = dao.getForLayer(layerId)

    /** Saves the current gesture result as a calibration valid for [bbox]. */
    suspend fun save(layerId: String, label: String, bbox: BBox, transform: Affine2D, nowMillis: Long): Long =
        dao.insert(
            layerCalibrationOf(
                layerId = layerId,
                label = label,
                bbox = bbox,
                transform = transform,
                createdAt = nowMillis,
            ),
        )

    suspend fun updateTransform(id: Long, transform: Affine2D, nowMillis: Long) {
        val existing = dao.getById(id) ?: return
        dao.update(existing.withTransform(transform, nowMillis))
    }

    suspend fun updateBBox(id: Long, bbox: BBox, nowMillis: Long) {
        val existing = dao.getById(id) ?: return
        dao.update(existing.withBBox(bbox, nowMillis))
    }

    suspend fun rename(id: Long, label: String, nowMillis: Long) = dao.rename(id, label, nowMillis)

    suspend fun setActive(id: Long, active: Boolean, nowMillis: Long) = dao.setActive(id, active, nowMillis)

    suspend fun delete(id: Long) = dao.deleteById(id)

    suspend fun count(): Int = dao.count()
}
