package cz.hh.detektormapy.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import cz.hh.detektormapy.data.entity.LayerCalibrationEntity
import kotlinx.coroutines.flow.Flow

/** Reads and writes for [LayerCalibrationEntity]. */
@Dao
interface LayerCalibrationDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(calibration: LayerCalibrationEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(calibrations: List<LayerCalibrationEntity>): List<Long>

    @Update
    suspend fun update(calibration: LayerCalibrationEntity)

    @Delete
    suspend fun delete(calibration: LayerCalibrationEntity)

    @Query("DELETE FROM layer_calibrations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM layer_calibrations")
    suspend fun deleteAll()

    @Query("SELECT * FROM layer_calibrations WHERE id = :id")
    suspend fun getById(id: Long): LayerCalibrationEntity?

    @Query("SELECT * FROM layer_calibrations ORDER BY layerId ASC, createdAt DESC")
    suspend fun getAll(): List<LayerCalibrationEntity>

    @Query("SELECT * FROM layer_calibrations ORDER BY layerId ASC, createdAt DESC")
    fun observeAll(): Flow<List<LayerCalibrationEntity>>

    @Query("SELECT * FROM layer_calibrations WHERE layerId = :layerId ORDER BY createdAt DESC")
    fun observeForLayer(layerId: String): Flow<List<LayerCalibrationEntity>>

    @Query("SELECT * FROM layer_calibrations WHERE layerId = :layerId ORDER BY createdAt DESC")
    suspend fun getForLayer(layerId: String): List<LayerCalibrationEntity>

    /**
     * "Nearest calibration wins" (PLAN.md section 6): every active calibration of [layerId] whose
     * bounding box contains the point, tightest box first. The tightest box is the one the user
     * tuned for the smallest area, so it is the most specific correction available.
     */
    @Query(
        """
        SELECT * FROM layer_calibrations
        WHERE layerId = :layerId
          AND active = 1
          AND :lat BETWEEN south AND north
          AND :lon BETWEEN west AND east
        ORDER BY ((east - west) * (north - south)) ASC, updatedAt DESC
        """,
    )
    fun observeContaining(layerId: String, lat: Double, lon: Double): Flow<List<LayerCalibrationEntity>>

    /** The single best calibration for a point, or null when the point sits outside every box. */
    @Query(
        """
        SELECT * FROM layer_calibrations
        WHERE layerId = :layerId
          AND active = 1
          AND :lat BETWEEN south AND north
          AND :lon BETWEEN west AND east
        ORDER BY ((east - west) * (north - south)) ASC, updatedAt DESC
        LIMIT 1
        """,
    )
    fun observeBestFor(layerId: String, lat: Double, lon: Double): Flow<LayerCalibrationEntity?>

    @Query(
        """
        SELECT * FROM layer_calibrations
        WHERE layerId = :layerId
          AND active = 1
          AND :lat BETWEEN south AND north
          AND :lon BETWEEN west AND east
        ORDER BY ((east - west) * (north - south)) ASC, updatedAt DESC
        LIMIT 1
        """,
    )
    suspend fun getBestFor(layerId: String, lat: Double, lon: Double): LayerCalibrationEntity?

    /** Calibrations whose box overlaps a viewport, used to draw the "kalibrováno" indicator. */
    @Query(
        """
        SELECT * FROM layer_calibrations
        WHERE layerId = :layerId
          AND active = 1
          AND west <= :east AND east >= :west
          AND south <= :north AND north >= :south
        ORDER BY ((east - west) * (north - south)) ASC
        """,
    )
    fun observeIntersecting(
        layerId: String,
        west: Double,
        south: Double,
        east: Double,
        north: Double,
    ): Flow<List<LayerCalibrationEntity>>

    @Query("UPDATE layer_calibrations SET active = :active, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setActive(id: Long, active: Boolean, updatedAt: Long)

    @Query("UPDATE layer_calibrations SET label = :label, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: Long, label: String, updatedAt: Long)

    @Query("SELECT COUNT(*) FROM layer_calibrations")
    suspend fun count(): Int
}
