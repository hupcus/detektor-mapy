package cz.hh.detektormapy.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cz.hh.detektormapy.data.entity.FindPhotoEntity
import kotlinx.coroutines.flow.Flow

/** Reads and writes for [FindPhotoEntity]. Rows die with their find via `ON DELETE CASCADE`. */
@Dao
interface FindPhotoDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(photo: FindPhotoEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(photos: List<FindPhotoEntity>): List<Long>

    @Delete
    suspend fun delete(photo: FindPhotoEntity)

    @Query("DELETE FROM find_photos WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM find_photos WHERE findId = :findId")
    suspend fun deleteForFind(findId: Long)

    @Query("SELECT * FROM find_photos WHERE findId = :findId ORDER BY isPrimary DESC, createdAt ASC")
    fun observeForFind(findId: Long): Flow<List<FindPhotoEntity>>

    @Query("SELECT * FROM find_photos WHERE findId = :findId ORDER BY isPrimary DESC, createdAt ASC")
    suspend fun getForFind(findId: Long): List<FindPhotoEntity>

    @Query("SELECT * FROM find_photos ORDER BY createdAt ASC")
    suspend fun getAll(): List<FindPhotoEntity>

    /** Makes [photoId] the primary photo of [findId] and demotes every sibling. */
    @Query("UPDATE find_photos SET isPrimary = (id = :photoId) WHERE findId = :findId")
    suspend fun setPrimary(findId: Long, photoId: Long)

    @Query("SELECT COUNT(*) FROM find_photos")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM find_photos WHERE findId = :findId")
    suspend fun countForFind(findId: Long): Int
}
