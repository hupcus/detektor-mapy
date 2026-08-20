package cz.hh.detektormapy.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import cz.hh.detektormapy.data.entity.SearchedAreaEntity
import kotlinx.coroutines.flow.Flow

/** Reads and writes for [SearchedAreaEntity]. */
@Dao
interface SearchedAreaDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(area: SearchedAreaEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(areas: List<SearchedAreaEntity>): List<Long>

    @Update
    suspend fun update(area: SearchedAreaEntity)

    @Delete
    suspend fun delete(area: SearchedAreaEntity)

    @Query("DELETE FROM searched_areas WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM searched_areas")
    suspend fun deleteAll()

    @Query("SELECT * FROM searched_areas WHERE id = :id")
    suspend fun getById(id: Long): SearchedAreaEntity?

    @Query("SELECT * FROM searched_areas ORDER BY createdAt DESC")
    suspend fun getAll(): List<SearchedAreaEntity>

    @Query("SELECT * FROM searched_areas ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SearchedAreaEntity>>

    @Query("SELECT * FROM searched_areas WHERE status = :status ORDER BY createdAt DESC")
    fun observeByStatus(status: String): Flow<List<SearchedAreaEntity>>

    @Query("UPDATE searched_areas SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: String)

    @Query("SELECT COALESCE(SUM(areaHa), 0.0) FROM searched_areas WHERE status = :status")
    fun observeTotalAreaHa(status: String): Flow<Double>

    @Query("SELECT COUNT(*) FROM searched_areas")
    suspend fun count(): Int
}
