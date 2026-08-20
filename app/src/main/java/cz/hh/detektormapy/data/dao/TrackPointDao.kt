package cz.hh.detektormapy.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cz.hh.detektormapy.data.entity.TrackPointEntity
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes for the live track point buffer. Rows die with their track via
 * `ON DELETE CASCADE`; they are normally kept until the track's GPX file has been written.
 */
@Dao
interface TrackPointDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(point: TrackPointEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(points: List<TrackPointEntity>): List<Long>

    @Query("SELECT * FROM track_points WHERE trackId = :trackId ORDER BY timestamp ASC")
    suspend fun getForTrack(trackId: Long): List<TrackPointEntity>

    @Query("SELECT * FROM track_points WHERE trackId = :trackId ORDER BY timestamp ASC")
    fun observeForTrack(trackId: Long): Flow<List<TrackPointEntity>>

    @Query("SELECT * FROM track_points WHERE trackId = :trackId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastForTrack(trackId: Long): TrackPointEntity?

    @Query("SELECT * FROM track_points ORDER BY trackId ASC, timestamp ASC")
    suspend fun getAll(): List<TrackPointEntity>

    @Query("DELETE FROM track_points WHERE trackId = :trackId")
    suspend fun deleteForTrack(trackId: Long)

    @Query("DELETE FROM track_points")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM track_points WHERE trackId = :trackId")
    suspend fun countForTrack(trackId: Long): Int

    @Query("SELECT COUNT(*) FROM track_points")
    suspend fun count(): Int
}
