package cz.hh.detektormapy.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import cz.hh.detektormapy.data.entity.DetectorEntity
import cz.hh.detektormapy.data.entity.DetectorPresetEntity
import cz.hh.detektormapy.data.relation.DetectorWithPresets
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes for the two detector tables.
 *
 * They live in one DAO because they are never used apart: a preset is meaningless without the
 * machine it belongs to, exactly like the GCP set / point pair.
 */
@Dao
interface DetectorDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDetector(detector: DetectorEntity): Long

    @Update
    suspend fun updateDetector(detector: DetectorEntity)

    @Delete
    suspend fun deleteDetector(detector: DetectorEntity)

    @Query("DELETE FROM detectors WHERE id = :id")
    suspend fun deleteDetectorById(id: Long)

    @Query("SELECT * FROM detectors WHERE id = :id")
    suspend fun getDetectorById(id: Long): DetectorEntity?

    @Query("SELECT * FROM detectors ORDER BY isDefault DESC, createdAt ASC")
    suspend fun getAllDetectors(): List<DetectorEntity>

    @Query("SELECT * FROM detectors ORDER BY isDefault DESC, createdAt ASC")
    fun observeDetectors(): Flow<List<DetectorEntity>>

    @Transaction
    @Query("SELECT * FROM detectors ORDER BY isDefault DESC, createdAt ASC")
    fun observeDetectorsWithPresets(): Flow<List<DetectorWithPresets>>

    @Query("SELECT COUNT(*) FROM detectors")
    suspend fun countDetectors(): Int

    @Query("UPDATE detectors SET isDefault = 0")
    suspend fun clearDefaultFlags()

    @Query("UPDATE detectors SET isDefault = 1 WHERE id = :id")
    suspend fun markDefault(id: Long)

    /**
     * Makes [id] the one default machine.
     *
     * Wrapped in a transaction because the two statements are only correct together: a crash
     * between them would leave the library with no default at all.
     */
    @Transaction
    suspend fun setDefaultDetector(id: Long) {
        clearDefaultFlags()
        markDefault(id)
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPreset(preset: DetectorPresetEntity): Long

    @Update
    suspend fun updatePreset(preset: DetectorPresetEntity)

    @Delete
    suspend fun deletePreset(preset: DetectorPresetEntity)

    @Query("DELETE FROM detector_presets WHERE id = :id")
    suspend fun deletePresetById(id: Long)

    @Query("SELECT * FROM detector_presets WHERE id = :id")
    suspend fun getPresetById(id: Long): DetectorPresetEntity?

    @Query("SELECT * FROM detector_presets WHERE detectorId = :detectorId ORDER BY createdAt ASC")
    fun observePresetsFor(detectorId: Long): Flow<List<DetectorPresetEntity>>

    @Query("SELECT * FROM detector_presets ORDER BY createdAt ASC")
    fun observeAllPresets(): Flow<List<DetectorPresetEntity>>

    @Query("SELECT COUNT(*) FROM detector_presets")
    suspend fun countPresets(): Int
}
