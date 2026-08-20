package cz.hh.detektormapy.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import cz.hh.detektormapy.data.entity.TrackEntity
import cz.hh.detektormapy.data.relation.TrackWithPoints
import kotlinx.coroutines.flow.Flow

/** Aggregate numbers for the statistics screen. */
data class TrackStats(val total: Int, val totalDistanceM: Double, val totalDurationMs: Long)

/** Reads and writes for [TrackEntity]. */
@Dao
interface TrackDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(track: TrackEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(tracks: List<TrackEntity>): List<Long>

    @Update
    suspend fun update(track: TrackEntity)

    @Delete
    suspend fun delete(track: TrackEntity)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM tracks")
    suspend fun deleteAll()

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getById(id: Long): TrackEntity?

    @Query("SELECT * FROM tracks ORDER BY startedAt DESC")
    suspend fun getAll(): List<TrackEntity>

    @Query("SELECT * FROM tracks ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<TrackEntity>>

    /** The track currently being recorded, if any. */
    @Query("SELECT * FROM tracks WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    fun observeActive(): Flow<TrackEntity?>

    @Query("SELECT * FROM tracks WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun getActive(): TrackEntity?

    @Transaction
    @Query("SELECT * FROM tracks WHERE id = :id")
    fun observeWithPoints(id: Long): Flow<TrackWithPoints?>

    @Transaction
    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getWithPoints(id: Long): TrackWithPoints?

    @Transaction
    @Query("SELECT * FROM tracks ORDER BY startedAt DESC")
    suspend fun getAllWithPoints(): List<TrackWithPoints>

    @Query(
        """
        UPDATE tracks
        SET endedAt = :endedAt, durationMs = :durationMs, distanceM = :distanceM,
            pointCount = :pointCount, gpxPath = :gpxPath
        WHERE id = :id
        """,
    )
    suspend fun finish(id: Long, endedAt: Long, durationMs: Long, distanceM: Double, pointCount: Int, gpxPath: String?)

    @Query(
        """
        SELECT COUNT(*) AS total,
               COALESCE(SUM(distanceM), 0.0) AS totalDistanceM,
               COALESCE(SUM(durationMs), 0) AS totalDurationMs
        FROM tracks
        """,
    )
    fun observeStats(): Flow<TrackStats>

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun count(): Int
}
