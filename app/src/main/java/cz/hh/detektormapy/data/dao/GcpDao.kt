package cz.hh.detektormapy.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import cz.hh.detektormapy.data.entity.GcpPointEntity
import cz.hh.detektormapy.data.entity.GcpSetEntity
import cz.hh.detektormapy.data.relation.GcpSetWithPoints
import kotlinx.coroutines.flow.Flow

/** Reads and writes for the GCP editor tables. */
@Dao
interface GcpDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSet(set: GcpSetEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSets(sets: List<GcpSetEntity>): List<Long>

    @Update
    suspend fun updateSet(set: GcpSetEntity)

    @Delete
    suspend fun deleteSet(set: GcpSetEntity)

    @Query("DELETE FROM gcp_sets WHERE id = :id")
    suspend fun deleteSetById(id: Long)

    @Query("DELETE FROM gcp_sets")
    suspend fun deleteAllSets()

    @Query("SELECT * FROM gcp_sets WHERE id = :id")
    suspend fun getSetById(id: Long): GcpSetEntity?

    @Query("SELECT * FROM gcp_sets ORDER BY createdAt DESC")
    suspend fun getAllSets(): List<GcpSetEntity>

    @Query("SELECT * FROM gcp_sets ORDER BY createdAt DESC")
    fun observeAllSets(): Flow<List<GcpSetEntity>>

    @Query("SELECT * FROM gcp_sets WHERE layerId = :layerId ORDER BY createdAt DESC")
    fun observeSetsForLayer(layerId: String): Flow<List<GcpSetEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPoint(point: GcpPointEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPoints(points: List<GcpPointEntity>): List<Long>

    @Update
    suspend fun updatePoint(point: GcpPointEntity)

    @Delete
    suspend fun deletePoint(point: GcpPointEntity)

    @Query("DELETE FROM gcp_points WHERE id = :id")
    suspend fun deletePointById(id: Long)

    @Query("DELETE FROM gcp_points WHERE setId = :setId")
    suspend fun deletePointsForSet(setId: Long)

    @Query("SELECT * FROM gcp_points WHERE setId = :setId ORDER BY id ASC")
    fun observePointsForSet(setId: Long): Flow<List<GcpPointEntity>>

    @Query("SELECT * FROM gcp_points WHERE setId = :setId ORDER BY id ASC")
    suspend fun getPointsForSet(setId: Long): List<GcpPointEntity>

    @Query("SELECT * FROM gcp_points ORDER BY setId ASC, id ASC")
    suspend fun getAllPoints(): List<GcpPointEntity>

    @Transaction
    @Query("SELECT * FROM gcp_sets WHERE id = :id")
    fun observeSetWithPoints(id: Long): Flow<GcpSetWithPoints?>

    @Transaction
    @Query("SELECT * FROM gcp_sets WHERE id = :id")
    suspend fun getSetWithPoints(id: Long): GcpSetWithPoints?

    @Query("SELECT COUNT(*) FROM gcp_sets")
    suspend fun countSets(): Int

    @Query("SELECT COUNT(*) FROM gcp_points")
    suspend fun countPoints(): Int
}
