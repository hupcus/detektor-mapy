package cz.hh.detektormapy.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import cz.hh.detektormapy.data.entity.PlaceEntity
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes for [PlaceEntity].
 *
 * The list is ordered by `createdAt` rather than by distance: SQLite has no spatial index and
 * distance depends on a position that changes every second, so sorting by distance belongs in the
 * view model, not here.
 */
@Dao
interface PlaceDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(place: PlaceEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(places: List<PlaceEntity>): List<Long>

    @Update
    suspend fun update(place: PlaceEntity)

    @Delete
    suspend fun delete(place: PlaceEntity)

    @Query("DELETE FROM places WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM places")
    suspend fun deleteAll()

    @Query("SELECT * FROM places WHERE id = :id")
    suspend fun getById(id: Long): PlaceEntity?

    @Query("SELECT * FROM places ORDER BY createdAt DESC")
    suspend fun getAll(): List<PlaceEntity>

    @Query("SELECT * FROM places ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<PlaceEntity>>

    @Query("SELECT * FROM places WHERE id = :id")
    fun observeById(id: Long): Flow<PlaceEntity?>

    @Query(
        """
        SELECT * FROM places
        WHERE (:ignoreTypes = 1 OR type IN (:types))
          AND (:unvisitedOnly = 0 OR visited = 0)
        ORDER BY createdAt DESC
        """,
    )
    fun observeFiltered(types: List<String>, ignoreTypes: Boolean, unvisitedOnly: Boolean): Flow<List<PlaceEntity>>

    @Query(
        """
        SELECT * FROM places
        WHERE lat BETWEEN :south AND :north AND lon BETWEEN :west AND :east
        ORDER BY createdAt DESC
        """,
    )
    fun observeInBBox(west: Double, south: Double, east: Double, north: Double): Flow<List<PlaceEntity>>

    @Query("UPDATE places SET visited = :visited, visitedAt = :visitedAt WHERE id = :id")
    suspend fun setVisited(id: Long, visited: Boolean, visitedAt: Long?)

    @Query("SELECT COUNT(*) FROM places")
    suspend fun count(): Int
}
