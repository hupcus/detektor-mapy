package cz.hh.detektormapy.data.repository

import cz.hh.detektormapy.data.dao.DetectorDao
import cz.hh.detektormapy.data.entity.DetectorEntity
import cz.hh.detektormapy.data.entity.DetectorPresetEntity
import cz.hh.detektormapy.data.relation.DetectorWithPresets
import kotlinx.coroutines.flow.Flow

/** The user's own machines and the settings they wrote down for them. */
class DetectorRepository(private val detectorDao: DetectorDao) {

    fun observeDetectors(): Flow<List<DetectorEntity>> = detectorDao.observeDetectors()

    fun observeLibrary(): Flow<List<DetectorWithPresets>> = detectorDao.observeDetectorsWithPresets()

    fun observePresetsFor(detectorId: Long): Flow<List<DetectorPresetEntity>> =
        detectorDao.observePresetsFor(detectorId)

    suspend fun getDetector(id: Long): DetectorEntity? = detectorDao.getDetectorById(id)

    suspend fun getAllDetectors(): List<DetectorEntity> = detectorDao.getAllDetectors()

    suspend fun getPreset(id: Long): DetectorPresetEntity? = detectorDao.getPresetById(id)

    /** Inserts a machine; the very first one becomes the default so the library is never headless. */
    suspend fun addDetector(detector: DetectorEntity): Long {
        val first = detectorDao.countDetectors() == 0
        val id = detectorDao.insertDetector(detector.copy(isDefault = detector.isDefault || first))
        if (detector.isDefault || first) detectorDao.setDefaultDetector(id)
        return id
    }

    suspend fun updateDetector(detector: DetectorEntity) = detectorDao.updateDetector(detector)

    suspend fun deleteDetector(id: Long) = detectorDao.deleteDetectorById(id)

    suspend fun setDefaultDetector(id: Long) = detectorDao.setDefaultDetector(id)

    suspend fun addPreset(preset: DetectorPresetEntity): Long = detectorDao.insertPreset(preset)

    suspend fun updatePreset(preset: DetectorPresetEntity) = detectorDao.updatePreset(preset)

    suspend fun deletePreset(id: Long) = detectorDao.deletePresetById(id)

    suspend fun countDetectors(): Int = detectorDao.countDetectors()

    suspend fun countPresets(): Int = detectorDao.countPresets()
}
