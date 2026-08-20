package cz.hh.detektormapy.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import cz.hh.detektormapy.data.entity.FindEntity
import cz.hh.detektormapy.data.relation.FindWithPhotos
import kotlinx.coroutines.flow.Flow

/**
 * Aggregate numbers for the statistics screen. Every column is coalesced so the query still
 * returns a usable row on an empty table.
 */
data class FindStats(
    val total: Int,
    val favorites: Int,
    val firstCreatedAt: Long?,
    val lastCreatedAt: Long?,
    val deepestCm: Int?,
)

/** One row of the "finds per category" histogram. */
data class CategoryCount(val category: String, val count: Int)

/** Reads and writes for [FindEntity]. Observable reads return `Flow`, writes are `suspend`. */
@Dao
interface FindDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(find: FindEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(finds: List<FindEntity>): List<Long>

    @Update
    suspend fun update(find: FindEntity)

    @Delete
    suspend fun delete(find: FindEntity)

    @Query("DELETE FROM finds WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM finds")
    suspend fun deleteAll()

    @Query("SELECT * FROM finds WHERE id = :id")
    suspend fun getById(id: Long): FindEntity?

    @Query("SELECT * FROM finds ORDER BY createdAt DESC")
    suspend fun getAll(): List<FindEntity>

    @Query("SELECT * FROM finds ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<FindEntity>>

    @Query("SELECT * FROM finds WHERE id = :id")
    fun observeById(id: Long): Flow<FindEntity?>

    /**
     * The gallery filter (PLAN.md F2-3). [ignoreCategories] short-circuits the `IN` clause so an
     * empty selection means "all categories" rather than "nothing".
     */
    @Query(
        """
        SELECT * FROM finds
        WHERE (:ignoreCategories = 1 OR category IN (:categories))
          AND createdAt >= :fromMillis
          AND createdAt <= :toMillis
          AND (:favoriteOnly = 0 OR favorite = 1)
        ORDER BY createdAt DESC
        """,
    )
    fun observeFiltered(
        categories: List<String>,
        ignoreCategories: Boolean,
        fromMillis: Long,
        toMillis: Long,
        favoriteOnly: Boolean,
    ): Flow<List<FindEntity>>

    /** Everything inside a map viewport, so the map only ever loads the pins it can draw. */
    @Query(
        """
        SELECT * FROM finds
        WHERE lat BETWEEN :south AND :north AND lon BETWEEN :west AND :east
        ORDER BY createdAt DESC
        """,
    )
    fun observeInBBox(west: Double, south: Double, east: Double, north: Double): Flow<List<FindEntity>>

    @Query(
        """
        SELECT * FROM finds
        WHERE lat BETWEEN :south AND :north AND lon BETWEEN :west AND :east
        ORDER BY createdAt DESC
        """,
    )
    suspend fun getInBBox(west: Double, south: Double, east: Double, north: Double): List<FindEntity>

    @Transaction
    @Query("SELECT * FROM finds WHERE id = :id")
    fun observeWithPhotos(id: Long): Flow<FindWithPhotos?>

    @Transaction
    @Query("SELECT * FROM finds WHERE id = :id")
    suspend fun getWithPhotos(id: Long): FindWithPhotos?

    @Transaction
    @Query("SELECT * FROM finds ORDER BY createdAt DESC")
    fun observeAllWithPhotos(): Flow<List<FindWithPhotos>>

    @Transaction
    @Query("SELECT * FROM finds ORDER BY createdAt DESC")
    suspend fun getAllWithPhotos(): List<FindWithPhotos>

    @Query("UPDATE finds SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query(
        """
        SELECT COUNT(*) AS total,
               COALESCE(SUM(CASE WHEN favorite = 1 THEN 1 ELSE 0 END), 0) AS favorites,
               MIN(createdAt) AS firstCreatedAt,
               MAX(createdAt) AS lastCreatedAt,
               MAX(depthCm) AS deepestCm
        FROM finds
        """,
    )
    fun observeStats(): Flow<FindStats>

    @Query("SELECT category AS category, COUNT(*) AS count FROM finds GROUP BY category ORDER BY count DESC")
    fun observeCountsByCategory(): Flow<List<CategoryCount>>

    @Query("SELECT COUNT(*) FROM finds")
    suspend fun count(): Int
}
